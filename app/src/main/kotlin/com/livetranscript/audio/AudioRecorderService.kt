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
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.livetranscript.MainActivity
import com.livetranscript.R
import com.livetranscript.asr.SherpaOnnxAsrEngine
import com.livetranscript.diarization.SherpaSpeakerDiarizer
import com.livetranscript.models.ModelAssetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class AudioRecorderService : Service() {

    inner class RecorderBinder : Binder() {
        fun getService(): AudioRecorderService = this@AudioRecorderService
    }

    private val binder = RecorderBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var asrEngine: SherpaOnnxAsrEngine? = null
    private var diarizer: SherpaSpeakerDiarizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    var isRecording = false
        private set

    // Callback to send transcription results to the UI
    var onTranscriptionResult: ((speakerId: Int, text: String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()

        // SCHRITT 1: Modelle kopieren — SYNCHRON, BLOCKIEREND
        // sherpa-onnx darf erst DANACH initialisiert werden
        val modelsReady = ModelAssetManager.prepareModels(this)
        if (!modelsReady) {
            Log.e(TAG, "Models not ready — stopping")
            stopSelf()
            return
        }

        // SCHRITT 2: Erst jetzt sherpa-onnx initialisieren
        initializeSherpaOnnx()

        // SCHRITT 3: Foreground Service starten
        startForeground(NOTIFICATION_ID, buildNotification())

        Log.d(TAG, "AudioRecorderService created")
    }

    private fun initializeSherpaOnnx() {
        try {
            asrEngine = SherpaOnnxAsrEngine(this).also { it.initialize() }
            diarizer = SherpaSpeakerDiarizer(this).also { it.initialize() }
            Log.d(TAG, "SherpaOnnx initialized: ASR=${asrEngine?.isInitialized}, Diarizer=${diarizer?.isInitialized}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SherpaOnnx", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
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
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        isRecording = true
        Log.d(TAG, "Recording started")

        recordingJob = serviceScope.launch {
            processAudioLoop()
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "Recording stopped")
    }

    private suspend fun processAudioLoop() {
        val chunkSamples = CHUNK_SIZE_SAMPLES
        val buffer = FloatArray(chunkSamples)
        val accumulator = mutableListOf<Float>()
        val silenceThreshold = 0.015f
        var silenceFrames = 0
        val maxSilenceFrames = 15 // ~1.5 seconds of silence triggers transcription

        while (currentCoroutineContext().isActive && isRecording) {
            val read = audioRecord?.read(buffer, 0, chunkSamples, AudioRecord.READ_NON_BLOCKING) ?: break
            if (read <= 0) continue

            val chunk = buffer.take(read)
            accumulator.addAll(chunk)

            // Check for silence to detect end of utterance
            val rms = chunk.map { it * it }.average().let { Math.sqrt(it).toFloat() }
            if (rms < silenceThreshold) {
                silenceFrames++
            } else {
                silenceFrames = 0
            }

            // Process after silence or buffer full
            if ((silenceFrames >= maxSilenceFrames || accumulator.size >= MAX_ACCUMULATOR_SAMPLES)
                && accumulator.size >= MIN_SAMPLES_FOR_TRANSCRIPTION) {

                val samples = accumulator.toFloatArray()
                accumulator.clear()
                silenceFrames = 0

                processSegment(samples)
            }
        }

        // Process any remaining audio
        if (accumulator.size >= MIN_SAMPLES_FOR_TRANSCRIPTION) {
            processSegment(accumulator.toFloatArray())
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
            val channel = NotificationChannel(
                channelId,
                "LiveTranscript Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live transcription in progress"
            }
            manager.createNotificationChannel(channel)
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
        const val ACTION_START = "com.livetranscript.START_RECORDING"
        const val ACTION_STOP = "com.livetranscript.STOP_RECORDING"
        private const val NOTIFICATION_ID = 1001
        const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE_SAMPLES = 4096
        private const val CHUNK_SIZE_BYTES = CHUNK_SIZE_SAMPLES * 4 // float = 4 bytes
        private const val MAX_ACCUMULATOR_SAMPLES = SAMPLE_RATE * 10 // 10 seconds max
        private const val MIN_SAMPLES_FOR_TRANSCRIPTION = SAMPLE_RATE * 3 / 2 // 1.5 seconds min
    }
}
