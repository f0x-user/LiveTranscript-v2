package com.livetranscript.ui

import androidx.compose.runtime.compositionLocalOf
import com.livetranscript.ui.language.ArabicStrings
import com.livetranscript.ui.language.ChineseStrings
import com.livetranscript.ui.language.DutchStrings
import com.livetranscript.ui.language.EnglishStrings
import com.livetranscript.ui.language.FrenchStrings
import com.livetranscript.ui.language.GermanStrings
import com.livetranscript.ui.language.ItalianStrings
import com.livetranscript.ui.language.JapaneseStrings
import com.livetranscript.ui.language.KoreanStrings
import com.livetranscript.ui.language.PolishStrings
import com.livetranscript.ui.language.PortugueseStrings
import com.livetranscript.ui.language.RussianStrings
import com.livetranscript.ui.language.SpanishStrings
import com.livetranscript.ui.language.TurkishStrings
import java.util.Locale

/**
 * Alle sichtbaren Strings der App in einem typsicheren Halter.
 *
 * Jede Sprache hat eine eigene Datei unter ui/language/Strings_XX.kt.
 * Neue Sprache hinzufügen:
 *   1. Neue Datei ui/language/Strings_XX.kt anlegen
 *   2. Import und when-Branch in stringsForLanguage() ergänzen
 */
data class AppStrings(
    // App bar
    val appTitle: String,
    // Aufnahmestatus
    val recordingRunning: String,
    val ready: String,
    val modelsLoading: String,
    val liveTranscriptionRunning: String,
    val startToBegin: String,
    // Buttons / Aktionen
    val startRecording: String,
    val stopRecording: String,
    val deleteAll: String,
    val save: String,
    val cancel: String,
    // Transkript
    val speaker: String,
    val unknown: String,
    // Einstellungen
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
    // Speichern-Dialog
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
    // Sprachauswahl
    val selectLanguage: String,
    val autoDetect: String,
    // Info-Karten
    val summary: String,
)

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Gibt die passenden [AppStrings] für den gewählten Whisper-Sprachcode zurück.
 * Leerer String ("") = Auto → Gerätesprache wird verwendet.
 */
fun stringsForLanguage(languageCode: String): AppStrings = when (languageCode) {
    "de" -> GermanStrings
    "en" -> EnglishStrings
    "fr" -> FrenchStrings
    "es" -> SpanishStrings
    "it" -> ItalianStrings
    "pt" -> PortugueseStrings
    "tr" -> TurkishStrings
    "nl" -> DutchStrings
    "pl" -> PolishStrings
    "ru" -> RussianStrings
    "zh" -> ChineseStrings
    "ja" -> JapaneseStrings
    "ko" -> KoreanStrings
    "ar" -> ArabicStrings
    ""   -> stringsForLanguage(Locale.getDefault().language)  // Auto → Gerätesprache
    else -> GermanStrings  // Fallback
}

/** CompositionLocal, das [AppStrings] der gesamten Composition bereitstellt. */
val LocalStrings = compositionLocalOf<AppStrings> { GermanStrings }
