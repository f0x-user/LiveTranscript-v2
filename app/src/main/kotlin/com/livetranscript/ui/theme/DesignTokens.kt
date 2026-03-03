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
 *  Quick reference:
 *    Background gradient .......... AppTheme.Gradients.background
 *    Brand / accent colours ....... AppTheme.Colors.accentBlue / accentCyan
 *    Recording FAB colour ......... AppTheme.Colors.recordActive
 *    Speaker label colours ........ AppTheme.Colors.speakers  (add more freely)
 *    Waveform bar count & height .. AppTheme.Dimens.waveformBars / waveformHeight
 *    Animation speed .............. AppTheme.Animation.*
 *    FAB size ..................... AppTheme.Dimens.fabSize
 *  ──────────────────────────────────────────────────────────────────────────
 */
object AppTheme {

    // ── Colours ───────────────────────────────────────────────────────────────
    object Colors {

        // Background — three layers used in the vertical gradient
        val bgDeep   = Color(0xFF0A1929)
        val bgMid    = Color(0xFF132B45)
        val bgLight  = Color(0xFF1A3456)

        // Surfaces / overlays
        val surface  = Color(0x1AFFFFFF)   // 10 % white  — transcript bubble fill
        val dialog   = Color(0xFF1A3050)   // save-dialog background

        // Brand accent
        val accentBlue = Color(0xFF1565C0)
        val accentCyan = Color(0xFF4FC3F7)  // waveform bars, focused borders

        // Record FAB states
        val recordActive   = Color(0xFFEF4444)   // actively recording
        val recordInactive = Color(0xFF546E7A)   // models not ready / disabled
        val recordPulse    = Color(0xFFEF4444)   // animated pulse-ring colour
        val recordingLabel = Color(0xFFEF9A9A)   // status text while recording

        // Text
        val textPrimary   = Color.White
        val textSecondary = Color(0xFFB0BEC5)

        // Language chip & dropdown
        val chipBorder   = Color(0x33FFFFFF)   // 20 % white outline
        val chipSelected = Color(0xFF1565C0)
        val chipDefault  = Color(0x1FFFFFFF)   // 12 % white fill

        /**
         * Speaker-label accent colours, indexed by speakerId % speakers.size.
         * Add more colours here if you regularly have > 6 speakers.
         */
        val speakers = listOf(
            Color(0xFF64B5F6),   // 0 – light blue
            Color(0xFF81C784),   // 1 – green
            Color(0xFFFFB74D),   // 2 – amber
            Color(0xFFCE93D8),   // 3 – lavender
            Color(0xFFEF9A9A),   // 4 – salmon
            Color(0xFF4DD0E1),   // 5 – cyan
        )

        // MaterialTheme colour-scheme values (Settings screen + M3 components)
        val m3Primary       = accentCyan
        val m3OnPrimary     = bgDeep
        val m3PrimaryContainer = accentBlue
        val m3Secondary     = Color(0xFF90CAF9)
        val m3Background    = bgDeep
        val m3Surface       = bgMid
        val m3SurfaceVariant = bgLight
        val m3OnSurface     = textPrimary
        val m3OnSurfaceVariant = textSecondary
    }

    // ── Gradients ─────────────────────────────────────────────────────────────
    object Gradients {
        /** Full-screen dark navy gradient applied behind all main-screen content. */
        val background: Brush = Brush.verticalGradient(
            colors = listOf(Colors.bgDeep, Colors.bgMid, Colors.bgLight),
        )
    }

    // ── Dimensions ────────────────────────────────────────────────────────────
    object Dimens {
        // Record FAB
        val fabSize: Dp     = 68.dp
        val fabIconSize: Dp = 30.dp

        // Pulse rings around FAB (shown while recording)
        val pulseRingInner: Dp   = 86.dp   // fixed inner ring diameter
        val pulseRingFactor: Float = 1.85f  // outer ring = FAB × this factor

        // Speaker dot in transcript bubble
        val speakerDot: Dp = 6.dp

        // Animated waveform
        val waveformHeight: Dp  = 44.dp
        val waveformPaddingH: Dp = 20.dp
        val waveformBars: Int   = 36
    }

    // ── Animation ─────────────────────────────────────────────────────────────
    object Animation {
        /** Milliseconds between waveform bar-height refreshes. Lower = faster. */
        val waveformTickMs: Long  = 110L
        /** Full cycle duration of the record-button pulse ring. */
        val pulseDurationMs: Int  = 950
    }

    // ── Shapes ────────────────────────────────────────────────────────────────
    object Shapes {
        /** Chat-bubble shape: sharp top-left corner, rounded elsewhere. */
        val bubble = RoundedCornerShape(
            topStart    = 4.dp,
            topEnd      = 12.dp,
            bottomEnd   = 12.dp,
            bottomStart = 12.dp,
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
