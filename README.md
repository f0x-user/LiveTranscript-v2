# LiveTranscript v2

**Offline Android speech-to-text with speaker diarization. No internet required. Android 12+.**

[![Android CI](https://github.com/f0x-user/LiveTranscript-v2/actions/workflows/android.yml/badge.svg)](https://github.com/f0x-user/LiveTranscript-v2/actions/workflows/android.yml)

## Features

- **100% Offline** — All processing on-device, no data leaves your phone
- **Real-time Transcription** — Powered by Whisper tiny.en via sherpa-onnx
- **Speaker Diarization** — Identifies different speakers using WeSpeaker ResNet34
- **Colored Speaker Labels** — Each speaker gets a distinct color
- **Android 12+** — Requires API 31, targets API 35

## Download APK

1. Go to [GitHub Actions](../../actions/workflows/android.yml)
2. Click the latest successful workflow run
3. Download the `app-debug` artifact

## Architecture

```
ASR Pipeline:
Microphone → AudioRecord (16kHz PCM float) → SherpaOnnxAsrEngine (Whisper tiny.en) → Text

Diarization:
Audio segments → SherpaSpeakerDiarizer (WeSpeaker ResNet34) → Speaker ID
```

## Models Used

| Model | Size | Purpose |
|-------|------|---------|
| Whisper tiny.en encoder (INT8) | ~18MB | Speech → Text |
| Whisper tiny.en decoder (INT8) | ~22MB | Speech → Text |
| WeSpeaker ResNet34 | ~30MB | Speaker identification |

## Technical Stack

- **Language**: Kotlin 2.0.0
- **UI**: Jetpack Compose + Material 3
- **ASR/Diarization**: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.12.10
- **Min SDK**: 31 (Android 12)
- **Architecture**: ABI arm64-v8a

## Building

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17

### Build steps

```bash
git clone https://github.com/f0x-user/LiveTranscript-v2.git
cd LiveTranscript-v2

# Gradle will automatically download sherpa-onnx AAR
./gradlew assembleDebug
```

## How it works

1. User grants RECORD_AUDIO permission
2. `AudioRecorderService` starts as a foreground service
3. `ModelAssetManager` copies all ONNX models from APK assets to `filesDir` (synchronous)
4. sherpa-onnx is initialized with the copied model paths
5. Audio is captured at 16kHz mono PCM float
6. Each audio segment is processed:
   - **Speaker ID** identified via WeSpeaker embedding + cosine similarity
   - **Transcription** via Whisper tiny.en
7. Results appear in real-time in the Compose UI

## License

MIT

---
*Built with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) by k2-fsa*
