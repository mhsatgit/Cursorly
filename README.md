# Cursorly – Ultra-Precise Mobile Mouse Control

![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)
![Python](https://img.shields.io/badge/Python-3.10%2B-3776AB)
![FastAPI](https://img.shields.io/badge/FastAPI-%3E%3D0.110-009688)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF)
![Android](https://img.shields.io/badge/Android-API%2028%2B-3DDC84)
![Platform](https://img.shields.io/badge/Platform-Windows%2010%2F11-0078D6)
![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)

**Cursorly** is an open-source, end-to-end **Mobile-to-PC Touchpad and Mouse Control System**. Built with a **Python + FastAPI + Win32 `SendInput`** host engine and a **Kotlin + Jetpack Compose** Android client (plus a zero-install browser trackpad and direct ADB Linux kernel streamer), Cursorly turns any Android phone or tablet into a **low-latency**, **jitter-free**, **multi-touch wireless or wired trackpad**.

> **Windows Host & Standalone Bluetooth HID Note:** The PC host service targets **Windows 10 / 11** via native Win32 `SendInput` and multimedia timers (when targeting an elevated/Administrator window, run the host terminal as Administrator so Windows UIPI permits input injection). For **host-free** usage across Windows, macOS, or Linux, the Android app also includes a **Standalone Bluetooth HID Mouse** mode.

```text
[ TOUCH STREAM ] ──► [ TRANSPORT ] ──► [ GESTURE ENGINE ] ──► [ 1€ FILTER & BALLISTICS ] ──► [ SUB-PIXEL ACCUMULATOR ] ──► [ WIN32 / BT HID ]
```

## Key Features

- **Multi-Transport Connectivity:** Connects seamlessly over **USB Tethering (RNDIS)**, **Wi-Fi / Mobile Hotspot** (WebSockets + `TCP_NODELAY`), **Bluetooth RFCOMM Host**, **Standalone Bluetooth HID Mouse**, **Zero-Install HTML5 Browser Trackpad**, or **Direct ADB Kernel Streaming** (`/dev/input/event*`).
- **One Euro (1€) Adaptive Jitter Filtering & Error Diffusion:** Suppresses capacitive sensor jitter down to `0.8 Hz` during micro-aiming while opening up to `120 Hz+` for lag-free flicks, paired with a fractional `SubpixelAccumulator` that preserves remainders across integer `SendInput` ticks.
- **Double-Tap-to-Drag & Multi-Touch Gestures:** Implements a `200 ms` deferred single-tap + `8 dp` drag-start disambiguation state machine so double-tap cleanly maximizes windows while double-tap-and-move drags smoothly, alongside 2-finger smooth scroll, pinch-to-zoom, 2-finger right click, 3-finger middle click, virtual desktop switching (`Win+Ctrl+Left/Right`), and Task View (`Win+Tab`).
- **Split-Touch Mouse Buttons & Remote Keyboard:** Independent multi-touch **Left Click**, **Mid**, and **Right Click** buttons below the trackpad surface let you hold any mouse button while moving, tapping, or scrolling, paired with a collapsible remote Unicode keyboard and shortcut bar.
- **Zero-Config Multi-Interface Auto-Discovery:** Combines UDP broadcast beacons (`41234`), mDNS/NSD (`_cursorly._tcp.`), and Windows Bluetooth radio lookup (`bthprops.cpl`) so the Android app automatically discovers PCs across Wi-Fi, Hotspot, USB Tethering, and Bluetooth.
- **Dual Orientation & Precision Sniper Mode:** Supports **Portrait** and 90°-rotated **Landscape** tracking plus an instant **Sniper Mode** (`0.35x` sensitivity dampener) controllable from either the phone UI or live PC console hotkeys.

## Tech Stack

| Category | Technology | Badge & Version | Purpose |
| :--- | :--- | :--- | :--- |
| **Android App** | Kotlin & Jetpack Compose | ![Kotlin 2.2.10](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white) ![Compose BOM 2025.05.00](https://img.shields.io/badge/Compose_BOM-2025.05.00-4285F4?logo=jetpackcompose&logoColor=white) | Native Android UI, Material 3 & unbuffered `MotionEvent` capture |
| **Android SDK & Build** | Android Gradle Plugin | ![AGP 9.5.1](https://img.shields.io/badge/AGP-9.5.1-3DDC84?logo=android&logoColor=white) ![Android API 28+](https://img.shields.io/badge/Min_SDK-28-3DDC84) | Android 9+ (`minSdk 28`, `targetSdk 36`, `compileSdk 37`) |
| **Android Networking** | OkHttp & Coroutines | ![OkHttp 4.12.0](https://img.shields.io/badge/OkHttp-4.12.0-000000) ![Coroutines 1.10.1](https://img.shields.io/badge/Coroutines-1.10.1-7F52FF) | Low-latency WebSocket transport (`TCP_NODELAY`) & async I/O |
| **Backend Host** | Python & FastAPI | ![Python 3.10+](https://img.shields.io/badge/Python-3.10%2B-3776AB?logo=python&logoColor=white) ![FastAPI >=0.110.0](https://img.shields.io/badge/FastAPI-%3E%3D0.110.0-009688?logo=fastapi&logoColor=white) | Host runtime, HTTP/WebSocket server & web trackpad client |
| **ASGI Server** | Uvicorn (`standard`) | ![Uvicorn >=0.28.0](https://img.shields.io/badge/Uvicorn-%3E%3D0.28.0-4051B5) | High-frequency WebSocket & HTTP event loop |
| **Signal Processing** | 1€ Filter & Ballistics | ![Pure Python](https://img.shields.io/badge/Engine-Pure_Python-3776AB) | Adaptive smoothing, dynamic velocity curve & sub-pixel error diffusion |
| **Windows Injection** | Win32 `SendInput` & `winmm` | ![Win32 ctypes](https://img.shields.io/badge/Win32-SendInput_%2B_1ms_Timer-0078D6?logo=windows&logoColor=white) | Sub-millisecond mouse, scroll, zoom & Unicode keyboard injection |
| **Wireless & Discovery** | UDP, mDNS & Bluetooth | ![RFCOMM + HID](https://img.shields.io/badge/Bluetooth-RFCOMM_%26_HID-0082FC?logo=bluetooth&logoColor=white) | Multi-interface auto-discovery, RFCOMM SPP & native BT HID Mouse |
| **Testing & Linting** | `unittest`, Pytest & Ruff | ![Pytest >=8.0.0](https://img.shields.io/badge/Pytest-%3E%3D8.0.0-0A9EDC?logo=pytest&logoColor=white) ![Ruff >=0.3.0](https://img.shields.io/badge/Ruff-%3E%3D0.3.0-D7FF64) | Python unit tests, JUnit 4 Android tests & linting |

## Project Structure

```text
Cursorly/
├── android/                                  # Native Android Jetpack Compose client
│   ├── app/
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── java/com/cursorly/trackpad/
│   │   │   │   │   ├── discovery/            # UDP, mDNS/NSD, USB & Bluetooth discovery
│   │   │   │   │   ├── input/                # Unbuffered TouchSurfaceView & LocalHidGestureEngine
│   │   │   │   │   ├── model/                # Transport, host & trackpad settings models
│   │   │   │   │   ├── protocol/             # 18-byte binary packet & RFCOMM framing codec
│   │   │   │   │   ├── transport/            # WebSocket, Bluetooth RFCOMM & BT HID transports
│   │   │   │   │   ├── ui/                   # TrackpadScreen, ConnectionSheet, SettingsSheet, KeyboardBar
│   │   │   │   │   └── MainActivity.kt       # Compose entry point & permissions
│   │   │   │   ├── res/                      # App icons, strings & dark theme
│   │   │   │   └── AndroidManifest.xml       # Network, multicast & Bluetooth permissions
│   │   │   └── test/java/com/cursorly/trackpad/ # JUnit tests (OneEuroFilter2DTest, PacketCodecTest)
│   │   └── build.gradle.kts                  # Android app dependencies & SDK configuration
│   ├── build.gradle.kts                      # Root Gradle plugin declarations
│   ├── gradle.properties                     # JVM & AndroidX flags
│   └── settings.gradle.kts                   # Gradle module configuration
├── cursorly/                                 # Python PC host package
│   ├── adapters/
│   │   ├── adb_streamer.py                   # Direct Linux kernel /dev/input/event* ADB streamer
│   │   ├── bluetooth_server.py               # Windows AF_BLUETOOTH RFCOMM/SPP server
│   │   ├── discovery.py                      # UDP multi-interface beacon & BT MAC lookup
│   │   └── web_server.py                     # FastAPI WebSocket + zero-install HTML5 trackpad
│   ├── engine/
│   │   ├── ballistics.py                     # Dynamic velocity acceleration & Sniper dampener
│   │   ├── filter_one_euro.py                # 1€ adaptive noise filter (1D & 2D)
│   │   ├── gestures.py                       # Multi-touch gestures & double-tap-drag state machine
│   │   └── subpixel_accumulator.py           # Fractional remainder sub-pixel accumulator
│   ├── injection/
│   │   └── win32_mouse.py                    # Win32 SendInput mouse/keyboard & 1ms timer
│   └── main.py                               # CLI entry point, pending scheduler & telemetry
├── tests/                                    # Python unit test suite (33 tests)
│   ├── test_ballistics.py                    # Sensitivity clamping, Sniper & ballistics tests
│   ├── test_discovery_and_transports.py      # Interface classification, RFCOMM, API & ADB tests
│   ├── test_gestures.py                      # Tap, double-tap-drag, scroll & filter integration tests
│   ├── test_one_euro_filter.py               # Jitter suppression & high-speed tracking tests
│   ├── test_orientation.py                   # Portrait/Landscape rotation math tests
│   └── test_subpixel_accumulator.py          # Sub-pixel error-diffusion conservation tests
├── .github/                                  # CI workflow, PR template & issue templates
├── pyproject.toml                            # Package metadata, console script & setuptools config
├── requirements.txt                          # Runtime dependencies (fastapi, uvicorn)
├── requirements-dev.txt                      # Development dependencies (pytest, ruff)
├── setup.bat                                 # One-click Windows venv & dependency setup
├── run_cursorly.bat                          # Windows virtual-environment launcher
└── tools.ps1                                 # Portable Windows x64 Android toolchain installer (.tools/)
```

## Transport Modes Summary

| Transport Mode | PC Host Required | Auto-Discovery | Typical Rate / Latency | Best Use Case |
| :--- | :---: | :---: | :---: | :--- |
| **USB Tethering (RNDIS)** | Yes | Yes (UDP `41234`) | `120–360 Hz` · `~1–8 ms` | Lowest-jitter wired connection without ADB |
| **Wi-Fi / Mobile Hotspot** | Yes | Yes (UDP + mDNS) | `120–360 Hz` · `~5–15 ms` | Everyday wireless control on local LAN or phone hotspot |
| **Bluetooth Host (RFCOMM)** | Yes | Yes (Paired BT + UDP) | `120 Hz+` · Wireless | Wireless control without Wi-Fi or shared LAN |
| **Bluetooth HID Mouse** | **No** | Paired BT Device | Native HID Mouse | Controlling Windows, macOS, or Linux with zero host software |
| **Direct ADB Kernel Stream** | Yes | USB / Wireless ADB | `120–360 Hz+` · `~1–3 ms` | Zero-app Linux `/dev/input/event*` digitizer capture |
| **Web Browser Trackpad** | Yes | Manual URL (`:8080`) | Coalesced Pointer Events | Zero-install control from any mobile browser |

## Getting Started

### Prerequisites

- **Windows 10 or Windows 11** (for the PC host using Win32 `SendInput`)
- **Python** `>= 3.10` (with `venv` and `pip`)
- **Android Device** (`Android 9 / API 28+`)
- **Optional:** Run `tools.ps1` on Windows x64 to automatically install the portable Android build toolchain (`.tools/jdk`, `.tools/android-sdk`, `.tools/gradle-9.9.1`), or open `android/` in **Android Studio**; **ADB** is included in `.tools/android-sdk/platform-tools` if using `--mode adb`

### Option 1: Quick Start (Recommended for Users)

1. **Install the Android App:** Download the latest APK from [GitHubReleases](https://github.com/mhsatgit/Cursorly/releases/latest) and install it on your Android phone.
2. **Setup the PC Host:** Double-click **`setup.bat`** on Windows (creates `.venv` and installs dependencies from
    `requirements.txt`).
3. **Launch the PC Host:** Double-click **`run_cursorly.bat`** to start Cursorly in default Seamless mode (Wi-Fi / Hotspot /
    USB Tethering + Bluetooth RFCOMM + Auto-Discovery). If Windows Firewall prompts, allow access on **Private** networks.
4. **Connect:** Open **Cursorly** on your phone—your PC appears automatically in **Available PCs** (or auto-connects when a
    single host is detected).

### Option 2: Manual Setup & CLI Launch

#### 1. **Install the Android App:**
  Download the latest APK from [GitHubReleases](https://github.com/mhsatgit/Cursorly/releases/latest)
  and install it on your Android phone.

#### 2. Clone the repository and create a Python virtual environment

```powershell
git clone https://github.com/mhsatgit/Cursorly.git
cd Cursorly
py -3 -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
```

#### 3. Run the PC Host

```powershell
# Default Seamless mode (WebSocket + UDP Auto-Discovery + Bluetooth RFCOMM)
python -m cursorly.main

# Start directly in Landscape mode or Sniper Precision mode
python -m cursorly.main --landscape
python -m cursorly.main --precision

# Custom base sensitivity (0.25x) or 1:1 raw linear tracking
python -m cursorly.main --sens 0.25
python -m cursorly.main --linear --sens 0.4

# Target a specific transport mode
python -m cursorly.main --mode web --port 8080
python -m cursorly.main --mode bluetooth
python -m cursorly.main --mode adb --serial 192.168.1.50:5555
```

### Option 3: Android App & Connection

1. Install the portable Android toolchain via `tools.ps1` and build/install the APK (see [Run Android Unit Tests & Build Debug APK](#run-android-unit-tests--build-debug-apk)), or open `android/` in **Android Studio**.
2. Install the generated APK onto your phone (`adb install -r android\app\build\outputs\apk\debug\app-debug.apk`).
3. Launch **Cursorly** on Android while the PC host is running—your PC appears automatically in **Available PCs** (or auto-connects when a single host is detected).
4. **Browser Fallback:** Without installing the APK, open `http://<PC_IP>:8080` in Chrome or Firefox on your phone.

## Running Tests & Building

### Run Python Unit Tests & Lint

```powershell
# Activate .venv or run directly via .venv\Scripts\python.exe
python -m unittest discover tests -v

# Optional: with pytest and ruff (install requirements-dev.txt first)
pip install -r requirements-dev.txt
pytest -v
ruff check cursorly tests
```

### Run Android Unit Tests & Build Debug APK

On Windows x64, run [tools.ps1](tools.ps1) from the repository root to automatically download and verify `.tools\jdk` (JDK 25), `.tools\android-sdk` (`platforms/android-37.0`, `build-tools/37.0.0`, `platform-tools`), and `.tools\gradle-9.9.1`:

```powershell
# 1. Install the portable Android toolchain into .tools\
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools.ps1

# 2. Point this PowerShell session to the installed JDK and Android SDK
$env:JAVA_HOME = (Resolve-Path ".tools\jdk").Path
$env:ANDROID_HOME = (Resolve-Path ".tools\android-sdk").Path
# Optional: keep Gradle's dependency cache inside .tools\gradle-cache instead of %USERPROFILE%\.gradle
# $env:GRADLE_USER_HOME = (Resolve-Path ".tools\gradle-cache").Path

# 3. Run the first build online (without --offline) so Gradle downloads dependencies into .gradle automatically
& ".tools\gradle-9.9.1\bin\gradle.bat" -p android testDebugUnitTest assembleDebug --no-daemon

# 4. Subsequent builds can run offline once dependencies are cached
& ".tools\gradle-9.9.1\bin\gradle.bat" -p android testDebugUnitTest assembleDebug --offline --no-daemon

# 5. (Optional) Install and launch the built APK on a connected USB-debugging device
& ".tools\android-sdk\platform-tools\adb.exe" install -r "android\app\build\outputs\apk\debug\app-debug.apk"
& ".tools\android-sdk\platform-tools\adb.exe" shell am start -n com.cursorly.trackpad/.MainActivity
```

*(Alternatively, open `android/` directly in **Android Studio** and run **Build > Build APK(s)** / unit tests.)*

## API Endpoints & Controls

### Host Network & WebSocket Endpoints

| Protocol / Method | Endpoint / Port | Description |
| :---: | :--- | :--- |
| `UDP` | `0.0.0.0:41234` | Multi-interface auto-discovery (`CURSORLY_DISCOVER_V1` / beacons) |
| `GET` | `/` | Serves the zero-install HTML5 mobile web trackpad client |
| `GET` | `/api/info` | Returns host name, `ws_port`, `connected_clients`, and live state |
| `WS` | `/ws` | Streams 18-byte big-endian binary touch frames (`!BBffd`) & JSON control actions |
| `RFCOMM` | `SPP UUID 7a9d3c10-8f4b-4e2a-9c1d-5b6e7f8a9012` | Length-prefixed binary touch & JSON control frames over Bluetooth |

### Multi-Touch Gestures & Live PC Hotkeys

| Input | Action |
| :--- | :--- |
| **1 Finger Move / Tap / Double-Tap & Drag** | Cursor movement, Left Click, Double-Click, and Click-and-Drag |
| **2 Fingers Move / Pinch / Tap** | Smooth 2D Scroll, Pinch-to-Zoom (`Ctrl+Wheel`), and Right Click |
| **3 Fingers Tap / Swipe Horizontal / Swipe Up** | Middle Click, Switch Virtual Desktop (`Win+Ctrl+Left/Right`), Task View (`Win+Tab`) |
| **PC Console `[O]`/`[L]` · `[P]` · `[-]`/`[+]` · `[1–4]`** | Toggle Portrait/Landscape, toggle Sniper Mode, step sensitivity, or apply presets (`0.20x`–`1.00x`) |

## Disclaimer

Cursorly injects OS-level mouse and keyboard input via Windows `SendInput` and opens local-network UDP/HTTP/WebSocket listeners on trusted LAN, hotspot, or USB tethering interfaces. Use it only on trusted networks and devices you own or administer.

## License

This project is open source and available under the **MIT License**. See [LICENSE](LICENSE) for details.
