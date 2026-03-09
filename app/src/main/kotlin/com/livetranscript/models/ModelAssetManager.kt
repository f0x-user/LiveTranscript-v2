package com.livetranscript.models

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages on-device ONNX model files for Whisper ASR and WeSpeaker diarization.
 *
 * Model files are bundled inside the APK as uncompressed assets and must be copied
 * to the app's internal storage ([Context.filesDir]) before sherpa-onnx can load them,
 * because the native library requires a real file path rather than an asset stream.
 *
 * Required files (relative to `assets/models/`):
 * - `whisper-base/base-encoder.int8.onnx`
 * - `whisper-base/base-decoder.int8.onnx`
 * - `whisper-base/base-tokens.txt`
 * - `wespeaker/model.onnx`
 *
 * Call [prepareModels] once on service start before initialising any engine.
 * Subsequent calls are cheap: files are only re-copied if the cached size differs
 * from the bundled asset size (stale cache detection).
 */
object ModelAssetManager {

    private const val TAG = "ModelAssetManager"

    private val REQUIRED_MODELS = listOf(
        "whisper-base/base-encoder.int8.onnx",
        "whisper-base/base-decoder.int8.onnx",
        "whisper-base/base-tokens.txt",
        "wespeaker/model.onnx"
    )

    /**
     * Copies all required model files from APK assets to [Context.filesDir] synchronously.
     *
     * Must be called before any sherpa-onnx engine is initialised. Already-valid files
     * (matching asset size) are skipped; stale/missing files are (re-)copied.
     *
     * @return true if all model files are present and non-empty after the operation.
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

    /**
     * Returns the absolute filesystem path for a model file.
     * The path is under [Context.filesDir]; the file may or may not exist yet.
     *
     * @param assetPath Relative path as used in [REQUIRED_MODELS], e.g. "whisper-tiny/tiny-encoder.int8.onnx".
     */
    fun getModelPath(context: Context, assetPath: String): String =
        File(context.filesDir, assetPath).absolutePath

    /**
     * Returns true only if every required model file exists on disk and has a non-zero size.
     * Use this to gate UI "ready" state without triggering a copy operation.
     */
    fun allModelsReady(context: Context): Boolean =
        REQUIRED_MODELS.all { assetPath ->
            val file = File(context.filesDir, assetPath)
            file.exists() && file.length() > 0
        }

    private fun copyAssetIfNeeded(context: Context, assetPath: String) {
        val target = File(context.filesDir, assetPath)
        // Detect stale/incompatible cached files: compare size with the bundled asset.
        // openFd() only works on uncompressed assets (noCompress in build.gradle.kts).
        // Falls back to unconditional copy if openFd() fails (e.g. compressed asset).
        if (target.exists() && target.length() > 0) {
            val assetSize = try {
                context.assets.openFd(assetPath).use { it.length }
            } catch (_: Exception) {
                // openFd nicht verfügbar (komprimiertes Asset) → Größe unbekannt, vorhandene
                // Datei wird beibehalten, sofern sie nicht leer ist.
                Log.w(TAG, "openFd failed for $assetPath — keeping existing cached file")
                return
            }
            if (target.length() == assetSize) {
                Log.d(TAG, "Already exists: $assetPath (${target.length()} bytes)")
                return
            }
            Log.w(TAG, "Stale cache detected for $assetPath " +
                "(cached=${target.length()}, asset=$assetSize) — replacing")
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
