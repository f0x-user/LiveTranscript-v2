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

class AudioRecorderService : Service() {

    inner class RecorderBinder : Binder() {
        fun getService(): AudioRecorderService = this@AudioRecorderService
    }

    private val binder = RecorderBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Whisper-Modus
    private var asrEngine: SherpaOnnxAsrEngine? = null
    private val asrMutex = Mutex()

    // Google-Speech-Modus
    private var speechEngine: AndroidSpeechEngine? = null

    // Immer vorhanden (Diarization läuft in beiden Modi)
    private var diarizer: SherpaSpeakerDiarizer? = null

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agcController: AutomaticGainControl? = null
    private var recordingJob: Job? = null

    /** Aktueller Sprecher — wird vom Diarizer-Loop aktualisiert, von beiden Engines gelesen. */
    @Volatile private var currentSpeakerId = 0

    private var activeBackend: AsrBackend = AsrBackend.GOOGLE_SPEECH

    var isRecording = false
        private set

    /** Callback: finales Transkriptions-Ergebnis → UI */
    var onTranscriptionResult: ((speakerId: Int, text: String) -> Unit)? = null

    /** Callback: Partial-Result während des Sprechens → UI (nur Google-Speech-Modus) */
    var onPartialResult: ((speakerId: Int, text: String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        val modelsReady = ModelAssetManager.prepareModels(this)
        if (!modelsReady) {
            Log.e(TAG, "Models not ready — stopping")
            stopSelf()
            return
        }
        initializeEngines()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(TAG, "AudioRecorderService created (backend=$activeBackend)")
    }

    private fun initializeEngines() {
        val repo = SettingsRepository(this)
        val language = repo.getLanguageSync()
        activeBackend = repo.getAsrBackendSync()

        // Diarizer läuft immer (WeSpeaker für Speaker-Erkennung)
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
                    onResult        = { text ->
                        onTranscriptionResult?.invoke(currentSpeakerId, text)
                    },
                    onPartialResult = { text ->
                        onPartialResult?.invoke(currentSpeakerId, text)
                    },
                )
                Log.d(TAG, "Google Speech Engine ready (lang=$language)")
            }
        }
    }

    /** Whisper-Modus: Sprache neu laden (nur während Pause). */
    fun reloadLanguage(language: String) {
        if (isRecording) return
        if (activeBackend != AsrBackend.WHISPER) return
        serviceScope.launch {
            asrMutex.withLock {
                asrEngine?.release()
                asrEngine = SherpaOnnxAsrEngine(this@AudioRecorderService, language)
                    .also { it.initialize() }
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
        isRecording = true

        when (activeBackend) {
            AsrBackend.WHISPER -> {
                // Whisper nutzt AudioRecord exklusiv für Aufnahme + Diarisierung
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                ).coerceAtLeast(CHUNK_SIZE_BYTES)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    bufferSize,
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord init failed")
                    audioRecord?.release(); audioRecord = null
                    isRecording = false; return
                }
                val sessionId = audioRecord!!.audioSessionId
                noiseSuppressor = if (NoiseSuppressor.isAvailable())
                    NoiseSuppressor.create(sessionId)?.also { it.enabled = true } else null
                agcController   = if (AutomaticGainControl.isAvailable())
                    AutomaticGainControl.create(sessionId)?.also { it.enabled = true } else null
                audioRecord?.startRecording()
                recordingJob = serviceScope.launch { processAudioLoop() }
                Log.d(TAG, "Recording started — Whisper mode")
            }
            AsrBackend.GOOGLE_SPEECH -> {
                // Google SpeechRecognizer verwaltet das Mikrofon selbst;
                // kein paralleler AudioRecord (würde Mikrofon-Konflikt erzeugen).
                // Diarisierung entfällt im Google-Speech-Modus — alle Ergebnisse → Speaker 0.
                currentSpeakerId = 0
                speechEngine?.start()
                Log.d(TAG, "Recording started — Google Speech mode (no AudioRecord)")
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        speechEngine?.stop()

        recordingJob?.cancel()
        recordingJob = null

        noiseSuppressor?.release(); noiseSuppressor = null
        agcController?.release();   agcController   = null
        audioRecord?.stop()
        audioRecord?.release();     audioRecord = null

        Log.d(TAG, "Recording stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WHISPER-MODUS: AudioRecord → WeSpeaker + Whisper
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun processAudioLoop() {
        val buffer = FloatArray(CHUNK_SIZE_SAMPLES)
        val accumulator = mutableListOf<Float>()

        val voiceThreshold   = 0.005f
        val silenceThreshold = 0.002f
        var silenceFrames    = 0
        var voiceFrames      = 0
        val maxSilenceFrames = 5   // 5 × 100 ms = 500 ms Stille → ASR

        while (currentCoroutineContext().isActive && isRecording) {
            val read = audioRecord?.read(buffer, 0, CHUNK_SIZE_SAMPLES, AudioRecord.READ_BLOCKING) ?: break
            if (read <= 0) continue

            val chunk = buffer.take(read)
            accumulator.addAll(chunk)

            val rms = chunk.map { it * it }.average().let { Math.sqrt(it).toFloat() }
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

        if (accumulator.size >= MIN_SAMPLES_FOR_TRANSCRIPTION && voiceFrames >= MIN_VOICE_FRAMES) {
            val samples = accumulator.toFloatArray()
            serviceScope.launch { asrMutex.withLock { processWhisperSegment(samples) } }
        }
    }

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
    // GOOGLE-SPEECH-MODUS: AudioRecord nur für Diarization
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun diarizerOnlyLoop() {
        val buffer = FloatArray(CHUNK_SIZE_SAMPLES)
        val accumulator = mutableListOf<Float>()

        val voiceThreshold   = 0.005f
        val silenceThreshold = 0.002f
        var silenceFrames    = 0
        var voiceFrames      = 0
        val maxSilenceFrames = 5

        while (currentCoroutineContext().isActive && isRecording) {
            val read = audioRecord?.read(buffer, 0, CHUNK_SIZE_SAMPLES, AudioRecord.READ_BLOCKING) ?: break
            if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) break
            if (read <= 0) continue

            val chunk = buffer.take(read)
            accumulator.addAll(chunk)

            val rms = chunk.map { it * it }.average().let { Math.sqrt(it).toFloat() }
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
                        currentSpeakerId = diarizer?.identifySpeaker(samples) ?: 0
                    }
                } else {
                    accumulator.clear(); voiceFrames = 0; silenceFrames = 0
                }
            }
        }
        Log.d(TAG, "Diarizer-only loop ended")
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
        private const val TAG = "AudioRecorderService"
        const val ACTION_START  = "com.livetranscript.START_RECORDING"
        const val ACTION_STOP   = "com.livetranscript.STOP_RECORDING"
        private const val NOTIFICATION_ID = 1001
        const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE_SAMPLES = 1600          // 100 ms per chunk
        private const val CHUNK_SIZE_BYTES   = CHUNK_SIZE_SAMPLES * 4
        private const val MAX_ACCUMULATOR_SAMPLES       = SAMPLE_RATE * 2   // 2 s
        private const val MIN_SAMPLES_FOR_TRANSCRIPTION = SAMPLE_RATE / 2   // 0,5 s
        private const val MIN_VOICE_FRAMES = 2
    }
}
