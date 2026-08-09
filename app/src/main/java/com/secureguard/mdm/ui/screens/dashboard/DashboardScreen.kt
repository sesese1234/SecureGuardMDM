package com.secureguard.mdm.ui.screens.dashboard

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.secureguard.mdm.R
import com.secureguard.mdm.ui.components.PasswordPromptDialog
import com.secureguard.mdm.ui.screens.dashboard.update.UpdateDialog
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit,
    onNavigateToAstore: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showAppInfoDialog by remember { mutableStateOf(false) }
    var showAppNotInstalledDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadInitialState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onEvent(DashboardEvent.OnUpdateFileSelected(result.data?.data))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collectLatest { onNavigateToSettings() }
    }

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collectLatest { effect ->
            when (effect) {
                is DashboardSideEffect.ToastMessage -> Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
                is DashboardSideEffect.ShowAppNotInstalledDialog -> showAppNotInstalledDialog = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.dashboard_title)) },
                actions = {
                    if (uiState.isManualUpdateEnabled) {
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "application/vnd.android.package-archive"
                            }
                            filePickerLauncher.launch(intent)
                        }) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = stringResource(R.string.dashboard_button_update_app))
                        }
                    }
                    if (uiState.isAstoreEnabled) {
                        IconButton(onClick = onNavigateToAstore) {
                            Icon(painter = painterResource(id = R.drawable.ic_astore), contentDescription = stringResource(R.string.astore_title), tint = Color.Unspecified)
                        }
                    }
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.logs_title))
                    }
                    IconButton(onClick = { showAppInfoDialog = true }) {
                        Icon(painterResource(id = R.drawable.ic_info), contentDescription = stringResource(R.string.dashboard_button_about_app))
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.isSettingsButtonVisible) {
                Button(
                    onClick = { viewModel.onEvent(DashboardEvent.OnSettingsClicked) },
                    // targetSdk 35+ forces edge-to-edge, so the window no longer insets
                    // itself for the system bars. Without this the button is drawn
                    // underneath the navigation buttons and cannot be tapped.
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    Text(stringResource(id = R.string.dashboard_button_settings))
                }
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (uiState.isNetfreeFeatureActive) {
                        item {
                            NetfreeStatusCard(
                                uiState = uiState,
                                onRecheck = { viewModel.onEvent(DashboardEvent.OnNetfreeRecheckClicked) },
                                onRestart = { viewModel.onEvent(DashboardEvent.OnNetfreeRestartServiceClicked) }
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    if (uiState.activeFeatures.isEmpty() && !uiState.isNetfreeFeatureActive) {
                        item {
                            Text(
                                stringResource(id = R.string.dashboard_no_active_protections),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.fillParentMaxSize().wrapContentHeight(Alignment.CenterVertically)
                            )
                        }
                    } else {
                        items(uiState.activeFeatures) { featureStatus ->
                            FeatureStatusRow(featureStatus = featureStatus)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (uiState.isPasswordPromptVisible) {
        PasswordPromptDialog(
            passwordError = uiState.passwordError,
            onConfirm = { password -> viewModel.onEvent(DashboardEvent.OnPasswordEntered(password)) },
            onDismiss = { viewModel.onEvent(DashboardEvent.OnDismissPasswordPrompt) }
        )
    }

    if (showAppInfoDialog) {
        AppInfoDialog(
            appVersion = stringResource(id = R.string.app_version),
            buildStatus = stringResource(id = R.string.app_build_status),
            isContactEmailVisible = uiState.isContactEmailVisible,
            onCheckForUpdateClick = { viewModel.onEvent(DashboardEvent.OnManualUpdateCheck) },
            onDismiss = { showAppInfoDialog = false }
        )
    }

    if (uiState.updateDialogState != UpdateDialogState.HIDDEN) {
        UpdateDialog(
            uiState = uiState,
            onEvent = viewModel::onEvent
        )
    }

    if (showAppNotInstalledDialog) {
        AppNotInstalledDialog(
            onDismiss = { showAppNotInstalledDialog = false }
        )
    }
}

@Composable
fun AppNotInstalledDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(id = R.string.dashboard_error_app_not_installed),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(id = R.string.dashboard_error_mod_apk_incompatibility),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss
            ) {
                Text(stringResource(id = R.string.dashboard_button_understood))
            }
        }
    )
}

@Composable
fun NetfreeStatusCard(
    uiState: DashboardUiState,
    onRecheck: () -> Unit,
    onRestart: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.netfree_status_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // --- התיקון המלא כאן ---
                val statusText: String
                val statusColor: Color
                val statusIcon = when (uiState.isNetfreeConnectionVerified) {
                    true -> {
                        statusText = when (uiState.approvedNetworkType) {
                            "Wi-Fi" -> stringResource(id = R.string.netfree_status_verified_wifi)
                            "Cellular" -> stringResource(id = R.string.netfree_status_verified_cellular)
                            else -> stringResource(id = R.string.netfree_status_verified)
                        }
                        statusColor = Color(0xFF388E3C)
                        Icons.Default.CheckCircle
                    }
                    false -> {
                        statusText = stringResource(id = R.string.netfree_status_not_verified_and_blocked)
                        statusColor = MaterialTheme.colorScheme.error
                        Icons.Default.Warning
                    }
                    null -> {
                        statusText = stringResource(id = R.string.netfree_status_checking)
                        statusColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        Icons.AutoMirrored.Filled.HelpOutline
                    }
                }

                if (uiState.isNetfreeCheckInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(imageVector = statusIcon, contentDescription = null, tint = statusColor)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = statusText, color = statusColor, style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(onClick = onRestart, enabled = !uiState.isNetfreeCheckInProgress) {
                    Text(stringResource(id = R.string.netfree_button_restart_service))
                }
                Button(onClick = onRecheck, enabled = !uiState.isNetfreeCheckInProgress) {
                    Text(stringResource(id = R.string.netfree_button_recheck))
                }
            }
        }
    }
}


@Composable
private fun FeatureStatusRow(featureStatus: FeatureStatus) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(id = featureStatus.feature.iconRes), contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(id = featureStatus.feature.titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(id = featureStatus.feature.descriptionRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        Spacer(modifier = Modifier.width(16.dp))
        StatusIndicator(isActive = featureStatus.isActive)
    }
}

@Composable
private fun StatusIndicator(isActive: Boolean) {
    val icon = if (isActive) Icons.Default.CheckCircle else Icons.Default.Warning
    val text = if (isActive) stringResource(id = R.string.dashboard_status_protected) else stringResource(id = R.string.dashboard_status_unprotected)
    val color = if (isActive) Color(0xFF388E3C) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(imageVector = icon, contentDescription = text, tint = color, modifier = Modifier.size(18.dp))
        Text(text = text, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}
