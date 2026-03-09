# LiveTranscript — Entwicklungsfortschritt

Letzte Aktualisierung: 08. März 2026

---

## Aktueller Stand

**Branch:** `claude/livetranscript-android-diarization-JoYix`
**Meilenstein:** M6 abgeschlossen — App startet stabil, Transkription und Diarisierung laufen

---

## Meilensteine

- [x] **M1: Repo & CI** — Projekt-Struktur angelegt, CI-Workflow konfiguriert
- [x] **M2: App startet** — APK installierbar, kein Crash auf Emulator und echtem Gerät
- [x] **M3: Modelle laden** — ModelAssetManager kopiert alle 4 Dateien korrekt; Größenvergleich erkennt veraltete Caches
- [x] **M4: Aufnahme** — AudioRecorderService läuft, Foreground-Notification erscheint
- [x] **M5: Transkription** — Whisper Tiny transkribiert Audio; Google Speech als Standard-Backend mit Partial Results
- [x] **M6: Diarization** — WeSpeaker erkennt und trennt bis zu 6 Sprecher (nur Whisper-Modus)
- [ ] **M7: Stabil** — 5 Minuten ohne Crash, Stop-Button, vollständige History
- [ ] **M8: Real Waveform** — Echte RMS-Daten statt simulierter Zufallswerte
- [ ] **M9: Landscape / Tablet** — WindowSizeClass Adaptive Layouts
- [ ] **M10: Background Controls** — Notification-Buttons für Pause/Stop

---

## Architektur (aktuell)

```
app/
├── App.kt                         — Application-Klasse (minimal)
├── MainActivity.kt                — Einstiegspunkt, Permission-Handling, Service-Binding
├── models/
│   └── ModelAssetManager.kt       — Kopiert ONNX-Modelle; Größenvergleich gegen Asset
├── audio/
│   └── AudioRecorderService.kt    — Foreground Service: mic → VAD → Diarizer → ASR
├── asr/
│   ├── AsrEngine.kt               — Interface: initialize / transcribe / release
│   ├── SherpaOnnxAsrEngine.kt     — Whisper Tiny via sherpa-onnx
│   └── AndroidSpeechEngine.kt     — Google SpeechRecognizer, 3-stufiger Language-Fallback
├── diarization/
│   ├── SpeakerDiarizer.kt         — Interface: initialize / identifySpeaker / reset / release
│   └── SherpaSpeakerDiarizer.kt   — WeSpeaker ResNet34-LM, Cosine-Similarity Matching
├── settings/
│   ├── AppSettings.kt             — ThemeMode, AsrBackend Enums, DataStore-Keys, LANGUAGE_OPTIONS
│   ├── SettingsRepository.kt      — Flows + suspend Setter + synchrone Getter (runBlocking)
│   └── SettingsViewModel.kt       — combine() → SettingsUiState; Factory-DI
└── ui/
    ├── AppStrings.kt              — Lokalisierung: DE / EN / FR / ES + CompositionLocal
    ├── LiveScreen.kt              — Haupt-Screen (845 Zeilen)
    ├── SaveTranscriptUtils.kt     — TXT / CSV / JSON / SRT Konverter
    ├── SettingsScreen.kt          — Theme, Transcript, ASR-Backend Einstellungen
    └── theme/
        ├── DesignTokens.kt        — SINGLE SOURCE OF TRUTH für alle UI-Werte
        ├── Theme.kt               — MaterialTheme Wrapper
        ├── Color.kt               — Re-Exports (Legacy)
        └── Type.kt                — Typografie-Skala
```

---

## Technische Details

| Eigenschaft | Wert |
|---|---|
| minSdk | 31 (Android 12) |
| targetSdk | 35 (Android 15) |
| compileSdk | 35 |
| AGP | 8.4.1 |
| Kotlin | 2.0.0 (K2-Compiler) |
| Compose BOM | 2024.10.00 |
| sherpa-onnx | 1.12.28 (AAR, arm64-v8a + x86_64 im Debug) |
| ONNX Runtime (gebündelt) | 1.16.x — max. ONNX IR Version 9 |
| ASR-Modell (Offline) | Whisper Tiny multilingual (INT8, ~99 MB gesamt) |
| Diarisierungsmodell | WeSpeaker ResNet34-LM (26.5 MB, ONNX IR v7-8) |
| Diarization-Schwellwert | Cosine-Similarity ≥ 0.55 |
| Audio-Sample-Rate | 16.000 Hz, mono, PCM float |
| VAD Chunk-Größe | 1.600 Samples = 100 ms |
| VAD Akkumulator | min. 0,5 s — max. 2 s |

