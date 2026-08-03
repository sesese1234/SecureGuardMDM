package com.secureguard.mdm.screentime.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.secureguard.mdm.screentime.ScreenTimeProfile
import com.secureguard.mdm.screentime.vm.ScreenTimeSettingsViewModel
import com.secureguard.mdm.services.ScreenTimeEnforcer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTimeSettingsScreen(
    viewModel: ScreenTimeSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToProfileEdit: (profileId: String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val hasUsageAccess = remember { mutableStateOf(ScreenTimeEnforcer.hasUsageAccessPermission(context)) }

    // רענון הרשימה כשחוזרים למסך הזה (למשל אחרי שמירת/מחיקת פרופיל)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsageAccess.value = ScreenTimeEnforcer.hasUsageAccessPermission(context)
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("הגבלת זמן מסך") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.isEnabled) {
                FloatingActionButton(onClick = { onNavigateToProfileEdit(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = "הוסף פרופיל")
                }
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("הפעלת הגבלת זמן מסך", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = uiState.isEnabled, onCheckedChange = { viewModel.onToggleEnabled(it) })
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (uiState.isEnabled && !hasUsageAccess.value) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "כדי לאכוף מגבלות זמן, יש לאשר גישה לנתוני שימוש",
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }) {
                                Text("פתח הגדרות גישה לנתוני שימוש")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            if (uiState.isEnabled) {
                if (uiState.profiles.isEmpty()) {
                    item {
                        Text(
                            "אין עדיין פרופילים. לחץ על + כדי להוסיף פרופיל ראשון.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(uiState.profiles, key = { it.id }) { profile ->
                        ProfileCard(
                            profile = profile,
                            onClick = { onNavigateToProfileEdit(profile.id) },
                            onDelete = { viewModel.deleteProfile(profile.id) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ProfileCard(profile: ScreenTimeProfile, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${profile.appPackages.size} אפליקציות · ${profile.dailyLimitMinutes} דק' ליום · " +
                        "${String.format("%02d:00", profile.allowedStartHour)}-${String.format("%02d:00", profile.allowedEndHour)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!profile.isEnabled) {
                    Text("מושבת", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "מחק פרופיל")
            }
        }
    }
}