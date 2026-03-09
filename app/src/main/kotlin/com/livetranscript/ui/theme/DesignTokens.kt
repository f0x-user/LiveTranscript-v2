package com.livetranscript.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ══════════════════════════════════════════════════════════════════════════════
 *  AppTheme  —  Single source of truth for all visual design decisions.
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  HOW TO CUSTOMISE THE UI
 *  ──────────────────────────────────────────────────────────────────────────
 *  Edit ONLY this file. Every screen reads from AppTheme, so changes
 *  propagate automatically throughout the whole app.
 *
 *  Theme-aware usage in composables:
 *    val isDark  = LocalIsDarkTheme.current
 *    val colors  = if (isDark) AppTheme.DarkColors else AppTheme.LightColors
 *    colors.accentCyan   // type-safe — both are AppColorPalette
 *  ──────────────────────────────────────────────────────────────────────────
 */

// ── Shared colour palette type ────────────────────────────────────────────────
/**
 * All colour values used by the app in a single strongly-typed holder.
 * [AppTheme.DarkColors] and [AppTheme.LightColors] are instances of this class,
 * which means composables can do:
 *
 *     val colors: AppColorPalette = if (isDark) AppTheme.DarkColors else AppTheme.LightColors
 *
 * and get full type-safe access to every colour slot.
 */
data class AppColorPalette(
    // Gradient layers (top → bottom)
    val bgDeep: Color,
    val bgMid: Color,
    val bgLight: Color,

    // Surfaces / overlays
    val surface: Color,
    val dialog: Color,
    val topBarContainer: Color,
    val cardBackground: Color,
    val cardBorder: Color,

    // Brand accent
    val accentBlue: Color,
    val accentCyan: Color,

    // Record FAB states
    val recordActive: Color,
    val recordInactive: Color,
    val recordPulse: Color,
    val recordingLabel: Color,

    // Text
    val textPrimary: Color,
    val textSecondary: Color,

    // Language chip / segment control
    val chipBorder: Color,
    val chipSelected: Color,
    val chipDefault: Color,

    // Speaker bubble accent colours (indexed by speakerId % size)
    val speakers: List<Color>,

    // Material 3 slot mappings
    val m3Primary: Color,
    val m3OnPrimary: Color,
    val m3PrimaryContainer: Color,
    val m3Secondary: Color,
    val m3Background: Color,
    val m3Surface: Color,
    val m3SurfaceVariant: Color,
    val m3OnSurface: Color,
    val m3OnSurfaceVariant: Color,
)

// ── AppTheme ───────────────────────────────────────────────────────────────────
object AppTheme {

    // ── Dark colour palette ────────────────────────────────────────────────────
    val DarkColors = AppColorPalette(
        bgDeep   = Color(0xFF0A1929),
        bgMid    = Color(0xFF132B45),
        bgLight  = Color(0xFF1A3456),

        surface        = Color(0x1AFFFFFF),
        dialog         = Color(0xFF1A3050),
        topBarContainer = Color(0x99132B45),
        cardBackground  = Color(0x1AFFFFFF),
        cardBorder      = Color(0x26FFFFFF),

        accentBlue = Color(0xFF1565C0),
        accentCyan = Color(0xFF4FC3F7),

        recordActive   = Color(0xFFEF4444),
        recordInactive = Color(0xFF546E7A),
        recordPulse    = Color(0xFFEF4444),
        recordingLabel = Color(0xFFEF9A9A),

        textPrimary   = Color.White,
        textSecondary = Color(0xFFCFD8DC),  // heller als vorher (war 0xFFB0BEC5)

        chipBorder   = Color(0x33FFFFFF),
        chipSelected = Color(0xFF1565C0),
        chipDefault  = Color(0x1FFFFFFF),

        speakers = listOf(
            Color(0xFF64B5F6),
            Color(0xFF81C784),
            Color(0xFFFFB74D),
            Color(0xFFCE93D8),
            Color(0xFFEF9A9A),
            Color(0xFF4DD0E1),
        ),

        m3Primary            = Color(0xFF4FC3F7),
        m3OnPrimary          = Color(0xFF0A1929),
        m3PrimaryContainer   = Color(0xFF1565C0),
        m3Secondary          = Color(0xFF90CAF9),
        m3Background         = Color(0xFF0A1929),
        m3Surface            = Color(0xFF132B45),
        m3SurfaceVariant     = Color(0xFF1A3456),
        m3OnSurface          = Color.White,
        m3OnSurfaceVariant   = Color(0xFFB0BEC5),
    )

