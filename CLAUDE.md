# CLAUDE.md — AI Context & Continuation Guide

This document is written for an AI assistant (Claude or similar) that is picking up
development of this project. It covers every architectural decision, the current state
of all important files, known limitations, and suggested next steps.

---

## 1. Project Overview

**LiveTranscript** is a fully offline Android app that transcribes speech in real-time
using on-device AI models. The two core engines are:

1. **Whisper Tiny (multilingual, ONNX int8)** — speech-to-text via sherpa-onnx
2. **WeSpeaker embedding model** — speaker diarization (who said what)

The app is written in **Kotlin** with **Jetpack Compose** (Material 3) and targets
**Android 12+ (API 31)**.

---

## 2. Repository Layout

```
LiveTranscript-v2/
├── app/
│   ├── build.gradle.kts             App module build script
│   ├── libs/                        sherpa-onnx-{VERSION}.aar (auto-downloaded)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/models/           Whisper + WeSpeaker ONNX files (NOT in git)
│       └── kotlin/com/livetranscript/
│           ├── App.kt               Application class (minimal)
│           ├── MainActivity.kt      Entry point
│           ├── asr/
│           │   ├── AsrEngine.kt     Interface
│           │   └── SherpaOnnxAsrEngine.kt  Whisper implementation
│           ├── audio/
│           │   └── AudioRecorderService.kt Foreground service (mic → ASR → callback)
│           ├── diarization/
│           │   ├── SpeakerDiarizer.kt      Interface
│           │   └── SherpaSpeakerDiarizer.kt WeSpeaker implementation
│           ├── models/
│           │   └── ModelAssetManager.kt    Copies assets to internal storage
│           ├── settings/
│           │   ├── AppSettings.kt          ThemeMode enum, LANGUAGE_OPTIONS, DataStore keys
│           │   ├── SettingsRepository.kt   Flows + suspend setters
│           │   └── SettingsViewModel.kt    SettingsUiState, set*() actions
│           └── ui/
│               ├── AppStrings.kt           DE/EN string sets + LocalStrings CompositionLocal
│               ├── LiveScreen.kt           Main screen composable
│               ├── SaveTranscriptUtils.kt  Format converters + share-sheet helper
│               ├── SettingsScreen.kt       Settings screen composable
│               └── theme/
│                   ├── DesignTokens.kt     ← SINGLE SOURCE OF TRUTH for all UI values
│                   ├── Color.kt            Re-exports from DesignTokens
│                   ├── Theme.kt            MaterialTheme wiring
│                   └── Type.kt             Typography scale
├── gradle/
│   └── libs.versions.toml           Version catalog
├── README.md
└── CLAUDE.md                        (this file)
```

---

## 3. Key Files — What They Do

### `DesignTokens.kt`
**This is the file to edit when restyling the UI.** It is an `object AppTheme` with
nested objects: `Colors`, `Gradients`, `Dimens`, `Animation`, `Shapes`, `TextSize`.
Every composable reads from here. No inline colour literals exist in screen files.

### `LiveScreen.kt`
Main screen. Contains:
- `LiveScreen()` composable — layout, state management, waveform animation, FAB pulse
- `TranscriptBubble()` composable — per-entry chat bubble
- `SaveTranscriptDialog()` private composable — format picker (TXT/CSV/JSON/SRT)
- `TranscriptEntry` data class — `speakerId: Int, text: String, timestamp: Long`

**State flow:** everything is passed as parameters; no global state.

### `AudioRecorderService.kt`
Foreground service that owns the audio pipeline:
```
AudioRecord → NoiseSuppressor/AGC → RMS-based VAD → SherpaSpeakerDiarizer → SherpaOnnxAsrEngine → onTranscriptionResult callback
```
The service is started and bound in `MainActivity`. Results are delivered via a lambda:
```kotlin
recorderService?.onTranscriptionResult = { speakerId, text ->
    runOnUiThread { transcripts.add(TranscriptEntry(speakerId, text)) }
}
```

