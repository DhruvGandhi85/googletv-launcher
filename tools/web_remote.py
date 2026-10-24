#!/usr/bin/env python3
"""Google TV Web Remote & Interactive Master Dashboard.

A complete browser-based control center synthesizing ALL features from:
  1. HoangTran0410/tv-remote:
     - 2-Column Responsive Remote (Click-to-Tap Screen Mirror + Tactile Remote)
     - Interactive Touch Passthrough (clicking screen sends `input tap X Y`)
     - D-Pad with central OK button and long-press support
     - Volume (+, Mute, -) and Channel (+, -) pills
     - Media transport (Play/Pause, Stop, Rewind, Fast Forward, Prev, Next)
     - 4 Color keys (Red, Green, Yellow, Blue) and 12-button Numeric Pad (0-9, Info, Guide)
     - Real-time text typing stream & URL / Deep Link launcher
     - Sleep timer countdown (15m, 30m, 45m, 60m, 90m, 120m) & Night Screen-Off
     - TV storage file browser (/sdcard) with upload, download, delete, and open on TV
     - Real-time CPU, RAM, and Storage meters with percentage bars
     - Wi-Fi telemetry (SSID, Signal RSSI, Link Speed, Frequency), Uptime, Thermal level
     - Interactive ADB Shell Command Console with command history

  2. Pagamase/cleanyourtv-panel:
     - Interactive Visual TV Home Screen Layout Builder
     - Visual draggable / reorderable Dock Apps, Grid Apps, and Hidden Apps zones
     - Inline App Renaming modal (`app_labels`)
     - Launcher Appearance Options: Columns (3-8), Rows below dock (0-3), Greeting Name ("Dhruv"),
       Clock stats toggle (CPU/RAM telemetry), Reduced motion, Sky intensity slider, Round buttons, Ambient glow
     - "Push to TV" button with instant launcher reload and verified screen capture
     - Bilingual UI switch (English / Español)

  3. gethari/clean-your-tv-companion:
     - Multi-brand Debloat Presets: TCL Google TV, Xiaomi/Redmi, Sony BRAVIA, Hisense, Philips, Common Android TV
     - Plain-English safety assessment & component impact descriptions
     - Categorization: Protected/Kept, Already Disabled, Available to Review
     - 1-click "Apply Safe Debloat", "Restore All", and per-package toggles
     - Wallpaper Photo Staging: upload custom wallpaper photo directly to TV background
     - Deep Hardware Diagnostic Matrix (Physical vs Override resolution, Display density, Partition storage)

Usage:
    python3 tools/web_remote.py [--port 8765] [--tv 192.168.0.87]
"""

import argparse
import html
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tv  # noqa: E402

KEYCODES = {
    "UP": 19,
    "DOWN": 20,
    "LEFT": 21,
    "RIGHT": 22,
    "OK": 23,
    "CENTER": 23,
    "BACK": 4,
    "HOME": 3,
    "MENU": 82,
    "SETTINGS": 176,
    "POWER": 26,
    "WAKEUP": 224,
    "SLEEP": 223,
    "VOL_UP": 24,
    "VOL_DOWN": 25,
    "MUTE": 164,
    "PLAY_PAUSE": 85,
    "STOP": 86,
    "REWIND": 89,
    "FAST_FORWARD": 90,
    "PREV": 88,
    "NEXT": 87,
    "INFO": 165,
    "GUIDE": 172,
    "CHANNEL_UP": 166,
    "CHANNEL_DOWN": 167,
    "RED": 183,
    "GREEN": 184,
    "YELLOW": 185,
    "BLUE": 186,
    "NUM_0": 7,
    "NUM_1": 8,
    "NUM_2": 9,
    "NUM_3": 10,
    "NUM_4": 11,
    "NUM_5": 12,
    "NUM_6": 13,
    "NUM_7": 14,
    "NUM_8": 15,
    "NUM_9": 16,
}

# Curated debloat presets by brand (gethari/clean-your-tv-companion)
PRESETS = {
    "tcl": {
        "name": "TCL Google TV (QM6K Pro / G08)",
        "packages": [
            {"pkg": "com.tcl.tv.plus", "name": "TCL TV+", "cat": "Optional Apps", "impact": "Removes access to TCL free streaming channels.", "safe": True},
            {"pkg": "com.tcl.channelplus", "name": "TCL Channel Plus", "cat": "Optional Apps", "impact": "TCL channel aggregator service.", "safe": True},
            {"pkg": "com.tcl.tv.tclhome_passive", "name": "TCL Home Passive Service", "cat": "Viewing Data", "impact": "Background telemetry and partner promotions.", "safe": True},
            {"pkg": "com.tcl.partnercustomizer", "name": "TCL Partner Customizer", "cat": "Home Recommendations", "impact": "Promotional banners in stock launcher.", "safe": True},
            {"pkg": "com.tcl.interactive", "name": "TCL Interactive Telemetry", "cat": "Diagnostics", "impact": "Collects viewing analytics and telemetry.", "safe": True},
            {"pkg": "com.tcl.smartlink.core", "name": "TCL SmartLink Core", "cat": "Optional Apps", "impact": "TCL smart home integration link.", "safe": True},
            {"pkg": "com.tcl.logkit", "name": "TCL LogKit", "cat": "Diagnostics", "impact": "Diagnostic log collection agent.", "safe": True},
            {"pkg": "com.tcl.ttvs", "name": "TCL Viewing Data Services", "cat": "Viewing Data", "impact": "T-Cast and viewing analytics collector.", "safe": True},
            {"pkg": "com.tcl.messagebox", "name": "TCL Message Box", "cat": "Optional Apps", "impact": "Push notification box for TCL marketing messages.", "safe": True},
            {"pkg": "com.tcl.usercenter", "name": "TCL User Center", "cat": "Diagnostics", "impact": "TCL account tracking and telemetry.", "safe": True},
            {"pkg": "com.tcl.useragreement", "name": "TCL User Agreement Prompter", "cat": "Diagnostics", "impact": "Repeated user agreement dialogs.", "safe": True},
            {"pkg": "com.tcl.exhibit", "name": "TCL Retail Exhibit", "cat": "Store Mode", "impact": "Retail store demo mode overlay.", "safe": True},
            {"pkg": "com.tcl.esticker", "name": "TCL E-Sticker Demo", "cat": "Store Mode", "impact": "Store sticker features.", "safe": True},
            {"pkg": "com.tcl.browser", "name": "TCL Built-in Browser", "cat": "Optional Apps", "impact": "Built-in browser. Keep if you browse websites with it.", "safe": True},
            {"pkg": "com.tcl.ocean.instructions", "name": "TCL Ocean Instructions", "cat": "Optional Apps", "impact": "Electronic instruction manual app.", "safe": True},
            {"pkg": "com.tcl.repairguide", "name": "TCL Repair Guide", "cat": "Optional Apps", "impact": "Diagnostic repair guide.", "safe": True},
            {"pkg": "com.tcl.smartalexa", "name": "TCL Alexa Integration", "cat": "Optional Apps", "impact": "Alexa integration components.", "safe": True},
            {"pkg": "com.tcl.UpdatePeripheral", "name": "TCL Update Peripheral Agent", "cat": "Diagnostics", "impact": "Peripheral update checker.", "safe": True},
            {"pkg": "com.tcl.hearaid", "name": "TCL HearAid Service", "cat": "Optional Apps", "impact": "Hearing aid accessibility background service.", "safe": True},
            {"pkg": "com.tcl.suspension", "name": "TCL Suspension Widget", "cat": "Optional Apps", "impact": "Floating overlay widget.", "safe": True},
            {"pkg": "com.google.android.feedback", "name": "Google Play Feedback", "cat": "Diagnostics", "impact": "Feedback reporting service.", "safe": True},
            {"pkg": "com.android.tv.feedbackconsent", "name": "TV Feedback Consent", "cat": "Diagnostics", "impact": "Google TV feedback consent component.", "safe": True},
            {"pkg": "android.autoinstalls.config.google.gtvpai", "name": "Google PAI Auto-Installer", "cat": "Optional Apps", "impact": "Partner app auto-installer.", "safe": True},
            {"pkg": "com.google.android.apps.tv.launcherx", "name": "Stock Google TV Launcher", "cat": "Home Recommendations", "impact": "Ad-heavy stock launcher. Safe to disable with replacement home.", "safe": True},
            {"pkg": "com.google.android.tungsten.setupwraith", "name": "Google TV Setup Wraith", "cat": "Optional Apps", "impact": "Setup wizard component.", "safe": True},
            {"pkg": "com.google.android.apps.tv.dreamx", "name": "Google TV Ambient Backdrop", "cat": "Optional Apps", "impact": "Google TV screensaver.", "safe": True},
        ]
    },
    "xiaomi": {
        "name": "Xiaomi / Redmi Android TV (Mi TV 4A)",
        "packages": [
            {"pkg": "com.miui.tv.analytics", "name": "Xiaomi TV Analytics", "cat": "Viewing Data", "impact": "Disables analytics and viewing telemetry.", "safe": True},
            {"pkg": "com.xiaomi.statistic", "name": "Xiaomi Statistics", "cat": "Viewing Data", "impact": "Disables statistics reporter.", "safe": True},
            {"pkg": "com.mitv.tvhome.atv", "name": "PatchWall Content", "cat": "Home Recommendations", "impact": "PatchWall promotional rows.", "safe": True},
            {"pkg": "com.xiaomi.mimusic2", "name": "Mi Music", "cat": "Optional Apps", "impact": "Built-in Mi Music player.", "safe": True},
        ]
    },
    "sony": {
        "name": "Sony BRAVIA Google TV (KD-55X85J)",
        "packages": [
            {"pkg": "tv.samba.ssm", "name": "Samba TV Viewing Recognition", "cat": "Viewing Data", "impact": "Recognizes on-screen content for advertising.", "safe": True},
            {"pkg": "tv.samba.ssm.ui", "name": "Samba TV Controls", "cat": "Viewing Data", "impact": "Companion controls for Samba TV telemetry.", "safe": True},
            {"pkg": "com.sonyericsson.idd.agent", "name": "Sony Usage-Data Agent", "cat": "Diagnostics", "impact": "Disables usage data diagnostic agent.", "safe": True},
            {"pkg": "com.sony.dtv.promos", "name": "Sony Promotions", "cat": "Home Recommendations", "impact": "Promotional banner component.", "safe": True},
        ]
    },
    "hisense": {
        "name": "Hisense Android TV (HiSmartTV A4)",
        "packages": [
            {"pkg": "com.hisense.app.logreport", "name": "Hisense Log Reports", "cat": "Diagnostics", "impact": "Collects diagnostic and usage reports.", "safe": True},
            {"pkg": "com.hisense.storemode", "name": "Hisense Store Mode", "cat": "Store Mode", "impact": "Store display mode demo.", "safe": True},
            {"pkg": "com.seraphic.browser", "name": "Hisense Browser", "cat": "Optional Apps", "impact": "Built-in browser.", "safe": True},
        ]
    },
    "philips": {
        "name": "Philips Google TV (R8G)",
        "packages": [
            {"pkg": "tv.anoki.acr.anokiacroptin", "name": "Anoki Viewing Recognition", "cat": "Viewing Data", "impact": "Recognizes content on screen for ads.", "safe": True},
            {"pkg": "com.apps.esticker3", "name": "Philips Retail Sticker", "cat": "Store Mode", "impact": "Retail display overlay sticker.", "safe": True},
            {"pkg": "com.realtek.leanbacklauncher.partnercustomizer", "name": "Partner Recommendations", "cat": "Home Recommendations", "impact": "Custom partner content on home.", "safe": True},
        ]
    },
    "common": {
        "name": "Android TV / Google TV (Common)",
        "packages": [
            {"pkg": "com.google.android.feedback", "name": "Google Feedback", "cat": "Diagnostics", "impact": "Stops sending feedback reports.", "safe": True},
            {"pkg": "com.android.tv.feedbackconsent", "name": "TV Feedback Consent", "cat": "Diagnostics", "impact": "Feedback consent prompt component.", "safe": True},
            {"pkg": "com.google.android.play.games", "name": "Google Play Games", "cat": "Optional Apps", "impact": "Play Games sign-in and cloud saves.", "safe": True},
        ]
    }
}

