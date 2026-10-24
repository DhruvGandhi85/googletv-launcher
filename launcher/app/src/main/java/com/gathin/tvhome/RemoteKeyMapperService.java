package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

/**
 * System-wide hardware remote button remapper.
 * Intercepts TCL TV button (KEYCODE_TV), Guide, color buttons, and custom mapped keys
 * to immediately launch Sky TV (IPTV), Stremio, or other chosen apps.
 */
public class RemoteKeyMapperService extends AccessibilityService {

    public static final String PREFS_REMAP = "tvhome_button_remapper";
    public static final String KEY_PREFIX = "remap_key_";

    public static final String ACTION_HOME = "ACTION_HOME";
    public static final String ACTION_INPUTS = "ACTION_INPUTS";
    public static final String ACTION_SETTINGS = "ACTION_SETTINGS";
    public static final String ACTION_COLOR_RED = "ACTION_COLOR_RED";
    public static final String ACTION_COLOR_GREEN = "ACTION_COLOR_GREEN";
    public static final String ACTION_COLOR_YELLOW = "ACTION_COLOR_YELLOW";
    public static final String ACTION_COLOR_BLUE = "ACTION_COLOR_BLUE";
    public static final String ACTION_PIP_SWAP = "ACTION_PIP_SWAP";

    public interface KeyCaptureListener {
        void onKeyCaptured(int keyCode, String keyName);
    }

    private static volatile KeyCaptureListener activeCaptureListener = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void setKeyCaptureListener(KeyCaptureListener listener) {
        activeCaptureListener = listener;
    }

