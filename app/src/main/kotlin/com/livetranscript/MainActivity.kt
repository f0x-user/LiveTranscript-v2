package com.livetranscript

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.livetranscript.audio.AudioRecorderService
import com.livetranscript.models.ModelAssetManager
import com.livetranscript.settings.SettingsViewModel
import com.livetranscript.ui.LiveScreen
import com.livetranscript.ui.LocalStrings
import com.livetranscript.ui.SettingsScreen
import com.livetranscript.ui.TranscriptEntry
import com.livetranscript.ui.stringsForLanguage
import com.livetranscript.ui.theme.LiveTranscript2Theme

/**
 * Application entry point. Owns the Compose UI tree and the connection to [AudioRecorderService].
 *
 * Responsibilities:
 * - Requests `RECORD_AUDIO` permission before starting the service.
 * - Starts and binds [AudioRecorderService] as a foreground service.
 * - Wires service callbacks to Compose state:
 *   - `onTranscriptionResult` → appends to [transcripts]
 *   - `onPartialResult`       → updates [partialText]
 * - Handles system audio capture mode via [MediaProjectionManager]:
 *   - When the user enables "Video/Media" capture, requests screen recording permission.
 *   - Passes the [MediaProjection] token to [AudioRecorderService] before recording starts.
 * - Handles navigation between [LiveScreen] and [SettingsScreen].
 * - Polls [ModelAssetManager.allModelsReady] until model files are available (first install).
 */
class MainActivity : ComponentActivity() {

    private var recorderService: AudioRecorderService? = null
    private var serviceBound    = false

    private val transcripts    = mutableStateListOf<TranscriptEntry>()
    private var isRecording    by mutableStateOf(false)
    private var modelsReady    by mutableStateOf(false)
    private var currentScreen  by mutableStateOf("main")
    private var partialText    by mutableStateOf("")

    private lateinit var settingsViewModel: SettingsViewModel

    // ── Service connection ────────────────────────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? AudioRecorderService.RecorderBinder ?: return
            recorderService = b.getService()
            serviceBound    = true
            recorderService?.onTranscriptionResult = { speakerId, text ->
                runOnUiThread {
                    partialText = ""
                    transcripts.add(TranscriptEntry(speakerId = speakerId, text = text))
                }
            }
            recorderService?.onPartialResult = { _, text ->
                runOnUiThread { partialText = text }
            }
            Log.d(TAG, "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorderService = null
            serviceBound    = false
            isRecording     = false
            Log.d(TAG, "Service disconnected")
        }
    }

    // ── Permission launchers ──────────────────────────────────────────────────

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) bindAndStartService()
        else Log.w(TAG, "RECORD_AUDIO permission denied")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settingsViewModel = ViewModelProvider(
            this,
            SettingsViewModel.Factory(applicationContext),
        )[SettingsViewModel::class.java]

        setContent {
            val settingsState by settingsViewModel.uiState.collectAsState()
            val appStrings    = stringsForLanguage(settingsState.transcriptionLanguage)

            LiveTranscript2Theme(themeMode = settingsState.themeMode) {
                CompositionLocalProvider(LocalStrings provides appStrings) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                        when (currentScreen) {
                            "settings" -> SettingsScreen(
                                settingsViewModel = settingsViewModel,
                                onBack            = { currentScreen = "main" },
                            )
                            else -> LiveScreen(
                                isRecording           = isRecording,
                                transcripts           = transcripts,
                                modelsReady           = modelsReady,
                                autoScroll            = settingsState.autoScroll,
                                showTimestamps        = settingsState.showTimestamps,
                                transcriptionLanguage = settingsState.transcriptionLanguage,
                                partialText           = partialText,
                                onLanguageChange      = { settingsViewModel.setLanguage(it) },
                                onStartRecording      = { startRecording() },
                                onStopRecording       = { stopRecording() },
                                onClear               = { transcripts.clear() },
                                onOpenSettings        = { currentScreen = "settings" },
                            )
                        }
                    }
                }
            }
        }

        checkPermissionAndBind()
    }

    // ── Permission & service helpers ──────────────────────────────────────────

    /**
     * Checks for `RECORD_AUDIO` permission. Proceeds to [bindAndStartService] if granted,
     * otherwise launches the system permission dialog.
     */
    private fun checkPermissionAndBind() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            bindAndStartService()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Starts [AudioRecorderService] as a foreground service and binds to it.
     * After a 2 s grace period (service init + model copy), polls [ModelAssetManager]
     * to set [modelsReady] — used by the UI to enable/disable the record button.
     */
    private fun bindAndStartService() {
        val intent = Intent(this, AudioRecorderService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        android.os.Handler(mainLooper).postDelayed({
            modelsReady = ModelAssetManager.allModelsReady(this)
            if (!modelsReady) checkModelsReady()
        }, 2000)
    }

    /**
     * Polls [ModelAssetManager.allModelsReady] every 1 s until all model files are present.
     */
    private fun checkModelsReady() {
        if (modelsReady) return
        android.os.Handler(mainLooper).postDelayed({
            modelsReady = ModelAssetManager.allModelsReady(this)
            if (!modelsReady) checkModelsReady()
        }, 1000)
    }

    // ── Recording control ─────────────────────────────────────────────────────

    /** Called when the user taps the record button. Starts microphone recording immediately. */
    fun startRecording() {
        startRecordingInternal()
    }

    /** Instructs the service to begin recording and updates [isRecording] state. */
    private fun startRecordingInternal() {
        recorderService?.startRecording()
        isRecording = true
    }

    /** Stops recording and clears partial text state. */
    fun stopRecording() {
        val service = recorderService ?: return
        service.stopRecording()
        isRecording = false
        partialText = ""
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
