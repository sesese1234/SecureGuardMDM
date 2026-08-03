package com.secureguard.mdm.services

import android.app.job.JobParameters
import android.app.job.JobService
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.utils.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * JobService המופעל מחדש כל דקה (ראה JobSchedulerHelper) ובודק אם יש
 * צורך להשעות או לשחרר אפליקציות בהתאם למגבלות הגבלת זמן המסך.
 */
@AndroidEntryPoint
class ScreenTimeEnforcerJob : JobService() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartJob(params: JobParameters?): Boolean {
        jobScope.launch {
            try {
                ScreenTimeEnforcer.runEnforcementCycle(applicationContext, settingsRepository)
            } catch (e: Exception) {
                FileLogger.log("ScreenTimeEnforcerJob", "Error during enforcement cycle: ${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }
}