    public static String getMapping(Context ctx, int keyCode) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_REMAP, MODE_PRIVATE);
        return sp.getString(KEY_PREFIX + keyCode, null);
    }

    public static void setMapping(Context ctx, int keyCode, String target) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_REMAP, MODE_PRIVATE);
        sp.edit().putString(KEY_PREFIX + keyCode, target).apply();
    }

    public static void clearMapping(Context ctx, int keyCode) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_REMAP, MODE_PRIVATE);
        sp.edit().remove(KEY_PREFIX + keyCode).apply();
    }

    public static Map<Integer, String> getAllConfiguredMappings(Context ctx) {
        Map<Integer, String> out = new HashMap<>();
        String tvMapping = getMapping(ctx, KeyEvent.KEYCODE_TV);
        if (tvMapping != null) out.put(KeyEvent.KEYCODE_TV, tvMapping);

        SharedPreferences sp = ctx.getSharedPreferences(PREFS_REMAP, MODE_PRIVATE);
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith(KEY_PREFIX)) {
                try {
                    int code = Integer.parseInt(k.substring(KEY_PREFIX.length()));
                    String target = sp.getString(k, null);
                    if (target != null && !target.isEmpty()) {
                        out.put(code, target);
                    }
                } catch (Exception ignored) {}
            }
        }
        return out;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        int keyCode = event.getKeyCode();

        // Do not intercept or remap D-Pad navigation, system keys, game controller, or media playback buttons
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_HOME ||
            keyCode == KeyEvent.KEYCODE_SETTINGS ||
            keyCode == 268 ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_STOP ||
            keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
            keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
            keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
            keyCode == KeyEvent.KEYCODE_MEDIA_RECORD ||
            keyCode == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK ||
            keyCode == KeyEvent.KEYCODE_CAPTIONS) {
            return false;
        }

        // Fix hardware TV Inputs button on remote so it opens Inputs menu from any app or HDMI
        if (keyCode == KeyEvent.KEYCODE_TV_INPUT || keyCode == 178) {
            launchTarget(ACTION_INPUTS);
            return true;
        }

        // Support the "123" virtual remote button (WINDOW 171, INFO 165, or 78)
        if (keyCode == KeyEvent.KEYCODE_WINDOW || keyCode == 171 || keyCode == 78) {
            String mapped = getMapping(this, keyCode);
            if (mapped != null && !mapped.isEmpty() && !"NONE".equals(mapped)) {
                launchTarget(mapped);
                return true;
            }
            if (com.gathin.tvhome.pip.PipOverlayService.isRunning) {
                com.gathin.tvhome.pip.PipOverlayService.swapInputs(this);
                return true;
            }
        }

        // Do not intercept native Color Buttons so they always pass to IPTV
        if (keyCode == KeyEvent.KEYCODE_PROG_RED || keyCode == 183 ||
            keyCode == KeyEvent.KEYCODE_PROG_GREEN || keyCode == 184 ||
            keyCode == KeyEvent.KEYCODE_PROG_YELLOW || keyCode == 185 ||
            keyCode == KeyEvent.KEYCODE_PROG_BLUE || keyCode == 186) {
            SharedPreferences sp = getSharedPreferences(PREFS_REMAP, MODE_PRIVATE);
            String saved = sp.getString(KEY_PREFIX + keyCode, null);
            if (saved == null || saved.isEmpty()) {
                return false; // Always pass through to IPTV!
            }
        }

        // 1. Live capture mode when user is learning/testing a remote button in the UI
        KeyCaptureListener listener = activeCaptureListener;
        if (listener != null) {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                final int capturedCode = keyCode;
                final String capturedName = friendlyKeyName(capturedCode);
                mainHandler.post(() -> {
                    if (activeCaptureListener != null) {
                        activeCaptureListener.onKeyCaptured(capturedCode, capturedName);
                    }
                });
                return true; // Consume so the TV doesn't navigate away while capturing
            }
            return false;
        }

        // 2. Check for configured mapping
        String target = getMapping(this, keyCode);
        if (target != null && !target.isEmpty() && !"NONE".equals(target)) {
            launchTarget(target);
            return true; // Completely suppress default TV action!
        }

        return super.onKeyEvent(event);
    }

    private void launchTarget(String target) {
        if (ACTION_COLOR_RED.equals(target)) {
            LocalAdbClient.injectKeyEvent(KeyEvent.KEYCODE_PROG_RED);
            return;
        }
        if (ACTION_COLOR_GREEN.equals(target)) {
            LocalAdbClient.injectKeyEvent(KeyEvent.KEYCODE_PROG_GREEN);
            return;
        }
        if (ACTION_COLOR_YELLOW.equals(target)) {
            LocalAdbClient.injectKeyEvent(KeyEvent.KEYCODE_PROG_YELLOW);
            return;
        }
        if (ACTION_COLOR_BLUE.equals(target)) {
            LocalAdbClient.injectKeyEvent(KeyEvent.KEYCODE_PROG_BLUE);
            return;
        }
        if (ACTION_PIP_SWAP.equals(target)) {
            com.gathin.tvhome.pip.PipOverlayService.swapInputs(this);
            return;
        }
        if (target.startsWith("KEY:")) {
            try {
                int code = Integer.parseInt(target.substring(4));
                LocalAdbClient.injectKeyEvent(code);
            } catch (Exception ignored) {}
            return;
        }

        if (ACTION_HOME.equals(target)) {
            MainActivity inst = MainActivity.activeInstance;
            if (inst != null && !inst.isFinishing()) {
                inst.runOnUiThread(inst::goHome);
                return;
            }
            Intent home = new Intent(this, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(home);
            return;
        }

        if (ACTION_INPUTS.equals(target)) {
            try {
                Intent inputs = new Intent("com.android.tv.action.VIEW_INPUTS");
                inputs.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(inputs);
            } catch (Exception e1) {
                try {
                    Intent inputs = new Intent("android.settings.TV_INPUT_SETTINGS");
                    inputs.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(inputs);
                } catch (Exception ignored) {}
            }
            return;
        }

        if (ACTION_SETTINGS.equals(target)) {
            try {
                Intent settings = new Intent(android.provider.Settings.ACTION_SETTINGS);
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(settings);
            } catch (Exception ignored) {}
            return;
        }

        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(target);
        if (i == null) i = pm.getLaunchIntentForPackage(target);
        if (i == null && ("com.tcl.channelplus".equals(target) || "com.tcl.tv".equals(target))) {
            i = pm.getLeanbackLaunchIntentForPackage("com.IPTVOutlet.STC");
            if (i == null) i = pm.getLaunchIntentForPackage("com.IPTVOutlet.STC");
        }
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, "Cannot open " + target, Toast.LENGTH_SHORT).show());
            }
        } else {
            mainHandler.post(() -> Toast.makeText(this, "App not found: " + target, Toast.LENGTH_SHORT).show());
        }
    }

    public static String friendlyKeyName(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_INFO: return "Info Button (INFO)";
            case KeyEvent.KEYCODE_WINDOW: return "Window / Info Button (WINDOW)";
            case KeyEvent.KEYCODE_TV: return "TCL TV / Live TV (TV)";
            case KeyEvent.KEYCODE_GUIDE: return "Guide / EPG (GUIDE)";
            case KeyEvent.KEYCODE_PROG_RED: return "Red Button (PROG_RED)";
            case KeyEvent.KEYCODE_PROG_GREEN: return "Green Button (PROG_GREEN)";
            case KeyEvent.KEYCODE_PROG_YELLOW: return "Yellow Button (PROG_YELLOW)";
            case KeyEvent.KEYCODE_PROG_BLUE: return "Blue Button (PROG_BLUE)";
            case KeyEvent.KEYCODE_TV_INPUT: return "TV Input Source (TV_INPUT)";
            case KeyEvent.KEYCODE_BOOKMARK: return "Bookmark Button";
            case KeyEvent.KEYCODE_CAPTIONS: return "Closed Captions (CC)";
            case KeyEvent.KEYCODE_SETTINGS: return "Settings Button (⚙)";
            case 268: return "Quick Settings Button";
            case KeyEvent.KEYCODE_MENU: return "Menu Button (☰)";
            case KeyEvent.KEYCODE_BUTTON_4:
            case 308:
            case 304: return "Disney+ Button";
            case KeyEvent.KEYCODE_BUTTON_3:
            case 307:
            case 303: return "Apple TV Button";
            case KeyEvent.KEYCODE_BUTTON_2:
            case 215:
            case 306:
            case 302: return "YouTube Button";
            case KeyEvent.KEYCODE_BUTTON_1:
            case 305:
            case 301:
            case 656: return "TCL Channel Button";
            case 207: return "Netflix Button";
            case KeyEvent.KEYCODE_LAST_CHANNEL: return "Last Channel (Recall)";
            case KeyEvent.KEYCODE_CHANNEL_UP: return "Channel Up";
            case KeyEvent.KEYCODE_CHANNEL_DOWN: return "Channel Down";
            default:
                String name = KeyEvent.keyCodeToString(keyCode);
                if (name.startsWith("KEYCODE_")) {
                    name = name.substring("KEYCODE_".length());
                }
                return name + " (" + keyCode + ")";
        }
    }
}
