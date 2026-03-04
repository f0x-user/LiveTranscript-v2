package com.livetranscript.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetranscript.ui.theme.AppTheme
import com.livetranscript.ui.theme.LocalIsDarkTheme
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
// Constants

private val LANG_FLAG = mapOf(
    ""   to "🌍", "de" to "🇩🇪", "en" to "🇬🇧", "fr" to "🇫🇷",
    "es" to "🇪🇸", "it" to "🇮🇹", "pt" to "🇵🇹", "tr" to "🇹🇷",
    "nl" to "🇳🇱", "pl" to "🇵🇱", "ru" to "🇷🇺", "zh" to "🇨🇳",
    "ja" to "🇯🇵", "ko" to "🇰🇷", "ar" to "🇸🇦",
)

private val SEGMENT_LANGS = listOf("", "de", "en", "fr", "es")
private val SEGMENT_LABELS = mapOf(
    "" to "Auto", "de" to "DE", "en" to "EN", "fr" to "FR", "es" to "ES",
)

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables

/**
 * Full-screen gradient background that adapts to the current theme.
 */
@Composable
fun GradientBackground(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val brush = if (isDark) AppTheme.Gradients.dark else AppTheme.Gradients.light
    Box(
        modifier = modifier.fillMaxSize().background(brush),
        content  = content,
    )
}

/**
 * Top app bar with centred title and settings icon.
 */
@Composable
private fun LiveTopAppBar(
    title: String,
    onSettings: () -> Unit,
    isDark: Boolean,
    isRecording: Boolean,
) {
    val colors = if (isDark) AppTheme.DarkColors else AppTheme.LightColors

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(colors.topBarContainer),
        contentAlignment = Alignment.Center,
    ) {
        // Centred title
        Text(
            text       = title,
            fontSize   = AppTheme.TextSize.title,
            fontWeight = FontWeight.Bold,
            color      = colors.textPrimary,
        )
        // Left placeholder — keeps title visually centred
        Spacer(modifier = Modifier.align(Alignment.CenterStart).width(48.dp))
        // Right — Settings icon
        IconButton(
            onClick  = onSettings,
            enabled  = !isRecording,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Icon(
                imageVector        = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint               = colors.textPrimary.copy(
                    alpha = if (isRecording) 0.35f else 0.85f,
                ),
            )
        }
    }
}

/**
 * Pill-shaped segment control for the five primary language options.
 * Non-primary languages are handled via a small dialog.
 */
@Composable
private fun LanguageSegmentRow(
    selectedLanguage: String,
    onSelect: (String) -> Unit,
    isDark: Boolean,
    isRecording: Boolean,
) {
    val chipSelected  = if (isDark) AppTheme.DarkColors.chipSelected  else AppTheme.LightColors.chipSelected
    val chipDefault   = if (isDark) AppTheme.DarkColors.chipDefault   else AppTheme.LightColors.chipDefault
    val chipBorder    = if (isDark) AppTheme.DarkColors.chipBorder    else AppTheme.LightColors.chipBorder
    val textPrimary   = if (isDark) AppTheme.DarkColors.textPrimary   else AppTheme.LightColors.textPrimary
    val textSecondary = if (isDark) AppTheme.DarkColors.textSecondary else AppTheme.LightColors.textSecondary

    val isOther  = selectedLanguage !in SEGMENT_LANGS
    var showMore by remember { mutableStateOf(false) }

    if (showMore) {
        LanguagePickerDialog(
            selectedLanguage = selectedLanguage,
            isDark           = isDark,
            onSelect         = { onSelect(it); showMore = false },
            onDismiss        = { showMore = false },
        )
    }

    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SEGMENT_LANGS.forEach { code ->
            val selected = (selectedLanguage == code) && !isOther
            SegmentPill(
                label         = "${LANG_FLAG[code] ?: "🌍"} ${SEGMENT_LABELS[code]}",
                selected      = selected,
                enabled       = !isRecording,
                chipSelected  = chipSelected,
                chipDefault   = chipDefault,
                chipBorder    = chipBorder,
                textPrimary   = textPrimary,
                textSecondary = textSecondary,
                onClick       = { if (!isRecording) onSelect(code) },
                modifier      = Modifier.weight(1f),
            )
        }
        // "More" pill — highlighted if current language is outside the segment
        SegmentPill(
            label         = if (isOther) "${LANG_FLAG[selectedLanguage] ?: "🌐"}" else "···",
            selected      = isOther,
            enabled       = !isRecording,
            chipSelected  = chipSelected,
            chipDefault   = chipDefault,
            chipBorder    = chipBorder,
            textPrimary   = textPrimary,
            textSecondary = textSecondary,
            onClick       = { if (!isRecording) showMore = true },
            modifier      = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SegmentPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    chipSelected: Color,
    chipDefault: Color,
    chipBorder: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        !enabled && selected -> chipSelected.copy(alpha = 0.5f)
        !enabled             -> chipDefault.copy(alpha = 0.4f)
        selected             -> chipSelected
        else                 -> chipDefault
    }
    val contentColor = when {
        !enabled && selected -> Color.White.copy(alpha = 0.5f)
        !enabled             -> textSecondary.copy(alpha = 0.5f)
        selected             -> Color.White
        else                 -> textPrimary
    }
    val borderColor = when {
        selected -> Color.Transparent
        !enabled -> chipBorder.copy(alpha = 0.4f)
        else     -> chipBorder
    }

    Surface(
        onClick         = onClick,
        modifier        = modifier.height(36.dp),
        shape           = AppTheme.Shapes.pill,
        color           = containerColor,
        border          = BorderStroke(1.dp, borderColor),
        enabled         = enabled,
        tonalElevation  = 0.dp,
        shadowElevation = if (selected) 2.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text       = label,
                color      = contentColor,
                fontSize   = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines   = 1,
                overflow   = TextOverflow.Clip,
            )
        }
    }
}

