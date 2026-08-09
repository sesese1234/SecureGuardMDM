package com.secureguard.mdm.kiosk.ui

import com.secureguard.mdm.ui.theme.SecureGuardTheme
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.secureguard.mdm.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.secureguard.mdm.MainActivity
import com.secureguard.mdm.appblocker.AppInfo
import com.secureguard.mdm.kiosk.vm.KioskViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import android.provider.Settings
import android.media.AudioManager
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.saveable.rememberSaveable
private val BackgroundColor = Color(0xFFE8E8EC)
private val CardColor = Color(0xFFD8D8DC)
private val TextColor = Color(0xFF1A1A1A)
private val BottomBarColor = Color(0xFFD0D0D4)
private const val TAG_KIOSK_UI = "KioskUi"
private const val ACTION_WIFI = "wifi"
private const val ACTION_BLUETOOTH = "bluetooth"
private const val ACTION_FLASHLIGHT = "flashlight"
private const val ACTION_VOLUME = "volume"

/**
 * מסך הקיוסק הראשי - עיצוב כרטיסיות אופקיות
 */
@RequiresApi(Build.VERSION_CODES.M)
@Composable
fun KioskScreen(
    viewModel: KioskViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showPasswordDialog by remember { mutableStateOf(false) }
    var showSettingsChoiceDialog by remember { mutableStateOf(false) }
    var showPowerDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Side effects
    LaunchedEffect(Unit) {
        viewModel.sideEffect.collectLatest { effect ->
            when (effect) {
                is KioskViewModel.SideEffect.LaunchApp -> launchApp(context, effect.packageName)
                is KioskViewModel.SideEffect.NavigateToSettings -> navigateToDashboard(context)
                is KioskViewModel.SideEffect.NavigateToKioskManagement -> navigateToKioskManagement(context)
                is KioskViewModel.SideEffect.NavigateToAstore -> navigateToAstore(context)
                is KioskViewModel.SideEffect.ShowToast -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // חסימת כפתור Back
    BackHandler { /* Block */ }

    val systemDirection = if (Locale.getDefault().language == "iw" || Locale.getDefault().language == "he") {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    LaunchedEffect(systemDirection) {
        Log.d(TAG_KIOSK_UI, "Computed systemDirection=$systemDirection from locale=${Locale.getDefault().language}")
    }

    SecureGuardTheme(overrideStatusBarColor = BackgroundColor) {
        CompositionLocalProvider(LocalLayoutDirection provides systemDirection) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = BackgroundColor
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Main Content Area
                        Box(modifier = Modifier.weight(1f)) {
                            if (uiState.isLoading) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                }
                            } else if (uiState.apps.isEmpty() && !uiState.isLoading) {
                                EmptyKioskState(
                                    onAddApps = {
                                        showPasswordDialog = true
                                    }
                                )
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(1),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 24.dp),
                                    contentPadding = PaddingValues(top = 80.dp, bottom = 120.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    if (uiState.isAstoreEnabled) {
                                        item {
                                            AstoreCard(
                                                onClick = { viewModel.onAstoreClick() },
                                                isRtl = systemDirection == LayoutDirection.Rtl
                                            )
                                        }
                                    }
                                    items(uiState.apps) { app ->
                                        AppCard(
                                            app = app,
                                            onClick = { viewModel.onAppClick(app.packageName) },
                                            isRtl = systemDirection == LayoutDirection.Rtl
                                        )
                                    }
                                }
                            }
                        }

                        // Bottom Bar
                        KioskBottomBar(
                            enabledActions = uiState.enabledActions,
                            onWifiClick = { openWifiSettings(context) },
                            onBluetoothClick = { openBluetoothSettings(context) },
                            onFlashlightToggle = { toggleFlashlight(context) },
                            onVolumeClick = { openVolumePanel(context) }
                        )
                    }

                    // Floating Top Bar
                    KioskTopBar(
                        currentTime = uiState.currentTime,
                        batteryLevel = uiState.batteryLevel,
                        jewishDate = uiState.jewishDate,
                        onSettingsClick = { showPasswordDialog = true },
                        onPowerClick = { showPowerDialog = true },
                        isRtl = systemDirection == LayoutDirection.Rtl
                    )

                    // Dialogs
                    if (showPasswordDialog) {
                        PasswordDialog(
                            onDismiss = { showPasswordDialog = false },
                            onConfirm = { password ->
                                scope.launch {
                                    if (viewModel.verifyPasswordResult(password)) {
                                        showPasswordDialog = false
                                        showSettingsChoiceDialog = true
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.dialog_error_wrong_password), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }

                    if (showSettingsChoiceDialog) {
                        SettingsChoiceDialog(
                            onDismiss = { showSettingsChoiceDialog = false },
                            onChoice = { choice ->
                                showSettingsChoiceDialog = false
                                if (choice == "kiosk") {
                                    navigateToKioskManagement(context)
                                } else {
                                    navigateToDashboard(context)
                                }
                            }
                        )
                    }

                    if (showPowerDialog) {
                        PowerDialog(
                            onDismiss = { showPowerDialog = false },
                            onConfirmReboot = {
                                viewModel.rebootDevice()
                                showPowerDialog = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KioskTopBar(
    currentTime: String,
    batteryLevel: Int,
    jewishDate: String,
    onSettingsClick: () -> Unit,
    onPowerClick: () -> Unit,
    isRtl: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Group 1: Power button on one side
        IconButton(
            onClick = onPowerClick,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.PowerSettingsNew,
                contentDescription = stringResource(id = R.string.kiosk_desc_power),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }

        // Group 2: Time and Date in the middle
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = currentTime,
                color = TextColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = jewishDate,
                color = TextColor.copy(alpha = 0.7f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }

        // Group 3: Battery and Settings on the other side
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "$batteryLevel%",
                color = TextColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(id = R.string.kiosk_desc_settings),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun AppCard(
    app: AppInfo,
    onClick: () -> Unit,
    isRtl: Boolean
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = CardColor,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isRtl) Arrangement.End else Arrangement.Start
        ) {
            if (!isRtl) {
                Image(
                    painter = rememberDrawablePainter(drawable = app.icon),
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Spacer(modifier = Modifier.width(20.dp))
            }

            Text(
                text = app.appName,
                color = TextColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = if (isRtl) TextAlign.End else TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (isRtl) {
                Spacer(modifier = Modifier.width(20.dp))
                Image(
                    painter = rememberDrawablePainter(drawable = app.icon),
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            }
        }
    }
}

@Composable
private fun KioskBottomBar(
    enabledActions: Set<String>,
    onWifiClick: () -> Unit,
    onBluetoothClick: () -> Unit,
    onFlashlightToggle: () -> Unit,
    onVolumeClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (enabledActions.contains(ACTION_WIFI)) {
            BottomBarButton(
                icon = Icons.Default.Wifi,
                onClick = {
                    Log.d(TAG_KIOSK_UI, "Bottom bar action clicked: wifi")
                    onWifiClick()
                }
            )
        }
        if (enabledActions.contains(ACTION_BLUETOOTH)) {
            BottomBarButton(
                icon = Icons.Default.Bluetooth,
                onClick = {
                    Log.d(TAG_KIOSK_UI, "Bottom bar action clicked: bluetooth")
                    onBluetoothClick()
                }
            )
        }
        if (enabledActions.contains(ACTION_FLASHLIGHT)) {
            BottomBarButton(
                icon = Icons.Default.Tungsten,
                onClick = {
                    Log.d(TAG_KIOSK_UI, "Bottom bar action clicked: flashlight")
                    onFlashlightToggle()
                }
            )
        }
        if (enabledActions.contains(ACTION_VOLUME)) {
            BottomBarButton(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                onClick = {
                    Log.d(TAG_KIOSK_UI, "Bottom bar action clicked: volume")
                    onVolumeClick()
                }
            )
        }
    }
}

private fun openWifiSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        Log.w(TAG_KIOSK_UI, "Failed to open Wi-Fi settings", e)
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (fallback: Exception) {
            Log.w(TAG_KIOSK_UI, "Failed to open fallback settings", fallback)
            Toast.makeText(context, context.getString(R.string.kiosk_toast_wifi_error), Toast.LENGTH_SHORT).show()
        }
    }
}

private fun openBluetoothSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        Log.w(TAG_KIOSK_UI, "Failed to open Bluetooth settings", e)
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (fallback: Exception) {
            Log.w(TAG_KIOSK_UI, "Failed to open fallback settings", fallback)
            Toast.makeText(context, context.getString(R.string.kiosk_toast_bluetooth_error), Toast.LENGTH_SHORT).show()
        }
    }
}

private fun openVolumePanel(context: Context) {
    try {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    } catch (e: Exception) {
        Log.w(TAG_KIOSK_UI, "Failed to open volume panel", e)
    }
}

/**
 * Tracks the torch state in-process.
 *
 * The previous implementation persisted the state with Settings.Global.putInt, which
 * requires WRITE_SECURE_SETTINGS. The write always threw a SecurityException, so the
 * stored state stayed 0 and every tap re-ran setTorchMode(id, true) — the flashlight
 * could be switched on but never off. The real state is instead mirrored from the
 * framework's own torch callback, which also keeps us in sync when something else
 * (or the camera being opened) turns the torch off.
 */
@RequiresApi(Build.VERSION_CODES.M)
private object FlashlightController {

    @Volatile
    private var torchOn: Boolean = false
    private var registered: Boolean = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == flashCameraId) torchOn = enabled
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == flashCameraId) torchOn = false
        }
    }

    @Volatile
    private var flashCameraId: String? = null

    /** Returns the first camera that actually reports a flash unit. */
    private fun resolveFlashCameraId(cameraManager: CameraManager): String? {
        flashCameraId?.let { return it }
        val id = cameraManager.cameraIdList.firstOrNull { candidate ->
            cameraManager.getCameraCharacteristics(candidate)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        flashCameraId = id
        return id
    }

    fun toggle(context: Context) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            Log.w(TAG_KIOSK_UI, "CameraManager not available")
            return
        }
        try {
            val cameraId = resolveFlashCameraId(cameraManager)
            if (cameraId == null) {
                Log.w(TAG_KIOSK_UI, "Flash not available on this device")
                return
            }

            if (!registered) {
                // Delivered on the main looper; seeds torchOn with the current state.
                cameraManager.registerTorchCallback(torchCallback, null)
                registered = true
            }

            val newState = !torchOn
            cameraManager.setTorchMode(cameraId, newState)
            torchOn = newState
            Log.d(TAG_KIOSK_UI, "Flashlight toggled to $newState")
        } catch (e: Exception) {
            Log.w(TAG_KIOSK_UI, "Failed to toggle flashlight", e)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.M)
private fun toggleFlashlight(context: Context) = FlashlightController.toggle(context)

@Composable
private fun BottomBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = CardColor,
        modifier = Modifier.size(56.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun PasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.kiosk_dialog_admin_title)) },
        text = {
            Column {
                Text(stringResource(id = R.string.kiosk_dialog_admin_message))
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(id = R.string.kiosk_dialog_admin_label_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }) {
                Text(stringResource(id = R.string.dialog_button_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.dialog_button_cancel))
            }
        }
    )
}

