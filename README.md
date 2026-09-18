# EcoSense (Android App)

EcoSense is an Android companion app for a smart waste-segregation bin.  
It uses CameraX + Gemini Vision to classify waste into **DRY**, **WET**, or **ELECTRONIC**, then sends a one-byte Bluetooth Classic command to an ESP32 controller.

---

## Features

- **Live camera preview** with Jetpack Compose + CameraX.
- **Gemini REST classification pipeline** using OkHttp (no Google client SDK).
- **Multi-model fallback and retry strategy** for more reliable classification.
- **Automatic object detection + stillness trigger** (hands-free auto-capture after ~2s of stillness).
- **Voice trigger support** via Android `SpeechRecognizer` (keywords like “scan”, “capture”, “take photo”, “ready”).
- **Bluetooth Classic SPP command dispatch** to the paired ESP32 device (`EcoSense_Bin`).
- **Result overlay + voice announcement** with category and detected item name.
- **Debug overlay** with:
  - Real-time logs
  - Runtime API key override
  - Simulation buttons for DRY/WET/ELECTRONIC
  - Detection status visibility

---

## How It Works

1. User (or auto-detection/voice) triggers image capture.
2. Image is downscaled/compressed for faster inference.
3. App sends image + prompt to Gemini REST API.
4. Response is parsed into:
   - Category: `DRY`, `WET`, `ELECTRONIC` (or error fallback path)
   - Item name (human-readable)
5. App maps category to Bluetooth command:
   - `DRY` → `D`
   - `WET` → `W`
   - `ELECTRONIC` → `E`
6. App opens RFCOMM SPP socket and sends one byte to ESP32.
7. Success overlay is shown for 5 seconds, then app returns to camera preview.

---

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Camera:** CameraX (`core`, `camera2`, `lifecycle`, `view`)
- **Networking:** OkHttp
- **Permissions (Compose):** Accompanist Permissions
- **Speech:** Android SpeechRecognizer + TextToSpeech
- **Architecture style:** Compose + `ViewModel` state management

---

## Project Structure

```text
EcoSense/
├─ app/
│  ├─ src/main/
│  │  ├─ java/com/app/wastemanager/
│  │  │  ├─ MainActivity.kt        # Main UI + ViewModel + core logic
│  │  │  └─ ui/theme/              # Compose theme files
│  │  ├─ AndroidManifest.xml       # Permissions + app declaration
│  │  └─ res/                      # Drawables, values, launcher assets
│  └─ build.gradle.kts             # App module dependencies/config
├─ gradle/libs.versions.toml       # Dependency versions
└─ settings.gradle.kts
```

> Current implementation is intentionally centralized in `MainActivity.kt` for fast iteration/prototyping.

---

## Requirements

- Android Studio (recent stable version)
- Android SDK configured locally
- JDK compatible with project setup
- Internet access for Gemini API calls
- A paired Bluetooth Classic target device named **`EcoSense_Bin`** (for hardware testing)

---

## Setup

### 1) Clone and open

Open the repository in Android Studio.

### 2) Configure `local.properties`

Create/update `/home/runner/work/EcoSense/EcoSense/local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
GEMINI_API_KEY=your_gemini_api_key
```

Use `/home/runner/work/EcoSense/EcoSense/example.local.properties` as reference.

> `local.properties` is gitignored and should not be committed.

### 3) Sync and build

From project root:

```bash
./gradlew assembleDebug
```

### 4) Run

- Run on a physical Android device for full camera + Bluetooth behavior.
- Emulator can be used for UI and non-Bluetooth validation (Bluetooth hardware is usually unavailable).

---

## Runtime Permissions

The app requests:

- `CAMERA`
- `RECORD_AUDIO`
- `BLUETOOTH_CONNECT` (Android 12+)
- Manifest also includes classic Bluetooth permissions and internet access.

Without camera/audio permissions, the app shows a permission placeholder screen.

---

## Gemini Classification Details

- Uses REST endpoint `models/{model}:generateContent` with image inlineData.
- Optimizes image payload before upload (downscale + JPEG compression).
- Tries multiple models in fallback order with retry handling for transient failures.
- If all models fail, app enters a safe fallback category path so bin control remains responsive.

---

## Bluetooth Behavior

- Target device name is constant: **`EcoSense_Bin`**
- Uses SPP UUID: `00001101-0000-1000-8000-00805F9B34FB`
- Performs reachability checks by opening a real RFCOMM connection (not only bonded-list checks).
- Sends one command byte (`D`, `W`, `E`) and closes socket.

---

## UI Overview

- **Camera background layer** (full screen)
- **Top controls**:
  - Bluetooth status
  - Detection lock/unlock
  - Recalibrate background
  - Camera flip
  - Flash toggle
  - Debug toggle
- **Auto-scan reticle** with object/stillness feedback
- **Hands-free mic action area**
- **Loading overlay** while classification is running
- **Success overlay** with category color coding
- **Debug overlay** with logs, API key override, and simulation controls

---

## Testing Notes

- **Physical device recommended** for camera + microphone + Bluetooth validation.
- **Emulator limitations**:
  - Bluetooth Classic workflows generally cannot be end-to-end tested.
  - You can still test UI, state transitions, and simulation flows.
- Simulation actions in Debug mode help validate category overlays and command mapping logic paths.

---

## Useful Gradle Commands

Run from `/home/runner/work/EcoSense/EcoSense`:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

---

## Troubleshooting

- **“Gemini API Key is empty”**
  - Ensure `GEMINI_API_KEY` is set in `local.properties`, or set a custom key in Debug overlay.
- **Bluetooth device not found**
  - Pair device first in Android system settings.
  - Confirm the device name exactly matches `EcoSense_Bin`.
- **No Bluetooth on emulator**
  - Expected behavior; use a physical device for Bluetooth tests.
- **Camera/voice trigger not working**
  - Verify runtime permissions are granted.

---

## Security Notes

- Never commit API keys.
- Keep `local.properties` local only.
- Review debug logs before sharing screenshots or recordings if they may expose sensitive runtime info.

---

## License

This repository includes a `LICENSE` file at the project root. See it for usage terms.
