package com.secureguard.mdm.screentime

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

class ScreenTimeEnforcerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val logTag = "ScreenTimeEnforcer"

    private val dpm: DevicePolicyManager =
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val adminComponent: ComponentName =
        SecureGuardDeviceAdminReceiver.getComponentName(appContext)

    private val prefs = ScreenTimeSimplePrefs(appContext.applicationContext)

    override suspend fun doWork(): Result {
        return try {
            if (!prefs.isEnabled()) {
                stopIfNeeded()
                return Result.success()
            }

            val config = prefs.getConfig()
            val now = ZonedDateTime.now()
            val timeNow = now.toLocalTime()
            val dayNow = now.dayOfWeek

            val allowedByDay = isDayAllowed(config.allowedDays, dayNow)
            val allowedByWindow = isInsideWindow(config.startTime, config.endTime, timeNow)

            if (!allowedByDay || !allowedByWindow) {
                stopIfNeeded()
                return Result.success()
            }

            val minutesUsed = UsageMinutesToday.computeForegroundMinutesSinceReset(
                context = applicationContext,
                resetTime = config.resetTime
            )

            if (minutesUsed >= config.maxMinutesPerDay.toLong()) {
                startSuspendIfNeeded()
            } else {
                stopIfNeeded()
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(logTag, "Failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun startSuspendIfNeeded() {
        if (prefs.isEnforcementActive()) return

        val targets = ScreenTimePackages.BLOCKED_PACKAGES
        if (targets.isEmpty()) return

        prefs.setLastTargets(targets)
        prefs.setEnforcementActive(true)

        try {
            dpm.setPackagesSuspended(adminComponent, targets.toTypedArray(), true)
        } catch (e: Exception) {
            Log.e(logTag, "setPackagesSuspended(true) failed: ${e.message}", e)
        }
    }

    private fun stopIfNeeded() {
        if (!prefs.isEnforcementActive()) return

        val targets = prefs.getTempTargetsOrFallback()
        if (targets.isNotEmpty()) {
            try {
                dpm.setPackagesSuspended(adminComponent, targets.toTypedArray(), false)
            } catch (e: Exception) {
                Log.e(logTag, "setPackagesSuspended(false) failed: ${e.message}", e)
            }
        }

        prefs.clearEnforcement()
    }

    private fun isDayAllowed(allowedDays: Set<DayOfWeek>, day: DayOfWeek): Boolean =
        allowedDays.contains(day)

    private fun isInsideWindow(start: LocalTime, end: LocalTime, time: LocalTime): Boolean {
        return if (start <= end) {
            time >= start && time <= end
        } else {
            // crosses midnight
            time >= start || time <= end
        }
    }
}