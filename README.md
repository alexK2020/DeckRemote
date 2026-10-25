# DeckRemote 🎮

Turn your Android smartphone into a Bluetooth HID media remote and controller for your **Steam Deck** (works natively in **Game Mode**).

![License](https://img.shields.io/badge/License-MIT-blue.svg)
![Platform](https://img.shields.io/badge/Platform-Android-green.svg)
![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)

---

## 🌟 Overview

Steam Deck's **Game Mode** does not support KDE Connect for navigation. **DeckRemote** solves this by emulating a native **Bluetooth HID Gamepad** on your Android device. Your Steam Deck recognizes your phone as a hardware controller, giving you seamless control over media, navigation, and shortcuts without extra software on SteamOS.

---

## ✨ Features

- **Native Bluetooth HID Emulation**: Recognized directly by SteamOS/Linux as a hardware gamepad.
- **Full Steam Deck Navigation Layout**:
  - **D-Pad & A Button**: Directional navigation and primary selection.
  - **B Button**: Back / Cancel.
  - **Steam Button**: Opens the main Steam Menu.
  - **Quick Access Menu (`...` Button)**: Triggers the Quick Access Menu via a timed `Steam + A` chord sequence.
  - **Select (`Sel`) & Start (`≡`) Buttons**: Standard gamepad menu/view controls.
- **Background Persistence**: Runs a Foreground Service to maintain connection when switching apps or locking your phone screen.
- **Auto-Reconnection**: Remembers your paired Steam Deck and connects automatically on startup.
- **Pitch-Black OLED Theme**: Edge-to-edge UI optimized for single-handed reachability.
- **Built-in Setup Guide & Discovery**: Interactive setup tutorial and Bluetooth discoverability toggle.

---

## 🚀 Getting Started

### Prerequisites
- An Android device running **Android 14+ (API 34+)** with Bluetooth enabled.
- A **Steam Deck** (LCD or OLED) running SteamOS.

### Setup Instructions
1. Enable **Bluetooth** on both your Steam Deck and your smartphone.
2. On your **Steam Deck**, go to **Settings -> Bluetooth** and enable **"Show all devices"**.
3. Open **DeckRemote** on your Android phone.
4. Tap the menu icon (**☰**) in the top right corner and tap **"Make Phone Visible (Discoverable)"**.
5. Select your smartphone on the Steam Deck Bluetooth menu and complete the pairing prompt.
6. In **DeckRemote**, tap **Connect** to start controlling your Steam Deck!

---

## 🛠️ Building from Source

1. Clone this repository:
   ```bash
   git clone https://github.com/your-username/DeckRemote.git
   cd DeckRemote
   ```
2. Open the project in **Android Studio** (2024.1+ / Ladybug+).
3. Build the APK using Gradle:
   ```bash
   ./gradlew assembleDebug
   ```
4. The output APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📜 License

Distributed under the **MIT License**. See `LICENSE` for more information.
