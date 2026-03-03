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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetranscript.settings.LANGUAGE_OPTIONS
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

// ─────────────────────────────────────────────────────────────────────────────
// Design tokens

private val BgGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF0A1929), Color(0xFF132B45), Color(0xFF1A3456)),
)
private val ChipBorder    = Color(0x33FFFFFF)
private val WaveColor     = Color(0xFF4FC3F7)
private val RecBtn        = Color(0xFFEF4444)
private val TextPrimary   = Color.White
private val TextSecondary = Color(0xFFB0BEC5)
private val SpeakerColors = listOf(
    Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFFFB74D),
    Color(0xFFCE93D8), Color(0xFFEF9A9A), Color(0xFF4DD0E1),
)

// Flag emojis keyed by Whisper language code
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

    // Waveform – single atomic state update per tick to avoid 36 recompositions
    var waveform by remember { mutableStateOf(FloatArray(36) { 0.12f }) }
    LaunchedEffect(isRecording) {
        while (isActive) {
            waveform = if (isRecording)
                FloatArray(36) { (0.08f + Random.nextFloat() * 0.84f) }
            else
                FloatArray(36) { 0.12f }
            delay(110)
        }
    }

    // Pulse rings around the record FAB
    val infiniteTransition = rememberInfiniteTransition(label = "rec-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(950, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse-alpha",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.85f,
        animationSpec = infiniteRepeatable(tween(950, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse-scale",
    )

    // Dialog state
    var showSaveDialog     by remember { mutableStateOf(false) }
    var langDropExpanded   by remember { mutableStateOf(false) }

    if (showSaveDialog) {
        SaveTranscriptDialog(
            transcripts = transcripts,
            strings     = strings,
            onDismiss   = { showSaveDialog = false },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(BgGradient)) {
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
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color      = TextPrimary,
                    modifier   = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = strings.settings,
                        tint = TextPrimary.copy(alpha = if (isRecording) 0.35f else 0.85f),
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
                        label         = { Text(strings.selectLanguage, fontSize = 12.sp) },
                        trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langDropExpanded) },
                        modifier      = Modifier.menuAnchor().fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor      = ChipBorder,
                            focusedBorderColor        = Color(0xFF64B5F6),
                            unfocusedTextColor        = TextPrimary,
                            focusedTextColor          = TextPrimary,
                            unfocusedLabelColor       = TextSecondary,
                            focusedLabelColor         = Color(0xFF64B5F6),
                            unfocusedTrailingIconColor = TextSecondary,
                            focusedTrailingIconColor  = TextPrimary,
                            unfocusedContainerColor   = Color.Transparent,
                            focusedContainerColor     = Color(0x0AFFFFFF),
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                    )
                    ExposedDropdownMenu(
                        expanded          = langDropExpanded,
                        onDismissRequest  = { langDropExpanded = false },
                        modifier          = Modifier.background(Color(0xFF1A3050)),
                    ) {
                        LANGUAGE_OPTIONS.forEach { (code, name) ->
                            DropdownMenuItem(
                                text    = { Text("${LANG_FLAG[code] ?: "🌍"} $name", color = TextPrimary) },
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

                // Save button – enabled only when there is something to save
                IconButton(
                    onClick  = { showSaveDialog = true },
                    enabled  = transcripts.isNotEmpty(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Save,
                        contentDescription = strings.save,
                        tint = if (transcripts.isNotEmpty()) TextPrimary
                               else TextSecondary.copy(alpha = 0.35f),
                    )
                }
            }

            // ── Waveform / empty hint ─────────────────────────────────────
            if (isRecording) {
                Text(
                    text       = strings.liveTranscriptionRunning,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color      = TextPrimary.copy(alpha = 0.85f),
                    modifier   = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 20.dp),
                ) {
                    val count   = waveform.size
                    val spacing = size.width / count
                    val barW    = spacing * 0.52f
                    for (i in 0 until count) {
                        val h = (waveform[i] * size.height).coerceAtLeast(3f)
                        drawRoundRect(
                            color        = WaveColor,
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
                        color     = TextSecondary,
                        fontSize  = 14.sp,
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

            // ── Record button + status ────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 12.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Animated pulse ring (recording only)
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size((72 * pulseScale).dp)
                                .background(RecBtn.copy(alpha = pulseAlpha * 0.45f), CircleShape),
                        )
                        Box(
                            modifier = Modifier
                                .size(86.dp)
                                .background(RecBtn.copy(alpha = 0.18f), CircleShape),
                        )
                    }

                    FloatingActionButton(
                        onClick = {
                            if (!modelsReady) return@FloatingActionButton
                            if (isRecording) onStopRecording() else onStartRecording()
                        },
                        modifier       = Modifier.size(68.dp),
                        containerColor = if (modelsReady) RecBtn else Color(0xFF546E7A),
                        contentColor   = TextPrimary,
                        elevation      = FloatingActionButtonDefaults.elevation(
                            defaultElevation = if (isRecording) 10.dp else 4.dp,
                        ),
                    ) {
                        Icon(
                            imageVector        = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (isRecording) strings.stopRecording else strings.startRecording,
                            modifier           = Modifier.size(30.dp),
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
                    color      = if (isRecording) Color(0xFFEF9A9A) else TextSecondary,
                    fontSize   = 13.sp,
                    fontWeight = if (isRecording) FontWeight.SemiBold else FontWeight.Normal,
                )

                if (transcripts.isNotEmpty() && !isRecording) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = onClear,
                        colors  = ButtonDefaults.textButtonColors(
                            contentColor = TextSecondary.copy(alpha = 0.6f),
                        ),
                    ) {
                        Text(strings.deleteAll, fontSize = 12.sp)
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
    val color = SpeakerColors[entry.speakerId.coerceAtLeast(0) % SpeakerColors.size]

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(bottom = 2.dp),
        ) {
            Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text       = if (entry.speakerId >= 0) "$speakerLabel ${entry.speakerId + 1}"
                             else unknownLabel,
                color      = color,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 11.sp,
            )
            if (showTimestamp) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text     = formatTimestamp(entry.timestamp),
                    fontSize = 10.sp,
                    color    = TextSecondary.copy(alpha = 0.5f),
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp),
            color = Color(0x1AFFFFFF),
        ) {
            Text(
                text       = entry.text,
                color      = TextPrimary.copy(alpha = 0.92f),
                fontSize   = 15.sp,
                lineHeight = 22.sp,
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
        containerColor    = Color(0xFF1A3050),
        titleContentColor = TextPrimary,
        textContentColor  = TextSecondary,
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
                        border         = BorderStroke(1.dp, ChipBorder),
                        colors         = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Column(
                            modifier  = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(desc, fontSize = 11.sp, color = TextSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors  = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
            ) {
                Text(strings.cancel)
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun formatTimestamp(millis: Long): String = timeFormat.format(Date(millis))
