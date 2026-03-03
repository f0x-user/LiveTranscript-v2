package com.livetranscript.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.livetranscript.MainActivity
import com.livetranscript.R
import com.livetranscript.asr.SherpaOnnxAsrEngine
import com.livetranscript.diarization.SherpaSpeakerDiarizer
import com.livetranscript.models.ModelAssetManager
import com.livetranscript.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AudioRecorderService : Service() {

    inner class RecorderBinder : Binder() {
        fun getService(): AudioRecorderService = this@AudioRecorderService
    }

    private val binder = RecorderBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var asrEngine: SherpaOnnxAsrEngine? = null
    private var diarizer: SherpaSpeakerDiarizer? = null
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agcController: AutomaticGainControl? = null
    private var recordingJob: Job? = null

    // Serializes ASR calls so only one segment is transcribed at a time.
    private val asrMutex = Mutex()

    var isRecording = false
        private set

    // Callback to send transcription results to the UI
    var onTranscriptionResult: ((speakerId: Int, text: String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()

        // SCHRITT 1: Modelle kopieren — SYNCHRON, BLOCKIEREND
        val modelsReady = ModelAssetManager.prepareModels(this)
        if (!modelsReady) {
            Log.e(TAG, "Models not ready — stopping")
            stopSelf()
            return
        }

        // SCHRITT 2: sherpa-onnx initialisieren
        initializeSherpaOnnx()

        // SCHRITT 3: Foreground Service starten
        startForeground(NOTIFICATION_ID, buildNotification())

        Log.d(TAG, "AudioRecorderService created")
    }

    private fun initializeSherpaOnnx() {
        try {
            val language = SettingsRepository(this).getLanguageSync()
            asrEngine = SherpaOnnxAsrEngine(this, language).also { it.initialize() }
            diarizer = SherpaSpeakerDiarizer(this).also { it.initialize() }
            Log.d(TAG, "SherpaOnnx initialized (lang=$language): ASR=${asrEngine?.isInitialized}, Diarizer=${diarizer?.isInitialized}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SherpaOnnx", e)
        }
    }

    /** Reinitialize ASR with a new language. Safe to call while not recording. */
    fun reloadLanguage(language: String) {
        if (isRecording) return
        serviceScope.launch {
            asrMutex.withLock {
                asrEngine?.release()
                asrEngine = SherpaOnnxAsrEngine(this@AudioRecorderService, language).also { it.initialize() }
                Log.d(TAG, "ASR language reloaded: $language")
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

    fun startRecording() {
        if (isRecording) return

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(CHUNK_SIZE_BYTES)

        audioRecord = AudioRecord(
            // VOICE_RECOGNITION applies device-side speech pre-processing
            // (echo cancellation, beam-forming on supported devices)
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            return
        }

        val sessionId = audioRecord!!.audioSessionId

        // Hardware noise suppressor — reduces wind, HVAC, keyboard noise
        noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
        } else null

        // Automatic gain control — boosts quiet voices (table-distance recording)
        agcController = if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)?.also { it.enabled = true }
        } else null

        audioRecord?.startRecording()
        isRecording = true
        Log.d(TAG, "Recording started — NoiseSuppressor=${noiseSuppressor != null}, AGC=${agcController != null}")

        recordingJob = serviceScope.launch {
            processAudioLoop()
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        agcController?.release()
        agcController = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "Recording stopped")
    }

    private suspend fun processAudioLoop() {
        val buffer = FloatArray(CHUNK_SIZE_SAMPLES)
        val accumulator = mutableListOf<Float>()

        // Thresholds calibrated for VOICE_RECOGNITION source + AGC:
        //   Suppressed ambient/wind → RMS ≈ 0.001–0.005
        //   Normal speech (even from a table, ~50 cm) → RMS ≈ 0.008–0.15
        val voiceThreshold   = 0.008f  // Counts as active voice
        val silenceThreshold = 0.003f  // Below this = silence
        var silenceFrames    = 0
        var voiceFrames      = 0       // Chunks with voice-level energy in this segment
        val maxSilenceFrames = 6       // 6 × 256 ms ≈ 1.5 s silence → send to ASR

        while (currentCoroutineContext().isActive && isRecording) {
            val read = audioRecord?.read(buffer, 0, CHUNK_SIZE_SAMPLES, AudioRecord.READ_BLOCKING) ?: break
            if (read <= 0) continue

            val chunk = buffer.take(read)
            accumulator.addAll(chunk)

            val rms = chunk.map { it * it }.average().let { Math.sqrt(it).toFloat() }

            when {
                rms >= voiceThreshold  -> { voiceFrames++; silenceFrames = 0 }
                rms < silenceThreshold -> silenceFrames++
                else                   -> silenceFrames = 0  // Transition zone
            }

            val enoughSilence = silenceFrames >= maxSilenceFrames
            val bufferFull    = accumulator.size >= MAX_ACCUMULATOR_SAMPLES

            if ((enoughSilence || bufferFull) && accumulator.size >= MIN_SAMPLES_FOR_TRANSCRIPTION) {
                if (voiceFrames >= MIN_VOICE_FRAMES) {
                    val samples = accumulator.toFloatArray()
                    accumulator.clear()
                    voiceFrames   = 0
                    silenceFrames = 0
                    // Launch ASR in background so audio recording continues uninterrupted.
                    // asrMutex ensures segments are processed one at a time (ASR is not thread-safe).
                    serviceScope.launch {
                        asrMutex.withLock { processSegment(samples) }
                    }
                } else {
                    Log.d(TAG, "Discarding segment — only noise (voiceFrames=$voiceFrames)")
                    accumulator.clear()
                    voiceFrames   = 0
                    silenceFrames = 0
                }
            }
        }

        // Drain remaining audio when recording stops
        if (accumulator.size >= MIN_SAMPLES_FOR_TRANSCRIPTION && voiceFrames >= MIN_VOICE_FRAMES) {
            val samples = accumulator.toFloatArray()
            serviceScope.launch {
                asrMutex.withLock { processSegment(samples) }
            }
        }
    }

    private fun processSegment(samples: FloatArray) {
        val speakerId = diarizer?.identifySpeaker(samples) ?: -1
        val text = asrEngine?.transcribe(samples)
        if (!text.isNullOrBlank()) {
            Log.d(TAG, "Speaker $speakerId: $text")
            onTranscriptionResult?.invoke(speakerId, text)
        }
    }

    override fun onDestroy() {
        stopRecording()
        asrEngine?.release()
        diarizer?.release()
        super.onDestroy()
        Log.d(TAG, "AudioRecorderService destroyed")
    }

    private fun buildNotification(): Notification {
        val channelId = "livetranscript_channel"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "LiveTranscript Recording", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Live transcription in progress" }
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
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
        private const val TAG = "AudioRecorderService"
        const val ACTION_START  = "com.livetranscript.START_RECORDING"
        const val ACTION_STOP   = "com.livetranscript.STOP_RECORDING"
        private const val NOTIFICATION_ID = 1001
        const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE_SAMPLES = 4096          // ≈ 256 ms per chunk
        private const val CHUNK_SIZE_BYTES   = CHUNK_SIZE_SAMPLES * 4
        private const val MAX_ACCUMULATOR_SAMPLES        = SAMPLE_RATE * 4      // 4 s max per segment
        private const val MIN_SAMPLES_FOR_TRANSCRIPTION  = SAMPLE_RATE * 3 / 4  // 0.75 s minimum
        private const val MIN_VOICE_FRAMES = 1               // At least 1 voice chunk required
    }
}