### `SettingsViewModel.kt`
```kotlin
data class SettingsUiState(
    val themeMode: ThemeMode,
    val autoScroll: Boolean,
    val showTimestamps: Boolean,
    val transcriptionLanguage: String,   // Whisper language code, "" = auto
)
```
Exposed as `StateFlow<SettingsUiState>` via `combine()` over 4 DataStore flows.
Actions: `setTheme()`, `setAutoScroll()`, `setShowTimestamps()`, `setLanguage()`.

### `AppStrings.kt`
```kotlin
data class AppStrings(appTitle, recordingRunning, ready, save, cancel, ...)
val GermanStrings  = AppStrings(...)   // default
val EnglishStrings = AppStrings(...)
fun stringsForLanguage(code: String): AppStrings   // "en" → EN, else DE
val LocalStrings = compositionLocalOf { GermanStrings }
```
`MainActivity` wraps the composition in `CompositionLocalProvider(LocalStrings provides appStrings)`.
Screens read: `val strings = LocalStrings.current`.

### `SaveTranscriptUtils.kt`
```kotlin
enum class SaveFormat(val ext: String)  // TXT, CSV, JSON, SRT
fun transcriptToText(...)   fun transcriptToCsv(...)
fun transcriptToJson(...)   fun transcriptToSrt(...)
fun shareTranscript(context, entries, format)  // uses Intent.ACTION_SEND
```
No `FileProvider` needed — content is passed as `EXTRA_TEXT` string.

---

## 4. Data Flow Diagram

```
Microphone
    │
    ▼
AudioRecorderService (Foreground)
    ├── AudioRecord (16 kHz, 16-bit PCM)
    ├── NoiseSuppressor + AGC (Android AudioEffect)
    ├── RMS VAD → accumulate speech segments
    ├── SherpaSpeakerDiarizer → speakerId: Int
    └── SherpaOnnxAsrEngine  → text: String
            │
            ▼ onTranscriptionResult(speakerId, text)
MainActivity (UI thread)
            │
            ▼
transcripts: MutableStateList<TranscriptEntry>
            │
            ▼
LiveScreen (Compose)
```

---

## 5. Settings Persistence

Uses Jetpack DataStore (Preferences). Keys defined in `AppSettings.PreferenceKeys`:

| Key | Type | Default |
|---|---|---|
| `theme_mode` | String (ThemeMode.name) | `SYSTEM` |
| `auto_scroll` | Boolean | `true` |
| `show_timestamps` | Boolean | `false` |
| `transcription_language` | String | `""` (auto) |

---

## 6. Build System

- Gradle Version Catalog: `gradle/libs.versions.toml`
- Kotlin 2.0, Compose Compiler plugin via `kotlin.compose` plugin alias
- The sherpa-onnx AAR is **not checked in**. A custom Gradle task `downloadSherpaOnnxAAR`
  in `app/build.gradle.kts` downloads it from GitHub Releases at build time.
  It tries two URLs (current version + fallback to 1.12.27).
- ABI filter: `arm64-v8a` only (covers 99% of modern Android devices).

---

## 7. Language & Localisation

Whisper language codes map to UI languages:
- `"en"` → `EnglishStrings`
- everything else (incl. `""` auto, `"de"`, French, etc.) → `GermanStrings`

To add a new UI language:
1. Add a new `val FooStrings = AppStrings(...)` in `AppStrings.kt`
2. Add a branch in `stringsForLanguage()`:
   ```kotlin
   "fr" -> FrenchStrings
   ```
3. No other files need touching.

---

## 8. UI Customisation Guide

**To change colours / spacing / animation:**
Edit `DesignTokens.kt` only — no other file. Key objects:
- `AppTheme.Colors.*` — every colour
- `AppTheme.Gradients.background` — main screen background gradient
- `AppTheme.Dimens.*` — sizes in `dp`
- `AppTheme.Animation.*` — timing in `ms`
- `AppTheme.Shapes.bubble` — transcript bubble corner radii

**To add a new speaker colour:**
Append to `AppTheme.Colors.speakers: List<Color>`.

**To change the waveform:**
- Bar count: `AppTheme.Dimens.waveformBars`
- Refresh rate: `AppTheme.Animation.waveformTickMs`
- Bar colour: `AppTheme.Colors.accentCyan`
- Canvas drawing logic: `LiveScreen.kt` → Canvas block inside `if (isRecording)`

> ⚠️ The waveform is currently **simulated** (random heights, not real RMS data).
> See "Known Limitations" below.

