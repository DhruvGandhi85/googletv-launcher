#!/usr/bin/env python3
"""Google TV Web Remote & Interactive Master Control Center.

Consolidated tab and subtab architecture following Jakub Krehel's "Less is More" minimalism:
- English-only interface
- Built specifically for Google TV
- Dual-column interactive remote with live screen mirror touch-to-click alongside ergonomic keypad
- Media Hub with Trakt cloud sync (Watchlist, In Progress, Trending, Stremio 1-click play) and Live ESPN Scoreboard (NBA, NFL, EPL, UCL, MLB, NHL)
- Complete maintenance & settings suite:
  - Remote: Live Screen Mirror, Touch Passthrough, Text & URL Input, HDMI Switcher, Full Remote D-Pad
  - Launcher: Layout (Visual Mockup, Dock Apps, Grid, Push to TV) & Apps (List, Filter, Actions, APK Install)
  - Media: Trakt (Personal Watchlist, Up Next, Trending, 1-Click Play on TV) & Sports (Live ESPN Scoreboards across 9 Leagues)
  - Storage: Files (Explorer, Download, Delete, Open on TV) & Wallpaper (Upload & Stage)
  - System: Debloat (Google TV Presets), Health (Meters & Telemetry), Automation (Sleep Timer, Night Mode), Setup (Speed, Reboot, ADB Console)
- No gratuitous text/additions or eyebrow elements
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
import urllib.request
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
    "INPUT": 178,
    "TV_INPUT": 178,
    "SOURCE": 178,
    "ASSIST": 231,
    "VOICE": 231,
    "TV": 170,
    "EXIT": 4,
    "RETURN": 4,
    "OPTION": 82,
    "DISPLAY": 165,
    "ZOOM": 268,
    "PICTURE_MODE": 256,
    "AUDIO_MODE": 257,
    "USB": 255,
}

TRAKT_CLIENT_ID = "TAFn3uRVPHeS8skvQDnS_DyymbWYbs6Sm9Q-IempuEc"
TRAKT_USER = "DhruvG85"

ESPN_LEAGUES = {
    "nba": ("NBA", "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard"),
    "nfl": ("NFL", "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard"),
    "epl": ("Premier League", "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard"),
    "ucl": ("Champions League", "https://site.api.espn.com/apis/site/v2/sports/soccer/uefa.champions/scoreboard"),
    "mlb": ("MLB", "https://site.api.espn.com/apis/site/v2/sports/baseball/mlb/scoreboard"),
    "nhl": ("NHL", "https://site.api.espn.com/apis/site/v2/sports/hockey/nhl/scoreboard"),
    "cfb": ("College Football", "https://site.api.espn.com/apis/site/v2/sports/football/college-football/scoreboard?limit=14"),
    "cbb": ("College Basketball", "https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/scoreboard?limit=14"),
    "mls": ("MLS", "https://site.api.espn.com/apis/site/v2/sports/soccer/usa.1/scoreboard"),
}

PRESETS = {
    "tcl": {
        "name": "TCL Google TV",
        "packages": [
            {"pkg": "com.tcl.tv.plus", "name": "TCL TV+", "cat": "Streaming", "impact": "TCL streaming channels", "safe": True},
            {"pkg": "com.tcl.channelplus", "name": "TCL Channel Plus", "cat": "Streaming", "impact": "Channel aggregator", "safe": True},
            {"pkg": "com.tcl.tv.tclhome_passive", "name": "TCL Home Passive", "cat": "Telemetry", "impact": "Background telemetry and partner promos", "safe": True},
            {"pkg": "com.tcl.partnercustomizer", "name": "TCL Partner Customizer", "cat": "Promotions", "impact": "Promotional banners in stock UI", "safe": True},
            {"pkg": "com.tcl.interactive", "name": "TCL Interactive Telemetry", "cat": "Telemetry", "impact": "Viewing analytics collector", "safe": True},
            {"pkg": "com.tcl.smartlink.core", "name": "TCL SmartLink Core", "cat": "Optional", "impact": "Smart home link service", "safe": True},
            {"pkg": "com.tcl.logkit", "name": "TCL LogKit", "cat": "Diagnostics", "impact": "Diagnostic log agent", "safe": True},
            {"pkg": "com.tcl.ttvs", "name": "TCL Viewing Services", "cat": "Telemetry", "impact": "T-Cast viewing tracker", "safe": True},
            {"pkg": "com.tcl.messagebox", "name": "TCL Message Box", "cat": "Notifications", "impact": "Marketing push notification agent", "safe": True},
            {"pkg": "com.tcl.usercenter", "name": "TCL User Center", "cat": "Diagnostics", "impact": "Account tracking service", "safe": True},
            {"pkg": "com.tcl.useragreement", "name": "TCL User Agreement", "cat": "Diagnostics", "impact": "User agreement prompt loop", "safe": True},
            {"pkg": "com.tcl.exhibit", "name": "TCL Retail Exhibit", "cat": "Store Mode", "impact": "Store demo overlay", "safe": True},
            {"pkg": "com.tcl.esticker", "name": "TCL E-Sticker", "cat": "Store Mode", "impact": "Store sticker component", "safe": True},
            {"pkg": "com.tcl.browser", "name": "TCL Built-in Browser", "cat": "Optional", "impact": "Stock browser", "safe": True},
            {"pkg": "com.tcl.ocean.instructions", "name": "TCL Ocean Instructions", "cat": "Optional", "impact": "Electronic instruction manual", "safe": True},
            {"pkg": "com.tcl.repairguide", "name": "TCL Repair Guide", "cat": "Optional", "impact": "Diagnostic repair guide", "safe": True},
            {"pkg": "com.tcl.smartalexa", "name": "TCL Alexa Integration", "cat": "Optional", "impact": "Alexa integration components", "safe": True},
            {"pkg": "com.tcl.UpdatePeripheral", "name": "TCL Peripheral Update", "cat": "Diagnostics", "impact": "Peripheral update agent", "safe": True},
            {"pkg": "com.tcl.hearaid", "name": "TCL HearAid Service", "cat": "Optional", "impact": "Hearing aid accessibility background service", "safe": True},
            {"pkg": "com.tcl.suspension", "name": "TCL Suspension Widget", "cat": "Optional", "impact": "Floating overlay widget", "safe": True},
            {"pkg": "com.google.android.feedback", "name": "Google Play Feedback", "cat": "Diagnostics", "impact": "Feedback reporting service", "safe": True},
            {"pkg": "com.android.tv.feedbackconsent", "name": "TV Feedback Consent", "cat": "Diagnostics", "impact": "Feedback consent prompt component", "safe": True},
            {"pkg": "android.autoinstalls.config.google.gtvpai", "name": "Google PAI Auto-Installer", "cat": "Optional", "impact": "Partner app auto-installer", "safe": True},
            {"pkg": "com.google.android.apps.tv.launcherx", "name": "Stock Google TV Launcher", "cat": "Launcher", "impact": "Ad-heavy stock launcher", "safe": True},
            {"pkg": "com.google.android.tungsten.setupwraith", "name": "Google TV Setup Wraith", "cat": "Optional", "impact": "Setup wizard component", "safe": True},
            {"pkg": "com.google.android.apps.tv.dreamx", "name": "Google TV Ambient Backdrop", "cat": "Optional", "impact": "Google TV screensaver", "safe": True},
        ]
    },
    "sony": {
        "name": "Sony BRAVIA Google TV",
        "packages": [
            {"pkg": "tv.samba.ssm", "name": "Samba TV ACR", "cat": "Telemetry", "impact": "On-screen content recognition for ads", "safe": True},
            {"pkg": "tv.samba.ssm.ui", "name": "Samba TV Controls", "cat": "Telemetry", "impact": "Samba TV companion telemetry", "safe": True},
            {"pkg": "com.sonyericsson.idd.agent", "name": "Sony Usage-Data Agent", "cat": "Diagnostics", "impact": "Usage data diagnostic reporting", "safe": True},
            {"pkg": "com.sony.dtv.promos", "name": "Sony Promotions", "cat": "Promotions", "impact": "Promotional banner component", "safe": True},
            {"pkg": "com.google.android.feedback", "name": "Google Feedback", "cat": "Diagnostics", "impact": "Feedback reporter", "safe": True},
            {"pkg": "com.google.android.apps.tv.launcherx", "name": "Stock Google TV Launcher", "cat": "Launcher", "impact": "Stock ad launcher", "safe": True},
        ]
    },
    "common": {
        "name": "Google TV (Common)",
        "packages": [
            {"pkg": "com.google.android.feedback", "name": "Google Feedback", "cat": "Diagnostics", "impact": "Telemetry and feedback service", "safe": True},
            {"pkg": "com.android.tv.feedbackconsent", "name": "TV Feedback Consent", "cat": "Diagnostics", "impact": "Consent prompt dialog component", "safe": True},
            {"pkg": "com.google.android.play.games", "name": "Google Play Games", "cat": "Optional", "impact": "Play Games sign-in & saves", "safe": True},
            {"pkg": "com.google.android.apps.tv.launcherx", "name": "Stock Google TV Launcher", "cat": "Launcher", "impact": "Stock launcher recommendations", "safe": True},
            {"pkg": "com.google.android.apps.tv.dreamx", "name": "Google TV Screensaver", "cat": "Optional", "impact": "Ambient backdrop screensaver", "safe": True},
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
  <title>Google TV Controller</title>
  <style>
    :root {
      --bg: #090a0f;
      --card: #12151c;
      --card-sub: #181d26;
      --card-border: #1e2430;
      --ink: #f0f3f6;
      --muted: #8c96a5;
      --accent: #2f6df6;
      --accent-hover: #3b82f6;
      --danger: #ef4444;
      --success: #10b981;
      --warning: #f59e0b;
      --radius: 12px;
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
      margin-bottom: 12px;
      padding: 0 4px;
    }
    .brand {
      font-weight: 700;
      font-size: 1.15rem;
      letter-spacing: -0.01em;
      color: var(--ink);
    }
    .chips {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .chip {
      font-size: 0.76rem;
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

    /* Navigation Container */
    .nav-container {
      width: 100%;
      max-width: 1240px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      margin-bottom: 14px;
    }
    nav.main-tabs {
      display: flex;
      width: 100%;
      background: var(--card);
      border: 1px solid var(--card-border);
      border-radius: 12px;
      padding: 4px;
      gap: 4px;
    }
    .main-tab-btn {
      flex: 1;
      background: transparent;
      border: none;
      color: var(--muted);
      padding: 9px 8px;
      font-size: 0.86rem;
      font-weight: 500;
      border-radius: 9px;
      cursor: pointer;
      transition: all 0.15s ease;
      text-align: center;
    }
    .main-tab-btn.active {
      background: #202734;
      color: #fff;
      font-weight: 600;
    }

    /* Subtabs Bar */
    .subtabs-bar {
      display: none;
      width: 100%;
      background: var(--card-sub);
      border: 1px solid var(--card-border);
      border-radius: 10px;
      padding: 4px;
      gap: 4px;
    }
    .subtabs-bar.active {
      display: flex;
    }
    .subtab-btn {
      flex: 1;
      background: transparent;
      border: none;
      color: var(--muted);
      padding: 7px 8px;
      font-size: 0.8rem;
      font-weight: 500;
      border-radius: 7px;
      cursor: pointer;
      transition: all 0.15s ease;
      text-align: center;
    }
    .subtab-btn.active {
      background: #2a3344;
      color: #fff;
      font-weight: 600;
    }

    /* Main Container */
    main {
      width: 100%;
      max-width: 1240px;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    .tab-panel {
      display: none;
      width: 100%;
      flex-direction: column;
      gap: 14px;
    }
    .tab-panel.active { display: flex; }

    /* Dual-Column Remote Layout: Screen on left, Remote on right */
    .remote-grid {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: 16px;
      align-items: start;
    }
    @media (min-width: 900px) {
      .remote-grid {
        grid-template-columns: minmax(0, 1.3fr) 360px;
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
      font-size: 0.78rem;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: var(--muted);
      font-weight: 700;
    }

    /* Buttons */
    .btn {
      background: #1e2430;
      border: 1px solid #2a3344;
      color: var(--ink);
      font-size: 0.84rem;
      font-weight: 600;
      border-radius: 9px;
      padding: 9px 14px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      user-select: none;
      transition: all 0.15s ease;
    }
    .btn:hover { background: #262e3d; }
    .btn:active { transform: scale(0.97); }
    .btn-primary {
      background: var(--accent);
      border-color: var(--accent);
      color: #fff;
    }
    .btn-primary:hover { background: var(--accent-hover); }
    .btn-danger {
      background: rgba(239, 68, 68, 0.18);
      border-color: rgba(239, 68, 68, 0.35);
      color: #fca5a5;
    }
    .btn-danger:hover { background: rgba(239, 68, 68, 0.3); }
    .btn-sm {
      padding: 5px 10px;
      font-size: 0.76rem;
      border-radius: 7px;
    }

    /* Grids */
    .grid-2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
    .grid-3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
    .grid-4 { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
    .grid-6 { display: grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 8px; }

    /* Remote D-Pad */
    .dpad-container {
      position: relative;
      width: 220px;
      height: 220px;
      margin: 4px auto;
      background: #181d26;
      border: 1px solid #242c3b;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      box-shadow: 0 4px 16px rgba(0,0,0,0.4);
    }
    .dpad-btn {
      position: absolute;
      background: transparent;
      border: none;
      color: #cbd5e1;
      font-size: 1.1rem;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      user-select: none;
      transition: all 0.12s;
    }
    .dpad-btn:active, .dpad-btn.active { color: var(--accent); transform: scale(1.15); }
    .dpad-up { top: 12px; left: 50%; transform: translateX(-50%); width: 70px; height: 50px; }
    .dpad-down { bottom: 12px; left: 50%; transform: translateX(-50%); width: 70px; height: 50px; }
    .dpad-left { left: 12px; top: 50%; transform: translateY(-50%); width: 50px; height: 70px; }
    .dpad-right { right: 12px; top: 50%; transform: translateY(-50%); width: 50px; height: 70px; }
    .dpad-center {
      width: 82px;
      height: 82px;
      background: #222938;
      border: 1px solid #323d50;
      border-radius: 50%;
      color: #fff;
      font-weight: 700;
      font-size: 0.95rem;
      z-index: 2;
    }
    .dpad-center:active, .dpad-center.active { background: var(--accent); }

    .cluster {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
    }
    .pill {
      background: #181d26;
      border: 1px solid #242c3b;
      border-radius: 18px;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: space-between;
      padding: 6px;
      height: 96px;
    }
    .pill button {
      background: transparent;
      border: none;
      color: #fff;
      font-size: 1.1rem;
      cursor: pointer;
      width: 100%;
      height: 34px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 12px;
      transition: background 0.15s;
    }
    .pill button:active { background: #242c3b; color: var(--accent); }
    .pill span {
      font-size: 0.72rem;
      color: var(--muted);
      font-weight: 700;
      letter-spacing: 0.05em;
    }

    .color-btn { font-size: 1rem; padding: 6px; }
    .btn-red { color: #f87171; }
    .btn-green { color: #4ade80; }
    .btn-yellow { color: #facc15; }
    .btn-blue { color: #60a5fa; }

    /* ================= VIRTUAL COPIES OF REMOTES ================= */
    .remote-model-view {
      width: 100%;
    }

    /* 1. TCL GOOGLE TV VOICE REMOTE (RC902V) */
    .remote-tcl {
      width: 272px;
      max-width: 100%;
      background:
        linear-gradient(90deg, rgba(255,255,255,0.03) 0%, rgba(255,255,255,0) 25%, rgba(0,0,0,0.2) 75%, rgba(255,255,255,0.04) 100%),
        repeating-linear-gradient(90deg, #2c2f36 0px, #2c2f36 1px, #262930 1px, #262930 2px),
        linear-gradient(180deg, #282b33 0%, #1c1d22 55%, #15161a 100%);
      border: 1px solid rgba(255, 255, 255, 0.12);
      border-radius: 38px;
      box-shadow: 0 20px 48px rgba(0, 0, 0, 0.8), inset 0 1px 1px rgba(255, 255, 255, 0.25), inset 0 -2px 4px rgba(0, 0, 0, 0.6);
      padding: 22px 18px 24px;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 14px;
      user-select: none;
    }
    .tcl-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      width: 100%;
      padding: 0 4px;
    }
    .tcl-btn-round {
      width: 38px;
      height: 38px;
      border-radius: 50%;
      background: #1e2026;
      border: 1px solid rgba(255, 255, 255, 0.09);
      box-shadow: 0 3px 6px rgba(0,0,0,0.45), inset 0 1px 0 rgba(255,255,255,0.1);
      display: flex;
      align-items: center;
      justify-content: center;
      color: #e2e8f0;
      cursor: pointer;
      transition: all 0.12s ease;
      outline: none;
    }
    .tcl-btn-round:active {
      transform: scale(0.92);
      background: #2a2e38;
      color: var(--accent);
    }
    .tcl-btn-power {
      border-color: rgba(239, 68, 68, 0.35);
    }
    .tcl-btn-power:active {
      background: rgba(239, 68, 68, 0.2);
    }
    .tcl-mic-cluster {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 4px;
    }
    .tcl-pinhole {
      width: 4px;
      height: 4px;
      border-radius: 50%;
      background: #0d0e12;
      box-shadow: inset 0 1px 1px rgba(0,0,0,0.9);
    }
    .tcl-led-dots {
      display: flex;
      gap: 3px;
    }
    .tcl-led-dots span {
      width: 3px;
      height: 3px;
      border-radius: 50%;
      background: rgba(16, 185, 129, 0.6);
    }
    .tcl-dpad {
      position: relative;
      width: 144px;
      height: 144px;
      border-radius: 50%;
      background: #17181d;
      border: 1px solid rgba(255,255,255,0.08);
      box-shadow: inset 0 3px 8px rgba(0,0,0,0.65), 0 3px 8px rgba(0,0,0,0.4);
      display: flex;
      align-items: center;
      justify-content: center;
      margin: 2px 0;
    }
    .tcl-dpad-dir {
      position: absolute;
      background: transparent;
      border: none;
      color: #94a3b8;
      font-size: 0.95rem;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: all 0.1s ease;
      outline: none;
    }
    .tcl-dpad-dir:active {
      color: #fff;
      transform: scale(1.2);
    }
    .tcl-dpad-up { top: 8px; left: 50%; transform: translateX(-50%); width: 48px; height: 32px; }
    .tcl-dpad-down { bottom: 8px; left: 50%; transform: translateX(-50%); width: 48px; height: 32px; }
    .tcl-dpad-left { left: 8px; top: 50%; transform: translateY(-50%); width: 32px; height: 48px; }
    .tcl-dpad-right { right: 8px; top: 50%; transform: translateY(-50%); width: 32px; height: 48px; }
    .tcl-dpad-center {
      width: 60px;
      height: 60px;
      border-radius: 50%;
      background: #242730;
      border: 1px solid rgba(255,255,255,0.12);
      box-shadow: 0 4px 10px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.15);
      color: #fff;
      font-weight: 700;
      font-size: 0.88rem;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      z-index: 2;
      transition: all 0.1s ease;
      outline: none;
    }
    .tcl-dpad-center:active {
      background: var(--accent);
      transform: scale(0.94);
    }
    .tcl-btn-assistant {
      width: 42px;
      height: 42px;
      border-color: rgba(255, 255, 255, 0.14);
    }
    .tcl-rockers-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      width: 100%;
      padding: 0 6px;
    }
    .tcl-rocker-wrapper {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 3px;
    }
    .tcl-rocker-label {
      font-size: 0.65rem;
      font-weight: 800;
      color: #64748b;
      letter-spacing: 0.05em;
    }
    .tcl-rocker {
      width: 38px;
      height: 90px;
      border-radius: 19px;
      background: #1b1c21;
      border: 1px solid rgba(255,255,255,0.08);
      box-shadow: 0 3px 8px rgba(0,0,0,0.45), inset 0 1px 0 rgba(255,255,255,0.1);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .tcl-rocker button {
      flex: 1;
      background: transparent;
      border: none;
      color: #cbd5e1;
      font-size: 1.15rem;
      font-weight: 700;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: background 0.12s ease;
      outline: none;
    }
    .tcl-rocker button:active {
      background: #282c36;
      color: var(--accent);
    }
    .tcl-rocker-divider {
      height: 1px;
      background: rgba(255,255,255,0.05);
      margin: 0 6px;
    }
    .tcl-mid-stack {
      display: flex;
      flex-direction: column;
      gap: 12px;
      align-items: center;
    }
    .tcl-numpad-inline {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 6px;
      width: 100%;
      background: #131418;
      border: 1px solid rgba(255,255,255,0.07);
      border-radius: 12px;
      padding: 8px;
      box-shadow: inset 0 2px 6px rgba(0,0,0,0.5);
    }
    .tcl-numpad-btn {
      height: 28px;
      border-radius: 6px;
      background: #20232a;
      border: 1px solid rgba(255,255,255,0.06);
      color: #f1f5f9;
      font-size: 0.82rem;
      font-weight: 600;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      outline: none;
    }
    .tcl-numpad-btn:active {
      background: var(--accent);
      color: #fff;
    }
    .tcl-app-grid {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 8px;
      width: 100%;
    }
    .tcl-app-btn {
      height: 36px;
      border-radius: 18px;
      background: #101115;
      border: 1px solid rgba(255, 255, 255, 0.09);
      box-shadow: 0 3px 6px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.08);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.12s ease;
      outline: none;
      padding: 0 4px;
    }
    .tcl-app-btn:hover {
      border-color: rgba(255, 255, 255, 0.2);
    }
    .tcl-app-btn:active {
      transform: scale(0.93);
      filter: brightness(1.25);
    }
    .tcl-brandmark {
      font-size: 0.82rem;
      font-weight: 800;
      letter-spacing: 0.22em;
      color: #4b5260;
      text-shadow: 0 1px 0 rgba(255,255,255,0.06);
      text-align: center;
      margin-top: 2px;
    }

    /* 2. CLASSIC OEM UNIVERSAL TV REMOTE */
    .remote-classic {
      width: 272px;
      max-width: 100%;
      background: #18191d;
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 26px 26px 48px 48px;
      box-shadow: 0 20px 48px rgba(0, 0, 0, 0.8), inset 0 1px 1px rgba(255, 255, 255, 0.15);
      padding: 20px 18px 30px;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 12px;
      user-select: none;
    }
    .classic-top-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      width: 100%;
      padding: 0 6px;
    }
    .classic-mute-btn {
      width: 36px;
      height: 36px;
      border-radius: 50%;
      background: #282b33;
      border: 1px solid rgba(255,255,255,0.08);
      color: #cbd5e1;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      box-shadow: 0 2px 5px rgba(0,0,0,0.4);
      outline: none;
      transition: all 0.12s;
    }
    .classic-mute-btn:active {
      transform: scale(0.92);
      background: #363b47;
    }
    .classic-power-btn {
      width: 38px;
      height: 28px;
      border-radius: 6px;
      background: #dc2626;
      border: 1px solid #ef4444;
      color: #fff;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      box-shadow: 0 2px 6px rgba(220, 38, 38, 0.4);
      outline: none;
      transition: all 0.12s;
    }
    .classic-power-btn:active {
      transform: scale(0.92);
      filter: brightness(1.2);
    }
    .classic-numpad-grid {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 7px;
      width: 100%;
    }
    .classic-key {
      height: 30px;
      border-radius: 6px;
      background: #252830;
      border: 1px solid rgba(255, 255, 255, 0.06);
      box-shadow: 0 2px 4px rgba(0,0,0,0.35), inset 0 1px 0 rgba(255,255,255,0.08);
      color: #f1f5f9;
      font-size: 0.88rem;
      font-weight: 600;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      outline: none;
      transition: all 0.1s;
    }
    .classic-key:active {
      transform: scale(0.92);
      background: #343a47;
      color: var(--accent);
    }
    .classic-text-key {
      font-size: 0.6rem;
      font-weight: 700;
      letter-spacing: 0.02em;
      color: #94a3b8;
    }
    .classic-oval-row {
      display: flex;
      justify-content: space-between;
      width: 100%;
      padding: 0 12px;
    }
    .classic-oval-btn {
      width: 52px;
      height: 24px;
      border-radius: 12px;
      background: #272a32;
      border: 1px solid rgba(255, 255, 255, 0.06);
      box-shadow: 0 2px 4px rgba(0,0,0,0.3);
      color: #94a3b8;
      font-size: 0.62rem;
      font-weight: 700;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      outline: none;
      transition: all 0.1s;
    }
    .classic-oval-btn:active {
      transform: scale(0.92);
      background: #363b46;
      color: #fff;
    }
    .classic-cross-dpad {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 4px;
      margin: 2px 0;
    }
    .classic-cross-mid {
      display: flex;
      align-items: center;
      gap: 4px;
    }
    .classic-cross-btn {
      width: 36px;
      height: 34px;
      border-radius: 8px;
      background: #262931;
      border: 1px solid rgba(255, 255, 255, 0.07);
      box-shadow: 0 2px 5px rgba(0,0,0,0.35);
      color: #cbd5e1;
      font-size: 0.85rem;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      outline: none;
      transition: all 0.1s;
    }
    .classic-cross-btn:active {
      transform: scale(0.92);
      background: #343946;
      color: var(--accent);
    }
    .classic-cross-ok {
      width: 44px;
      height: 42px;
      border-radius: 10px;
      background: #2d313c;
      border: 1px solid rgba(255, 255, 255, 0.1);
      box-shadow: 0 3px 6px rgba(0,0,0,0.45);
      color: #fff;
      font-size: 0.82rem;
      font-weight: 800;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      outline: none;
      transition: all 0.1s;
    }
    .classic-cross-ok:active {
      background: var(--accent);
      transform: scale(0.92);
    }
    .classic-rockers-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      width: 100%;
      padding: 0 8px;
    }
    .classic-rocker {
      width: 38px;
      height: 84px;
      border-radius: 18px;
      background: #242730;
      border: 1px solid rgba(255,255,255,0.06);
      box-shadow: 0 2px 6px rgba(0,0,0,0.35);
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: space-between;
      padding: 4px;
    }
    .classic-rocker button {
      background: transparent;
      border: none;
      color: #cbd5e1;
      font-size: 1.1rem;
      font-weight: 700;
      cursor: pointer;
      width: 100%;
      height: 28px;
      display: flex;
      align-items: center;
      justify-content: center;
      outline: none;
    }
    .classic-rocker button:active {
      color: var(--accent);
    }
    .classic-rocker-text {
      font-size: 0.6rem;
      font-weight: 800;
      color: #64748b;
      letter-spacing: 0.05em;
    }
    .classic-mid-ovals {
      display: flex;
      flex-direction: column;
      gap: 10px;
      align-items: center;
    }
    .classic-feature-row {
      display: flex;
      justify-content: space-between;
      width: 100%;
      padding: 0 4px;
    }
    .classic-feat-col {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 3px;
    }
    .classic-feat-label {
      font-size: 0.52rem;
      font-weight: 800;
      color: #64748b;
      letter-spacing: 0.02em;
    }
    .classic-round-feat {
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background: #2a2e38;
      border: 1px solid rgba(255, 255, 255, 0.08);
      box-shadow: 0 2px 4px rgba(0,0,0,0.3);
      cursor: pointer;
      outline: none;
      transition: all 0.1s;
    }
    .classic-round-feat:active {
      transform: scale(0.9);
      background: #3e4454;
    }
    .classic-color-row {
      display: flex;
      justify-content: space-between;
      width: 100%;
      padding: 0 4px;
    }
    .classic-color-col {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 3px;
    }
    .classic-color-btn {
      width: 42px;
      height: 18px;
      border-radius: 4px;
      border: none;
      color: #fff;
      font-size: 0.62rem;
      font-weight: 800;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      box-shadow: 0 2px 4px rgba(0,0,0,0.3);
      outline: none;
      transition: all 0.1s;
    }
    .classic-color-btn:active {
      transform: scale(0.92);
      filter: brightness(1.25);
    }
    .classic-f1 { background: #ef4444; }
    .classic-f2 { background: #22c55e; }
    .classic-f3 { background: #eab308; color: #111; }
    .classic-f4 { background: #3b82f6; }
    .classic-color-label {
      font-size: 0.52rem;
      font-weight: 700;
      color: #64748b;
    }

    /* Screen Mirror */
    .screen-container {
      width: 100%;
      aspect-ratio: 16 / 9;
      background: #000;
      border-radius: 10px;
      overflow: hidden;
      border: 1px solid #242c3b;
      position: relative;
      cursor: crosshair;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .screen-img {
      width: 100%;
      height: 100%;
      object-fit: contain;
      display: block;
    }
    .tap-ripple {
      position: absolute;
      width: 32px;
      height: 32px;
      border-radius: 50%;
      border: 2px solid var(--accent);
      background: rgba(47, 109, 246, 0.35);
      transform: translate(-50%, -50%) scale(0.2);
      animation: ripple 0.5s ease-out forwards;
      pointer-events: none;
    }
    @keyframes ripple {
      to { transform: translate(-50%, -50%) scale(1.6); opacity: 0; }
    }

    /* Inputs */
    .text-input {
      background: #181d26;
      border: 1px solid #242c3b;
      color: #fff;
      font-size: 0.85rem;
      border-radius: 8px;
      padding: 9px 12px;
      outline: none;
      width: 100%;
      transition: border-color 0.15s;
    }
    .text-input:focus { border-color: var(--accent); }
    .typing-box {
      display: flex;
      gap: 8px;
    }

    /* Mockup */
    .tv-mockup {
      background: #06080c;
      border: 1px solid #242c3b;
      border-radius: 12px;
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    .mock-top {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
    }
    .mock-greet {
      font-size: 0.72rem;
      color: var(--muted);
      font-weight: 700;
      letter-spacing: 0.05em;
    }
    .mock-clock {
      font-size: 1.8rem;
      font-weight: 300;
      color: #fff;
    }
    .dock-zone {
      background: rgba(255, 255, 255, 0.03);
      border: 1px dashed #242c3b;
      border-radius: 10px;
      padding: 10px;
      min-height: 70px;
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
    }
    .tile {
      background: #1e2633;
      border: 1px solid #323d4e;
      border-radius: 8px;
      padding: 7px 11px;
      font-size: 0.8rem;
      font-weight: 600;
      color: #fff;
      display: flex;
      align-items: center;
      gap: 8px;
      user-select: none;
    }
    .tile-del { font-size: 0.82rem; color: var(--danger); cursor: pointer; padding: 0 4px; }

    /* Lists */
    .list-container {
      display: flex;
      flex-direction: column;
      gap: 8px;
      max-height: 540px;
      overflow-y: auto;
      padding-right: 4px;
    }
    .item-card {
      background: #181d26;
      border: 1px solid #242c3b;
      border-radius: 9px;
      padding: 10px 14px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
    }
    .item-meta {
      display: flex;
      align-items: center;
      gap: 12px;
      min-width: 0;
    }
    .item-icon { font-size: 1.3rem; }
    .item-details {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }
    .item-title {
      font-size: 0.84rem;
      font-weight: 600;
      color: #fff;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .item-sub {
      font-size: 0.72rem;
      color: var(--muted);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .item-actions {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-shrink: 0;
    }

    /* Trakt Media Grid */
    .media-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
      gap: 14px;
    }
    .media-card {
      background: #181d26;
      border: 1px solid #242c3b;
      border-radius: 10px;
      overflow: hidden;
      display: flex;
      flex-direction: column;
      transition: transform 0.15s, border-color 0.15s;
    }
    .media-card:hover {
      transform: translateY(-2px);
      border-color: #3b82f6;
    }
    .poster-wrap {
      width: 100%;
      aspect-ratio: 2 / 3;
      background: #080a0e;
      position: relative;
      overflow: hidden;
    }
    .poster-img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      display: block;
    }
    .media-body {
      padding: 10px;
      display: flex;
      flex-direction: column;
      gap: 6px;
      flex: 1;
    }
    .media-name {
      font-size: 0.82rem;
      font-weight: 600;
      color: #fff;
      line-height: 1.25;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }
    .media-meta-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 0.72rem;
      color: var(--muted);
    }
    .rating-badge {
      color: #facc15;
      font-weight: 600;
    }

    /* Sports Scoreboard Grid */
    .sports-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
      gap: 12px;
    }
    .game-card {
      background: #181d26;
      border: 1px solid #242c3b;
      border-radius: 10px;
      padding: 14px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .game-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 0.74rem;
      color: var(--muted);
    }
    .game-status-badge {
      padding: 2px 7px;
      border-radius: 6px;
      font-weight: 700;
      font-size: 0.68rem;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }
    .status-live { background: rgba(239, 68, 68, 0.2); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.35); }
    .status-final { background: rgba(148, 163, 184, 0.15); color: #94a3b8; }
    .status-pre { background: rgba(59, 130, 246, 0.15); color: #60a5fa; }

    .team-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .team-info {
      display: flex;
      align-items: center;
      gap: 10px;
      min-width: 0;
    }
    .team-logo {
      width: 26px;
      height: 26px;
      object-fit: contain;
    }
    .team-name {
      font-size: 0.86rem;
      font-weight: 600;
      color: #fff;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .team-score {
      font-size: 1.15rem;
      font-weight: 700;
      color: #fff;
    }

    /* Meters */
    .meter-box {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .meter-label {
      display: flex;
      justify-content: space-between;
      font-size: 0.78rem;
      color: #cbd5e1;
    }
    .meter-bar {
      height: 8px;
      background: #181d26;
      border-radius: 999px;
      overflow: hidden;
      border: 1px solid #242c3b;
    }
    .meter-fill {
      height: 100%;
      background: var(--accent);
      border-radius: 999px;
      transition: width 0.3s;
    }
    .meter-fill.warn { background: var(--warning); }
    .meter-fill.bad { background: var(--danger); }

    /* Tables */
    .diag-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.8rem;
    }
    .diag-table tr { border-bottom: 1px solid #1c222e; }
    .diag-table tr:last-child { border-bottom: none; }
    .diag-table td { padding: 9px 4px; }
    .diag-table td:first-child { color: var(--muted); width: 38%; font-weight: 500; }
    .diag-table td:last-child { color: #fff; font-weight: 600; text-align: right; }

    /* Badges */
    .badge {
      font-size: 0.7rem;
      font-weight: 600;
      padding: 3px 8px;
      border-radius: 6px;
    }
    .badge-on { background: rgba(16, 185, 129, 0.15); color: var(--success); }
    .badge-off { background: rgba(59, 130, 246, 0.15); color: #60a5fa; }

    /* Terminal Console */
    .term-box {
      background: #080a0e;
      border: 1px solid #1c222d;
      border-radius: 9px;
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
      padding: 8px 16px;
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
    <div class="brand">Google TV</div>
    <div class="chips">
      <div class="chip" id="chipTv">
        <div class="status-dot"></div>
        <span id="tvHostLabel">Connected</span>
      </div>
      <button class="btn btn-sm btn-danger" onclick="sendKey('POWER')">Power</button>
    </div>
  </header>

  <!-- NAVIGATION TABS & SUBTABS -->
  <div class="nav-container">
    <nav class="main-tabs" role="tablist">
      <button class="main-tab-btn active" data-tab="remote" onclick="switchMainTab('remote')">Remote</button>
      <button class="main-tab-btn" data-tab="launcher" onclick="switchMainTab('launcher')">Launcher</button>
      <button class="main-tab-btn" data-tab="media" onclick="switchMainTab('media')">Media</button>
      <button class="main-tab-btn" data-tab="storage" onclick="switchMainTab('storage')">Storage</button>
      <button class="main-tab-btn" data-tab="system" onclick="switchMainTab('system')">System</button>
    </nav>

    <!-- Subtabs for Remote -->
    <div class="subtabs-bar active" data-parent="remote">
      <button class="subtab-btn active" data-sub="tcl" onclick="switchSubTab('remote', 'tcl')">TCL Voice</button>
      <button class="subtab-btn" data-sub="classic" onclick="switchSubTab('remote', 'classic')">Classic TV</button>
      <button class="subtab-btn" data-sub="compact" onclick="switchSubTab('remote', 'compact')">Compact</button>
    </div>

    <!-- Subtabs for Launcher -->
    <div class="subtabs-bar" data-parent="launcher">
      <button class="subtab-btn active" data-sub="layout" onclick="switchSubTab('launcher', 'layout')">Layout</button>
      <button class="subtab-btn" data-sub="apps" onclick="switchSubTab('launcher', 'apps')">Apps</button>
    </div>

    <!-- Subtabs for Media (Trakt & Sports) -->
    <div class="subtabs-bar" data-parent="media">
      <button class="subtab-btn active" data-sub="trakt" onclick="switchSubTab('media', 'trakt')">Trakt</button>
      <button class="subtab-btn" data-sub="sports" onclick="switchSubTab('media', 'sports')">Sports</button>
    </div>

    <!-- Subtabs for Storage -->
    <div class="subtabs-bar" data-parent="storage">
      <button class="subtab-btn active" data-sub="files" onclick="switchSubTab('storage', 'files')">Files</button>
      <button class="subtab-btn" data-sub="wallpaper" onclick="switchSubTab('storage', 'wallpaper')">Wallpaper</button>
    </div>

    <!-- Subtabs for System -->
    <div class="subtabs-bar" data-parent="system">
      <button class="subtab-btn active" data-sub="debloat" onclick="switchSubTab('system', 'debloat')">Debloat</button>
      <button class="subtab-btn" data-sub="health" onclick="switchSubTab('system', 'health')">Health</button>
      <button class="subtab-btn" data-sub="auto" onclick="switchSubTab('system', 'auto')">Automation</button>
      <button class="subtab-btn" data-sub="setup" onclick="switchSubTab('system', 'setup')">Setup</button>
    </div>
  </div>

  <main>
    <!-- ================= 1. REMOTE ================= -->
    <section class="tab-panel active" id="panel-remote">
      <div class="remote-grid">
        <!-- LEFT COLUMN: LIVE SCREEN & INPUTS -->
        <div style="display:flex; flex-direction:column; gap:14px;">
          <!-- Screen Mirror -->
          <div class="card">
            <div class="card-head">
              <span class="card-title">Screen Mirror</span>
              <div style="display:flex; gap:6px;">
                <button class="btn btn-sm" onclick="refreshScreen()">Refresh</button>
                <button class="btn btn-sm" id="autoBtn" onclick="toggleAutoRefresh()">Auto</button>
                <a class="btn btn-sm" id="dlBtn" href="/api/screenshot" download="screen.png" style="text-decoration:none;">Save</a>
              </div>
            </div>
            <div class="screen-container" id="screenContainer" onclick="handleScreenTap(event)">
              <img id="screenImg" class="screen-img" src="/api/screenshot" alt="TV Screen Mirror">
            </div>
          </div>

          <!-- Type Text -->
          <div class="card">
            <span class="card-title">Type Text</span>
            <div class="typing-box">
              <input type="text" id="typeInput" class="text-input" placeholder="Type text..." onkeydown="if(event.key==='Enter') sendText()">
              <button class="btn btn-primary" onclick="sendText()">Send</button>
            </div>
          </div>

          <!-- Open URL -->
          <div class="card">
            <span class="card-title">Open URL</span>
            <div class="typing-box">
              <input type="text" id="urlInput" class="text-input" placeholder="https://..." onkeydown="if(event.key==='Enter') sendUrl()">
              <button class="btn btn-primary" onclick="sendUrl()">Open</button>
            </div>
          </div>

          <!-- HDMI Source Switcher -->
          <div class="card">
            <span class="card-title">HDMI Inputs</span>
            <div class="grid-3">
              <button class="btn" onclick="launchInput('HDMI 1')">HDMI 1</button>
              <button class="btn" onclick="launchInput('HDMI 2')">HDMI 2</button>
              <button class="btn" onclick="launchInput('AV')">Live TV</button>
            </div>
          </div>
        </div>

        <!-- RIGHT COLUMN: VIRTUAL COPIES OF REMOTES -->
        <div style="display:flex; flex-direction:column; gap:14px; align-items:center;">

          <!-- 1. TCL GOOGLE TV VOICE REMOTE (RC902V) -->
          <div id="remote-view-tcl" class="remote-model-view" style="display:flex; justify-content:center; width:100%;">
            <div class="remote-tcl">
              <!-- Top Row: Mute, Mic Pinhole/LEDs, Power -->
              <div class="tcl-row">
                <button class="tcl-btn-round" data-k="MUTE" title="Mute">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 5L6 9H2v6h4l5 4V5z"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>
                </button>
                <div class="tcl-mic-cluster" title="Microphone">
                  <span class="tcl-pinhole"></span>
                  <div class="tcl-led-dots">
                    <span></span><span></span><span></span>
                  </div>
                </div>
                <button class="tcl-btn-round tcl-btn-power" data-k="POWER" title="Power">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.5"><path d="M18.36 6.64a9 9 0 1 1-12.73 0"/><line x1="12" y1="2" x2="12" y2="12"/></svg>
                </button>
              </div>

              <!-- Row 2: Source / Input, Menu -->
              <div class="tcl-row">
                <button class="tcl-btn-round" data-k="TV_INPUT" title="Source / Input">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="7" width="20" height="14" rx="2"/><path d="M13 14l3-3-3-3"/><path d="M7 11h9"/></svg>
                </button>
                <button class="tcl-btn-round" data-k="MENU" title="Menu">
                  <svg width="18" height="18" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5"><line x1="4" y1="6" x2="20" y2="6"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="18" x2="20" y2="18"/></svg>
                </button>
              </div>

              <!-- Center: Large Circular D-Pad Controller -->
              <div class="tcl-dpad">
                <button class="tcl-dpad-dir tcl-dpad-up" data-k="UP">▲</button>
                <button class="tcl-dpad-dir tcl-dpad-left" data-k="LEFT">◀</button>
                <button class="tcl-dpad-center" data-k="OK">OK</button>
                <button class="tcl-dpad-dir tcl-dpad-right" data-k="RIGHT">▶</button>
                <button class="tcl-dpad-dir tcl-dpad-down" data-k="DOWN">▼</button>
              </div>

              <!-- Row 4: Back, Google Assistant, Home -->
              <div class="tcl-row">
                <button class="tcl-btn-round" data-k="BACK" title="Back">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
                </button>
                <button class="tcl-btn-round tcl-btn-assistant" data-k="ASSIST" title="Google Assistant">
                  <svg width="22" height="22" viewBox="0 0 24 24">
                    <circle cx="6.5" cy="12" r="2.2" fill="#4285F4"/>
                    <circle cx="12" cy="7" r="3" fill="#EA4335"/>
                    <circle cx="17.5" cy="12" r="2.2" fill="#FBBC05"/>
                    <circle cx="12" cy="17" r="2.2" fill="#34A853"/>
                  </svg>
                </button>
                <button class="tcl-btn-round" data-k="HOME" title="Home">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 10.5L12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z"/></svg>
                </button>
              </div>

              <!-- Row 5: Settings, TV Guide -->
              <div class="tcl-row">
                <button class="tcl-btn-round" data-k="SETTINGS" title="Settings">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
                </button>
                <button class="tcl-btn-round" data-k="GUIDE" title="Live TV / Guide">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="7" width="20" height="15" rx="2"/><polyline points="17 2 12 7 7 2"/></svg>
                </button>
              </div>

              <!-- Row 6: Volume, 123/Info, Channel -->
              <div class="tcl-rockers-row">
                <div class="tcl-rocker-wrapper">
                  <span class="tcl-rocker-label">VOL</span>
                  <div class="tcl-rocker">
                    <button data-k="VOL_UP">+</button>
                    <div class="tcl-rocker-divider"></div>
                    <button data-k="VOL_DOWN">−</button>
                  </div>
                </div>

                <div class="tcl-mid-stack">
                  <button class="tcl-btn-round" onclick="toggleTclNumpad()" title="Numeric Pad">
                    <span style="font-size:0.75rem; font-weight:800; letter-spacing:-0.03em;">123</span>
                  </button>
                  <button class="tcl-btn-round" data-k="INFO" title="Info">
                    <span style="font-size:0.85rem; font-weight:700;">ⓘ</span>
                  </button>
                </div>

                <div class="tcl-rocker-wrapper">
                  <span class="tcl-rocker-label">CH</span>
                  <div class="tcl-rocker">
                    <button data-k="CHANNEL_UP">∧</button>
                    <div class="tcl-rocker-divider"></div>
                    <button data-k="CHANNEL_DOWN">∨</button>
                  </div>
                </div>
              </div>

              <!-- Inline Numpad for TCL -->
              <div id="tclNumpadInline" class="tcl-numpad-inline" style="display:none;">
                <button class="tcl-numpad-btn" data-k="NUM_1">1</button>
                <button class="tcl-numpad-btn" data-k="NUM_2">2</button>
                <button class="tcl-numpad-btn" data-k="NUM_3">3</button>
                <button class="tcl-numpad-btn" data-k="NUM_4">4</button>
                <button class="tcl-numpad-btn" data-k="NUM_5">5</button>
                <button class="tcl-numpad-btn" data-k="NUM_6">6</button>
                <button class="tcl-numpad-btn" data-k="NUM_7">7</button>
                <button class="tcl-numpad-btn" data-k="NUM_8">8</button>
                <button class="tcl-numpad-btn" data-k="NUM_9">9</button>
                <button class="tcl-numpad-btn" data-k="BACK" style="font-size:0.7rem;">⌫</button>
                <button class="tcl-numpad-btn" data-k="NUM_0">0</button>
                <button class="tcl-numpad-btn" data-k="OK" style="font-size:0.7rem; color:var(--accent);">OK</button>
              </div>

              <!-- Streaming Apps 2x3 Grid -->
              <div class="tcl-app-grid">
                <button class="tcl-app-btn" onclick="appAction('launch','com.netflix.ninja')" title="Netflix">
                  <span style="color:#e50914; font-weight:900; font-size:0.76rem; letter-spacing:0.04em;">NETFLIX</span>
                </button>
                <button class="tcl-app-btn" onclick="appAction('launch','com.amazon.amazonvideo.livingroom')" title="Prime Video">
                  <span style="color:#fff; font-size:0.68rem; font-weight:700; line-height:1.1;">prime<br><span style="color:#38bdf8; font-size:0.62rem;">video</span></span>
                </button>
                <button class="tcl-app-btn" onclick="appAction('launch','com.google.android.youtube.tv')" title="YouTube">
                  <svg width="28" height="20" viewBox="0 0 28 20" fill="none"><rect width="28" height="20" rx="6" fill="#ef4444"/><polygon points="12,5 20,10 12,15" fill="#fff"/></svg>
                </button>
                <button class="tcl-app-btn" onclick="appAction('launch','com.apple.atve.androidtv.appletv')" title="Apple TV">
                  <span style="color:#fff; font-size:0.72rem; font-weight:700;"> tv</span>
                </button>
                <button class="tcl-app-btn" onclick="appAction('launch','com.tcl.tv.plus')" title="TCL TV+">
                  <span style="color:#fff; font-size:0.72rem; font-weight:800;">TCL<span style="color:#ef4444; font-size:0.65rem;">tv+</span></span>
                </button>
                <button class="tcl-app-btn" onclick="appAction('launch','com.disney.disneyplus')" title="Disney+">
                  <span style="color:#fff; font-size:0.75rem; font-weight:800; font-style:italic;">Disney<span style="color:#38bdf8; font-style:normal;">+</span></span>
                </button>
              </div>

              <!-- TCL Brand Wordmark -->
              <div class="tcl-brandmark">TCL</div>
            </div>
          </div>

          <!-- 2. CLASSIC OEM UNIVERSAL TV REMOTE -->
          <div id="remote-view-classic" class="remote-model-view" style="display:none; justify-content:center; width:100%;">
            <div class="remote-classic">
              <!-- Top Row: Mute, Power -->
              <div class="classic-top-row">
                <button class="classic-mute-btn" data-k="MUTE" title="Mute">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 5L6 9H2v6h4l5 4V5z"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>
                </button>
                <button class="classic-power-btn" data-k="POWER" title="Power">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.8"><path d="M18.36 6.64a9 9 0 1 1-12.73 0"/><line x1="12" y1="2" x2="12" y2="12"/></svg>
                </button>
              </div>

              <!-- 3x4 Numeric Keypad -->
              <div class="classic-numpad-grid">
                <button class="classic-key" data-k="NUM_1">1</button>
                <button class="classic-key" data-k="NUM_2">2</button>
                <button class="classic-key" data-k="NUM_3">3</button>
                <button class="classic-key" data-k="NUM_4">4</button>
                <button class="classic-key" data-k="NUM_5">5</button>
                <button class="classic-key" data-k="NUM_6">6</button>
                <button class="classic-key" data-k="NUM_7">7</button>
                <button class="classic-key" data-k="NUM_8">8</button>
                <button class="classic-key" data-k="NUM_9">9</button>
                <button class="classic-key classic-text-key" data-k="DISPLAY">DISPLAY</button>
                <button class="classic-key" data-k="NUM_0">0</button>
                <button class="classic-key classic-text-key" data-k="RETURN">RETURN</button>
              </div>

              <!-- Control Row 1: SOURCE, MENU -->
              <div class="classic-oval-row">
                <button class="classic-oval-btn" data-k="SOURCE">SOURCE</button>
                <button class="classic-oval-btn" data-k="MENU">MENU</button>
              </div>

              <!-- Diamond / Cross D-Pad -->
              <div class="classic-cross-dpad">
                <button class="classic-cross-btn classic-cross-up" data-k="UP">▲</button>
                <div class="classic-cross-mid">
                  <button class="classic-cross-btn classic-cross-left" data-k="LEFT">◀</button>
                  <button class="classic-cross-ok" data-k="OK">OK</button>
                  <button class="classic-cross-btn classic-cross-right" data-k="RIGHT">▶</button>
                </div>
                <button class="classic-cross-btn classic-cross-down" data-k="DOWN">▼</button>
              </div>

              <!-- Control Row 2: OPTION, EXIT -->
              <div class="classic-oval-row">
                <button class="classic-oval-btn" data-k="OPTION">OPTION</button>
                <button class="classic-oval-btn" data-k="EXIT">EXIT</button>
              </div>

              <!-- Volume, TV/USB, Channel -->
              <div class="classic-rockers-row">
                <div class="classic-rocker">
                  <button data-k="VOL_UP">+</button>
                  <span class="classic-rocker-text">VOL</span>
                  <button data-k="VOL_DOWN">−</button>
                </div>

                <div class="classic-mid-ovals">
                  <button class="classic-oval-btn" data-k="TV">TV</button>
                  <button class="classic-oval-btn" data-k="USB">USB</button>
                </div>

                <div class="classic-rocker">
                  <button data-k="CHANNEL_UP">+</button>
                  <span class="classic-rocker-text">CH</span>
                  <button data-k="CHANNEL_DOWN">−</button>
                </div>
              </div>

              <!-- Feature Row: ZOOM, P.EFFECT, S.EFFECT, SLEEP -->
              <div class="classic-feature-row">
                <div class="classic-feat-col">
                  <span class="classic-feat-label">ZOOM</span>
                  <button class="classic-round-feat" data-k="ZOOM" title="Zoom"></button>
                </div>
                <div class="classic-feat-col">
                  <span class="classic-feat-label">P.EFFECT</span>
                  <button class="classic-round-feat" data-k="PICTURE_MODE" title="Picture Effect"></button>
                </div>
                <div class="classic-feat-col">
                  <span class="classic-feat-label">S.EFFECT</span>
                  <button class="classic-round-feat" data-k="AUDIO_MODE" title="Sound Effect"></button>
                </div>
                <div class="classic-feat-col">
                  <span class="classic-feat-label">SLEEP</span>
                  <button class="classic-round-feat" data-k="SLEEP" title="Sleep Timer"></button>
                </div>
              </div>

              <!-- Color Function Row: F1(Red/MTS), F2(Green/3D), F3(Yellow/Demo), F4(Blue/Demo) -->
              <div class="classic-color-row">
                <div class="classic-color-col">
                  <button class="classic-color-btn classic-f1" data-k="RED">F1</button>
                  <span class="classic-color-label">MTS</span>
                </div>
                <div class="classic-color-col">
                  <button class="classic-color-btn classic-f2" data-k="GREEN">F2</button>
                  <span class="classic-color-label">3D</span>
                </div>
                <div class="classic-color-col">
                  <button class="classic-color-btn classic-f3" data-k="YELLOW">F3</button>
                  <span class="classic-color-label">👁 DEMO</span>
                </div>
                <div class="classic-color-col">
                  <button class="classic-color-btn classic-f4" data-k="BLUE">F4</button>
                  <span class="classic-color-label">DEMO</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 3. COMPACT ERGONOMIC REMOTE -->
          <div id="remote-view-compact" class="remote-model-view" style="display:none; width:100%;">
            <div class="card" style="width:100%;">
              <div class="grid-3">
                <button class="btn btn-danger" onclick="sendKey('POWER')">Power</button>
                <button class="btn" onclick="sendKey('SETTINGS')">Settings</button>
                <button class="btn" onclick="sendKey('MENU')">Menu</button>
              </div>

              <!-- D-Pad with Central OK -->
              <div class="dpad-container">
                <button class="dpad-btn dpad-up" data-k="UP">▲</button>
                <button class="dpad-btn dpad-left" data-k="LEFT">◀</button>
                <button class="dpad-btn dpad-center" data-k="OK">OK</button>
                <button class="dpad-btn dpad-right" data-k="RIGHT">▶</button>
                <button class="dpad-btn dpad-down" data-k="DOWN">▼</button>
              </div>

              <div class="grid-2">
                <button class="btn" onclick="sendKey('BACK')">Back</button>
                <button class="btn" onclick="sendKey('HOME')">Home</button>
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

              <button class="btn" onclick="sendKey('MUTE')">Mute</button>

              <!-- Media Playback -->
              <div class="grid-4">
                <button class="btn" onclick="sendKey('PREV')">⏮</button>
                <button class="btn" onclick="sendKey('REWIND')">⏪</button>
                <button class="btn" onclick="sendKey('PLAY_PAUSE')">⏯</button>
                <button class="btn" onclick="sendKey('FAST_FORWARD')">⏩</button>
              </div>

              <!-- Color Keys -->
              <div class="grid-4">
                <button class="btn color-btn btn-red" onclick="sendKey('RED')">●</button>
                <button class="btn color-btn btn-green" onclick="sendKey('GREEN')">●</button>
                <button class="btn color-btn btn-yellow" onclick="sendKey('YELLOW')">●</button>
                <button class="btn color-btn btn-blue" onclick="sendKey('BLUE')">●</button>
              </div>

              <!-- Numpad Toggle -->
              <div class="card-head">
                <span class="card-title">Numeric Pad</span>
                <span style="font-size:0.75rem; cursor:pointer; color:var(--accent);" onclick="toggleNumpad()">Toggle ▾</span>
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
      </div>
    </section>

    <!-- ================= 2. LAUNCHER -> LAYOUT ================= -->
    <section class="tab-panel" id="panel-launcher-layout">
      <div class="tv-mockup">
        <div class="mock-top">
          <div>
            <div class="mock-greet" id="mockGreetingText">GOOD EVENING, DHRUV</div>
            <div class="mock-clock" id="mockClock">21:00</div>
          </div>
        </div>
        <div>
          <span class="card-title" style="margin-bottom:8px; display:block;">Dock Apps</span>
          <div class="dock-zone" id="dockZone"></div>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <span class="card-title">Appearance & Grid</span>
          <button class="btn btn-primary" onclick="pushLayoutToTv()">Push to TV</button>
        </div>

        <div class="grid-3">
          <div>
            <label style="font-size:0.75rem; color:var(--muted); display:block; margin-bottom:4px;">Greeting Name</label>
            <input type="text" id="cfgGreeting" class="text-input" placeholder="Dhruv" oninput="updateMockup()">
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

      <div class="card">
        <span class="card-title">Available Apps</span>
        <div class="grid-6" id="availableAppsGrid"></div>
      </div>
    </section>

    <!-- ================= 3. LAUNCHER -> APPS ================= -->
    <section class="tab-panel" id="panel-launcher-apps">
      <div class="card">
        <div class="card-head">
          <span class="card-title">Installed Apps</span>
          <div style="display:flex; gap:8px;">
            <input type="text" id="appFilter" class="text-input" style="padding:4px 10px; font-size:0.8rem; width:160px;" placeholder="Filter..." oninput="filterApps()">
            <button class="btn btn-sm" onclick="loadApps()">Refresh</button>
          </div>
        </div>
        <div class="list-container" id="appsList">
          <div style="text-align:center; padding:20px; color:var(--muted);">Loading apps...</div>
        </div>
      </div>

      <div class="card">
        <span class="card-title">Upload & Install APK</span>
        <div class="typing-box">
          <input type="file" id="apkFileInput" class="text-input" accept=".apk" style="padding:6px;">
          <button class="btn btn-primary" onclick="uploadAndInstallApk()">Install APK</button>
        </div>
      </div>
    </section>

    <!-- ================= 4. MEDIA -> TRAKT ================= -->
    <section class="tab-panel" id="panel-media-trakt">
      <div class="card">
        <div class="card-head">
          <span class="card-title">TV Trakt Shelves</span>
          <button class="btn btn-sm btn-primary" onclick="pushTraktToTv()">Push to TV</button>
        </div>
        <div style="display:flex; align-items:center; gap:10px; margin-bottom:12px;">
          <input type="text" id="traktUsernameInput" class="text-input" style="max-width:240px; padding:6px 12px;" value="DhruvG85" placeholder="Trakt Username">
          <button class="btn btn-sm" onclick="saveTraktUser()">Set User</button>
        </div>
        <div style="display:flex; gap:8px; flex-wrap:wrap;" id="traktTvShelves">
          <button class="btn btn-sm active" data-shelf="Watchlist" onclick="toggleTvShelf(this)">Watchlist</button>
          <button class="btn btn-sm active" data-shelf="In Progress" onclick="toggleTvShelf(this)">In Progress</button>
          <button class="btn btn-sm active" data-shelf="Trending Movies" onclick="toggleTvShelf(this)">Trending Movies</button>
          <button class="btn btn-sm active" data-shelf="Trending Shows" onclick="toggleTvShelf(this)">Trending Shows</button>
          <button class="btn btn-sm active" data-shelf="User Lists" onclick="toggleTvShelf(this)">User Lists</button>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <div style="display:flex; align-items:center; gap:10px;">
            <span class="card-title">Trakt Cloud</span>
            <span class="badge badge-on" id="traktUserBadge">DhruvG85</span>
          </div>
          <div style="display:flex; gap:6px;">
            <button class="btn btn-sm active" id="btnShelfWatchlist" onclick="setTraktShelf('watchlist')">Watchlist</button>
            <button class="btn btn-sm" id="btnShelfProgress" onclick="setTraktShelf('inprogress')">In Progress</button>
            <button class="btn btn-sm" id="btnShelfTrendingM" onclick="setTraktShelf('trending_movies')">Trending Movies</button>
            <button class="btn btn-sm" id="btnShelfTrendingS" onclick="setTraktShelf('trending_shows')">Trending Shows</button>
          </div>
        </div>

        <div class="typing-box">
          <input type="text" id="traktSearchInput" class="text-input" placeholder="Search Trakt movies or series..." onkeydown="if(event.key==='Enter') searchTrakt()">
          <button class="btn btn-primary" onclick="searchTrakt()">Search</button>
        </div>

        <div id="traktContainer" class="media-grid">
          <div style="text-align:center; padding:30px; color:var(--muted); grid-column:1/-1;">Loading Trakt items...</div>
        </div>
      </div>
    </section>

    <!-- ================= 5. MEDIA -> SPORTS ================= -->
    <section class="tab-panel" id="panel-media-sports">
      <div class="card">
        <div class="card-head">
          <span class="card-title">TV Sports Leagues</span>
          <button class="btn btn-sm btn-primary" onclick="pushSportsToTv()">Push to TV</button>
        </div>
        <div style="display:flex; gap:8px; flex-wrap:wrap;" id="sportsTvLeagues">
          <button class="btn btn-sm active" data-league="NBA" onclick="toggleTvLeague(this)">NBA</button>
          <button class="btn btn-sm active" data-league="NFL" onclick="toggleTvLeague(this)">NFL</button>
          <button class="btn btn-sm active" data-league="EPL" onclick="toggleTvLeague(this)">EPL</button>
          <button class="btn btn-sm active" data-league="UCL" onclick="toggleTvLeague(this)">UCL</button>
          <button class="btn btn-sm active" data-league="MLB" onclick="toggleTvLeague(this)">MLB</button>
          <button class="btn btn-sm active" data-league="NHL" onclick="toggleTvLeague(this)">NHL</button>
          <button class="btn btn-sm active" data-league="CFB" onclick="toggleTvLeague(this)">CFB</button>
          <button class="btn btn-sm active" data-league="CBB" onclick="toggleTvLeague(this)">CBB</button>
          <button class="btn btn-sm active" data-league="MLS" onclick="toggleTvLeague(this)">MLS</button>
          <button class="btn btn-sm active" data-league="WNBA" onclick="toggleTvLeague(this)">WNBA</button>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <span class="card-title">ESPN Live Scoreboard</span>
          <div style="display:flex; gap:6px; flex-wrap:wrap;" id="sportsLeaguePills">
            <button class="btn btn-sm active" onclick="setSportsLeague('nba')">NBA</button>
            <button class="btn btn-sm" onclick="setSportsLeague('nfl')">NFL</button>
            <button class="btn btn-sm" onclick="setSportsLeague('epl')">EPL</button>
            <button class="btn btn-sm" onclick="setSportsLeague('ucl')">UCL</button>
            <button class="btn btn-sm" onclick="setSportsLeague('mlb')">MLB</button>
            <button class="btn btn-sm" onclick="setSportsLeague('nhl')">NHL</button>
            <button class="btn btn-sm" onclick="setSportsLeague('cfb')">CFB</button>
            <button class="btn btn-sm" onclick="setSportsLeague('cbb')">CBB</button>
            <button class="btn btn-sm" onclick="setSportsLeague('mls')">MLS</button>
            <button class="btn btn-sm" onclick="loadSports()">Refresh</button>
          </div>
        </div>

        <div id="sportsContainer" class="sports-grid">
          <div style="text-align:center; padding:30px; color:var(--muted); grid-column:1/-1;">Loading live scores...</div>
        </div>
      </div>
    </section>

    <!-- ================= 6. STORAGE -> FILES ================= -->
    <section class="tab-panel" id="panel-storage-files">
      <div class="card">
        <div class="card-head">
          <span class="card-title">Storage Explorer</span>
          <div style="display:flex; gap:6px;">
            <button class="btn btn-sm" onclick="loadFiles('/sdcard')">Home</button>
            <button class="btn btn-sm" onclick="loadFiles('/sdcard/Download')">Downloads</button>
            <button class="btn btn-sm" onclick="loadFiles('/sdcard/Movies')">Movies</button>
            <button class="btn btn-sm" onclick="loadFiles('/sdcard/Pictures')">Pictures</button>
            <button class="btn btn-sm" onclick="createFolder()">New Folder</button>
          </div>
        </div>

        <div id="fileCrumbs" style="font-size:0.82rem; color:var(--muted); font-weight:500; padding:2px; word-break:break-all;">/sdcard</div>

        <div class="list-container" id="filesList">
          <div style="text-align:center; padding:20px; color:var(--muted);">Loading files...</div>
        </div>

        <div class="card-head" style="margin-top:6px;">
          <span class="card-title">Upload File to Directory</span>
        </div>
        <div class="typing-box">
          <input type="file" id="generalFileInput" class="text-input" style="padding:6px;">
          <button class="btn btn-primary" onclick="uploadGeneralFile()">Upload</button>
        </div>
      </div>
    </section>

    <!-- ================= 7. STORAGE -> WALLPAPER ================= -->
    <section class="tab-panel" id="panel-storage-wallpaper">
      <div class="card">
        <span class="card-title">Wallpaper</span>
        <div class="typing-box">
          <input type="file" id="wallpaperFileInput" class="text-input" accept="image/*" style="padding:6px;">
          <button class="btn btn-primary" onclick="uploadWallpaper()">Set Wallpaper</button>
        </div>
      </div>
    </section>

    <!-- ================= 8. SYSTEM -> DEBLOAT ================= -->
    <section class="tab-panel" id="panel-system-debloat">
      <div class="card">
        <div class="card-head">
          <span class="card-title">Google TV Debloat</span>
          <select id="presetBrandSelect" class="text-input" style="width:230px; padding:6px 10px;" onchange="loadPresetDebloat()">
            <option value="tcl">TCL Google TV</option>
            <option value="sony">Sony BRAVIA Google TV</option>
            <option value="common">Google TV (Common)</option>
          </select>
        </div>

        <div class="grid-2">
          <button class="btn btn-primary" onclick="applyDebloatPreset()">Apply Debloat</button>
          <button class="btn btn-danger" onclick="restoreAllDebloat()">Restore All</button>
        </div>

        <div class="list-container" id="debloatPresetList">
          <div style="text-align:center; padding:20px; color:var(--muted);">Loading packages...</div>
        </div>
      </div>
    </section>

    <!-- ================= 9. SYSTEM -> HEALTH ================= -->
    <section class="tab-panel" id="panel-system-health">
      <div class="card">
        <div class="card-head">
          <span class="card-title">System Performance</span>
          <button class="btn btn-sm" onclick="loadHealth()">Refresh</button>
        </div>

        <div class="meter-box">
          <div class="meter-label">
            <span>CPU Load</span>
            <b id="cpuMeterVal">Loading...</b>
          </div>
          <div class="meter-bar"><div id="cpuFill" class="meter-fill" style="width: 20%;"></div></div>
        </div>

        <div class="meter-box">
          <div class="meter-label">
            <span>Memory (RAM)</span>
            <b id="ramMeterVal">Loading...</b>
          </div>
          <div class="meter-bar"><div id="ramFill" class="meter-fill" style="width: 50%;"></div></div>
        </div>

        <div class="meter-box">
          <div class="meter-label">
            <span>Storage (/data)</span>
            <b id="storageMeterVal">Loading...</b>
          </div>
          <div class="meter-bar"><div id="storageFill" class="meter-fill" style="width: 25%;"></div></div>
        </div>

        <div>
          <button class="btn btn-sm btn-primary" onclick="freeRam()">Free RAM</button>
        </div>
      </div>

      <div class="card">
        <span class="card-title">Diagnostics</span>
        <table class="diag-table" id="diagTable">
          <tr><td>TV Model</td><td id="diagModel">Loading...</td></tr>
          <tr><td>Manufacturer</td><td id="diagMfg">-</td></tr>
          <tr><td>Android OS</td><td id="diagVersion">-</td></tr>
          <tr><td>Resolution</td><td id="diagRes">-</td></tr>
          <tr><td>Density</td><td id="diagDensity">-</td></tr>
          <tr><td>Wi-Fi Network</td><td id="diagSsid">-</td></tr>
          <tr><td>Wi-Fi Signal</td><td id="diagWifiSignal">-</td></tr>
          <tr><td>Thermal Status</td><td id="diagThermal">-</td></tr>
          <tr><td>Window Focus</td><td id="diagFocus" style="font-size:0.75rem;">-</td></tr>
          <tr><td>Uptime</td><td id="diagUptime">-</td></tr>
        </table>
      </div>
    </section>

    <!-- ================= 10. SYSTEM -> AUTOMATION ================= -->
    <section class="tab-panel" id="panel-system-auto">
      <div class="card">
        <span class="card-title">Sleep Timer</span>
        <div id="timerCountdown" style="font-size:1.8rem; font-weight:700; text-align:center; color:var(--accent); margin:8px 0;">Off</div>
        <div class="grid-3">
          <button class="btn" onclick="setSleepTimer(15)">15m</button>
          <button class="btn" onclick="setSleepTimer(30)">30m</button>
          <button class="btn" onclick="setSleepTimer(45)">45m</button>
          <button class="btn" onclick="setSleepTimer(60)">60m</button>
          <button class="btn" onclick="setSleepTimer(90)">90m</button>
          <button class="btn" onclick="setSleepTimer(120)">120m</button>
        </div>
        <button class="btn btn-danger" onclick="setSleepTimer(0)">Cancel Timer</button>
      </div>

      <div class="card">
        <span class="card-title">Display</span>
        <div class="grid-2">
          <button class="btn" onclick="sendKey('SLEEP')">Screen Off</button>
          <button class="btn" onclick="sendKey('WAKEUP')">Wake Display</button>
        </div>
      </div>
    </section>

    <!-- ================= 11. SYSTEM -> SETUP ================= -->
    <section class="tab-panel" id="panel-system-setup">
      <div class="card">
        <span class="card-title">Animation Scale</span>
        <div class="grid-3">
          <button class="btn" onclick="setAnimationScale(0.0)">Instant (0.0x)</button>
          <button class="btn" onclick="setAnimationScale(0.5)">Snappy (0.5x)</button>
          <button class="btn" onclick="setAnimationScale(1.0)">Stock (1.0x)</button>
        </div>
      </div>

      <div class="card">
        <span class="card-title">Reboot</span>
        <div class="grid-3">
          <button class="btn" onclick="rebootTv('soft')">Soft Reboot</button>
          <button class="btn btn-danger" onclick="rebootTv('full')">Full Reboot</button>
          <button class="btn btn-danger" onclick="rebootTv('recovery')">Recovery</button>
        </div>
      </div>

      <div class="card">
        <span class="card-title">ADB Shell</span>
        <div class="typing-box">
          <input type="text" id="shellInput" class="text-input" placeholder="Command..." onkeydown="if(event.key==='Enter') runShell()">
          <button class="btn btn-primary" onclick="runShell()">Run</button>
        </div>
        <div class="term-box" id="termOutput">Console ready.</div>
      </div>
    </section>
  </main>

  <div id="toast"></div>

  <script>
    let activeMainTab = 'remote';
    let activeSubTabs = {
      remote: 'tcl',
      launcher: 'layout',
      media: 'trakt',
      storage: 'files',
      system: 'debloat'
    };

    let currentPath = '/sdcard';
    let autoInterval = null;
    let sleepSecondsRemaining = 0;
    let sleepTimerInterval = null;
    let installedApps = [];
    let dockApps = [];

    let currentTraktShelf = 'watchlist';
    let currentSportsLeague = 'nba';

    function showToast(msg) {
      const t = document.getElementById('toast');
      t.textContent = msg;
      t.classList.add('show');
      setTimeout(() => t.classList.remove('show'), 2200);
    }

    function switchMainTab(mainTabId) {
      activeMainTab = mainTabId;
      document.querySelectorAll('.main-tab-btn').forEach(b => {
        b.classList.toggle('active', b.dataset.tab === mainTabId);
      });
      document.querySelectorAll('.subtabs-bar').forEach(sb => {
        sb.classList.toggle('active', sb.dataset.parent === mainTabId);
      });

      if (mainTabId === 'remote') {
        document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
        document.getElementById('panel-remote').classList.add('active');
        refreshScreen();
        switchSubTab('remote', activeSubTabs.remote || 'tcl');
      } else {
        switchSubTab(mainTabId, activeSubTabs[mainTabId]);
      }
    }

    function switchSubTab(mainTabId, subTabId) {
      activeSubTabs[mainTabId] = subTabId;
      const subBar = document.querySelector(`.subtabs-bar[data-parent="${mainTabId}"]`);
      if (subBar) {
        subBar.querySelectorAll('.subtab-btn').forEach(b => {
          b.classList.toggle('active', b.dataset.sub === subTabId);
        });
      }

      if (mainTabId === 'remote') {
        document.querySelectorAll('.remote-model-view').forEach(v => v.style.display = 'none');
        const targetView = document.getElementById(`remote-view-${subTabId}`);
        if (targetView) targetView.style.display = 'flex';
        return;
      }

      document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
      const target = document.getElementById(`panel-${mainTabId}-${subTabId}`);
      if (target) target.classList.add('active');

      if (mainTabId === 'launcher' && subTabId === 'layout') loadLayout();
      if (mainTabId === 'launcher' && subTabId === 'apps') loadApps();
      if (mainTabId === 'media' && subTabId === 'trakt') {
        loadTvTraktSettings();
        loadTrakt(currentTraktShelf);
      }
      if (mainTabId === 'media' && subTabId === 'sports') {
        loadTvSportsSettings();
        loadSports(currentSportsLeague);
      }
      if (mainTabId === 'storage' && subTabId === 'files') loadFiles(currentPath);
      if (mainTabId === 'system' && subTabId === 'debloat') loadPresetDebloat();
      if (mainTabId === 'system' && subTabId === 'health') loadHealth();
    }

    function toggleNumpad() {
      const el = document.getElementById('numpadGrid');
      el.style.display = (el.style.display === 'none') ? 'grid' : 'none';
    }

    function toggleTclNumpad() {
      const el = document.getElementById('tclNumpadInline');
      if (el) el.style.display = (el.style.display === 'none') ? 'grid' : 'none';
    }

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

    // D-Pad Pointer Handlers
    document.querySelectorAll('[data-k]').forEach(btn => {
      let timer = null;
      let isLong = false;
      btn.addEventListener('pointerdown', () => {
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
        showToast('Sent');
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
        showToast('Opened URL');
      } catch (e) {
        showToast('Failed to open URL');
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

    // Touch Screen Mirror Click-to-Tap
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
        btn.textContent = 'Auto';
      } else {
        autoInterval = setInterval(refreshScreen, 3000);
        btn.textContent = 'Auto (3s)';
        refreshScreen();
      }
    // TV Trakt & Sports Config
    function toggleTvShelf(btn) {
      btn.classList.toggle('active');
    }

    function toggleTvLeague(btn) {
      btn.classList.toggle('active');
    }

    async function loadTvSportsSettings() {
      try {
        const res = await fetch('/api/sports/settings');
        const data = await res.json();
        if (data.ok && data.enabled) {
          document.querySelectorAll('#sportsTvLeagues button').forEach(b => {
            b.classList.toggle('active', data.enabled.includes(b.dataset.league));
          });
        }
      } catch (e) {}
    }

    async function pushSportsToTv() {
      const enabled = [];
      document.querySelectorAll('#sportsTvLeagues button.active').forEach(b => {
        enabled.push(b.dataset.league);
      });
      showToast('Pushing sports to TV...');
      try {
        const res = await fetch('/api/sports/settings', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ enabled })
        });
        const data = await res.json();
        if (data.ok) showToast('TV sports updated');
        else showToast('Sync failed: ' + (data.output || 'TV offline'));
      } catch (e) {
        showToast('Connection error');
      }
    }

    async function loadTvTraktSettings() {
      try {
        const res = await fetch('/api/trakt/settings');
        const data = await res.json();
        if (data.ok) {
          if (data.username) {
            document.getElementById('traktUsernameInput').value = data.username;
            document.getElementById('traktUserBadge').textContent = data.username;
          }
          if (data.hidden) {
            document.querySelectorAll('#traktTvShelves button').forEach(b => {
              b.classList.toggle('active', !data.hidden.includes(b.dataset.shelf));
            });
          }
        }
      } catch (e) {}
    }

    function saveTraktUser() {
      const u = document.getElementById('traktUsernameInput').value.trim();
      if (!u) return;
      document.getElementById('traktUserBadge').textContent = u;
      loadTrakt(currentTraktShelf);
      pushTraktToTv();
    }

    async function pushTraktToTv() {
      const u = document.getElementById('traktUsernameInput').value.trim() || 'DhruvG85';
      const hidden = [];
      document.querySelectorAll('#traktTvShelves button:not(.active)').forEach(b => {
        hidden.push(b.dataset.shelf);
      });
      showToast('Pushing Trakt shelves to TV...');
      try {
        const res = await fetch('/api/trakt/settings', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username: u, hidden })
        });
        const data = await res.json();
        if (data.ok) showToast('TV Trakt shelves updated');
        else showToast('Sync failed: ' + (data.output || 'TV offline'));
      } catch (e) {
        showToast('Connection error');
      }
    }

    // Trakt Cloud Sync
    function setTraktShelf(shelf) {
      currentTraktShelf = shelf;
      document.querySelectorAll('#panel-media-trakt .card-head .btn').forEach(b => b.classList.remove('active'));
      const activeBtn = {
        'watchlist': 'btnShelfWatchlist',
        'inprogress': 'btnShelfProgress',
        'trending_movies': 'btnShelfTrendingM',
        'trending_shows': 'btnShelfTrendingS'
      }[shelf];
      if (activeBtn) document.getElementById(activeBtn).classList.add('active');
      loadTrakt(shelf);
    }

    async function loadTrakt(shelf = 'watchlist') {
      const container = document.getElementById('traktContainer');
      container.innerHTML = '<div style="text-align:center; padding:30px; color:var(--muted); grid-column:1/-1;">Loading Trakt items...</div>';
      try {
        const res = await fetch('/api/trakt/items?shelf=' + shelf);
        const data = await res.json();
        renderTraktItems(data.items || []);
      } catch (e) {
        container.innerHTML = '<div style="text-align:center; padding:30px; color:var(--danger); grid-column:1/-1;">Failed to load Trakt items</div>';
      }
    }

    async function searchTrakt() {
      const q = document.getElementById('traktSearchInput').value.trim();
      if (!q) return;
      const container = document.getElementById('traktContainer');
      container.innerHTML = '<div style="text-align:center; padding:30px; color:var(--muted); grid-column:1/-1;">Searching Trakt...</div>';
      try {
        const res = await fetch('/api/trakt/search?q=' + encodeURIComponent(q));
        const data = await res.json();
        renderTraktItems(data.items || []);
      } catch (e) {
        container.innerHTML = '<div style="text-align:center; padding:30px; color:var(--danger); grid-column:1/-1;">Search failed</div>';
      }
    }

    function renderTraktItems(items) {
      const container = document.getElementById('traktContainer');
      container.innerHTML = '';
      if (!items.length) {
        container.innerHTML = '<div style="text-align:center; padding:30px; color:var(--muted); grid-column:1/-1;">No items found</div>';
        return;
      }

      items.forEach(it => {
        const card = document.createElement('div');
        card.className = 'media-card';
        const posterUrl = it.poster || `https://via.placeholder.com/300x450/181d26/8c96a5?text=${encodeURIComponent(it.title)}`;
        const typeBadge = it.type === 'movie' ? 'Movie' : 'Series';

        card.innerHTML = `
          <div class="poster-wrap">
            <img class="poster-img" src="${posterUrl}" alt="${it.title}" onerror="this.src='https://via.placeholder.com/300x450/181d26/8c96a5?text=No+Poster'">
          </div>
          <div class="media-body">
            <div class="media-name">${it.title}</div>
            <div class="media-meta-row">
              <span>${it.year || ''} · ${typeBadge}</span>
              <span class="rating-badge">★ ${it.rating || '-'}</span>
            </div>
            <button class="btn btn-sm btn-primary" style="margin-top:4px;" onclick="playTraktOnTv('${it.type}', '${it.imdb}')">
              Play on TV
            </button>
          </div>
        `;
        container.appendChild(card);
      });
    }

    async function playTraktOnTv(type, imdb) {
      if (!imdb) {
        showToast('No IMDB ID found for this title');
        return;
      }
      showToast('Launching on TV via Stremio...');
      try {
        const res = await fetch('/api/trakt/play', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ type, imdb_id: imdb })
        });
        const d = await res.json();
        showToast(d.ok ? 'Streaming on Google TV!' : 'Launch failed: ' + d.output);
      } catch (e) {
        showToast('Play error');
      }
    }

    // ESPN Live Sports
    function setSportsLeague(league) {
      currentSportsLeague = league;
      document.querySelectorAll('#sportsLeaguePills button').forEach(b => {
        b.classList.toggle('active', b.textContent.toLowerCase() === league.toLowerCase());
      });
      loadSports(league);
    }

    async function loadSports(league = currentSportsLeague) {
      const container = document.getElementById('sportsContainer');
      container.innerHTML = '<div style="text-align:center; padding:30px; color:var(--muted); grid-column:1/-1;">Loading live scores...</div>';
      try {
        const res = await fetch('/api/sports/scores?league=' + league);
        const data = await res.json();
        renderSportsGames(data.games || []);
      } catch (e) {
        container.innerHTML = '<div style="text-align:center; padding:30px; color:var(--danger); grid-column:1/-1;">Failed to load live scores</div>';
      }
    }

    function renderSportsGames(games) {
      const container = document.getElementById('sportsContainer');
      container.innerHTML = '';
      if (!games.length) {
        container.innerHTML = '<div style="text-align:center; padding:30px; color:var(--muted); grid-column:1/-1;">No games scheduled today</div>';
        return;
      }

      games.forEach(g => {
        const card = document.createElement('div');
        card.className = 'game-card';
        const isLive = g.state === 'in';
        const isFinal = g.state === 'post';
        const statusClass = isLive ? 'status-live' : isFinal ? 'status-final' : 'status-pre';
        const statusText = isLive ? 'LIVE' : isFinal ? 'FINAL' : 'SCHEDULED';

        card.innerHTML = `
          <div class="game-header">
            <span class="game-status-badge ${statusClass}">${statusText}</span>
            <span>${g.detail}</span>
            <span>${g.network || ''}</span>
          </div>

          <div class="team-row">
            <div class="team-info">
              ${g.away_logo ? `<img class="team-logo" src="${g.away_logo}" alt="${g.away_abbr}">` : ''}
              <span class="team-name">${g.away_name}</span>
            </div>
            <span class="team-score">${g.away_score}</span>
          </div>

          <div class="team-row">
            <div class="team-info">
              ${g.home_logo ? `<img class="team-logo" src="${g.home_logo}" alt="${g.home_abbr}">` : ''}
              <span class="team-name">${g.home_name}</span>
            </div>
            <span class="team-score">${g.home_score}</span>
          </div>

          ${g.odds ? `<div style="font-size:0.72rem; color:var(--muted);">${g.odds}</div>` : ''}

          <button class="btn btn-sm btn-primary" onclick="tuneSportsOnTv('${g.network}')">
            Watch on TV
          </button>
        `;
        container.appendChild(card);
      });
    }

    async function tuneSportsOnTv(network) {
      showToast('Tuning TV to sports broadcast...');
      try {
        await fetch('/api/sports/tune', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ network })
        });
      } catch (e) {
        showToast('Tune error');
      }
    }

    // Layout
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
          <span class="tile-del" onclick="removeDockApp(${index})">✕</span>
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
        showToast('Added');
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
      showToast('Pushing layout to TV...');
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
        showToast(d.ok ? 'Layout applied!' : 'Failed: ' + d.error);
        setTimeout(refreshScreen, 1500);
      } catch (e) {
        showToast('Push layout error');
      }
    }

    // Apps
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
            <button class="btn btn-sm btn-primary" onclick="appAction('launch', '${a.pkg}')">Launch</button>
            <button class="btn btn-sm" onclick="appAction('stop', '${a.pkg}')">Stop</button>
            <button class="btn btn-sm" onclick="appAction('clear', '${a.pkg}')">Clear</button>
            <button class="btn btn-sm btn-danger" onclick="appAction('freeze', '${a.pkg}')">Freeze</button>
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
      if (act === 'freeze' && !confirm('Freeze ' + pkg + '?')) return;
      showToast('Executing ' + act + '...');
      try {
        await fetch('/api/apps/action', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ action: act, pkg })
        });
        showToast('Done');
      } catch (e) {
        showToast('Action failed');
      }
    }

    async function uploadAndInstallApk() {
      const fileInput = document.getElementById('apkFileInput');
      if (!fileInput.files.length) {
        showToast('Select an APK file');
        return;
      }
      const file = fileInput.files[0];
      showToast('Uploading ' + file.name + '...');
      try {
        const res = await fetch('/api/files/upload?dest=/data/local/tmp/' + encodeURIComponent(file.name), {
          method: 'POST',
          body: file
        });
        const d = await res.json();
        if (d.ok) {
          showToast('Installing...');
          const instRes = await fetch('/api/apps/install', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: '/data/local/tmp/' + file.name })
          });
          const instData = await instRes.json();
          showToast(instData.ok ? 'Installed successfully!' : 'Install failed: ' + instData.output);
          loadApps();
        }
      } catch (e) {
        showToast('Upload error');
      }
    }

    // Files
    async function loadFiles(path) {
      currentPath = path;
      document.getElementById('fileCrumbs').textContent = path;
      const list = document.getElementById('filesList');
      list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--muted);">Loading...</div>';
      try {
        const res = await fetch('/api/files?path=' + encodeURIComponent(path));
        const data = await res.json();
        list.innerHTML = '';
        if (!data.items || !data.items.length) {
          list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--muted);">Empty folder</div>';
          return;
        }

        data.items.sort((a,b) => (b.dir ? 1 : 0) - (a.dir ? 1 : 0) || a.name.localeCompare(b.name));

        data.items.forEach(item => {
          const card = document.createElement('div');
          card.className = 'item-card';
          const fullPath = (currentPath.endsWith('/') ? currentPath : currentPath + '/') + item.name;
          const isApk = item.name.endsWith('.apk');
          const isMedia = item.name.match(/\\.(mp4|mkv|mp3|png|jpg|jpeg|webp)$/i);

          let acts = `<button class="btn btn-sm btn-danger" onclick="deleteFile('${fullPath}')">Delete</button>`;
          if (!item.dir) {
            acts = `<a class="btn btn-sm" href="/api/files/download?path=${encodeURIComponent(fullPath)}" download style="text-decoration:none;">Download</a>` + acts;
            if (isApk) acts = `<button class="btn btn-sm btn-primary" onclick="installRemoteApk('${fullPath}')">Install</button>` + acts;
            if (isMedia) acts = `<button class="btn btn-sm" onclick="openFileOnTv('${fullPath}')">Open</button>` + acts;
          }

          card.innerHTML = `
            <div class="item-meta" style="${item.dir ? 'cursor:pointer;' : ''}" onclick="${item.dir ? `loadFiles('${fullPath}')` : ''}">
              <span class="item-icon">${item.dir ? '📁' : isApk ? '📦' : isMedia ? '🎬' : '📄'}</span>
              <div class="item-details">
                <span class="item-title">${item.name}</span>
                <span class="item-sub">${item.dir ? 'Folder' : item.size + ' · ' + item.date}</span>
              </div>
            </div>
            <div class="item-actions">${acts}</div>
          `;
          list.appendChild(card);
        });
      } catch (e) {
        list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--danger);">Failed to load folder</div>';
      }
    }

    async function deleteFile(path) {
      if (!confirm('Delete ' + path + '?')) return;
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
      showToast('Opening...');
      try {
        await fetch('/api/files/open', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ path })
        });
      } catch (e) {
        showToast('Open failed');
      }
    }

    async function installRemoteApk(path) {
      showToast('Installing APK...');
      try {
        const res = await fetch('/api/apps/install', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ path })
        });
        const d = await res.json();
        showToast(d.ok ? 'Installed!' : 'Failed: ' + d.output);
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
        showToast('Uploaded');
        loadFiles(currentPath);
      } catch (e) {
        showToast('Upload failed');
      }
    }

    // Wallpaper
    async function uploadWallpaper() {
      const input = document.getElementById('wallpaperFileInput');
      if (!input.files.length) {
        showToast('Select an image');
        return;
      }
      const file = input.files[0];
      showToast('Uploading wallpaper...');
      try {
        const res = await fetch('/api/wallpaper/upload', {
          method: 'POST',
          body: file
        });
        const d = await res.json();
        showToast(d.ok ? 'Wallpaper updated' : 'Upload failed');
      } catch (e) {
        showToast('Wallpaper error');
      }
    }

    // Debloat
    async function loadPresetDebloat() {
      const brand = document.getElementById('presetBrandSelect').value;
      const list = document.getElementById('debloatPresetList');
      list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--muted);">Checking status...</div>';
      try {
        const res = await fetch('/api/presets?brand=' + brand);
        const data = await res.json();
        list.innerHTML = '';
        data.packages.forEach(p => {
          const div = document.createElement('div');
          div.className = 'item-card';
          const badgeClass = p.disabled ? 'badge-off' : 'badge-on';
          const badgeText = p.disabled ? 'Disabled' : 'Active';

          div.innerHTML = `
            <div class="item-meta">
              <span class="item-icon">${p.disabled ? '❄️' : '📦'}</span>
              <div class="item-details">
                <span class="item-title">${p.name} <small style="color:var(--accent);">(${p.cat})</small></span>
                <span class="item-sub">${p.pkg} · ${p.impact}</span>
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
        list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--danger);">Failed to load packages</div>';
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
      if (!confirm('Apply safe debloat for ' + brand + '?')) return;
      showToast('Applying preset...');
      try {
        const res = await fetch('/api/presets/apply?brand=' + brand, { method: 'POST' });
        const d = await res.json();
        showToast(`Preset applied (${d.disabled_count} packages)`);
        loadPresetDebloat();
      } catch (e) {
        showToast('Debloat error');
      }
    }

    async function restoreAllDebloat() {
      if (!confirm('Re-enable all stock packages?')) return;
      showToast('Restoring packages...');
      try {
        await fetch('/api/debloat/restore', { method: 'POST' });
        showToast('Stock packages restored');
        loadPresetDebloat();
      } catch (e) {
        showToast('Restore error');
      }
    }

    // Health
    async function loadHealth() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();

        document.getElementById('cpuMeterVal').textContent = data.load || 'N/A';
        document.getElementById('ramMeterVal').textContent = `${data.mem_used_pct}% (${data.mem_avail} free)`;
        document.getElementById('ramFill').style.width = data.mem_used_pct + '%';
        if (data.mem_used_pct > 85) document.getElementById('ramFill').className = 'meter-fill bad';
        else if (data.mem_used_pct > 70) document.getElementById('ramFill').className = 'meter-fill warn';
        else document.getElementById('ramFill').className = 'meter-fill';

        document.getElementById('storageMeterVal').textContent = data.storage || 'N/A';
        document.getElementById('storageFill').style.width = (data.storage_pct || 25) + '%';

        document.getElementById('diagModel').textContent = data.model || 'Unknown';
        document.getElementById('diagMfg').textContent = data.manufacturer || 'TCL';
        document.getElementById('diagVersion').textContent = `Android ${data.release} (SDK ${data.sdk})`;
        document.getElementById('diagRes').textContent = data.resolution || '1920x1080';
        document.getElementById('diagDensity').textContent = data.density || '320 dpi';
        document.getElementById('diagSsid').textContent = data.wifi_ssid || 'root';
        document.getElementById('diagWifiSignal').textContent = `${data.wifi_rssi} dBm · ${data.wifi_speed}`;
        document.getElementById('diagThermal').textContent = data.thermal || 'Normal';
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
      showToast('Freeing memory...');
      try {
        await fetch('/api/free', { method: 'POST' });
        showToast('RAM freed');
        loadHealth();
      } catch (e) {
        showToast('Failed to free RAM');
      }
    }

    // Automation: Sleep Timer
    function setSleepTimer(mins) {
      clearInterval(sleepTimerInterval);
      if (mins <= 0) {
        sleepSecondsRemaining = 0;
        document.getElementById('timerCountdown').textContent = 'Off';
        showToast('Timer cancelled');
        return;
      }
      sleepSecondsRemaining = mins * 60;
      showToast(`Sleep timer: ${mins}m`);
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

    // Setup: Animation Speeds
    async function setAnimationScale(scale) {
      showToast(`Setting scale to ${scale}x...`);
      try {
        await fetch('/api/setup/optimize', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ scale })
        });
        showToast(`Scale set to ${scale}x`);
      } catch (e) {
        showToast('Failed to set animations');
      }
    }

    // Setup: Reboot
    async function rebootTv(mode) {
      if (!confirm(`Reboot TV (${mode})?`)) return;
      showToast(`Rebooting (${mode})...`);
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

    // Setup: Shell
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

    // Keyboard Shortcuts
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
        self.sports_enabled = ["NBA", "NFL", "EPL", "UCL", "MLB", "NHL", "CFB", "CBB", "MLS", "WNBA"]
        self.trakt_settings = {
            "username": "DhruvG85",
            "hidden": []
        }

    def push_sports_settings(self, enabled_leagues):
        self.sports_enabled = enabled_leagues
        leagues_str = ",".join(enabled_leagues)
        cmd = f'am start -n com.gathin.tvhome/.MainActivity --es ENABLED_LEAGUES "{leagues_str}"'
        err, out = self.run_tv(cmd)
        return err == 0, out

    def push_trakt_settings(self, username, hidden_shelves):
        self.trakt_settings["username"] = username
        self.trakt_settings["hidden"] = hidden_shelves
        hidden_str = ",".join(hidden_shelves)
        cmd = f'am start -n com.gathin.tvhome/.MainActivity --es TRAKT_USERNAME "{username}" --es HIDDEN_TRAKT_SHELVES "{hidden_str}"'
        err, out = self.run_tv(cmd)
        return err == 0, out

    def get_device(self, timeout=2.0):
        try:
            return tv.PureAdbDevice(self.tv_host, 5555, timeout=timeout).connect()
        except Exception:
            return None

    def run_tv(self, cmd, timeout=30.0):
        with self.lock:
            dev = self.get_device(timeout=2.0)
            if not dev:
                return 1, "TV offline"
            try:
                out = dev.shell(cmd, read_timeout_s=timeout)
                dev.close()
                return 0, out
            except Exception as e:
                try: dev.close()
                except Exception: pass
                return 1, str(e)

    def capture_screenshot(self):
        with self.lock:
            dev = self.get_device(timeout=2.0)
            if not dev:
                return b""
            try:
                dev.shell("screencap -p /data/local/tmp/screen.png")
                dev.pull("/data/local/tmp/screen.png", self.shot_path)
                dev.shell("rm -f /data/local/tmp/screen.png")
                dev.close()
                if os.path.exists(self.shot_path):
                    with open(self.shot_path, "rb") as f:
                        return f.read()
            except Exception as e:
                sys.stderr.write(f"Screenshot error: {e}\n")
                try: dev.close()
                except Exception: pass
        return b""

    def pull_file(self, remote_path, local_path):
        with self.lock:
            dev = self.get_device(timeout=2.0)
            if not dev:
                return False
            try:
                dev.pull(remote_path, local_path)
                dev.close()
                return os.path.exists(local_path)
            except Exception:
                try: dev.close()
                except Exception: pass
                return False

    def push_file(self, local_path, remote_path):
        with self.lock:
            dev = self.get_device(timeout=2.0)
            if not dev:
                return False
            try:
                dev.push(local_path, remote_path)
                dev.close()
                return True
            except Exception:
                try: dev.close()
                except Exception: pass
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

    def get_trakt_items(self, shelf="watchlist"):
        endpoint_map = {
            "watchlist": f"users/{TRAKT_USER}/watchlist?extended=full",
            "inprogress": f"users/{TRAKT_USER}/lists/in-progress/items?extended=full",
            "trending_movies": "movies/trending?extended=full&limit=16",
            "trending_shows": "shows/trending?extended=full&limit=16"
        }
        endpoint = endpoint_map.get(shelf, endpoint_map["watchlist"])
        url = f"https://api.trakt.tv/{endpoint}"
        req = urllib.request.Request(url, headers={
            "Content-Type": "application/json",
            "trakt-api-version": "2",
            "trakt-api-key": TRAKT_CLIENT_ID,
            "User-Agent": "GoogleTV-Launcher/1.0"
        })
        items = []
        try:
            with urllib.request.urlopen(req, timeout=7.0) as resp:
                raw = json.loads(resp.read().decode("utf-8"))
                for it in raw:
                    m = it.get("movie") or it.get("show") or it
                    if not isinstance(m, dict) or not m.get("title"):
                        continue
                    mtype = "movie" if ("movie" in it or it.get("type") == "movie") else "series"
                    imdb = m.get("ids", {}).get("imdb", "")
                    poster = f"https://images.metahub.space/poster/medium/{imdb}/img" if imdb else ""
                    items.append({
                        "title": m.get("title"),
                        "year": m.get("year", ""),
                        "type": mtype,
                        "imdb": imdb,
                        "rating": round(m.get("rating", 0), 1),
                        "poster": poster
                    })
        except Exception as e:
            sys.stderr.write(f"Trakt fetch error: {e}\n")
        return items

    def search_trakt(self, query):
        if not query:
            return []
        url = f"https://api.trakt.tv/search/movie,show?query={urllib.parse.quote(query)}&extended=full&limit=16"
        req = urllib.request.Request(url, headers={
            "Content-Type": "application/json",
            "trakt-api-version": "2",
            "trakt-api-key": TRAKT_CLIENT_ID,
            "User-Agent": "GoogleTV-Launcher/1.0"
        })
        items = []
        try:
            with urllib.request.urlopen(req, timeout=7.0) as resp:
                raw = json.loads(resp.read().decode("utf-8"))
                for it in raw:
                    m = it.get("movie") or it.get("show")
                    if not m:
                        continue
                    mtype = "movie" if "movie" in it else "series"
                    imdb = m.get("ids", {}).get("imdb", "")
                    poster = f"https://images.metahub.space/poster/medium/{imdb}/img" if imdb else ""
                    items.append({
                        "title": m.get("title"),
                        "year": m.get("year", ""),
                        "type": mtype,
                        "imdb": imdb,
                        "rating": round(m.get("rating", 0), 1),
                        "poster": poster
                    })
        except Exception as e:
            sys.stderr.write(f"Trakt search error: {e}\n")
        return items

    def get_sports_scores(self, league="nba"):
        info = ESPN_LEAGUES.get(league.lower(), ESPN_LEAGUES["nba"])
        url = info[1]
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        games = []
        try:
            with urllib.request.urlopen(req, timeout=7.0) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                for ev in data.get("events", []):
                    comp = ev.get("competitions", [{}])[0]
                    comps = comp.get("competitors", [])
                    if len(comps) >= 2:
                        home = comps[0]
                        away = comps[1]
                        status_type = comp.get("status", {}).get("type", {})
                        state = status_type.get("state", "pre")
                        detail = status_type.get("detail", status_type.get("description", ""))
                        broadcasts = comp.get("broadcasts", [])
                        network = broadcasts[0].get("names", [""])[0] if broadcasts and broadcasts[0].get("names") else ""
                        odds = comp.get("odds", [{}])[0].get("details", "")
                        games.append({
                            "id": ev.get("id"),
                            "state": state,
                            "detail": detail,
                            "network": network,
                            "odds": odds,
                            "away_name": away.get("team", {}).get("displayName", ""),
                            "away_abbr": away.get("team", {}).get("abbreviation", ""),
                            "away_score": away.get("score", "-"),
                            "away_logo": away.get("team", {}).get("logo", ""),
                            "home_name": home.get("team", {}).get("displayName", ""),
                            "home_abbr": home.get("team", {}).get("abbreviation", ""),
                            "home_score": home.get("score", "-"),
                            "home_logo": home.get("team", {}).get("logo", ""),
                        })
        except Exception as e:
            sys.stderr.write(f"ESPN fetch error: {e}\n")
        return games


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

                storage_str = "N/A"
                storage_pct = 25
                if df.strip():
                    parts = df.split()
                    if len(parts) >= 5:
                        storage_str = f"{parts[2]} used / {parts[1]} ({parts[4]})"
                        try: storage_pct = int(parts[4].replace("%", ""))
                        except Exception: pass

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
                    "thermal": "Normal",
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

            if path == "/api/trakt/items":
                shelf = query.get("shelf", ["watchlist"])[0]
                items = server_ctx.get_trakt_items(shelf)
                return self._send(200, json.dumps({"ok": True, "shelf": shelf, "items": items}).encode("utf-8"))

            if path == "/api/trakt/search":
                q = query.get("q", [""])[0]
                items = server_ctx.search_trakt(q)
                return self._send(200, json.dumps({"ok": True, "query": q, "items": items}).encode("utf-8"))

            if path == "/api/sports/scores":
                league = query.get("league", ["nba"])[0]
                games = server_ctx.get_sports_scores(league)
                return self._send(200, json.dumps({"ok": True, "league": league, "games": games}).encode("utf-8"))

            if path == "/api/sports/settings":
                return self._send(200, json.dumps({"ok": True, "enabled": server_ctx.sports_enabled}).encode("utf-8"))

            if path == "/api/trakt/settings":
                return self._send(200, json.dumps({
                    "ok": True,
                    "username": server_ctx.trakt_settings["username"],
                    "hidden": server_ctx.trakt_settings["hidden"]
                }).encode("utf-8"))

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

            body_bytes = self.rfile.read(content_len) if content_len > 0 else b"{}"
            try:
                data = json.loads(body_bytes.decode("utf-8"))
            except Exception:
                data = {}

            if path == "/api/trakt/play":
                imdb_id = data.get("imdb_id", "").strip()
                mtype = data.get("type", "movie").strip()
                if not imdb_id:
                    return self._send(400, b'{"error":"Missing imdb_id"}')
                cmd = f"am start -a android.intent.action.VIEW -d 'stremio:///detail/{mtype}/{imdb_id}'"
                err, out = server_ctx.run_tv(cmd)
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/sports/tune":
                network = data.get("network", "").lower()
                if "espn" in network:
                    cmd = "monkey -p com.espn.score_center -c android.intent.category.LAUNCHER 1 2>/dev/null || input keyevent KEYCODE_TV_INPUT_HDMI_1"
                else:
                    cmd = "input keyevent KEYCODE_TV_INPUT_HDMI_1"
                err, out = server_ctx.run_tv(cmd)
                return self._send(200 if err == 0 else 500, json.dumps({"ok": err == 0, "output": out}).encode("utf-8"))

            if path == "/api/sports/settings":
                enabled = data.get("enabled", [])
                ok, out = server_ctx.push_sports_settings(enabled)
                return self._send(200 if ok else 500, json.dumps({"ok": ok, "output": out}).encode("utf-8"))

            if path == "/api/trakt/settings":
                username = data.get("username", "DhruvG85")
                hidden = data.get("hidden", [])
                ok, out = server_ctx.push_trakt_settings(username, hidden)
                return self._send(200 if ok else 500, json.dumps({"ok": ok, "output": out}).encode("utf-8"))

            if path == "/api/key":
                key_name = data.get("key", "").upper()
                is_long = bool(data.get("long", False))
                code = KEYCODES.get(key_name)
                if code is None:
                    return self._send(400, b'{"error":"Unknown key"}')
                if key_name in ("ASSIST", "VOICE"):
                    cmd = "input keyevent 231 || input keyevent 84 || am start -n com.google.android.katniss/com.google.android.katniss.search.SearchActivity"
                elif key_name == "TV":
                    cmd = "input keyevent 170 || input keyevent KEYCODE_TV_INPUT_HDMI_1"
                elif key_name == "USB":
                    cmd = "input keyevent 255 || monkey -p com.google.android.videos -c android.intent.category.LAUNCHER 1 2>/dev/null || monkey -p com.android.gallery3d -c android.intent.category.LAUNCHER 1 2>/dev/null"
                elif key_name == "ZOOM":
                    cmd = "input keyevent 268 || input keyevent 168"
                elif key_name == "PICTURE_MODE":
                    cmd = "input keyevent 256 || input keyevent 176"
                elif key_name == "AUDIO_MODE":
                    cmd = "input keyevent 257 || input keyevent 176"
                else:
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
    parser = argparse.ArgumentParser(description="Google TV Web Remote & Controller")
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
    print(f"Google TV Controller Active")
    print(f"   Target TV:   {args.tv}")
    print(f"   Local URL:   http://localhost:{args.port}")
    print(f"   Network URL: http://{local_ip}:{args.port}")
    print(f"=======================================================\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down controller.")
        server.server_close()


if __name__ == "__main__":
    main()
