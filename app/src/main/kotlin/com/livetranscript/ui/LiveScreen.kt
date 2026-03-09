package com.livetranscript.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.livetranscript.settings.LANGUAGE_OPTIONS
import com.livetranscript.ui.theme.AppTheme
import com.livetranscript.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// Data model

/**
 * A single transcribed speech segment.
 *
 * @param speakerId 0-based speaker index assigned by [SherpaSpeakerDiarizer].
 *                  -1 means the speaker could not be identified.
 * @param text      Cleaned, non-blank transcription output.
 * @param timestamp Unix epoch milliseconds when the result was received by the UI.
 *                  Used for timestamps display and SRT export.
 */
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
 * Top app bar: centred title, Settings icon on the right.
 * Der Speichern-Button ist in die Dropdown-Zeile gewandert.
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
            .height(AppTheme.Dimens.topBarHeight)
            .background(colors.topBarContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = title,
            fontSize   = AppTheme.TextSize.title,
            fontWeight = FontWeight.Bold,
            color      = colors.textPrimary,
        )
        IconButton(
            onClick  = onSettings,
            enabled  = !isRecording,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Icon(
                imageVector        = Icons.Filled.Settings,
                contentDescription = "Einstellungen",
                tint               = colors.textPrimary.copy(
                    alpha = if (isRecording) 0.35f else 0.85f,
                ),
            )
        }
    }
}

/**
 * Sprachauswahl-Dropdown — nimmt den ihr zugewiesenen Platz im Row ein.
 *
 * ── GRÖSSE ANPASSEN ─────────────────────────────────────────────────────────
 * Die Breite des Dropdowns wird durch das übergeordnete Row gesteuert:
 *   Modifier.weight(1f)  → füllt verfügbaren Platz (Standard)
 *   Modifier.width(200.dp) → feste Breite
 *
 * Abstände zwischen Dropdown und Speichern-Button:
 *   AppTheme.Dimens.dropdownSaveSpacing  (in DesignTokens.kt)
 *
 * Abstand der gesamten Zeile vom Rand:
 *   padding(horizontal = X.dp) im Row in LiveScreen (Abschnitt [2])
 * ────────────────────────────────────────────────────────────────────────────
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(
    selectedLanguage: String,
    onSelect: (String) -> Unit,
    isDark: Boolean,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = if (isDark) AppTheme.DarkColors else AppTheme.LightColors
    var expanded by remember { mutableStateOf(false) }

    val selectedName = LANGUAGE_OPTIONS.find { it.first == selectedLanguage }?.second ?: "Auto"
    val selectedFlag = LANG_FLAG[selectedLanguage] ?: "🌍"

    ExposedDropdownMenuBox(
        expanded         = expanded,
        onExpandedChange = { if (!isRecording) expanded = it },
        modifier         = modifier,
    ) {
        OutlinedTextField(
            value         = "$selectedFlag  $selectedName",
            onValueChange = {},
            readOnly      = true,
            enabled       = !isRecording,
            singleLine    = true,
            leadingIcon   = {
                Text(
                    text     = "🌐",
                    fontSize = AppTheme.TextSize.subtitle,
                    color    = if (isRecording) colors.textSecondary.copy(alpha = 0.4f)
                               else colors.textSecondary,
                )
            },
            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape         = AppTheme.Shapes.dropdown,
            colors        = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor      = colors.chipBorder,
                focusedBorderColor        = colors.accentCyan,
                unfocusedContainerColor   = colors.chipDefault,
                focusedContainerColor     = colors.chipDefault,
                unfocusedTextColor        = colors.textPrimary,
                focusedTextColor          = colors.textPrimary,
                disabledBorderColor       = colors.chipBorder.copy(alpha = 0.35f),
                disabledContainerColor    = colors.chipDefault.copy(alpha = 0.35f),
                disabledTextColor         = colors.textSecondary.copy(alpha = 0.4f),
                disabledTrailingIconColor = colors.textSecondary.copy(alpha = 0.4f),
                disabledLeadingIconColor  = colors.textSecondary.copy(alpha = 0.4f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )

        ExposedDropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
        ) {
            LANGUAGE_OPTIONS.forEach { (code, name) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text     = "${LANG_FLAG[code] ?: "🌍"}  $name",
                            color    = colors.textPrimary,
                            fontSize = AppTheme.TextSize.text,
                        )
                    },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/**
 * Animated waveform bar visualiser with centre-bright gradient.
 */
