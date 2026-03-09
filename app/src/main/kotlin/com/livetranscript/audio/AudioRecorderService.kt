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
 * MIGRATION: SpeechRecognizer → AudioRecord + Whisper (sherpa-onnx)
 * Datum: 2026-03-09
 * Grund: SpeechRecognizer erkennt kein TV/Lautsprecher-Audio (Nahfeld-optimiert)
 * Lösung: AudioRecord(UNPROCESSED) + lokales Whisper Tiny Modell
 * Rollback: Suche nach "[LEGACY SpeechRecognizer]" um alten Code wiederherzustellen
 * Modell: Whisper Tiny multilingual (bereits in assets/models/whisper-tiny/)
 */

/**
 * Foreground service that owns the complete audio pipeline.
 *
 * ## ASR backend
 * Always uses [AsrBackend.WHISPER] (AudioRecord + sherpa-onnx Whisper Tiny).
 * This allows capturing TV/speaker audio that SpeechRecognizer misses (Nahfeld-only).
 *
 * ## Audio capture sources
 * The service supports two audio capture sources, selectable at recording start:
 *
 * **Microphone (default)**
 * ```
 * AudioRecord (UNPROCESSED, 16 kHz PCM float)   [Whisper mode]
 *     → RMS-based VAD → SherpaSpeakerDiarizer → SherpaOnnxAsrEngine → result
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

    // [WHISPER] Always WHISPER after migration; GOOGLE_SPEECH is legacy.
    private var activeBackend: AsrBackend = AsrBackend.WHISPER

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
     * Initialises ASR and diarizer engines.
     *
     * [AsrBackend.WHISPER] is always used after the SpeechRecognizer migration.
     * AudioRecord(UNPROCESSED) + sherpa-onnx Whisper Tiny captures TV/speaker audio
     * that the previous SpeechRecognizer (near-field only) could not recognise.
     *
     * The diarizer ([SherpaSpeakerDiarizer]) is always initialised — it is used in both
     * Whisper mode and system audio capture mode.
     * Language code is read synchronously from [SettingsRepository].
     */
    private fun initializeEngines() {
        val repo     = SettingsRepository(this)
        val language = repo.getLanguageSync()

        // [WHISPER] Always use Whisper — AudioRecord(UNPROCESSED) captures TV/speaker audio
        // that SpeechRecognizer misses (SpeechRecognizer is optimised for near-field mic only).
        activeBackend = AsrBackend.WHISPER
        // [LEGACY SpeechRecognizer] activeBackend = if (SpeechRecognizer.isRecognitionAvailable(this))
        // [LEGACY SpeechRecognizer]     AsrBackend.GOOGLE_SPEECH
        // [LEGACY SpeechRecognizer] else
        // [LEGACY SpeechRecognizer]     AsrBackend.WHISPER

        try {
            diarizer = SherpaSpeakerDiarizer(this).also { it.initialize() }
        } catch (e: Exception) {
            Log.e(TAG, "Diarizer init failed", e)
        }

        // [WHISPER] Initialize Whisper Tiny ONNX engine (always active after migration)
        try {
            asrEngine = SherpaOnnxAsrEngine(this, language).also { it.initialize() }
            Log.d(TAG, "Whisper initialized (lang=$language)")
        } catch (e: Exception) {
            Log.e(TAG, "Whisper init failed", e)
        }

        // [LEGACY SpeechRecognizer] when (activeBackend) {
        // [LEGACY SpeechRecognizer]     AsrBackend.WHISPER -> {
        // [LEGACY SpeechRecognizer]         try {
        // [LEGACY SpeechRecognizer]             asrEngine = SherpaOnnxAsrEngine(this, language).also { it.initialize() }
        // [LEGACY SpeechRecognizer]             Log.d(TAG, "Whisper initialized (lang=$language)")
        // [LEGACY SpeechRecognizer]         } catch (e: Exception) {
        // [LEGACY SpeechRecognizer]             Log.e(TAG, "Whisper init failed", e)
        // [LEGACY SpeechRecognizer]         }
        // [LEGACY SpeechRecognizer]     }
        // [LEGACY SpeechRecognizer]     AsrBackend.GOOGLE_SPEECH -> {
        // [LEGACY SpeechRecognizer]         speechEngine = AndroidSpeechEngine(
        // [LEGACY SpeechRecognizer]             context         = this,
        // [LEGACY SpeechRecognizer]             language        = language,
        // [LEGACY SpeechRecognizer]             onResult        = { text -> onTranscriptionResult?.invoke(currentSpeakerId, text) },
        // [LEGACY SpeechRecognizer]             onPartialResult = { text -> onPartialResult?.invoke(currentSpeakerId, text) },
        // [LEGACY SpeechRecognizer]         )
        // [LEGACY SpeechRecognizer]         try {
        // [LEGACY SpeechRecognizer]             asrEngine = SherpaOnnxAsrEngine(this, language).also { it.initialize() }
        // [LEGACY SpeechRecognizer]             Log.d(TAG, "Whisper also initialized for system audio capture (lang=$language)")
        // [LEGACY SpeechRecognizer]         } catch (e: Exception) {
        // [LEGACY SpeechRecognizer]             Log.w(TAG, "Whisper init skipped (models not present) — system audio capture unavailable")
        // [LEGACY SpeechRecognizer]         }
        // [LEGACY SpeechRecognizer]         Log.d(TAG, "Google Speech Engine ready (lang=$language)")
        // [LEGACY SpeechRecognizer]     }
        // [LEGACY SpeechRecognizer] }
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
     * **[WHISPER] Microphone mode** (mic, [AsrBackend.WHISPER]):
     * Opens [AudioRecord] with [MediaRecorder.AudioSource.UNPROCESSED] — raw mic audio
     * with no echo cancellation or noise suppression, matching Live Transcribe's approach.
     * No NoiseSuppressor or AutomaticGainControl is attached.
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
     * Starts microphone capture using AudioRecord + Whisper.
     *
     * [WHISPER] Uses [MediaRecorder.AudioSource.UNPROCESSED] (API 24+) — raw audio without
     * echo cancellation, noise suppression, or AGC. This is the key difference vs.
     * SpeechRecognizer: UNPROCESSED does not treat loudspeaker output as "noise", so
     * TV audio and room speakers are picked up correctly.
     *
     * Falls back to [MediaRecorder.AudioSource.MIC] on devices that do not support UNPROCESSED.
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
            // [LEGACY SpeechRecognizer] AsrBackend.GOOGLE_SPEECH -> {
            // [LEGACY SpeechRecognizer]     // SpeechRecognizer manages its own mic; no separate AudioRecord.
            // [LEGACY SpeechRecognizer]     // All results assigned to speaker 0 (no diarization in SR mode).
            // [LEGACY SpeechRecognizer]     currentSpeakerId = 0
            // [LEGACY SpeechRecognizer]     speechEngine?.start()
            // [LEGACY SpeechRecognizer]     Log.d(TAG, "Recording started — Google Speech mode")
            // [LEGACY SpeechRecognizer] }
            else -> {
                // No-op: should never be reached after migration (activeBackend is always WHISPER)
                Log.w(TAG, "startMicCapture: unexpected backend=$activeBackend, ignoring")
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
     * - Raise thresholds (voiceThreshold / silenceThreshold) to reduce false triggers from background noise.
     * - Lower thresholds to detect quieter sources (device speakers, far-field audio).
     * - Raise [MIN_VOICE_FRAMES] to require more confirmed speech before sending to Whisper.
     */
    private suspend fun processAudioLoop() {
        val buffer      = FloatArray(CHUNK_SIZE_SAMPLES)
        val accumulator = mutableListOf<Float>()

        // Thresholds calibrated from live RMS measurements on this device:
        // background noise ≈ 0.0001–0.0003, speech ≈ 0.0004–0.0008.
        val voiceThreshold   = 0.0004f
        val silenceThreshold = 0.00015f
        var silenceFrames    = 0
        var voiceFrames      = 0
        // 15 × 100 ms = 1.5 s of silence ends a segment.
        // Longer pause tolerance lets full sentences complete before Whisper inference —
        // mid-sentence cuts were the main cause of wrong words.
        val maxSilenceFrames = 15

        var debugFrameCount = 0
        while (currentCoroutineContext().isActive && isRecording) {
            val read = audioRecord?.read(buffer, 0, CHUNK_SIZE_SAMPLES, AudioRecord.READ_BLOCKING) ?: break
            if (read <= 0) { Log.w(TAG, "audioRecord.read returned $read"); continue }

            val chunk = buffer.take(read)
            accumulator.addAll(chunk)

            val rms = chunk.map { it * it }.average().let { kotlin.math.sqrt(it).toFloat() }
            // Log every 10 frames (= every 1 second) so we can see live audio levels
            if (++debugFrameCount % 10 == 0) {
                Log.d(TAG, "VAD rms=%.5f voiceFrames=$voiceFrames silenceFrames=$silenceFrames accum=${accumulator.size}".format(rms))
            }
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
        // 6 s max segment — Whisper was trained on 30 s chunks; longer segments give it
        // more acoustic context and significantly reduce word-error rate.
        private const val MAX_ACCUMULATOR_SAMPLES       = SAMPLE_RATE * 6
        private const val MIN_SAMPLES_FOR_TRANSCRIPTION = SAMPLE_RATE * 3 / 4 // 0.75 s minimum
        private const val MIN_VOICE_FRAMES              = 1          // 1 × 100 ms = 100 ms real speech required (Whisper filters noise itself)
    }
}
