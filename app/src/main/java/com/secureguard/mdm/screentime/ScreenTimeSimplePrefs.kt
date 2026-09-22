package com.secureguard.mdm.screentime

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalTime

class ScreenTimeSimplePrefs(context: Context) {

    private val prefs = context.getSharedPreferences("screen_time_simple_prefs", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)
    fun setEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun isEnforcementActive(): Boolean = prefs.getBoolean(KEY_ACTIVE, false)
    fun setEnforcementActive(value: Boolean) = prefs.edit().putBoolean(KEY_ACTIVE, value).apply()

    fun clearEnforcement() {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .remove(KEY_LAST_TARGETS)
            .apply()
    }

    fun getTempTargetsOrFallback(): Set<String> {
        val last = prefs.getStringSet(KEY_LAST_TARGETS, null)
        return last ?: ScreenTimePackages.BLOCKED_PACKAGES
    }

    fun setLastTargets(targets: Set<String>) {
        prefs.edit().putStringSet(KEY_LAST_TARGETS, targets).apply()
    }

    fun getConfig(): ScreenTimeConfig {
        return ScreenTimeConfig(
            allowedDays = setOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
            ),
            startTime = parseHHmm(prefs.getString(KEY_START, DEFAULT_START) ?: DEFAULT_START),
            endTime = parseHHmm(prefs.getString(KEY_END, DEFAULT_END) ?: DEFAULT_END),
            maxMinutesPerDay = prefs.getInt(KEY_MAX_MINUTES, DEFAULT_MAX_MINUTES),
            resetTime = parseHHmm(prefs.getString(KEY_RESET, DEFAULT_RESET) ?: DEFAULT_RESET)
        )
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACTIVE = "active"
        private const val KEY_LAST_TARGETS = "last_targets"
        private const val KEY_START = "start_hhmm"
        private const val KEY_END = "end_hhmm"
        private const val KEY_MAX_MINUTES = "max_minutes"
        private const val KEY_RESET = "reset_hhmm"

        private const val DEFAULT_START = "08:00"
        private const val DEFAULT_END = "20:00"
        private const val DEFAULT_MAX_MINUTES = 60
        private const val DEFAULT_RESET = "12:00"

        fun parseHHmm(value: String): LocalTime {
            // value expected: "HH:mm"
            val parts = value.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return LocalTime.of(h, m)
        }
    }
}

data class ScreenTimeConfig(
    val allowedDays: Set<DayOfWeek>,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val maxMinutesPerDay: Int,
    val resetTime: LocalTime,
)