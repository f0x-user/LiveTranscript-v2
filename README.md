# LiveTranscript

[![Android CI](https://github.com/f0x-user/LiveTranscript-v2/actions/workflows/android.yml/badge.svg)](https://github.com/f0x-user/LiveTranscript-v2/actions/workflows/android.yml)

Real-time speech transcription and speaker diarization on Android, running fully **on-device** — no internet required at runtime.

---

## Features

| Feature | Details |
|---|---|
| **On-device ASR** | Whisper Tiny (multilingual) via sherpa-onnx |
| **Speaker diarization** | WeSpeaker embedding model distinguishes up to 6 speakers |
| **15 languages** | DE, EN, FR, ES, IT, PT, TR, NL, PL, RU, ZH, JA, KO, AR + auto-detect |
| **App language** | UI switches to English when English is selected; German otherwise |
| **Save transcript** | Export as TXT · CSV · JSON · SRT via Android share-sheet |
| **Dark UI** | Navy-blue gradient, animated waveform, red recording FAB with pulse rings |
| **Settings** | Dark / Light / System theme, auto-scroll, timestamps |
| **Edge-to-edge** | Gradient fills the full screen including behind the status bar |

---

## Architecture

```
com.livetranscript
├── MainActivity              Entry point, service binding, Compose root
│
├── audio/
│   └── AudioRecorderService  Foreground service: mic → VAD → ASR → callback
│
├── asr/
│   ├── AsrEngine             Interface: initialize / transcribe / release
│   └── SherpaOnnxAsrEngine   Whisper Tiny via sherpa-onnx AAR
│
├── diarization/
│   ├── SpeakerDiarizer       Interface
│   └── SherpaSpeakerDiarizer WeSpeaker embedding model via sherpa-onnx
│
├── models/
│   └── ModelAssetManager     Copies bundled ONNX models to internal storage on first run
│
├── settings/
│   ├── AppSettings           ThemeMode enum, LANGUAGE_OPTIONS list, DataStore preference keys
│   ├── SettingsRepository    Flows for each setting + suspend setters
│   └── SettingsViewModel     Combines flows → SettingsUiState; exposes set*() actions
│
└── ui/
    ├── AppStrings            Localised string sets (DE / EN) + LocalStrings CompositionLocal
    ├── LiveScreen            Main recording screen
    ├── SaveTranscriptUtils   Format converters (TXT/CSV/JSON/SRT) + Android share-sheet
    ├── SettingsScreen        Theme + transcript toggles
    └── theme/
        ├── DesignTokens      ← Edit here to restyle the whole app
        ├── Theme             MaterialTheme wiring (reads from DesignTokens)
        ├── Color             Re-exports from DesignTokens
        └── Type              Typography scale
```

**Pattern:** MVVM with a thin repository layer.
`AudioRecorderService` runs in the foreground and delivers results via a callback set by `MainActivity`. `SettingsViewModel` exposes a single `StateFlow<SettingsUiState>` produced by `combine()`. All Compose UI is stateless — state flows down, events flow up.

---

## UI Customisation

All visual design decisions live in **one file**:

```
app/src/main/kotlin/com/livetranscript/ui/theme/DesignTokens.kt
```

Edit `AppTheme.Colors`, `AppTheme.Gradients`, `AppTheme.Dimens`, or `AppTheme.Animation` — every screen reads from these tokens so changes propagate automatically.

| Token | Default | Effect |
|---|---|---|
| `AppTheme.Colors.bgDeep/Mid/Light` | `#0A1929 / #132B45 / #1A3456` | Background gradient stops |
| `AppTheme.Colors.recordActive` | `#EF4444` | Recording FAB colour |
| `AppTheme.Colors.accentCyan` | `#4FC3F7` | Waveform bars, focused borders |
| `AppTheme.Colors.speakers` | 6 colours | Per-speaker label colours |
| `AppTheme.Dimens.fabSize` | `68.dp` | Record button diameter |
| `AppTheme.Dimens.waveformBars` | `36` | Number of waveform bars |
| `AppTheme.Animation.waveformTickMs` | `110L` | Waveform refresh rate (ms) |
| `AppTheme.Animation.pulseDurationMs` | `950` | FAB pulse ring cycle (ms) |

---

## Tech Stack

| Component | Library / Version |
|---|---|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose (BOM 2024.08.00) + Material 3 |
| ASR engine | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.12.28 (AAR) |
| ASR model | Whisper Tiny multilingual (ONNX int8 quantised) |
| Diarization | WeSpeaker embedding model via sherpa-onnx |
| Persistence | Jetpack DataStore (Preferences) |
| Async | Kotlin Coroutines + Flow |
| DI | Manual (Factory pattern in ViewModel) |
| Min SDK | 31 (Android 12) |
| Target SDK | 35 (Android 15) |
| Build | AGP 8.4.1, Gradle version catalog (`libs.versions.toml`) |

---

## Build Requirements

| Requirement | Version |
|---|---|
| JDK | 17+ |
| Android Studio | Iguana (2023.2)+ **or** command-line only |
| Android SDK | API 35 compile / API 31 minimum |
| Internet | Required at build time to download the sherpa-onnx AAR |

### Clone & Build

```bash
# 1. Clone
git clone https://github.com/f0x-user/LiveTranscript-v2.git
cd LiveTranscript-v2

# 2. Build (Gradle auto-downloads the sherpa-onnx AAR ~120 MB on first run)
./gradlew assembleDebug

# 3. Install on a connected device / emulator
./gradlew installDebug
```

> **First-run note:** Whisper model files must be placed in
> `app/src/main/assets/models/whisper-tiny/` before the APK is built.
> See [Adding Models](#adding-models).

### Android Studio

1. Open the project root in Android Studio.
2. Wait for Gradle sync (AAR is downloaded automatically).
3. Press **Shift+F10** or click **Run**.

### Release Build

```bash
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=keystore.jks \
  -Pandroid.injected.signing.store.password=<pass> \
  -Pandroid.injected.signing.key.alias=<alias> \
  -Pandroid.injected.signing.key.password=<pass>
```

---

## Adding Models

Place ONNX model files in the assets directory before building:

```
app/src/main/assets/models/
└── whisper-tiny/
    ├── tiny-encoder.int8.onnx   (~30 MB)
    ├── tiny-decoder.int8.onnx   (~80 MB)
    └── tiny-tokens.txt
```

Download multilingual Whisper Tiny ONNX models from the
[sherpa-onnx releases page](https://github.com/k2-fsa/sherpa-onnx/releases).

The WeSpeaker diarization model filenames are defined in `ModelAssetManager.kt`.

---

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Microphone access |
| `FOREGROUND_SERVICE` | Keep recording service alive when backgrounded |
| `POST_NOTIFICATIONS` | Foreground-service notification (Android 13+) |

---

## Saving Transcripts

Tap the 💾 icon (top-right of main screen, enabled after first transcript entry):

| Format | Use case |
|---|---|
| **TXT** | Plain text with speaker labels — quick copy-paste |
| **CSV** | Import into Excel / Google Sheets |
| **JSON** | Programmatic processing (timestamp, speakerId, text) |
| **SRT** | Add subtitles to a video in any editor |

Files are shared via the Android share-sheet (save to Files, send via email, etc.).

---

## Project Status

- [x] On-device Whisper ASR (Tiny multilingual)
- [x] Speaker diarization (WeSpeaker)
- [x] 15-language dropdown + auto-detect
- [x] App UI language follows selected language (DE / EN)
- [x] Dark navy UI with animated waveform
- [x] Save transcript (TXT / CSV / JSON / SRT)
- [x] Settings screen (theme, auto-scroll, timestamps)
- [ ] Larger Whisper models (Small / Medium) for better accuracy
- [ ] Real-time RMS-based waveform (currently simulated random)
- [ ] Background transcription with notification controls
- [ ] Custom vocabulary / hotword support
- [ ] Tablet / landscape layout

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
sherpa-onnx: Apache 2.0 · Whisper: MIT (OpenAI)
