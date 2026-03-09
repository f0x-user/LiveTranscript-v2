package com.livetranscript.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
// Note: AsrBackend is kept in AppSettings.kt for use by AudioRecorderService internally.

/**
 * Single source of truth for all persistent user settings, backed by Jetpack DataStore.
 *
 * All settings are exposed as [Flow]s so that the UI (via [SettingsViewModel]) can
 * react to changes reactively. Setter functions are `suspend` and must be called from a
 * coroutine scope.
 *
 * [getLanguageSync] is a synchronous getter for use during [AudioRecorderService]
 * initialisation, before the coroutine scope is set up. Do not use it in UI code.
 *
 * DataStore key names and default values are defined in [PreferenceKeys] / [AppSettings.kt].
 *
 * Note: ASR backend selection is NOT persisted here — it is auto-detected at service
 * start time via [SpeechRecognizer.isRecognitionAvailable].
 */
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
        prefs[PreferenceKeys.TRANSCRIPTION_LANGUAGE] ?: "de"  // Standard: Deutsch statt Auto-Erkennung
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

    /** Synchronous read used during service initialisation (called before coroutines are set up). */
    fun getLanguageSync(): String = runBlocking {
        context.settingsDataStore.data.first()[PreferenceKeys.TRANSCRIPTION_LANGUAGE] ?: "de"
    }
}
