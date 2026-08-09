package com.secureguard.mdm.astore.ui

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.secureguard.mdm.utils.AppLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import com.secureguard.mdm.R
import com.secureguard.mdm.astore.cache.AstoreCacheManager
import com.secureguard.mdm.astore.domain.AppMetadata
import com.secureguard.mdm.astore.domain.AppStoreService
import com.secureguard.mdm.astore.domain.DownloadSource
import com.secureguard.mdm.astore.downloader.AstoreDownloadProgress
import com.secureguard.mdm.astore.downloader.AstoreDownloader
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.receivers.InstallReceiver
import com.secureguard.mdm.security.PasswordManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "AstoreViewModel"

@HiltViewModel
class AstoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val appStoreService: AppStoreService,
    private val dpm: DevicePolicyManager,
    private val downloader: AstoreDownloader,
    private val cacheManager: AstoreCacheManager,
    private val passwordManager: PasswordManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AstoreUiState())
    val uiState = _uiState.asStateFlow()
    private val activeInstalls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        loadApps()
    }

    /**
     * Load apps - uses cache if valid, otherwise fetches from network
     */
    private fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            // Try cache first
            if (cacheManager.isCacheValid()) {
                val cachedApps = cacheManager.getCachedApps()
                if (cachedApps.isNotEmpty()) {
                    AppLogger.d(TAG, "Using cached apps (${cachedApps.size})")
                    _uiState.update { it.copy(isLoading = false, apps = cachedApps) }
                    // Refresh in background
                    refreshAppsInBackground(cachedApps)
                    return@launch
                }
            }
            
            // Fetch from network
            loadInstalledApps()
        }
    }

    /**
     * Refresh apps in background without blocking UI
     */
    private fun refreshAppsInBackground(currentApps: List<AppMetadata>) {
        viewModelScope.launch {
            try {
                loadInstalledAppsInternal()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Background refresh failed", e)
            }
        }
    }

    /**
     * Force refresh from network
     */
    fun refreshApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            loadInstalledApps()
        }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            loadInstalledAppsInternal()
        }
    }

    private suspend fun loadInstalledAppsInternal() {
        // Run PackageManager queries on IO thread to avoid blocking the UI (prevents ANR)
        val appList = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val activities = pm.queryIntentActivities(intent, 0)
            val incompatibleApps = InstallReceiver.getIncompatiblePackages(context)
            
            activities.map { resolveInfo ->
                val pkgName = resolveInfo.activityInfo.packageName
                val pkgInfo = try { pm.getPackageInfo(pkgName, 0) } catch (e: Exception) { null }
                AppMetadata(
                    packageName = pkgName,
                    title = resolveInfo.loadLabel(pm).toString(),
                    versionName = pkgInfo?.versionName ?: "",
                    versionCode = pkgInfo?.let { 
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong() 
                    } ?: 0L,
                    isInstalled = true
                )
            }.distinctBy { it.packageName }
            .filter { 
                it.packageName != context.packageName &&
                !isNonUpdatableSystemApp(it.packageName) &&
                it.packageName !in incompatibleApps
            }
        }

        // Step 1: Merge with existing apps to preserve cached update states
        _uiState.update { currentState ->
            val mergedApps = appList.map { newApp ->
                val existingApp = currentState.apps.find { it.packageName == newApp.packageName }
                if (existingApp != null) {
                    newApp.copy(
                        updateAvailable = existingApp.updateAvailable,
                        versionName = existingApp.versionName.takeIf { existingApp.updateAvailable } ?: newApp.versionName,
                        iconUrl = existingApp.iconUrl ?: newApp.iconUrl,
                        selectedSource = existingApp.selectedSource,
                        availableSources = existingApp.availableSources
                    )
                } else {
                    newApp
                }
            }
            
            // Keep custom packages. A custom package that is also installed appears in
            // both lists; emitting both produced two entries with the same packageName,
            // and LazyColumn's key = { it.packageName } throws on the duplicate, crashing
            // the store on open. The custom entry wins so its metadata is preserved.
            val customApps = currentState.apps.filter { it.isCustomPackage }
            val customPackageNames = customApps.map { it.packageName }.toSet()

            currentState.copy(
                isLoading = false,
                isRefreshing = false,
                apps = (mergedApps.filter { it.packageName !in customPackageNames } + customApps)
                    .distinctBy { it.packageName },
                isCheckingUpdates = true
            )
        }
        
        // Step 2: Load custom packages first (they should always be visible)
        loadCustomPackages()
        
        // Step 3: Check for updates in background — this will hide up-to-date apps when done
        checkForUpdatesWithSources(appList)
    }
    
    /**
     * Load custom packages from settings and add them to the app list
     */
    private suspend fun loadCustomPackages() {
        val customPackages = settingsRepository.getAstoreCustomPackages()
        if (customPackages.isEmpty()) {
            return
        }
        
        AppLogger.d(TAG, "Loading ${customPackages.size} custom packages")
        
        // Only skip packages already resolved as custom entries. Filtering against every
        // known package meant a custom package that happened to be installed was dropped
        // here and never reached the custom tab, so it could be added but never installed
        // or updated from it.
        val alreadyResolved = _uiState.value.apps
            .filter { it.isCustomPackage }
            .map { it.packageName }
            .toSet()

        val packagesToFetch = customPackages.filter { it !in alreadyResolved }

        if (packagesToFetch.isEmpty()) {
            return
        }
        
        // Fetch details for each custom package. Every entry must carry
        // isCustomPackage = true, otherwise it is filtered off the custom tab and
        // re-fetched on every load; and a lookup that yields nothing still gets a basic
        // entry so the package the user added does not silently disappear.
        val customApps = packagesToFetch.map { packageName ->
            val fallback = AppMetadata(
                packageName = packageName,
                title = packageName,
                versionName = "",
                versionCode = 0,
                isInstalled = false,
                isCustomPackage = true
            )
            try {
                val appInfo = appStoreService.getCustomPackageDetails(packageName)
                val sources = appStoreService.checkSourceAvailability(packageName)
                (appInfo ?: fallback).copy(availableSources = sources, isCustomPackage = true)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get custom package details for $packageName", e)
                fallback
            }
        }
        
        // Add custom apps to the list
        if (customApps.isNotEmpty()) {
            _uiState.update { currentState ->
                // Filter out existing entries for these package names (to prefer custom metadata)
                val packageNamesToReplace = customApps.map { it.packageName }.toSet()
                val remainingApps = currentState.apps.filter { it.packageName !in packageNamesToReplace }
                currentState.copy(apps = remainingApps + customApps)
            }
            
            // Save to cache
            cacheManager.saveAppsToCache(_uiState.value.apps)
        }
    }

    private suspend fun checkForUpdatesWithSources(installedApps: List<AppMetadata>) {
        val packagesToCheck = installedApps.map { Triple(it.packageName, it.versionCode, it.versionName) }
        
        // Check for updates (this no longer skips any apps)
        val updates = appStoreService.checkForUpdates(packagesToCheck)
        
        // Build set of packages that have updates
        val packagesWithUpdates = updates.map { it.packageName }.toSet()
        
        // Update state: mark apps with updates, remove up-to-date apps (keep custom packages)
        _uiState.update { currentState ->
            val updatedApps = currentState.apps.mapNotNull { app ->
                // Always keep custom packages
                if (app.isCustomPackage) return@mapNotNull app
                
                val update = updates.find { it.packageName == app.packageName }
                if (update != null) {
                    // This app has an update — keep it and mark it
                    app.copy(
                        updateAvailable = true,
                        title = cleanTitle(update.title.ifEmpty { app.title }),
                        versionName = update.versionName,
                        iconUrl = update.iconUrl ?: app.iconUrl
                    )
                } else {
                    // No update found — this app is up-to-date, hide it
                    null
                }
            }
            currentState.copy(apps = updatedApps, isCheckingUpdates = false)
        }
        
        // Save outdated apps to cache so they appear instantly next time
        cacheManager.saveAppsToCache(_uiState.value.apps)
        
        AppLogger.d(TAG, "Update check complete: ${packagesWithUpdates.size} updates found, ${_uiState.value.apps.size} apps remaining")
        
        // Check source availability for apps with updates (in background)
        checkSourceAvailabilityForUpdates()
    }

    /**
     * Check source availability for apps that have updates
     */
    private fun checkSourceAvailabilityForUpdates() {
        viewModelScope.launch {
            val appsWithUpdates = _uiState.value.apps.filter { it.updateAvailable }
            
            appsWithUpdates.forEach { app ->
                try {
                    val sources = appStoreService.checkSourceAvailability(app.packageName)
                    _uiState.update { currentState ->
                        val updatedApps = currentState.apps.map { currentApp ->
                            if (currentApp.packageName == app.packageName) {
                                currentApp.copy(availableSources = sources)
                            } else {
                                currentApp
                            }
                        }
                        currentState.copy(apps = updatedApps)
                    }
                    
                    // Update cache
                    val updatedApp = _uiState.value.apps.find { it.packageName == app.packageName }
                    if (updatedApp != null) {
                        cacheManager.updateCachedApp(updatedApp)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to check sources for ${app.packageName}", e)
                }
            }
        }
    }

    /**
     * Select a download source for an app
     */
    fun selectSource(packageName: String, source: DownloadSource) {
        _uiState.update { currentState ->
            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == packageName) {
                    app.copy(selectedSource = source)
                } else {
                    app
                }
            }
            currentState.copy(apps = updatedApps)
        }
        
        // Update cache
        val app = _uiState.value.apps.find { it.packageName == packageName }
        if (app != null) {
            cacheManager.updateCachedApp(app)
        }
    }

    fun uninstallApp(packageName: String) {
        viewModelScope.launch {
            try {
                val adminComponent = SecureGuardDeviceAdminReceiver.getComponentName(context)
                dpm.setUninstallBlocked(adminComponent, packageName, false)
                
                val packageInstaller = context.packageManager.packageInstaller
                val intent = Intent(context, InstallReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                )
                packageInstaller.uninstall(packageName, pendingIntent.intentSender)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = context.getString(R.string.astore_error_uninstall_failed, e.localizedMessage)) }
            }
        }
    }

    /**
     * Update an app using the selected source
     */
    fun updateApp(packageName: String) {
        viewModelScope.launch {
            if (!activeInstalls.add(packageName)) {
                return@launch
            }
            
            // Security Enforcement: Only allowed apps can be downloaded/updated
            val blockedApps = settingsRepository.getBlockedAppPackages()
            if (packageName in blockedApps) {
                _uiState.update { it.copy(error = context.getString(R.string.astore_error_app_blocked, packageName)) }
                activeInstalls.remove(packageName)
                return@launch
            }
            
            val app = _uiState.value.apps.find { it.packageName == packageName }
            if (app == null) {
                activeInstalls.remove(packageName)
                return@launch
            }
            
            updateAppProgress(packageName, 0, true)
            
            try {
                // Get download URL for selected source
                val downloadUrl = if (app.availableSources.isNotEmpty()) {
                    app.getDownloadUrlForSelectedSource()
                } else {
                    // Fallback to default method
                    appStoreService.getDownloadLink(packageName)
                }
                
                if (downloadUrl != null) {
                    val originalSource = app.getOriginalSourceName() ?: app.selectedSource.displayName
                    AppLogger.d(TAG, "Downloading $packageName from $originalSource: $downloadUrl")
                    downloader.downloadApk(packageName, downloadUrl).collect { progress ->
                        when (progress) {
                            is AstoreDownloadProgress.Downloading -> {
                                updateAppProgress(packageName, progress.progress, true)
                            }
                            is AstoreDownloadProgress.Installing -> {
                                updateAppProgress(packageName, 100, true)
                            }
                            is AstoreDownloadProgress.Completed -> {
                                updateAppProgress(packageName, 0, false)
                                // Update app state to reflect installed
                                updateAppInstalledState(packageName)
                            }
                            is AstoreDownloadProgress.Error -> {
                                updateAppProgress(packageName, 0, false)
                                _uiState.update { it.copy(error = progress.message) }
                            }
                        }
                    }
                } else {
                    updateAppProgress(packageName, 0, false)
                    _uiState.update { it.copy(error = context.getString(R.string.astore_error_no_download_link)) }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Update failed for $packageName", e)
                updateAppProgress(packageName, 0, false)
                _uiState.update { it.copy(error = context.getString(R.string.astore_error_update_failed, e.localizedMessage)) }
            } finally {
                // Never leave a row spinning: if the flow ends without a terminal event
                // the progress state would otherwise stay stuck on "downloading".
                updateAppProgress(packageName, 0, false)
                activeInstalls.remove(packageName)
            }
        }
    }

    /**
     * Update all apps that have updates available
     */
    fun updateAll() {
        val appsToUpdate = _uiState.value.apps.filter { it.updateAvailable && !it.isDownloading }
        appsToUpdate.forEach { app ->
            updateApp(app.packageName)
        }
    }

    private fun updateAppInstalledState(packageName: String) {
        _uiState.update { currentState ->
            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == packageName) {
                    app.copy(
                        updateAvailable = false,
                        isDownloading = false,
                        downloadProgress = 0,
                        isInstalled = true,
                        isCustomPackage = false // No longer a custom package after installation
                    )
                } else {
                    app
                }
            }
            currentState.copy(apps = updatedApps)
        }
        
        // Update cache
        val app = _uiState.value.apps.find { it.packageName == packageName }
        if (app != null) {
            cacheManager.updateCachedApp(app)
        }
    }

    fun openApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Verify if the provided password is correct
     */
    suspend fun verifyPassword(password: String): Boolean {
        return passwordManager.verifyPassword(password)
    }

    /**
     * Add a custom package to Astore
     */
    fun addCustomPackage(packageName: String) {
        viewModelScope.launch {
            val currentPackages = settingsRepository.getAstoreCustomPackages().toMutableSet()
            if (currentPackages.add(packageName)) {
                settingsRepository.setAstoreCustomPackages(currentPackages)
                // Refresh list
                loadCustomPackageDetailSilently(packageName)
            }
        }
    }

    /**
     * Remove a custom package from Astore
     */
    fun removeCustomPackage(packageName: String) {
        viewModelScope.launch {
            val currentPackages = settingsRepository.getAstoreCustomPackages().toMutableSet()
            if (currentPackages.remove(packageName)) {
                settingsRepository.setAstoreCustomPackages(currentPackages)
                // Update UI state
                _uiState.update { currentState ->
                    currentState.copy(apps = currentState.apps.filter { it.packageName != packageName || it.isInstalled })
                }
                // Update cache
                cacheManager.saveAppsToCache(_uiState.value.apps)
            }
        }
    }

    private suspend fun loadCustomPackageDetailSilently(packageName: String) {
        try {
            val appInfo = appStoreService.getCustomPackageDetails(packageName) ?: AppMetadata(
                packageName = packageName,
                title = packageName,
                versionName = "",
                versionCode = 0,
                isInstalled = false,
                isCustomPackage = true
            )
            
            val sources = appStoreService.checkSourceAvailability(packageName)
            val finalApp = appInfo.copy(availableSources = sources, isCustomPackage = true)
            
            _uiState.update { currentState ->
                // Filter out if already exists, and add the new one
                val filteredApps = currentState.apps.filter { it.packageName != packageName }
                currentState.copy(apps = filteredApps + finalApp)
            }
            
            // Save to cache
            cacheManager.saveAppsToCache(_uiState.value.apps)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load custom package $packageName", e)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun updateAppProgress(packageName: String, progress: Int, isDownloading: Boolean) {
        _uiState.update { state ->
            val updatedApps = state.apps.map { app ->
                if (app.packageName == packageName) {
                    app.copy(downloadProgress = progress, isDownloading = isDownloading)
                } else {
                    app
                }
            }
            state.copy(apps = updatedApps)
        }
    }

    /**
     * Returns true if the package is a non-updatable system app that isn't on any app store.
     * These are filtered out to avoid wasting network requests during update checks.
     */
    private fun isNonUpdatableSystemApp(packageName: String): Boolean {
        // Known non-updatable system package prefixes
        val nonUpdatablePrefixes = listOf(
            "com.android.settings",
            "com.android.dialer",
            "com.android.contacts",
            "com.android.phone",
            "com.android.systemui",
            "com.android.launcher",
            "com.android.documentsui",
            "com.android.providers.",
            "com.android.inputmethod",
            "com.android.shell",
            "com.android.nfc",
            "com.android.bluetooth",
            "com.android.printspooler",
            "com.android.stk",
            "com.android.cellbroadcast",
            "com.android.emergency",
            "com.android.traceur",
            "com.android.wallpaper",
            "com.android.storagemanager",
            "com.android.vpndialogs",
            "com.android.soundrecorder",
            "com.android.bips",
        )
        return nonUpdatablePrefixes.any { packageName.startsWith(it) }
    }
    
    /** Strip common prefixes/suffixes from scraped app titles */
    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        // Strip "Download " prefix that some scrapers add
        if (cleaned.startsWith("Download ", ignoreCase = true)) {
            cleaned = cleaned.removePrefix("Download ").removePrefix("download ")
        }
        return cleaned.trim()
    }
}

data class AstoreUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isCheckingUpdates: Boolean = false,
    val apps: List<AppMetadata> = emptyList(),
    val error: String? = null
)
