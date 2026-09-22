package com.secureguard.mdm.screentime.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.screentime.ScreenTimeProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** מצב מסך רשימת הפרופילים של הגבלת זמן המסך. */
data class ScreenTimeSettingsState(
    val isLoading: Boolean = true,
    val isEnabled: Boolean = false,
    val profiles: List<ScreenTimeProfile> = emptyList()
)

@HiltViewModel
class ScreenTimeSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScreenTimeSettingsState())
    val uiState = _uiState.asStateFlow()

    init {
        loadState()
    }

    private fun loadState() {
        viewModelScope.launch {
            val isEnabled = settingsRepository.isScreenTimeEnabled()
            val profiles = settingsRepository.getScreenTimeProfiles()
            _uiState.update {
                it.copy(isLoading = false, isEnabled = isEnabled, profiles = profiles)
            }
        }
    }

    /** קורא מחדש את רשימת הפרופילים - נקרא כשחוזרים ממסך העריכה. */
    fun refresh() {
        viewModelScope.launch {
            val profiles = settingsRepository.getScreenTimeProfiles()
            _uiState.update { it.copy(profiles = profiles) }
        }
    }

    fun onToggleEnabled(isEnabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setScreenTimeEnabled(isEnabled)
            _uiState.update { it.copy(isEnabled = isEnabled) }
            if (isEnabled) {
                com.secureguard.mdm.utils.JobSchedulerHelper.scheduleScreenTimeEnforcer(context)
            } else {
                com.secureguard.mdm.utils.JobSchedulerHelper.cancelScreenTimeEnforcer(context)
                com.secureguard.mdm.services.ScreenTimeEnforcer.releaseAllSuspensions(context, settingsRepository)
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            val updated = _uiState.value.profiles.filterNot { it.id == profileId }
            settingsRepository.setScreenTimeProfiles(updated)
            _uiState.update { it.copy(profiles = updated) }
        }
    }
}