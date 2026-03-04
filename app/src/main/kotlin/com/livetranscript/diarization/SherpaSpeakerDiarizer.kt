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

    // Running-average embeddings and sample counts per speaker
    private val speakerEmbeddings = mutableListOf<FloatArray>()
    private val speakerSampleCounts = mutableListOf<Int>()
    override val speakerCount: Int get() = speakerEmbeddings.size

    // Fallback-Sprecher für zu kurze Segmente — Instanzvariable (kein statischer Zustand)
    private var lastSpeakerId = 0

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
        // Skip diarization for very short segments — embeddings are unreliable
        if (samples.size < MIN_SAMPLES_FOR_EMBEDDING) return lastSpeakerId

        return try {
            val stream = ext.createStream()
            stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
            try { stream.inputFinished() } catch (_: Exception) {}
            val embedding = ext.compute(stream)
            stream.release()

            // Find the BEST matching speaker (highest similarity above threshold)
            var bestId = -1
            var bestSim = SIMILARITY_THRESHOLD
            for (i in speakerEmbeddings.indices) {
                val similarity = cosineSimilarity(embedding, speakerEmbeddings[i])
                if (similarity >= bestSim) {
                    bestSim = similarity
                    bestId = i
                }
            }

            if (bestId >= 0) {
                // Update running average for matched speaker
                updateEmbedding(bestId, embedding)
                Log.d(TAG, "Speaker $bestId matched (similarity=$bestSim)")
                lastSpeakerId = bestId
                bestId
            } else {
                // Register new speaker
                val newId = speakerEmbeddings.size
                speakerEmbeddings.add(embedding.copyOf())
                speakerSampleCounts.add(1)
                Log.d(TAG, "New speaker: $newId (total: ${speakerEmbeddings.size})")
                lastSpeakerId = newId
                newId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Speaker identification failed", e)
            lastSpeakerId
        }
    }

    /** Incrementally updates the running-average embedding for [speakerId]. */
    private fun updateEmbedding(speakerId: Int, newEmbedding: FloatArray) {
        val count = speakerSampleCounts[speakerId]
        if (count >= MAX_EMBEDDING_SAMPLES) return // enough data, stop updating
        val avg = speakerEmbeddings[speakerId]
        val newCount = count + 1
        for (i in avg.indices) {
            avg[i] = (avg[i] * count + newEmbedding[i]) / newCount
        }
        speakerSampleCounts[speakerId] = newCount
    }

    override fun reset() {
        speakerEmbeddings.clear()
        speakerSampleCounts.clear()
        lastSpeakerId = 0
        Log.d(TAG, "Speaker diarizer reset")
    }

    override fun release() {
        extractor?.release()
        extractor = null
        isInitialized = false
        speakerEmbeddings.clear()
        speakerSampleCounts.clear()
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
        /** Minimum audio length for a reliable embedding (0.75 s — war 1.5 s) */
        private const val MIN_SAMPLES_FOR_EMBEDDING = SAMPLE_RATE * 3 / 4
        /** Höherer Schwellwert → sauberere Sprechertrennung (war 0.45) */
        private const val SIMILARITY_THRESHOLD = 0.55f
        /** Stop updating average after this many samples to keep it stable */
        private const val MAX_EMBEDDING_SAMPLES = 8
    }
}
