# LiveTranscript

[![Android CI](https://github.com/f0x-user/LiveTranscript-v2/actions/workflows/android.yml/badge.svg)](https://github.com/f0x-user/LiveTranscript-v2/actions/workflows/android.yml)

Real-time speech transcription and speaker diarization on Android, running fully **on-device** — no internet required at runtime.

---

## Features

| Feature | Details |
|---|---|
| **Hybrid ASR** | Google Speech Recognizer (default, mit Partial Results) oder Whisper Tiny via sherpa-onnx |
| **Speaker diarization** | WeSpeaker ResNet34-LM embedding model — bis zu 6 Sprecher erkennbar |
| **15 languages** | DE, EN, FR, ES, IT, PT, TR, NL, PL, RU, ZH, JA, KO, AR + Auto-Detect |
| **App-Lokalisierung** | UI wechselt auf EN, FR, ES oder DE je nach Sprachauswahl; für alle anderen Codes: Deutsch |
| **Partial Results** | Echtzeit-Vorschau der laufenden Transkription (Google-Speech-Modus) |
| **Transkript speichern** | Export als TXT · CSV · JSON · SRT via Android Share-Sheet |
| **Dark / Light Theme** | Navy-Blau (dark) oder Sky-Blau (light) Gradient, System-Theme-Unterstützung |
| **Waveform-Animation** | 36-Bar Visualizer mit Puls-FAB bei Aufnahme |
| **Settings** | Theme (Dark/Light/System), Auto-Scroll, Timestamps, ASR-Backend |
| **Edge-to-edge** | Gradient füllt den gesamten Bildschirm inkl. Status Bar |

---

## Architecture

```
com.livetranscript
├── App.kt                    Application-Klasse (minimal)
├── MainActivity.kt           Entry point, Permission-Handling, Service-Binding, Compose root
│
├── audio/
│   └── AudioRecorderService  Foreground Service: mic → VAD → Diarization → ASR → Callback
│
├── asr/
│   ├── AsrEngine             Interface: initialize / transcribe / release
│   ├── SherpaOnnxAsrEngine   Whisper Tiny via sherpa-onnx (offline)
│   └── AndroidSpeechEngine   Google SpeechRecognizer mit 3-stufigem Language-Fallback
│
├── diarization/
│   ├── SpeakerDiarizer       Interface: initialize / identifySpeaker / reset / release
│   └── SherpaSpeakerDiarizer WeSpeaker ResNet34-LM — Cosine-Similarity Matching
│
├── models/
│   └── ModelAssetManager     Kopiert ONNX-Modelle aus APK-Assets nach filesDir;
│                             erkennt veraltete/inkompatible Caches per Größenvergleich
│
├── settings/
│   ├── AppSettings           ThemeMode + AsrBackend Enums, LANGUAGE_OPTIONS, DataStore-Keys
│   ├── SettingsRepository    Flows für alle Einstellungen + suspend Setter + synchrone Getter
│   └── SettingsViewModel     combine() → SettingsUiState StateFlow; Factory-DI
│
└── ui/
    ├── AppStrings            Lokalisierte Strings (DE / EN / FR / ES) + CompositionLocal
    ├── LiveScreen            Haupt-Screen (845 Zeilen) mit Waveform, FAB, Transcript-Bubbles
    ├── SaveTranscriptUtils   Format-Konverter (TXT/CSV/JSON/SRT) + Share-Sheet Helper
    ├── SettingsScreen        Appearance, Transcript, ASR-Backend Einstellungen
    └── theme/
        ├── DesignTokens      ← SINGLE SOURCE OF TRUTH für alle UI-Werte
        ├── Theme             MaterialTheme Wrapper (liest aus DesignTokens)
        ├── Color             Re-Exports aus DesignTokens (Legacy-Kompatibilität)
        └── Type              Typografie-Skala
```

**Pattern:** MVVM mit dünner Repository-Schicht.
`AudioRecorderService` läuft im Vordergrund und liefert Ergebnisse via Callback an `MainActivity`.
`SettingsViewModel` exponiert einen einzelnen `StateFlow<SettingsUiState>` via `combine()`.
Alle Compose-Composables sind zustandslos — State fließt nach unten, Events nach oben.

---

## Audio Pipeline

### Google Speech Modus (Standard)
```
Android SpeechRecognizer
    ↓ (verwaltet Mikrofon selbst)
Partial Results → onPartialResult Callback → UI (Live-Vorschau)
Final Result   → onTranscriptionResult Callback → TranscriptEntry
```
*Diarisierung nicht verfügbar in diesem Modus — alle Ergebnisse gehen an Speaker 0.*

### Whisper Modus (Offline)
```
AudioRecord (16 kHz, mono, PCM float)
    ↓
NoiseSuppressor + AutomaticGainControl
    ↓
RMS-VAD: voiceThreshold=0.005, silenceThreshold=0.002
    ↓ (nach 500 ms Stille oder 2 s Akkumulation)
SherpaSpeakerDiarizer → speakerId (Cosine-Similarity, Schwellwert 0.55)
    ↓
SherpaOnnxAsrEngine   → text (Whisper Tiny, Noise-Token-Filterung)
    ↓
onTranscriptionResult(speakerId, text) → UI
```

---

## UI Customisation

Alle Design-Entscheidungen leben in **einer Datei**:

```
app/src/main/kotlin/com/livetranscript/ui/theme/DesignTokens.kt
```

`AppTheme.Colors.*`, `AppTheme.Gradients`, `AppTheme.Dimens` oder `AppTheme.Animation` editieren — alle Screens lesen von diesen Tokens, Änderungen propagieren automatisch.

| Token | Default (Dark) | Effekt |
|---|---|---|
| `AppTheme.DarkColors.bgDeep/Mid/Light` | `#0A1929 / #132B45 / #1A3456` | Hintergrund-Gradient |
| `AppTheme.DarkColors.recordActive` | `#EF4444` | Recording-FAB-Farbe |
| `AppTheme.DarkColors.accentCyan` | `#4FC3F7` | Waveform-Bars, Fokus-Rahmen |
| `AppTheme.DarkColors.speakers` | 6 Farben | Pro-Sprecher-Label-Farben |
| `AppTheme.Dimens.fabSize` | `76.dp` | Aufnahme-Button-Durchmesser |
| `AppTheme.Dimens.waveformBars` | `36` | Anzahl Waveform-Balken |
| `AppTheme.Animation.waveformTickMs` | `110L` | Waveform-Refresh-Rate (ms) |
| `AppTheme.Animation.pulseDurationMs` | `950` | FAB-Puls-Ring-Zyklus (ms) |

Um eine neue Sprecher-Farbe hinzuzufügen: `AppTheme.Colors.speakers` erweitern.

---

## Tech Stack

| Komponente | Library / Version |
|---|---|
| Sprache | Kotlin 2.0 (K2-Compiler) |
| UI | Jetpack Compose (BOM 2024.10.00) + Material 3 |
| ASR (Offline) | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.12.28 (AAR, auto-download) |
| ASR-Modell | Whisper Tiny multilingual (ONNX int8 quantisiert) |
| ASR (Online) | Android SpeechRecognizer (Google Speech) mit 3-stufigem Fallback |
| Diarisierung | WeSpeaker ResNet34-LM via sherpa-onnx (ONNX IR v≤9 kompatibel) |
| Persistenz | Jetpack DataStore (Preferences) |
| Async | Kotlin Coroutines + Flow |
| DI | Manuell (Factory-Pattern im ViewModel) |
| Min SDK | 31 (Android 12) |
| Target SDK | 35 (Android 15) |
| Build | AGP 8.4.1, Gradle Version Catalog (`libs.versions.toml`) |

---

## Build Requirements

| Voraussetzung | Version |
|---|---|
| JDK | 17+ |
| Android Studio | Iguana (2023.2)+ oder nur Kommandozeile |
| Android SDK | API 35 (compileSdk) / API 31 (minSdk) |
| Internet | Nur zur Build-Zeit (AAR + WeSpeaker-Modell werden automatisch heruntergeladen) |

### Clone & Build

```bash
# 1. Klonen
git clone https://github.com/f0x-user/LiveTranscript-v2.git
cd LiveTranscript-v2

# 2. Bauen — Gradle lädt automatisch herunter:
#    - sherpa-onnx-1.12.28.aar (~120 MB) aus GitHub Releases
#    - wespeaker_en_voxceleb_resnet34_LM.onnx (~26 MB) aus sherpa-onnx Releases
./gradlew assembleDebug

# 3. Auf Gerät/Emulator installieren
./gradlew installDebug
```

> **Hinweis Whisper-Modelle:** Die Whisper-Tiny ONNX-Dateien müssen manuell in
> `app/models/whisper-tiny/` abgelegt werden (zu groß für automatischen Build-Download).
> Siehe [Modelle hinzufügen](#modelle-hinzufügen).

### Android Studio

1. Projektordner in Android Studio öffnen.
2. Gradle-Sync abwarten (AAR + WeSpeaker-Modell werden automatisch heruntergeladen).
3. **Shift+F10** oder **Run** drücken.

### Release Build

```bash
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=keystore.jks \
  -Pandroid.injected.signing.store.password=<pass> \
  -Pandroid.injected.signing.key.alias=<alias> \
  -Pandroid.injected.signing.key.password=<pass>
```

> **ABI-Hinweis:** Release-APKs enthalten nur `arm64-v8a` (99 % moderner Android-Geräte).
> Debug-APKs enthalten zusätzlich `x86_64` für Emulator-Unterstützung.

---

## Modelle hinzufügen

### Automatisch (WeSpeaker)
Das WeSpeaker-Diarisierungsmodell wird beim Build automatisch heruntergeladen:
- **Quelle:** `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/`
- **Modell:** `wespeaker_en_voxceleb_resnet34_LM.onnx` (ResNet34, Large-Margin Fine-Tuning)
- **Ziel:** `app/models/wespeaker/model.onnx`

Das Build-System erkennt inkompatible Modelle (falsche ONNX IR Version) automatisch und ersetzt sie.

### Manuell (Whisper Tiny)
ONNX-Modell-Dateien in das Assets-Verzeichnis legen:

```
app/models/whisper-tiny/
├── tiny-encoder.int8.onnx   (~13 MB)
├── tiny-decoder.int8.onnx   (~86 MB)
└── tiny-tokens.txt          (~800 KB)
```

Download von der [sherpa-onnx Releases-Seite](https://github.com/k2-fsa/sherpa-onnx/releases)
(Whisper Tiny multilingual, ONNX int8 quantisiert).

### Modell-Kompatibilität
Das in sherpa-onnx 1.12.28 gebündelte ONNX Runtime unterstützt maximal **ONNX IR Version 9**.
Modelle, die mit ONNX IR Version 10+ exportiert wurden, verursachen einen nativen Crash (SIGABRT).
`ModelAssetManager` erkennt veraltete Caches per Asset-Größenvergleich und kopiert bei Abweichung neu.

---

## Berechtigungen

| Berechtigung | Warum |
|---|---|
| `RECORD_AUDIO` | Mikrofon-Zugriff |
| `INTERNET` | Wird deklariert, aber nur zur Build-Zeit genutzt (Modell-Downloads); zur Laufzeit nicht benötigt |
| `FOREGROUND_SERVICE` | Recording-Service am Leben halten wenn im Hintergrund |
| `FOREGROUND_SERVICE_MICROPHONE` | Android 14: Foreground Service Type für Mikrofon |
| `POST_NOTIFICATIONS` | Foreground-Service-Benachrichtigung (Android 13+) |

---

## Transkript speichern

Auf das Speichern-Icon (oben rechts, nach erstem Transkript-Eintrag verfügbar) tippen:

| Format | Anwendungsfall |
|---|---|
| **TXT** | Einfacher Text mit Sprecher-Labels — schnelles Copy-Paste |
| **CSV** | Import in Excel / Google Sheets |
| **JSON** | Programmatische Verarbeitung (timestamp, speakerId, text) |
| **SRT** | Untertitel zu einem Video hinzufügen |

Dateien werden via Android Share-Sheet geteilt (in Dateien speichern, per E-Mail senden, etc.).

---

## Lokalisierung

Die App-UI-Sprache folgt der ausgewählten Transkriptionssprache:

| Sprachcode | App-UI |
|---|---|
| `en` | English |
| `fr` | Français |
| `es` | Español |
| `""` (Auto) | Gerätesprache (automatisch erkannt) |
| alle anderen | Deutsch (Standard) |

Um eine neue UI-Sprache hinzuzufügen:
1. Neue `val FooStrings = AppStrings(...)` in `AppStrings.kt` anlegen
2. Branch in `stringsForLanguage()` hinzufügen
3. Keine weiteren Dateien notwendig

---

## Bekannte Einschränkungen

| Problem | Ort | Status |
|---|---|---|
| Waveform ist simuliert (kein echtes RMS) | `LiveScreen.kt` | Offen |
| Keine Diarisierung im Google-Speech-Modus | `AudioRecorderService.kt` | By Design |
| `diarizerOnlyLoop()` ist Dead Code | `AudioRecorderService.kt` | Technische Schuld |
| Kein Landscape-Layout | Alle Composables | Offen |
| Service-Lifecycle bei Task-Entfernung | `AudioRecorderService.kt` | Offen |
| Keine Error-States in der UI | `LiveScreen.kt` | Offen |
| Nur ARM64 in Release | `build.gradle.kts` | By Design |

---

## Projektstand

- [x] Hybrid ASR: Google Speech (Standard) + Whisper Tiny (Offline)
- [x] Speaker Diarization (WeSpeaker ResNet34-LM)
- [x] 15-Sprachen-Dropdown + Auto-Detect
- [x] App-UI-Sprache folgt Transkriptionssprache (DE / EN / FR / ES)
- [x] Dark / Light / System Theme
- [x] Animated Gradient UI mit Puls-FAB
- [x] Transkript speichern (TXT / CSV / JSON / SRT)
- [x] Settings Screen (Theme, Auto-Scroll, Timestamps, ASR-Backend)
- [x] Partial Results (Live-Vorschau während Sprache erkannt wird)
- [x] Emulator-Unterstützung (x86_64 in Debug-Builds)
- [x] Automatischer WeSpeaker-Modell-Download zur Build-Zeit
- [x] Inkompatible ONNX-Modelle werden automatisch erkannt und ersetzt
- [ ] Echtzeit-RMS-Waveform (derzeit simuliert mit Zufallswerten)
- [ ] Größere Whisper-Modelle (Small / Medium) für bessere Genauigkeit
- [ ] Hintergrund-Transkription mit Benachrichtigungs-Controls
- [ ] Landscape / Tablet-Layout
- [ ] Diarisierung im Google-Speech-Modus
- [ ] Anpassbares Vokabular / Hotword-Unterstützung

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
sherpa-onnx: Apache 2.0 · Whisper: MIT (OpenAI) · WeSpeaker: Apache 2.0
