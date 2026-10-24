# Google TV Launcher 📺⚡

An ultra-fast, ad-free, Apple TV-inspired custom launcher and power suite for Google TV and Android TV. Features **HDMI DualView Picture-in-Picture (PiP)**, a dedicated **Launcher Settings App**, real-time **ESPN Sports Feeds**, **Trakt.tv cloud sync**, and **hardware remote button remapping**.

Built with native Java for maximum responsiveness, zero bloat, and a lightweight memory footprint (<80 MB RAM).

---

## Highlights

* 🚫 **100% Ad-Free & Zero Bloat**: Replaces ad-cluttered stock launchers (`launcherx`) with a clean, minimal interface.
* ⚡ **Ultra-Low Latency Navigation**: Instant D-Pad response, no click delay, and standard 28dp TV-safe margins.
* 🔌 **HDMI DualView Picture-in-Picture**: Pair any two HDMI inputs (e.g., PS5 on HDMI 2 + Fire TV on HDMI 3) with a live floating preview card and instant remote quick-swapping.
* ⚙️ **Dedicated Launcher Settings TV App**: Standalone 10-foot two-pane settings app for managing HDMI pairs, remote buttons, TV layout, sports leagues, and system diagnostics.
* 🎮 **System-Wide Hardware Remote Remapping**: Remaps manufacturer buttons on your physical remote:
  * **"123" Virtual Remote Button** (`KEYCODE_WINDOW` / 171) ➔ Instant HDMI DualView swap.
  * **TV Inputs Button** (`KEYCODE_TV_INPUT` / 178) ➔ Quick clean inputs switcher across all screens.
  * **TV / Guide / Color Buttons** ➔ Custom app shortcuts (IPTV, Stremio, etc.).
* 🏀 **Real-Time Sports Feeds**: Live scoreboards, game clocks, and period scores for NBA, NFL, Premier League, UEFA Champions League, MLB, and NHL powered by ESPN APIs with priority team pins.
* 🎬 **Trakt.tv & Stremio 4K Hub**: Synced cloud watchlist, Continue Watching shelves, and seamless deep-linking into Stremio.
* 🚀 **Performance & Memory Booster**: Real-time RAM monitor and one-click memory cache trimmer.

---

## Features Overview

### 1. HDMI DualView & PiP (`com.gathin.tvhome.pip`)
Play games full-screen while keeping an eye on live sports, streaming video, or another HDMI console:
* **Input Pairing**: Pair HDMI 1, HDMI 2 (e.g. PlayStation 5), HDMI 3 (e.g. Fire TV Stick), or HDMI 4.
* **Corner Placement**: Position the floating overlay card in any corner (Top-Right, Bottom-Right, Top-Left, Bottom-Left).
* **Instant Hotkey**: Press the **"123"** remote key (or custom hotkey) while gaming to swap active inputs instantly.
* **Live Sports Ticker**: Embedded live scores ticker directly on the floating PiP overlay.

### 2. Standalone Launcher Settings App (`com.gathin.tvhome.settings`)
A dedicated Leanback TV app navigable with the D-Pad:
* **HDMI DualView PiP**: Configure pairs, corners, and toggle overlay session.
* **Remote Buttons & Live Sniffer**: Includes a **real-time Key Tester**—press any button on your physical remote to see its KeyCode and action live on screen.
* **Home Screen & Margins**: Switch between 28dp Clean Margins and 58dp Inset Margins; toggle dynamic hero backdrops.
* **Sports Leagues**: Toggle NBA, NFL, Premier League, UCL, MLB, NHL feeds and pinned teams.
* **Trakt & Stremio**: View `@DhruvG85` account sync status and flush media caches.
* **Memory Diagnostics**: Live RAM usage gauge (`Used MB / Total MB`), hardware display mode (`1920x1080 @ 60Hz` / `3840x2160 @ 120Hz`), and one-click cache trimmer.
* **System Controls**: One-click switch to stock Google TV or native Android TV Settings.

### 3. Remote Button Remapper Service (`RemoteKeyMapperService`)
Runs as an Android Accessibility Service to intercept and remap hardware keys system-wide:
* Eliminates disabled stock launcher dead-keys.
* Restores the physical **Inputs** button so it works across all video inputs and third-party apps.
* Maps the **"123" Virtual Remote Key** (TCL RC923A remote `WINDOW` 171) to trigger HDMI quick-swapping.

---

## Project Structure

```
googletv-launcher/
├── launcher/
│   ├── app/src/main/
│   │   ├── java/com/gathin/tvhome/
│   │   │   ├── MainActivity.java              # Main TV home screen & hub view
│   │   │   ├── HubDataFetcher.java            # ESPN sports & Trakt API client
│   │   │   ├── RemoteKeyMapperService.java    # Accessibility hardware key remapper
│   │   │   ├── pip/
│   │   │   │   ├── PipOverlayService.java     # Floating PiP overlay window
│   │   │   │   └── PipSettingsActivity.java   # Dedicated HDMI DualView app
│   │   │   └── settings/
│   │   │       └── LauncherSettingsActivity.java # 2-pane TV Settings app
│   │   ├── res/                               # Layouts, Apple TV focus styles & icons
│   │   └── AndroidManifest.xml                # Launcher & activity declarations
│   └── build-and-install.sh                   # Automated build & Wi-Fi push script
└── tools/
    ├── tv.py                                  # ADB wrapper for key injection & screen capture
    ├── b64push.py                             # Fast APK pusher
    └── restore.sh                             # One-command factory restore script
```

---

## Quick Start & Installation

### Prerequisites
* A smart TV running **Google TV** or **Android TV** (TCL, Sony, Xiaomi, Hisense, Chromecast with Google TV, etc.)
* Computer on the same local Wi-Fi network as the TV
* Java 17+ and Android SDK platform 34

### 1. Enable ADB on your TV
1. Go to **Settings** ➔ **System** ➔ **About**.
2. Highlight **Android TV OS build** and press **OK** 7 times until Developer Options are enabled.
3. Go back to **Settings** ➔ **System** ➔ **Developer options** ➔ enable **USB debugging** and **Network debugging**.

### 2. Build and Install over Wi-Fi
From your computer terminal, run:
```bash
# Set your TV's local IP address and run the installer
TV_HOST=192.168.0.87 launcher/build-and-install.sh
```

The script automatically:
1. Compiles the APK (`app-debug.apk`).
2. Pushes and installs it on the TV.
3. Grants required overlay (`SYSTEM_ALERT_WINDOW`) and location permissions for Wi-Fi SSID reading.
4. Sets `MainActivity` as the default TV Home screen.
5. Disables stock launcher advertising (`launcherx`).

### 3. Factory Restore (If Needed)
If you ever want to return your TV to stock Google TV:
```bash
TV_HOST=192.168.0.87 tools/restore.sh
```

---

## License

This project is licensed under the [MIT License](LICENSE) &copy; 2026 Dhruv Gandhi.
The Outfit typeface is licensed under the SIL Open Font License.
