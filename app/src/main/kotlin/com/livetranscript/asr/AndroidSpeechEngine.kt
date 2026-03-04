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
 * - Bevorzugt Offline-Erkennung (EXTRA_PREFER_OFFLINE = true).
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

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        active = true
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

    private fun listen() {
        if (!active) return
        val locale = if (language.isEmpty()) Locale.getDefault()
                     else Locale.forLanguageTag(language)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                     RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }
        recognizer?.startListening(intent)
        Log.d(TAG, "Listening started (lang=$language)")
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
            Log.d(TAG, "Error: ${errorName(error)}")
            if (!active) return
            val delayMs = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 600L
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_SERVER            -> 1200L
                else                                     -> 0L   // NO_MATCH, SPEECH_TIMEOUT usw.
            }
            if (delayMs > 0) mainHandler.postDelayed({ listen() }, delayMs)
            else listen()
        }
    }

    companion object {
        private const val TAG = "AndroidSpeechEngine"

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