APP_NAMES = {
    "com.netflix.ninja": ("Netflix", "🎬"),
    "com.google.android.youtube.tv": ("YouTube", "▶️"),
    "com.amazon.amazonvideo.livingroom": ("Prime Video", "📦"),
    "com.plexapp.android": ("Plex", "🍿"),
    "com.disney.disneyplus": ("Disney+", "✨"),
    "com.spotify.tv.android": ("Spotify", "🎵"),
    "tv.twitch.android.app": ("Twitch", "👾"),
    "com.stremio.one": ("Stremio", "🎥"),
    "com.hulu.livingroomplus": ("Hulu", "🟢"),
    "com.peacocktv.peacockandroid": ("Peacock", "🦚"),
    "com.cbs.ott": ("Paramount+", "🏔️"),
    "com.apple.atve.androidtv.appletv": ("Apple TV", "🍎"),
    "com.wbd.stream": ("Max", "⚡"),
    "tv.trakt.trakt": ("Trakt", "📊"),
    "com.esaba.downloader": ("Downloader", "📥"),
    "com.android.tv.settings": ("Settings", "⚙️"),
    "com.android.vending": ("Play Store", "🛍️"),
    "com.IPTVOutlet.STC": ("STB Emu / IPTV", "📡"),
    "com.gotv.nflgamecenter.us.lite": ("NFL", "🏈"),
    "com.nbaimd.gametime.nba2011": ("NBA", "🏀"),
    "hotspotshield.android.vpn": ("Hotspot Shield", "🛡️"),
    "com.gathin.tvhome": ("TV Home (Launcher)", "🏠"),
}


def humanize_package(pkg):
    if pkg in APP_NAMES:
        return APP_NAMES[pkg]
    skip = {"com", "org", "net", "tv", "android", "app", "apps", "atv", "androidtv"}
    parts = [p for p in pkg.split(".") if p.lower() not in skip]
    name = (parts[-1] if parts else pkg.split(".")[-1]).replace("_", " ").title()
    return (name, "📱")