@Composable
fun WaveformVisualizer(
    waveform: FloatArray,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val barColor = if (isDark) AppTheme.DarkColors.accentCyan else AppTheme.LightColors.accentCyan
    val count = waveform.size

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(AppTheme.Dimens.waveformHeight)
            .padding(horizontal = AppTheme.Dimens.waveformPaddingH),
    ) {
        val spacing = size.width / count
        val barW    = spacing * 0.52f
        for (i in 0 until count) {
            val centreFactor = 1f - abs(2f * i / count.toFloat() - 1f) * 0.35f
            val h = (waveform[i] * size.height).coerceAtLeast(3f)
            drawRoundRect(
                color        = barColor.copy(alpha = centreFactor),
                topLeft      = Offset(i * spacing + spacing * 0.24f, (size.height - h) / 2f),
                size         = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/**
 * Aufnahme-Button — rechteckig, Größe über DesignTokens.kt steuerbar.
 *
 * ── GRÖSSE & POSITION ANPASSEN ───────────────────────────────────────────────
 *   Breite:  AppTheme.Dimens.fabWidth    (in DesignTokens.kt)
 *   Höhe:    AppTheme.Dimens.fabHeight   (in DesignTokens.kt)
 *   Icon:    AppTheme.Dimens.fabIconSize  (in DesignTokens.kt)
 *   Form:    AppTheme.Shapes.button       (in DesignTokens.kt)
 *
 *   Für dynamische Breite (füllt verfügbaren Platz):
 *   In LiveScreen.kt → modifier = Modifier.weight(1f).height(AppTheme.Dimens.fabHeight)
 * ────────────────────────────────────────────────────────────────────────────
 */
@Composable
fun RecordingFab(
    isRecording: Boolean,
    modelsReady: Boolean,
    onToggle: () -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors   = if (isDark) AppTheme.DarkColors else AppTheme.LightColors
    val fabColor = if (modelsReady) colors.recordActive else colors.recordInactive

    val infiniteTransition = rememberInfiniteTransition(label = "fab-pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = AppTheme.Dimens.pulseFabScale,
        animationSpec = infiniteRepeatable(
            tween(AppTheme.Animation.pulseDurationMs, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "fab-scale",
    )

    Button(
        onClick  = { if (!modelsReady) return@Button; onToggle() },
        modifier = modifier.graphicsLayer {
            val s = if (isRecording) pulseScale else 1f
            scaleX = s; scaleY = s
        },
        shape    = AppTheme.Shapes.button,
        colors   = ButtonDefaults.buttonColors(
            containerColor         = fabColor,
            contentColor           = Color.White,
            disabledContainerColor = colors.recordInactive.copy(alpha = 0.5f),
            disabledContentColor   = Color.White.copy(alpha = 0.5f),
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (isRecording) 8.dp else 4.dp,
            pressedElevation = 3.dp,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Icon(
            imageVector        = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (isRecording) "Stop" else "Start",
            modifier           = Modifier.size(AppTheme.Dimens.fabIconSize),
        )
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// Main screen

/**
 * Main screen composable. Stateless — all state is passed as parameters.
 *
 * Layout sections (top → bottom):
 * 1. [LiveTopAppBar]     — app title + settings icon
 * 2. Language selector row — [LanguageSelector] + save button
 * 3. Transcript card    — status label, [WaveformVisualizer], [TranscriptBubble] list
 * 4. Bottom button row  — [RecordingFab] + delete button
 *
 * Waveform: currently **simulated** (random heights). To use real RMS data, expose a
 * `StateFlow<Float>` from [AudioRecorderService] and replace the [LaunchedEffect] below.
 *
 * To change spacing / colours / sizes: edit `DesignTokens.kt` only.
 *
 * @param isRecording           Whether recording is in progress.
 * @param transcripts           Ordered list of completed transcript entries.
 * @param modelsReady           Whether ONNX model files are available (gates the record button).
 * @param autoScroll            Whether to auto-scroll the transcript list on new entries.
 * @param showTimestamps        Whether to render timestamps inside [TranscriptBubble].
 * @param transcriptionLanguage Currently selected Whisper language code.
 * @param partialText           Live partial result from Google Speech (shown while speaking).
 * @param onLanguageChange      Called when the user picks a different language.
 * @param onStartRecording      Called when the record FAB is pressed while idle.
 * @param onStopRecording       Called when the record FAB is pressed while recording.
 * @param onClear               Called when the delete button is pressed.
 * @param onOpenSettings        Called when the settings icon is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    isRecording: Boolean,
    transcripts: List<TranscriptEntry>,
    modelsReady: Boolean,
    autoScroll: Boolean,
    showTimestamps: Boolean,
    transcriptionLanguage: String,
    modifier: Modifier = Modifier,
    partialText: String = "",
    onLanguageChange: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
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

            // [1] Top app bar (Speichern-Button ist in Zeile [2] gewandert)
            LiveTopAppBar(
                title       = strings.appTitle,
                onSettings  = onOpenSettings,
                isDark      = isDark,
                isRecording = isRecording,
            )

            // ─────────────────────────────────────────────────────────────────
            // [2] Sprachauswahl-Zeile: [Dropdown] [Speichern-Button]
            //
            // ── ABSTÄNDE DER GESAMTEN ZEILE ANPASSEN ─────────────────────────
            // Abstand links/rechts:  padding(horizontal = X.dp)
            // Abstand oben/unten:    padding(vertical = X.dp)
            // Abstand Dropdown ↔ Speichern-Button: AppTheme.Dimens.dropdownSaveSpacing
            // ─────────────────────────────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(start = AppTheme.Dimens.langRowPaddingH, top = AppTheme.Dimens.langRowPaddingTop, end = AppTheme.Dimens.langRowPaddingH, bottom = AppTheme.Dimens.langRowPaddingBottom),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Dropdown füllt den verfügbaren Platz (weight(1f))
                LanguageSelector(
                    selectedLanguage = transcriptionLanguage,
                    onSelect         = onLanguageChange,
                    isDark           = isDark,
                    isRecording      = isRecording,
                    modifier         = Modifier.weight(1f),
                )

                Spacer(modifier = Modifier.width(AppTheme.Dimens.dropdownSaveSpacing))

                // ── SPEICHERN-BUTTON ANPASSEN ─────────────────────────────────
                // Breite:  AppTheme.Dimens.saveButtonWidth   (in DesignTokens.kt)
                // Höhe:    AppTheme.Dimens.saveButtonHeight   (in DesignTokens.kt)
                // Form:    AppTheme.Shapes.button             (in DesignTokens.kt)
                // Immer sichtbar; ausgegraut wenn kein Transkript oder Aufnahme läuft.
                // ─────────────────────────────────────────────────────────────
                Button(
                    onClick  = { showSaveDialog = true },
                    enabled  = transcripts.isNotEmpty() && !isRecording,
                    modifier = Modifier
                        .width(AppTheme.Dimens.saveButtonWidth)
                        .height(AppTheme.Dimens.saveButtonHeight),
                    shape    = AppTheme.Shapes.button,
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = colors.accentCyan,
                        contentColor           = Color.White,
                        disabledContainerColor = colors.chipBorder.copy(alpha = 0.3f),
                        disabledContentColor   = colors.textSecondary.copy(alpha = 0.5f),
                    ),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Save,
                        contentDescription = strings.save,
                    )
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // [3] Transcript-Karte (füllt restlichen vertikalen Platz)
            // ─────────────────────────────────────────────────────────────────
            Card(
                modifier  = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = AppTheme.Dimens.transcriptCardPaddingH, top = AppTheme.Dimens.transcriptCardPaddingTop, end = AppTheme.Dimens.transcriptCardPaddingH, bottom = AppTheme.Dimens.transcriptCardPaddingBottom),
                shape     = AppTheme.Shapes.card,
                colors    = CardDefaults.cardColors(containerColor = colors.cardBackground),
                border    = BorderStroke(1.dp, colors.cardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = AppTheme.Dimens.transcriptCardInnerTop, bottom = AppTheme.Dimens.transcriptCardInnerBottom),
                ) {
                    // Status / Live-Partial-Text
                    Text(
                        text = when {
                            !modelsReady -> strings.modelsLoading
                            isRecording && partialText.isNotBlank() -> partialText
                            isRecording  -> strings.liveTranscriptionRunning
                            transcripts.isNotEmpty() -> strings.transcript
                            else         -> " "
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

                    // Waveform — sichtbar während Aufnahme
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

                    // Transkript-Liste oder Platzhalter
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
                                .padding(horizontal = AppTheme.Dimens.transcriptListPaddingH),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.Dimens.transcriptListItemSpacing),
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

            // ─────────────────────────────────────────────────────────────────
            // [4] Untere Button-Reihe: [Aufnahme-Button] [Löschen-Button]
            //
            // ── BUTTON-REIHE POSITION ANPASSEN ────────────────────────────────
            // Abstand vom Rand:  padding(horizontal = X.dp)
            // Abstand oben:      padding(top = X.dp)
            // Abstand zwischen Aufnahme- und Löschen-Button:
            //                    AppTheme.Dimens.bottomRowSpacing
            // ─────────────────────────────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(start = AppTheme.Dimens.bottomRowPaddingH, top = AppTheme.Dimens.bottomRowPaddingTop, end = AppTheme.Dimens.bottomRowPaddingH, bottom = AppTheme.Dimens.bottomRowPaddingBottom),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ── AUFNAHME-BUTTON ───────────────────────────────────────────
                // Breite und Höhe: AppTheme.Dimens.fabWidth / fabHeight
                // Für volle Zeilenbreite: modifier = Modifier.weight(1f).height(AppTheme.Dimens.fabHeight)
                // ─────────────────────────────────────────────────────────────
                RecordingFab(
                    isRecording = isRecording,
                    modelsReady = modelsReady,
                    onToggle    = { if (isRecording) onStopRecording() else onStartRecording() },
                    isDark      = isDark,
                    modifier    = Modifier
                        .width(AppTheme.Dimens.fabWidth)
                        .height(AppTheme.Dimens.fabHeight),
                )

                Spacer(modifier = Modifier.width(AppTheme.Dimens.bottomRowSpacing))

                // ── LÖSCHEN-BUTTON ────────────────────────────────────────────
                // Breite und Höhe: AppTheme.Dimens.deleteButtonWidth / deleteButtonHeight
                // Dauerhaft sichtbar; ausgegraut wenn kein Transkript oder Aufnahme läuft.
                // ─────────────────────────────────────────────────────────────
                Button(
                    onClick  = onClear,
                    enabled  = transcripts.isNotEmpty() && !isRecording,
                    modifier = Modifier
                        .width(AppTheme.Dimens.deleteButtonWidth)
                        .height(AppTheme.Dimens.deleteButtonHeight),
                    shape    = AppTheme.Shapes.button,
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = colors.accentCyan,
                        contentColor           = Color.White,
                        disabledContainerColor = colors.chipBorder.copy(alpha = 0.3f),
                        disabledContentColor   = colors.textSecondary.copy(alpha = 0.5f),
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                ) {
                    Text(strings.deleteAll, fontSize = AppTheme.TextSize.caption)
                }
            }

        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Transcript bubble

/**
 * Renders a single transcript entry as a speaker-coloured chat bubble.
 *
 * The speaker colour is derived from [AppTheme.DarkColors.speakers] / [AppTheme.LightColors.speakers]
 * using `speakerId % palette.size`, so colours cycle if there are more speakers than palette entries.
 *
 * To add more speaker colours: append entries to [AppTheme.DarkColors.speakers] and
 * [AppTheme.LightColors.speakers] in `DesignTokens.kt`.
 *
 * @param entry         The transcript data to display.
 * @param showTimestamp Whether to render the formatted timestamp beside the speaker label.
 * @param speakerLabel  Localised label prefix (e.g. "Speaker" → "Speaker 1").
 * @param unknownLabel  Localised label used when [TranscriptEntry.speakerId] is -1.
 * @param isDark        Drives the colour palette selection.
 */
@Composable
fun TranscriptBubble(
    entry: TranscriptEntry,
    showTimestamp: Boolean = false,
    speakerLabel: String   = "Speaker",
    unknownLabel: String   = "Unknown",
    isDark: Boolean        = true,
) {
    val palette       = if (isDark) AppTheme.DarkColors.speakers else AppTheme.LightColors.speakers
    val speakerColor  = palette[entry.speakerId.coerceAtLeast(0) % palette.size]
    val textPrimary   = if (isDark) AppTheme.DarkColors.textPrimary   else AppTheme.LightColors.textPrimary
    val textSecondary = if (isDark) AppTheme.DarkColors.textSecondary else AppTheme.LightColors.textSecondary

    Column(modifier = Modifier.fillMaxWidth()) {
        // Speaker-Label-Zeile
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(bottom = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(AppTheme.Dimens.speakerDot)
                    .background(speakerColor, CircleShape),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text       = if (entry.speakerId >= 0) "$speakerLabel ${entry.speakerId + 1}"
                             else unknownLabel,
                color      = speakerColor,
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
        // Bubble mit Speaker-Farbe
        Surface(
            shape           = AppTheme.Shapes.bubble,
            color           = speakerColor.copy(alpha = if (isDark) 0.10f else 0.07f),
            border          = BorderStroke(1.dp, speakerColor.copy(alpha = 0.30f)),
            tonalElevation  = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Text(
                text      = entry.text,
                color     = textPrimary,
                fontSize  = AppTheme.TextSize.text,
                lineHeight = AppTheme.TextSize.subtitle,
                modifier  = Modifier.padding(
                    horizontal = AppTheme.Dimens.bubblePaddingH,
                    vertical   = AppTheme.Dimens.bubblePaddingV,
                ),
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

@Suppress("ConstantLocale")
private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun formatTimestamp(millis: Long): String = timeFormat.format(Date(millis))