---

## Behobene Bugs (chronologisch)

### Session: 08. März 2026

#### Bug 1: App-Crash auf x86_64-Emulator (`UnsatisfiedLinkError`)
- **Ursache:** `abiFilters += "arm64-v8a"` in `defaultConfig.ndk` schloss x86_64 aus.
  Der Pixel 8 Pro Emulator läuft auf x86_64 — die sherpa-onnx JNI-Library konnte nicht geladen werden.
- **Symptom:** App öffnet sich und schließt sich sofort; Debug-Session trennt sich unmittelbar.
- **Fix:** `x86_64` wird im `debug`-BuildType zu den ABI-Filtern hinzugefügt.
  Release bleibt `arm64-v8a`-only für kleinere APK-Größe.
- **Datei:** `app/build.gradle.kts`

#### Bug 2: App-Crash durch inkompatibles WeSpeaker-Modell (`SIGABRT / Ort::Exception`)
- **Ursache:** Das vorhandene `app/models/wespeaker/model.onnx` (26.535.549 Bytes) wurde
  mit ONNX IR Version 10 exportiert. Das in sherpa-onnx 1.12.28 gebündelte ONNX Runtime
  unterstützt maximal IR Version 9. Der C++-Fehler (`Ort::Exception`) propagiert als
  `SIGABRT` — nicht abfangbar durch Kotlin `try/catch`.
- **Symptom:** Crash nach ~600 ms beim Start; Logcat zeigt:
  `Unsupported model IR version: 10, max supported IR version: 9`
- **Fix A:** Neuer Gradle-Task `downloadWespeakerModel` lädt das offizielle
  sherpa-onnx-kompatible Modell `wespeaker_en_voxceleb_resnet34_LM.onnx` herunter.
  Inkompatibles Modell wird anhand der bekannten Dateigröße erkannt und ersetzt.
- **Fix B:** `ModelAssetManager.copyAssetIfNeeded()` vergleicht jetzt die Dateigröße
  der gecachten Datei mit der Asset-Größe — bei Abweichung wird die Datei in `filesDir`
  neu kopiert. Verhindert, dass veraltete Modelle nach APK-Updates bestehen bleiben.
- **Dateien:** `app/build.gradle.kts`, `app/src/main/kotlin/.../models/ModelAssetManager.kt`

---

## Ablauf: Model-Loading (aktuell)

1. **Build-Zeit:**
   - `downloadSherpaOnnxAAR` lädt `sherpa-onnx-1.12.28.aar` (~120 MB) herunter (falls fehlt)
   - `downloadWespeakerModel` lädt `wespeaker_en_voxceleb_resnet34_LM.onnx` (~26 MB) herunter
     oder ersetzt inkompatible Versionen (erkannt über Dateigröße 26.535.549 Bytes)

2. **App-Start:**
   - `AudioRecorderService.onCreate()` → `ModelAssetManager.prepareModels()` (synchron)
   - Für jede Modelldatei: Asset-Größe vs. filesDir-Größe vergleichen
   - Wenn unterschiedlich oder fehlend: Asset nach filesDir kopieren
   - Erst nach vollständiger Vorbereitung: sherpa-onnx initialisieren

3. **Diarizer-Initialisierung:**
   - `SherpaSpeakerDiarizer.initialize()` → `SpeakerEmbeddingExtractor(config)`
   - WeSpeaker-Modell aus filesDir laden
   - Bei Erfolg: `isInitialized = true`

4. **ASR-Initialisierung:**
   - Backend-abhängig (aus DataStore geladen via `runBlocking`)
   - **GOOGLE_SPEECH:** `AndroidSpeechEngine` erstellen (kein Modell nötig)
   - **WHISPER:** `SherpaOnnxAsrEngine` mit Whisper-Tiny-Modellen initialisieren

---

## Bekannte Technische Schulden

| # | Problem | Datei | Schwere | Status |
|---|---|---|---|---|
| 1 | Waveform verwendet `Random.nextFloat()` statt echten RMS-Daten | `LiveScreen.kt` | Mittel | Offen |
| 2 | `diarizerOnlyLoop()` ist Dead Code — niemals aufgerufen | `AudioRecorderService.kt` | Niedrig | Offen |
| 3 | `runBlocking` in `SettingsRepository` kann ANR verursachen | `SettingsRepository.kt` | Mittel | Offen |
| 4 | Keine Error-States in der UI (fehlende Modelle, ASR-Fehler) | `LiveScreen.kt` | Mittel | Offen |
| 5 | Model-Ready-Check via Handler-Loop statt Callback | `MainActivity.kt` | Niedrig | Offen |
| 6 | `onTaskRemoved()` nicht implementiert im Service | `AudioRecorderService.kt` | Niedrig | Offen |
| 7 | Keine Diarisierung im Google-Speech-Modus | `AudioRecorderService.kt` | Niedrig | By Design |
| 8 | `LiveScreen.kt` mit 845 Zeilen zu groß (Refactoring nötig) | `LiveScreen.kt` | Niedrig | Offen |
| 9 | Keine automatisierten Tests vorhanden | — | Hoch | Offen |

