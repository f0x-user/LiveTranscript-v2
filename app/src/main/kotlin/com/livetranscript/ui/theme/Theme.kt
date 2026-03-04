package com.livetranscript.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.livetranscript.settings.ThemeMode

/**
 * CompositionLocal that exposes whether the current theme is dark.
 * Read it in any composable: val isDark = LocalIsDarkTheme.current
 */
val LocalIsDarkTheme = compositionLocalOf { true }

// ── Material 3 colour schemes ──────────────────────────────────────────────────
// All values are sourced from AppTheme.DarkColors / LightColors (DesignTokens.kt).

private val AppDarkColorScheme = darkColorScheme(
    primary            = AppTheme.DarkColors.m3Primary,
    onPrimary          = AppTheme.DarkColors.m3OnPrimary,
    primaryContainer   = AppTheme.DarkColors.m3PrimaryContainer,
    onPrimaryContainer = Color.White,
    secondary          = AppTheme.DarkColors.m3Secondary,
    onSecondary        = AppTheme.DarkColors.m3OnPrimary,
    background         = AppTheme.DarkColors.m3Background,
    onBackground       = AppTheme.DarkColors.m3OnSurface,
    surface            = AppTheme.DarkColors.m3Surface,
    onSurface          = AppTheme.DarkColors.m3OnSurface,
    surfaceVariant     = AppTheme.DarkColors.m3SurfaceVariant,
    onSurfaceVariant   = AppTheme.DarkColors.m3OnSurfaceVariant,
)

private val AppLightColorScheme = lightColorScheme(
    primary            = AppTheme.LightColors.m3Primary,
    onPrimary          = AppTheme.LightColors.m3OnPrimary,
    primaryContainer   = AppTheme.LightColors.m3PrimaryContainer,
    onPrimaryContainer = Color(0xFF0D1B2A),
    secondary          = AppTheme.LightColors.m3Secondary,
    onSecondary        = Color.White,
    background         = AppTheme.LightColors.m3Background,
    onBackground       = AppTheme.LightColors.m3OnSurface,
    surface            = AppTheme.LightColors.m3Surface,
    onSurface          = AppTheme.LightColors.m3OnSurface,
    surfaceVariant     = AppTheme.LightColors.m3SurfaceVariant,
    onSurfaceVariant   = AppTheme.LightColors.m3OnSurfaceVariant,
)

// ── Theme composable ───────────────────────────────────────────────────────────

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

    val colorScheme = if (darkTheme) AppDarkColorScheme else AppLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = (
                if (darkTheme) AppTheme.DarkColors.bgDeep
                else           AppTheme.LightColors.bgDeep
            ).toArgb()
            // Light status-bar icons on dark theme; dark icons on light theme
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = Typography,
            content     = content,
        )
    }
}