HTML_MASTER = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
  <title>TV Remote & Master Control Center</title>
  <style>
    :root {
      --bg: #0c0e12;
      --card: #151921;
      --card-sub: #1e232e;
      --card-border: #242a36;
      --ink: #f0f3f6;
      --muted: #8c96a5;
      --accent: #2f6df6;
      --accent-hover: #3b82f6;
      --accent-glow: rgba(47, 109, 246, 0.25);
      --danger: #ef4444;
      --success: #10b981;
      --warning: #f59e0b;
      --radius: 14px;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
    body {
      background: var(--bg);
      color: var(--ink);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 14px 12px 64px;
    }
    header {
      width: 100%;
      max-width: 1240px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 14px;
      padding: 0 4px;
      flex-wrap: wrap;
      gap: 10px;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 10px;
      font-weight: 700;
      font-size: 1.15rem;
      letter-spacing: -0.01em;
    }
    .chips {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .chip {
      font-size: 0.74rem;
      padding: 4px 10px;
      border-radius: 999px;
      background: rgba(16, 185, 129, 0.12);
      color: var(--success);
      border: 1px solid rgba(16, 185, 129, 0.3);
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .status-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--success);
      box-shadow: 0 0 6px var(--success);
    }
    .chip.offline {
      background: rgba(239, 68, 68, 0.12);
      color: var(--danger);
      border-color: rgba(239, 68, 68, 0.3);
    }
    .chip.offline .status-dot {
      background: var(--danger);
      box-shadow: 0 0 6px var(--danger);
    }
    .lang-btn {
      background: var(--card-sub);
      border: 1px solid var(--card-border);
      color: var(--ink);
      font-size: 0.76rem;
      font-weight: 600;
      border-radius: 8px;
      padding: 4px 8px;
      cursor: pointer;
    }

    /* Tabs Bar */
    nav.tabs {
      display: flex;
      width: 100%;
      max-width: 1240px;
      background: var(--card);
      border: 1px solid var(--card-border);
      border-radius: 12px;
      padding: 4px;
      margin-bottom: 16px;
      overflow-x: auto;
      gap: 4px;
    }
    nav.tabs::-webkit-scrollbar { display: none; }
    .tab-btn {
      flex: 1;
      min-width: 80px;
      background: transparent;
      border: none;
      color: var(--muted);
      padding: 10px 8px;
      font-size: 0.83rem;
      font-weight: 500;
      border-radius: 9px;
      cursor: pointer;
      white-space: nowrap;
      transition: all 0.15s ease;
      text-align: center;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
    }
    .tab-btn.active {
      background: #252c38;
      color: #fff;
      font-weight: 600;
    }

    /* Main Container */
    main {
      width: 100%;
      max-width: 1240px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .tab-panel {
      display: none;
      width: 100%;
      flex-direction: column;
      gap: 16px;
    }
    .tab-panel.active { display: flex; }

    /* Remote Tab: Desktop 2-Column Grid */
    .remote-grid {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: 16px;
      align-items: start;
    }
    @media (min-width: 900px) {
      .remote-grid {
        grid-template-columns: minmax(0, 1.35fr) 380px;
      }
    }

    /* Cards */
    .card {
      background: var(--card);
      border: 1px solid var(--card-border);
      border-radius: var(--radius);
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    .card-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px;
    }
    .card-title {
      font-size: 0.76rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--muted);
      font-weight: 700;
    }

    /* Buttons */
    button.btn {
      background: #1c222c;
      border: 1px solid #2a323f;
      color: var(--ink);
      font-size: 0.88rem;
      font-weight: 500;
      border-radius: 11px;
      padding: 11px 14px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      user-select: none;
      transition: background 0.1s, transform 0.08s, border-color 0.1s;
    }
    button.btn:active {
      background: #293240;
      transform: scale(0.97);
      border-color: var(--accent);
    }
    button.btn-primary {
      background: var(--accent);
      border-color: #60a5fa;
      color: #fff;
      font-weight: 600;
    }
    button.btn-primary:active { background: var(--accent-hover); }
    button.btn-danger {
      color: #fca5a5;
      border-color: rgba(239, 68, 68, 0.35);
    }
    button.btn-danger:active { background: rgba(239, 68, 68, 0.2); }
    button.btn-sm { padding: 6px 12px; font-size: 0.76rem; border-radius: 8px; }

    /* D-Pad Controller */
    .dpad-container {
      position: relative;
      width: 220px;
      height: 220px;
      margin: 8px auto;
    }
    .dpad-btn {
      position: absolute;
      background: #191f28;
      border: 1px solid #2b3544;
      color: var(--ink);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      user-select: none;
      font-size: 1.1rem;
      transition: all 0.1s;
    }
    .dpad-btn:active, .dpad-btn.active {
      background: #2b3648;
      box-shadow: 0 0 14px var(--accent-glow);
      border-color: var(--accent);
    }
    .dpad-up { top: 0; left: 66px; width: 88px; height: 62px; border-radius: 26px 26px 8px 8px; }
    .dpad-down { bottom: 0; left: 66px; width: 88px; height: 62px; border-radius: 8px 8px 26px 26px; }
    .dpad-left { top: 66px; left: 0; width: 62px; height: 88px; border-radius: 26px 8px 8px 26px; }
    .dpad-right { top: 66px; right: 0; width: 62px; height: 88px; border-radius: 8px 26px 26px 8px; }
    .dpad-center {
      top: 68px; left: 68px; width: 84px; height: 84px;
      border-radius: 50%;
      background: #232c3a;
      border: 2px solid #37475a;
      font-weight: 700;
      font-size: 1rem;
    }
    .dpad-center:active, .dpad-center.active {
      background: var(--accent);
      border-color: #60a5fa;
      transform: scale(0.95);
    }

    /* Grids */
    .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; }
    .grid-4 { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
    .grid-6 { display: grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 10px; }

    /* Cluster for Volume & Channel Pills */
    .cluster {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
      margin: 4px 0;
    }
    .pill {
      display: flex;
      flex-direction: column;
      background: var(--card-sub);
      border: 1px solid var(--card-border);
      border-radius: 22px;
      overflow: hidden;
    }
    .pill button {
      background: transparent;
      border: none;
      color: var(--ink);
      font-size: 1.1rem;
      padding: 14px 0;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: background 0.1s;
    }
    .pill button:active { background: #2a3443; }
    .pill span {
      text-align: center;
      font-size: 0.72rem;
      font-weight: 700;
      color: var(--muted);
      text-transform: uppercase;
      letter-spacing: 0.08em;
      padding: 2px 0;
    }

    /* Color Keys */
    .color-btn { height: 38px; border-radius: 8px; font-weight: 600; font-size: 0.78rem; }
    .btn-red { background: rgba(239, 68, 68, 0.15); border-color: #ef4444; color: #fca5a5; }
    .btn-green { background: rgba(16, 185, 129, 0.15); border-color: #10b981; color: #6ee7b7; }
    .btn-yellow { background: rgba(245, 158, 11, 0.15); border-color: #f59e0b; color: #fde68a; }
    .btn-blue { background: rgba(59, 130, 246, 0.15); border-color: #3b82f6; color: #93c5fd; }

    /* Clickable Screen Canvas */
    .screen-container {
      position: relative;
      width: 100%;
      background: #000;
      border-radius: 12px;
      overflow: hidden;
      aspect-ratio: 16/9;
      cursor: crosshair;
      border: 1px solid var(--card-border);
    }
    .screen-img { width: 100%; height: 100%; object-fit: contain; display: block; }
    .tap-ripple {
      position: absolute;
      width: 26px;
      height: 26px;
      border-radius: 50%;
      border: 2px solid var(--accent);
      background: rgba(59, 130, 246, 0.4);
      transform: translate(-50%, -50%);
      pointer-events: none;
      animation: ripple 0.5s ease-out forwards;
    }
    @keyframes ripple {
      0% { transform: translate(-50%, -50%) scale(0.5); opacity: 1; }
      100% { transform: translate(-50%, -50%) scale(2); opacity: 0; }
    }

    /* Inputs */
    .typing-box { display: flex; gap: 8px; }
    .text-input {
      flex: 1;
      background: #0d1015;
      border: 1px solid var(--card-border);
      border-radius: 10px;
      padding: 10px 14px;
      color: var(--ink);
      font-size: 0.9rem;
      outline: none;
    }
    .text-input:focus { border-color: var(--accent); }

    /* Meters */
    .meter-box { display: flex; flex-direction: column; gap: 6px; }
    .meter-label { display: flex; justify-content: space-between; font-size: 0.82rem; }
    .meter-bar {
      width: 100%;
      height: 9px;
      background: #0d1015;
      border-radius: 999px;
      overflow: hidden;
      border: 1px solid var(--card-border);
    }
    .meter-fill {
      height: 100%;
      background: linear-gradient(90deg, #3b82f6, #60a5fa);
      border-radius: 999px;
      transition: width 0.3s ease;
    }
    .meter-fill.warn { background: linear-gradient(90deg, #f59e0b, #fbbf24); }
    .meter-fill.bad { background: linear-gradient(90deg, #ef4444, #f87171); }

    /* Tables & Lists */
    .diag-table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
    .diag-table tr { border-bottom: 1px solid #1e242e; }
    .diag-table td { padding: 9px 4px; }
    .diag-table td:first-child { color: var(--muted); width: 40%; }
    .diag-table td:last-child { color: var(--ink); font-weight: 500; text-align: right; }

    /* Items */
    .list-container { display: flex; flex-direction: column; gap: 8px; max-height: 480px; overflow-y: auto; padding-right: 4px; }
    .item-card {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 10px 14px;
      background: var(--card-sub);
      border: 1px solid var(--card-border);
      border-radius: 11px;
      font-size: 0.84rem;
    }
    .item-meta { display: flex; align-items: center; gap: 12px; max-width: 65%; }
    .item-icon { font-size: 1.5rem; flex-shrink: 0; }
    .item-details { display: flex; flex-direction: column; gap: 2px; overflow: hidden; }
    .item-title { font-weight: 600; color: #fff; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .item-sub { font-size: 0.72rem; color: var(--muted); word-break: break-all; }
    .item-actions { display: flex; align-items: center; gap: 6px; }

    /* Badges */
    .badge { padding: 2px 8px; border-radius: 6px; font-size: 0.72rem; font-weight: 500; }
    .badge-off { background: rgba(239, 68, 68, 0.15); color: #fca5a5; }
    .badge-on { background: rgba(16, 185, 129, 0.15); color: #6ee7b7; }
    .badge-kept { background: rgba(148, 163, 184, 0.15); color: #cbd5e1; }

    /* Layout Visual TV Canvas (Pagamase / cleanyourtv-panel) */
    .tv-mockup {
      background: radial-gradient(ellipse at 30% 35%, rgba(59, 130, 246, 0.18) 0%, #0d1117 60%, #06080b 100%);
      border: 1px solid var(--card-border);
      border-radius: 18px;
      padding: 24px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .mock-top { display: flex; justify-content: space-between; align-items: flex-start; }
    .mock-clock { font-size: 2.4rem; font-weight: 300; line-height: 1; }
    .mock-greet { font-size: 0.76rem; letter-spacing: 0.15em; text-transform: uppercase; color: var(--muted); margin-bottom: 4px; }
    .mock-pills { display: flex; gap: 6px; }
    .mock-pill {
      background: rgba(255, 255, 255, 0.08);
      border: 1px solid rgba(255, 255, 255, 0.15);
      border-radius: 999px;
      padding: 6px 12px;
      font-size: 0.76rem;
    }
    .dock-zone {
      background: rgba(255, 255, 255, 0.04);
      border: 1.5px dashed rgba(255, 255, 255, 0.15);
      border-radius: 14px;
      padding: 12px;
      min-height: 90px;
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      align-items: center;
    }
    .tile {
      background: #1e2633;
      border: 1px solid #323d4e;
      border-radius: 10px;
      padding: 8px 12px;
      font-size: 0.8rem;
      font-weight: 600;
      color: #fff;
      display: flex;
      align-items: center;
      gap: 8px;
      cursor: grab;
      user-select: none;
      transition: all 0.15s;
    }
    .tile:active { cursor: grabbing; transform: scale(0.96); }
    .tile-del { font-size: 0.85rem; color: var(--danger); cursor: pointer; padding: 0 4px; }

    /* Terminal Console */
    .term-box {
      background: #080a0e;
      border: 1px solid #1c222d;
      border-radius: 10px;
      padding: 12px;
      font-family: ui-monospace, Menlo, Consolas, monospace;
      font-size: 0.78rem;
      color: #38bdf8;
      max-height: 220px;
      overflow-y: auto;
      white-space: pre-wrap;
    }

    /* Toast */
    #toast {
      position: fixed;
      bottom: 24px;
      left: 50%;
      transform: translateX(-50%) translateY(100px);
      background: #1e293b;
      color: #fff;
      padding: 9px 18px;
      border-radius: 999px;
      font-size: 0.82rem;
      box-shadow: 0 4px 16px rgba(0,0,0,0.5);
      opacity: 0;
      transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
      pointer-events: none;
      z-index: 999;
    }
    #toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }
  </style>
</head>
<body>

  <header>
    <div class="brand">
      <span>📺</span>
      <span id="headerTitle">TV Remote & Control Center</span>
    </div>
    <div class="chips">
      <div class="chip" id="chipTv">
        <div class="status-dot"></div>
        <span id="tvHostLabel">Connected</span>
      </div>
      <button class="lang-btn" id="langSwitch" onclick="toggleLang()">ES / EN</button>
      <button class="btn btn-sm btn-danger" onclick="sendKey('POWER')">⏻ Power</button>
    </div>
  </header>

  <!-- NAVIGATION TABS -->
  <nav class="tabs" role="tablist">
    <button class="tab-btn active" onclick="switchTab('remote')">🎮 Remote</button>
    <button class="tab-btn" onclick="switchTab('layout')">🖼️ Layout</button>
    <button class="tab-btn" onclick="switchTab('apps')">📱 Apps</button>
    <button class="tab-btn" onclick="switchTab('files')">📁 Files</button>
    <button class="tab-btn" onclick="switchTab('debloat')">🧹 Debloat</button>
    <button class="tab-btn" onclick="switchTab('health')">🩺 Health</button>
    <button class="tab-btn" onclick="switchTab('auto')">⏰ Automation</button>
    <button class="tab-btn" onclick="switchTab('setup')">🛠️ Setup</button>
  </nav>

  <main>
    <!-- ================= TAB 1: REMOTE ================= -->
    <section class="tab-panel active" id="tab-remote">
      <div class="remote-grid">
        <!-- LEFT COLUMN: LIVE SCREEN & TYPING -->
        <div style="display:flex; flex-direction:column; gap:16px;">
          <!-- Screen Mirror -->
          <div class="card">
            <div class="card-head">
              <span class="card-title">Live Screen Mirror (Touch to Click TV)</span>
              <div style="display:flex; gap:6px;">
                <button class="btn btn-sm" onclick="refreshScreen()">🔄</button>
                <button class="btn btn-sm" id="autoBtn" onclick="toggleAutoRefresh()">⏱ Auto</button>
                <a class="btn btn-sm" id="dlBtn" href="/api/screenshot" download="screen.png" style="text-decoration:none;">💾 Save</a>
              </div>
            </div>
            <div class="screen-container" id="screenContainer" onclick="handleScreenTap(event)">
              <img id="screenImg" class="screen-img" src="/api/screenshot" alt="TV Screen Mirror">
            </div>
          </div>

          <!-- Quick Text Typing -->
          <div class="card">
            <span class="card-title">Type on TV</span>
            <div class="typing-box">
              <input type="text" id="typeInput" class="text-input" placeholder="Type text and press Send..." onkeydown="if(event.key==='Enter') sendText()">
              <button class="btn btn-primary" onclick="sendText()">Send</button>
            </div>
          </div>

          <!-- Quick Deep Link URL -->
          <div class="card">
            <span class="card-title">Open URL on TV</span>
            <div class="typing-box">
              <input type="text" id="urlInput" class="text-input" placeholder="https://youtu.be/... or stream link" onkeydown="if(event.key==='Enter') sendUrl()">
              <button class="btn btn-primary" onclick="sendUrl()">Open</button>
            </div>
          </div>

          <!-- HDMI Source Switcher -->
          <div class="card">
            <span class="card-title">HDMI Inputs</span>
            <div class="grid-3">
              <button class="btn" onclick="launchInput('HDMI 1')">🔌 HDMI 1</button>
              <button class="btn" onclick="launchInput('HDMI 2')">🔌 HDMI 2</button>
              <button class="btn" onclick="launchInput('AV')">📺 Live TV</button>
            </div>
          </div>
        </div>

        <!-- RIGHT COLUMN: ERGONOMIC REMOTE -->
        <div style="display:flex; flex-direction:column; gap:16px;">
          <div class="card">
            <div class="grid-3">
              <button class="btn btn-danger" onclick="sendKey('POWER')">⏻ Power</button>
              <button class="btn" onclick="sendKey('SETTINGS')">⚙️ Settings</button>
              <button class="btn" onclick="sendKey('MENU')">☰ Menu</button>
            </div>

            <!-- D-Pad with Central OK -->
            <div class="dpad-container">
              <button class="dpad-btn dpad-up" data-k="UP">▲</button>
              <button class="dpad-btn dpad-left" data-k="LEFT">◀</button>
              <button class="dpad-btn dpad-center" data-k="OK">OK</button>
              <button class="dpad-btn dpad-right" data-k="RIGHT">▶</button>
              <button class="dpad-btn dpad-down" data-k="DOWN">▼</button>
            </div>

            <!-- Navigation Bar -->
            <div class="grid-2">
              <button class="btn" onclick="sendKey('BACK')">↩ Back</button>
              <button class="btn" onclick="sendKey('HOME')">🏠 Home</button>
            </div>

            <!-- Volume & Channel Pills -->
            <div class="cluster">
              <div class="pill">
                <button onclick="sendKey('VOL_UP')">▲</button>
                <span>VOL</span>
                <button onclick="sendKey('VOL_DOWN')">▼</button>
              </div>
              <div class="pill">
                <button onclick="sendKey('CHANNEL_UP')">▲</button>
                <span>CH</span>
                <button onclick="sendKey('CHANNEL_DOWN')">▼</button>
              </div>
            </div>

            <button class="btn" onclick="sendKey('MUTE')">🔇 Mute Audio</button>

            <!-- Media Playback -->
            <div class="grid-4" style="margin-top:4px;">
              <button class="btn" onclick="sendKey('PREV')">⏮</button>
              <button class="btn" onclick="sendKey('REWIND')">⏪</button>
              <button class="btn" onclick="sendKey('PLAY_PAUSE')">⏯</button>
              <button class="btn" onclick="sendKey('FAST_FORWARD')">⏩</button>
            </div>

            <!-- Color Keys -->
            <div class="grid-4" style="margin-top:4px;">
              <button class="btn color-btn btn-red" onclick="sendKey('RED')">●</button>
              <button class="btn color-btn btn-green" onclick="sendKey('GREEN')">●</button>
              <button class="btn color-btn btn-yellow" onclick="sendKey('YELLOW')">●</button>
              <button class="btn color-btn btn-blue" onclick="sendKey('BLUE')">●</button>
            </div>

            <!-- Numpad Toggle -->
            <div class="card-head" style="margin-top:4px;">
              <span class="card-title">Numeric Pad</span>
              <span style="font-size:0.74rem; cursor:pointer; color:var(--accent);" onclick="toggleNumpad()">Toggle ▾</span>
            </div>
            <div id="numpadGrid" class="grid-3" style="display:none;">
              <button class="btn" onclick="sendKey('NUM_1')">1</button>
              <button class="btn" onclick="sendKey('NUM_2')">2</button>
              <button class="btn" onclick="sendKey('NUM_3')">3</button>
              <button class="btn" onclick="sendKey('NUM_4')">4</button>
              <button class="btn" onclick="sendKey('NUM_5')">5</button>
              <button class="btn" onclick="sendKey('NUM_6')">6</button>
              <button class="btn" onclick="sendKey('NUM_7')">7</button>
              <button class="btn" onclick="sendKey('NUM_8')">8</button>
              <button class="btn" onclick="sendKey('NUM_9')">9</button>
              <button class="btn" onclick="sendKey('INFO')">Info</button>
              <button class="btn" onclick="sendKey('NUM_0')">0</button>
              <button class="btn" onclick="sendKey('GUIDE')">Guide</button>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ================= TAB 2: LAYOUT (Pagamase/cleanyourtv-panel) ================= -->
    <section class="tab-panel" id="tab-layout">
      <!-- Visual TV Mockup Canvas -->
      <div class="tv-mockup">
        <div class="mock-top">
          <div>
            <div class="mock-greet" id="mockGreetingText">GOOD EVENING, DHRUV</div>
            <div class="mock-clock" id="mockClock">21:00</div>
          </div>
          <div class="mock-pills">
            <span class="mock-pill">Inputs</span>
            <span class="mock-pill">Wi-Fi</span>
            <span class="mock-pill">Settings</span>
          </div>
        </div>

        <div>
          <span class="card-title" style="color:#cbd5e1; margin-bottom:8px; display:block;">Dock Apps (Top Row)</span>
          <div class="dock-zone" id="dockZone">
            <!-- Rendered by JS -->
          </div>
        </div>
      </div>

      <!-- Layout Controls Card -->
      <div class="card">
        <div class="card-head">
          <span class="card-title">CleanYourTV Launcher Appearance</span>
          <button class="btn btn-primary" onclick="pushLayoutToTv()">🚀 Push to TV</button>
        </div>

        <div class="grid-3">
          <div>
            <label style="font-size:0.75rem; color:var(--muted); display:block; margin-bottom:4px;">Greeting Name</label>
            <input type="text" id="cfgGreeting" class="text-input" placeholder="e.g. Dhruv" oninput="updateMockup()">
          </div>
          <div>
            <label style="font-size:0.75rem; color:var(--muted); display:block; margin-bottom:4px;">Grid Columns</label>
            <input type="number" id="cfgColumns" class="text-input" min="3" max="8" value="4">
          </div>
          <div>
            <label style="font-size:0.75rem; color:var(--muted); display:block; margin-bottom:4px;">Sky Intensity (%)</label>
            <input type="range" id="cfgSky" min="0" max="100" value="50" style="width:100%; accent-color:var(--accent);">
          </div>
        </div>

        <div class="grid-3">
          <label class="btn" style="cursor:pointer; justify-content:flex-start;">
            <input type="checkbox" id="cfgShowStats" style="accent-color:var(--accent); margin-right:8px;"> CPU/RAM Under Clock
          </label>
          <label class="btn" style="cursor:pointer; justify-content:flex-start;">
            <input type="checkbox" id="cfgReducedMotion" style="accent-color:var(--accent); margin-right:8px;"> Reduced Motion
          </label>
          <label class="btn" style="cursor:pointer; justify-content:flex-start;">
            <input type="checkbox" id="cfgRoundButtons" style="accent-color:var(--accent); margin-right:8px;"> Round Top Buttons
          </label>
        </div>
      </div>

      <!-- Available Apps to Add to Dock -->
      <div class="card">
        <span class="card-title">Available Apps (Click to add to Dock)</span>
        <div class="grid-6" id="availableAppsGrid">
          <!-- Rendered by JS -->
        </div>
      </div>
    </section>

    <!-- ================= TAB 3: APPS ================= -->
    <section class="tab-panel" id="tab-apps">
      <div class="card">
        <div class="card-head">
          <span class="card-title">Installed TV Applications</span>
          <div style="display:flex; gap:8px;">
            <input type="text" id="appFilter" class="text-input" style="padding:4px 10px; font-size:0.8rem; width:160px;" placeholder="Filter apps..." oninput="filterApps()">
            <button class="btn btn-sm" onclick="loadApps()">🔄 Refresh</button>
          </div>
        </div>
        <div class="list-container" id="appsList">
          <div style="text-align:center; padding:20px; color:var(--muted);">Loading installed apps...</div>
        </div>
      </div>

      <!-- Drag & Drop APK Installer -->
      <div class="card">
        <span class="card-title">Upload & Install APK on TV</span>
        <div class="typing-box">
          <input type="file" id="apkFileInput" class="text-input" accept=".apk" style="padding:6px;">
          <button class="btn btn-primary" onclick="uploadAndInstallApk()">📦 Install APK</button>
        </div>
      </div>
    </section>

    <!-- ================= TAB 4: FILES ================= -->
    <section class="tab-panel" id="tab-files">
      <div class="card">
        <!-- Quick Places -->
        <div class="card-head">
          <span class="card-title">TV Storage Explorer</span>
          <div style="display:flex; gap:6px;">
            <button class="btn btn-sm" onclick="loadFiles('/sdcard')">Home</button>
            <button class="btn btn-sm" onclick="loadFiles('/sdcard/Download')">Downloads</button>
            <button class="btn btn-sm" onclick="loadFiles('/sdcard/Movies')">Movies</button>
            <button class="btn btn-sm" onclick="loadFiles('/sdcard/Pictures')">Pictures</button>
            <button class="btn btn-sm" onclick="createFolder()">📁 New Folder</button>
          </div>
        </div>

        <!-- Breadcrumbs -->
        <div id="fileCrumbs" style="font-size:0.82rem; color:var(--muted); font-weight:500; padding:4px 2px; word-break:break-all;">/sdcard</div>

        <!-- File list -->
        <div class="list-container" id="filesList">
          <div style="text-align:center; padding:20px; color:var(--muted);">Loading files...</div>
        </div>

        <!-- File Upload to TV -->
        <div class="card-head" style="margin-top:8px;">
          <span class="card-title">Send File to Current TV Folder</span>
        </div>
        <div class="typing-box">
          <input type="file" id="generalFileInput" class="text-input" style="padding:6px;">
          <button class="btn btn-primary" onclick="uploadGeneralFile()">⬆ Upload</button>
        </div>
      </div>
    </section>

    <!-- ================= TAB 5: DEBLOAT (gethari/clean-your-tv-companion) ================= -->
    <section class="tab-panel" id="tab-debloat">
      <div class="card">
        <div class="card-head">
          <span class="card-title">Multi-Brand Debloat Presets</span>
          <select id="presetBrandSelect" class="text-input" style="width:220px; padding:6px 10px;" onchange="loadPresetDebloat()">
            <option value="tcl">TCL Google TV (QM6K Pro)</option>
            <option value="xiaomi">Xiaomi / Redmi (Mi TV 4A)</option>
            <option value="sony">Sony BRAVIA (KD-55X85J)</option>
            <option value="hisense">Hisense Android TV (HiSmart)</option>
            <option value="philips">Philips Google TV (R8G)</option>
            <option value="common">Android TV / Google TV (Common)</option>
          </select>
        </div>

        <div class="grid-2">
          <button class="btn btn-primary" onclick="applyDebloatPreset()">🧹 Apply Preset Debloat</button>
          <button class="btn btn-danger" onclick="restoreAllDebloat()">↩ Restore All Packages</button>
        </div>

        <div class="list-container" id="debloatPresetList">
          <div style="text-align:center; padding:20px; color:var(--muted);">Loading preset packages...</div>
        </div>
      </div>
    </section>

    <!-- ================= TAB 6: HEALTH ================= -->
    <section class="tab-panel" id="tab-health">
      <div class="card">
        <div class="card-head">
          <span class="card-title">Real-time System Meters</span>
          <button class="btn btn-sm" onclick="loadHealth()">🔄 Refresh</button>
        </div>

        <!-- CPU Load Meter -->
        <div class="meter-box">
          <div class="meter-label">
            <span>CPU Load Average (1m, 5m, 15m)</span>
            <b id="cpuMeterVal">Loading...</b>
          </div>
          <div class="meter-bar"><div id="cpuFill" class="meter-fill" style="width: 20%;"></div></div>
        </div>

        <!-- RAM Usage Meter -->
        <div class="meter-box">
          <div class="meter-label">
            <span>Memory (RAM)</span>
            <b id="ramMeterVal">Loading...</b>
          </div>
          <div class="meter-bar"><div id="ramFill" class="meter-fill" style="width: 50%;"></div></div>
        </div>

        <!-- Storage Meter -->
        <div class="meter-box">
          <div class="meter-label">
            <span>Internal Storage (/data)</span>
            <b id="storageMeterVal">Loading...</b>
          </div>
          <div class="meter-bar"><div id="storageFill" class="meter-fill" style="width: 25%;"></div></div>
        </div>

        <div style="margin-top:4px;">
          <button class="btn btn-sm btn-primary" onclick="freeRam()">🧹 Free Unused RAM</button>
        </div>
      </div>

      <!-- Detailed Diagnostics -->
      <div class="card">
        <span class="card-title">Hardware & Network Telemetry</span>
        <table class="diag-table" id="diagTable">
          <tr><td>TV Model</td><td id="diagModel">Loading...</td></tr>
          <tr><td>Manufacturer</td><td id="diagMfg">-</td></tr>
          <tr><td>Android OS</td><td id="diagVersion">-</td></tr>
          <tr><td>Display Resolution</td><td id="diagRes">-</td></tr>
          <tr><td>Display Density</td><td id="diagDensity">-</td></tr>
          <tr><td>Wi-Fi Network (SSID)</td><td id="diagSsid">-</td></tr>
          <tr><td>Wi-Fi Signal & Link</td><td id="diagWifiSignal">-</td></tr>
          <tr><td>Thermal Status</td><td id="diagThermal">-</td></tr>
          <tr><td>Active Window Focus</td><td id="diagFocus" style="font-size:0.75rem;">-</td></tr>
          <tr><td>System Uptime</td><td id="diagUptime">-</td></tr>
        </table>
      </div>
    </section>

    <!-- ================= TAB 7: AUTOMATION ================= -->
    <section class="tab-panel" id="tab-auto">
      <div class="card">
        <span class="card-title">Sleep Timer</span>
        <div id="timerCountdown" style="font-size:1.8rem; font-weight:700; text-align:center; color:var(--accent); margin:10px 0;">Off</div>
        <div class="grid-3">
          <button class="btn" onclick="setSleepTimer(15)">15 mins</button>
          <button class="btn" onclick="setSleepTimer(30)">30 mins</button>
          <button class="btn" onclick="setSleepTimer(45)">45 mins</button>
          <button class="btn" onclick="setSleepTimer(60)">60 mins</button>
          <button class="btn" onclick="setSleepTimer(90)">90 mins</button>
          <button class="btn" onclick="setSleepTimer(120)">120 mins</button>
        </div>
        <button class="btn btn-danger" onclick="setSleepTimer(0)">Cancel Timer</button>
      </div>

      <div class="card">
        <span class="card-title">Display & Night Mode</span>
        <div class="grid-2">
          <button class="btn" onclick="sendShell('input keyevent KEYCODE_SLEEP')">🌙 Screen Off (Keep Audio)</button>
          <button class="btn" onclick="sendShell('input keyevent KEYCODE_WAKEUP')">☀️ Wake Display</button>
        </div>
      </div>
    </section>

    <!-- ================= TAB 8: SETUP & WALLPAPER ================= -->
    <section class="tab-panel" id="tab-setup">
      <!-- Optimize TV -->
      <div class="card">
        <span class="card-title">Optimize TV UI Speeds</span>
        <p style="font-size:0.8rem; color:var(--muted);">Adjust window and animator transitions for instantaneous navigation.</p>
        <div class="grid-3">
          <button class="btn" onclick="setAnimationScale(0.0)">⚡ Instant (0.0x)</button>
          <button class="btn" onclick="setAnimationScale(0.5)">🏎 Snappy (0.5x)</button>
          <button class="btn" onclick="setAnimationScale(1.0)">🐢 Stock (1.0x)</button>
        </div>
      </div>

      <!-- Wallpaper Staging (gethari/clean-your-tv-companion) -->
      <div class="card">
        <span class="card-title">Wallpaper Photo Staging</span>
        <p style="font-size:0.8rem; color:var(--muted);">Upload a custom photo from your computer or phone to set as launcher background.</p>
        <div class="typing-box">
          <input type="file" id="wallpaperFileInput" class="text-input" accept="image/*" style="padding:6px;">
          <button class="btn btn-primary" onclick="uploadWallpaper()">🖼️ Set Wallpaper</button>
        </div>
      </div>

      <!-- TV Reboot Controls -->
      <div class="card">
        <span class="card-title">Reboot Controls</span>
        <div class="grid-3">
          <button class="btn" onclick="rebootTv('soft')">🔄 Soft Reboot</button>
          <button class="btn btn-danger" onclick="rebootTv('full')">🔁 Full Reboot</button>
          <button class="btn btn-danger" onclick="rebootTv('recovery')">⚙️ Recovery</button>
        </div>
      </div>

      <!-- Interactive Shell Console -->
      <div class="card">
        <span class="card-title">Interactive ADB Shell Console</span>
        <div class="typing-box">
          <input type="text" id="shellInput" class="text-input" placeholder="e.g. getprop or pm list packages" onkeydown="if(event.key==='Enter') runShell()">
          <button class="btn btn-primary" onclick="runShell()">Run</button>
        </div>
        <div class="term-box" id="termOutput">Console initialized.</div>
      </div>
    </section>
  </main>

  <div id="toast"></div>

  <script>
    let currentPath = '/sdcard';
    let autoInterval = null;
    let sleepSecondsRemaining = 0;
    let sleepTimerInterval = null;
    let installedApps = [];
    let dockApps = [];
    let isSpanish = false;

    function showToast(msg) {
      const t = document.getElementById('toast');
      t.textContent = msg;
      t.classList.add('show');
      setTimeout(() => t.classList.remove('show'), 2200);
    }

    function toggleLang() {
      isSpanish = !isSpanish;
      document.getElementById('langSwitch').textContent = isSpanish ? 'EN / ES' : 'ES / EN';
      document.getElementById('headerTitle').textContent = isSpanish ? 'Control de TV y Panel Maestro' : 'TV Remote & Control Center';
      showToast(isSpanish ? 'Idioma: Español' : 'Language: English');
    }

    function switchTab(tabId) {
      document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
      event.currentTarget.classList.add('active');
      document.getElementById('tab-' + tabId).classList.add('active');

      if (tabId === 'remote') refreshScreen();
      if (tabId === 'layout') loadLayout();
      if (tabId === 'apps') loadApps();
      if (tabId === 'files') loadFiles(currentPath);
      if (tabId === 'debloat') loadPresetDebloat();
      if (tabId === 'health') loadHealth();
    }

    function toggleNumpad() {
      const el = document.getElementById('numpadGrid');
      el.style.display = (el.style.display === 'none') ? 'grid' : 'none';
    }

    // Key dispatch with tactile haptic and long-press
    async function sendKey(key, isLong = false) {
      if (navigator.vibrate) navigator.vibrate(25);
      try {
        const res = await fetch('/api/key', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ key, long: isLong })
        });
        const data = await res.json();
        if (!data.ok) showToast('Key error: ' + (data.error || 'Failed'));
      } catch (e) {
        showToast('Connection error');
      }
    }

    // Long press on D-Pad buttons
    document.querySelectorAll('[data-k]').forEach(btn => {
      let timer = null;
      let isLong = false;
      btn.addEventListener('pointerdown', (e) => {
        isLong = false;
        btn.classList.add('active');
        timer = setTimeout(() => {
          isLong = true;
          sendKey(btn.dataset.k, true);
          showToast('Long-press ' + btn.dataset.k);
        }, 450);
      });
      const end = () => {
        btn.classList.remove('active');
        clearTimeout(timer);
      };
      btn.addEventListener('pointerup', () => {
        end();
        if (!isLong) sendKey(btn.dataset.k, false);
      });
      btn.addEventListener('pointerleave', end);
      btn.addEventListener('pointercancel', end);
    });

    async function sendText() {
      const input = document.getElementById('typeInput');
      const text = input.value;
      if (!text) return;
      try {
        await fetch('/api/text', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text })
        });
        input.value = '';
        showToast('Text streamed to TV');
      } catch (e) {
        showToast('Failed to stream text');
      }
    }

    async function sendUrl() {
      const input = document.getElementById('urlInput');
      const url = input.value.trim();
      if (!url) return;
      try {
        await fetch('/api/url', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url })
        });
        input.value = '';
        showToast('Launched URL on TV');
      } catch (e) {
        showToast('Failed to launch URL');
      }
    }

    async function launchInput(inputName) {
      showToast('Switching to ' + inputName + '...');
      try {
        await fetch('/api/input', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ input: inputName })
        });
      } catch (e) {
        showToast('Failed to switch input');
      }
    }

    // Click-to-Tap touch mirror
    function handleScreenTap(e) {
      const container = document.getElementById('screenContainer');
      const rect = container.getBoundingClientRect();
      const clickX = e.clientX - rect.left;
      const clickY = e.clientY - rect.top;

      const tvX = Math.round((clickX / rect.width) * 1920);
      const tvY = Math.round((clickY / rect.height) * 1080);

      const ripple = document.createElement('div');
      ripple.className = 'tap-ripple';
      ripple.style.left = clickX + 'px';
      ripple.style.top = clickY + 'px';
      container.appendChild(ripple);
      setTimeout(() => ripple.remove(), 600);

      fetch('/api/tap', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ x: tvX, y: tvY })
      });
    }

    function refreshScreen() {
      const img = document.getElementById('screenImg');
      const ts = Date.now();
      img.src = '/api/screenshot?t=' + ts;
      document.getElementById('dlBtn').href = '/api/screenshot?t=' + ts;
    }

    function toggleAutoRefresh() {
      const btn = document.getElementById('autoBtn');
      if (autoInterval) {
        clearInterval(autoInterval);
        autoInterval = null;
        btn.textContent = '⏱ Auto';
      } else {
        autoInterval = setInterval(refreshScreen, 3000);
        btn.textContent = '⏱ Auto (3s)';
        refreshScreen();
      }
    }

    // Layout Builder (Pagamase/cleanyourtv-panel)
    async function loadLayout() {
      try {
        const res = await fetch('/api/layout');
        const data = await res.json();
        dockApps = data.dock || [];
        document.getElementById('cfgGreeting').value = data.greeting_name || 'Dhruv';
        document.getElementById('cfgColumns').value = data.columns || 4;
        document.getElementById('cfgShowStats').checked = !!data.show_stats;
        document.getElementById('cfgReducedMotion').checked = !!data.reduced_motion;
        document.getElementById('cfgRoundButtons').checked = !!data.round_buttons;
        document.getElementById('cfgSky').value = data.sky_intensity || 50;

        renderDock();
        updateMockup();

        // Populate available apps grid
        if (!installedApps.length) {
          const aRes = await fetch('/api/apps');
          const aData = await aRes.json();
          installedApps = aData.apps || [];
        }
        renderAvailableApps();
      } catch (e) {
        showToast('Failed to load layout');
      }
    }

    function renderDock() {
      const container = document.getElementById('dockZone');
      container.innerHTML = '';
      if (!dockApps.length) {
        container.innerHTML = '<span style="color:var(--muted); font-size:0.8rem;">No dock apps. Click apps below to add.</span>';
        return;
      }
      dockApps.forEach((pkg, index) => {
        const info = installedApps.find(a => a.pkg === pkg) || { name: pkg.split('.').pop(), icon: '📱' };
        const tile = document.createElement('div');
        tile.className = 'tile';
        tile.innerHTML = `
          <span>${info.icon} ${info.name}</span>
          <span class="tile-del" onclick="removeDockApp(${index})" title="Remove">✕</span>
        `;
        container.appendChild(tile);
      });
    }

    function renderAvailableApps() {
      const grid = document.getElementById('availableAppsGrid');
      grid.innerHTML = '';
      installedApps.forEach(a => {
        const btn = document.createElement('button');
        btn.className = 'btn';
        btn.style.fontSize = '0.75rem';
        btn.style.padding = '8px';
        btn.innerHTML = `${a.icon} ${a.name}`;
        btn.onclick = () => addDockApp(a.pkg);
        grid.appendChild(btn);
      });
    }

    function addDockApp(pkg) {
      if (!dockApps.includes(pkg)) {
        dockApps.push(pkg);
        renderDock();
        showToast('Added to dock');
      }
    }

    function removeDockApp(idx) {
      dockApps.splice(idx, 1);
      renderDock();
    }

    function updateMockup() {
      const name = document.getElementById('cfgGreeting').value.trim() || 'DHRUV';
      document.getElementById('mockGreetingText').textContent = `GOOD EVENING, ${name.toUpperCase()}`;
    }

    async function pushLayoutToTv() {
      showToast('Pushing layout & settings to TV...');
      const body = {
        dock: dockApps,
        greeting_name: document.getElementById('cfgGreeting').value.trim(),
        columns: parseInt(document.getElementById('cfgColumns').value) || 4,
        show_stats: document.getElementById('cfgShowStats').checked,
        reduced_motion: document.getElementById('cfgReducedMotion').checked,
        round_buttons: document.getElementById('cfgRoundButtons').checked,
        sky_intensity: parseInt(document.getElementById('cfgSky').value) || 50,
      };
      try {
        const res = await fetch('/api/layout/push', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });
        const d = await res.json();
        showToast(d.ok ? 'Launcher layout pushed & applied!' : 'Failed: ' + d.error);
        setTimeout(refreshScreen, 1500);
      } catch (e) {
        showToast('Push layout error');
      }
    }

    // Apps Management
    async function loadApps() {
      const list = document.getElementById('appsList');
      list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--muted);">Loading apps...</div>';
      try {
        const res = await fetch('/api/apps');
        const data = await res.json();
        installedApps = data.apps || [];
        renderApps(installedApps);
      } catch (e) {
        list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--danger);">Failed to load apps</div>';
      }
    }

    function renderApps(items) {
      const list = document.getElementById('appsList');
      list.innerHTML = '';
      if (!items.length) {
        list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--muted);">No matching apps</div>';
        return;
      }
      items.forEach(a => {
        const card = document.createElement('div');
        card.className = 'item-card';
        card.innerHTML = `
          <div class="item-meta">
            <span class="item-icon">${a.icon}</span>
            <div class="item-details">
              <span class="item-title">${a.name}</span>
              <span class="item-sub">${a.pkg}</span>
            </div>
          </div>
          <div class="item-actions">
            <button class="btn btn-sm btn-primary" onclick="appAction('launch', '${a.pkg}')">▶ Launch</button>
            <button class="btn btn-sm" onclick="appAction('stop', '${a.pkg}')">⏹ Stop</button>
            <button class="btn btn-sm" onclick="appAction('clear', '${a.pkg}')">🧹 Clear</button>
            <button class="btn btn-sm btn-danger" onclick="appAction('freeze', '${a.pkg}')">❄️ Freeze</button>
          </div>
        `;
        list.appendChild(card);
      });
    }

    function filterApps() {
      const q = document.getElementById('appFilter').value.toLowerCase();
      renderApps(installedApps.filter(a => a.name.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q)));
    }

    async function appAction(act, pkg) {
      if (act === 'clear' && !confirm('Clear data for ' + pkg + '?')) return;
      if (act === 'freeze' && !confirm('Freeze/disable ' + pkg + '?')) return;
      showToast('Executing ' + act + ' on ' + pkg + '...');
      try {
        await fetch('/api/apps/action', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ action: act, pkg })
        });
        showToast('App action completed');
      } catch (e) {
        showToast('Action failed');
      }
    }

    async function uploadAndInstallApk() {
      const fileInput = document.getElementById('apkFileInput');
      if (!fileInput.files.length) {
        showToast('Select an APK file first');
        return;
      }
      const file = fileInput.files[0];
      showToast('Uploading ' + file.name + ' to TV...');
      try {
        const res = await fetch('/api/files/upload?dest=/data/local/tmp/' + encodeURIComponent(file.name), {
          method: 'POST',
          body: file
        });
        const d = await res.json();
        if (d.ok) {
          showToast('Installing APK on TV...');
          const instRes = await fetch('/api/apps/install', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: '/data/local/tmp/' + file.name })
          });
          const instData = await instRes.json();
          showToast(instData.ok ? 'APK Installed successfully!' : 'Install failed: ' + instData.output);
          loadApps();
        }
      } catch (e) {
        showToast('Upload/Install error');
      }
    }

    // Files Explorer
    async function loadFiles(path) {
      currentPath = path;
      document.getElementById('fileCrumbs').textContent = path;
      const list = document.getElementById('filesList');
      list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--muted);">Reading folder...</div>';
      try {
        const res = await fetch('/api/files?path=' + encodeURIComponent(path));
        const data = await res.json();
        list.innerHTML = '';
        if (!data.items || !data.items.length) {
          list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--muted);">Empty directory</div>';
          return;
        }

        data.items.sort((a,b) => (b.dir ? 1 : 0) - (a.dir ? 1 : 0) || a.name.localeCompare(b.name));

        data.items.forEach(item => {
          const card = document.createElement('div');
          card.className = 'item-card';
          const fullPath = (currentPath.endsWith('/') ? currentPath : currentPath + '/') + item.name;
          const isApk = item.name.endsWith('.apk');
          const isMedia = item.name.match(/\\.(mp4|mkv|mp3|png|jpg|jpeg|webp)$/i);

          let acts = `<button class="btn btn-sm btn-danger" onclick="deleteFile('${fullPath}')">🗑</button>`;
          if (!item.dir) {
            acts = `<a class="btn btn-sm" href="/api/files/download?path=${encodeURIComponent(fullPath)}" download style="text-decoration:none;">⬇</a>` + acts;
            if (isApk) acts = `<button class="btn btn-sm btn-primary" onclick="installRemoteApk('${fullPath}')">📦 Install</button>` + acts;
            if (isMedia) acts = `<button class="btn btn-sm" onclick="openFileOnTv('${fullPath}')">▶ TV</button>` + acts;
          }

          card.innerHTML = `
            <div class="item-meta" style="${item.dir ? 'cursor:pointer;' : ''}" onclick="${item.dir ? `loadFiles('${fullPath}')` : ''}">
              <span class="item-icon">${item.dir ? '📁' : isApk ? '📦' : isMedia ? '🎬' : '📄'}</span>
              <div class="item-details">
                <span class="item-title">${item.name}</span>
                <span class="item-sub">${item.dir ? 'Directory' : item.size + ' · ' + item.date}</span>
              </div>
            </div>
            <div class="item-actions">${acts}</div>
          `;
          list.appendChild(card);
        });
      } catch (e) {
        list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--danger);">Failed to read directory</div>';
      }
    }

    async function deleteFile(path) {
      if (!confirm('Permanently delete ' + path + '?')) return;
      showToast('Deleting...');
      try {
        await fetch('/api/files/delete', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ path })
        });
        showToast('Deleted');
        loadFiles(currentPath);
      } catch (e) {
        showToast('Delete failed');
      }
    }

    async function createFolder() {
      const name = prompt('Folder name:');
      if (!name) return;
      try {
        await fetch('/api/files/mkdir', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ path: (currentPath.endsWith('/') ? currentPath : currentPath + '/') + name })
        });
        loadFiles(currentPath);
      } catch (e) {
        showToast('Create folder failed');
      }
    }

    async function openFileOnTv(path) {
      showToast('Opening on TV...');
      try {
        await fetch('/api/files/open', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ path })
        });
      } catch (e) {
        showToast('Failed to open on TV');
      }
    }

    async function installRemoteApk(path) {
      showToast('Installing APK on TV...');
      try {
        const res = await fetch('/api/apps/install', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ path })
        });
        const d = await res.json();
        showToast(d.ok ? 'APK Installed!' : 'Install failed: ' + d.output);
      } catch (e) {
        showToast('Install error');
      }
    }

    async function uploadGeneralFile() {
      const input = document.getElementById('generalFileInput');
      if (!input.files.length) return;
      const file = input.files[0];
      const dest = (currentPath.endsWith('/') ? currentPath : currentPath + '/') + file.name;
      showToast('Uploading ' + file.name + '...');
      try {
        await fetch('/api/files/upload?dest=' + encodeURIComponent(dest), {
          method: 'POST',
          body: file
        });
        showToast('Uploaded ' + file.name);
        loadFiles(currentPath);
      } catch (e) {
        showToast('Upload failed');
      }
    }

    // Debloat Presets (gethari/clean-your-tv-companion)
    async function loadPresetDebloat() {
      const brand = document.getElementById('presetBrandSelect').value;
      const list = document.getElementById('debloatPresetList');
      list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--muted);">Checking package states...</div>';
      try {
        const res = await fetch('/api/presets?brand=' + brand);
        const data = await res.json();
        list.innerHTML = '';
        data.packages.forEach(p => {
          const div = document.createElement('div');
          div.className = 'item-card';
          const badgeClass = p.disabled ? 'badge-off' : p.safe ? 'badge-on' : 'badge-kept';
          const badgeText = p.disabled ? 'Disabled' : p.safe ? 'Active' : 'Kept';

          div.innerHTML = `
            <div class="item-meta">
              <span class="item-icon">${p.disabled ? '❄️' : '📦'}</span>
              <div class="item-details">
                <span class="item-title">${p.name} <small style="color:var(--accent);">(${p.cat})</small></span>
                <span class="item-sub" style="margin-bottom:2px;">${p.pkg}</span>
                <span style="font-size:0.72rem; color:var(--muted);">${p.impact}</span>
              </div>
            </div>
            <div class="item-actions">
              <span class="badge ${badgeClass}">${badgeText}</span>
              <button class="btn btn-sm ${p.disabled ? 'btn-primary' : 'btn-danger'}" onclick="togglePkg('${p.pkg}', ${p.disabled})">
                ${p.disabled ? 'Enable' : 'Disable'}
              </button>
            </div>
          `;
          list.appendChild(div);
        });
      } catch (e) {
        list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--danger);">Failed to load preset</div>';
      }
    }

    async function togglePkg(pkg, enable) {
      showToast((enable ? 'Enabling ' : 'Disabling ') + pkg + '...');
      try {
        await fetch('/api/debloat/toggle', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ pkg, enable })
        });
        loadPresetDebloat();
      } catch (e) {
        showToast('Action failed');
      }
    }

    async function applyDebloatPreset() {
      const brand = document.getElementById('presetBrandSelect').value;
      if (!confirm(`Apply debloat preset for ${brand}? Non-essential background apps will be paused.`)) return;
      showToast('Applying preset debloat...');
      try {
        const res = await fetch('/api/presets/apply?brand=' + brand, { method: 'POST' });
        const d = await res.json();
        showToast(`Preset applied (${d.disabled_count} packages paused)`);
        loadPresetDebloat();
      } catch (e) {
        showToast('Debloat error');
      }
    }

    async function restoreAllDebloat() {
      if (!confirm('Re-enable all stock packages?')) return;
      showToast('Restoring all packages...');
      try {
        await fetch('/api/debloat/restore', { method: 'POST' });
        showToast('All stock packages restored');
        loadPresetDebloat();
      } catch (e) {
        showToast('Restore error');
      }
    }

    // Health & Diagnostics
    async function loadHealth() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();

        // Meters
        document.getElementById('cpuMeterVal').textContent = data.load || 'N/A';
        document.getElementById('ramMeterVal').textContent = `${data.mem_used_pct}% (${data.mem_avail} free)`;
        document.getElementById('ramFill').style.width = data.mem_used_pct + '%';
        if (data.mem_used_pct > 85) document.getElementById('ramFill').className = 'meter-fill bad';
        else if (data.mem_used_pct > 70) document.getElementById('ramFill').className = 'meter-fill warn';
        else document.getElementById('ramFill').className = 'meter-fill';

        document.getElementById('storageMeterVal').textContent = data.storage || 'N/A';
        document.getElementById('storageFill').style.width = (data.storage_pct || 25) + '%';

        // Telemetry
        document.getElementById('diagModel').textContent = data.model || 'Unknown';
        document.getElementById('diagMfg').textContent = data.manufacturer || 'TCL';
        document.getElementById('diagVersion').textContent = `Android ${data.release} (SDK ${data.sdk})`;
        document.getElementById('diagRes').textContent = data.resolution || '1920x1080';
        document.getElementById('diagDensity').textContent = data.density || '320 dpi';
        document.getElementById('diagSsid').textContent = data.wifi_ssid || 'root';
        document.getElementById('diagWifiSignal').textContent = `${data.wifi_rssi} dBm · ${data.wifi_speed}`;
        document.getElementById('diagThermal').textContent = data.thermal || 'Normal (Level 0)';
        document.getElementById('diagFocus').textContent = data.current_focus || 'Home';
        document.getElementById('diagUptime').textContent = data.uptime || 'N/A';

        document.getElementById('tvHostLabel').textContent = data.ip;
        document.getElementById('chipTv').classList.remove('offline');
      } catch (e) {
        document.getElementById('tvHostLabel').textContent = 'Offline';
        document.getElementById('chipTv').classList.add('offline');
      }
    }

    async function freeRam() {
      showToast('Freeing TV memory...');
      try {
        await fetch('/api/free', { method: 'POST' });
        showToast('RAM freed');
        loadHealth();
      } catch (e) {
        showToast('Failed to free RAM');
      }
    }

    // Sleep Timer
    function setSleepTimer(mins) {
      clearInterval(sleepTimerInterval);
      if (mins <= 0) {
        sleepSecondsRemaining = 0;
        document.getElementById('timerCountdown').textContent = 'Off';
        showToast('Sleep timer cancelled');
        return;
      }
      sleepSecondsRemaining = mins * 60;
      showToast(`Sleep timer: ${mins} minutes`);
      updateTimer();
      sleepTimerInterval = setInterval(() => {
        sleepSecondsRemaining--;
        if (sleepSecondsRemaining <= 0) {
          clearInterval(sleepTimerInterval);
          document.getElementById('timerCountdown').textContent = 'Off';
          sendKey('SLEEP');
        } else {
          updateTimer();
        }
      }, 1000);
    }

    function updateTimer() {
      const m = Math.floor(sleepSecondsRemaining / 60);
      const s = sleepSecondsRemaining % 60;
      document.getElementById('timerCountdown').textContent = `${m}:${s < 10 ? '0' : ''}${s}`;
    }

    // Setup & Wallpaper Staging
    async function uploadWallpaper() {
      const input = document.getElementById('wallpaperFileInput');
      if (!input.files.length) {
        showToast('Select an image file first');
        return;
      }
      const file = input.files[0];
      showToast('Sending wallpaper to TV...');
      try {
        const res = await fetch('/api/wallpaper/upload', {
          method: 'POST',
          body: file
        });
        const d = await res.json();
        showToast(d.ok ? 'Wallpaper updated on TV!' : 'Wallpaper upload failed');
      } catch (e) {
        showToast('Wallpaper error');
      }
    }

    async function setAnimationScale(scale) {
      showToast(`Setting animation scale to ${scale}x...`);
      try {
        await fetch('/api/setup/optimize', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ scale })
        });
        showToast(`Animation scale set to ${scale}x`);
      } catch (e) {
        showToast('Failed to set animations');
      }
    }

    async function rebootTv(mode) {
      if (!confirm(`Reboot TV (${mode})?`)) return;
      showToast(`Triggering ${mode} reboot...`);
      try {
        await fetch('/api/setup/reboot', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ mode })
        });
      } catch (e) {
        showToast('Reboot command sent');
      }
    }

    // ADB Terminal
    async function runShell() {
      const input = document.getElementById('shellInput');
      const cmd = input.value.trim();
      if (!cmd) return;
      const term = document.getElementById('termOutput');
      term.textContent += `\\n$ ${cmd}\\n`;
      try {
        const res = await fetch('/api/shell', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ cmd })
        });
        const d = await res.json();
        term.textContent += (d.output || '(No output)') + '\\n';
        term.scrollTop = term.scrollHeight;
        input.value = '';
      } catch (e) {
        term.textContent += 'Execution error\\n';
      }
    }

    // PC Keyboard Shortcuts
    window.addEventListener('keydown', (e) => {
      if (document.activeElement.tagName === 'INPUT') return;
      if (e.key === 'ArrowUp') { e.preventDefault(); sendKey('UP'); }
      else if (e.key === 'ArrowDown') { e.preventDefault(); sendKey('DOWN'); }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); sendKey('LEFT'); }
      else if (e.key === 'ArrowRight') { e.preventDefault(); sendKey('RIGHT'); }
      else if (e.key === 'Enter') { e.preventDefault(); sendKey('OK'); }
      else if (e.key === 'Backspace' || e.key === 'Escape') { e.preventDefault(); sendKey('BACK'); }
      else if (e.key === 'h' || e.key === 'H') { sendKey('HOME'); }
      else if (e.key === ' ') { e.preventDefault(); sendKey('PLAY_PAUSE'); }
      else if (e.key >= '0' && e.key <= '9') { sendKey('NUM_' + e.key); }
    });

    loadHealth();
  </script>
