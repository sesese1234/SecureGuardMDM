package com.secureguard.mdm.settingsfeatures.impl

import com.secureguard.mdm.R
import com.secureguard.mdm.settingsfeatures.api.NavigationalSetting
import com.secureguard.mdm.settingsfeatures.api.SettingCategory
import com.secureguard.mdm.ui.navigation.Routes

object NavigateToAppSelectionSetting : NavigationalSetting {
    override val id: String = "navigate_app_selection"
    override val titleRes: Int = R.string.settings_item_select_apps_to_block
    override val iconRes: Int = R.drawable.ic_manage_apps
    override val category: SettingCategory = SettingCategory.APP_MANAGEMENT
    override val route: String = Routes.APP_SELECTION
}

object NavigateToBlockedAppsSetting : NavigationalSetting {
    override val id: String = "navigate_blocked_apps"
    override val titleRes: Int = R.string.settings_item_view_blocked_apps
    override val iconRes: Int = R.drawable.ic_apps_blocked
    override val category: SettingCategory = SettingCategory.APP_MANAGEMENT
    override val route: String = Routes.BLOCKED_APPS_DISPLAY
}

object NavigateToScreenTimeSetting : NavigationalSetting {
    override val id: String = "navigate_screen_time"
    override val titleRes: Int = R.string.settings_item_screen_time
    override val iconRes: Int = R.drawable.ic_screen_time
    override val category: SettingCategory = SettingCategory.APP_MANAGEMENT
    override val route: String = Routes.SCREEN_TIME_SETTINGS
}
