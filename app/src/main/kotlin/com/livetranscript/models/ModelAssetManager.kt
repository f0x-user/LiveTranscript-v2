package com.livetranscript.models

import android.content.Context
import android.util.Log
import java.io.File

object ModelAssetManager {

    private const val TAG = "ModelAssetManager"

    private val REQUIRED_MODELS = listOf(
        "whisper-tiny/tiny-encoder.int8.onnx",
        "whisper-tiny/tiny-decoder.int8.onnx",
        "whisper-tiny/tiny-tokens.txt",
        "wespeaker/model.onnx"
    )

    /**
     * Kopiert ALLE Modelle synchron und blockierend aus den APK-Assets
     * nach filesDir. Muss aufgerufen werden BEVOR sherpa-onnx
     * initialisiert wird.
     * Gibt true zurück wenn alle Modelle verfügbar sind.
     */
    fun prepareModels(context: Context): Boolean {
        return try {
            REQUIRED_MODELS.forEach { assetPath ->
                copyAssetIfNeeded(context, assetPath)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare models", e)
            false
        }
    }

    fun getModelPath(context: Context, assetPath: String): String =
        File(context.filesDir, assetPath).absolutePath

    fun allModelsReady(context: Context): Boolean =
        REQUIRED_MODELS.all { assetPath ->
            val file = File(context.filesDir, assetPath)
            file.exists() && file.length() > 0
        }

    private fun copyAssetIfNeeded(context: Context, assetPath: String) {
        val target = File(context.filesDir, assetPath)
        if (target.exists() && target.length() > 0) {
            Log.d(TAG, "Already exists: $assetPath (${target.length()} bytes)")
            return
        }
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Log.d(TAG, "Copied: $assetPath (${target.length()} bytes)")
    }
}
