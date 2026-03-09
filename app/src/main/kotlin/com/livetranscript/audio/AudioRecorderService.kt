package com.livetranscript.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.IBinder
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.livetranscript.MainActivity
import com.livetranscript.R
import com.livetranscript.asr.AndroidSpeechEngine
import com.livetranscript.asr.SherpaOnnxAsrEngine
import com.livetranscript.diarization.SherpaSpeakerDiarizer
import com.livetranscript.models.ModelAssetManager
import com.livetranscript.settings.AsrBackend
import com.livetranscript.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that owns the complete audio pipeline.
 *
 * ## ASR backends (auto-selected at startup)
 * - [AsrBackend.GOOGLE_SPEECH] — Android SpeechRecognizer; selected when Google recognition
 *   service is installed. Low latency, continuous, partial results.
 * - [AsrBackend.WHISPER] — On-device ONNX Whisper Tiny; fallback when Google SR is unavailable.
 *   Fully offline.
 *
 * ## Audio capture sources
 * The service supports two audio capture sources, selectable at recording start:
 *
 * **Microphone (default)**
 * ```
 * AudioRecord (UNPROCESSED, 16 kHz PCM float)   [Whisper mode]
 *     → RMS-based VAD → SherpaSpeakerDiarizer → SherpaOnnxAsrEngine → result
 *
 * AndroidSpeechEngine (SpeechRecognizer)          [Google Speech mode]
 *     → onResult / onPartialResult → result
 * ```
 *
 * **System audio / Media capture (Android 10+, requires MediaProjection)**
 * ```
 * AudioPlaybackCaptureConfiguration (captures all media/game/unknown audio streams)
 *     → AudioRecord (16 kHz PCM float, no mic involved)
 *     → RMS-based VAD → SherpaSpeakerDiarizer → SherpaOnnxAsrEngine → result
 * ```
 * Note: system audio capture always uses Whisper because [SpeechRecognizer] does not
 * accept externally supplied audio. The [MediaProjection] token is passed from [MainActivity]
 * via [setMediaProjection] before calling [startRecording].
 *
 * ## Why no NoiseSuppressor / AutomaticGainControl?
 * Analysis of Google Live Transcribe (com.google.audio.hearing.visualization.accessibility.scribe)
 * via DEX disassembly shows it uses a raw [AudioRecord] with VOICE_RECOGNITION source and
 * no Android AudioEffects. NoiseSuppressor treats loudspeaker output as "noise" and
 * suppresses it — this is why our previous implementation failed to pick up video audio.
 * Removing NS/AGC and using UNPROCESSED source mirrors Live Transcribe's approach.
 *
 * ## Lifecycle
 * - Started via `startForegroundService()` in [MainActivity].
 * - Bound by [MainActivity] to receive callbacks.
 * - [startRecording] / [stopRecording] must be called explicitly.
 * - All resources are released in [onDestroy].
 *
 * ## Callbacks (set by [MainActivity] after binding)
 * - [onTranscriptionResult] — final text + speaker ID → append to transcript list
 * - [onPartialResult]       — live partial text during speech → update status label
 */
class AudioRecorderService : Service() {

    inner class RecorderBinder : Binder() {
        fun getService(): AudioRecorderService = this@AudioRecorderService
    }

    private val binder       = RecorderBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── ASR engines ───────────────────────────────────────────────────────────
    private var asrEngine   : SherpaOnnxAsrEngine? = null
    private val asrMutex     = Mutex()
    private var speechEngine: AndroidSpeechEngine? = null
    private var diarizer    : SherpaSpeakerDiarizer? = null

    // ── Recording state ───────────────────────────────────────────────────────
    private var audioRecord  : AudioRecord? = null
    private var recordingJob : Job? = null

    /** Current speaker ID, updated by the diarizer, read by result callbacks. */
    @Volatile private var currentSpeakerId = 0

    /** Active ASR backend, determined automatically in [initializeEngines]. */
    private var activeBackend: AsrBackend = AsrBackend.GOOGLE_SPEECH

    /**
     * Optional [MediaProjection] for system audio capture (Android 10+).
     * Set via [setMediaProjection] before calling [startRecording].
     * When non-null, [startRecording] routes audio through [AudioPlaybackCaptureConfiguration]
     * instead of the microphone, and Whisper is used regardless of [activeBackend].
     */
    private var mediaProjection: MediaProjection? = null

    var isRecording = false
        private set

    // ── Public callbacks ──────────────────────────────────────────────────────

    /** Invoked on every final transcription result with the speaker ID and transcript text. */
    var onTranscriptionResult: ((speakerId: Int, text: String) -> Unit)? = null