/**
 * Dialog for selecting languages outside the five-pill segment.
 */
@Composable
private fun LanguagePickerDialog(
    selectedLanguage: String,
    isDark: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val allLanguages = listOf(
        "" to "Auto", "de" to "Deutsch", "en" to "English", "fr" to "Français",
        "es" to "Español", "it" to "Italiano", "pt" to "Português", "tr" to "Türkçe",
        "nl" to "Nederlands", "pl" to "Polski", "ru" to "Русский", "zh" to "中文",
        "ja" to "日本語", "ko" to "한국어", "ar" to "العربية",
    )
    val colors = if (isDark) AppTheme.DarkColors else AppTheme.LightColors

    AlertDialog(
        onDismissRequest  = onDismiss,
        containerColor    = colors.dialog,
        titleContentColor = colors.textPrimary,
        title = { Text("Sprache / Language", fontWeight = FontWeight.SemiBold) },
        text  = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(allLanguages) { (code, name) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = selectedLanguage == code,
                            onClick  = { onSelect(code) },
                        )
                        Text(
                            text  = "${LANG_FLAG[code] ?: "🌍"} $name",
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = colors.textSecondary)
            }
        },
    )
}

/**
 * Animated waveform bar visualiser.
 */
@Composable
fun WaveformVisualizer(
    waveform: FloatArray,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val barColor = if (isDark) AppTheme.DarkColors.accentCyan else AppTheme.LightColors.accentCyan

    Canvas(
        modifier = modifier
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
                color        = barColor,
                topLeft      = Offset(i * spacing + spacing * 0.24f, (size.height - h) / 2f),
                size         = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/**
 * Large pulsing record FAB — the visual anchor of the screen.
 */
@Composable
fun RecordingFab(
    isRecording: Boolean,
    modelsReady: Boolean,
    onToggle: () -> Unit,
    pulseAlpha: Float,
    pulseScale: Float,
    isDark: Boolean,
) {
    val colors = if (isDark) AppTheme.DarkColors else AppTheme.LightColors
    val fabColor = if (modelsReady) colors.recordActive else colors.recordInactive

    Box(contentAlignment = Alignment.Center) {
        // Outer animated pulse ring
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size((AppTheme.Dimens.fabSize.value * pulseScale).dp)
                    .background(
                        colors.recordPulse.copy(alpha = pulseAlpha * 0.40f),
                        CircleShape,
                    ),
            )
            // Inner fixed glow ring
            Box(
                modifier = Modifier
                    .size(AppTheme.Dimens.pulseRingInner)
                    .background(
                        colors.recordPulse.copy(alpha = 0.15f),
                        CircleShape,
                    ),
            )
        }

        FloatingActionButton(
            onClick        = { if (!modelsReady) return@FloatingActionButton; onToggle() },
            modifier       = Modifier.size(AppTheme.Dimens.fabSize),
            containerColor = fabColor,
            contentColor   = Color.White,
            elevation      = FloatingActionButtonDefaults.elevation(
                defaultElevation  = if (isRecording) 12.dp else 6.dp,
                pressedElevation  = 4.dp,
            ),
        ) {
            Icon(
                imageVector        = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isRecording) "Stop" else "Start",
                modifier           = Modifier.size(AppTheme.Dimens.fabIconSize),
            )
        }
    }
}

