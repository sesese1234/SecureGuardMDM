package com.secureguard.mdm.ui.screens.settings

import android.app.admin.DeviceAdminInfo
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.net.VpnService
import android.os.Build
import com.secureguard.mdm.utils.AppLogger
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.R
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.features.impl.BlockInternetVpnFeature
import com.secureguard.mdm.features.impl.InstallAndProtectNetGuardFeature
import com.secureguard.mdm.features.impl.NetfreeOnlyModeFeature
import com.secureguard.mdm.features.registry.CategoryRegistry
import com.secureguard.mdm.security.PasswordManager
import com.secureguard.mdm.settingsfeatures.api.SettingsFeature
import com.secureguard.mdm.settingsfeatures.api.ToggleSetting
import com.secureguard.mdm.settingsfeatures.impl.*
import com.secureguard.mdm.settingsfeatures.registry.SettingsRegistry
import com.secureguard.mdm.data.model.PresetConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.OutputStreamWriter
import com.secureguard.mdm.data.local.PreferencesManager
import javax.inject.Inject


sealed class SettingsSideEffect {
    object NavigateBack : SettingsSideEffect()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val passwordManager: PasswordManager,
    private val preferencesManager: PreferencesManager,
    private val dpm: DevicePolicyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    private val _passwordPromptState = MutableStateFlow(PasswordPromptState())
    val passwordPromptState = _passwordPromptState.asStateFlow()

    private val _removalOptionsDialogState = MutableStateFlow(RemovalOptionsDialogState())
    val removalOptionsDialogState = _removalOptionsDialogState.asStateFlow()

    private val _deviceAdminSelectionState = MutableStateFlow(DeviceAdminSelectionState())
    val deviceAdminSelectionState = _deviceAdminSelectionState.asStateFlow()

    private val _errorDialogState = MutableStateFlow(ErrorDialogState())
    val errorDialogState = _errorDialogState.asStateFlow()

    private val _sideEffect = MutableSharedFlow<SettingsSideEffect>()
    val sideEffect = _sideEffect.asSharedFlow()

    private val _vpnPermissionRequestEvent = MutableSharedFlow<Unit>()
    val vpnPermissionRequestEvent = _vpnPermissionRequestEvent.asSharedFlow()

    private val _triggerUninstallEvent = MutableSharedFlow<Unit>()
    val triggerUninstallEvent = _triggerUninstallEvent.asSharedFlow()

    private val _createDocumentEvent = MutableSharedFlow<Unit>()
    val createDocumentEvent = _createDocumentEvent.asSharedFlow()

    private val adminComponentName by lazy {
        SecureGuardDeviceAdminReceiver.getComponentName(context)
    }

    // --- NEW: Store initial state for comparison ---
    private var initialProtectionTogglesState: Map<String, Boolean> = emptyMap()
    private var initialSettingsTogglesState: Map<String, Boolean> = emptyMap()

    private var pendingVpnEnableRequest: Boolean = false

