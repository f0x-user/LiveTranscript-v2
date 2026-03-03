package com.livetranscript.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.livetranscript.models.ModelAssetManager

class SherpaOnnxAsrEngine(private val context: Context, private val language: String = "de") : AsrEngine {

    private var recognizer: OfflineRecognizer? = null
    override var isInitialized: Boolean = false
        private set

    override fun initialize() {
        if (isInitialized) return
        try {
            val encoderPath = ModelAssetManager.getModelPath(
                context, "whisper-tiny/tiny-encoder.int8.onnx"
            )
            val decoderPath = ModelAssetManager.getModelPath(
                context, "whisper-tiny/tiny-decoder.int8.onnx"
            )
            val tokensPath = ModelAssetManager.getModelPath(
                context, "whisper-tiny/tiny-tokens.txt"
            )

            val whisperConfig = OfflineWhisperModelConfig(
                encoder = encoderPath,
                decoder = decoderPath,
                language = language,
                task = "transcribe"
            )

            val modelConfig = OfflineModelConfig(
                whisper = whisperConfig,
                tokens = tokensPath,
                numThreads = 2,
                debug = false
            )

            val featureConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80
            )

            val config = OfflineRecognizerConfig(
                featConfig = featureConfig,
                modelConfig = modelConfig
            )

            recognizer = OfflineRecognizer(config = config)
            isInitialized = true
            Log.d(TAG, "ASR engine initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ASR engine", e)
            isInitialized = false
        }
    }

    override fun transcribe(samples: FloatArray): String? {
        val rec = recognizer ?: return null
        return try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
            // OfflineStream has no inputFinished() — only OnlineStream does
            rec.decode(stream)
            val raw = rec.getResult(stream).text.trim()
            stream.release()
            val cleaned = cleanTranscription(raw)
            cleaned.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            null
        }
    }

    /**
     * Removes Whisper noise tokens like [BLANK_AUDIO], (silence), etc.
     * and returns only actual speech content.
     */
    private fun cleanTranscription(text: String): String {
        if (text.isBlank()) return ""
        // Remove bracketed/parenthesised Whisper tokens: [BLANK_AUDIO], (silence), etc.
        val cleaned = text
            .replace(NOISE_TOKEN_REGEX, "")
            .trim()
        // Reject if nothing meaningful remains or only punctuation
        if (cleaned.length < 2) return ""
        if (cleaned.all { !it.isLetterOrDigit() }) return ""
        return cleaned
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
        isInitialized = false
        Log.d(TAG, "ASR engine released")
    }

    companion object {
        private const val TAG = "SherpaOnnxAsrEngine"
        const val SAMPLE_RATE = 16000
        /** Matches Whisper noise tokens like [BLANK_AUDIO], [ BLANK_AUDIO], (silence), <|nospeech|> etc. */
        private val NOISE_TOKEN_REGEX = Regex(
            """\[[\s]*BLANK[\s_]*AUDIO[\s]*]|\([\s]*silence[\s]*\)|<\|[^|]*\>|\[[\s]*SILENCE[\s]*]""",
            RegexOption.IGNORE_CASE,
        )
    }
}
