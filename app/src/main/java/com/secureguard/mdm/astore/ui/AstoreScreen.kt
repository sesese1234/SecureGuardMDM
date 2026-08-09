package com.secureguard.mdm.astore.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.secureguard.mdm.R
import com.secureguard.mdm.astore.domain.AppMetadata
import com.secureguard.mdm.astore.domain.DownloadSource
import com.secureguard.mdm.ui.components.PasswordPromptDialog
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext

/**
 * Main Astore Screen with Google Play style bottom navigation.
 * Shows only apps with available updates - no browsing or searching.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstoreScreen(
    viewModel: AstoreViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showSourceDialog by remember { mutableStateOf<AppMetadata?>(null) }
    
    // Package management states
    var showAddPackageDialog by remember { mutableStateOf(false) }
    var packageNameToAdd by remember { mutableStateOf("") }
    
    // Password protection states
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val coroutineScope = rememberCoroutineScope()
    
    val requestPassword: (() -> Unit) -> Unit = { action ->
        pendingAction = action
        showPasswordPrompt = true
        passwordError = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(stringResource(R.string.astore_title))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    val updatesAvailable = uiState.apps.any { it.updateAvailable && !it.isDownloading }
                    if (selectedTab == 1 && updatesAvailable) {
                        TextButton(onClick = { viewModel.updateAll() }) {
                            Text(
                                text = stringResource(R.string.astore_button_update_all),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.refreshApps() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.astore_refresh))
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    label = { Text(stringResource(R.string.astore_tab_custom)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                    label = { Text(stringResource(R.string.astore_tab_updates)) }
                )
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(onClick = { 
                    requestPassword { showAddPackageDialog = true }
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.astore_add_package_button))
                }
            }
        }
    ) { paddingValues ->
        // Error snackbar
        if (uiState.error != null) {
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.dismissError() }) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            ) {
                Text(uiState.error!!)
            }
        }

        // Source selection dialog
        if (showSourceDialog != null) {
            SourceSelectionDialog(
                app = showSourceDialog!!,
                onDismiss = { showSourceDialog = null },
                onSourceSelected = { source ->
                    viewModel.selectSource(showSourceDialog!!.packageName, source)
                    showSourceDialog = showSourceDialog?.copy(selectedSource = source)
                },
                onUpdate = {
                    viewModel.updateApp(showSourceDialog!!.packageName)
                    showSourceDialog = null
                }
            )
        }

        // Add Package Dialog
        if (showAddPackageDialog) {
            AddPackageDialog(
                onDismiss = { showAddPackageDialog = false },
                onConfirm = { pkg ->
                    viewModel.addCustomPackage(pkg)
                    showAddPackageDialog = false
                }
            )
        }

        // Password Prompt Dialog
        val wrongPasswordError = stringResource(R.string.dialog_error_wrong_password)
        if (showPasswordPrompt) {
            PasswordPromptDialog(
                passwordError = passwordError,
                onConfirm = { password ->
                    coroutineScope.launch {
                        if (viewModel.verifyPassword(password)) {
                            showPasswordPrompt = false
                            pendingAction?.invoke()
                            pendingAction = null
                        } else {
                            passwordError = wrongPasswordError
                        }
                    }
                },
                onDismiss = {
                    showPasswordPrompt = false
                    pendingAction = null
                }
            )
        }


        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> {
                    // Custom packages tab - show custom packages from settings
                    // distinctBy guards the LazyColumn key below: a duplicate packageName
                    // throws rather than rendering, taking the whole store down.
                    val customPackages = uiState.apps.filter { it.isCustomPackage }.distinctBy { it.packageName }
                    if (customPackages.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.AddBox,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.astore_no_custom_packages),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { requestPassword { showAddPackageDialog = true } }) {
                                    Text(stringResource(R.string.astore_add_package_button))
                                }
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(customPackages, key = { it.packageName }) { app ->
                                CustomPackageItem(
                                    app = app,
                                    onSourceClick = { showSourceDialog = app },
                                    onInstall = { viewModel.updateApp(app.packageName) },
                                    onRemove = {
                                        requestPassword { viewModel.removeCustomPackage(app.packageName) }
                                    }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    // Updates tab - only show apps with updates
                    val appsWithUpdates = uiState.apps.filter { it.updateAvailable }.distinctBy { it.packageName }
                    
                    if (uiState.isCheckingUpdates && appsWithUpdates.isEmpty()) {
                        // Still checking for updates
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.astore_checking_updates),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    } else if (appsWithUpdates.isEmpty() && !uiState.isCheckingUpdates) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.astore_no_updates),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(appsWithUpdates, key = { it.packageName }) { app ->
                                AppUpdateItem(
                                    app = app,
                                    onSourceClick = { showSourceDialog = app },
                                    onUpdate = { viewModel.updateApp(app.packageName) }
                                )
                            }
                        }
                    }
                }
            }
            
            // Loading indicator for refresh
            if (uiState.isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                )
            }
        }
    }
}

@Composable
fun AppUpdateItem(
    app: AppMetadata,
    onSourceClick: () -> Unit,
    onUpdate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon with download progress
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center
            ) {
                if (app.isDownloading) {
                    CircularProgressIndicator(
                        progress = app.downloadProgress / 100f,
                        modifier = Modifier.size(56.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                }
                
                val context = LocalContext.current
                val iconModel = remember(app.packageName, app.iconUrl) {
                    if (app.iconUrl != null) {
                        // Use web URL from Google Play
                        ImageRequest.Builder(context)
                            .data(app.iconUrl)
                            .crossfade(true)
                            .build()
                    } else {
                        // Use local installed app icon from PackageManager
                        try {
                            val drawable = context.packageManager.getApplicationIcon(app.packageName)
                            ImageRequest.Builder(context)
                                .data(drawable)
                                .crossfade(true)
                                .build()
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                
                if (iconModel != null) {
                    AsyncImage(
                        model = iconModel,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.Android,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.astore_app_version, app.versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Source indicator - show actual source if available
                if (app.availableSources.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        // Show the actual source (e.g., "APKCombo" instead of "StoreKit")
                        val displaySource = app.getOriginalSourceName() ?: app.selectedSource.displayName
                        Text(
                            text = stringResource(R.string.astore_source_format, displaySource),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = onSourceClick,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = stringResource(R.string.astore_change_source),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            
            // Update button
            Button(
                onClick = onUpdate,
                enabled = !app.isDownloading && app.isSourceAvailable()
            ) {
                if (app.isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.astore_button_update))
                }
            }
        }
    }
}

@Composable
fun CustomPackageItem(
    app: AppMetadata,
    onSourceClick: () -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon with download progress
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center
            ) {
                if (app.isDownloading) {
                    CircularProgressIndicator(
                        progress = app.downloadProgress / 100f,
                        modifier = Modifier.size(56.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                }
                AsyncImage(
                    model = app.iconUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                // Show a download icon if no icon URL
                if (app.iconUrl == null) {
                    Icon(
                        Icons.Default.Android,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.versionName.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.astore_app_version, app.versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Source indicator - show actual source if available
                if (app.availableSources.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        // Show the actual source (e.g., "APKCombo" instead of "StoreKit")
                        val displaySource = app.getOriginalSourceName() ?: app.selectedSource.displayName
                        Text(
                            text = stringResource(R.string.astore_source_format, displaySource),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = onSourceClick,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = stringResource(R.string.astore_change_source),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            
            // Remove button
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.astore_remove_package),
                    tint = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.width(8.dp))

            // Install button
            Button(
                onClick = onInstall,
                enabled = !app.isDownloading && app.isSourceAvailable()
            ) {
                if (app.isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.astore_button_install))
                }
            }
        }
    }
}


@Composable
fun InstalledAppItem(
    app: AppMetadata,
    onOpen: () -> Unit,
    onUninstall: () -> Unit,
    onUpdate: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = app.iconUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.astore_app_version, app.versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Action buttons
            if (onUpdate != null) {
                Button(
                    onClick = onUpdate,
                    enabled = !app.isDownloading
                ) {
                    Text(stringResource(R.string.astore_button_update))
                }
                Spacer(Modifier.width(8.dp))
            } else {
                TextButton(onClick = onOpen) {
                    Text(stringResource(R.string.astore_button_open))
                }
                Spacer(Modifier.width(8.dp))
            }
            
            TextButton(onClick = onUninstall) {
                Text(
                    stringResource(R.string.astore_button_uninstall),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun SourceSelectionDialog(
    app: AppMetadata,
    onDismiss: () -> Unit,
    onSourceSelected: (DownloadSource) -> Unit,
    onUpdate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.astore_select_source_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.astore_select_source_desc, app.title),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                
                app.availableSources.forEach { sourceAvailability ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = app.selectedSource == sourceAvailability.source,
                            onClick = { onSourceSelected(sourceAvailability.source) },
                            enabled = sourceAvailability.isAvailable
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            // Show the actual source name if available
                            val displayName = sourceAvailability.originalSource ?: sourceAvailability.source.displayName
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (sourceAvailability.isAvailable) {
                                sourceAvailability.versionName?.let { version ->
                                    Text(
                                        text = stringResource(R.string.astore_source_version, version),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.astore_source_unavailable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdate,
                enabled = app.isSourceAvailable()
            ) {
                Text(stringResource(R.string.astore_button_update))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
@Composable
fun AddPackageDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.astore_add_package_title)) },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.astore_add_package_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onConfirm(text.trim())
                    }
                }
            ) {
                Text(stringResource(R.string.astore_add_package_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