    // ── Light colour palette ───────────────────────────────────────────────────
    val LightColors = AppColorPalette(
        bgDeep   = Color(0xFFF0F6FF),   // alias: bgTop for gradient
        bgMid    = Color(0xFFD8EAFF),
        bgLight  = Color(0xFFC2DAFF),

        surface        = Color(0xCCFFFFFF),
        dialog         = Color(0xFFEBF3FF),
        topBarContainer = Color(0xB3F0F6FF),
        cardBackground  = Color(0xB3FFFFFF),
        cardBorder      = Color(0x401565C0),

        accentBlue = Color(0xFF1565C0),
        accentCyan = Color(0xFF0288D1),

        recordActive   = Color(0xFFD32F2F),
        recordInactive = Color(0xFF78909C),
        recordPulse    = Color(0xFFD32F2F),
        recordingLabel = Color(0xFFB71C1C),

        textPrimary   = Color(0xFF0D1B2A),
        textSecondary = Color(0xFF455A64),

        chipBorder   = Color(0x661565C0),
        chipSelected = Color(0xFF1565C0),
        chipDefault  = Color(0x141565C0),

        speakers = listOf(
            Color(0xFF1565C0),
            Color(0xFF2E7D32),
            Color(0xFFE65100),
            Color(0xFF6A1B9A),
            Color(0xFFC62828),
            Color(0xFF00695C),
        ),

        m3Primary            = Color(0xFF1565C0),
        m3OnPrimary          = Color.White,
        m3PrimaryContainer   = Color(0xFFBBDEFB),
        m3Secondary          = Color(0xFF0288D1),
        m3Background         = Color(0xFFF0F6FF),
        m3Surface            = Color.White,
        m3SurfaceVariant     = Color(0xFFE3EAF2),
        m3OnSurface          = Color(0xFF0D1B2A),
        m3OnSurfaceVariant   = Color(0xFF455A64),
    )

    // ── Backward-compatible alias (= dark palette) ─────────────────────────────
    val Colors: AppColorPalette = DarkColors

