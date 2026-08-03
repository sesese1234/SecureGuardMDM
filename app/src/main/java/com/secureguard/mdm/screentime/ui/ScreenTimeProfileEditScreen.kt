package com.secureguard.mdm.screentime.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.secureguard.mdm.appblocker.AppInfo
import com.secureguard.mdm.screentime.vm.ScreenTimeProfileEditViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTimeProfileEditScreen(
    profileId: String?,
    viewModel: ScreenTimeProfileEditViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.load(profileId)
    }

    LaunchedEffect(uiState.didSave) {
        if (uiState.didSave) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isNewProfile) "פרופיל חדש" else "עריכת פרופיל") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save() }) {
                        Text("שמור")
                    }
                }
            )
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
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = { viewModel.onNameChanged(it) },
                    label = { Text("שם הפרופיל") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("פרופיל פעיל", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = uiState.isEnabled, onCheckedChange = { viewModel.onEnabledToggled(it) })
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Text("מגבלת דקות יומית", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = uiState.dailyLimitMinutes.toFloat(),
                        onValueChange = { viewModel.onDailyLimitChanged(it.toInt()) },
                        valueRange = 5f..240f,
                        steps = 46,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${uiState.dailyLimitMinutes} דק'")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Text("שעות מותרות", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    HourPickerField(
                        label = "משעה",
                        hour = uiState.allowedStartHour,
                        onHourChange = { viewModel.onAllowedHoursChanged(it, uiState.allowedEndHour) }
                    )
                    HourPickerField(
                        label = "עד שעה",
                        hour = uiState.allowedEndHour,
                        onHourChange = { viewModel.onAllowedHoursChanged(uiState.allowedStartHour, it) }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Text(
                    "אפליקציות בפרופיל זה (${uiState.selectedPackages.size} נבחרו)",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    placeholder = { Text("חיפוש אפליקציה...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(uiState.filteredApps, key = { it.packageName }) { app ->
                ProfileAppRow(
                    app = app,
                    isSelected = uiState.selectedPackages.contains(app.packageName),
                    onClick = { viewModel.toggleApp(app.packageName) }
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HourPickerField(label: String, hour: Int, onHourChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onHourChange(((hour - 1) + 24) % 24) }) {
                Text("-", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = String.format("%02d:00", hour),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = { onHourChange((hour + 1) % 24) }) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun ProfileAppRow(app: AppInfo, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberDrawablePainter(drawable = app.icon),
            contentDescription = app.appName,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = app.appName,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Checkbox(checked = isSelected, onCheckedChange = { onClick() })
    }
}