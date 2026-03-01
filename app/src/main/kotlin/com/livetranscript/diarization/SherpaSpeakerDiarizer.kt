package com.livetranscript.diarization

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.livetranscript.models.ModelAssetManager

class SherpaSpeakerDiarizer(private val context: Context) : SpeakerDiarizer {

    private var extractor: SpeakerEmbeddingExtractor? = null
    override var isInitialized: Boolean = false
        private set

    // Store embeddings for each known speaker
    private val speakerEmbeddings = mutableListOf<FloatArray>()
    override val speakerCount: Int get() = speakerEmbeddings.size

    // Cosine similarity threshold to decide if it's the same speaker
    private val similarityThreshold = 0.6f

    override fun initialize() {
        if (isInitialized) return
        try {
            val modelPath = ModelAssetManager.getModelPath(
                context, "wespeaker/model.onnx"
            )
            val config = SpeakerEmbeddingExtractorConfig(
                model = modelPath,
                numThreads = 1,
                debug = false
            )
            extractor = SpeakerEmbeddingExtractor(config = config)
            isInitialized = true
            Log.d(TAG, "Speaker diarizer initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speaker diarizer", e)
            isInitialized = false
        }
    }

    override fun identifySpeaker(samples: FloatArray): Int {
        val ext = extractor ?: return -1
        return try {
            val stream = ext.createStream()
            stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
            // Signal end of input before extracting embedding
            try { stream.inputFinished() } catch (_: Exception) {}
            val embedding = ext.compute(stream)
            stream.release()

            // Compare against known speakers
            for (i in speakerEmbeddings.indices) {
                val similarity = cosineSimilarity(embedding, speakerEmbeddings[i])
                if (similarity >= similarityThreshold) {
                    Log.d(TAG, "Speaker $i matched (similarity=$similarity)")
                    return i
                }
            }

            // New speaker
            val newId = speakerEmbeddings.size
            speakerEmbeddings.add(embedding)
            Log.d(TAG, "New speaker detected: Speaker $newId (total: ${speakerEmbeddings.size})")
            newId
        } catch (e: Exception) {
            Log.e(TAG, "Speaker identification failed", e)
            -1
        }
    }

    override fun reset() {
        speakerEmbeddings.clear()
        Log.d(TAG, "Speaker diarizer reset")
    }

    override fun release() {
        extractor?.release()
        extractor = null
        isInitialized = false
        speakerEmbeddings.clear()
        Log.d(TAG, "Speaker diarizer released")
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }

    companion object {
        private const val TAG = "SherpaSpeakerDiarizer"
        const val SAMPLE_RATE = 16000
    }
}
