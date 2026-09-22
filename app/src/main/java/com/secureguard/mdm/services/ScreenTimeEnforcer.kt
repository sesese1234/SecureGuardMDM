package com.secureguard.mdm.services

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.utils.FileLogger
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * הלוגיקה המרכזית של הגבלת זמן המסך.
 * תומך בכמה פרופילים במקביל. אם אפליקציה שייכת ליותר מפרופיל אחד,
 * חל עליה החיתוך המחמיר: היא מותרת רק אם *כל* הפרופילים שהיא שייכת אליהם
 * מאשרים אותה (גם בטווח השעות שלהם וגם מתחת למגבלת הדקות שלהם).
 */
object ScreenTimeEnforcer {

    private const val TAG = "ScreenTimeEnforcer"

    /** בודק אם למשתמש/אפליקציה יש הרשאת "Usage Access" (נדרשת ידנית מהמשתמש). */
    fun hasUsageAccessPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * מריץ מחזור בדיקה אחד על פני כל הפרופילים הפעילים: קורא הגדרות,
     * ממזג אותן לפי "הכי מחמיר מנצח", ומשעה/משחרר אפליקציות בהתאם.
     */
    suspend fun runEnforcementCycle(context: Context, settingsRepository: SettingsRepository) {
        if (!settingsRepository.isScreenTimeEnabled()) {
            releaseAllSuspensions(context, settingsRepository)
            return
        }

        if (!hasUsageAccessPermission(context)) {
            FileLogger.log(TAG, "Missing Usage Access permission. Cannot enforce screen time limits.")
            return
        }

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = SecureGuardDeviceAdminReceiver.getComponentName(context)
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            FileLogger.log(TAG, "App is not Device Owner. Cannot suspend apps.")
            return
        }

        val profiles = settingsRepository.getScreenTimeProfiles().filter { it.isEnabled }
        val previouslySuspended = settingsRepository.getScreenTimeSuspendedPackages()

        if (profiles.isEmpty()) {
            releaseAllSuspensions(context, settingsRepository)
            return
        }

        // כל האפליקציות המנוהלות ע"י לפחות פרופיל אחד
        val managedPackages = profiles.flatMap { it.appPackages }.toSet()
        val usageByPackage = getTodayUsageMinutes(context, managedPackages)

        val stillSuspended = mutableSetOf<String>()

        for (packageName in managedPackages) {
            val relevantProfiles = profiles.filter { it.appPackages.contains(packageName) }
            val usedMinutes = usageByPackage[packageName] ?: 0L

            // "הכי מחמיר מנצח": מספיק שפרופיל אחד חוסם כדי שהאפליקציה תושעה.
            val shouldBeSuspended = relevantProfiles.any { profile ->
                val overDailyLimit = usedMinutes >= profile.dailyLimitMinutes
                val outsideWindow = !isCurrentHourInAllowedWindow(profile.allowedStartHour, profile.allowedEndHour)
                overDailyLimit || outsideWindow
            }

            if (shouldBeSuspended) {
                if (!isPackageSuspended(dpm, admin, packageName)) {
                    trySetSuspended(dpm, admin, packageName, true)
                    FileLogger.log(
                        TAG,
                        "Suspended $packageName (usedMinutes=$usedMinutes, profiles=${relevantProfiles.map { it.name }})"
                    )
                }
                stillSuspended.add(packageName)
            } else if (previouslySuspended.contains(packageName)) {
                trySetSuspended(dpm, admin, packageName, false)
                FileLogger.log(TAG, "Released $packageName (back within limits/windows of all its profiles)")
            }
        }

        // ניקוי: אפליקציות שהוסרו מכל הפרופילים אך עדיין רשומות כמושעות ע"י הפיצ'ר
        val removedFromAllProfiles = previouslySuspended - managedPackages
        removedFromAllProfiles.forEach { packageName ->
            trySetSuspended(dpm, admin, packageName, false)
            FileLogger.log(TAG, "Released $packageName (no longer managed by any profile)")
        }

        settingsRepository.setScreenTimeSuspendedPackages(stillSuspended)
    }

    /** משחרר את כל האפליקציות שהושעו ע"י פיצ'ר זה (למשל כשהמשתמש מכבה את הפיצ'ר). */
    suspend fun releaseAllSuspensions(context: Context, settingsRepository: SettingsRepository) {
        val suspended = settingsRepository.getScreenTimeSuspendedPackages()
        if (suspended.isEmpty()) return

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = SecureGuardDeviceAdminReceiver.getComponentName(context)

        suspended.forEach { packageName -> trySetSuspended(dpm, admin, packageName, false) }
        settingsRepository.setScreenTimeSuspendedPackages(emptySet())
        FileLogger.log(TAG, "Released all screen-time suspensions.")
    }

    private fun trySetSuspended(
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
        packageName: String,
        suspended: Boolean
    ) {
        try {
            dpm.setPackagesSuspended(admin, arrayOf(packageName), suspended)
        } catch (e: Exception) {
            FileLogger.log(TAG, "Failed to set suspended=$suspended for $packageName: ${e.message}")
        }
    }

    private fun isPackageSuspended(
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
        packageName: String
    ): Boolean {
        return try {
            dpm.isPackageSuspended(admin, packageName)
        } catch (e: Exception) {
            false
        }
    }

    /** true אם השעה הנוכחית בטווח המותר. תומך גם בטווח שחוצה חצות (למשל 22 -> 06). */
    private fun isCurrentHourInAllowedWindow(startHour: Int, endHour: Int): Boolean {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (startHour == endHour) {
            true
        } else if (startHour < endHour) {
            currentHour in startHour until endHour
        } else {
            currentHour >= startHour || currentHour < endHour
        }
    }

    /** מחזיר מפה של package -> דקות שימוש היום (מתחילת היום ועד עכשיו), לפי UsageStatsManager. */
    private fun getTodayUsageMinutes(context: Context, packages: Set<String>): Map<String, Long> {
        if (packages.isEmpty()) return emptyMap()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        val now = System.currentTimeMillis()

        return try {
            val statsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
            statsList
                .filter { packages.contains(it.packageName) }
                .groupBy { it.packageName }
                .mapValues { (_, stats) ->
                    TimeUnit.MILLISECONDS.toMinutes(stats.sumOf { it.totalTimeInForeground })
                }
        } catch (e: Exception) {
            FileLogger.log(TAG, "Failed to query usage stats: ${e.message}")
            emptyMap()
        }
    }
}