@Composable
private fun PowerDialog(
    onDismiss: () -> Unit,
    onConfirmReboot: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.kiosk_dialog_power_title)) },
        text = {
            Column {
                Text(stringResource(id = R.string.kiosk_dialog_power_message))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(id = R.string.kiosk_dialog_power_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmReboot,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(stringResource(id = R.string.kiosk_dialog_power_button_reboot))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.dialog_button_cancel))
            }
        }
    )
}


@Composable
private fun SettingsChoiceDialog(
    onDismiss: () -> Unit,
    onChoice: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.kiosk_dialog_choice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(id = R.string.kiosk_dialog_choice_message))
                
                Button(
                    onClick = { onChoice("kiosk") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(id = R.string.kiosk_dialog_choice_button_kiosk))
                }
                
                OutlinedButton(
                    onClick = { onChoice("dashboard") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(id = R.string.kiosk_dialog_choice_button_dashboard))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.dialog_button_cancel))
            }
        }
    )
}

private fun launchApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent != null) {
        context.startActivity(intent)
    } else {
        Toast.makeText(context, context.getString(R.string.kiosk_toast_app_not_found), Toast.LENGTH_SHORT).show()
    }
}

private fun navigateToDashboard(context: Context) {
    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra("start_destination", "dashboard")
        putExtra("is_from_kiosk", true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    context.startActivity(intent)
    (context as? KioskActivity)?.finish()
}

private fun navigateToKioskManagement(context: Context) {
    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra("start_destination", "kiosk_management")
        putExtra("is_from_kiosk", true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    context.startActivity(intent)
    (context as? KioskActivity)?.finish()
}

@Composable
private fun EmptyKioskState(onAddApps: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Apps,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = Color.Gray.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(id = R.string.kiosk_empty_state_title),
            fontSize = 20.sp,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onAddApps,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(id = R.string.kiosk_empty_state_button))
        }
    }
}

@Composable
private fun AstoreCard(
    onClick: () -> Unit,
    isRtl: Boolean
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isRtl) Arrangement.End else Arrangement.Start
        ) {
            if (!isRtl) {
                Image(
                    painter = painterResource(id = R.drawable.ic_astore),
                    contentDescription = null,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Spacer(modifier = Modifier.width(20.dp))
            }

            Text(
                text = stringResource(R.string.astore_title),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = if (isRtl) TextAlign.End else TextAlign.Start,
                modifier = Modifier.weight(1f)
            )

            if (isRtl) {
                Spacer(modifier = Modifier.width(20.dp))
                Image(
                    painter = painterResource(id = R.drawable.ic_astore),
                    contentDescription = null,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            }
        }
    }
}

private fun navigateToAstore(context: Context) {
    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra("start_destination", "astore")
        putExtra("is_from_kiosk", true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    context.startActivity(intent)
    (context as? KioskActivity)?.finish()
}
