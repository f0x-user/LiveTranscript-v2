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

Two ASR backends are selectable in settings:
- **Google Speech** (default) — uses Android `SpeechRecognizer`; continuous, low-latency
- **Whisper** — fully offline ONNX inference; slower but private

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
│           ├── MainActivity.kt      Entry point; service binding, permission, nav
│           ├── asr/
│           │   ├── AsrEngine.kt           Interface (initialize/transcribe/release)
│           │   ├── SherpaOnnxAsrEngine.kt Whisper ONNX implementation
│           │   └── AndroidSpeechEngine.kt Google SpeechRecognizer implementation
│           ├── audio/
│           │   └── AudioRecorderService.kt Foreground service (mic → VAD → ASR → callback)
│           ├── diarization/
│           │   ├── SpeakerDiarizer.kt          Interface
│           │   └── SherpaSpeakerDiarizer.kt    WeSpeaker cosine-similarity implementation
│           ├── models/
│           │   └── ModelAssetManager.kt        Copies assets → filesDir; stale-cache detection
│           ├── settings/
│           │   ├── AppSettings.kt              ThemeMode, AsrBackend enums, DataStore keys, LANGUAGE_OPTIONS
│           │   ├── SettingsRepository.kt       DataStore Flows + suspend setters + sync getters
│           │   └── SettingsViewModel.kt        SettingsUiState StateFlow + set*() actions
│           └── ui/
│               ├── AppStrings.kt               AppStrings data class + stringsForLanguage()
│               ├── language/                   Per-language string files (Strings_de.kt, …)
│               │   ├── Strings_de.kt  Strings_en.kt  Strings_fr.kt  Strings_es.kt
│               │   ├── Strings_it.kt  Strings_pt.kt  Strings_tr.kt  Strings_nl.kt
│               │   ├── Strings_pl.kt  Strings_ru.kt  Strings_zh.kt  Strings_ja.kt
│               │   ├── Strings_ko.kt  Strings_ar.kt
│               ├── LiveScreen.kt               Main screen composable
│               ├── SaveTranscriptUtils.kt      Format converters + share-sheet
│               ├── SettingsScreen.kt           Settings screen composable
│               └── theme/
│                   ├── DesignTokens.kt         ← SINGLE SOURCE OF TRUTH for all UI values
│                   ├── Color.kt                Re-exports from DesignTokens
│                   ├── Theme.kt                MaterialTheme wiring
│                   └── Type.kt                 Typography scale
├── gradle/
│   └── libs.versions.toml           Version catalog
├── README.md
└── CLAUDE.md                        (this file)
```

---

## 3. Audio Architecture — Key Design Decisions

### ASR backend auto-selection
The ASR backend is **not user-configurable**. It is selected automatically at service start:
- If `SpeechRecognizer.isRecognitionAvailable(context)` returns true → **Google Speech**
- Otherwise → **Whisper** (fully offline ONNX)

### Why no NoiseSuppressor / AutomaticGainControl?
DEX analysis of Google Live Transcribe (`com.google.audio.hearing.visualization.accessibility.scribe`)
shows it uses raw `AudioRecord` with `VOICE_RECOGNITION` source and **zero AudioEffects**.
Our previous implementation attached `NoiseSuppressor` and `AutomaticGainControl`, which treat
loudspeaker output as "noise/echo" and actively suppress it — preventing transcription of
audio from videos or external speakers. Removing these effects and using `UNPROCESSED` source
mirrors Live Transcribe's approach.

### System audio capture (video/media mode)
Android 10+ `AudioPlaybackCaptureConfiguration` API allows capturing the device's media output
stream (videos, music, games) directly, without using the microphone.

- Activated via the 📹 button in `LiveScreen` → triggers a system "Screen Record" permission dialog.
- `MediaProjection` token is passed from `MainActivity` to `AudioRecorderService.setMediaProjection()`.
- In this mode, Whisper is always used (SpeechRecognizer cannot accept injected audio).
- Captures `USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN` audio attributes.
- Requires `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission + `mediaProjection` foreground service type.

---

## 3b. Key Files — What They Do

### `DesignTokens.kt`
**Edit this file to restyle the UI.** It contains `object AppTheme` with nested objects:
- `AppTheme.DarkColors` / `AppTheme.LightColors` — typed `AppColorPalette` instances
- `AppTheme.Gradients` — background gradient brushes
- `AppTheme.Dimens` — all `dp` sizes (buttons, padding, waveform, bubbles, …)
- `AppTheme.Animation` — timing constants
- `AppTheme.Shapes` — corner radii for cards, buttons, bubbles, dropdowns
- `AppTheme.TextSize` — typography scale in `sp`

