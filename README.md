# Google TV Launcher

A minimal, ad-free launcher for Google TV and Android TV. Includes HDMI DualView Picture-in-Picture, a standalone Settings app, live sports scores, Trakt sync, and hardware remote button remapping.

Written in native Java with zero bloat and a sub-80 MB memory footprint.

---

## Interface

| Home | Settings |
| :---: | :---: |
| <img src="docs/launcher-home.png" width="480" alt="Home" /> | <img src="docs/launcher-settings.png" width="480" alt="Settings" /> |

| DualView Setup | Floating Overlay |
| :---: | :---: |
| <img src="docs/hdmi-dualview-pip.png" width="480" alt="DualView Setup" /> | <img src="docs/pip-overlay-active.png" width="480" alt="Floating Overlay" /> |

| Inputs |
| :---: |
| <img src="docs/inputs-menu.png" width="480" alt="Inputs" /> |

---

## Features

### DualView (Picture-in-Picture)
* Pair any two HDMI inputs (e.g. HDMI 2 console + HDMI 3 streaming device).
* Position the floating overlay in any corner.
* Instant 1-click video swap via the "123" remote button.

### Settings App
* Standalone Leanback TV app with two-pane navigation.
* Configure HDMI pairings, remote keys, and home layout.
* Live Remote Key Tester showing hardware keycodes in real time.
* Toggle sports feeds (NBA, NFL, Premier League, Champions League, MLB, NHL).
* RAM monitor and one-click memory cache trimmer.

### Remote Key Remapping
* System-wide hardware remote button handling via Android Accessibility.
* Intercepts and restores the physical Inputs button across all screens.
* Maps the "123" virtual remote key (`KEYCODE_WINDOW` 171) to trigger HDMI swap.

### Integrations
* Real-time scoreboards via ESPN APIs with priority team pins.
* Trakt cloud watchlist and Next Up episode tracking with Stremio integration.

---

## Project Structure

```
googletv-launcher/
├── launcher/
│   ├── app/src/main/
│   │   ├── java/com/gathin/tvhome/
│   │   │   ├── MainActivity.java              # Home screen
│   │   │   ├── HubDataFetcher.java            # ESPN and Trakt data client
│   │   │   ├── RemoteKeyMapperService.java    # Accessibility key mapper
│   │   │   ├── pip/
│   │   │   │   ├── PipOverlayService.java     # Floating PiP overlay
│   │   │   │   └── PipSettingsActivity.java   # DualView configuration app
│   │   │   └── settings/
│   │   │       └── LauncherSettingsActivity.java # Settings app
│   │   └── AndroidManifest.xml
│   └── build-and-install.sh
└── tools/
    ├── tv.py                                  # ADB wrapper
    ├── b64push.py                             # Fast APK push
    └── restore.sh                             # Stock restore script
```

---

## Installation

### Prerequisites
* Google TV or Android TV with USB / Network debugging enabled
* Computer on the same local network
* Java 17+ and Android SDK platform 34

### Build and Install
```bash
TV_HOST=192.168.0.87 launcher/build-and-install.sh
```

### Factory Restore
```bash
TV_HOST=192.168.0.87 tools/restore.sh
```

---

## License

MIT License &copy; 2026 Dhruv Gandhi.
The Outfit typeface is licensed under the SIL Open Font License.
