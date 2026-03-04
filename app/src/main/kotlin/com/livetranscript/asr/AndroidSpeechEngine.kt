package com.livetranscript.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Kontinuierliche ASR-Engine auf Basis von Android SpeechRecognizer.
 *
 * - Startet nach jedem Ergebnis automatisch neu (kontinuierliche Erkennung).
 * - Liefert Partial-Results für Echtzeit-Anzeige während des Sprechens.
 * - Sprach-Fallback bei ERROR_LANGUAGE_NOT_SUPPORTED (error 12):
 *   Stufe 0 → konfigurierte Sprache, Stufe 1 → Gerätesprache, Stufe 2 → Auto-Detect.
 * - SpeechRecognizer muss auf dem Main-Thread laufen; alle Aufrufe werden
 *   intern über Handler(mainLooper) geleitet.
 */
class AndroidSpeechEngine(
    private val context: Context,
    private val language: String,
    private val onResult: (String) -> Unit,
    private val onPartialResult: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var active = false

    // Language fallback state: 0 = use configured language, 1 = device default, 2 = auto-detect
    private var langFallbackLevel = 0

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        active = true
        langFallbackLevel = 0
        mainHandler.post {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
            }
            listen()
        }
    }

    fun stop() {
        active = false
        mainHandler.post {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun effectiveLocaleTag(): String = when (langFallbackLevel) {
        0    -> if (language.isEmpty()) Locale.getDefault().toLanguageTag()
                else Locale.forLanguageTag(language).toLanguageTag()
        1    -> Locale.getDefault().toLanguageTag()
        else -> ""  // auto-detect: omit EXTRA_LANGUAGE → engine chooses
    }

    private fun listen() {
        if (!active) return
        val localeTag = effectiveLocaleTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                     RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (localeTag.isNotEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Allow longer sessions: 3 s complete silence before the recognizer stops.
            // This reduces restart frequency → fewer mic-acquisition clicks and fewer
            // gaps where the first word of a new sentence is lost.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }
        recognizer?.startListening(intent)
        Log.d(TAG, "Listening started (fallbackLevel=$langFallbackLevel, locale='$localeTag')")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?)  = Unit
        override fun onBeginningOfSpeech()              = Unit
        override fun onRmsChanged(rmsdB: Float)         = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech()                    = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()
            if (!text.isNullOrBlank()) onPartialResult(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()
            if (!text.isNullOrBlank()) onResult(text)
            if (active) listen()
        }

        override fun onError(error: Int) {
            Log.w(TAG, "Error: ${errorName(error)} (fallbackLevel=$langFallbackLevel)")
            if (!active) return
            val delayMs = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 1000L
                SpeechRecognizer.ERROR_AUDIO             -> 2000L
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER            -> 2000L
                ERROR_LANGUAGE_NOT_SUPPORTED             -> {
                    // Advance language fallback: configured → device default → auto-detect
                    if (langFallbackLevel < 2) {
                        langFallbackLevel++
                        Log.w(TAG, "Language not supported — falling back to level $langFallbackLevel")
                    }
                    3000L
                }
                else                                     -> 300L
            }
            mainHandler.postDelayed({ listen() }, delayMs)
        }
    }

    companion object {
        private const val TAG = "AndroidSpeechEngine"
        // Introduced in API 31; defined here for backwards compatibility
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12

        private fun errorName(error: Int) = when (error) {
            SpeechRecognizer.ERROR_AUDIO                    -> "AUDIO"
            SpeechRecognizer.ERROR_CLIENT                   -> "CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK                  -> "NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT          -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH                 -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY          -> "BUSY"
            SpeechRecognizer.ERROR_SERVER                   -> "SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT           -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS        -> "TOO_MANY_REQUESTS"
            else                                            -> "UNKNOWN($error)"
        }
    }
}
