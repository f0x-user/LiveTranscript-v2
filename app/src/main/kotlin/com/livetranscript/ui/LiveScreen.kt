package com.livetranscript.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.livetranscript.settings.LANGUAGE_OPTIONS
import com.livetranscript.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// Data model

data class TranscriptEntry(
    val speakerId: Int,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)

// Flag emoji keyed by Whisper language code
private val LANG_FLAG = mapOf(
    ""   to "🌍", "de" to "🇩🇪", "en" to "🇬🇧", "fr" to "🇫🇷",
    "es" to "🇪🇸", "it" to "🇮🇹", "pt" to "🇵🇹", "tr" to "🇹🇷",
    "nl" to "🇳🇱", "pl" to "🇵🇱", "ru" to "🇷🇺", "zh" to "🇨🇳",
    "ja" to "🇯🇵", "ko" to "🇰🇷", "ar" to "🇸🇦",
)

// ─────────────────────────────────────────────────────────────────────────────
// Main screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    isRecording: Boolean,
    transcripts: List<TranscriptEntry>,
    modelsReady: Boolean,
    autoScroll: Boolean,
    showTimestamps: Boolean,
    transcriptionLanguage: String,
    onLanguageChange: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings        = LocalStrings.current
    val listState      = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to newest entry
    LaunchedEffect(transcripts.size, autoScroll) {
        if (autoScroll && transcripts.isNotEmpty()) {
            coroutineScope.launch { listState.animateScrollToItem(transcripts.size - 1) }
        }
    }

    // Waveform — single atomic state update per tick avoids per-bar recompositions
    var waveform by remember { mutableStateOf(FloatArray(AppTheme.Dimens.waveformBars) { 0.12f }) }
    LaunchedEffect(isRecording) {
        while (isActive) {
            waveform = if (isRecording)
                FloatArray(AppTheme.Dimens.waveformBars) { 0.08f + Random.nextFloat() * 0.84f }
            else
                FloatArray(AppTheme.Dimens.waveformBars) { 0.12f }
            delay(AppTheme.Animation.waveformTickMs)
        }
    }

    // Pulse rings around FAB (only while recording)
    val infiniteTransition = rememberInfiniteTransition(label = "rec-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            tween(AppTheme.Animation.pulseDurationMs, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "pulse-alpha",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = AppTheme.Dimens.pulseRingFactor,
        animationSpec = infiniteRepeatable(
            tween(AppTheme.Animation.pulseDurationMs, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "pulse-scale",
    )

    var showSaveDialog   by remember { mutableStateOf(false) }
    var langDropExpanded by remember { mutableStateOf(false) }

    if (showSaveDialog) {
        SaveTranscriptDialog(
            transcripts = transcripts,
            strings     = strings,
            onDismiss   = { showSaveDialog = false },
        )
    }

    // Full-screen gradient — covers whole window including behind status bar
    Box(modifier = Modifier.fillMaxSize().background(AppTheme.Gradients.background)) {
        Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {

            // ── Top bar ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text       = strings.appTitle,
                    fontSize   = AppTheme.TextSize.title,
                    fontWeight = FontWeight.Bold,
                    color      = AppTheme.Colors.textPrimary,
                    modifier   = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = strings.settings,
                        tint = AppTheme.Colors.textPrimary.copy(
                            alpha = if (isRecording) 0.35f else 0.85f,
                        ),
                    )
                }
            }

            // ── Language dropdown + Save button ───────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val currentName = LANGUAGE_OPTIONS.find { it.first == transcriptionLanguage }
                    ?.second ?: strings.autoDetect

                ExposedDropdownMenuBox(
                    expanded         = langDropExpanded,
                    onExpandedChange = { if (!isRecording) langDropExpanded = it },
                    modifier         = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value         = "${LANG_FLAG[transcriptionLanguage] ?: "🌍"} $currentName",
                        onValueChange = {},
                        readOnly      = true,
                        singleLine    = true,
                        label         = {
                            Text(strings.selectLanguage, fontSize = AppTheme.TextSize.caption)
                        },
                        trailingIcon  = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = langDropExpanded)
                        },
                        modifier      = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor       = AppTheme.Colors.chipBorder,
                            focusedBorderColor         = AppTheme.Colors.accentCyan,
                            unfocusedTextColor         = AppTheme.Colors.textPrimary,
                            focusedTextColor           = AppTheme.Colors.textPrimary,
                            unfocusedLabelColor        = AppTheme.Colors.textSecondary,
                            focusedLabelColor          = AppTheme.Colors.accentCyan,
                            unfocusedTrailingIconColor = AppTheme.Colors.textSecondary,
                            focusedTrailingIconColor   = AppTheme.Colors.textPrimary,
                            unfocusedContainerColor    = AppTheme.Colors.chipDefault,
                            focusedContainerColor      = AppTheme.Colors.chipDefault,
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = AppTheme.TextSize.text),
                    )
                    ExposedDropdownMenu(
                        expanded         = langDropExpanded,
                        onDismissRequest = { langDropExpanded = false },
                        modifier         = Modifier.background(AppTheme.Colors.dialog),
                    ) {
                        LANGUAGE_OPTIONS.forEach { (code, name) ->
                            DropdownMenuItem(
                                text    = {
                                    Text(
                                        "${LANG_FLAG[code] ?: "🌍"} $name",
                                        color = AppTheme.Colors.textPrimary,
                                    )
                                },
                                onClick = {
                                    onLanguageChange(code)
                                    langDropExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Save — active only when the transcript has entries
                IconButton(
                    onClick  = { showSaveDialog = true },
                    enabled  = transcripts.isNotEmpty(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Save,
                        contentDescription = strings.save,
                        tint = if (transcripts.isNotEmpty())
                            AppTheme.Colors.textPrimary
                        else
                            AppTheme.Colors.textSecondary.copy(alpha = 0.35f),
                    )
                }
            }

            // ── Waveform / empty-state hint ───────────────────────────────
            if (isRecording) {
                Text(
                    text       = strings.liveTranscriptionRunning,
                    fontSize   = AppTheme.TextSize.body,
                    fontWeight = FontWeight.Medium,
                    color      = AppTheme.Colors.textPrimary.copy(alpha = 0.85f),
                    modifier   = Modifier.padding(
                        horizontal = AppTheme.Dimens.waveformPaddingH,
                        vertical   = 4.dp,
                    ),
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AppTheme.Dimens.waveformHeight)
                        .padding(horizontal = AppTheme.Dimens.waveformPaddingH),
                ) {
                    val count   = waveform.size
                    val spacing = size.width / count
                    val barW    = spacing * 0.52f
                    for (i in 0 until count) {
                        val h = (waveform[i] * size.height).coerceAtLeast(3f)
                        drawRoundRect(
                            color        = AppTheme.Colors.accentCyan,
                            topLeft      = Offset(i * spacing + spacing * 0.24f, (size.height - h) / 2f),
                            size         = Size(barW, h),
                            cornerRadius = CornerRadius(barW / 2f),
                        )
                    }
                }
            } else if (transcripts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text      = if (modelsReady) strings.startToBegin else strings.modelsLoading,
                        color     = AppTheme.Colors.textSecondary,
                        fontSize  = AppTheme.TextSize.text,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // ── Transcript list ───────────────────────────────────────────
            LazyColumn(
                state   = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding      = PaddingValues(vertical = 8.dp),
            ) {
                items(transcripts) { entry ->
                    TranscriptBubble(
                        entry         = entry,
                        showTimestamp = showTimestamps,
                        speakerLabel  = strings.speaker,
                        unknownLabel  = strings.unknown,
                    )
                }
            }

            // ── Record FAB + status ───────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 12.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Animated pulse rings (recording only)
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size((AppTheme.Dimens.fabSize.value * pulseScale).dp)
                                .background(
                                    AppTheme.Colors.recordPulse.copy(alpha = pulseAlpha * 0.45f),
                                    CircleShape,
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .size(AppTheme.Dimens.pulseRingInner)
                                .background(
                                    AppTheme.Colors.recordPulse.copy(alpha = 0.18f),
                                    CircleShape,
                                ),
                        )
                    }

                    FloatingActionButton(
                        onClick = {
                            if (!modelsReady) return@FloatingActionButton
                            if (isRecording) onStopRecording() else onStartRecording()
                        },
                        modifier       = Modifier.size(AppTheme.Dimens.fabSize),
                        containerColor = if (modelsReady) AppTheme.Colors.recordActive
                                         else AppTheme.Colors.recordInactive,
                        contentColor   = AppTheme.Colors.textPrimary,
                        elevation      = FloatingActionButtonDefaults.elevation(
                            defaultElevation = if (isRecording) 10.dp else 4.dp,
                        ),
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (isRecording) strings.stopRecording
                                                 else strings.startRecording,
                            modifier = Modifier.size(AppTheme.Dimens.fabIconSize),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = when {
                        !modelsReady -> strings.modelsLoading
                        isRecording  -> strings.recordingRunning
                        else         -> strings.ready
                    },
                    color      = if (isRecording) AppTheme.Colors.recordingLabel
                                 else AppTheme.Colors.textSecondary,
                    fontSize   = AppTheme.TextSize.body,
                    fontWeight = if (isRecording) FontWeight.SemiBold else FontWeight.Normal,
                )

                if (transcripts.isNotEmpty() && !isRecording) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = onClear,
                        colors  = ButtonDefaults.textButtonColors(
                            contentColor = AppTheme.Colors.textSecondary.copy(alpha = 0.6f),
                        ),
                    ) {
                        Text(strings.deleteAll, fontSize = AppTheme.TextSize.caption)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Transcript bubble

@Composable
fun TranscriptBubble(
    entry: TranscriptEntry,
    showTimestamp: Boolean = false,
    speakerLabel: String   = "Speaker",
    unknownLabel: String   = "Unknown",
) {
    val color = AppTheme.Colors.speakers[
        entry.speakerId.coerceAtLeast(0) % AppTheme.Colors.speakers.size
    ]

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(bottom = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(AppTheme.Dimens.speakerDot)
                    .background(color, CircleShape),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text       = if (entry.speakerId >= 0) "$speakerLabel ${entry.speakerId + 1}"
                             else unknownLabel,
                color      = color,
                fontWeight = FontWeight.SemiBold,
                fontSize   = AppTheme.TextSize.label,
            )
            if (showTimestamp) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text     = formatTimestamp(entry.timestamp),
                    fontSize = AppTheme.TextSize.micro,
                    color    = AppTheme.Colors.textSecondary.copy(alpha = 0.5f),
                )
            }
        }
        Surface(
            shape = AppTheme.Shapes.bubble,
            color = AppTheme.Colors.surface,
        ) {
            Text(
                text       = entry.text,
                color      = AppTheme.Colors.textPrimary.copy(alpha = 0.92f),
                fontSize   = AppTheme.TextSize.text,
                lineHeight = AppTheme.TextSize.subtitle,
                modifier   = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Save-format dialog

@Composable
private fun SaveTranscriptDialog(
    transcripts: List<TranscriptEntry>,
    strings: AppStrings,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest  = onDismiss,
        containerColor    = AppTheme.Colors.dialog,
        titleContentColor = AppTheme.Colors.textPrimary,
        textContentColor  = AppTheme.Colors.textSecondary,
        title = { Text(strings.saveTranscript, fontWeight = FontWeight.SemiBold) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.selectFormat, style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(2.dp))

                listOf(
                    SaveFormat.TXT  to (strings.formatTxt  to strings.formatTxtDesc),
                    SaveFormat.CSV  to (strings.formatCsv  to strings.formatCsvDesc),
                    SaveFormat.JSON to (strings.formatJson to strings.formatJsonDesc),
                    SaveFormat.SRT  to (strings.formatSrt  to strings.formatSrtDesc),
                ).forEach { (fmt, labels) ->
                    val (label, desc) = labels
                    OutlinedButton(
                        onClick = {
                            shareTranscript(context, transcripts, fmt)
                            onDismiss()
                        },
                        modifier       = Modifier.fillMaxWidth(),
                        border         = BorderStroke(1.dp, AppTheme.Colors.chipBorder),
                        colors         = ButtonDefaults.outlinedButtonColors(
                            contentColor = AppTheme.Colors.textPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Column(
                            modifier            = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Text(
                                label,
                                fontWeight = FontWeight.Medium,
                                fontSize   = AppTheme.TextSize.text,
                            )
                            Text(
                                desc,
                                fontSize = AppTheme.TextSize.label,
                                color    = AppTheme.Colors.textSecondary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors  = ButtonDefaults.textButtonColors(
                    contentColor = AppTheme.Colors.textSecondary,
                ),
            ) {
                Text(strings.cancel)
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun formatTimestamp(millis: Long): String = timeFormat.format(Date(millis))
