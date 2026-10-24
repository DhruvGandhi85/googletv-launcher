#!/usr/bin/env bash
# Restore Google TV to stock launcher and re-enable stock packages.
# Usage: TV_HOST=192.168.0.87 tools/restore.sh
set -euo pipefail
cd "$(dirname "$0")/.."
STOCK="${1:-com.google.android.apps.tv.launcherx}"
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
[ -x "$PY" ] || PY=python3

{
  echo "pm enable com.google.android.apps.tv.launcherx"
  echo "pm enable com.google.android.tungsten.setupwraith"
  echo "pm enable com.google.android.apps.tv.dreamx"
  echo "pm enable com.google.android.feedback"
  echo "pm enable com.android.tv.feedbackconsent"
  echo "pm enable com.tcl.tv.plus 2>/dev/null || true"
  echo "pm enable com.tcl.channelplus 2>/dev/null || true"
  echo "pm enable com.tcl.partnercustomizer 2>/dev/null || true"
  echo "cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK | tail -n 1)"
  echo "settings put global window_animation_scale 1.0"
  echo "settings put global transition_animation_scale 1.0"
  echo "settings put global animator_duration_scale 1.0"
  echo "pm list packages -d"
} | "$PY" -I tools/tv.py --batch
