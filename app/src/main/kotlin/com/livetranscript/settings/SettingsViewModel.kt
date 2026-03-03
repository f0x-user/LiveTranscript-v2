package com.livetranscript.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoScroll: Boolean = true,
    val showTimestamps: Boolean = false,
    val transcriptionLanguage: String = "",
)

class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.themeMode,
        repository.autoScroll,
        repository.showTimestamps,
        repository.transcriptionLanguage,
    ) { theme, scroll, timestamps, language ->
        SettingsUiState(
            themeMode             = theme,
            autoScroll            = scroll,
            showTimestamps        = timestamps,
            transcriptionLanguage = language,
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = SettingsUiState(),
    )

    fun setTheme(mode: ThemeMode)        = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setAutoScroll(enabled: Boolean)  = viewModelScope.launch { repository.setAutoScroll(enabled) }
    fun setShowTimestamps(enabled: Boolean) = viewModelScope.launch { repository.setShowTimestamps(enabled) }
    fun setLanguage(code: String)        = viewModelScope.launch { repository.setTranscriptionLanguage(code) }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(
                repository = SettingsRepository(context.applicationContext),
            ) as T
    }
}