---

## Offene Aufgaben (priorisiert)

1. **Echte Waveform** — RMS `StateFlow<Float>` aus `AudioRecorderService` exponieren;
   in `MainActivity` weiterleiten; in `LiveScreen` als Parameter entgegennehmen;
   `WaveformVisualizer` mit realen Werten befüllen.

2. **Modell-Download-Screen** — In-App-Wizard für Whisper-Modelle (WorkManager + Fortschrittsanzeige)

3. **Größere Whisper-Modelle** — Settings-Toggle (Tiny / Small / Medium);
   `SherpaOnnxAsrEngine` akzeptiert bereits `language`, `modelSize`-Parameter analog hinzufügen;
   `ModelAssetManager` um weitere Modellpfade erweitern.

4. **Error-States** — `StateFlow<AsrError?>` in `AudioRecorderService`;
   Snackbar/Dialog in `LiveScreen` bei fehlenden Modellen oder ASR-Fehler.

5. **Background Recording** — `onTaskRemoved()` implementieren; Notification-Action-Buttons
   für Pause/Stop hinzufügen.

6. **Dead Code entfernen** — `diarizerOnlyLoop()` entweder implementieren oder löschen.

7. **Landscape / Tablet** — `WindowSizeClass`-Checks in `LiveScreen.kt` einbauen.

8. **Tests** — Unit-Tests für `SherpaSpeakerDiarizer`, `SherpaOnnxAsrEngine`;
   Composable-Tests für `LiveScreen`, `SettingsScreen`.

---

## Datenfluss (End-to-End)

```
User tippt FAB (Start)
        │
        ▼
MainActivity.startRecording()
        │
        ▼
AudioRecorderService.startRecording()
        │
        ├─── GOOGLE_SPEECH ─────────────────────────────────────────────────────┐
        │    AndroidSpeechEngine.start()                                        │
        │    SpeechRecognizer → onPartialResults → onPartialResult Callback      │
        │    SpeechRecognizer → onResults        → onTranscriptionResult Callback│
        │                                                                       │
        └─── WHISPER ────────────────────────────────────────────────────────┐  │
             AudioRecord.startRecording() (16kHz, mono, float)              │  │
             NoiseSuppressor + AGC aktiviert                                │  │
             serviceScope.launch { processAudioLoop() }                     │  │
                     │                                                      │  │
                     ▼ (alle 100ms)                                          │  │
             RMS-VAD: Voice/Silence Detection                                │  │
                     │ nach 500ms Stille oder 2s                             │  │
                     ▼                                                      │  │
             SherpaSpeakerDiarizer.identifySpeaker(samples)                 │  │
                     │ (Cosine-Similarity, Schwellwert 0.55)                 │  │
                     ▼                                                      │  │
             SherpaOnnxAsrEngine.transcribe(samples)                        │  │
                     │ (Whisper Tiny Inference + Noise-Token-Filter)         │  │
                     ▼                                                      │  │
             onTranscriptionResult(speakerId, text) ◄───────────────────────┘  │
                                                    ◄──────────────────────────┘
        │
        ▼
MainActivity.runOnUiThread {
    transcripts.add(TranscriptEntry(speakerId, text))
}
        │
        ▼
LiveScreen Recomposition
TranscriptBubble mit Sprecher-Farbe + Text
```

---

## CI/CD

GitHub Actions: `.github/workflows/android.yml`
- Lädt sherpa-onnx AAR von GitHub Releases herunter
- Lädt Whisper Tiny Modell herunter
- Lädt WeSpeaker Modell herunter
- Baut Debug-APK
- Lädt APK als Artifact hoch (14 Tage)

---

## Git-Workflow

```bash
# Aktiver Feature-Branch
git checkout claude/livetranscript-android-diarization-JoYix

# Bauen und prüfen
./gradlew assembleDebug

# Pushen
git push -u origin claude/livetranscript-android-diarization-JoYix
```

> Direkter Push auf `main` schlägt mit HTTP 403 fehl (Protected Branch).
> Merge via Pull Request auf der Gitea Web-UI.
