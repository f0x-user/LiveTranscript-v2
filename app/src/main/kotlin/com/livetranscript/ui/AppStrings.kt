package com.livetranscript.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * All user-visible strings in one place.
 * Add a new language block below and wire it up in [stringsForLanguage].
 */
data class AppStrings(
    // App bar
    val appTitle: String,
    // Recording states
    val recordingRunning: String,
    val ready: String,
    val modelsLoading: String,
    val liveTranscriptionRunning: String,
    val startToBegin: String,
    // Buttons / actions
    val startRecording: String,
    val stopRecording: String,
    val deleteAll: String,
    val save: String,
    val cancel: String,
    // Transcript
    val speaker: String,
    val unknown: String,
    // Settings
    val settings: String,
    val settingsTitle: String,
    val appearance: String,
    val themeLight: String,
    val themeDark: String,
    val themeSystem: String,
    val transcript: String,
    val autoScroll: String,
    val autoScrollDesc: String,
    val showTimestamps: String,
    val showTimestampsDesc: String,
    val info: String,
    // Save dialog
    val saveTranscript: String,
    val selectFormat: String,
    val formatTxt: String,
    val formatTxtDesc: String,
    val formatCsv: String,
    val formatCsvDesc: String,
    val formatJson: String,
    val formatJsonDesc: String,
    val formatSrt: String,
    val formatSrtDesc: String,
    // Language picker
    val selectLanguage: String,
    val autoDetect: String,
    // ASR backend
    val asrBackend: String,
    val asrGoogle: String,
    val asrGoogleDesc: String,
    val asrWhisper: String,
    val asrWhisperDesc: String,
)

val GermanStrings = AppStrings(
    appTitle                = "Live-Transkript",
    recordingRunning        = "Aufnahme läuft…",
    ready                   = "Bereit",
    modelsLoading           = "Modelle werden geladen…",
    liveTranscriptionRunning = "Live-Transkription läuft…",
    startToBegin            = "Aufnahme starten, um zu beginnen",
    startRecording          = "Aufnahme starten",
    stopRecording           = "Aufnahme stoppen",
    deleteAll               = "Löschen",
    save                    = "Speichern",
    cancel                  = "Abbrechen",
    speaker                 = "Sprecher",
    unknown                 = "Unbekannt",
    settings                = "Einstellungen",
    settingsTitle           = "Einstellungen",
    appearance              = "Erscheinungsbild",
    themeLight              = "Hell",
    themeDark               = "Dunkel",
    themeSystem             = "System (automatisch)",
    transcript              = "Transkript",
    autoScroll              = "Automatisch scrollen",
    autoScrollDesc          = "Scrollt automatisch zum neuesten Eintrag",
    showTimestamps          = "Zeitstempel anzeigen",
    showTimestampsDesc      = "Zeigt die Uhrzeit neben jedem Eintrag",
    info                    = "Info",
    saveTranscript          = "Transkript speichern",
    selectFormat            = "Format auswählen",
    formatTxt               = "Nur Text (.txt)",
    formatTxtDesc           = "Einfaches Textformat",
    formatCsv               = "Tabelle (.csv)",
    formatCsvDesc           = "Für Excel, Sheets & Co.",
    formatJson              = "JSON (.json)",
    formatJsonDesc          = "Strukturiertes Datenformat",
    formatSrt               = "Untertitel (.srt)",
    formatSrtDesc           = "Für Videountertitel",
    selectLanguage          = "Sprache wählen",
    autoDetect              = "Automatisch",
    asrBackend              = "Spracherkennung",
    asrGoogle               = "Google (empfohlen)",
    asrGoogleDesc           = "Schnell, hohe Genauigkeit, nutzt Android-Sprachpakete",
    asrWhisper              = "Whisper (offline)",
    asrWhisperDesc          = "Vollständig offline, nutzt eingebettetes KI-Modell",
)

val EnglishStrings = AppStrings(
    appTitle                = "Live Transcript",
    recordingRunning        = "Recording…",
    ready                   = "Ready",
    modelsLoading           = "Loading models…",
    liveTranscriptionRunning = "Live transcription running…",
    startToBegin            = "Start recording to begin",
    startRecording          = "Start recording",
    stopRecording           = "Stop recording",
    deleteAll               = "Clear",
    save                    = "Save",
    cancel                  = "Cancel",
    speaker                 = "Speaker",
    unknown                 = "Unknown",
    settings                = "Settings",
    settingsTitle           = "Settings",
    appearance              = "Appearance",
    themeLight              = "Light",
    themeDark               = "Dark",
    themeSystem             = "System (automatic)",
    transcript              = "Transcript",
    autoScroll              = "Auto-scroll",
    autoScrollDesc          = "Automatically scroll to the latest entry",
    showTimestamps          = "Show timestamps",
    showTimestampsDesc      = "Display the time next to each entry",
    info                    = "Info",
    saveTranscript          = "Save transcript",
    selectFormat            = "Select format",
    formatTxt               = "Plain text (.txt)",
    formatTxtDesc           = "Simple text format",
    formatCsv               = "Spreadsheet (.csv)",
    formatCsvDesc           = "For Excel, Sheets etc.",
    formatJson              = "JSON (.json)",
    formatJsonDesc          = "Structured data format",
    formatSrt               = "Subtitles (.srt)",
    formatSrtDesc           = "For video subtitles",
    selectLanguage          = "Select language",
    autoDetect              = "Auto-detect",
    asrBackend              = "Speech recognition",
    asrGoogle               = "Google (recommended)",
    asrGoogleDesc           = "Fast, high accuracy, uses Android language packs",
    asrWhisper              = "Whisper (offline)",
    asrWhisperDesc          = "Fully offline, uses embedded AI model",
)

/** Returns the appropriate [AppStrings] for the given Whisper language code. */
fun stringsForLanguage(languageCode: String): AppStrings = when (languageCode) {
    "en" -> EnglishStrings
    else -> GermanStrings   // default – covers "de", "auto", and all others
}

/** CompositionLocal that provides [AppStrings] to the entire composition tree. */
val LocalStrings = compositionLocalOf<AppStrings> { GermanStrings }
