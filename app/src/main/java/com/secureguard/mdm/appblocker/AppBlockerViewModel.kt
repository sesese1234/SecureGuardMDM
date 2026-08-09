package com.secureguard.mdm.appblocker

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.R
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import com.secureguard.mdm.data.db.BlockedAppCache
import com.secureguard.mdm.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

private const val TAG = "AppBlockerViewModel"
private const val OWN_PACKAGE = "com.secureguard.mdm"

// Critical system apps that should not be blocked as they may cause device instability
private val CRITICAL_SYSTEM_PACKAGES = setOf(
    "com.android.settings",           // Settings app
    "com.android.systemui",          // System UI
    "android",                       // Android System
    "com.google.android.gsf",         // Google Services Framework
    "com.android.launcher3",          // Default launcher
    "com.google.android.launcher",    // Google launcher
)

@HiltViewModel
class AppBlockerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val dpm: DevicePolicyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppBlockerUiState())
    val uiState = _uiState.asStateFlow()

    private var allAppsMasterList: List<AppInfo> = emptyList()
    private var allBlockedAppsMasterList: List<AppInfo> = emptyList()
    private var allSuspendedAppsMasterList: List<AppInfo> = emptyList()

    private val adminComponentName by lazy { SecureGuardDeviceAdminReceiver.getComponentName(context) }
    private val iconsDir by lazy { File(context.filesDir, "app_icons").apply { mkdirs() } }

    init {
        loadAllData()
    }

    fun onEvent(event: AppBlockerEvent) {
        when (event) {
            is AppBlockerEvent.OnFilterChanged -> onFilterChanged(event.newFilter)
            is AppBlockerEvent.OnAppSelectionChanged -> onAppSelectionChanged(event.packageName, event.isBlocked)
            is AppBlockerEvent.OnAppSuspensionChanged -> onAppSuspensionChanged(event.packageName, event.isSuspended)
            is AppBlockerEvent.OnAppDeselected -> onAppDeselected(event.packageName)
            is AppBlockerEvent.OnSaveRequest -> saveAndApplyChanges() // קריאה ישירה
            is AppBlockerEvent.OnUnblockSelectedRequest -> unblockSelectedApps() // קריאה ישירה
            is AppBlockerEvent.OnUnsuspendSelectedRequest -> unsuspendSelectedApps() // קריאה ישירה
            is AppBlockerEvent.OnAddPackageManually -> addPackageManually(event.packageName)
            is AppBlockerEvent.OnToggleUnblockSelection -> toggleUnblockSelection(event.packageName)
            is AppBlockerEvent.OnToggleUnsuspendSelection -> toggleUnsuspendSelection(event.packageName)
            is AppBlockerEvent.OnSearchQueryChanged -> onSearchQueryChanged(event.query)
            is AppBlockerEvent.OnDismissPasswordPrompt -> { /* No longer needed */ }
            is AppBlockerEvent.OnDismissCriticalAppsWarning -> dismissCriticalAppsWarning()
        }
    }

    fun loadAllData() {
        // A refresh must not clear the search box or swap the list out for a spinner:
        // both rebuild the LazyColumn from scratch and throw the user back to the top
        // of a list that can be hundreds of apps long.
        refreshData(showSpinner = _uiState.value.displayedAppsForSelection.isEmpty())
    }

    private fun refreshData(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _uiState.update { it.copy(isLoading = true) }
            allAppsMasterList = getInstalledApps()
            allBlockedAppsMasterList = getBlockedAppsFromCache()
            allSuspendedAppsMasterList = getSuspendedAppsFromCache()
            applyFilter()
            applyBlockedAppsFilter()
            applySuspendedAppsFilter()
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun onFilterChanged(newFilter: AppFilterType) {
        if (newFilter != _uiState.value.currentFilter) {
            _uiState.update { it.copy(currentFilter = newFilter) }
            applyFilter()
        }
    }

    private fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFilter()
        applyBlockedAppsFilter()
        applySuspendedAppsFilter()
    }

    private fun applyFilter() {
        val query = _uiState.value.searchQuery.lowercase()
        var filteredList = when (_uiState.value.currentFilter) {
            AppFilterType.USER_ONLY -> allAppsMasterList.filter { it.isInstalled && !it.isSystemApp }
            AppFilterType.ALL_EXCEPT_CORE -> allAppsMasterList.filter { it.isInstalled && !it.packageName.startsWith("com.android.") }
            AppFilterType.LAUNCHER_ONLY -> allAppsMasterList.filter { it.isLauncherApp }
            AppFilterType.ALL -> allAppsMasterList.filter { it.isInstalled }
        }
        if (query.isNotBlank()) {
            filteredList = filteredList.filter {
                it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
        _uiState.update { it.copy(displayedAppsForSelection = filteredList) }
    }

    private fun applyBlockedAppsFilter() {
        val query = _uiState.value.searchQuery.lowercase()
        val filteredList = if(query.isNotBlank()) {
            allBlockedAppsMasterList.filter {
                it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        } else {
            allBlockedAppsMasterList
        }
        _uiState.update { it.copy(displayedBlockedApps = filteredList) }
    }

    private fun applySuspendedAppsFilter() {
        val query = _uiState.value.searchQuery.lowercase()
        val filteredList = if (query.isNotBlank()) {
            allSuspendedAppsMasterList.filter {
                it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        } else {
            allSuspendedAppsMasterList
        }
        _uiState.update { it.copy(displayedSuspendedApps = filteredList) }
    }

    private suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherPackages = pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.packageName }.toSet()

        val allInstalledAppsInfo = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val blockedAppPackages = settingsRepository.getBlockedAppPackages()
        val suspendedAppPackages = settingsRepository.getSuspendedAppPackages()
        allInstalledAppsInfo.map { appInfo ->
            if (appInfo.packageName == OWN_PACKAGE) return@map null
            AppInfo(
                appName = appInfo.loadLabel(pm).toString(), packageName = appInfo.packageName,
                icon = appInfo.loadIcon(pm), isBlocked = blockedAppPackages.contains(appInfo.packageName),
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isLauncherApp = launcherPackages.contains(appInfo.packageName),
                isSuspended = suspendedAppPackages.contains(appInfo.packageName),
                isInstalled = true
            )
        }.filterNotNull().sortedBy { it.appName.lowercase() }
    }

    private suspend fun getBlockedAppsFromCache(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherPackages = pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.packageName }.toSet()

        val blockedPackages = settingsRepository.getBlockedAppPackages()
        val suspendedPackages = settingsRepository.getSuspendedAppPackages()
        val cachedApps = settingsRepository.getBlockedAppsCache().associateBy { it.packageName }
        blockedPackages.mapNotNull { packageName ->
            try {
                val appInfo = try {
                    pm.getApplicationInfo(packageName, 0)
                } catch (e: Exception) { null }

                val cachedInfo = cachedApps[packageName]
                AppInfo(
                    appName = appInfo?.loadLabel(pm)?.toString() ?: cachedInfo?.appName ?: packageName,
                    packageName = packageName,
                    icon = cachedInfo?.let { loadIconFromFile(it.iconPath) } ?: appInfo?.loadIcon(pm) ?: createDefaultIcon(),
                    isBlocked = true,
                    isSystemApp = appInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false,
                    isLauncherApp = launcherPackages.contains(packageName),
                    isSuspended = suspendedPackages.contains(packageName),
                    isInstalled = appInfo != null
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not fully resolve app info for $packageName", e)
                null
            }
        }.sortedBy { it.appName.lowercase() }
    }

    private suspend fun getSuspendedAppsFromCache(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherPackages = pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.packageName }.toSet()

        val suspendedPackages = settingsRepository.getSuspendedAppPackages()
        val cachedApps = settingsRepository.getBlockedAppsCache().associateBy { it.packageName }
        suspendedPackages.mapNotNull { packageName ->
            try {
                val appInfo = try {
                    pm.getApplicationInfo(packageName, 0)
                } catch (e: Exception) { null }

                val cachedInfo = cachedApps[packageName]
                AppInfo(
                    appName = appInfo?.loadLabel(pm)?.toString() ?: cachedInfo?.appName ?: packageName,
                    packageName = packageName,
                    icon = cachedInfo?.let { loadIconFromFile(it.iconPath) } ?: appInfo?.loadIcon(pm) ?: createDefaultIcon(),
                    isBlocked = false,
                    isSystemApp = appInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false,
                    isLauncherApp = launcherPackages.contains(packageName),
                    isSuspended = true,
                    isInstalled = appInfo != null
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not fully resolve app info for $packageName", e)
                null
            }
        }.sortedBy { it.appName.lowercase() }
    }

    private fun onAppSelectionChanged(packageName: String, isBlocked: Boolean) {
        allAppsMasterList = allAppsMasterList.map {
            if (it.packageName == packageName) it.copy(isBlocked = isBlocked, isSuspended = if (isBlocked) false else it.isSuspended) else it
        }
        applyFilter()
    }

    private fun onAppDeselected(packageName: String) {
        allAppsMasterList = allAppsMasterList.map {
            if (it.packageName == packageName) it.copy(isBlocked = false, isSuspended = false) else it
        }
        applyFilter()
    }

    private fun onAppSuspensionChanged(packageName: String, isSuspended: Boolean) {
        if (isSuspended && CRITICAL_SYSTEM_PACKAGES.contains(packageName)) {
            val criticalAppInfos = allAppsMasterList.filter { it.packageName == packageName }
            _uiState.update { it.copy(showCriticalAppsWarning = true, criticalAppsDetected = criticalAppInfos) }
            return
        }
        allAppsMasterList = allAppsMasterList.map {
            if (it.packageName == packageName) it.copy(isSuspended = isSuspended, isBlocked = if (isSuspended) false else it.isBlocked) else it
        }
        applyFilter()
    }

    private fun saveAndApplyChanges() {
        viewModelScope.launch(Dispatchers.IO) {
            val oldBlockedSet = settingsRepository.getBlockedAppPackages()
            val oldSuspendedSet = settingsRepository.getSuspendedAppPackages()

            // The master list is the source of truth for every package it covers, so
            // clearing a checkbox actually removes the app. Packages the list does not
            // cover (e.g. a blocked app that has since been uninstalled) are carried
            // over untouched instead of being silently dropped.
            val knownPackages = allAppsMasterList.map { it.packageName }.toSet()
            val selectedBlocked = allAppsMasterList.filter { it.isBlocked }.map { it.packageName }.toSet()
            val selectedSuspended = allAppsMasterList.filter { it.isSuspended }.map { it.packageName }.toSet()

            val finalBlockedSet = ((oldBlockedSet - knownPackages) + selectedBlocked) - OWN_PACKAGE
            val finalSuspendedSet =
                ((((oldSuspendedSet - knownPackages) + selectedSuspended) - finalBlockedSet)) - OWN_PACKAGE

            if (finalBlockedSet == oldBlockedSet && finalSuspendedSet == oldSuspendedSet) {
                Log.d(TAG, "No changes to save.")
                return@launch
            }

            val blockedToAdd = finalBlockedSet - oldBlockedSet
            val blockedToRemove = oldBlockedSet - finalBlockedSet
            val suspendedToAdd = finalSuspendedSet - oldSuspendedSet
            val suspendedToRemove = oldSuspendedSet - finalSuspendedSet

            // Check for critical system apps (block/suspend)
            val criticalApps = (blockedToAdd + suspendedToAdd).filter { CRITICAL_SYSTEM_PACKAGES.contains(it) }
            if (criticalApps.isNotEmpty()) {
                val criticalAppInfos = allAppsMasterList.filter { criticalApps.contains(it.packageName) }
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(showCriticalAppsWarning = true, criticalAppsDetected = criticalAppInfos) }
                }
                return@launch
            }

            settingsRepository.setBlockedAppPackages(finalBlockedSet)
            settingsRepository.setSuspendedAppPackages(finalSuspendedSet)

            // Only evict apps that are no longer protected at all — an app moving from
            // suspended to blocked appears in both "removed" and "added" sets and must
            // keep its cached name and icon.
            val noLongerProtected =
                (blockedToRemove + suspendedToRemove) - finalBlockedSet - finalSuspendedSet
            removeAppsFromCache(noLongerProtected.toList())

            val newlyProtected = blockedToAdd + suspendedToAdd
            allAppsMasterList.filter { newlyProtected.contains(it.packageName) }.forEach { cacheAppInfo(it) }

            blockedToRemove.forEach { packageName ->
                try {
                    dpm.setApplicationHidden(adminComponentName, packageName, false)
                    Log.d(TAG, "Set hidden state for $packageName to false (deselected)")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not unhide $packageName", e)
                }
            }

            suspendedToRemove.forEach { packageName ->
                setPackagesSuspendedSafely(packageName, false)
            }

            blockedToAdd.forEach { packageName ->
                try {
                    dpm.setApplicationHidden(adminComponentName, packageName, true)
                    Log.d(TAG, "Set hidden state for $packageName to true")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not change hidden state for $packageName", e)
                }
            }

            suspendedToAdd.forEach { packageName ->
                setPackagesSuspendedSafely(packageName, true)
            }

            withContext(Dispatchers.Main) { refreshData(showSpinner = false) }
        }
    }

    /**
     * setPackagesSuspended is API 24+; minSdk is 22, and a missing method raises
     * NoSuchMethodError rather than an Exception, so the version has to be checked.
     */
    private fun setPackagesSuspendedSafely(packageName: String, suspended: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            dpm.setPackagesSuspended(adminComponentName, arrayOf(packageName), suspended)
            Log.d(TAG, "Set suspended state for $packageName to $suspended")
        } catch (e: Exception) {
            Log.w(TAG, "Could not change suspended state for $packageName", e)
        }
    }

    private fun toggleUnblockSelection(packageName: String) {
        val currentSelection = _uiState.value.selectionForUnblock.toMutableSet()
        if (currentSelection.contains(packageName)) currentSelection.remove(packageName) else currentSelection.add(packageName)
        _uiState.update { it.copy(selectionForUnblock = currentSelection) }
    }

    private fun toggleUnsuspendSelection(packageName: String) {
        val currentSelection = _uiState.value.selectionForUnsuspend.toMutableSet()
        if (currentSelection.contains(packageName)) currentSelection.remove(packageName) else currentSelection.add(packageName)
        _uiState.update { it.copy(selectionForUnsuspend = currentSelection) }
    }

    private fun unblockSelectedApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val packagesToUnblock = _uiState.value.selectionForUnblock
            if (packagesToUnblock.isEmpty()) return@launch
            val currentBlocked = settingsRepository.getBlockedAppPackages().toMutableSet()
            currentBlocked.removeAll(packagesToUnblock)
            settingsRepository.setBlockedAppPackages(currentBlocked)
            removeAppsFromCache(packagesToUnblock.toList())
            packagesToUnblock.forEach { packageName ->
                try {
                    dpm.setApplicationHidden(adminComponentName, packageName, false)
                    Log.d(TAG, "Set hidden state for $packageName to false (unblocked)")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not unhide $packageName", e)
                }
            }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(selectionForUnblock = emptySet()) }
                loadAllData()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun unsuspendSelectedApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val packagesToUnsuspend = _uiState.value.selectionForUnsuspend
            if (packagesToUnsuspend.isEmpty()) return@launch
            val currentSuspended = settingsRepository.getSuspendedAppPackages().toMutableSet()
            currentSuspended.removeAll(packagesToUnsuspend)
            settingsRepository.setSuspendedAppPackages(currentSuspended)
            removeAppsFromCache(packagesToUnsuspend.toList())
            packagesToUnsuspend.forEach { packageName ->
                try {
                    dpm.setPackagesSuspended(adminComponentName, arrayOf(packageName), false)
                    Log.d(TAG, "Set suspended state for $packageName to false (unsuspend)")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not unsuspend $packageName", e)
                }
            }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(selectionForUnsuspend = emptySet()) }
                loadAllData()
            }
        }
    }

    fun addPackageManually(packageName: String): String? {
        if (packageName.isBlank()) return context.getString(R.string.app_blocker_error_empty_package)
        if (packageName == OWN_PACKAGE) return context.getString(R.string.app_blocker_error_own_package)

        // Check if it's a critical system app
        if (CRITICAL_SYSTEM_PACKAGES.contains(packageName)) {
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val criticalApp = AppInfo(
                    appName = appInfo.loadLabel(pm).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(pm),
                    isBlocked = false,
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isLauncherApp = false,
                    isSuspended = false,
                    isInstalled = true
                )
                _uiState.update { it.copy(showCriticalAppsWarning = true, criticalAppsDetected = listOf(criticalApp)) }
            } catch (e: PackageManager.NameNotFoundException) {
                return context.getString(R.string.app_blocker_error_package_not_found, packageName)
            }
            return null // Don't show error, show warning instead
        }

        viewModelScope.launch {
            val currentlyBlockedPackages = settingsRepository.getBlockedAppPackages()
            val currentlySuspendedPackages = settingsRepository.getSuspendedAppPackages()
            allAppsMasterList = allAppsMasterList.map {
                it.copy(
                    isBlocked = currentlyBlockedPackages.contains(it.packageName),
                    isSuspended = currentlySuspendedPackages.contains(it.packageName)
                )
            }
        }

        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val appAlreadyInList = allAppsMasterList.any { it.packageName == packageName }

            val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val isLauncher = pm.queryIntentActivities(launcherIntent, 0).any { it.activityInfo.packageName == packageName }

            if (!appAlreadyInList) {
                val newApp = AppInfo(
                    appName = appInfo.loadLabel(pm).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(pm),
                    isBlocked = true,
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isLauncherApp = isLauncher,
                    isSuspended = false,
                    isInstalled = true
                )
                allAppsMasterList = (allAppsMasterList + newApp).sortedBy { it.appName.lowercase() }
            }

            onAppSelectionChanged(packageName, true)
            return null
        } catch (e: PackageManager.NameNotFoundException) {
            return context.getString(R.string.app_blocker_error_package_not_found, packageName)
        }
    }

    private suspend fun cacheAppInfo(appInfo: AppInfo) {
        if (!appInfo.isInstalled) return
        val iconPath = saveIconToFile(appInfo.packageName, appInfo.icon) ?: return
        val cacheEntry = BlockedAppCache(
            packageName = appInfo.packageName, appName = appInfo.appName, iconPath = iconPath
        )
        settingsRepository.addAppToCache(cacheEntry)
    }

    private suspend fun removeAppsFromCache(packageNames: List<String>) {
        settingsRepository.removeAppsFromCache(packageNames)
        packageNames.forEach {
            try {
                File(iconsDir, "$it.png").delete()
            } catch (e: Exception) { Log.e(TAG, "Failed to delete icon for $it") }
        }
    }

    private fun saveIconToFile(packageName: String, drawable: Drawable): String? {
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
            val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
            try {
                val bmp = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create bitmap from drawable for $packageName", e)
                return null
            }
        }

        try {
            val file = File(iconsDir, "$packageName.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                return file.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving icon to file for $packageName", e)
        }
        return null
    }

    private fun dismissCriticalAppsWarning() {
        _uiState.update { it.copy(showCriticalAppsWarning = false, criticalAppsDetected = emptyList()) }
        // Also reset the blocked selections for critical apps
        val criticalPackages = CRITICAL_SYSTEM_PACKAGES
        allAppsMasterList = allAppsMasterList.map {
            if (criticalPackages.contains(it.packageName)) it.copy(isBlocked = false, isSuspended = false) else it
        }
        applyFilter()
    }

    private fun loadIconFromFile(path: String): Drawable? = Drawable.createFromPath(path)
    private fun createDefaultIcon(): Drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!
}
