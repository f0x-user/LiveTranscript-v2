package com.livetranscript.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Supported transcript export formats.
 *
 * - [TXT]  — Plain text, one line per entry: `[Speaker 1] Hello`
 * - [CSV]  — Timestamp, speaker, text (RFC 4180-compatible, UTF-8)
 * - [JSON] — Array of `{timestamp, speaker, text}` objects
 * - [SRT]  — SubRip subtitle format; each entry gets a 3-second window
 *
 * The [ext] property is appended to the auto-generated filename.
 */
enum class SaveFormat(val ext: String) {
    TXT("txt"),
    CSV("csv"),
    JSON("json"),
    SRT("srt"),
}

// ─────────────────────────────────────────────────────────────────────────────
// Format converters

/** Converts [entries] to a plain-text string, one `[Speaker N] text` line per entry. */
fun transcriptToText(entries: List<TranscriptEntry>): String =
    entries.joinToString("\n") { e ->
        val label = if (e.speakerId >= 0) "Speaker ${e.speakerId + 1}" else "Unknown"
        "[$label] ${e.text}"
    }

/** Converts [entries] to CSV with columns: Timestamp, Speaker, Text. Text values are quoted and escaped. */
fun transcriptToCsv(entries: List<TranscriptEntry>): String {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return buildString {
        appendLine("Timestamp,Speaker,Text")
        entries.forEach { e ->
            val ts    = fmt.format(Date(e.timestamp))
            val sp    = if (e.speakerId >= 0) "Speaker ${e.speakerId + 1}" else "Unknown"
            val text  = e.text.replace("\"", "\"\"")
            appendLine("$ts,\"$sp\",\"$text\"")
        }
    }
}

/** Converts [entries] to a JSON array: `[{"timestamp":…,"speaker":…,"text":"…"},…]` */
fun transcriptToJson(entries: List<TranscriptEntry>): String {
    val items = entries.joinToString(",\n  ") { e ->
        val sp = if (e.speakerId >= 0) e.speakerId + 1 else -1
        """{"timestamp":${e.timestamp},"speaker":$sp,"text":${jsonString(e.text)}}"""
    }
    return "[\n  $items\n]"
}

/**
 * Converts [entries] to SubRip (.srt) subtitle format.
 * Timestamps are relative to the first entry; each entry gets a 3-second display window.
 */
fun transcriptToSrt(entries: List<TranscriptEntry>): String {
    val first = entries.firstOrNull()?.timestamp ?: 0L
    return buildString {
        entries.forEachIndexed { i, e ->
            val startMs = e.timestamp - first
            val endMs   = startMs + 3_000L
            val label   = if (e.speakerId >= 0) "Speaker ${e.speakerId + 1}" else "Unknown"
            appendLine(i + 1)
            appendLine("${srtTime(startMs)} --> ${srtTime(endMs)}")
            appendLine("[$label] ${e.text}")
            appendLine()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Save directly to Downloads folder (MediaStore, no permission required API 29+)

/**
 * Writes [entries] as [format] to the public Downloads folder via [MediaStore.Downloads].
 * No WRITE_EXTERNAL_STORAGE permission is required on Android 10+ (API 29+).
 * Returns the saved filename on success, or null on failure.
 */
fun saveTranscriptToDownloads(
    context: Context,
    entries: List<TranscriptEntry>,
    format: SaveFormat,
): String? {
    val content = when (format) {
        SaveFormat.TXT  -> transcriptToText(entries)
        SaveFormat.CSV  -> transcriptToCsv(entries)
        SaveFormat.JSON -> transcriptToJson(entries)
        SaveFormat.SRT  -> transcriptToSrt(entries)
    }
    val ts       = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val filename = "transcript_$ts.${format.ext}"
    val mimeType = when (format) {
        SaveFormat.TXT, SaveFormat.SRT -> "text/plain"
        SaveFormat.CSV                 -> "text/csv"
        SaveFormat.JSON                -> "application/json"
    }

    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, filename)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run {
        Log.e("SaveTranscript", "MediaStore insert returned null")
        return null
    }
    return try {
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        filename
    } catch (e: Exception) {
        Log.e("SaveTranscript", "Write failed", e)
        resolver.delete(uri, null, null)
        null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Share via Android share-sheet

/**
 * Formats [entries] according to [format] and opens the Android share-sheet via
 * [Intent.ACTION_SEND]. The content is passed as `EXTRA_TEXT` (no FileProvider needed).
 * The suggested filename (`transcript_YYYYMMDD_HHmmss.ext`) appears in apps that support it.
 */
fun shareTranscript(context: Context, entries: List<TranscriptEntry>, format: SaveFormat) {
    val content = when (format) {
        SaveFormat.TXT  -> transcriptToText(entries)
        SaveFormat.CSV  -> transcriptToCsv(entries)
        SaveFormat.JSON -> transcriptToJson(entries)
        SaveFormat.SRT  -> transcriptToSrt(entries)
    }
    val ts       = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val filename = "transcript_$ts.${format.ext}"
    val intent   = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, filename)
        putExtra(Intent.EXTRA_TEXT, content)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers

private fun srtTime(ms: Long): String {
    val h      = ms / 3_600_000
    val m      = (ms % 3_600_000) / 60_000
    val s      = (ms % 60_000) / 1_000
    val millis = ms % 1_000
    return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
}

private fun jsonString(s: String): String {
    val escaped = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "\"$escaped\""
}
