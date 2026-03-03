package com.livetranscript.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** Single DataStore instance scoped to the application. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object PreferenceKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val AUTO_SCROLL = booleanPreferencesKey("auto_scroll")
    val SHOW_TIMESTAMPS = booleanPreferencesKey("show_timestamps")
}
