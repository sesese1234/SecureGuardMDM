package com.secureguard.mdm.data.local

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesManager @Inject constructor(internal val prefs: SharedPreferences) {

    fun saveString(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    fun loadString(key: String, defaultValue: String?): String? = prefs.getString(key, defaultValue)
    fun saveBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun loadBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)
    fun saveStringSet(key: String, value: Set<String>) = prefs.edit().putStringSet(key, value).apply()
    fun loadStringSet(key: String, defaultValue: Set<String>): Set<String> = prefs.getStringSet(key, defaultValue) ?: defaultValue
    fun saveInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    fun loadInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)


    companion object {
        const val KEY_PASSWORD_HASH = "password_hash"
        const val KEY_IS_SETUP_COMPLETE = "is_setup_complete"
        const val KEY_BLOCKED_APP_PACKAGES = "blocked_app_packages"
        const val KEY_SUSPENDED_APP_PACKAGES = "suspended_app_packages"
        const val KEY_ORIGINAL_DIALER_PACKAGE = "original_dialer_package"
        const val KEY_CUSTOM_FRP_IDS = "custom_frp_ids"
        const val KEY_AUTO_UPDATE_CHECK_ENABLED = "auto_update_check_enabled"

        // --- מפתחות להתאמה אישית ונעילה ---
        const val KEY_UI_PREF_TOGGLE_ON_START = "ui_pref_toggle_on_start"
        const val KEY_UI_PREF_USE_CHECKBOX = "ui_pref_use_checkbox"
        const val KEY_UI_PREF_SHOW_CONTACT_EMAIL = "ui_pref_show_contact_email"
        const val KEY_UPDATE_PREF_DISABLE_ALL_UPDATES = "update_pref_disable_all_updates"
        const val KEY_UPDATE_CHANNEL = "update_channel"
        const val KEY_SETTINGS_LOCKED_PERMANENTLY = "settings_locked_permanently"
        const val KEY_ALLOW_MANUAL_UPDATE_WHEN_LOCKED = "allow_manual_update_when_locked"
        const val KEY_SHOW_BOOT_TOAST = "show_boot_toast"

        // --- Kiosk Mode Keys ---
        const val KEY_KIOSK_MODE_ENABLED = "kiosk_mode_enabled"
        const val KEY_KIOSK_APP_PACKAGES = "kiosk_app_packages"
        const val KEY_KIOSK_LAYOUT_JSON = "kiosk_layout_json" // --- THIS LINE IS ADDED ---
        const val KEY_KIOSK_TITLE_TEXT = "kiosk_title_text"
        const val KEY_KIOSK_SHOW_SECURE_UPDATE = "kiosk_show_secure_update"
        const val KEY_KIOSK_ACTION_BAR_ITEMS = "kiosk_action_bar_items"
        const val KEY_KIOSK_DATE_FORMAT = "kiosk_date_format" // shhhhhhhhh...
        const val KEY_KIOSK_BACKGROUND_COLOR = "kiosk_background_color"
        // --- הוספה: מפתח לשמירת הצבע הראשי (Primary/Accent) של הקיוסק ---
        const val KEY_KIOSK_PRIMARY_COLOR = "kiosk_primary_color"
        const val KEY_KIOSK_BLOCKED_LAUNCHER_PKG = "kiosk_blocked_launcher_pkg"
        const val KEY_KIOSK_ALLOW_SETTINGS_IN_LOCK_TASK = "kiosk_allow_settings_in_lock_task"
        const val KEY_CHOSEN_HOME_LAUNCHER_PKG = "chosen_home_launcher_pkg"
        const val KEY_DONT_SHOW_HOME_CHOICE_AGAIN = "dont_show_home_choice_again"
        const val KEY_KIOSK_APP_MONITOR_ENABLED = "kiosk_app_monitor_enabled"

        // --- Screen Time Limit Keys ---
        const val KEY_SCREEN_TIME_ENABLED = "screen_time_enabled"
        const val KEY_SCREEN_TIME_APP_PACKAGES = "screen_time_app_packages"
        const val KEY_SCREEN_TIME_DAILY_LIMIT_MINUTES = "screen_time_daily_limit_minutes"
        const val KEY_SCREEN_TIME_ALLOWED_START_HOUR = "screen_time_allowed_start_hour"
        const val KEY_SCREEN_TIME_ALLOWED_END_HOUR = "screen_time_allowed_end_hour"
        // רשימת החבילות שהושעו ספציפית ע"י פיצ'ר הגבלת זמן המסך (כדי לא להתנגש עם השעיות של AppBlocker)
        const val KEY_SCREEN_TIME_SUSPENDED_PACKAGES = "screen_time_suspended_packages"
    }
}