Every composable reads from here. No hardcoded colour or size literals exist in screen files.

### `LiveScreen.kt`
Main screen. Contains:
- `TranscriptEntry` data class — `speakerId: Int, text: String, timestamp: Long`
- `LiveScreen()` composable — stateless; all state is passed as parameters
- `GradientBackground()` — full-screen theme-aware gradient wrapper
- `LiveTopAppBar()` — title bar + settings icon
- `LanguageSelector()` — `ExposedDropdownMenuBox` language picker
- `WaveformVisualizer()` — Canvas-based animated bar visualiser (currently simulated)
- `RecordingFab()` — pulsing record/stop button
- `TranscriptBubble()` — per-entry chat bubble with speaker colour
- `SaveTranscriptDialog()` — format picker (TXT / CSV / JSON / SRT)

**State flow:** everything is passed as parameters; no global state.

### `AndroidSpeechEngine.kt`
Wraps Android `SpeechRecognizer` for continuous speech recognition:
- Auto-restarts after each result for continuous mode.
- Provides partial results during speech for live display.
- 3-stage language fallback on `ERROR_LANGUAGE_NOT_SUPPORTED`:
  configured language → device language → auto-detect.
- All `SpeechRecognizer` calls are marshalled to the main thread via `Handler`.
- Mutes `STREAM_MUSIC` and `STREAM_NOTIFICATION` briefly on each restart to suppress click sounds.

### `AudioRecorderService.kt`
Foreground service that owns the full audio pipeline.

**System audio capture mode (priority, if `MediaProjection` provided):**
```
AudioPlaybackCaptureConfiguration (USAGE_MEDIA + USAGE_GAME + USAGE_UNKNOWN)
    → AudioRecord (16 kHz PCM float, no mic)
    → RMS-based VAD
    → SherpaSpeakerDiarizer  → speakerId
    → SherpaOnnxAsrEngine    → text
    → onTranscriptionResult(speakerId, text)
```

**Whisper mode (mic, no Google SR):**
```
AudioRecord (UNPROCESSED source, 16 kHz PCM float, NO NoiseSuppressor/AGC)
    → RMS-based VAD (processAudioLoop)
    → SherpaSpeakerDiarizer  → speakerId
    → SherpaOnnxAsrEngine    → text
    → onTranscriptionResult(speakerId, text)
```

**Google Speech mode (mic, Google SR available):**
```
AndroidSpeechEngine (SpeechRecognizer manages mic)
    → onResult   → onTranscriptionResult(0, text)
    → onPartial  → onPartialResult(0, text)
```
Diarization is not available in Google Speech mode (all results → speaker 0).

VAD thresholds (in `processAudioLoop`):
- `voiceThreshold = 0.0005f` — raise if too much background noise is transcribed
- `silenceThreshold = 0.0002f`
- `maxSilenceFrames = 8` (800 ms silence ends a segment)

### `SherpaSpeakerDiarizer.kt`
WeSpeaker-based speaker embedding engine:
1. Extracts a d-vector embedding from audio.
2. Finds the best cosine-similarity match above `SIMILARITY_THRESHOLD = 0.55`.
3. Registers a new speaker if no match found.
4. Updates the running-average embedding (up to `MAX_EMBEDDING_SAMPLES = 8` samples).

### `SherpaOnnxAsrEngine.kt`
Whisper Tiny ONNX engine. Calls `cleanTranscription()` to strip noise tokens
(`[BLANK_AUDIO]`, `(silence)`, `<|nospeech|>`, etc.) before returning results.

### `ModelAssetManager.kt`
Copies all four model files from APK assets to `filesDir` on first launch.
Uses asset file-size comparison for stale-cache detection — only re-copies if sizes differ.
Call `prepareModels(context)` once before initialising any engine.
Call `allModelsReady(context)` to gate UI state without triggering a copy.

### `SettingsViewModel.kt`
```kotlin
data class SettingsUiState(
    val themeMode: ThemeMode,           // LIGHT / DARK / SYSTEM
    val autoScroll: Boolean,            // auto-scroll transcript list
    val showTimestamps: Boolean,        // show timestamps in bubbles
    val transcriptionLanguage: String,  // Whisper/Google language code, "" = auto
    val asrBackend: AsrBackend,         // GOOGLE_SPEECH / WHISPER
)
```
Exposed as `StateFlow<SettingsUiState>` via `combine()` over 5 DataStore flows.
Actions: `setTheme()`, `setAutoScroll()`, `setShowTimestamps()`, `setLanguage()`, `setAsrBackend()`.

