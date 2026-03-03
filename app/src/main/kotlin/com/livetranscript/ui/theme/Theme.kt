package com.livetranscript.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.livetranscript.settings.ThemeMode

// ── Colour schemes ────────────────────────────────────────────────────────────
// All brand colours are read from AppTheme.Colors (DesignTokens.kt).
// To restyle the whole app, edit DesignTokens.kt — do not hardcode colours here.

private val DarkColorScheme = darkColorScheme(
    primary            = AppTheme.Colors.m3Primary,
    onPrimary          = AppTheme.Colors.m3OnPrimary,
    primaryContainer   = AppTheme.Colors.m3PrimaryContainer,
    onPrimaryContainer = Color.White,
    secondary          = AppTheme.Colors.m3Secondary,
    onSecondary        = AppTheme.Colors.m3OnPrimary,
    background         = AppTheme.Colors.m3Background,
    onBackground       = AppTheme.Colors.m3OnSurface,
    surface            = AppTheme.Colors.m3Surface,
    onSurface          = AppTheme.Colors.m3OnSurface,
    surfaceVariant     = AppTheme.Colors.m3SurfaceVariant,
    onSurfaceVariant   = AppTheme.Colors.m3OnSurfaceVariant,
)

private val LightColorScheme = lightColorScheme(
    primary          = Blue40,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFBBDEFB),
    secondary        = Cyan40,
    onSecondary      = Color.White,
    background       = Color(0xFFF5F7FA),
    onBackground     = Color(0xFF1A1A2E),
    surface          = Color.White,
    onSurface        = Color(0xFF1A1A2E),
    surfaceVariant   = Color(0xFFE3EAF2),
    onSurfaceVariant = Color(0xFF455A64),
)

// ── Theme composable ──────────────────────────────────────────────────────────

@Composable
fun LiveTranscript2Theme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Status bar always dark-navy so it blends with the gradient
            window.statusBarColor = AppTheme.Colors.bgDeep.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content,
    )
}
