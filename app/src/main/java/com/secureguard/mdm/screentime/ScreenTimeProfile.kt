package com.secureguard.mdm.screentime

import java.util.UUID

/**
 * פרופיל בודד של הגבלת זמן מסך.
 * ניתן להגדיר כמה פרופילים במקביל; אם אפליקציה נמצאת ביותר מפרופיל אחד,
 * חל עליה החיתוך המחמיר מכולם - היא מותרת רק אם *כל* הפרופילים שהיא
 * שייכת אליהם מאשרים אותה (גם בטווח השעות וגם מתחת למגבלת הדקות שלהם).
 */
data class ScreenTimeProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "פרופיל חדש",
    val appPackages: Set<String> = emptySet(),
    val dailyLimitMinutes: Int = 60,
    val allowedStartHour: Int = 16,
    val allowedEndHour: Int = 20,
    val isEnabled: Boolean = true
)
