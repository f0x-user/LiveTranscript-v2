package com.livetranscript.diarization

/**
 * Interface for speaker diarization.
 * Identifies which speaker is speaking in an audio segment.
 */
interface SpeakerDiarizer {

    /**
     * Initialize the diarizer. Must be called before identifySpeaker().
     */
    fun initialize()

    /**
     * Identify the speaker for the given audio samples.
     * @param samples PCM audio samples, 16kHz, mono, float32 [-1.0, 1.0]
     * @return Speaker ID (0-based index), or -1 if identification failed
     */
    fun identifySpeaker(samples: FloatArray): Int

    /**
     * Reset speaker tracking (e.g. start of a new conversation).
     */
    fun reset()

    /**
     * Release all resources.
     */
    fun release()

    val isInitialized: Boolean

    /** Number of unique speakers detected so far */
    val speakerCount: Int
}
