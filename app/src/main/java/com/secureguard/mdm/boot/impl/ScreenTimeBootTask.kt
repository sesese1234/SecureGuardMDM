package com.secureguard.mdm.boot.impl

import android.content.Context
import com.secureguard.mdm.boot.api.BootTask
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.utils.FileLogger
import com.secureguard.mdm.utils.JobSchedulerHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ScreenTimeBootTask @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : BootTask {
    override suspend fun onBootCompleted() {
        if (settingsRepository.isScreenTimeEnabled()) {
            FileLogger.log("ScreenTimeBootTask", "Screen time limit is enabled. Rescheduling enforcer job.")
            JobSchedulerHelper.scheduleScreenTimeEnforcer(context)
        } else {
            FileLogger.log("ScreenTimeBootTask", "Screen time limit is disabled. Nothing to do.")
        }
    }
}
