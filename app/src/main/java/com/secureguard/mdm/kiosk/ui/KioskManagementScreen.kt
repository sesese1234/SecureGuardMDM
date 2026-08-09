package com.secureguard.mdm.kiosk.ui

import com.secureguard.mdm.ui.theme.SecureGuardTheme
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.secureguard.mdm.R
import com.secureguard.mdm.kiosk.vm.KioskManagementViewModel
import com.secureguard.mdm.ui.navigation.Routes
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Tungsten
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.automirrored.filled.VolumeUp

/**
 * מסך ניהול מצב קיוסק - גרסה מתוקנת ונקייה
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KioskManagementScreen(
    viewModel: KioskManagementViewModel = hiltViewModel(),
    isFromKiosk: Boolean = false,
    onNavigateBack: () -> Unit,
    onNavigateTo: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    SecureGuardTheme {
        val layoutDirection = if (java.util.Locale.getDefault().language == "iw" || java.util.Locale.getDefault().language == "he") {
            androidx.compose.ui.unit.LayoutDirection.Rtl
        } else {
            androidx.compose.ui.unit.LayoutDirection.Ltr
        }

        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides layoutDirection
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(id = R.string.settings_item_manage_kiosk)) },
                            navigationIcon = {
                                IconButton(onClick = onNavigateBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.kiosk_nav_back_desc))
                                }
                            }
                        )
                    }
                ) { padding ->
                    if (uiState.isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        // The card list is taller than the viewport on shorter screens.
                        // Without a scroll container, Arrangement.spacedBy has to
                        // distribute negative free space, which draws the action-button
                        // toggles stacked on top of each other and out of reach.
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // הפעלת מצב קיוסק
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(id = R.string.kiosk_management_enable_label),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = stringResource(id = R.string.kiosk_management_enable_description),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = uiState.isKioskModeEnabled,
                                        onCheckedChange = { viewModel.setKioskModeEnabled(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                        )
                                    )
                                }
                            }

                            // שירות ניטור
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(id = R.string.kiosk_management_monitor_label),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = stringResource(id = R.string.kiosk_management_monitor_description),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = uiState.isMonitorEnabled,
                                        onCheckedChange = { viewModel.setMonitorEnabled(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                        )
                                    )
                                }
                            }

                            // קיוסק לאתר בודד
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(id = R.string.kiosk_management_single_website_label),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = stringResource(id = R.string.kiosk_management_single_website_description),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = uiState.isSingleWebsiteKioskEnabled,
                                            onCheckedChange = { viewModel.setSingleWebsiteKioskEnabled(it) },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                            )
                                        )
                                    }

                                    if (uiState.isSingleWebsiteKioskEnabled) {
                                        OutlinedTextField(
                                            value = uiState.singleWebsiteUrl,
                                            onValueChange = { viewModel.setSingleWebsiteKioskUrl(it) },
                                            label = { Text(stringResource(id = R.string.kiosk_management_single_website_url_label)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                    }
                                }
                            }


                            // בחירת אפליקציות
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onNavigateTo(Routes.KIOSK_APP_SELECTION) }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(id = R.string.kiosk_management_select_apps),
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            // בחירת כפתורי פעולה בקיוסק
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(id = R.string.kiosk_management_action_buttons_title),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    ActionToggleRow(
                                        title = stringResource(id = R.string.kiosk_action_wifi),
                                        icon = Icons.Default.Wifi,
                                        checked = uiState.enabledActions.contains("wifi"),
                                        onCheckedChange = { viewModel.toggleActionButton("wifi", it) }
                                    )
                                    ActionToggleRow(
                                        title = stringResource(id = R.string.kiosk_action_bluetooth),
                                        icon = Icons.Default.Bluetooth,
                                        checked = uiState.enabledActions.contains("bluetooth"),
                                        onCheckedChange = { viewModel.toggleActionButton("bluetooth", it) }
                                    )
                                    ActionToggleRow(
                                        title = stringResource(id = R.string.kiosk_action_flashlight),
                                        icon = Icons.Default.Tungsten,
                                        checked = uiState.enabledActions.contains("flashlight"),
                                        onCheckedChange = { viewModel.toggleActionButton("flashlight", it) }
                                    )
                                    ActionToggleRow(
                                        title = stringResource(id = R.string.kiosk_action_volume),
                                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                                        checked = uiState.enabledActions.contains("volume"),
                                        onCheckedChange = { viewModel.toggleActionButton("volume", it) }
                                    )
                                }
                            }

                            // A weighted spacer cannot be measured inside a scrollable
                            // Column (its height is unbounded), so use a fixed gap.
                            Spacer(modifier = Modifier.height(8.dp))

                            if (uiState.isKioskModeEnabled) {
                                Button(
                                    onClick = {
                                        viewModel.saveAndApply(onComplete = onNavigateBack)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(16.dp)
                                ) {
                                    Text(
                                        text = if (isFromKiosk) stringResource(R.string.kiosk_save_and_return_to_kiosk) else stringResource(R.string.kiosk_save_and_apply),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusItem(label: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (isOk) stringResource(R.string.kiosk_status_ok) else stringResource(R.string.kiosk_status_not_set),
            color = if (isOk) Color(0xFF4CAF50) else Color(0xFFF44336),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ActionToggleRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}
