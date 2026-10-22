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
        String saved = sp.getString(KEY_PREFIX + keyCode, null);
        if (saved != null) return saved;

        // Factory defaults for TCL RC923A & TLC-925 Remotes:
        if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == 268) {
            return ACTION_SETTINGS;
        }
        if (keyCode == KeyEvent.KEYCODE_TV_INPUT || keyCode == 178) {
            return ACTION_INPUTS;
        }
        if (keyCode == KeyEvent.KEYCODE_TV || keyCode == 170) {
            return "com.IPTVOutlet.STC";
        }
        if (keyCode == KeyEvent.KEYCODE_GUIDE || keyCode == 172) {
            return "com.IPTVOutlet.STC";
        }

        // Remap dedicated App Buttons on Remote to Color Inputs for IPTV (View, Sort, Favs, Move):
        // 1. Disney+ button -> 🔴 Red (KEYCODE_PROG_RED 183 / View in IPTV)
        if (keyCode == KeyEvent.KEYCODE_BUTTON_4 || keyCode == 308 || keyCode == 304 || keyCode == 259) {
            return ACTION_COLOR_RED;
        }
        // 2. Apple TV button -> 🟢 Green (KEYCODE_PROG_GREEN 184 / Sort in IPTV)
        if (keyCode == KeyEvent.KEYCODE_BUTTON_3 || keyCode == 307 || keyCode == 303 || keyCode == 258) {
            return ACTION_COLOR_GREEN;
        }
        // 3. YouTube button -> 🟡 Yellow (KEYCODE_PROG_YELLOW 185 / Favorites in IPTV)
        if (keyCode == 215 || keyCode == KeyEvent.KEYCODE_BUTTON_2 || keyCode == 306 || keyCode == 302 || keyCode == 257) {
            return ACTION_COLOR_YELLOW;
        }
        // 4. TCL Channel button -> 🔵 Blue (KEYCODE_PROG_BLUE 186 / Move in IPTV)
        if (keyCode == 305 || keyCode == 656 || keyCode == KeyEvent.KEYCODE_BUTTON_1 || keyCode == 301 || keyCode == 256) {
            return ACTION_COLOR_BLUE;
        }
        if (keyCode == 207) {
            return "com.netflix.ninja";
        }

        // Native Color Buttons (183, 184, 185, 186): DO NOT INTERCEPT!
        // Return null so they pass directly to IPTV.
        return null;
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

        // Do not intercept or remap D-Pad navigation keys
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER) {
            return false;
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
            MainActivity inst = MainActivity.activeInstance;
            if (inst != null && !inst.isFinishing()) {
                inst.runOnUiThread(inst::toggleInputsPanel);
                return;
            }
            Intent inputs = new Intent(this, MainActivity.class);
            inputs.putExtra("SHOW_INPUTS", true);
            inputs.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(inputs);
            return;
        }

        if (ACTION_SETTINGS.equals(target)) {
            MainActivity inst = MainActivity.activeInstance;
            if (inst != null && !inst.isFinishing()) {
                inst.runOnUiThread(inst::toggleSettingsModal);
                return;
            }
            Intent settings = new Intent(this, MainActivity.class);
            settings.putExtra("SHOW_SETTINGS", true);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(settings);
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
