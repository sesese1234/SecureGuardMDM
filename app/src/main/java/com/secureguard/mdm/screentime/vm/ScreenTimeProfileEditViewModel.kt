package com.secureguard.mdm.screentime.vm

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.appblocker.AppInfo
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.screentime.ScreenTimeProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class ScreenTimeProfileEditState(
    val isLoading: Boolean = true,
    val isNewProfile: Boolean = true,
    val profileId: String = "",
    val name: String = "",
    val allApps: List<AppInfo> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val dailyLimitMinutes: Int = 60,
    val allowedStartHour: Int = 16,
    val allowedEndHour: Int = 20,
    val isEnabled: Boolean = true,
    val searchQuery: String = "",
    val didSave: Boolean = false
) {
    val filteredApps: List<AppInfo>
        get() = if (searchQuery.isBlank()) {
            allApps
        } else {
            allApps.filter { it.appName.contains(searchQuery, ignoreCase = true) }
        }
}

@HiltViewModel
class ScreenTimeProfileEditViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScreenTimeProfileEditState())
    val uiState = _uiState.asStateFlow()

    private var didLoad = false

    /** profileId == null פירושו יצירת פרופיל חדש. */
    fun load(profileId: String?) {
        if (didLoad) return
        didLoad = true
        viewModelScope.launch {
            val apps = getInstalledUserApps()
            val existing = profileId?.let { id ->
                settingsRepository.getScreenTimeProfiles().find { it.id == id }
            }

            if (existing == null) {
                _uiState.update {
                    it.copy(isLoading = false, isNewProfile = true, allApps = apps, name = "פרופיל חדש")
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isNewProfile = false,
                        profileId = existing.id,
                        name = existing.name,
                        allApps = apps,
                        selectedPackages = existing.appPackages,
                        dailyLimitMinutes = existing.dailyLimitMinutes,
                        allowedStartHour = existing.allowedStartHour,
                        allowedEndHour = existing.allowedEndHour,
                        isEnabled = existing.isEnabled
                    )
                }
            }
        }
    }

    private suspend fun getInstalledUserApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { appInfo ->
                appInfo.packageName != context.packageName &&
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(appInfo.packageName) != null)
            }
            .map { appInfo ->
                AppInfo(
                    appName = appInfo.loadLabel(pm).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(pm),
                    isBlocked = false,
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isLauncherApp = pm.getLaunchIntentForPackage(appInfo.packageName) != null,
                    isSuspended = false,
                    isInstalled = true
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    fun onNameChanged(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun toggleApp(packageName: String) {
        _uiState.update {
            val current = it.selectedPackages
            val updated = if (current.contains(packageName)) current - packageName else current + packageName
            it.copy(selectedPackages = updated)
        }
    }

    fun onDailyLimitChanged(minutes: Int) {
        _uiState.update { it.copy(dailyLimitMinutes = minutes.coerceIn(5, 24 * 60)) }
    }

    fun onAllowedHoursChanged(startHour: Int, endHour: Int) {
        _uiState.update { it.copy(allowedStartHour = startHour.coerceIn(0, 23), allowedEndHour = endHour.coerceIn(0, 23)) }
    }

    fun onEnabledToggled(isEnabled: Boolean) {
        _uiState.update { it.copy(isEnabled = isEnabled) }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            val profile = ScreenTimeProfile(
                id = if (state.isNewProfile) UUID.randomUUID().toString() else state.profileId,
                name = state.name.ifBlank { "פרופיל ללא שם" },
                appPackages = state.selectedPackages,
                dailyLimitMinutes = state.dailyLimitMinutes,
                allowedStartHour = state.allowedStartHour,
                allowedEndHour = state.allowedEndHour,
                isEnabled = state.isEnabled
            )
            val current = settingsRepository.getScreenTimeProfiles()
            val updated = if (state.isNewProfile) {
                current + profile
            } else {
                current.map { if (it.id == profile.id) profile else it }
            }
            settingsRepository.setScreenTimeProfiles(updated)
            _uiState.update { it.copy(didSave = true) }
        }
    }
}