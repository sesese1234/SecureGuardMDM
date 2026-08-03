package com.secureguard.mdm.settingsfeatures.registry

import com.secureguard.mdm.settingsfeatures.api.SettingsFeature
import com.secureguard.mdm.settingsfeatures.impl.*

/**
 * מרשם מרכזי לכל פריטי ההגדרות באפליקציה.
 * מסך ההגדרות יבנה את עצמו באופן דינמי על סמך רשימה זו.
 * כדי להוסיף פריט הגדרה חדש, יש ליצור אותו בחבילת 'impl' ולהוסיף אותו כאן.
 */
object SettingsRegistry {

    val allSettings: List<SettingsFeature> = listOf(
        // קטגוריית ניהול אפליקציות
        NavigateToAppSelectionSetting,
        NavigateToBlockedAppsSetting,
        NavigateToScreenTimeSetting,

        // קטגוריית התאמה אישית והתנהגות
        ToggleUiPositionSetting,
        ToggleUiControlTypeSetting,
        ToggleContactEmailSetting,
        ShowBootToastSetting,

        // קטגוריית פעולות מתקדמות
        UpdateChannelAction,
        NavigateToKioskModeSetting,
        NavigateToFrpSetting,
        NavigateToChangePasswordSetting,
        ToggleUpdatesSetting,
        LockSettingsAction,
        RemovalOptionsAction
    )
}
