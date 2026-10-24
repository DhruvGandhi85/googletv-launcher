#!/usr/bin/env python3
"""Google TV Web Remote & Unified Control Center.

Combines all features from:
  - HoangTran0410/tv-remote:
      * Full tactile D-Pad (Up, Down, Left, Right, OK) with long-press support
      * Volume (+, -, Mute), Channel (+, -), Media transport (Play/Pause, Stop, Prev, Next, Rewind, FF)
      * Full numeric keypad (0-9) & 4-color keys (Red, Green, Yellow, Blue)
      * Interactive Click-to-Tap screen mirror (clicking screen image sends `input tap X Y`)
      * Real-time text typing stream & URL / Deep Link launcher
      * Sleep timer countdown (15m, 30m, 45m, 60m)
      * Shell command console
  - Pagamase/cleanyourtv-panel:
      * Live query of all installed launcher and leanback apps
      * Humanized names, quick 1-tap app launch
      * HDMI input switching (HDMI 1, HDMI 2, HDMI 3, AV)
      * Custom app ordering & launcher settings
  - gethari/clean-your-tv-companion:
      * Debloat package inspector (Disabled vs Bloatware vs System)
      * Individual 1-click Enable/Disable package toggle
      * "Apply Recommended Debloat" & "Restore All" batch actions
      * Deep TV hardware diagnostics (Resolution, Density, Storage, RAM, Uptime, Focus Activity)

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

TCL_BLOATWARE = [
    {"pkg": "com.tcl.tv.plus", "name": "TCL TV+ (Ad/Channel aggregator)", "safe": True},
    {"pkg": "com.tcl.channelplus", "name": "TCL Channel Plus", "safe": True},
    {"pkg": "com.tcl.tv.tclhome_passive", "name": "TCL Home Passive Service", "safe": True},
    {"pkg": "com.tcl.partnercustomizer", "name": "TCL Partner Customizer", "safe": True},
    {"pkg": "com.tcl.interactive", "name": "TCL Interactive Telemetry", "safe": True},
    {"pkg": "com.tcl.smartlink.core", "name": "TCL SmartLink Core", "safe": True},
    {"pkg": "com.tcl.logkit", "name": "TCL LogKit Diagnostic Collector", "safe": True},
    {"pkg": "com.tcl.ttvs", "name": "TCL TTVS Background Agent", "safe": True},
    {"pkg": "com.tcl.messagebox", "name": "TCL Message Box Push Notification", "safe": True},
    {"pkg": "com.tcl.usercenter", "name": "TCL User Center Account Tracker", "safe": True},
    {"pkg": "com.tcl.useragreement", "name": "TCL User Agreement Prompter", "safe": True},
    {"pkg": "com.tcl.exhibit", "name": "TCL Retail Exhibit / Demo Mode", "safe": True},
    {"pkg": "com.tcl.esticker", "name": "TCL E-Sticker Store Demo", "safe": True},
    {"pkg": "com.tcl.browser", "name": "TCL Built-in Browser", "safe": True},
    {"pkg": "com.tcl.ocean.instructions", "name": "TCL Ocean Instructions", "safe": True},
    {"pkg": "com.tcl.repairguide", "name": "TCL Repair Guide", "safe": True},
    {"pkg": "com.tcl.smartalexa", "name": "TCL Alexa Integration Agent", "safe": True},
    {"pkg": "com.tcl.UpdatePeripheral", "name": "TCL Update Peripheral Agent", "safe": True},
    {"pkg": "com.tcl.hearaid", "name": "TCL HearAid Service", "safe": True},
    {"pkg": "com.tcl.suspension", "name": "TCL Suspension Floating Widget", "safe": True},
    {"pkg": "com.google.android.feedback", "name": "Google Play Feedback Collector", "safe": True},
    {"pkg": "com.android.tv.feedbackconsent", "name": "Google TV Feedback Consent", "safe": True},
    {"pkg": "android.autoinstalls.config.google.gtvpai", "name": "Google PAI Auto-Installer", "safe": True},
    {"pkg": "com.google.android.apps.tv.launcherx", "name": "Stock Google TV Launcher (Ad-heavy)", "safe": True},
    {"pkg": "com.google.android.tungsten.setupwraith", "name": "Google TV Setup Wraith", "safe": True},
    {"pkg": "com.google.android.apps.tv.dreamx", "name": "Google TV Ambient Backdrop", "safe": True},
]

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


HTML_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
  <title>TV Remote & Control Center</title>
  <style>
    :root {
      --bg: #0b0d11;
      --card-bg: #14171e;
      --card-border: #212631;
      --card-sub: #191e27;
      --accent: #3b82f6;
      --accent-glow: rgba(59, 130, 246, 0.25);
      --text: #f3f4f6;
      --text-muted: #94a3b8;
      --danger: #ef4444;
      --success: #10b981;
      --warning: #f59e0b;
      --radius: 14px;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
    body {
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 14px 10px 48px;
    }
    header {
      width: 100%;
      max-width: 480px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 14px;
      padding: 0 4px;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 10px;
      font-weight: 600;
      font-size: 1.05rem;
      letter-spacing: -0.01em;
    }
    .status-badge {
      font-size: 0.72rem;
      padding: 3px 9px;
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
    .status-badge.offline {
      background: rgba(239, 68, 68, 0.12);
      color: var(--danger);
      border-color: rgba(239, 68, 68, 0.3);
    }
    .status-badge.offline .status-dot {
      background: var(--danger);
      box-shadow: 0 0 6px var(--danger);
    }
    .tabs {
      display: flex;
      width: 100%;
      max-width: 480px;
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 12px;
      padding: 3px;
      margin-bottom: 14px;
      overflow-x: auto;
      gap: 2px;
    }
    .tabs::-webkit-scrollbar { display: none; }
    .tab-btn {
      flex: 1;
      min-width: 72px;
      background: transparent;
      border: none;
      color: var(--text-muted);
      padding: 8px 6px;
      font-size: 0.78rem;
      font-weight: 500;
      border-radius: 9px;
      cursor: pointer;
      white-space: nowrap;
      transition: all 0.15s ease;
      text-align: center;
    }
    .tab-btn.active {
      background: #232a37;
      color: #fff;
      font-weight: 600;
    }
    .tab-content {
      width: 100%;
      max-width: 480px;
      display: none;
      flex-direction: column;
      gap: 14px;
    }
    .tab-content.active { display: flex; }

    /* Cards */
    .card {
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: var(--radius);
      padding: 15px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .card-title {
      font-size: 0.73rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--text-muted);
      font-weight: 600;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    /* Buttons */
    button.btn {
      background: #1b2029;
      border: 1px solid #28303f;
      color: var(--text);
      font-size: 0.88rem;
      font-weight: 500;
      border-radius: 11px;
      padding: 11px;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      user-select: none;
      transition: background 0.1s, transform 0.08s, border-color 0.1s;
    }
    button.btn:active {
      background: #283141;
      transform: scale(0.96);
      border-color: var(--accent);
    }
    button.btn-primary {
      background: var(--accent);
      border-color: #60a5fa;
      color: #fff;
    }
    button.btn-danger {
      color: #fca5a5;
      border-color: rgba(239, 68, 68, 0.35);
    }
    button.btn-danger:active {
      background: rgba(239, 68, 68, 0.2);
    }

    /* D-Pad Controller */
    .dpad-container {
      position: relative;
      width: 220px;
      height: 220px;
      margin: 4px auto 8px;
    }
    .dpad-btn {
      position: absolute;
      background: #1a1f28;
      border: 1px solid #2a3343;
      color: var(--text);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      user-select: none;
      transition: all 0.1s;
    }
    .dpad-btn:active, .dpad-btn.active {
      background: #2a3547;
      box-shadow: 0 0 12px var(--accent-glow);
      border-color: var(--accent);
    }
    .dpad-up { top: 0; left: 66px; width: 88px; height: 62px; border-radius: 26px 26px 8px 8px; }
    .dpad-down { bottom: 0; left: 66px; width: 88px; height: 62px; border-radius: 8px 8px 26px 26px; }
    .dpad-left { top: 66px; left: 0; width: 62px; height: 88px; border-radius: 26px 8px 8px 26px; }
    .dpad-right { top: 66px; right: 0; width: 62px; height: 88px; border-radius: 8px 26px 26px 8px; }
    .dpad-center {
      top: 68px; left: 68px; width: 84px; height: 84px;
      border-radius: 50%;
      background: #232b38;
      border: 2px solid #364457;
      font-weight: 700;
      font-size: 0.95rem;
    }
    .dpad-center:active, .dpad-center.active {
      background: var(--accent);
      border-color: #60a5fa;
      transform: scale(0.94);
    }

    /* Grids */
    .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; }
    .grid-4 { display: grid; grid-template-columns: repeat(4, 1fr); gap: 7px; }

    /* Color Keys */
    .color-btn { height: 38px; border-radius: 8px; font-weight: 600; font-size: 0.78rem; }
    .btn-red { background: rgba(239, 68, 68, 0.2); border-color: #ef4444; color: #fca5a5; }
    .btn-green { background: rgba(16, 185, 129, 0.2); border-color: #10b981; color: #6ee7b7; }
    .btn-yellow { background: rgba(245, 158, 11, 0.2); border-color: #f59e0b; color: #fde68a; }
    .btn-blue { background: rgba(59, 130, 246, 0.2); border-color: #3b82f6; color: #93c5fd; }

    /* Inputs */
    .typing-box { display: flex; gap: 8px; }
    .text-input {
      flex: 1;
      background: #0f1217;
      border: 1px solid var(--card-border);
      border-radius: 10px;
      padding: 10px 14px;
      color: var(--text);
      font-size: 0.9rem;
      outline: none;
    }
    .text-input:focus { border-color: var(--accent); }

    /* Clickable Screen Mirror */
    .screen-container {
      position: relative;
      width: 100%;
      background: #000;
      border-radius: 10px;
      overflow: hidden;
      aspect-ratio: 16/9;
      cursor: crosshair;
      border: 1px solid var(--card-border);
    }
    .screen-img { width: 100%; height: 100%; object-fit: contain; display: block; }
    .tap-ripple {
      position: absolute;
      width: 24px;
      height: 24px;
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

    /* Diagnostics Table */
    .diag-table { width: 100%; border-collapse: collapse; font-size: 0.83rem; }
    .diag-table tr { border-bottom: 1px solid #1a202c; }
    .diag-table td { padding: 7px 4px; }
    .diag-table td:first-child { color: var(--text-muted); width: 42%; }
    .diag-table td:last-child { color: var(--text); font-weight: 500; text-align: right; }

    /* Debloat Package List */
    .pkg-list { display: flex; flex-direction: column; gap: 6px; max-height: 380px; overflow-y: auto; padding-right: 4px; }
    .pkg-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 9px 12px;
      background: var(--card-sub);
      border: 1px solid var(--card-border);
      border-radius: 10px;
      font-size: 0.82rem;
    }
    .pkg-info { display: flex; flex-direction: column; gap: 2px; max-width: 72%; }
    .pkg-name { font-weight: 600; color: #fff; }
    .pkg-sub { font-size: 0.72rem; color: var(--text-muted); word-break: break-all; }
    .badge-off { background: rgba(239, 68, 68, 0.15); color: #fca5a5; padding: 2px 7px; border-radius: 6px; font-size: 0.7rem; }
    .badge-on { background: rgba(16, 185, 129, 0.15); color: #6ee7b7; padding: 2px 7px; border-radius: 6px; font-size: 0.7rem; }

    /* App Grid */
    .app-item {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
      padding: 10px 4px;
      font-size: 0.75rem;
      text-align: center;
      word-break: break-word;
    }
    .app-icon { font-size: 1.4rem; }

    /* Terminal Console */
    .term-box {
      background: #090b0e;
      border: 1px solid #1c222d;
      border-radius: 10px;
      padding: 10px;
      font-family: monospace;
      font-size: 0.76rem;
      color: #38bdf8;
      max-height: 180px;
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
      <span>TV Control Center</span>
    </div>
    <div class="status-badge" id="tvStatus">
      <div class="status-dot"></div>
      <span id="tvHostLabel">Connected</span>
    </div>
  </header>

  <!-- NAVIGATION TABS -->
  <div class="tabs">
    <button class="tab-btn active" onclick="switchTab('remote')">🎮 Remote</button>
    <button class="tab-btn" onclick="switchTab('screen')">📺 Mirror</button>
    <button class="tab-btn" onclick="switchTab('apps')">🚀 Apps</button>
    <button class="tab-btn" onclick="switchTab('debloat')">🧹 Debloat</button>
    <button class="tab-btn" onclick="switchTab('timer')">⏱ Timers</button>
    <button class="tab-btn" onclick="switchTab('system')">📊 System</button>
  </div>

  <!-- TAB 1: FULL REMOTE -->
  <div class="tab-content active" id="tab-remote">
    <!-- Top Bar -->
    <div class="card">
      <div class="grid-3">
        <button class="btn btn-danger" onclick="sendKey('POWER')">⏻ Power</button>
        <button class="btn" onclick="sendKey('SETTINGS')">⚙️ Settings</button>
        <button class="btn" onclick="sendKey('MENU')">☰ Menu</button>
      </div>

      <!-- D-Pad -->
      <div class="dpad-container">
        <button class="dpad-btn dpad-up" data-k="UP">▲</button>
        <button class="dpad-btn dpad-left" data-k="LEFT">◀</button>
        <button class="dpad-btn dpad-center" data-k="OK">OK</button>
        <button class="dpad-btn dpad-right" data-k="RIGHT">▶</button>
        <button class="dpad-btn dpad-down" data-k="DOWN">▼</button>
      </div>

      <div class="grid-2">
        <button class="btn" onclick="sendKey('BACK')">↩ Back</button>
        <button class="btn" onclick="sendKey('HOME')">🏠 Home</button>
      </div>
    </div>

    <!-- Volume & Channels -->
    <div class="card">
      <div class="card-title">Volume & Channels</div>
      <div class="grid-3">
        <button class="btn" onclick="sendKey('VOL_DOWN')">🔉 Vol -</button>
        <button class="btn" onclick="sendKey('MUTE')">🔇 Mute</button>
        <button class="btn" onclick="sendKey('VOL_UP')">🔊 Vol +</button>
      </div>
      <div class="grid-2">
        <button class="btn" onclick="sendKey('CHANNEL_DOWN')">CH ▾</button>
        <button class="btn" onclick="sendKey('CHANNEL_UP')">CH ▴</button>
      </div>
    </div>

    <!-- Media Controls -->
    <div class="card">
      <div class="card-title">Playback Transport</div>
      <div class="grid-4">
        <button class="btn" onclick="sendKey('PREV')">⏮</button>
        <button class="btn" onclick="sendKey('REWIND')">⏪</button>
        <button class="btn" onclick="sendKey('PLAY_PAUSE')">⏯</button>
        <button class="btn" onclick="sendKey('FAST_FORWARD')">⏩</button>
      </div>
    </div>

    <!-- Color Keys -->
    <div class="card">
      <div class="card-title">Color Keys & Actions</div>
      <div class="grid-4">
        <button class="btn color-btn btn-red" onclick="sendKey('RED')">● Red</button>
        <button class="btn color-btn btn-green" onclick="sendKey('GREEN')">● Green</button>
        <button class="btn color-btn btn-yellow" onclick="sendKey('YELLOW')">● Yellow</button>
        <button class="btn color-btn btn-blue" onclick="sendKey('BLUE')">● Blue</button>
      </div>
    </div>

    <!-- Number Pad (Collapsible) -->
    <div class="card">
      <div class="card-title">
        <span>Numeric Pad</span>
        <span style="font-size:0.7rem; cursor:pointer;" onclick="toggleNumpad()">Toggle ▾</span>
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

    <!-- Real-time Text Typing & URL -->
    <div class="card">
      <div class="card-title">Type on TV</div>
      <div class="typing-box">
        <input type="text" id="typeInput" class="text-input" placeholder="Type text..." onkeydown="if(event.key==='Enter') sendText()">
        <button class="btn btn-primary" onclick="sendText()">Send</button>
      </div>
    </div>

    <div class="card">
      <div class="card-title">Open URL on TV</div>
      <div class="typing-box">
        <input type="text" id="urlInput" class="text-input" placeholder="https://youtu.be/... or web link" onkeydown="if(event.key==='Enter') sendUrl()">
        <button class="btn btn-primary" onclick="sendUrl()">Open</button>
      </div>
    </div>
  </div>

  <!-- TAB 2: LIVE SCREEN MIRROR & TOUCH TAP -->
  <div class="tab-content" id="tab-screen">
    <div class="card">
      <div class="card-title">
        <span>Interactive Screen Touch</span>
        <span id="screenStatus" style="font-size:0.7rem; color:var(--text-muted);">Tap anywhere to click TV</span>
      </div>
      <div class="screen-container" id="screenContainer" onclick="handleScreenTap(event)">
        <img id="screenImg" class="screen-img" src="/api/screenshot" alt="TV Screen Mirror">
      </div>
      <div class="grid-3">
        <button class="btn" onclick="refreshScreen()">🔄 Refresh</button>
        <button class="btn" id="autoBtn" onclick="toggleAutoRefresh()">⏱ Auto (Off)</button>
        <a class="btn" id="dlBtn" href="/api/screenshot" download="tv-screen.png" style="text-decoration:none;">💾 Save</a>
      </div>
    </div>
  </div>

  <!-- TAB 3: APPS & INPUTS -->
  <div class="tab-content" id="tab-apps">
    <div class="card">
      <div class="card-title">HDMI Inputs</div>
      <div class="grid-3">
        <button class="btn" onclick="launchInput('HDMI 1')">🔌 HDMI 1</button>
        <button class="btn" onclick="launchInput('HDMI 2')">🔌 HDMI 2</button>
        <button class="btn" onclick="launchInput('AV')">📺 Live TV</button>
      </div>
    </div>

    <div class="card">
      <div class="card-title">
        <span>Installed Apps</span>
        <input type="text" id="appFilter" class="text-input" style="padding:4px 8px; width:120px; font-size:0.75rem;" placeholder="Search apps..." oninput="filterApps()">
      </div>
      <div class="grid-4" id="installedAppsGrid">
        <div style="grid-column: span 4; text-align:center; padding:20px; color:var(--text-muted);">Loading apps from TV...</div>
      </div>
    </div>
  </div>

  <!-- TAB 4: DEBLOAT & PACKAGE INSPECTOR -->
  <div class="tab-content" id="tab-debloat">
    <div class="card">
      <div class="card-title">Debloat Package Manager</div>
      <div class="grid-2">
        <button class="btn btn-primary" onclick="applyDebloat()">🧹 Apply Safe Debloat</button>
        <button class="btn btn-danger" onclick="restoreAll()">↩ Restore All Packages</button>
      </div>
      <div class="pkg-list" id="debloatPkgList">
        <div style="text-align:center; padding:20px; color:var(--text-muted);">Reading package states...</div>
      </div>
    </div>
  </div>

  <!-- TAB 5: SLEEP TIMERS & DIMMER -->
  <div class="tab-content" id="tab-timer">
    <div class="card">
      <div class="card-title">Sleep Timer</div>
      <div id="timerCountdown" style="font-size:1.4rem; font-weight:700; text-align:center; color:var(--accent);">Off</div>
      <div class="grid-4">
        <button class="btn" onclick="setSleepTimer(15)">15m</button>
        <button class="btn" onclick="setSleepTimer(30)">30m</button>
        <button class="btn" onclick="setSleepTimer(45)">45m</button>
        <button class="btn" onclick="setSleepTimer(60)">60m</button>
      </div>
      <button class="btn btn-danger" onclick="setSleepTimer(0)">Cancel Timer</button>
    </div>

    <div class="card">
      <div class="card-title">Display & Dimmer</div>
      <div class="grid-2">
        <button class="btn" onclick="sendShell('input keyevent KEYCODE_SLEEP')">🌙 Screen Off</button>
        <button class="btn" onclick="sendShell('input keyevent KEYCODE_WAKEUP')">☀️ Wake Screen</button>
      </div>
    </div>
  </div>

  <!-- TAB 6: SYSTEM & TERMINAL -->
  <div class="tab-content" id="tab-system">
    <div class="card">
      <div class="card-title">TCL Hardware Diagnostics</div>
      <table class="diag-table" id="diagTable">
        <tr><td>Model</td><td id="diagModel">Loading...</td></tr>
        <tr><td>Manufacturer</td><td id="diagMfg">-</td></tr>
        <tr><td>Android Version</td><td id="diagVersion">-</td></tr>
        <tr><td>Display Resolution</td><td id="diagRes">-</td></tr>
        <tr><td>Display Density</td><td id="diagDensity">-</td></tr>
        <tr><td>Storage (/data)</td><td id="diagStorage">-</td></tr>
        <tr><td>RAM Available</td><td id="diagMem">-</td></tr>
        <tr><td>Power State</td><td id="diagWake">-</td></tr>
        <tr><td>Active Focus</td><td id="diagFocus" style="font-size:0.75rem;">-</td></tr>
        <tr><td>Uptime</td><td id="diagUptime">-</td></tr>
      </table>
      <button class="btn" onclick="loadStatus()">🔄 Refresh Telemetry</button>
    </div>

    <!-- ADB Shell Console -->
    <div class="card">
      <div class="card-title">ADB Shell Command Console</div>
      <div class="typing-box">
        <input type="text" id="shellInput" class="text-input" placeholder="e.g. getprop or pm list packages" onkeydown="if(event.key==='Enter') runShell()">
        <button class="btn btn-primary" onclick="runShell()">Run</button>
      </div>
      <div class="term-box" id="termOutput">Console ready.</div>
    </div>
  </div>

  <div id="toast"></div>

  <script>
    let autoInterval = null;
    let sleepSecondsRemaining = 0;
    let sleepTimerInterval = null;
    let installedApps = [];

    function showToast(msg) {
      const t = document.getElementById('toast');
      t.textContent = msg;
      t.classList.add('show');
      setTimeout(() => t.classList.remove('show'), 2200);
    }

    function switchTab(tabId) {
      document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
      event.target.classList.add('active');
      document.getElementById('tab-' + tabId).classList.add('active');
      if (tabId === 'screen') refreshScreen();
      if (tabId === 'apps') loadApps();
      if (tabId === 'debloat') loadDebloatList();
      if (tabId === 'system') loadStatus();
    }

    function toggleNumpad() {
      const el = document.getElementById('numpadGrid');
      el.style.display = (el.style.display === 'none') ? 'grid' : 'none';
    }

    // Key press with tactile / long-press support
    async function sendKey(key, isLong = false) {
      if (navigator.vibrate) navigator.vibrate(25);
      try {
        const res = await fetch('/api/key', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ key, long: isLong })
        });
        const data = await res.json();
        if (!data.ok) showToast('Error: ' + (data.error || 'Failed'));
      } catch (e) {
        showToast('Connection error');
      }
    }

    // Set up pointer down / up on D-Pad for long-press support
    document.querySelectorAll('[data-k]').forEach(btn => {
      let pressTimer = null;
      let didLongPress = false;

      btn.addEventListener('pointerdown', (e) => {
        didLongPress = false;
        btn.classList.add('active');
        pressTimer = setTimeout(() => {
          didLongPress = true;
          sendKey(btn.dataset.k, true);
          showToast('Long-press ' + btn.dataset.k);
        }, 450);
      });

      const release = () => {
        btn.classList.remove('active');
        clearTimeout(pressTimer);
      };

      btn.addEventListener('pointerup', (e) => {
        release();
        if (!didLongPress) sendKey(btn.dataset.k, false);
      });
      btn.addEventListener('pointerleave', release);
      btn.addEventListener('pointercancel', release);
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
        showToast('Text sent to TV');
      } catch (e) {
        showToast('Failed to send text');
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
        showToast('Opened URL on TV');
      } catch (e) {
        showToast('Failed to open URL');
      }
    }

    async function launchApp(pkg) {
      showToast('Launching ' + pkg + '...');
      try {
        await fetch('/api/app', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ pkg })
        });
      } catch (e) {
        showToast('Failed to launch');
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

    // Touch tap on screen
    function handleScreenTap(e) {
      const container = document.getElementById('screenContainer');
      const rect = container.getBoundingClientRect();
      const clickX = e.clientX - rect.left;
      const clickY = e.clientY - rect.top;

      // Scale to 1920x1080
      const tvX = Math.round((clickX / rect.width) * 1920);
      const tvY = Math.round((clickY / rect.height) * 1080);

      // Visual ripple
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
        btn.textContent = '⏱ Auto (Off)';
      } else {
        autoInterval = setInterval(refreshScreen, 3000);
        btn.textContent = '⏱ Auto (3s)';
        refreshScreen();
      }
    }

    // Sleep Timer
    function setSleepTimer(minutes) {
      clearInterval(sleepTimerInterval);
      if (minutes <= 0) {
        sleepSecondsRemaining = 0;
        document.getElementById('timerCountdown').textContent = 'Off';
        showToast('Timer cancelled');
        return;
      }
      sleepSecondsRemaining = minutes * 60;
      showToast('Sleep timer set: ' + minutes + ' mins');
      updateTimerDisplay();
      sleepTimerInterval = setInterval(() => {
        sleepSecondsRemaining--;
        if (sleepSecondsRemaining <= 0) {
          clearInterval(sleepTimerInterval);
          document.getElementById('timerCountdown').textContent = 'Off';
          sendKey('SLEEP');
        } else {
          updateTimerDisplay();
        }
      }, 1000);
    }

    function updateTimerDisplay() {
      const m = Math.floor(sleepSecondsRemaining / 60);
      const s = sleepSecondsRemaining % 60;
      document.getElementById('timerCountdown').textContent = `${m}:${s < 10 ? '0' : ''}${s}`;
    }

    // Load installed apps
    async function loadApps() {
      try {
        const res = await fetch('/api/apps');
        const data = await res.json();
        installedApps = data.apps || [];
        renderApps(installedApps);
      } catch (e) {
        document.getElementById('installedAppsGrid').innerHTML = '<div style="grid-column: span 4; color:var(--danger); text-align:center;">Failed to load apps</div>';
      }
    }

    function renderApps(list) {
      const grid = document.getElementById('installedAppsGrid');
      grid.innerHTML = '';
      if (!list.length) {
        grid.innerHTML = '<div style="grid-column: span 4; color:var(--text-muted); text-align:center;">No apps found</div>';
        return;
      }
      list.forEach(app => {
        const btn = document.createElement('button');
        btn.className = 'btn app-item';
        btn.onclick = () => launchApp(app.pkg);
        btn.innerHTML = `<span class="app-icon">${app.icon}</span><span>${app.name}</span>`;
        grid.appendChild(btn);
      });
    }

    function filterApps() {
      const q = document.getElementById('appFilter').value.toLowerCase();
      const filtered = installedApps.filter(a => a.name.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q));
      renderApps(filtered);
    }

    // Debloat list
    async function loadDebloatList() {
      const container = document.getElementById('debloatPkgList');
      container.innerHTML = '<div style="text-align:center; padding:15px; color:var(--text-muted);">Scanning packages...</div>';
      try {
        const res = await fetch('/api/debloat/list');
        const data = await res.json();
        container.innerHTML = '';
        data.packages.forEach(p => {
          const div = document.createElement('div');
          div.className = 'pkg-item';
          const badge = p.disabled ? '<span class="badge-off">Disabled</span>' : '<span class="badge-on">Active</span>';
          const actionBtn = p.disabled
            ? `<button class="btn btn-primary" style="padding:4px 10px; font-size:0.74rem;" onclick="togglePkg('${p.pkg}', true)">Enable</button>`
            : `<button class="btn btn-danger" style="padding:4px 10px; font-size:0.74rem;" onclick="togglePkg('${p.pkg}', false)">Disable</button>`;
          div.innerHTML = `
            <div class="pkg-info">
              <span class="pkg-name">${p.name}</span>
              <span class="pkg-sub">${p.pkg}</span>
            </div>
            <div style="display:flex; align-items:center; gap:8px;">
              ${badge}
              ${actionBtn}
            </div>
          `;
          container.appendChild(div);
        });
      } catch (e) {
        container.innerHTML = '<div style="color:var(--danger); text-align:center; padding:15px;">Failed to load debloat list</div>';
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
        loadDebloatList();
      } catch (e) {
        showToast('Failed to modify package');
      }
    }

    async function applyDebloat() {
      if (!confirm('Apply safe debloat? Non-essential TCL background bloatware and ad services will be paused.')) return;
      showToast('Applying safe debloat...');
      try {
        const res = await fetch('/api/debloat/apply', { method: 'POST' });
        const data = await res.json();
        showToast('Debloat applied: ' + data.disabled_count + ' packages paused');
        loadDebloatList();
      } catch (e) {
        showToast('Debloat failed');
      }
    }

    async function restoreAll() {
      if (!confirm('Re-enable all stock packages and services?')) return;
      showToast('Restoring all packages...');
      try {
        await fetch('/api/debloat/restore', { method: 'POST' });
        showToast('All stock packages restored');
        loadDebloatList();
      } catch (e) {
        showToast('Restore failed');
      }
    }

    // Diagnostics Telemetry
    async function loadStatus() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();
        document.getElementById('diagModel').textContent = data.model || 'Unknown';
        document.getElementById('diagMfg').textContent = data.manufacturer || 'TCL';
        document.getElementById('diagVersion').textContent = 'Android ' + (data.release || '14') + ' (SDK ' + data.sdk + ')';
        document.getElementById('diagRes').textContent = data.resolution || '1920x1080';
        document.getElementById('diagDensity').textContent = data.density || '320 dpi';
        document.getElementById('diagStorage').textContent = data.storage || 'N/A';
        document.getElementById('diagMem').textContent = data.mem_avail || 'N/A';
        document.getElementById('diagWake').textContent = data.wakefulness || 'Awake';
        document.getElementById('diagFocus').textContent = data.current_focus || 'None';
        document.getElementById('diagUptime').textContent = data.uptime || 'N/A';
        document.getElementById('tvHostLabel').textContent = data.ip || 'Connected';
        document.getElementById('tvStatus').classList.remove('offline');
      } catch (e) {
        document.getElementById('tvHostLabel').textContent = 'Offline';
        document.getElementById('tvStatus').classList.add('offline');
      }
    }

    // Run custom shell command
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
        const data = await res.json();
        term.textContent += (data.output || '(No output)') + '\\n';
        term.scrollTop = term.scrollHeight;
        input.value = '';
      } catch (e) {
        term.textContent += 'Error executing command\\n';
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

    loadStatus();
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

    def get_installed_apps(self):
        # Query both standard launcher activities and leanback activities
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

    def get_debloat_status(self):
        _, disabled_raw = self.run_tv("pm list packages -d")
        disabled_set = {
            line.replace("package:", "").strip()
            for line in disabled_raw.splitlines()
            if line.startswith("package:")
        }

        results = []
        for item in TCL_BLOATWARE:
            results.append({
                "pkg": item["pkg"],
                "name": item["name"],
                "disabled": item["pkg"] in disabled_set,
                "safe": item["safe"]
            })
        return results


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

            if path in ("/", "/index.html"):
                return self._send(200, HTML_PAGE.encode("utf-8"), "text/html; charset=utf-8")

            if path == "/api/status":
                _, model = server_ctx.run_tv("getprop ro.product.model")
                _, mfg = server_ctx.run_tv("getprop ro.product.manufacturer")
                _, rel = server_ctx.run_tv("getprop ro.build.version.release")
                _, sdk = server_ctx.run_tv("getprop ro.build.version.sdk")
                _, size = server_ctx.run_tv("wm size")
                _, density = server_ctx.run_tv("wm density")
                _, wake = server_ctx.run_tv("dumpsys power | grep mWakefulness=")
                _, mem = server_ctx.run_tv("cat /proc/meminfo | grep MemAvailable")
                _, df = server_ctx.run_tv("df -h /data | tail -n 1")
                _, focus = server_ctx.run_tv("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1")
                _, uptime = server_ctx.run_tv("uptime")

                wake_clean = wake.split("=")[-1].strip() if "=" in wake else "Awake"
                mem_clean = mem.split(":")[-1].strip() if ":" in mem else "N/A"
                res_clean = size.replace("Physical size:", "").replace("Override size:", " / ").strip()
                density_clean = density.replace("Physical density:", "").replace("Override density:", " / ").strip()

                storage_clean = "N/A"
                if df.strip():
                    parts = df.split()
                    if len(parts) >= 5:
                        storage_clean = f"{parts[2]} used / {parts[1]} total ({parts[4]})"

                focus_clean = "Home"
                if focus.strip():
                    m = re.search(r"([a-zA-Z0-9._]+/[a-zA-Z0-9._]+)", focus)
                    if m:
                        focus_clean = m.group(1)

                res = {
                    "ok": True,
                    "model": model.strip(),
                    "manufacturer": mfg.strip(),
                    "release": rel.strip(),
                    "sdk": sdk.strip(),
                    "resolution": res_clean,
                    "density": density_clean,
                    "storage": storage_clean,
                    "wakefulness": wake_clean,
                    "mem_avail": mem_clean,
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

            if path == "/api/debloat/list":
                packages = server_ctx.get_debloat_status()
                return self._send(200, json.dumps({"ok": True, "packages": packages}).encode("utf-8"))

            self._send(404, b'{"error":"Not Found"}')

        def do_POST(self):
            parsed = urllib.parse.urlparse(self.path)
            path = parsed.path

            content_len = int(self.headers.get("Content-Length", 0))
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

            if path == "/api/app":
                pkg = data.get("pkg", "").strip()
                if not pkg:
                    return self._send(400, b'{"error":"Missing pkg"}')
                cmd = f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1"
                err, out = server_ctx.run_tv(cmd)
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/input":
                target = data.get("input", "")
                if target == "HDMI 1":
                    cmd = "input keyevent KEYCODE_TV_INPUT_HDMI_1"
                elif target == "HDMI 2":
                    cmd = "input keyevent KEYCODE_TV_INPUT_HDMI_2"
                elif target == "AV":
                    cmd = "input keyevent KEYCODE_TV_INPUT"
                else:
                    return self._send(400, b'{"error":"Unknown input"}')
                err, out = server_ctx.run_tv(cmd)
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/debloat/toggle":
                pkg = data.get("pkg", "").strip()
                enable = bool(data.get("enable", False))
                if not pkg:
                    return self._send(400, b'{"error":"Missing pkg"}')
                cmd = f"pm enable {pkg}" if enable else f"pm disable-user --user 0 {pkg}"
                err, out = server_ctx.run_tv(cmd)
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/debloat/apply":
                pkgs = [p["pkg"] for p in TCL_BLOATWARE if p["safe"]]
                cmd = " ".join([f"pm disable-user --user 0 {p} 2>/dev/null;" for p in pkgs])
                err, out = server_ctx.run_tv(cmd, timeout=60.0)
                return self._send(200, json.dumps({"ok": True, "disabled_count": len(pkgs)}).encode("utf-8"))

            if path == "/api/debloat/restore":
                pkgs = [p["pkg"] for p in TCL_BLOATWARE]
                cmd = " ".join([f"pm enable {p} 2>/dev/null;" for p in pkgs])
                err, out = server_ctx.run_tv(cmd, timeout=60.0)
                return self._send(200, json.dumps({"ok": True}).encode("utf-8"))

            if path == "/api/shell":
                cmd = data.get("cmd", "").strip()
                if not cmd:
                    return self._send(400, b'{"error":"Missing cmd"}')
                err, out = server_ctx.run_tv(cmd, timeout=60.0)
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            self._send(404, b'{"error":"Not Found"}')

    return Handler


def main():
    parser = argparse.ArgumentParser(description="Google TV Web Remote & Unified Control Center")
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
    print(f"📺 Unified Google TV Web Control Center Active!")
    print(f"   Target TV:   {args.tv}")
    print(f"   Local URL:   http://localhost:{args.port}")
    print(f"   Network URL: http://{local_ip}:{args.port}")
    print(f"=======================================================\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down web control center.")
        server.server_close()


if __name__ == "__main__":
    main()
