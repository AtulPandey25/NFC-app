# NFC Player

A native Android application built with **Kotlin** and **Android Studio** that detects contactless NFC debit cards and NFC-enabled smartphones to play audio files automatically.

---

## 📱 Features

1. **Native NFC Reader Mode**:
   - Uses `NfcAdapter.enableReaderMode` tuned with `FLAG_READER_NFC_A`, `FLAG_READER_NFC_B`, `FLAG_READER_SKIP_NDEF_CHECK`, and `FLAG_READER_NO_PLATFORM_SOUNDS`.
   - Filters and verifies `IsoDep` (ISO/IEC 14443-4), the standard protocol for contactless EMV payment cards (Visa payWave, Mastercard PayPass, RuPay Contactless, etc.).
   - Implements **debouncing** (2.5 seconds) so keeping the card near the phone will not cause stuttering or repeated audio restarts.

2. **Feasibility Testing & Diagnostics Console**:
   - Live on-screen console displays real-time NFC events, discovered tag technologies (e.g., `[IsoDep, NfcA]`), and feasibility confirmation.
   - Clearly flags whether the detected tag is an ISO-DEP payment card or an alternative tag (e.g. MIFARE, NDEF).

3. **Audio Playback**:
   - Plays a bundled audio file from `res/raw/card_chime.wav` automatically upon card detection using Android's native `MediaPlayer`.
   - Includes manual **Play Sound** and **Stop Sound** controls.
   - Safely releases audio resources across Activity lifecycle states (`onStop`, `onDestroy`).

4. **100% Privacy & Security**:
   - **Zero Internet Permission**: The app contains no network permissions in `AndroidManifest.xml` and runs completely offline.
   - **No Financial Data Access**: Does **not** transmit APDU commands (like `SELECT PPSE` or `READ RECORD`). Does **not** read or store card numbers (PAN), expiration dates, CVVs, or cardholder names.
   - Purely detects the RF presence of the card's ISO-DEP chip.

5. **Graceful Hardware Handling**:
   - Checks if NFC hardware is supported or disabled, and provides a direct shortcut button to Android's NFC Settings.

---

## 📂 Project Structure

```
NFCPRojet/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── java/com/example/nfcdebitplayer/
│           │   └── MainActivity.kt
│           └── res/
│               ├── drawable/       # Icons for NFC, Card, Play, Stop
│               ├── layout/
│               │   └── activity_main.xml
│               ├── raw/
│               │   └── card_chime.wav  # Bundled notification chime
│               └── values/
│                   ├── colors.xml
│                   ├── strings.xml
│                   └── themes.xml
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
└── README.md
```

---

## 🚀 How to Open and Run in Android Studio

### 1. Open the Project
1. Launch **Android Studio**.
2. Select **File > Open...** (or click **Open** on the Welcome Screen).
3. Navigate to `c:\Users\atulp\OneDrive\Desktop\NFCPRojet` and click **OK**.
4. Allow Gradle Sync to finish.

### 2. Run on a Physical Device
> **Note**: Android Studio's virtual emulators cannot emulate physical contactless debit cards. A **physical Android phone with NFC hardware** is required.

1. Enable **Developer Options** and **USB Debugging** on your Android phone.
2. Ensure **NFC is enabled** in your phone's settings:
   - *Settings > Connected devices > Connection preferences > NFC* (or search "NFC" in Settings).
3. Connect your phone via USB or Wi-Fi pairing.
4. Select your phone from the device dropdown in Android Studio and click the green **Run (▶)** button.

---

## 🧪 Feasibility Testing Guide

### Feasibility Steps
1. **Launch the App**:
   - The app verifies your phone's NFC adapter.
   - The status card should show **"NFC is available and enabled"**.
2. **Locate Your Phone's NFC Antenna**:
   - Most phones have the NFC coil near the top edge, around the camera bump, or in the center of the rear glass.
3. **Present the Card**:
   - Hold your contactless debit card flat against the back of the phone.
4. **Observe the Results**:
   - **Compatible Card Detected**: The detection card turns green, logs `IsoDep=YES`, and automatically starts playing the audio chime.
   - **Diagnostic Log**: Displays discovered tech details (e.g. `Techs=[IsoDep, NfcA]`).

### Card Detection Considerations
- **RFID-Blocking Wallets / Sleeves**: Remove the card from any wallet or RFID sleeve.
- **Phone Cases**: Very thick or metal phone cases may weaken the NFC coupling.
- **Metal Debit Cards**: Cards made of solid metal can sometimes interfere with inductive NFC coupling.
- **Non-Contactless Cards**: Cards without the contactless "wave" symbol do not have an NFC chip.