### `AppStrings.kt`
Defines `AppStrings` data class (all visible UI strings in one typed holder) and
`stringsForLanguage(code)` which maps Whisper language codes to string instances.
`LocalStrings` is a `CompositionLocal` that makes strings available throughout the tree.

Language string files live in `ui/language/Strings_XX.kt` (one file per language).

### `SaveTranscriptUtils.kt`
```kotlin
enum class SaveFormat(val ext: String)   // TXT, CSV, JSON, SRT
fun transcriptToText(...)
fun transcriptToCsv(...)
fun transcriptToJson(...)
fun transcriptToSrt(...)
fun shareTranscript(context, entries, format)   // Intent.ACTION_SEND (no FileProvider needed)
```

---

## 4. Data Flow Diagram

```
Microphone
    │
    ▼
AudioRecorderService (Foreground)
    │
    ├─[Whisper mode]──────────────────────────────────────────────────────┐
    │   AudioRecord (16 kHz PCM float)                                    │
    │   → NoiseSuppressor + AGC                                           │
    │   → RMS VAD (processAudioLoop)                                      │
    │   → SherpaSpeakerDiarizer → speakerId                               │
    │   → SherpaOnnxAsrEngine   → text                                    │
    │   → onTranscriptionResult(speakerId, text)                          │
    │                                                                      │
    └─[Google Speech mode]────────────────────────────────────────────────┘
        AndroidSpeechEngine (SpeechRecognizer manages mic)
        → onResult / onPartialResult
        → onTranscriptionResult(0, text) / onPartialResult(0, text)
                │
                ▼
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

Uses Jetpack DataStore (Preferences). Keys defined in `PreferenceKeys` (`AppSettings.kt`):

| Key | Type | Default |
|---|---|---|
| `theme_mode` | String (`ThemeMode.name`) | `SYSTEM` |
| `auto_scroll` | Boolean | `true` |
| `show_timestamps` | Boolean | `false` |
| `transcription_language` | String | `"de"` |
| `asr_backend` | String (`AsrBackend.name`) | `GOOGLE_SPEECH` |

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

The UI language follows the selected transcription language (set in settings).
`stringsForLanguage(code)` maps language codes to the corresponding string objects.

Currently supported UI languages (all have a file in `ui/language/`):

| Code | Language | File |
|------|----------|------|
| `de` | German | `Strings_de.kt` |
| `en` | English | `Strings_en.kt` |
| `fr` | French | `Strings_fr.kt` |
| `es` | Spanish | `Strings_es.kt` |
| `it` | Italian | `Strings_it.kt` |
| `pt` | Portuguese | `Strings_pt.kt` |
| `tr` | Turkish | `Strings_tr.kt` |
| `nl` | Dutch | `Strings_nl.kt` |
| `pl` | Polish | `Strings_pl.kt` |
| `ru` | Russian | `Strings_ru.kt` |
| `zh` | Chinese | `Strings_zh.kt` |
| `ja` | Japanese | `Strings_ja.kt` |
| `ko` | Korean | `Strings_ko.kt` |
| `ar` | Arabic | `Strings_ar.kt` |
| `""` | Auto → device locale | (resolved at runtime) |

**To add a new UI language:**
1. Create `ui/language/Strings_XX.kt` with `val XxStrings = AppStrings(...)`
2. Add an import and `"xx" -> XxStrings` branch in `stringsForLanguage()` in `AppStrings.kt`
3. Add `"xx" to "Display Name"` to `LANGUAGE_OPTIONS` in `AppSettings.kt`
4. Add the flag emoji to `LANG_FLAG` in `LiveScreen.kt`
5. No other files need touching.

---

## 8. UI Customisation Guide

**To change colours / spacing / animation:**
Edit `DesignTokens.kt` only — no other file. Key objects:
- `AppTheme.DarkColors` / `AppTheme.LightColors` — colour palettes (`AppColorPalette`)
- `AppTheme.Gradients.dark` / `.light` — background gradients
- `AppTheme.Dimens.*` — all sizes in `dp`
- `AppTheme.Animation.*` — timing in `ms`
- `AppTheme.Shapes.*` — corner radii

**To add a new speaker colour:**
Append to both `AppTheme.DarkColors.speakers` and `AppTheme.LightColors.speakers`.

**To change the waveform:**
- Bar count: `AppTheme.Dimens.waveformBars`
- Refresh rate: `AppTheme.Animation.waveformTickMs`
- Bar colour: `AppTheme.DarkColors.accentCyan` / `AppTheme.LightColors.accentCyan`
- Canvas drawing: `LiveScreen.kt` → `WaveformVisualizer()` composable

> ⚠️ The waveform is currently **simulated** (random heights, not real RMS data).
> See "Known Limitations" below.

---

## 9. Known Limitations & Technical Debt

| Issue | Location | Suggested Fix |
|---|---|---|
| **Waveform is simulated** | `LiveScreen.kt` `LaunchedEffect` | Expose `val rms: StateFlow<Float>` from `AudioRecorderService`; pass to `LiveScreen` |
| **No diarization in Google Speech mode** | `AudioRecorderService.kt` | `SpeechRecognizer` holds the mic exclusively — parallel `AudioRecord` would cause conflict |
| **No error handling on ASR failure** | `SherpaOnnxAsrEngine.kt` | Expose an error state / callback to the UI |
| **Model files not bundled in git** | `app/src/main/assets/models/` | Add a model-download screen or `WorkManager` job |
| **Whisper Tiny only** | `SherpaOnnxAsrEngine.kt` | Add `modelSize` param and settings toggle (Tiny / Small / Medium) |
| **No landscape layout** | `LiveScreen.kt` | Add `WindowSizeClass` checks |
| **Service lifecycle on task remove** | `AudioRecorderService.kt` | Implement `onTaskRemoved` for proper cleanup |
| **Direct push to main blocked** | git | The git proxy only allows pushes to `claude/` branches; merge via Gitea UI |

---

## 10. Current Git State

```
Branch: claude/livetranscript-android-diarization-JoYix
Remote: origin/claude/livetranscript-android-diarization-JoYix

