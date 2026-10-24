#!/usr/bin/env python3
"""TV Web Remote & Control Panel.

A lightweight, zero-dependency browser-based TV remote and control center
combining the best features of:
  - HoangTran0410/tv-remote (tactile D-Pad, volume, media, live typing, screen capture)
  - Pagamase/cleanyourtv-panel (native launcher integration, app launcher, screenshots)
  - gethari/clean-your-tv-companion (TCL Android 14 diagnostics, debloat status)

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
    "UP": 19,               # KEYCODE_DPAD_UP
    "DOWN": 20,             # KEYCODE_DPAD_DOWN
    "LEFT": 21,             # KEYCODE_DPAD_LEFT
    "RIGHT": 22,            # KEYCODE_DPAD_RIGHT
    "OK": 23,               # KEYCODE_DPAD_CENTER
    "CENTER": 23,
    "BACK": 4,              # KEYCODE_BACK
    "HOME": 3,              # KEYCODE_HOME
    "MENU": 82,             # KEYCODE_MENU
    "SETTINGS": 176,        # KEYCODE_SETTINGS
    "POWER": 26,            # KEYCODE_POWER
    "VOL_UP": 24,           # KEYCODE_VOLUME_UP
    "VOL_DOWN": 25,         # KEYCODE_VOLUME_DOWN
    "MUTE": 164,            # KEYCODE_VOLUME_MUTE
    "PLAY_PAUSE": 85,       # KEYCODE_MEDIA_PLAY_PAUSE
    "REWIND": 89,           # KEYCODE_MEDIA_REWIND
    "FAST_FORWARD": 90,     # KEYCODE_MEDIA_FAST_FORWARD
    "PREV": 88,             # KEYCODE_MEDIA_PREVIOUS
    "NEXT": 87,             # KEYCODE_MEDIA_NEXT
    "INFO": 165,            # KEYCODE_INFO
    "GUIDE": 172,           # KEYCODE_GUIDE
    "CHANNEL_UP": 166,      # KEYCODE_CHANNEL_UP
    "CHANNEL_DOWN": 167,    # KEYCODE_CHANNEL_DOWN
}

COMMON_APPS = [
    {"name": "YouTube", "pkg": "com.google.android.youtube.tv", "icon": "▶️"},
    {"name": "Netflix", "pkg": "com.netflix.ninja", "icon": "🎬"},
    {"name": "Prime Video", "pkg": "com.amazon.amazonvideo.livingroom", "icon": "📦"},
    {"name": "Plex", "pkg": "com.plexapp.android", "icon": "🍿"},
    {"name": "Spotify", "pkg": "com.spotify.tv.android", "icon": "🎵"},
    {"name": "Twitch", "pkg": "tv.twitch.android.app", "icon": "👾"},
    {"name": "Disney+", "pkg": "com.disney.disneyplus", "icon": "✨"},
    {"name": "Settings", "pkg": "com.android.tv.settings", "icon": "⚙️"},
]

HTML_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
  <title>Google TV Web Remote</title>
  <style>
    :root {
      --bg: #0d0f12;
      --card-bg: #15181e;
      --card-border: #222731;
      --accent: #3b82f6;
      --accent-glow: rgba(59, 130, 246, 0.25);
      --text: #f3f4f6;
      --text-muted: #9ca3af;
      --danger: #ef4444;
      --success: #10b981;
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
      padding: 16px 12px 48px;
    }
    header {
      width: 100%;
      max-width: 440px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 16px;
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
      padding: 3px 8px;
      border-radius: 999px;
      background: rgba(16, 185, 129, 0.15);
      color: var(--success);
      border: 1px solid rgba(16, 185, 129, 0.3);
      display: flex;
      align-items: center;
      gap: 5px;
    }
    .status-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--success);
      box-shadow: 0 0 6px var(--success);
    }
    .tabs {
      display: flex;
      width: 100%;
      max-width: 440px;
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 12px;
      padding: 3px;
      margin-bottom: 16px;
    }
    .tab-btn {
      flex: 1;
      background: transparent;
      border: none;
      color: var(--text-muted);
      padding: 8px 12px;
      font-size: 0.82rem;
      font-weight: 500;
      border-radius: 9px;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .tab-btn.active {
      background: var(--card-border);
      color: var(--text);
    }
    .tab-content {
      width: 100%;
      max-width: 440px;
      display: none;
      flex-direction: column;
      gap: 16px;
    }
    .tab-content.active { display: flex; }

    /* Remote Cards */
    .card {
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: var(--radius);
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    .card-title {
      font-size: 0.75rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--text-muted);
      font-weight: 600;
    }

    /* Buttons */
    button.btn {
      background: #1c212a;
      border: 1px solid #28303d;
      color: var(--text);
      font-size: 0.95rem;
      font-weight: 500;
      border-radius: 12px;
      padding: 12px;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      user-select: none;
      transition: background 0.1s, transform 0.08s, border-color 0.1s;
    }
    button.btn:active {
      background: #272f3d;
      transform: scale(0.96);
      border-color: var(--accent);
    }
    button.btn-danger {
      color: #fca5a5;
      border-color: rgba(239, 68, 68, 0.3);
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
      border: 1px solid #2c3545;
      color: var(--text);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      user-select: none;
      transition: all 0.1s;
    }
    .dpad-btn:active {
      background: #2d3748;
      box-shadow: 0 0 12px var(--accent-glow);
      border-color: var(--accent);
    }
    .dpad-up {
      top: 0; left: 66px; width: 88px; height: 60px;
      border-radius: 24px 24px 8px 8px;
    }
    .dpad-down {
      bottom: 0; left: 66px; width: 88px; height: 60px;
      border-radius: 8px 8px 24px 24px;
    }
    .dpad-left {
      top: 66px; left: 0; width: 60px; height: 88px;
      border-radius: 24px 8px 8px 24px;
    }
    .dpad-right {
      top: 66px; right: 0; width: 60px; height: 88px;
      border-radius: 8px 24px 24px 8px;
    }
    .dpad-center {
      top: 68px; left: 68px; width: 84px; height: 84px;
      border-radius: 50%;
      background: #252d3a;
      border: 2px solid #3b4759;
      font-weight: 700;
      font-size: 0.9rem;
      letter-spacing: 0.04em;
    }
    .dpad-center:active {
      background: var(--accent);
      border-color: #60a5fa;
      transform: scale(0.94);
    }

    /* Row grids */
    .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; }
    .grid-4 { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }

    /* Text Typing Bar */
    .typing-box {
      display: flex;
      gap: 8px;
    }
    .text-input {
      flex: 1;
      background: #11141a;
      border: 1px solid var(--card-border);
      border-radius: 10px;
      padding: 10px 14px;
      color: var(--text);
      font-size: 0.92rem;
      outline: none;
    }
    .text-input:focus { border-color: var(--accent); }

    /* Live Screen Preview */
    .screen-img-box {
      width: 100%;
      background: #000;
      border-radius: 10px;
      overflow: hidden;
      aspect-ratio: 16/9;
      display: flex;
      align-items: center;
      justify-content: center;
      border: 1px solid var(--card-border);
    }
    .screen-img {
      width: 100%;
      height: 100%;
      object-fit: contain;
      display: block;
    }

    /* Diagnostics Table */
    .diag-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.85rem;
    }
    .diag-table tr {
      border-bottom: 1px solid #1f2530;
    }
    .diag-table td {
      padding: 8px 4px;
    }
    .diag-table td:first-child {
      color: var(--text-muted);
      width: 40%;
    }
    .diag-table td:last-child {
      color: var(--text);
      font-weight: 500;
      text-align: right;
    }

    /* Quick App Button */
    .app-btn {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
      padding: 12px 6px;
      font-size: 0.76rem;
    }
    .app-icon { font-size: 1.3rem; }

    /* Toast */
    #toast {
      position: fixed;
      bottom: 20px;
      left: 50%;
      transform: translateX(-50%) translateY(100px);
      background: #1e293b;
      color: #fff;
      padding: 8px 16px;
      border-radius: 999px;
      font-size: 0.82rem;
      box-shadow: 0 4px 14px rgba(0,0,0,0.4);
      opacity: 0;
      transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
      pointer-events: none;
      z-index: 100;
    }
    #toast.show {
      opacity: 1;
      transform: translateX(-50%) translateY(0);
    }
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

  <div class="tabs">
    <button class="tab-btn active" onclick="switchTab('remote')">Remote</button>
    <button class="tab-btn" onclick="switchTab('screen')">Live Screen</button>
    <button class="tab-btn" onclick="switchTab('apps')">Apps & Inputs</button>
    <button class="tab-btn" onclick="switchTab('system')">TV System</button>
  </div>

  <!-- TAB 1: REMOTE -->
  <div class="tab-content active" id="tab-remote">
    <div class="card">
      <div class="grid-3">
        <button class="btn btn-danger" onclick="sendKey('POWER')">⏻ Power</button>
        <button class="btn" onclick="sendKey('SETTINGS')">⚙️ Settings</button>
        <button class="btn" onclick="sendKey('MENU')">☰ Menu</button>
      </div>

      <!-- D-PAD -->
      <div class="dpad-container">
        <button class="dpad-btn dpad-up" onclick="sendKey('UP')">▲</button>
        <button class="dpad-btn dpad-left" onclick="sendKey('LEFT')">◀</button>
        <button class="dpad-btn dpad-center" onclick="sendKey('OK')">OK</button>
        <button class="dpad-btn dpad-right" onclick="sendKey('RIGHT')">▶</button>
        <button class="dpad-btn dpad-down" onclick="sendKey('DOWN')">▼</button>
      </div>

      <div class="grid-2">
        <button class="btn" onclick="sendKey('BACK')">↩ Back</button>
        <button class="btn" onclick="sendKey('HOME')">🏠 Home</button>
      </div>
    </div>

    <!-- Volume & Media -->
    <div class="card">
      <div class="card-title">Volume & Audio</div>
      <div class="grid-3">
        <button class="btn" onclick="sendKey('VOL_DOWN')">🔉 Vol -</button>
        <button class="btn" onclick="sendKey('MUTE')">🔇 Mute</button>
        <button class="btn" onclick="sendKey('VOL_UP')">🔊 Vol +</button>
      </div>
    </div>

    <div class="card">
      <div class="card-title">Media Controls</div>
      <div class="grid-4">
        <button class="btn" onclick="sendKey('PREV')">⏮</button>
        <button class="btn" onclick="sendKey('REWIND')">⏪</button>
        <button class="btn" onclick="sendKey('PLAY_PAUSE')">⏯</button>
        <button class="btn" onclick="sendKey('FAST_FORWARD')">⏩</button>
      </div>
    </div>

    <!-- Text Typing -->
    <div class="card">
      <div class="card-title">Type on TV</div>
      <div class="typing-box">
        <input type="text" id="typeInput" class="text-input" placeholder="Type text and press Send..." onkeydown="if(event.key==='Enter') sendText()">
        <button class="btn" onclick="sendText()">Send</button>
      </div>
    </div>
  </div>

  <!-- TAB 2: LIVE SCREEN -->
  <div class="tab-content" id="tab-screen">
    <div class="card">
      <div class="card-title">TV Screen Mirror</div>
      <div class="screen-img-box">
        <img id="screenImg" class="screen-img" src="/api/screenshot" alt="TV Screen">
      </div>
      <div class="grid-2">
        <button class="btn" onclick="refreshScreen()">🔄 Refresh Shot</button>
        <button class="btn" id="autoBtn" onclick="toggleAutoRefresh()">⏱ Auto (Off)</button>
      </div>
    </div>
  </div>

  <!-- TAB 3: APPS & INPUTS -->
  <div class="tab-content" id="tab-apps">
    <div class="card">
      <div class="card-title">Quick Apps</div>
      <div class="grid-4" id="quickAppsGrid">
        <!-- Rendered by JS -->
      </div>
    </div>

    <div class="card">
      <div class="card-title">HDMI Inputs</div>
      <div class="grid-3">
        <button class="btn" onclick="launchInput('HDMI 1')">🔌 HDMI 1</button>
        <button class="btn" onclick="launchInput('HDMI 2')">🔌 HDMI 2</button>
        <button class="btn" onclick="launchInput('AV')">📺 Live TV</button>
      </div>
    </div>

    <div class="card">
      <div class="card-title">Launcher Custom Actions</div>
      <div class="grid-2">
        <button class="btn" onclick="sendKey('HOME')">🏠 TV Home</button>
        <button class="btn" onclick="sendShell('am start -n com.gathin.tvhome/.MainActivity')">⚡ Launch TVHome</button>
      </div>
    </div>
  </div>

  <!-- TAB 4: SYSTEM & DIAGNOSTICS -->
  <div class="tab-content" id="tab-system">
    <div class="card">
      <div class="card-title">TCL Device Diagnostics</div>
      <table class="diag-table" id="diagTable">
        <tr><td>Model</td><td id="diagModel">Loading...</td></tr>
        <tr><td>Manufacturer</td><td id="diagMfg">-</td></tr>
        <tr><td>Android Version</td><td id="diagVersion">-</td></tr>
        <tr><td>SDK Level</td><td id="diagSdk">-</td></tr>
        <tr><td>Wakefulness</td><td id="diagWake">-</td></tr>
        <tr><td>IP Address</td><td id="diagIp">-</td></tr>
        <tr><td>RAM Available</td><td id="diagMem">-</td></tr>
      </table>
      <button class="btn" onclick="loadStatus()">🔄 Refresh Diagnostics</button>
    </div>

    <div class="card">
      <div class="card-title">Quick Commands</div>
      <div class="grid-2">
        <button class="btn" onclick="sendShell('input keyevent KEYCODE_WAKEUP')">☀️ Wake TV</button>
        <button class="btn btn-danger" onclick="sendShell('input keyevent KEYCODE_SLEEP')">🌙 Sleep TV</button>
      </div>
    </div>
  </div>

  <div id="toast"></div>

  <script>
    let autoInterval = null;
    const apps = [
      { name: "YouTube", pkg: "com.google.android.youtube.tv", icon: "▶️" },
      { name: "Netflix", pkg: "com.netflix.ninja", icon: "🎬" },
      { name: "Prime", pkg: "com.amazon.amazonvideo.livingroom", icon: "📦" },
      { name: "Plex", pkg: "com.plexapp.android", icon: "🍿" },
      { name: "Spotify", pkg: "com.spotify.tv.android", icon: "🎵" },
      { name: "Twitch", pkg: "tv.twitch.android.app", icon: "👾" },
      { name: "Disney+", pkg: "com.disney.disneyplus", icon: "✨" },
      { name: "Settings", pkg: "com.android.tv.settings", icon: "⚙️" }
    ];

    function showToast(msg) {
      const t = document.getElementById('toast');
      t.textContent = msg;
      t.classList.add('show');
      setTimeout(() => t.classList.remove('show'), 2000);
    }

    function switchTab(tabId) {
      document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
      event.target.classList.add('active');
      document.getElementById('tab-' + tabId).classList.add('active');
      if (tabId === 'screen') refreshScreen();
      if (tabId === 'system') loadStatus();
    }

    async function sendKey(key) {
      if (navigator.vibrate) navigator.vibrate(25);
      try {
        const res = await fetch('/api/key', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ key })
        });
        const data = await res.json();
        if (!data.ok) showToast('Error: ' + (data.error || 'Failed'));
      } catch (e) {
        showToast('Connection error');
      }
    }

    async function sendText() {
      const input = document.getElementById('typeInput');
      const text = input.value.trim();
      if (!text) return;
      try {
        const res = await fetch('/api/text', {
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

    async function launchApp(pkg) {
      showToast('Opening ' + pkg + '...');
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

    async function sendShell(cmd) {
      try {
        const res = await fetch('/api/shell', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ cmd })
        });
        const data = await res.json();
        showToast(data.output ? data.output.trim() : 'Executed');
      } catch (e) {
        showToast('Command failed');
      }
    }

    function refreshScreen() {
      const img = document.getElementById('screenImg');
      img.src = '/api/screenshot?t=' + Date.now();
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

    async function loadStatus() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();
        document.getElementById('diagModel').textContent = data.model || 'Unknown';
        document.getElementById('diagMfg').textContent = data.manufacturer || 'TCL';
        document.getElementById('diagVersion').textContent = 'Android ' + (data.release || '14');
        document.getElementById('diagSdk').textContent = data.sdk || '34';
        document.getElementById('diagWake').textContent = data.wakefulness || 'Awake';
        document.getElementById('diagIp').textContent = data.ip || '192.168.0.87';
        document.getElementById('diagMem').textContent = data.mem_avail || 'Available';
        document.getElementById('tvHostLabel').textContent = data.ip || 'Connected';
      } catch (e) {
        document.getElementById('tvHostLabel').textContent = 'Offline';
      }
    }

    function initApps() {
      const grid = document.getElementById('quickAppsGrid');
      grid.innerHTML = '';
      apps.forEach(app => {
        const btn = document.createElement('button');
        btn.className = 'btn app-btn';
        btn.onclick = () => launchApp(app.pkg);
        btn.innerHTML = `<span class="app-icon">${app.icon}</span><span>${app.name}</span>`;
        grid.appendChild(btn);
      });
    }

    // Keyboard support on PC
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
    });

    initApps();
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

    def run_tv(self, cmd):
        with self.lock:
            try:
                dev = tv.connect()
                out = dev.shell(cmd, read_timeout_s=30.0)
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
                code, model = server_ctx.run_tv("getprop ro.product.model")
                _, mfg = server_ctx.run_tv("getprop ro.product.manufacturer")
                _, rel = server_ctx.run_tv("getprop ro.build.version.release")
                _, sdk = server_ctx.run_tv("getprop ro.build.version.sdk")
                _, wake = server_ctx.run_tv("dumpsys power | grep mWakefulness=")
                _, mem = server_ctx.run_tv("cat /proc/meminfo | grep MemAvailable")

                wake_clean = wake.split("=")[-1].strip() if "=" in wake else "Awake"
                mem_clean = mem.split(":")[-1].strip() if ":" in mem else "N/A"

                res = {
                    "ok": code == 0,
                    "model": model.strip(),
                    "manufacturer": mfg.strip(),
                    "release": rel.strip(),
                    "sdk": sdk.strip(),
                    "wakefulness": wake_clean,
                    "ip": server_ctx.tv_host,
                    "mem_avail": mem_clean,
                }
                return self._send(200, json.dumps(res).encode("utf-8"))

            if path == "/api/screenshot":
                png_bytes = server_ctx.capture_screenshot()
                if png_bytes:
                    return self._send(200, png_bytes, "image/png")
                return self._send(500, b'{"error":"Screenshot failed"}')

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
                code = KEYCODES.get(key_name)
                if code is None:
                    return self._send(400, b'{"error":"Unknown key"}')
                err, out = server_ctx.run_tv(f"input keyevent {code}")
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/text":
                text = data.get("text", "")
                if not text:
                    return self._send(400, b'{"error":"Empty text"}')
                # Escape characters safe for Android 'input text'
                escaped = text.replace(" ", "%s").replace("&", "\\&").replace("'", "\\'")
                err, out = server_ctx.run_tv(f"input text '{escaped}'")
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

            if path == "/api/shell":
                cmd = data.get("cmd", "").strip()
                if not cmd:
                    return self._send(400, b'{"error":"Missing cmd"}')
                err, out = server_ctx.run_tv(cmd)
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            self._send(404, b'{"error":"Not Found"}')

    return Handler


def main():
    parser = argparse.ArgumentParser(description="TV Web Remote")
    parser.add_argument("--port", type=int, default=8765, help="HTTP port (default 8765)")
    parser.add_argument("--tv", default=os.environ.get("TV_HOST", "192.168.0.87"), help="TV IP address")
    args = parser.parse_args()

    ctx = WebRemoteServer(port=args.port, tv_host=args.tv)
    handler = create_handler(ctx)
    server = ThreadingHTTPServer(("0.0.0.0", args.port), handler)

    # Local IP for user convenience
    local_ip = "127.0.0.1"
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        pass

    print(f"\n=======================================================")
    print(f"📺 Google TV Web Remote & Control Panel Running!")
    print(f"   Target TV:   {args.tv}")
    print(f"   Local URL:   http://localhost:{args.port}")
    print(f"   Network URL: http://{local_ip}:{args.port}")
    print(f"=======================================================\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down web remote.")
        server.server_close()


if __name__ == "__main__":
    main()