    /**
     * Invoked with live partial recognition text while the user is still speaking.
     * Only fires in Google Speech mode.
     */
    var onPartialResult: ((speakerId: Int, text: String) -> Unit)? = null

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        // startForeground() must be called within 5 s of startForegroundService().
        // On Android 10+ (API 29+) we must pass the exact foreground service type being used.
        // We start with MICROPHONE only — mediaProjection type is added dynamically in
        // startSystemAudioCapture() when the user provides a MediaProjection token.
        // NEVER declare mediaProjection type here without an active MediaProjection token,
        // as Android 14+ (API 34+) enforces this at the OS level and will crash the service.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        val modelsReady = ModelAssetManager.prepareModels(this)
        if (!modelsReady) {
            Log.e(TAG, "Models not ready — stopping service")
            stopSelf()
            return
        }
        initializeEngines()
        Log.d(TAG, "AudioRecorderService created (backend=$activeBackend)")
    }

    /**
     * Auto-selects the ASR backend and initialises engines.
     *
     * Selection logic:
     * - [AsrBackend.GOOGLE_SPEECH] when [SpeechRecognizer.isRecognitionAvailable] returns true.
     * - [AsrBackend.WHISPER] otherwise (no Google recognition service installed).
     *
     * The diarizer ([SherpaSpeakerDiarizer]) is always initialised — it is used in both
     * Whisper mode and system audio capture mode.
     * Language code is read synchronously from [SettingsRepository].
     */
    private fun initializeEngines() {
        val repo     = SettingsRepository(this)
        val language = repo.getLanguageSync()

        activeBackend = if (SpeechRecognizer.isRecognitionAvailable(this))
            AsrBackend.GOOGLE_SPEECH
        else
            AsrBackend.WHISPER

        try {
            diarizer = SherpaSpeakerDiarizer(this).also { it.initialize() }
        } catch (e: Exception) {
            Log.e(TAG, "Diarizer init failed", e)
        }

        when (activeBackend) {
            AsrBackend.WHISPER -> {
                try {
                    asrEngine = SherpaOnnxAsrEngine(this, language).also { it.initialize() }
                    Log.d(TAG, "Whisper initialized (lang=$language)")
                } catch (e: Exception) {
                    Log.e(TAG, "Whisper init failed", e)
                }
            }
            AsrBackend.GOOGLE_SPEECH -> {
                speechEngine = AndroidSpeechEngine(
                    context         = this,
                    language        = language,
                    onResult        = { text -> onTranscriptionResult?.invoke(currentSpeakerId, text) },
                    onPartialResult = { text -> onPartialResult?.invoke(currentSpeakerId, text) },
                )
                // Whisper must also be ready as fallback for system audio capture mode
                try {
                    asrEngine = SherpaOnnxAsrEngine(this, language).also { it.initialize() }
                    Log.d(TAG, "Whisper also initialized for system audio capture (lang=$language)")
                } catch (e: Exception) {
                    Log.w(TAG, "Whisper init skipped (models not present) — system audio capture unavailable")
                }
                Log.d(TAG, "Google Speech Engine ready (lang=$language)")
            }
        }
    }

    /**
     * Provides a [MediaProjection] token for system audio capture.
     *
     * Call this from [MainActivity] after the user grants screen capture permission,
     * before calling [startRecording]. When a projection is set, [startRecording] will
     * use [AudioPlaybackCaptureConfiguration] to capture media/game audio instead of
     * the microphone, and Whisper is used for transcription.
     *
     * Pass null to revert to microphone capture.
     */
    fun setMediaProjection(projection: MediaProjection?) {
        mediaProjection = projection
        Log.d(TAG, "MediaProjection set: ${if (projection != null) "system audio mode" else "microphone mode"}")
    }

    /**
     * Reloads the Whisper engine with a new language code.
     * No-op if recording is active or if system audio capture (Whisper) is not in use.
     */
    fun reloadLanguage(language: String) {
        if (isRecording) return
        serviceScope.launch {
            asrMutex.withLock {
                asrEngine?.release()
                asrEngine = try {
                    SherpaOnnxAsrEngine(this@AudioRecorderService, language).also { it.initialize() }
                } catch (e: Exception) {
                    Log.e(TAG, "Whisper reload failed", e); null
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP  -> stopRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Starts the audio capture pipeline.
     *
     * **System audio mode** (when [mediaProjection] is set, Android 10+):
     * Uses [AudioPlaybackCaptureConfiguration] to capture media/game audio streams
     * from the device output (e.g. a playing video) without using the microphone.
     * Whisper is used for transcription.
     *
     * **Whisper mode** (mic, [AsrBackend.WHISPER]):
     * Opens [AudioRecord] with [MediaRecorder.AudioSource.UNPROCESSED] — raw mic audio
     * with no echo cancellation or noise suppression, matching Live Transcribe's approach.
     * No NoiseSuppressor or AutomaticGainControl is attached.
     *
     * **Google Speech mode** (mic, [AsrBackend.GOOGLE_SPEECH]):
     * Delegates to [AndroidSpeechEngine]; the SpeechRecognizer manages the mic internally.
     * No separate [AudioRecord] is created.
     *
     * No-op if already recording.
     */
    fun startRecording() {
        if (isRecording) return
        isRecording = true

        val projection = mediaProjection
        if (projection != null) {
            startSystemAudioCapture(projection)
        } else {
            startMicCapture()
        }
    }

    /**
     * Starts system audio capture using [AudioPlaybackCaptureConfiguration].
     * Captures [AudioAttributes.USAGE_MEDIA], [AudioAttributes.USAGE_GAME], and
     * [AudioAttributes.USAGE_UNKNOWN] streams — covers videos, music, games, etc.
     * Transcription uses Whisper because [SpeechRecognizer] cannot accept injected audio.
     *
     * Requires Android 10 (API 29) and a valid [MediaProjection] token.
     */
    private fun startSystemAudioCapture(projection: MediaProjection) {
        // On Android 14+, the foreground service type must include MEDIA_PROJECTION when
        // an AudioPlaybackCaptureConfiguration is active. Upgrade the type here — this is
        // safe because we already hold a valid MediaProjection token at this point.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(CHUNK_SIZE_BYTES)

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord (system audio) init failed")
            audioRecord?.release(); audioRecord = null
            isRecording = false; return
        }
        audioRecord?.startRecording()
        recordingJob = serviceScope.launch { processAudioLoop() }
        Log.d(TAG, "Recording started — system audio capture mode (VideoAudio→Whisper)")
    }

    /**
     * Starts microphone capture.
     *
     * Uses [MediaRecorder.AudioSource.UNPROCESSED] (API 24+) which provides raw audio
     * without echo cancellation, noise suppression, or automatic gain control.
     * This mirrors how Google Live Transcribe captures audio and allows picking up
     * speech from device speakers (e.g. videos) as well as ambient speech.
     *
     * Falls back to [MediaRecorder.AudioSource.MIC] on devices that do not support
     * UNPROCESSED (very rare).
     */
    private fun startMicCapture() {
        when (activeBackend) {
            AsrBackend.WHISPER -> {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                ).coerceAtLeast(CHUNK_SIZE_BYTES)

                // UNPROCESSED: raw mic, no echo canceller / NoiseSuppressor / AGC.
                // Live Transcribe analysis shows it uses VOICE_RECOGNITION (6) without effects;
                // UNPROCESSED (9) is even more raw and better for capturing speaker audio.
                val audioSource = MediaRecorder.AudioSource.UNPROCESSED
                audioRecord = AudioRecord(
                    audioSource,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    bufferSize,
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    // UNPROCESSED not supported — fall back to MIC
                    Log.w(TAG, "UNPROCESSED not supported, falling back to MIC source")
                    audioRecord?.release()
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT,
                        bufferSize,
                    )
                }

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord init failed (both UNPROCESSED and MIC)")
                    audioRecord?.release(); audioRecord = null
                    isRecording = false; return
                }

                // DO NOT attach NoiseSuppressor or AutomaticGainControl.
                // These AudioEffect APIs suppress loudspeaker output as "noise/echo",
                // preventing transcription of video audio or distant speakers.

                audioRecord?.startRecording()
                recordingJob = serviceScope.launch { processAudioLoop() }
                Log.d(TAG, "Recording started — Whisper mode (mic, UNPROCESSED, no effects)")
            }
            AsrBackend.GOOGLE_SPEECH -> {
                // SpeechRecognizer manages its own mic; no separate AudioRecord.
                // All results assigned to speaker 0 (no diarization in SR mode).
                currentSpeakerId = 0
                speechEngine?.start()
                Log.d(TAG, "Recording started — Google Speech mode")
            }
        }
    }

    /**
     * Stops the recording pipeline and releases all mic/audio resources.
     * No-op if not currently recording.
     */
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        speechEngine?.stop()

        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // If we upgraded to mediaProjection type, revert to microphone-only type.
        // This keeps the foreground service valid for the next microphone recording.
        if (mediaProjection != null) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }

        Log.d(TAG, "Recording stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Audio processing loop (Whisper mode and system audio capture mode)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads audio from [audioRecord] in 100 ms chunks and dispatches voice segments
     * for diarization and Whisper transcription.
     *
     * **VAD (Voice Activity Detection):**
     * - Frames above [voiceThreshold] RMS are counted as speech.
     * - Frames below [silenceThreshold] RMS increment a silence counter.
     * - A segment is dispatched when [maxSilenceFrames] consecutive silent frames are seen,
     *   or when the accumulator reaches [MAX_ACCUMULATOR_SAMPLES] (2 seconds).
     * - Segments are only sent to Whisper if they contain ≥ [MIN_VOICE_FRAMES] speech frames.
     *
     * **Tuning:**
     * - Raise thresholds (0.005 / 0.002) to reduce false triggers from background noise.
     * - Lower thresholds to detect quieter sources (device speakers, far-field audio).
     */
    private suspend fun processAudioLoop() {
        val buffer      = FloatArray(CHUNK_SIZE_SAMPLES)
        val accumulator = mutableListOf<Float>()

        val voiceThreshold   = 0.0005f
        val silenceThreshold = 0.0002f
        var silenceFrames    = 0
        var voiceFrames      = 0
        val maxSilenceFrames = 8   // 8 × 100 ms = 800 ms of silence ends a segment

        while (currentCoroutineContext().isActive && isRecording) {
            val read = audioRecord?.read(buffer, 0, CHUNK_SIZE_SAMPLES, AudioRecord.READ_BLOCKING) ?: break
            if (read <= 0) continue

            val chunk = buffer.take(read)
            accumulator.addAll(chunk)

            val rms = chunk.map { it * it }.average().let { kotlin.math.sqrt(it).toFloat() }
            when {
                rms >= voiceThreshold  -> { voiceFrames++; silenceFrames = 0 }
                rms < silenceThreshold -> silenceFrames++
                else                   -> silenceFrames = 0
            }

            val enoughSilence = silenceFrames >= maxSilenceFrames
            val bufferFull    = accumulator.size >= MAX_ACCUMULATOR_SAMPLES

            if ((enoughSilence || bufferFull) && accumulator.size >= MIN_SAMPLES_FOR_TRANSCRIPTION) {
                if (voiceFrames >= MIN_VOICE_FRAMES) {
                    val samples = accumulator.toFloatArray()
                    accumulator.clear(); voiceFrames = 0; silenceFrames = 0
                    serviceScope.launch {
                        asrMutex.withLock { processWhisperSegment(samples) }
                    }
                } else {
                    accumulator.clear(); voiceFrames = 0; silenceFrames = 0
                }
            }
        }

        // Flush remaining audio at loop end
        if (accumulator.size >= MIN_SAMPLES_FOR_TRANSCRIPTION && voiceFrames >= MIN_VOICE_FRAMES) {
            val samples = accumulator.toFloatArray()
            serviceScope.launch { asrMutex.withLock { processWhisperSegment(samples) } }
        }
    }

    /**
     * Runs speaker diarization and Whisper transcription on a single audio segment.
     * Called inside [asrMutex] to prevent concurrent Whisper inference.
     */
    private fun processWhisperSegment(samples: FloatArray) {
        val speakerId = diarizer?.identifySpeaker(samples) ?: 0
        currentSpeakerId = speakerId
        val text = asrEngine?.transcribe(samples)
        if (!text.isNullOrBlank()) {
            Log.d(TAG, "Whisper Speaker $speakerId: $text")
            onTranscriptionResult?.invoke(speakerId, text)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onDestroy() {
        stopRecording()
        asrEngine?.release()
        diarizer?.release()
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
        Log.d(TAG, "AudioRecorderService destroyed")
    }

    /**
     * Builds the persistent foreground notification shown while the service is running.
     * Creates the notification channel on first call (required on Android 8+).
     * Tapping the notification opens [MainActivity].
     */
    private fun buildNotification(): Notification {
        val channelId = "livetranscript_channel"
        val manager   = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "LiveTranscript Recording",
                    NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Live transcription in progress" }
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("LiveTranscript")
            .setContentText("Recording and transcribing...")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG             = "AudioRecorderService"
        const val ACTION_START            = "com.livetranscript.START_RECORDING"
        const val ACTION_STOP             = "com.livetranscript.STOP_RECORDING"
        private const val NOTIFICATION_ID = 1001
        const val SAMPLE_RATE             = 16000
        private const val CHUNK_SIZE_SAMPLES            = 1600      // 100 ms per chunk
        private const val CHUNK_SIZE_BYTES              = CHUNK_SIZE_SAMPLES * 4
        private const val MAX_ACCUMULATOR_SAMPLES       = SAMPLE_RATE * 2   // 2 s max segment
        private const val MIN_SAMPLES_FOR_TRANSCRIPTION = SAMPLE_RATE / 2   // 0.5 s minimum
        private const val MIN_VOICE_FRAMES              = 1          // 1 × 100 ms speech required
    }
}