    init {
        loadInitialState()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.OnToggleProtectionFeature -> handleProtectionToggle(event.featureId, event.isEnabled)
            is SettingsEvent.OnVpnPermissionResult -> handleVpnPermissionResult(event.granted)
            is SettingsEvent.OnToggleSettingChanged -> handleSettingToggle(event.settingId, event.isChecked)
            is SettingsEvent.OnActionSettingClicked -> handleActionClick(event.settingId)
            is SettingsEvent.OnLockSettingsConfirmed -> lockSettings(event.allowManualUpdate)
            is SettingsEvent.OnRegularRemovalSelected -> handleRegularRemovalSelected()
            is SettingsEvent.OnTransferOwnershipSelected -> handleTransferOwnershipSelected()
            is SettingsEvent.OnDismissRemovalOptionsDialog -> _removalOptionsDialogState.update { RemovalOptionsDialogState() }
            is SettingsEvent.OnDeviceAdminSelectionDismissed -> _deviceAdminSelectionState.update { DeviceAdminSelectionState() }
            is SettingsEvent.OnDeviceAdminSelected -> handleDeviceAdminSelected(event.deviceAdminItem)
            is SettingsEvent.OnDeviceAdminTransferConfirmed -> handleDeviceAdminTransferConfirmed()
            is SettingsEvent.OnDeviceAdminTransferCancelled -> handleDeviceAdminTransferCancelled()
            is SettingsEvent.OnErrorDialogDismissed -> _errorDialogState.update { ErrorDialogState() }
            is SettingsEvent.OnSaveClick -> saveChanges()
            is SettingsEvent.OnSnackbarShown -> _uiState.update { it.copy(snackbarMessage = null) }
            is SettingsEvent.OnExportFileSelected -> exportSettings(event.uri)
        }
    }

    fun onPasswordPromptEvent(event: PasswordPromptEvent) {
        when (event) {
            is PasswordPromptEvent.OnPasswordEntered -> handleRemoveProtectionPassword(event.password)
            PasswordPromptEvent.OnDismiss -> _passwordPromptState.update { PasswordPromptState() }
        }
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val protectionCategoryToggles = loadProtectionFeatures()
            val settingItemsByCategory = loadSettingsFeatures()

            // --- NEW: Capture the initial state after loading ---
            initialProtectionTogglesState = protectionCategoryToggles.flatMap { it.toggles }.associate { it.feature.id to it.isEnabled }
            initialSettingsTogglesState = settingItemsByCategory.values.flatten().filter { it.feature is ToggleSetting }.associate { it.feature.id to it.isChecked }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    protectionCategoryToggles = protectionCategoryToggles,
                    settingItemsByCategory = settingItemsByCategory
                )
            }
        }
    }

    private suspend fun loadProtectionFeatures(): List<ProtectionCategoryToggle> {
        val isNetGuardInstalled = isNetGuardInstalled()
        val currentDeviceApi = Build.VERSION.SDK_INT
        return CategoryRegistry.allCategories.map { category ->
            val featureToggles = category.features.map { feature ->
                var isSupported = currentDeviceApi >= feature.requiredSdkVersion
                var conflictReason: Int? = null
                if (feature.id == BlockInternetVpnFeature.id && isNetGuardInstalled) {
                    isSupported = false
                    conflictReason = R.string.conflict_reason_netguard_installed
                }
                FeatureToggle(
                    feature = feature,
                    isEnabled = settingsRepository.getFeatureState(feature.id),
                    isSupported = isSupported,
                    requiredApi = feature.requiredSdkVersion,
                    conflictReasonResId = conflictReason
                )
            }
            ProtectionCategoryToggle(titleResId = category.titleResId, toggles = featureToggles)
        }
    }

    private suspend fun loadSettingsFeatures(): Map<com.secureguard.mdm.settingsfeatures.api.SettingCategory, List<SettingItemModel>> {
        val currentDeviceApi = Build.VERSION.SDK_INT
        return SettingsRegistry.allSettings
            .map { feature ->
                val isChecked = if (feature is ToggleSetting) {
                    when (feature.id) {
                        ToggleUiPositionSetting.id -> settingsRepository.isToggleOnStart()
                        ToggleUiControlTypeSetting.id -> settingsRepository.useCheckbox()
                        ToggleContactEmailSetting.id -> settingsRepository.isContactEmailVisible()
                        ToggleUpdatesSetting.id -> settingsRepository.areAllUpdatesDisabled()
                        ShowBootToastSetting.id -> settingsRepository.isShowBootToastEnabled()
                        AstoreToggleSetting.id -> settingsRepository.isAstoreEnabled()
                        else -> false
                    }
                } else false
                
                SettingItemModel(
                    feature = feature,
                    isChecked = isChecked,
                    isSupported = currentDeviceApi >= feature.requiredSdkVersion,
                    requiredApi = feature.requiredSdkVersion
                )
            }
            .groupBy { it.feature.category }
    }


    private fun handleActionClick(settingId: String) {
        when (settingId) {
            LockSettingsAction.id -> {
                // This is handled in the screen, which shows the dialog.
                // The dialog confirmation will call OnLockSettingsConfirmed.
            }
            RemovalOptionsAction.id -> {
                _passwordPromptState.update { it.copy(isVisible = true) }
            }
            ExportSettingsAction.id -> {
                viewModelScope.launch {
                    _createDocumentEvent.emit(Unit)
                }
            }
        }
    }

    private fun handleSettingToggle(settingId: String, isChecked: Boolean) {
        _uiState.update { currentState ->
            val updatedMap = currentState.settingItemsByCategory.toMutableMap()
            for ((category, items) in updatedMap) {
                val updatedItems = items.map { model ->
                    if (model.feature.id == settingId) {
                        model.copy(isChecked = isChecked)
                    } else {
                        model
                    }
                }
                updatedMap[category] = updatedItems
            }
            currentState.copy(settingItemsByCategory = updatedMap)
        }
    }

    private fun saveChanges() {
        viewModelScope.launch {
            val currentState = _uiState.value
            var hasChanges = false
            var snackbarMessage = context.getString(R.string.dialog_changes_saved_successfully)

            // Save new modular settings, ONLY IF CHANGED
            currentState.settingItemsByCategory.values.flatten().forEach { model ->
                if (model.feature is ToggleSetting) {
                    val initialValue = initialSettingsTogglesState[model.feature.id]
                    if (initialValue != model.isChecked) {
                        hasChanges = true
                        when (model.feature.id) {
                            ToggleUiPositionSetting.id -> settingsRepository.setToggleOnStart(model.isChecked)
                            ToggleUiControlTypeSetting.id -> settingsRepository.setUseCheckbox(model.isChecked)
                            ToggleContactEmailSetting.id -> settingsRepository.setContactEmailVisible(model.isChecked)
                            ToggleUpdatesSetting.id -> settingsRepository.setAllUpdatesDisabled(model.isChecked)
                            ShowBootToastSetting.id -> settingsRepository.setShowBootToastEnabled(model.isChecked) // <-- SAVE the new state
                            AstoreToggleSetting.id -> settingsRepository.setAstoreEnabled(model.isChecked)
                        }
                    }
                }
            }

            // Save main protection features, ONLY IF CHANGED
            val wasNetGuardProtectedBeforeSave = initialProtectionTogglesState[InstallAndProtectNetGuardFeature.id] ?: false
            currentState.protectionCategoryToggles.flatMap { it.toggles }.forEach { toggle ->
                val initialValue = initialProtectionTogglesState[toggle.feature.id]
                if (initialValue != toggle.isEnabled) {
                    hasChanges = true
                    toggle.feature.applyPolicy(context, dpm, adminComponentName, toggle.isEnabled)
                    settingsRepository.setFeatureState(toggle.feature.id, toggle.isEnabled)

                    // Special message for NetGuard
                    if (toggle.feature.id == InstallAndProtectNetGuardFeature.id && wasNetGuardProtectedBeforeSave && !toggle.isEnabled && isNetGuardInstalled()) {
                        snackbarMessage += "\n" + context.getString(R.string.toast_netguard_can_be_uninstalled)
                    }
                }
            }

            if (hasChanges) {
                _uiState.update { it.copy(snackbarMessage = snackbarMessage) }
            }

            // Always navigate back, even if no changes were made.
            _sideEffect.emit(SettingsSideEffect.NavigateBack)
        }
    }

    private fun exportSettings(uri: Uri) {
        viewModelScope.launch {
            try {
                val protectionFeatures = _uiState.value.protectionCategoryToggles.flatMap { it.toggles }.associate { it.feature.id to it.isEnabled }
                val settings = _uiState.value.settingItemsByCategory.values.flatten().filter { it.feature is ToggleSetting }.associate { it.feature.id to it.isChecked }
                val allFeatures = protectionFeatures + settings

                // Fetch expanded settings
                val blockedApps = settingsRepository.getBlockedAppPackages()
                val suspendedApps = settingsRepository.getSuspendedAppPackages()
                val frpIds = settingsRepository.getCustomFrpIds()
                val savedChannel = preferencesManager.loadString(PreferencesManager.KEY_UPDATE_CHANNEL, "STABLE")
                
                // Fetch Kiosk settings
                val kioskEnabled = settingsRepository.isKioskModeEnabled()
                val kioskApps = settingsRepository.getKioskAppPackages().toList()
                val kioskTitle = settingsRepository.getKioskTitle()
                val kioskBackgroundColor = settingsRepository.getKioskBackgroundColor()
                val kioskPrimaryColor = settingsRepository.getKioskPrimaryColor()
                val kioskShowUpdate = settingsRepository.shouldShowKioskSecureUpdate()
                val kioskActionButtons = settingsRepository.getKioskActionButtons()
                val kioskLayoutJson = settingsRepository.getKioskLayoutJson()
                val kioskBlockedLauncher = settingsRepository.getKioskBlockedLauncherPackage()
                val kioskSettingsInLockTask = settingsRepository.isKioskSettingsInLockTaskEnabled()
                val kioskAppMonitor = settingsRepository.isKioskAppMonitorEnabled()
                val autoUpdateCheck = settingsRepository.isAutoUpdateCheckEnabled()

                val config = PresetConfig(
                    features = allFeatures,
                    kioskEnabled = kioskEnabled,
                    kioskApps = kioskApps.ifEmpty { null },
                    kioskTitle = kioskTitle,
                    kioskBackgroundColor = kioskBackgroundColor,
                    kioskPrimaryColor = kioskPrimaryColor,
                    kioskShowUpdate = kioskShowUpdate,
                    kioskActionButtons = kioskActionButtons.ifEmpty { null },
                    kioskLayoutJson = kioskLayoutJson,
                    // New expanded fields
                    blockedApps = blockedApps.ifEmpty { null },
                    suspendedApps = suspendedApps.ifEmpty { null },
                    customFrpIds = frpIds.ifEmpty { null },
                    updateChannel = savedChannel,
                    kioskBlockedLauncherPackage = kioskBlockedLauncher,
                    kioskSettingsInLockTaskEnabled = kioskSettingsInLockTask,
                    kioskAppMonitorEnabled = kioskAppMonitor,
                    autoUpdateCheckEnabled = autoUpdateCheck
                )
                val jsonString = Json.encodeToString(config)

                context.contentResolver.openOutputStream(uri)?.use {
                    OutputStreamWriter(it).use { writer ->
                        writer.write(jsonString)
                    }
                }
                _uiState.update { it.copy(snackbarMessage = context.getString(R.string.settings_export_success)) }

            } catch (e: IOException) {
                AppLogger.e("SettingsVM", "Failed to export settings", e)
                _uiState.update { it.copy(snackbarMessage = context.getString(R.string.settings_export_error, e.message)) }
            } catch (e: Exception) {
                AppLogger.e("SettingsVM", "An unexpected error occurred during export", e)
                _uiState.update { it.copy(snackbarMessage = context.getString(R.string.settings_export_error_unexpected)) }
            }
        }
    }

    private fun lockSettings(allowManualUpdate: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAllUpdatesDisabled(true)
            settingsRepository.setAutoUpdateCheckEnabled(false)

            settingsRepository.lockSettingsPermanently(allowManualUpdate)
            AppLogger.d("SettingsVM", "SETTINGS PERMANENTLY LOCKED. Allow manual updates: $allowManualUpdate")
            _sideEffect.emit(SettingsSideEffect.NavigateBack)
        }
    }

    private fun handleProtectionToggle(featureId: String, isEnabled: Boolean) {
        if ((featureId == BlockInternetVpnFeature.id || featureId == NetfreeOnlyModeFeature.id) && isEnabled) {
            if (VpnService.prepare(context) != null) {
                pendingVpnEnableRequest = true
                viewModelScope.launch { _vpnPermissionRequestEvent.emit(Unit) }
                return
            }
        }
        _uiState.update { currentState ->
            val updatedCategories = currentState.protectionCategoryToggles.map { category ->
                val updatedToggles = category.toggles.map { toggle ->
                    if (toggle.feature.id == featureId) toggle.copy(isEnabled = isEnabled) else toggle
                }
                category.copy(toggles = updatedToggles)
            }
            currentState.copy(protectionCategoryToggles = updatedCategories)
        }
    }

    private fun handleVpnPermissionResult(granted: Boolean) {
        if (granted && pendingVpnEnableRequest) {
            // Find which feature was pending
            val pendingFeature = _uiState.value.protectionCategoryToggles
                .flatMap { it.toggles }
                .find { it.feature.id == BlockInternetVpnFeature.id || it.feature.id == NetfreeOnlyModeFeature.id }
                ?.feature?.id

            if (pendingFeature != null) {
                handleProtectionToggle(pendingFeature, true)
            }
        } else if (!granted) {
            _uiState.update { it.copy(snackbarMessage = context.getString(R.string.settings_error_vpn_permission_required)) }
        }
        pendingVpnEnableRequest = false
    }

    private fun handleRemoveProtectionPassword(password: String) {
        viewModelScope.launch {
            if (passwordManager.verifyPassword(password)) {
                _passwordPromptState.update { it.copy(isVisible = false) }
                _removalOptionsDialogState.update { RemovalOptionsDialogState(isVisible = true) }
            } else {
                _passwordPromptState.update { it.copy(error = context.getString(R.string.dialog_error_wrong_password)) }
            }
        }
    }

    private fun handleRegularRemovalSelected() {
        _removalOptionsDialogState.update { RemovalOptionsDialogState() }
        initiateRemoval()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun handleTransferOwnershipSelected() {
        _removalOptionsDialogState.update { RemovalOptionsDialogState() }
        // TODO: Show device admin selection dialog
        showDeviceAdminSelection()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun showDeviceAdminSelection() {
        viewModelScope.launch {
            try {
                val deviceAdmins = loadDeviceAdmins()
                _deviceAdminSelectionState.update {
                    DeviceAdminSelectionState(isVisible = true, deviceAdmins = deviceAdmins)
                }
            } catch (e: Exception) {
                AppLogger.e("SettingsVM", "Error loading device admins", e)
                _uiState.update { it.copy(snackbarMessage = context.getString(R.string.settings_error_loading_device_admins)) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun loadDeviceAdmins(): List<DeviceAdminItem> {
        val pm = context.packageManager
        val deviceAdmins = mutableListOf<DeviceAdminItem>()

        // Query for device admin receivers
        val intent = Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED)
        val resolveInfos = pm.queryBroadcastReceivers(intent, PackageManager.GET_META_DATA)

        for (resolveInfo in resolveInfos) {
            try {
                val deviceAdminInfo = DeviceAdminInfo(context, resolveInfo)
                val displayName = deviceAdminInfo.loadLabel(pm).toString()
                val packageName = deviceAdminInfo.packageName

                // Skip our own app and check if the app supports ownership transfer
                if (packageName != context.packageName && deviceAdminInfo.supportsTransferOwnership()) {
                    deviceAdmins.add(DeviceAdminItem(deviceAdminInfo, displayName, packageName))
                }
            } catch (e: Exception) {
                AppLogger.w("SettingsVM", "Error loading device admin info for ${resolveInfo.activityInfo?.packageName}", e)
            }
        }

        return deviceAdmins
    }

    private fun handleDeviceAdminSelected(deviceAdminItem: DeviceAdminItem) {
        _deviceAdminSelectionState.update { it.copy(selectedAdmin = deviceAdminItem, showConfirmationDialog = true) }
    }

    private fun handleDeviceAdminTransferConfirmed() {
        val selectedAdmin = _deviceAdminSelectionState.value.selectedAdmin
        if (selectedAdmin != null) {
            performOwnershipTransfer(selectedAdmin)
        }
        _deviceAdminSelectionState.update { DeviceAdminSelectionState() }
    }

    private fun handleDeviceAdminTransferCancelled() {
        _deviceAdminSelectionState.update { it.copy(selectedAdmin = null, showConfirmationDialog = false) }
    }

    private fun performOwnershipTransfer(deviceAdminItem: DeviceAdminItem) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // transferOwnership is only available from API 28 (Android P)
            _uiState.update { it.copy(snackbarMessage = context.getString(R.string.settings_error_android_version_required)) }
            return
        }

        viewModelScope.launch {
            try {

                // bruh, why? AI is so stupid..
                // initiateRemoval()

                // Remove all protection features
                _uiState.value.protectionCategoryToggles.flatMap { it.toggles }.forEach {
                    it.feature.applyPolicy(context, dpm, adminComponentName, false)
                    settingsRepository.setFeatureState(it.feature.id, false)
                }

                // Unhide blocked apps
                val blockedApps = settingsRepository.getBlockedAppPackages()
                blockedApps.forEach { packageName ->
                    dpm.setApplicationHidden(adminComponentName, packageName, false)
                }
                // Unsuspend apps
                val suspendedApps = settingsRepository.getSuspendedAppPackages()
                suspendedApps.forEach { packageName ->
                    dpm.setPackagesSuspended(adminComponentName, arrayOf(packageName), false)
                }
                settingsRepository.removeAppsFromCache((blockedApps + suspendedApps).toList())
                settingsRepository.setBlockedAppPackages(emptySet())
                settingsRepository.setSuspendedAppPackages(emptySet())
                // try to move owner
                val targetComponent = deviceAdminItem.deviceAdminInfo.component
                dpm.transferOwnership(adminComponentName, targetComponent, null)
                _uiState.update { it.copy(snackbarMessage = context.getString(R.string.settings_transfer_ownership_success, deviceAdminItem.displayName)) }
                // remove app
                _triggerUninstallEvent.emit(Unit)
            } catch (e: SecurityException) {
                AppLogger.e("SettingsVM", "Error transferring ownership", e)
                _uiState.update { it.copy(snackbarMessage = context.getString(R.string.settings_transfer_ownership_error, e.message)) }
            } catch (e: IllegalArgumentException) {
                AppLogger.e("SettingsVM", "Invalid target for ownership transfer", e)
                _uiState.update { it.copy(snackbarMessage = context.getString(R.string.settings_transfer_ownership_error_unsupported, e.message)) }
            } catch (e: IllegalStateException) {
                AppLogger.e("SettingsVM", "Invalid state for ownership transfer", e)
                _uiState.update { it.copy(snackbarMessage = context.getString(R.string.settings_transfer_ownership_error_invalid_state, e.message)) }
            } catch (e: Exception) {
                AppLogger.e("SettingsVM", "Unexpected error during ownership transfer", e)
                _uiState.update { it.copy(snackbarMessage = context.getString(R.string.settings_transfer_ownership_error_unexpected, e.message ?: context.getString(R.string.dialog_button_cancel))) }
            }
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        _errorDialogState.update { ErrorDialogState(isVisible = true, title = title, message = message) }
    }

    private fun isNetGuardInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("eu.faircode.netguard", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Clears every user restriction currently set by this admin. Reads the live set from
     * the framework so restrictions applied by features no longer present in the UI state
     * are removed too.
     */
    private fun clearAllUserRestrictions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val restrictions = try {
            dpm.getUserRestrictions(adminComponentName)
        } catch (e: Exception) {
            AppLogger.w("SettingsVM", "Could not read user restrictions", e)
            return
        }
        restrictions.keySet().forEach { key ->
            try {
                dpm.clearUserRestriction(adminComponentName, key)
            } catch (e: Exception) {
                AppLogger.w("SettingsVM", "Could not clear restriction $key", e)
            }
        }
    }

    private fun initiateRemoval() {
        viewModelScope.launch {
            try {
                // Remove all protection features. Each one is isolated: a single failing
                // policy used to abort the whole removal, leaving the device owner in
                // place with the protections already half torn down.
                _uiState.value.protectionCategoryToggles.flatMap { it.toggles }.forEach {
                    try {
                        it.feature.applyPolicy(context, dpm, adminComponentName, false)
                        settingsRepository.setFeatureState(it.feature.id, false)
                    } catch (e: Exception) {
                        AppLogger.w("SettingsVM", "Could not clear policy ${it.feature.id}", e)
                    }
                }

                // Unhide blocked apps
                val blockedApps = settingsRepository.getBlockedAppPackages()
                blockedApps.forEach { packageName ->
                    try {
                        dpm.setApplicationHidden(adminComponentName, packageName, false)
                    } catch (e: Exception) {
                        AppLogger.w("SettingsVM", "Could not unhide $packageName", e)
                    }
                }
                // Unsuspend apps
                val suspendedApps = settingsRepository.getSuspendedAppPackages()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    suspendedApps.forEach { packageName ->
                        try {
                            dpm.setPackagesSuspended(adminComponentName, arrayOf(packageName), false)
                        } catch (e: Exception) {
                            AppLogger.w("SettingsVM", "Could not unsuspend $packageName", e)
                        }
                    }
                }
                settingsRepository.removeAppsFromCache((blockedApps + suspendedApps).toList())
                settingsRepository.setBlockedAppPackages(emptySet())
                settingsRepository.setSuspendedAppPackages(emptySet())

                // Drop every remaining user restriction rather than relying on the
                // feature toggles above having covered them. DISALLOW_UNINSTALL_APPS and
                // DISALLOW_APPS_CONTROL in particular make the package un-removable, and
                // once device ownership is gone they can no longer be cleared — which is
                // exactly how the app ended up stuck on the device with its protections
                // already disabled.
                clearAllUserRestrictions()

                // Also make sure nothing still marks this package as protected.
                try {
                    dpm.setUninstallBlocked(adminComponentName, context.packageName, false)
                } catch (e: Exception) {
                    AppLogger.w("SettingsVM", "Could not clear own uninstall block", e)
                }

                // Clear device owner (this removes admin privileges)
                dpm.clearDeviceOwnerApp(context.packageName)

                // Trigger app uninstall
                _triggerUninstallEvent.emit(Unit)
            } catch (e: SecurityException) {
                AppLogger.e("SettingsVM", "Security error during removal", e)
                showErrorDialog(context.getString(R.string.removal_error_security_title), context.getString(R.string.removal_error_security, e.message))
            } catch (e: IllegalArgumentException) {
                AppLogger.e("SettingsVM", "Invalid argument during removal", e)
                showErrorDialog(context.getString(R.string.removal_error_invalid_parameter_title), context.getString(R.string.removal_error_invalid_parameter, e.message))
            } catch (e: IllegalStateException) {
                AppLogger.e("SettingsVM", "Invalid state during removal", e)
                showErrorDialog(context.getString(R.string.removal_error_invalid_state_title), context.getString(R.string.removal_error_invalid_state, e.message))
            } catch (e: RuntimeException) {
                AppLogger.e("SettingsVM", "Runtime error during removal", e)
                showErrorDialog(context.getString(R.string.removal_error_runtime_title), context.getString(R.string.removal_error_runtime, e.message))
            } catch (e: Exception) {
                AppLogger.e("SettingsVM", "Unexpected error during removal", e)
                showErrorDialog(context.getString(R.string.removal_error_unexpected_title), context.getString(R.string.removal_error_unexpected, e.message ?: context.getString(R.string.dialog_button_cancel)))
            }
        }
    }
}

data class PasswordPromptState(
    val isVisible: Boolean = false,
    val error: String? = null
)

data class RemovalOptionsDialogState(
    val isVisible: Boolean = false
)

data class DeviceAdminItem(
    val deviceAdminInfo: DeviceAdminInfo,
    val displayName: String,
    val packageName: String
)

data class DeviceAdminSelectionState(
    val isVisible: Boolean = false,
    val deviceAdmins: List<DeviceAdminItem> = emptyList(),
    val selectedAdmin: DeviceAdminItem? = null,
    val showConfirmationDialog: Boolean = false
)

data class ErrorDialogState(
    val isVisible: Boolean = false,
    val title: String = "",
    val message: String = ""
)

sealed class PasswordPromptEvent {
    data class OnPasswordEntered(val password: String) : PasswordPromptEvent()
    object OnDismiss : PasswordPromptEvent()
}
