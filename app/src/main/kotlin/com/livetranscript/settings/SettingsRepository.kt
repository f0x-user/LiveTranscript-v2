package com.livetranscript.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val context: Context) {

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        prefs[PreferenceKeys.THEME_MODE]
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    }

    val autoScroll: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[PreferenceKeys.AUTO_SCROLL] ?: true
    }

    val showTimestamps: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[PreferenceKeys.SHOW_TIMESTAMPS] ?: false
    }

    val transcriptionLanguage: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[PreferenceKeys.TRANSCRIPTION_LANGUAGE] ?: ""
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[PreferenceKeys.THEME_MODE] = mode.name }
    }

    suspend fun setAutoScroll(enabled: Boolean) {
        context.settingsDataStore.edit { it[PreferenceKeys.AUTO_SCROLL] = enabled }
    }

    suspend fun setShowTimestamps(enabled: Boolean) {
        context.settingsDataStore.edit { it[PreferenceKeys.SHOW_TIMESTAMPS] = enabled }
    }

    suspend fun setTranscriptionLanguage(code: String) {
        context.settingsDataStore.edit { it[PreferenceKeys.TRANSCRIPTION_LANGUAGE] = code }
    }
}
