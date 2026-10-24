# Contributing to Google TV Launcher

Contributions, feature suggestions, and TV model compatibility reports are welcome!

## Areas of Contribution

### 1. Launcher UI & Experience
The core launcher lives in `launcher/app/src/main/java/com/gathin/tvhome/`:
- **Native Android TV UI**: Fast, responsive Leanback experience written in native Java for maximum performance on smart TV hardware (<100 MB RAM).
- **Smooth Navigation**: 60fps D-Pad interactions, TV-safe 28dp margins, and zero input lag.

### 2. HDMI DualView & Picture-in-Picture
The DualView PiP module is in `launcher/app/src/main/java/com/gathin/tvhome/pip/`:
- Pair multiple HDMI inputs (e.g. HDMI 2 Gaming Console + HDMI 3 Streaming Device).
- Overlay positioning and live overlay service optimization.

### 3. Remote Button Remapping
The hardware key mapper is in `launcher/app/src/main/java/com/gathin/tvhome/RemoteKeyMapperService.java`:
- Keycode interception for manufacturer remote buttons (`KEYCODE_TV_INPUT`, `KEYCODE_WINDOW`, `KEYCODE_TV`, `KEYCODE_GUIDE`).
- Testing and adding support for new OEM remote layouts.

### 4. Sports & Media Integrations
Data fetchers live in `launcher/app/src/main/java/com/gathin/tvhome/HubDataFetcher.java`:
- ESPN live scores API parsing for sports leagues (NBA, NFL, Premier League, UEFA, MLB, NHL).
- Trakt.tv watch history and Stremio integration.

## Building and Testing

1. Ensure the TV has **USB / Network Debugging** enabled and is connected to the same Wi-Fi network.
2. Build and install directly over ADB:
   ```bash
   TV_HOST=192.168.0.87 launcher/build-and-install.sh
   ```
3. Test remote navigation and take TV screenshots:
   ```bash
   tools/tv.py screencap -s 0 -p > screenshot.png
   ```
