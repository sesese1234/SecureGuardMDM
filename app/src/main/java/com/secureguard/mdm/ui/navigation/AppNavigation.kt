package com.secureguard.mdm.ui.navigation

import android.content.Context
import android.content.Intent
import android.app.admin.DevicePolicyManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.secureguard.mdm.appblocker.ui.AppSelectionScreen
import com.secureguard.mdm.appblocker.ui.BlockedAppsScreen
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.kiosk.ui.KioskAppSelectionScreen
import com.secureguard.mdm.kiosk.ui.KioskManagementScreen
import com.secureguard.mdm.ui.screens.changepassword.ChangePasswordScreen
import com.secureguard.mdm.ui.screens.dashboard.DashboardScreen
import com.secureguard.mdm.ui.screens.frpsettings.FrpSettingsScreen
import com.secureguard.mdm.ui.screens.provisioning.ProvisioningScreen
import com.secureguard.mdm.ui.screens.settings.SettingsScreen
import com.secureguard.mdm.ui.screens.setup.SetupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Routes {
    const val PROVISIONING = "provisioning"
    const val SETUP = "setup"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val CHANGE_PASSWORD = "change_password"
    const val APP_SELECTION = "app_selection"
    const val BLOCKED_APPS_DISPLAY = "blocked_apps_display"
    const val FRP_SETTINGS = "frp_settings"
    const val KIOSK_MANAGEMENT = "kiosk_management"
    const val KIOSK_APP_SELECTION = "kiosk_app_selection"
    const val SCREEN_TIME_SETTINGS = "screen_time_settings"
}

@Composable
fun AppNavigation(
    settingsRepository: SettingsRepository = hiltViewModel<DummyViewModel>().settingsRepository,
    startDestinationOverride: String? = null,
    isFromKiosk: Boolean = false
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    var refreshTrigger by remember { mutableStateOf(false) }

    val startDestinationState = produceState<String?>(initialValue = null, key1 = refreshTrigger, key2 = startDestinationOverride) {
        value = if (startDestinationOverride != null) {
            startDestinationOverride
        } else {
            withContext(Dispatchers.IO) {
                val isAdmin = dpm.isDeviceOwnerApp(context.packageName)
                val isSetupComplete = settingsRepository.isSetupComplete()

                when {
                    !isAdmin -> Routes.PROVISIONING
                    !isSetupComplete -> Routes.SETUP
                    else -> Routes.DASHBOARD
                }
            }
        }
    }

    val startDestination = startDestinationState.value

    if (startDestination != null) {
        NavHost(navController = navController, startDestination = startDestination) {
            composable(Routes.PROVISIONING) {
                ProvisioningScreen(onCheckAgain = { refreshTrigger = !refreshTrigger })
            }
            composable(Routes.SETUP) {
                SetupScreen(onSetupComplete = {
                    navController.navigate(Routes.DASHBOARD) { popUpTo(Routes.SETUP) { inclusive = true } }
                })
            }
            composable(Routes.DASHBOARD) {
                DashboardScreen(onNavigateToSettings = { navController.navigate(Routes.SETTINGS) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateTo = { route -> navController.navigate(route) }
                )
            }
            composable(Routes.CHANGE_PASSWORD) {
                ChangePasswordScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Routes.APP_SELECTION) {
                AppSelectionScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Routes.BLOCKED_APPS_DISPLAY) {
                BlockedAppsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Routes.FRP_SETTINGS) {
                FrpSettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Routes.KIOSK_MANAGEMENT) {
                KioskManagementScreen(
                    isFromKiosk = isFromKiosk,
                    onNavigateBack = {
                        if (isFromKiosk) {
                            val intent = Intent(context, com.secureguard.mdm.kiosk.ui.KioskActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            context.startActivity(intent)
                            (context as? android.app.Activity)?.finish()
                        } else {
                            navController.popBackStack()
                        }
                    },
                    onNavigateTo = { route -> navController.navigate(route) }
                )
            }
            composable(Routes.KIOSK_APP_SELECTION) {
                KioskAppSelectionScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Routes.SCREEN_TIME_SETTINGS) {
                com.secureguard.mdm.screentime.ui.ScreenTimeSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToProfileEdit = { profileId ->
                        // Navigate to profile edit screen. Use an empty string for new profile.
                        val idSegment = profileId ?: ""
                        navController.navigate("/screentime/edit/$idSegment")
                    }
                )
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class DummyViewModel @javax.inject.Inject constructor(
    val settingsRepository: SettingsRepository
) : androidx.lifecycle.ViewModel()
