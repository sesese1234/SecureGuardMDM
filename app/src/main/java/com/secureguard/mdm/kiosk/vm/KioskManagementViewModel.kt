package com.secureguard.mdm.kiosk.vm

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.kiosk.manager.KioskLockManager
import com.secureguard.mdm.screentime.ScreenTimeScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KioskManagementState(
    val isLoading: Boolean = true,
    val isKioskModeEnabled: Boolean = false,
    val isMonitorEnabled: Boolean = false,
    val isDeviceOwner: Boolean = false,
    val isDefaultLauncher: Boolean = false,
    val enabledActions: Set<String> = emptySet()
)

@HiltViewModel
class KioskManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val kioskLockManager: KioskLockManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(KioskManagementState())
    val uiState = _uiState.asStateFlow()

    init {
        loadState()
    }

    private fun loadState() {
        viewModelScope.launch {
            val isEnabled = settingsRepository.isKioskModeEnabled()
            val isMonitorEnabled = settingsRepository.isKioskAppMonitorEnabled()
            val isDO = kioskLockManager.isDeviceOwner()
            val isDefault = kioskLockManager.isKioskDefaultLauncher()
            val enabledActions = settingsRepository.getKioskActionButtons()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    isKioskModeEnabled = isEnabled,
                    isMonitorEnabled = isMonitorEnabled,
                    isDeviceOwner = isDO,
                    isDefaultLauncher = isDefault,
                    enabledActions = enabledActions
                )
            }
        }
    }

    fun toggleActionButton(actionId: String, isEnabled: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value.enabledActions
            val updated = if (isEnabled) current + actionId else current - actionId
            settingsRepository.setKioskActionButtons(updated)
            _uiState.update { it.copy(enabledActions = updated) }
            if (_uiState.value.isKioskModeEnabled) {
                kioskLockManager.setLockTaskPackages()
            }
        }
    }

    fun setKioskModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                kioskLockManager.setLockTaskPackages()
                kioskLockManager.enableKioskModeFeatures()

                val includeViewAbsorber = settingsRepository.isKioskAppMonitorEnabled()
                kioskLockManager.setKioskAsDefaultLauncher(includeViewAbsorber)
            } else {
                kioskLockManager.clearLockTaskPackages()
                kioskLockManager.disableKioskModeFeatures()

                val originalLauncher = settingsRepository.getChosenHomeLauncherPackage()
                if (originalLauncher != null) {
                    kioskLockManager.setSpecificAsDefaultLauncher(originalLauncher)
                } else {
                    kioskLockManager.clearKioskAsDefaultLauncher()
                }
            }

            settingsRepository.setKioskModeEnabled(enabled)
            _uiState.update { it.copy(isKioskModeEnabled = enabled) }

            if (enabled) {
                launchKiosk()
                ScreenTimeScheduler(context).start()
            }
        }
    }

    fun saveAndApply(onComplete: () -> Unit) {
        viewModelScope.launch {
            if (uiState.value.isKioskModeEnabled) {
                launchKiosk()
            }
            onComplete()
        }
    }

    private fun launchKiosk() {
        val intent = Intent(context, com.secureguard.mdm.kiosk.ui.KioskActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }

    fun setMonitorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKioskAppMonitorEnabled(enabled)
            _uiState.update { it.copy(isMonitorEnabled = enabled) }

            val kioskEnabled = settingsRepository.isKioskModeEnabled()
            if (kioskEnabled) {
                kioskLockManager.setKioskAsDefaultLauncher(enabled)
            }
        }
    }

    fun rebootDevice() {
        kioskLockManager.rebootDevice()
    }

    fun resetLayout() {
        viewModelScope.launch {
            settingsRepository.setKioskLayoutJson(null)
        }
    }
}