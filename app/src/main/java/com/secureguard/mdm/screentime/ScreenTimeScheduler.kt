package com.secureguard.mdm.screentime

import android.content.Context
import androidx.work.*

import java.util.concurrent.TimeUnit

class ScreenTimeScheduler(private val context: Context) {

    fun start() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val request = PeriodicWorkRequestBuilder<ScreenTimeEnforcerWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "screen_time_enforcer",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun stop() {
        WorkManager.getInstance(context).cancelUniqueWork("screen_time_enforcer")
    }
}