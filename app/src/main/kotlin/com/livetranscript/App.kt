package com.livetranscript

import android.app.Application
import android.util.Log

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "LiveTranscript2 App starting")
    }

    companion object {
        private const val TAG = "LiveTranscript2"
    }
}
