package com.secureguard.mdm.screentime

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object UsageMinutesToday {

    fun computeForegroundMinutesSinceReset(
        context: Context,
        resetTime: LocalTime
    ): Long {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val now = ZonedDateTime.now()
        val zone = ZoneId.systemDefault()

        val resetDate: LocalDate = if (now.toLocalTime().isBefore(resetTime)) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }

        val startMillis = resetDate
            .atTime(resetTime)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val endMillis = System.currentTimeMillis()

        val stats: List<UsageStats> = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            startMillis,
            endMillis
        ) ?: emptyList()

        val totalForegroundMs = stats.sumOf { it.totalTimeInForeground ?: 0L }
        return totalForegroundMs / 60000L
    }
}