/**
 * Small info card shown at the bottom of the screen.
 */
@Composable
fun InfoCard(
    icon: ImageVector,
    label: String,
    isDark: Boolean,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = if (isDark) AppTheme.DarkColors else AppTheme.LightColors
    val gradient = Brush.verticalGradient(
        listOf(
            colors.cardBackground,
            colors.cardBackground.copy(alpha = 0.6f),
        ),
    )

    Surface(
        onClick          = onClick,
        modifier         = modifier.height(68.dp),
        shape            = AppTheme.Shapes.card,
        color            = Color.Transparent,
        border           = BorderStroke(1.dp, colors.cardBorder),
        tonalElevation   = 0.dp,
        shadowElevation  = 1.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = colors.accentCyan,
                    modifier           = Modifier.size(18.dp),
                )
                Text(
                    text       = label,
                    color      = colors.textSecondary,
                    fontSize   = AppTheme.TextSize.caption,
                    fontWeight = FontWeight.Medium,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

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
    partialText: String = "",
    onLanguageChange: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings        = LocalStrings.current
    val isDark         = LocalIsDarkTheme.current
    val colors         = if (isDark) AppTheme.DarkColors else AppTheme.LightColors
    val listState      = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to newest entry
    LaunchedEffect(transcripts.size, autoScroll) {
        if (autoScroll && transcripts.isNotEmpty())
            coroutineScope.launch { listState.animateScrollToItem(transcripts.size - 1) }
    }

    // Waveform — single atomic state per tick
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

    // Pulse animation for FAB
    val infiniteTransition = rememberInfiniteTransition(label = "rec-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue  = 0f,
        animationSpec = infiniteRepeatable(
            tween(AppTheme.Animation.pulseDurationMs, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "pulse-alpha",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = AppTheme.Dimens.pulseRingFactor,
        animationSpec = infiniteRepeatable(
            tween(AppTheme.Animation.pulseDurationMs, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "pulse-scale",
    )

    var showSaveDialog by remember { mutableStateOf(false) }

    if (showSaveDialog) {
        SaveTranscriptDialog(
            transcripts = transcripts,
            strings     = strings,
            isDark      = isDark,
            onDismiss   = { showSaveDialog = false },
        )
    }

    // ── Root layout ───────────────────────────────────────────────────────────
    GradientBackground(isDark = isDark) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {

            // [1] Top app bar
            LiveTopAppBar(
                title       = strings.appTitle,
                onSettings  = onOpenSettings,
                isDark      = isDark,
                isRecording = isRecording,
            )

            // [2] Language segment control
            LanguageSegmentRow(
                selectedLanguage = transcriptionLanguage,
                onSelect         = onLanguageChange,
                isDark           = isDark,
                isRecording      = isRecording,
            )

            // [3] Transcript card (fills remaining vertical space)
            val cardBg     = colors.cardBackground
            val cardBorder = colors.cardBorder

            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape  = AppTheme.Shapes.card,
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 12.dp, bottom = 4.dp),
                ) {
                    // Status / live partial text
                    Text(
                        text = when {
                            !modelsReady -> strings.modelsLoading
                            isRecording && partialText.isNotBlank() -> partialText
                            isRecording  -> strings.liveTranscriptionRunning
                            transcripts.isNotEmpty() -> strings.transcript
                            else         -> strings.startToBegin
                        },
                        color      = if (isRecording) colors.accentCyan else colors.textSecondary,
                        fontSize   = AppTheme.TextSize.caption,
                        fontWeight = if (isRecording) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp),
                    )

                    // Waveform — animates in while recording
                    AnimatedVisibility(
                        visible = isRecording,
                        enter   = fadeIn(tween(200)),
                        exit    = fadeOut(tween(200)),
                    ) {
                        WaveformVisualizer(
                            waveform = waveform,
                            isDark   = isDark,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }

                    // Transcript list or hint
                    if (transcripts.isEmpty()) {
                        Box(
                            modifier         = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text      = if (modelsReady) strings.startToBegin
                                            else strings.modelsLoading,
                                color     = colors.textSecondary.copy(alpha = 0.6f),
                                fontSize  = AppTheme.TextSize.text,
                                textAlign = TextAlign.Center,
                                modifier  = Modifier.padding(horizontal = 24.dp),
                            )
                        }
                    } else {
                        LazyColumn(
                            state               = listState,
                            modifier            = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding      = PaddingValues(vertical = 8.dp),
                        ) {
                            items(transcripts) { entry ->
                                TranscriptBubble(
                                    entry         = entry,
                                    showTimestamp = showTimestamps,
                                    speakerLabel  = strings.speaker,
                                    unknownLabel  = strings.unknown,
                                    isDark        = isDark,
                                )
                            }
                        }
                    }
                }
            }

            // [4] Bottom section: FAB left | status+delete right | summary card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 8.dp, bottom = 12.dp),
            ) {
                // FAB row: record button (left) + status text + delete button (right)
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left — Record FAB
                    RecordingFab(
                        isRecording = isRecording,
                        modelsReady = modelsReady,
                        onToggle    = { if (isRecording) onStopRecording() else onStartRecording() },
                        pulseAlpha  = pulseAlpha,
                        pulseScale  = pulseScale,
                        isDark      = isDark,
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // Centre — status text (takes remaining width)
                    Text(
                        text = when {
                            !modelsReady -> strings.modelsLoading
                            isRecording  -> strings.recordingRunning
                            else         -> strings.ready
                        },
                        color      = if (isRecording) colors.recordingLabel else colors.textSecondary,
                        fontSize   = AppTheme.TextSize.body,
                        fontWeight = if (isRecording) FontWeight.SemiBold else FontWeight.Normal,
                        modifier   = Modifier.weight(1f),
                    )

                    // Right — Delete button (visible when there is content and not recording)
                    AnimatedVisibility(visible = transcripts.isNotEmpty() && !isRecording) {
                        TextButton(
                            onClick = onClear,
                            colors  = ButtonDefaults.textButtonColors(
                                contentColor = colors.textSecondary.copy(alpha = 0.55f),
                            ),
                        ) {
                            Text(strings.deleteAll, fontSize = AppTheme.TextSize.caption)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Summary / save card — full width
                InfoCard(
                    icon     = Icons.Filled.Description,
                    label    = strings.summary,
                    isDark   = isDark,
                    onClick  = { if (transcripts.isNotEmpty()) showSaveDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
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
    isDark: Boolean        = true,
) {
    val palette = if (isDark) AppTheme.DarkColors.speakers else AppTheme.LightColors.speakers
    val color   = palette[entry.speakerId.coerceAtLeast(0) % palette.size]
    val textPrimary = if (isDark) AppTheme.DarkColors.textPrimary else AppTheme.LightColors.textPrimary
    val textSecondary = if (isDark) AppTheme.DarkColors.textSecondary else AppTheme.LightColors.textSecondary
    val surface = if (isDark) AppTheme.DarkColors.surface else AppTheme.LightColors.surface

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
                    color    = textSecondary.copy(alpha = 0.5f),
                )
            }
        }
        Surface(
            shape = AppTheme.Shapes.bubble,
            color = surface,
        ) {
            Text(
                text       = entry.text,
                color      = textPrimary.copy(alpha = 0.92f),
                fontSize   = AppTheme.TextSize.text,
                lineHeight  = AppTheme.TextSize.subtitle,
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
    isDark: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors  = if (isDark) AppTheme.DarkColors else AppTheme.LightColors

    AlertDialog(
        onDismissRequest  = onDismiss,
        containerColor    = colors.dialog,
        titleContentColor = colors.textPrimary,
        textContentColor  = colors.textSecondary,
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
                        shape          = AppTheme.Shapes.button,
                        border         = BorderStroke(1.dp, colors.chipBorder),
                        colors         = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.textPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Column(
                            modifier            = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Text(label, fontWeight = FontWeight.Medium, fontSize = AppTheme.TextSize.text)
                            Text(desc, fontSize = AppTheme.TextSize.label, color = colors.textSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors  = ButtonDefaults.textButtonColors(contentColor = colors.textSecondary),
            ) {
                Text(strings.cancel)
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun formatTimestamp(millis: Long): String = timeFormat.format(Date(millis))
