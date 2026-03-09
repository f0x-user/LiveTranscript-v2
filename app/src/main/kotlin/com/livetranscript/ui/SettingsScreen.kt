package com.livetranscript.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livetranscript.settings.SettingsViewModel
import com.livetranscript.settings.ThemeMode
import com.livetranscript.ui.theme.AppTheme
import com.livetranscript.ui.theme.LocalIsDarkTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    val state   by settingsViewModel.uiState.collectAsState()
    val strings = LocalStrings.current
    val isDark  = LocalIsDarkTheme.current
    val colors  = if (isDark) AppTheme.DarkColors else AppTheme.LightColors

    GradientBackground(isDark = isDark) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text       = strings.settingsTitle,
                            fontSize   = AppTheme.TextSize.title,
                            fontWeight = FontWeight.SemiBold,
                            color      = colors.textPrimary,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Zurück",
                                tint               = colors.textPrimary,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.topBarContainer,
                    ),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                // ── Appearance ────────────────────────────────────────────────
                SettingsSection(title = strings.appearance, isDark = isDark) {
                    listOf(
                        ThemeMode.LIGHT  to strings.themeLight,
                        ThemeMode.DARK   to strings.themeDark,
                        ThemeMode.SYSTEM to strings.themeSystem,
                    ).forEach { (mode, label) ->
                        OptionRow(
                            label    = label,
                            selected = state.themeMode == mode,
                            isDark   = isDark,
                            onClick  = { settingsViewModel.setTheme(mode) },
                        )
                    }
                }

                // ── Transcript ────────────────────────────────────────────────
                SettingsSection(title = strings.transcript, isDark = isDark) {
                    SwitchRow(
                        label           = strings.autoScroll,
                        description     = strings.autoScrollDesc,
                        checked         = state.autoScroll,
                        isDark          = isDark,
                        onCheckedChange = { settingsViewModel.setAutoScroll(it) },
                    )
                    SwitchRow(
                        label           = strings.showTimestamps,
                        description     = strings.showTimestampsDesc,
                        checked         = state.showTimestamps,
                        isDark          = isDark,
                        onCheckedChange = { settingsViewModel.setShowTimestamps(it) },
                    )
                }

                // ── Info ──────────────────────────────────────────────────────
                SettingsSection(title = strings.info, isDark = isDark) {
                    Text(
                        text     = "LiveTranscript v1.0",
                        fontSize = AppTheme.TextSize.body,
                        color    = colors.textSecondary,
                    )
                    Text(
                        text     = "Whisper Tiny + WeSpeaker Diarization",
                        fontSize = AppTheme.TextSize.body,
                        color    = colors.textSecondary,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section card wrapper

@Composable
private fun SettingsSection(
    title: String,
    isDark: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = if (isDark) AppTheme.DarkColors else AppTheme.LightColors

    Column {
        Text(
            text       = title,
            fontWeight = FontWeight.SemiBold,
            fontSize   = AppTheme.TextSize.caption,
            color      = colors.accentCyan,
            modifier   = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Surface(
            shape           = AppTheme.Shapes.card,
            color           = colors.cardBackground,
            border          = BorderStroke(1.dp, colors.cardBorder),
            tonalElevation  = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                content  = content,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Row components

@Composable
private fun OptionRowWithDesc(
    label: String,
    desc: String,
    selected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val colors = if (isDark) AppTheme.DarkColors else AppTheme.LightColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(label, fontSize = AppTheme.TextSize.text, color = colors.textPrimary)
            Text(
                text     = desc,
                fontSize = AppTheme.TextSize.caption,
                color    = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val colors = if (isDark) AppTheme.DarkColors else AppTheme.LightColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = AppTheme.TextSize.text, color = colors.textPrimary)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    isDark: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = if (isDark) AppTheme.DarkColors else AppTheme.LightColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label,       fontSize = AppTheme.TextSize.text,    color = colors.textPrimary)
            Text(description, fontSize = AppTheme.TextSize.caption, color = colors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
