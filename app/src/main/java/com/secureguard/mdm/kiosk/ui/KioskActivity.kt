package com.secureguard.mdm.kiosk.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.secureguard.mdm.R
import com.secureguard.mdm.boot.BootCompletedReceiver
import com.secureguard.mdm.data.local.PreferencesManager
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.kiosk.manager.KioskLockManager
import com.secureguard.mdm.ui.theme.SecureGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity להצגת מסך הקיוסק.
 * פועל כ-Launcher אלטרנטיבי כאשר מצב קיוסק מופעל.
 */
@AndroidEntryPoint
class KioskActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var prefs: SharedPreferences

    @Inject
    lateinit var kioskLockManager: KioskLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isLaunchedAsHome = intent.action == Intent.ACTION_MAIN &&
                intent.hasCategory(Intent.CATEGORY_HOME)
        
        // בדיקה אם נפתח בתור Viewer (חדש - בליעת ה-Intent ב"הגבלה מחמירה")
        val isViewer = intent.action == Intent.ACTION_VIEW
        if (isViewer) {
            lifecycleScope.launch {
                if (settingsRepository.isKioskAppMonitorEnabled()) {
                    Log.d("KioskActivity", "Strict Mode: Absorbing ACTION_VIEW: ${intent.data}")
                    // אנחנו לא עושים כלום - פשוט נמנעים מביצוע ה-Intent
                } else {
                    // אם הגבלה מחמירה כבויה, פשוט נסגור את הניסיון (או נטפל בו כרגיל אם בעתיד תרצה)
                    finish()
                }
            }
        }

        // אם הופעלו כ-Home, נרצה שהרקע יהיה חלק מהאפליקציה ולא שקוף/צף
        if (isLaunchedAsHome) {
            setTheme(R.style.Theme_SecureGuard_Kiosk)
        }

        lifecycleScope.launch {
            // A failure reading settings used to abort before setContent ran, leaving the
            // launcher-replacing activity blank and the device effectively unlocked.
            // Fail closed instead: assume the kiosk is on and still draw it.
            val kioskEnabled = runCatching { settingsRepository.isKioskModeEnabled() }.getOrElse { e ->
                Log.e("KioskActivity", "Failed to read kiosk state; assuming enabled.", e)
                true
            }
            val chosenLauncher = runCatching { settingsRepository.getChosenHomeLauncherPackage() }.getOrNull()
            val dontShowAgain = runCatching { settingsRepository.shouldNotShowHomeChoiceAgain() }.getOrDefault(false)

            // הפעל משימות BOOT רק אם הן עוד לא הופעלו בתהליך זה (קורה בדרך כלל בשיגור כ-Launcher)
            if (isLaunchedAsHome) {
                BootCompletedReceiver.simulateBootCompleted(this@KioskActivity)
            }

            val systemDirection = if (java.util.Locale.getDefault().language == "iw" || java.util.Locale.getDefault().language == "he" || java.util.Locale.getDefault().language == "en") {
                androidx.compose.ui.unit.LayoutDirection.Rtl
            } else {
                androidx.compose.ui.unit.LayoutDirection.Ltr
            }

            // אם מצב קיוסק כבוי ואנחנו משמשים כ-Launcher
            if (isLaunchedAsHome && !kioskEnabled) {
                if (chosenLauncher != null && dontShowAgain) {
                    launchHomeApp(chosenLauncher)
                    finish()
                    return@launch
                }

                // הצג מסך בחירת לאנצ'ר
                setContent {
                    SecureGuardTheme {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalLayoutDirection provides systemDirection
                        ) {
                            HomeLauncherSelectionScreen(
                                onDismiss = {
                                    val intent = Intent(this@KioskActivity, com.secureguard.mdm.MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    }
                                    startActivity(intent)
                                    finish()
                                }
                            )
                        }
                    }
                }
                return@launch
            }

            // הצג את מסך הקיוסק
            val isSingleEnabled = runCatching { settingsRepository.isSingleWebsiteKioskEnabled() }.getOrDefault(false)
            val singleUrl = runCatching { settingsRepository.getSingleWebsiteKioskUrl() }.getOrDefault("")

            setContent {
                SecureGuardTheme {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalLayoutDirection provides systemDirection
                    ) {
                        if (isSingleEnabled && singleUrl.isNotEmpty()) {
                            SingleWebsiteKioskScreen(url = singleUrl)
                        } else {
                            KioskScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        
        lifecycleScope.launch {
            val kioskEnabled = settingsRepository.isKioskModeEnabled()
            val isLaunchedAsHome = intent.action == Intent.ACTION_MAIN &&
                    intent.hasCategory(Intent.CATEGORY_HOME)

            if (!kioskEnabled) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    if (activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
                        try {
                            stopLockTask()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                if (!isLaunchedAsHome) {
                    val intent = Intent(this@KioskActivity, com.secureguard.mdm.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(intent)
                    finish()
                }
                return@launch
            }

            // If we are not on the lock-task allowlist there is nothing to fall back on
            // and the device sits on the kiosk screen completely unlocked. That happens
            // whenever the allowlist was lost (a reboot before it was re-applied, or an
            // owner change), so re-apply it and try once more instead of giving up.
            if (!kioskLockManager.isLockTaskPermitted()) {
                Log.w("KioskActivity", "Lock task not permitted; re-applying lock task packages.")
                try {
                    kioskLockManager.setLockTaskPackages()
                } catch (e: Exception) {
                    Log.e("KioskActivity", "Failed to re-apply lock task packages", e)
                }
            }

            if (kioskLockManager.isLockTaskPermitted()) {
                // בדוק אם אנחנו כבר ב-Lock Task
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    if (activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                        try {
                            startLockTask()
                        } catch (e: Exception) {
                            Log.e("KioskActivity", "startLockTask failed", e)
                        }
                    }
                }
            } else {
                Log.e("KioskActivity", "Kiosk is enabled but lock task is still not permitted.")
            }
        }
    }

    private fun launchHomeApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }
}
