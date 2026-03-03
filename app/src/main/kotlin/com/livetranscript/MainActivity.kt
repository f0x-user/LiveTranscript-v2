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
import com.livetranscript.ui.SettingsScreen
import com.livetranscript.ui.TranscriptEntry
import com.livetranscript.ui.theme.LiveTranscript2Theme

class MainActivity : ComponentActivity() {

    private var recorderService: AudioRecorderService? = null
    private var serviceBound = false

    private val transcripts = mutableStateListOf<TranscriptEntry>()
    private var isRecording by mutableStateOf(false)
    private var modelsReady by mutableStateOf(false)
    private var currentScreen by mutableStateOf("main")
    private lateinit var settingsViewModel: SettingsViewModel

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? AudioRecorderService.RecorderBinder ?: return
            recorderService = b.getService()
            serviceBound = true
            recorderService?.onTranscriptionResult = { speakerId, text ->
                runOnUiThread {
                    transcripts.add(TranscriptEntry(speakerId = speakerId, text = text))
                }
            }
            Log.d(TAG, "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorderService = null
            serviceBound = false
            isRecording = false
            Log.d(TAG, "Service disconnected")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            bindAndStartService()
        } else {
            Log.w(TAG, "RECORD_AUDIO permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settingsViewModel = ViewModelProvider(
            this,
            SettingsViewModel.Factory(applicationContext),
        )[SettingsViewModel::class.java]

        setContent {
            val settingsState by settingsViewModel.uiState.collectAsState()

            LiveTranscript2Theme(themeMode = settingsState.themeMode) {
                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    when (currentScreen) {
                        "settings" -> SettingsScreen(
                            settingsViewModel = settingsViewModel,
                            onBack = { currentScreen = "main" },
                        )
                        else -> LiveScreen(
                            isRecording = isRecording,
                            transcripts = transcripts,
                            modelsReady = modelsReady,
                            autoScroll = settingsState.autoScroll,
                            showTimestamps = settingsState.showTimestamps,
                            onStartRecording = { startRecording() },
                            onStopRecording = { stopRecording() },
                            onClear = { transcripts.clear() },
                            onOpenSettings = { currentScreen = "settings" },
                        )
                    }
                }
            }
        }

        checkPermissionAndBind()
    }

    private fun checkPermissionAndBind() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> {
                bindAndStartService()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun bindAndStartService() {
        val intent = Intent(this, AudioRecorderService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        // Check if models are ready after a short delay (service copies them on start)
        android.os.Handler(mainLooper).postDelayed({
            modelsReady = ModelAssetManager.allModelsReady(this)
            if (!modelsReady) {
                // Keep checking until ready
                checkModelsReady()
            }
        }, 2000)
    }

    private fun checkModelsReady() {
        if (modelsReady) return
        android.os.Handler(mainLooper).postDelayed({
            modelsReady = ModelAssetManager.allModelsReady(this)
            if (!modelsReady) checkModelsReady()
        }, 1000)
    }

    private fun startRecording() {
        val service = recorderService ?: return
        service.startRecording()
        isRecording = true
    }

    private fun stopRecording() {
        val service = recorderService ?: return
        service.stopRecording()
        isRecording = false
    }

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