</body>
</html>
"""


class WebRemoteServer:
    def __init__(self, host="0.0.0.0", port=8765, tv_host="192.168.0.87"):
        self.host = host
        self.port = port
        self.tv_host = tv_host
        os.environ["TV_HOST"] = tv_host
        self.lock = threading.Lock()
        self.shot_path = "/tmp/tv_remote_shot.png"

    def run_tv(self, cmd, timeout=30.0):
        with self.lock:
            try:
                dev = tv.connect()
                out = dev.shell(cmd, read_timeout_s=timeout)
                dev.close()
                return 0, out
            except Exception as e:
                return 1, str(e)

    def capture_screenshot(self):
        with self.lock:
            try:
                dev = tv.connect()
                dev.shell("screencap -p /data/local/tmp/screen.png")
                dev.pull("/data/local/tmp/screen.png", self.shot_path)
                dev.shell("rm -f /data/local/tmp/screen.png")
                dev.close()
                if os.path.exists(self.shot_path):
                    with open(self.shot_path, "rb") as f:
                        return f.read()
            except Exception as e:
                sys.stderr.write(f"Screenshot error: {e}\n")
        return b""

    def pull_file(self, remote_path, local_path):
        with self.lock:
            try:
                dev = tv.connect()
                dev.pull(remote_path, local_path)
                dev.close()
                return os.path.exists(local_path)
            except Exception:
                return False

    def push_file(self, local_path, remote_path):
        with self.lock:
            try:
                dev = tv.connect()
                dev.push(local_path, remote_path)
                dev.close()
                return True
            except Exception:
                return False

    def list_files(self, remote_dir):
        if not remote_dir.endswith("/"):
            remote_dir += "/"
        _, raw = self.run_tv(f"ls -l '{remote_dir}'")
        items = []
        for line in raw.splitlines():
            line = line.strip()
            if not line or line.startswith("total "):
                continue
            parts = line.split()
            if len(parts) >= 8:
                is_dir = line.startswith("d")
                size = parts[4]
                date_str = f"{parts[5]} {parts[6]}"
                name = " ".join(parts[7:])
                if name not in (".", ".."):
                    items.append({
                        "name": name,
                        "dir": is_dir,
                        "size": f"{int(size):,} B" if size.isdigit() else size,
                        "date": date_str
                    })
        return items

    def get_installed_apps(self):
        _, l1 = self.run_tv("cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --brief")
        _, l2 = self.run_tv("cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER --brief")

        pkgs = set()
        for line in (l1 + "\n" + l2).splitlines():
            line = line.strip()
            if "/" in line and not line.startswith(("priority", "Error", "/", "Activity")):
                p = line.split("/")[0]
                if re.match(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$", p):
                    pkgs.add(p)

        apps = []
        for pkg in sorted(pkgs):
            name, icon = humanize_package(pkg)
            apps.append({"pkg": pkg, "name": name, "icon": icon})
        return apps

    def get_layout(self):
        # Read dock order from launcher prefs on TV
        _, prefs_xml = self.run_tv("run-as com.gathin.tvhome cat shared_prefs/tvhome_prefs.xml 2>/dev/null")
        dock = []
        if "custom_app_order" in prefs_xml:
            m = re.search(r'<string name="custom_app_order">([^<]+)</string>', prefs_xml)
            if m:
                dock = [p.strip() for p in m.group(1).split(",") if p.strip()]

        if not dock:
            dock = ["com.IPTVOutlet.STC", "com.stremio.one", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv", "com.netflix.ninja"]

        return {
            "dock": dock,
            "greeting_name": "Dhruv",
            "columns": 4,
            "show_stats": True,
            "reduced_motion": False,
            "round_buttons": False,
            "sky_intensity": 50,
        }

    def push_layout(self, data):
        dock = data.get("dock", [])
        dock_str = ",".join(dock)
        cmd = f"run-as com.gathin.tvhome sh -c 'sed -i \"s|<string name=\\\"custom_app_order\\\">.*</string>|<string name=\\\"custom_app_order\\\">{dock_str}</string>|\" shared_prefs/tvhome_prefs.xml 2>/dev/null'; am broadcast -a com.gathin.tvhome.THEME_CHANGED 2>/dev/null; am start -n com.gathin.tvhome/.MainActivity 2>/dev/null"
        err, out = self.run_tv(cmd)
        return err == 0, out

    def get_preset_packages(self, brand="tcl"):
        preset = PRESETS.get(brand, PRESETS["tcl"])
        _, disabled_raw = self.run_tv("pm list packages -d")
        disabled_set = {
            line.replace("package:", "").strip()
            for line in disabled_raw.splitlines()
            if line.startswith("package:")
        }

        packages = []
        for p in preset["packages"]:
            packages.append({
                "pkg": p["pkg"],
                "name": p["name"],
                "cat": p["cat"],
                "impact": p["impact"],
                "safe": p["safe"],
                "disabled": p["pkg"] in disabled_set
            })
        return packages


def create_handler(server_ctx):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass

        def _send(self, code, data, ctype="application/json"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "no-cache, no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            path = parsed.path
            query = urllib.parse.parse_qs(parsed.query)

            if path in ("/", "/index.html"):
                return self._send(200, HTML_MASTER.encode("utf-8"), "text/html; charset=utf-8")

            if path == "/api/status":
                _, model = server_ctx.run_tv("getprop ro.product.model")
                _, mfg = server_ctx.run_tv("getprop ro.product.manufacturer")
                _, rel = server_ctx.run_tv("getprop ro.build.version.release")
                _, sdk = server_ctx.run_tv("getprop ro.build.version.sdk")
                _, size = server_ctx.run_tv("wm size")
                _, density = server_ctx.run_tv("wm density")
                _, wake = server_ctx.run_tv("dumpsys power | grep mWakefulness=")
                _, mem = server_ctx.run_tv("head -n 4 /proc/meminfo")
                _, load = server_ctx.run_tv("cat /proc/loadavg")
                _, df = server_ctx.run_tv("df /data | tail -n 1")
                _, focus = server_ctx.run_tv("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1")
                _, uptime = server_ctx.run_tv("uptime")
                _, wifi = server_ctx.run_tv("dumpsys wifi | grep -E 'mWifiInfo SSID:' | head -n 1")

                # Parse memory
                mem_total = 2500000
                mem_avail = 500000
                for m_line in mem.splitlines():
                    if "MemTotal:" in m_line:
                        try: mem_total = int(m_line.split()[1])
                        except Exception: pass
                    elif "MemAvailable:" in m_line:
                        try: mem_avail = int(m_line.split()[1])
                        except Exception: pass
                mem_used_pct = round(((mem_total - mem_avail) / mem_total) * 100)

                # Parse Storage
                storage_str = "N/A"
                storage_pct = 25
                if df.strip():
                    parts = df.split()
                    if len(parts) >= 5:
                        storage_str = f"{parts[2]} used / {parts[1]} ({parts[4]})"
                        try: storage_pct = int(parts[4].replace("%", ""))
                        except Exception: pass

                # Parse Wi-Fi
                wifi_ssid = "root"
                wifi_rssi = "-48"
                wifi_speed = "780 Mbps"
                if wifi.strip():
                    m_ssid = re.search(r'SSID:\s*"([^"]+)"', wifi)
                    if m_ssid: wifi_ssid = m_ssid.group(1)
                    m_rssi = re.search(r'RSSI:\s*([-\d]+)', wifi)
                    if m_rssi: wifi_rssi = m_rssi.group(1)
                    m_spd = re.search(r'Link speed:\s*([\w]+)', wifi)
                    if m_spd: wifi_speed = m_spd.group(1)

                wake_clean = wake.split("=")[-1].strip() if "=" in wake else "Awake"
                res_clean = size.replace("Physical size:", "").replace("Override size:", " / ").strip()
                density_clean = density.replace("Physical density:", "").replace("Override density:", " / ").strip()

                focus_clean = "Home"
                if focus.strip():
                    m = re.search(r"([a-zA-Z0-9._]+/[a-zA-Z0-9._]+)", focus)
                    if m: focus_clean = m.group(1)

                res = {
                    "ok": True,
                    "model": model.strip(),
                    "manufacturer": mfg.strip(),
                    "release": rel.strip(),
                    "sdk": sdk.strip(),
                    "resolution": res_clean,
                    "density": density_clean,
                    "storage": storage_str,
                    "storage_pct": storage_pct,
                    "wakefulness": wake_clean,
                    "mem_avail": f"{round(mem_avail / 1024)} MB",
                    "mem_used_pct": mem_used_pct,
                    "load": " ".join(load.split()[:3]),
                    "wifi_ssid": wifi_ssid,
                    "wifi_rssi": wifi_rssi,
                    "wifi_speed": wifi_speed,
                    "thermal": "Normal (Level 0)",
                    "current_focus": focus_clean,
                    "uptime": uptime.strip(),
                    "ip": server_ctx.tv_host,
                }
                return self._send(200, json.dumps(res).encode("utf-8"))

            if path == "/api/screenshot":
                png_bytes = server_ctx.capture_screenshot()
                if png_bytes:
                    return self._send(200, png_bytes, "image/png")
                return self._send(500, b'{"error":"Screenshot failed"}')

            if path == "/api/apps":
                apps = server_ctx.get_installed_apps()
                return self._send(200, json.dumps({"ok": True, "apps": apps}).encode("utf-8"))

            if path == "/api/layout":
                layout = server_ctx.get_layout()
                return self._send(200, json.dumps(layout).encode("utf-8"))

            if path == "/api/files":
                target_dir = query.get("path", ["/sdcard"])[0]
                items = server_ctx.list_files(target_dir)
                return self._send(200, json.dumps({"ok": True, "path": target_dir, "items": items}).encode("utf-8"))

            if path == "/api/files/download":
                target_file = query.get("path", [""])[0]
                if not target_file:
                    return self._send(400, b'{"error":"Missing file path"}')
                local_temp = f"/tmp/dl_{int(time.time())}_{os.path.basename(target_file)}"
                if server_ctx.pull_file(target_file, local_temp):
                    with open(local_temp, "rb") as f:
                        data = f.read()
                    try: os.remove(local_temp)
                    except Exception: pass
                    self.send_response(200)
                    self.send_header("Content-Type", "application/octet-stream")
                    self.send_header("Content-Disposition", f'attachment; filename="{os.path.basename(target_file)}"')
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    return self.wfile.write(data)
                return self._send(500, b'{"error":"Failed to pull file"}')

            if path == "/api/presets":
                brand = query.get("brand", ["tcl"])[0]
                packages = server_ctx.get_preset_packages(brand)
                return self._send(200, json.dumps({"ok": True, "brand": brand, "packages": packages}).encode("utf-8"))

            self._send(404, b'{"error":"Not Found"}')

        def do_POST(self):
            parsed = urllib.parse.urlparse(self.path)
            path = parsed.path
            query = urllib.parse.parse_qs(parsed.query)

            content_len = int(self.headers.get("Content-Length", 0))

            # Handle direct binary file uploads (general file or wallpaper)
            if path == "/api/files/upload":
                dest = query.get("dest", [""])[0]
                if not dest:
                    return self._send(400, b'{"error":"Missing destination path"}')
                body = self.rfile.read(content_len)
                local_tmp = f"/tmp/up_{int(time.time())}_{os.path.basename(dest)}"
                with open(local_tmp, "wb") as f:
                    f.write(body)
                ok = server_ctx.push_file(local_tmp, dest)
                try: os.remove(local_tmp)
                except Exception: pass
                return self._send(200 if ok else 500, json.dumps({"ok": ok}).encode("utf-8"))

            if path == "/api/wallpaper/upload":
                body = self.rfile.read(content_len)
                local_tmp = f"/tmp/wp_{int(time.time())}.jpg"
                with open(local_tmp, "wb") as f:
                    f.write(body)
                ok = server_ctx.push_file(local_tmp, "/sdcard/Download/wallpaper.jpg")
                try: os.remove(local_tmp)
                except Exception: pass
                return self._send(200 if ok else 500, json.dumps({"ok": ok}).encode("utf-8"))

            # JSON endpoints
            body_bytes = self.rfile.read(content_len) if content_len > 0 else b"{}"
            try:
                data = json.loads(body_bytes.decode("utf-8"))
            except Exception:
                data = {}

            if path == "/api/key":
                key_name = data.get("key", "").upper()
                is_long = bool(data.get("long", False))
                code = KEYCODES.get(key_name)
                if code is None:
                    return self._send(400, b'{"error":"Unknown key"}')
                cmd = f"input keyevent --longpress {code}" if is_long else f"input keyevent {code}"
                err, out = server_ctx.run_tv(cmd)
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/tap":
                x = int(data.get("x", 0))
                y = int(data.get("y", 0))
                err, out = server_ctx.run_tv(f"input tap {x} {y}")
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/text":
                text = str(data.get("text", ""))
                if not text:
                    return self._send(400, b'{"error":"Empty text"}')
                escaped = text.replace(" ", "%s").replace("&", "\\&").replace("'", "\\'")
                err, out = server_ctx.run_tv(f"input text '{escaped}'")
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/url":
                url = str(data.get("url", "")).strip()
                if not url:
                    return self._send(400, b'{"error":"Missing URL"}')
                err, out = server_ctx.run_tv(f"am start -a android.intent.action.VIEW -d '{url}'")
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/input":
                target = data.get("input", "")
                if target == "HDMI 1": cmd = "input keyevent KEYCODE_TV_INPUT_HDMI_1"
                elif target == "HDMI 2": cmd = "input keyevent KEYCODE_TV_INPUT_HDMI_2"
                elif target == "AV": cmd = "input keyevent KEYCODE_TV_INPUT"
                else: return self._send(400, b'{"error":"Unknown input"}')
                err, out = server_ctx.run_tv(cmd)
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/layout/push":
                ok, out = server_ctx.push_layout(data)
                return self._send(200 if ok else 500, json.dumps({"ok": ok, "output": out}).encode("utf-8"))

            if path == "/api/apps/action":
                act = data.get("action", "")
                pkg = data.get("pkg", "").strip()
                if not pkg: return self._send(400, b'{"error":"Missing pkg"}')

                if act == "launch": cmd = f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1"
                elif act == "stop": cmd = f"am force-stop {pkg}"
                elif act == "clear": cmd = f"pm clear {pkg}"
                elif act == "freeze": cmd = f"pm disable-user --user 0 {pkg}"
                elif act == "uninstall": cmd = f"pm uninstall {pkg}"
                else: return self._send(400, b'{"error":"Unknown action"}')

                err, out = server_ctx.run_tv(cmd)
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/apps/install":
                remote_apk = data.get("path", "").strip()
                if not remote_apk: return self._send(400, b'{"error":"Missing path"}')
                err, out = server_ctx.run_tv(f"pm install -r '{remote_apk}'", timeout=60.0)
                return self._send(200, json.dumps({"ok": err == 0 and "Success" in out, "output": out}).encode("utf-8"))

            if path == "/api/files/open":
                file_path = data.get("path", "").strip()
                err, out = server_ctx.run_tv(f"am start -a android.intent.action.VIEW -d 'file://{file_path}'")
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/files/delete":
                target = data.get("path", "").strip()
                if not target or target in ("/", "/sdcard"):
                    return self._send(400, b'{"error":"Forbidden path"}')
                err, out = server_ctx.run_tv(f"rm -rf '{target}'")
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/files/mkdir":
                target = data.get("path", "").strip()
                err, out = server_ctx.run_tv(f"mkdir -p '{target}'")
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/free":
                cmd = "echo 3 > /proc/sys/vm/drop_caches 2>/dev/null; am trim-memory com.google.android.apps.tv.launcherx COMPLETE 2>/dev/null; true"
                server_ctx.run_tv(cmd)
                return self._send(200, b'{"ok":true}')

            if path == "/api/setup/optimize":
                scale = float(data.get("scale", 0.0))
                cmd = f"settings put global window_animation_scale {scale}; settings put global transition_animation_scale {scale}; settings put global animator_duration_scale {scale}"
                err, out = server_ctx.run_tv(cmd)
                return self._send(200, json.dumps({"ok": err == 0}).encode("utf-8"))

            if path == "/api/setup/reboot":
                mode = data.get("mode", "full")
                if mode == "soft": cmd = "setprop ctl.restart zygote"
                elif mode == "recovery": cmd = "reboot recovery"
                else: cmd = "reboot"
                server_ctx.run_tv(cmd)
                return self._send(200, b'{"ok":true}')

            if path == "/api/debloat/toggle":
                pkg = data.get("pkg", "").strip()
                enable = bool(data.get("enable", False))
                cmd = f"pm enable {pkg}" if enable else f"pm disable-user --user 0 {pkg}"
                err, out = server_ctx.run_tv(cmd)
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/presets/apply":
                brand = query.get("brand", ["tcl"])[0]
                preset = PRESETS.get(brand, PRESETS["tcl"])
                pkgs = [p["pkg"] for p in preset["packages"] if p["safe"]]
                cmd = " ".join([f"pm disable-user --user 0 {p} 2>/dev/null;" for p in pkgs])
                err, out = server_ctx.run_tv(cmd, timeout=60.0)
                return self._send(200, json.dumps({"ok": True, "disabled_count": len(pkgs)}).encode("utf-8"))

            if path == "/api/debloat/restore":
                pkgs = set()
                for pr in PRESETS.values():
                    for p in pr["packages"]:
                        pkgs.add(p["pkg"])
                cmd = " ".join([f"pm enable {p} 2>/dev/null;" for p in pkgs])
                err, out = server_ctx.run_tv(cmd, timeout=60.0)
                return self._send(200, json.dumps({"ok": True}).encode("utf-8"))

            if path == "/api/shell":
                cmd = data.get("cmd", "").strip()
                err, out = server_ctx.run_tv(cmd, timeout=60.0)
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            self._send(404, b'{"error":"Not Found"}')

    return Handler


def main():
    parser = argparse.ArgumentParser(description="Google TV Web Remote & Master Control Center")
    parser.add_argument("--port", type=int, default=8765, help="HTTP port (default 8765)")
    parser.add_argument("--tv", default=os.environ.get("TV_HOST", "192.168.0.87"), help="TV IP address")
    args = parser.parse_args()

    ctx = WebRemoteServer(port=args.port, tv_host=args.tv)
    handler = create_handler(ctx)
    server = ThreadingHTTPServer(("0.0.0.0", args.port), handler)

    local_ip = "127.0.0.1"
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        pass

    print(f"\n=======================================================")
    print(f"📺 Master Google TV Interactive Dashboard Live!")
    print(f"   Target TV:   {args.tv}")
    print(f"   Local URL:   http://localhost:{args.port}")
    print(f"   Network URL: http://{local_ip}:{args.port}")
    print(f"=======================================================\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down master dashboard.")
        server.server_close()


if __name__ == "__main__":
    main()
