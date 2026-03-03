package com.livetranscript.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetranscript.settings.SettingsViewModel
import com.livetranscript.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    val state   by settingsViewModel.uiState.collectAsState()
    val strings = LocalStrings.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.settingsTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {

            // ── Appearance ────────────────────────────────────────────────
            SectionHeader(strings.appearance)

            listOf(
                ThemeMode.LIGHT  to strings.themeLight,
                ThemeMode.DARK   to strings.themeDark,
                ThemeMode.SYSTEM to strings.themeSystem,
            ).forEach { (mode, label) ->
                OptionRow(
                    label    = label,
                    selected = state.themeMode == mode,
                    onClick  = { settingsViewModel.setTheme(mode) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ── Transcript ────────────────────────────────────────────────
            SectionHeader(strings.transcript)

            SwitchRow(
                label         = strings.autoScroll,
                description   = strings.autoScrollDesc,
                checked       = state.autoScroll,
                onCheckedChange = { settingsViewModel.setAutoScroll(it) },
            )

            SwitchRow(
                label         = strings.showTimestamps,
                description   = strings.showTimestampsDesc,
                checked       = state.showTimestamps,
                onCheckedChange = { settingsViewModel.setShowTimestamps(it) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ── Info ──────────────────────────────────────────────────────
            SectionHeader(strings.info)
            Text(
                text  = "LiveTranscript v1.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text  = "Whisper Tiny + WeSpeaker Diarization",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text       = title,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 13.sp,
        color      = MaterialTheme.colorScheme.primary,
        modifier   = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 15.sp)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
