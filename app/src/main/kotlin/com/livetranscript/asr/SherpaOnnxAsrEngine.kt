package com.livetranscript.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.livetranscript.models.ModelAssetManager

class SherpaOnnxAsrEngine(private val context: Context) : AsrEngine {

    private var recognizer: OfflineRecognizer? = null
    override var isInitialized: Boolean = false
        private set

    override fun initialize() {
        if (isInitialized) return
        try {
            val encoderPath = ModelAssetManager.getModelPath(
                context, "whisper-tiny/tiny.en-encoder.int8.onnx"
            )
            val decoderPath = ModelAssetManager.getModelPath(
                context, "whisper-tiny/tiny.en-decoder.int8.onnx"
            )
            val tokensPath = ModelAssetManager.getModelPath(
                context, "whisper-tiny/tiny.en-tokens.txt"
            )

            val whisperConfig = OfflineWhisperModelConfig(
                encoder = encoderPath,
                decoder = decoderPath,
                language = "en",
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
            // Signal end of input (required by some sherpa-onnx versions)
            try { stream.inputFinished() } catch (_: Exception) {}
            rec.decode(stream)
            val result = rec.getResult(stream).text.trim()
            stream.release()
            result.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            null
        }
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
    }
}