---

## 9. Known Limitations & Technical Debt

| Issue | Location | Suggested Fix |
|---|---|---|
| **Waveform is simulated** | `LiveScreen.kt` LaunchedEffect | Expose an RMS `StateFlow` from `AudioRecorderService` and read it in the composable |
| **No error handling on ASR failure** | `SherpaOnnxAsrEngine.kt` | Expose an error state to the UI |
| **Model files not bundled in git** | `app/src/main/assets/models/` | Document the exact download steps or add a model-download screen |
| **Only DE/EN UI languages** | `AppStrings.kt` | Add more `AppStrings` instances |
| **Whisper Tiny only** | `SherpaOnnxAsrEngine.kt` | Add a settings option to choose model size; `ModelAssetManager` needs updating |
| **No landscape layout** | `LiveScreen.kt` | Add `WindowSizeClass` checks |
| **Service lifecycle** | `AudioRecorderService.kt` | Add proper cleanup on `onTaskRemoved` |
| **Direct push to main blocked** | git | The git proxy only allows pushes to `claude/` branches; merge via Gitea UI |

---

## 10. Current Git State (as of last session)

```
Branch: claude/livetranscript-android-diarization-JoYix
Remote: origin/claude/livetranscript-android-diarization-JoYix  ← fully pushed

origin/main ← waiting for PR merge from the feature branch
```

**Recent commits on the feature branch:**
```
d4916a2  chore: pull in audio + CI fixes from remote feature branch
8d6f4f5  feat: save transcript, language dropdown, app-language localisation
4734df3  fix: filter noise tokens, improve diarization, add settings screen
...
```

To land these changes on `main`, create a Pull Request on the Gitea web UI from
`claude/livetranscript-android-diarization-JoYix` → `main`.

---

## 11. Next Steps (Prioritised)

1. **Real waveform** — Pass RMS from `AudioRecorderService` to the UI via a `StateFlow`.
   Expose `val rms: StateFlow<Float>` from the service; connect in `MainActivity`;
   pass as a `Float` param to `LiveScreen`; replace the random waveform with real data.

2. **Model downloader** — Add an in-app screen or `WorkManager` job that downloads
   the Whisper ONNX files on first launch, with progress feedback.

3. **Larger Whisper models** — Add a settings toggle (Tiny / Small / Medium).
   `SherpaOnnxAsrEngine` already accepts a `language` param; extend it to accept a
   `modelSize` param and adjust file paths accordingly.

4. **More UI languages** — Add `FrenchStrings`, `SpanishStrings`, etc. in `AppStrings.kt`
   and wire them up in `stringsForLanguage()`.

5. **Background recording** — Add notification action buttons (Pause / Stop) and handle
   `onTaskRemoved` in `AudioRecorderService`.

6. **Error states** — Show a snackbar / dialog when ASR fails to initialize (missing
   model files, OOM, etc.).

---

## 12. How to Continue Development

```bash
# Switch to the active feature branch
git checkout claude/livetranscript-android-diarization-JoYix

# Build and verify
./gradlew assembleDebug

# Make changes, then commit and push
git add -p
git commit -m "feat: ..."
git push -u origin claude/livetranscript-android-diarization-JoYix
```

> Push to `main` directly will fail with HTTP 403 (protected branch in this environment).
> Use a PR via the Gitea web UI.

---

## 13. Important Patterns & Conventions

- **No hardcoded colour literals** in screen files — always use `AppTheme.Colors.*`
- **No hardcoded string literals** in screen files — always use `LocalStrings.current.*`
- **`TranscriptEntry`** is defined in `LiveScreen.kt` (not a separate file) — move it
  to a `model/` package if the model grows
- **`SettingsViewModel.Factory`** uses manual DI — no Hilt/Koin
- **Coroutine scope in Service** uses `SupervisorJob` so one failure doesn't cancel all
- **`LaunchedEffect(isRecording)`** in `LiveScreen` resets waveform on start/stop —
  `isActive` check ensures cancellation on recomposition
- **`menuAnchor()`** on `OutlinedTextField` inside `ExposedDropdownMenuBox` — required
  for Material 3 dropdown; no type parameter for API compatibility
