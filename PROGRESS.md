# LiveTranscript2 Progress

## Status
Aktueller Meilenstein: M1 — Repo & CI in Bearbeitung

## Meilensteine
- [x] M1: Repo & CI — Projekt-Struktur angelegt, CI-Workflow konfiguriert
- [ ] M2: App startet — APK installierbar, kein Crash
- [ ] M3: Modelle laden — ModelAssetManager kopiert alle 4 Dateien
- [ ] M4: Aufnahme — AudioRecorderService läuft, Notification erscheint
- [ ] M5: Transkription — Whisper transkribiert Audio
- [ ] M6: Diarization — Sprecher werden erkannt und angezeigt
- [ ] M7: Stabil — 5 Minuten ohne Crash, Stop-Button, History

## Architektur

```
app/
├── App.kt                         — Application-Klasse
├── MainActivity.kt                — Einstiegspunkt, Permission-Handling
├── models/
│   └── ModelAssetManager.kt       — Kopiert ONNX-Modelle aus APK-Assets
├── audio/
│   └── AudioRecorderService.kt    — Foreground Service für Mikrofon-Aufnahme
├── asr/
│   ├── AsrEngine.kt               — Interface für ASR-Engines
│   └── SherpaOnnxAsrEngine.kt     — Whisper tiny.en via sherpa-onnx
├── diarization/
│   ├── SpeakerDiarizer.kt         — Interface für Speaker Diarization
│   └── SherpaSpeakerDiarizer.kt   — WeSpeaker via sherpa-onnx
└── ui/
    ├── LiveScreen.kt              — Compose UI: Transcript-Anzeige + Buttons
    └── theme/                     — Material3 Theme
```

## Technische Details
- **minSdk**: 31 (Android 12)
- **targetSdk**: 35
- **AGP**: 8.4.1
- **Kotlin**: 2.0.0
- **sherpa-onnx**: 1.12.10 (ARM64 AAR)
- **ASR-Modell**: Whisper tiny.en (INT8 quantisiert, ~40MB)
- **Diarization-Modell**: WeSpeaker ResNet34 (~30MB)

## Ablauf: Model-Loading
1. `AudioRecorderService.onCreate()` ruft `ModelAssetManager.prepareModels()` **synchron** auf
2. Alle 4 Modelldateien werden aus APK-Assets → filesDir kopiert
3. Erst dann wird sherpa-onnx initialisiert
4. Dann wird der Foreground Service gestartet

## Bekannte Probleme
- [ ] sherpa-onnx AAR wird in CI heruntergeladen (kein direkter Internetzugang in dev-Env)
- [ ] ONNX-Modelle werden in CI heruntergeladen (zu groß für Git)

## Letzter Commit
Initial commit — Vollständige Projekt-Struktur angelegt

## CI/CD
GitHub Actions: `.github/workflows/android.yml`
- Downloads sherpa-onnx AAR von GitHub Releases
- Downloads Whisper tiny.en Modell
- Downloads WeSpeaker Modell
- Baut Debug-APK
- Lädt APK als Artifact hoch (14 Tage)
