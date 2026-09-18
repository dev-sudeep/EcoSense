# Role & Context:
Act as an Expert Android/Kotlin Developer. I am building the Android companion app for a smart waste-segregation dustbin. The app acts as the "brain" and user interface. It captures an image, sends it to the Gemini REST API for classification (DRY, WET, or ELECTRONIC), and sends a single-byte command ('D', 'W', or 'E') via Bluetooth Classic to an ESP32 controlling a servo motor.

## Tech Stack:

- Android Studio (Empty Compose Activity)

- Kotlin

- Jetpack Compose (for a polished, animated UI)

- CameraX (for live viewfinder)

- OkHttp (for custom REST API calls)

- Accompanist (for Compose permissions)

## Constraint Checklist & Rules:

DO NOT use the official Google AI client SDK or the Gemini API Activity template. We must use a custom OkHttp REST implementation to control the model fallback loop.

Use standard Bluetooth Classic . Assume the ESP32 is already bonded/paired in OS settings with the name "SmartDustbin".

Ensure all UI state is managed with Compose ViewModel or hoisted state.

## Phase 1: Configuration
1. AndroidManifest.xml
Use exactly this manifest:

```XML
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.CAMERA" />
    <!-- Legacy Bluetooth -->
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <!-- Android 12+ Bluetooth Permissions -->
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.WasteManager">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.WasteManager"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>

```
2. Dependencies (build.gradle.kts :app)
Include OkHttp, CameraX (core, camera2, lifecycle, view), and Accompanist permissions.

## Phase 2: Core Logic
1. Gemini API Fallback Loop (OkHttp)
- Create a suspend function that accepts a ByteArray (the image).

- Convert the ByteArray to a Base64 string.

- Prompt: "Classify waste strictly as ONE of: DRY, WET, ELECTRONIC. Reply with ONE word only."

- Crucial: Implement a try-catch loop over this exact model list: gemini-3.8-flash, gemini-3.7-flash, gemini-3.6-flash, gemini-3.5-flash, gemini-3.0-flash.

- If a model fails (network error or non-200 code), log it and move to the next.

- Return the classification string ("DRY", "WET", "ELECTRONIC") or "ERROR".

- Accept a callback onDebugLog: (String) -> Unit to stream logs to the UI.

2. Bluetooth SPP Sender
- Create a helper function to send a command.

- Find the bonded device named "SmartDustbin".

- Open the RFCOMM socket, write the Char ('D', 'W', or 'E') to the OutputStream, and close the socket.

- Wrap in a try/catch and log errors.

## Phase 3: Jetpack Compose UI/UX
Build a polished, single-screen UI with the following layers (Z-Index from bottom to top):

- Background Layer: A full-screen CameraX PreviewView.

- Top Bar Layer:

- A small Bluetooth connection status icon.

- A "Debug" toggle icon (e.g., a bug icon).

- Debug Layer (Conditional): If the Debug toggle is on, show a semi-transparent dark overlay at the top with a scrollable Text column displaying the real-time logs from the onDebugLog callback (e.g., "3.8 failed, trying 3.7...").

- Bottom Action Layer: A sleek, large circular "Scan" button (styled like a native camera shutter).

- Loading Overlay (Conditional): When the API call is running, blur the background slightly and show a glowing "Thinking..." animation or CircularProgressIndicator in the center.

- Success Overlay (Conditional): When a result returns:

- Fill the screen with a solid, vibrant color (Green for WET, Blue for DRY, Yellow/Orange for ELECTRONIC).

- Display massive, bold text: "DROP IN\n[CATEGORY]".

- Show this overlay for exactly 5 seconds before returning to the live camera view.

Generate the complete Kotlin code for MainActivity.kt containing the Compose UI and the logic functions. Make sure the app is entirely functional and ready to use. Use the Android Emulator MCP to verify the app works and do thorough testing keeping in mind the emulator isn't actually connected to the ESP32 when testing.