    // ── Gradients ─────────────────────────────────────────────────────────────
    object Gradients {
        val dark: Brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0A1929),
                Color(0xFF132B45),
                Color(0xFF1A3456),
            ),
        )
        val light: Brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFF0F6FF),
                Color(0xFFD8EAFF),
                Color(0xFFC2DAFF),
            ),
        )
        /** Backward-compatible alias → dark gradient. */
        val background: Brush get() = dark
    }

    // ── Dimensions ────────────────────────────────────────────────────────────
    //
    //  ALLE BUTTON-GRÖSSEN HIER ZENTRAL ANPASSEN.
    //  Änderungen wirken sich automatisch auf die gesamte UI aus.
    //
    object Dimens {

        // ── Aufnahme-Button ────────────────────────────────────────────────────
        // Tipp: Für volle verfügbare Breite statt fester Breite in LiveScreen.kt
        //       modifier = Modifier.weight(1f).height(fabHeight) verwenden.
        val fabWidth: Dp    = 150.dp  // ← Breite des Aufnahme-Buttons
        val fabHeight: Dp   = 64.dp   // ← Höhe des Aufnahme-Buttons
        val fabIconSize: Dp = 38.dp   // ← Icon-Größe

        // ── Löschen-Button ─────────────────────────────────────────────────────
        val deleteButtonWidth: Dp  = 120.dp  // ← Breite des Löschen-Buttons
        val deleteButtonHeight: Dp = 64.dp   // ← Höhe des Löschen-Buttons

        // ── Speichern-Button (neben Sprachauswahl) ─────────────────────────────
        val saveButtonWidth: Dp  = 80.dp   // ← Breite des Speichern-Buttons
        val saveButtonHeight: Dp = 56.dp   // ← Höhe des Speichern-Buttons

        // ── [1] Top-App-Bar ────────────────────────────────────────────────────
        val topBarHeight: Dp = 64.dp           // ← Höhe der oberen Leiste

        // ── [2] Sprachauswahl-Zeile ────────────────────────────────────────────
        val langRowPaddingH: Dp      = 50.dp   // ← Außenabstand links/rechts
        val langRowPaddingTop: Dp    = 20.dp    // ← Außenabstand oben
        val langRowPaddingBottom: Dp = 8.dp    // ← Außenabstand unten
        val dropdownSaveSpacing: Dp = 48.dp    // ← Abstand Dropdown ↔ Speichern-Button

        // ── [3] Transkript-Karte ───────────────────────────────────────────────
        val transcriptCardPaddingH: Dp      = 16.dp  // ← Außenabstand links/rechts
        val transcriptCardPaddingTop: Dp    = 4.dp   // ← Außenabstand oben
        val transcriptCardPaddingBottom: Dp = 4.dp   // ← Außenabstand unten
        val transcriptCardInnerTop: Dp     = 12.dp  // ← Innenabstand oben
        val transcriptCardInnerBottom: Dp  = 4.dp   // ← Innenabstand unten
        val transcriptListPaddingH: Dp     = 12.dp  // ← Listeneinrückung links/rechts
        val transcriptListItemSpacing: Dp  = 10.dp  // ← Abstand zwischen Einträgen

        // ── [4] Untere Button-Reihe ────────────────────────────────────────────
        val bottomRowPaddingH: Dp      = 56.dp  // ← Außenabstand links/rechts
        val bottomRowPaddingTop: Dp    = 8.dp  // ← Außenabstand oben
        val bottomRowPaddingBottom: Dp = 58.dp  // ← Außenabstand unten
        val bottomRowSpacing: Dp  = 80.dp      // ← Abstand Aufnahme ↔ Löschen-Button

        // Subtle scale pulse (1.0 → pulseFabScale when recording)
        val pulseFabScale: Float = 1.04f

        // Speaker dot in transcript bubble
        val speakerDot: Dp = 6.dp

        // Transcript bubble
        val bubblePaddingH: Dp = 14.dp
        val bubblePaddingV: Dp = 10.dp

        // Animated waveform
        val waveformHeight: Dp   = 48.dp
        val waveformPaddingH: Dp = 20.dp
        val waveformBars: Int    = 36
    }

    // ── Animation ─────────────────────────────────────────────────────────────
    object Animation {
        val waveformTickMs: Long = 110L
        val pulseDurationMs: Int = 950
    }

    // ── Shapes ────────────────────────────────────────────────────────────────
    object Shapes {
        val card     = RoundedCornerShape(22.dp)
        val button   = RoundedCornerShape(18.dp)
        val dropdown = RoundedCornerShape(16.dp)

        /** Transcript bubble: sharp top-left, rounded elsewhere. */
        val bubble = RoundedCornerShape(
            topStart    = 4.dp,
            topEnd      = 16.dp,
            bottomEnd   = 16.dp,
            bottomStart = 16.dp,
        )
    }

    // ── Typography ────────────────────────────────────────────────────────────
    object TextSize {
        val micro:    TextUnit = 10.sp
        val label:    TextUnit = 11.sp
        val caption:  TextUnit = 12.sp
        val body:     TextUnit = 13.sp
        val text:     TextUnit = 15.sp
        val subtitle: TextUnit = 16.sp
        val title:    TextUnit = 20.sp
    }
}