origin/main ← waiting for PR merge from the feature branch
```

Recent commits on the feature branch:
```
9070d43  fix+feat: UI tweaks, language localisation, continuous recording improvements
e2258de  feat: complete visual redesign — gradient themes, new composables, theme-aware UI
afe84f7  fix: add 3-stage language fallback for SpeechRecognizer error 12
b0be991  fix: resolve mic conflict and SpeechRecognizer restart loop
68ccd0d  feat: hybrid ASR with Android SpeechRecognizer + partial results
```

To land changes on `main`: create a Pull Request on the Gitea web UI from
`claude/livetranscript-android-diarization-JoYix` → `main`.

---

## 11. Next Steps (Prioritised)

1. **Real waveform** — Expose `val rms: StateFlow<Float>` from `AudioRecorderService`;
   connect in `MainActivity`; replace the random waveform `LaunchedEffect` in `LiveScreen`.

2. **Diarization in Google Speech mode** — Run a separate `AudioRecord` + `diarizerOnlyLoop()`
   alongside `AndroidSpeechEngine` to assign real speaker IDs (needs mic sharing investigation).

3. **Model downloader** — Add an in-app screen or `WorkManager` job that downloads
   the Whisper ONNX files on first launch with progress feedback.

4. **Larger Whisper models** — Add a settings toggle (Tiny / Small / Medium).
   `SherpaOnnxAsrEngine` already accepts a `language` param; extend it to accept
   `modelSize` and adjust file paths in `ModelAssetManager.REQUIRED_MODELS`.

5. **Background recording controls** — Add Pause / Stop notification action buttons;
   implement `onTaskRemoved` in `AudioRecorderService`.

6. **Error states** — Show a snackbar / dialog when ASR fails to initialise (missing
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

> Direct push to `main` will fail with HTTP 403 (protected branch).
> Use a PR via the Gitea web UI.

---

## 13. Important Patterns & Conventions

- **No hardcoded colour literals** in screen files — always use `AppTheme.DarkColors.*` / `AppTheme.LightColors.*`
- **No hardcoded string literals** in screen files — always use `LocalStrings.current.*`
- **`TranscriptEntry`** is defined in `LiveScreen.kt` — move to a `model/` package if it grows
- **`SettingsViewModel.Factory`** uses manual DI — no Hilt/Koin
- **Coroutine scope in Service** uses `SupervisorJob` so one failure doesn't cancel all
- **`asrMutex`** in `AudioRecorderService` prevents concurrent Whisper inference calls
- **`LaunchedEffect(isRecording)`** in `LiveScreen` drives the waveform animation loop;
  `isActive` check ensures proper cancellation on recomposition
- **`menuAnchor(MenuAnchorType.PrimaryNotEditable)`** on `OutlinedTextField` inside
  `ExposedDropdownMenuBox` — required for Material 3 dropdown; no type parameter for API compatibility
- **`AudioRecord` format** is `ENCODING_PCM_FLOAT` (not 16-bit) — sherpa-onnx and the
  diarizer both expect `FloatArray` samples in range [-1.0, 1.0]
