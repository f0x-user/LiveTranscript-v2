package com.livetranscript.asr

/**
 * Interface for ASR (Automatic Speech Recognition) engines.
 * Implementations should be thread-safe.
 */
interface AsrEngine {

    /**
     * Initialize the ASR engine. Must be called before transcribe().
     */
    fun initialize()

    /**
     * Transcribe a chunk of audio samples.
     * @param samples PCM audio samples, 16kHz, mono, float32 [-1.0, 1.0]
     * @return Transcribed text, or null if no speech detected
     */
    fun transcribe(samples: FloatArray): String?

    /**
     * Release all resources. Engine cannot be used after this.
     */
    fun release()

    val isInitialized: Boolean
}
