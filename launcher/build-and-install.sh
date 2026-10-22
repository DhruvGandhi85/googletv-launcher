#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:=192.168.0.87}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx}"
ROOT_DIR="$(cd .. && pwd)"
if [ -d "$ROOT_DIR/.toolchain/jdk/Contents/Home" ]; then
  DEFAULT_JAVA="$ROOT_DIR/.toolchain/jdk/Contents/Home"
else
  DEFAULT_JAVA="$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)"
fi
if [ -d "$ROOT_DIR/.toolchain/android-sdk" ]; then
  DEFAULT_SDK="$ROOT_DIR/.toolchain/android-sdk"
elif [ -d ~/Library/Android/sdk ]; then
  DEFAULT_SDK=~/Library/Android/sdk
else
  DEFAULT_SDK=/opt/homebrew/share/android-commandlinetools
fi
export JAVA_HOME="${JAVA_HOME:-$DEFAULT_JAVA}"
export ANDROID_HOME="${ANDROID_HOME:-$DEFAULT_SDK}"
export PATH="$JAVA_HOME/bin:$PATH"
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
PY=../tools/venv/bin/python
[ -x "$PY" ] || PY=../tools/venv/Scripts/python.exe
[ -x "$PY" ] || PY=python3
export MSYS_NO_PATHCONV=1
export TV_HOST
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
printf '%s\n' \
  "settings put global verifier_verify_adb_installs 0" \
  "settings put global package_verifier_enable 0" \
  "pm install -r --bypass-low-target-sdk-block /data/local/tmp/tvhome.apk" \
  "rm -f /data/local/tmp/tvhome.apk" \
  "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "pm disable-user --user 0 $STOCK_LAUNCHER" \
  "pm disable-user --user 0 com.google.android.tungsten.setupwraith" \
  "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" \
  "pm enable com.google.android.apps.tv.dreamx 2>/dev/null; echo dreamx-enabled" \
  "settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop" \
  "settings put secure screensaver_enabled 1" \
  | "$PY" -I ../tools/tv.py --batch
echo "Installed. Press Home on the remote to see it."



