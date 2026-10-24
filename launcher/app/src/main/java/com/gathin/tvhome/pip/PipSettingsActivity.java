package com.gathin.tvhome.pip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gathin.tvhome.R;
import com.gathin.tvhome.RemoteKeyMapperService;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated TV App & Input Settings for HDMI & Google TV App DualView.
 * Supports pairing 1 Google TV App + 1 HDMI, 2 Google TV Apps, or 2 HDMI ports,
 * configuring floating PiP placement, and activating instant remote swapping.
 */
public class PipSettingsActivity extends Activity {

    public static class HdmiOption {
        public final String id;
        public final String name;
        public final String sub;
        public final String icon;

        public HdmiOption(String id, String name, String sub, String icon) {
            this.id = id;
            this.name = name;
            this.sub = sub;
            this.icon = icon;
        }
    }

    private final List<HdmiOption> availableInputs = new ArrayList<>();
    private int selectedIndexA = 1; // Default: HDMI 2 (PS5)
    private int selectedIndexB = 2; // Default: HDMI 3 (Fire TV)
    private int selectedCorner = 0; // 0=Top-Right, 1=Bottom-Right, 2=Bottom-Left, 3=Top-Left
    private int selectedHotkey = 0; // 0=Yellow Button, 1=Info Button, 2=Blue Button

    private TextView inputAValueView;
    private TextView inputBValueView;
    private TextView cornerValueView;
    private TextView hotkeyValueView;
    private TextView statusOverlayToggleView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initAvailableInputs();
        loadSavedPreferences();
        buildUI();
    }

    private void initAvailableInputs() {
        availableInputs.clear();

        // 1. Hardware HDMI Inputs
        availableInputs.add(new HdmiOption(
                "com.tcl.tvinput/.TvPassThroughService/HW15",
                "HDMI 1",
                "Standard HDMI Port",
                "🔌"
        ));
        availableInputs.add(new HdmiOption(
                "com.tcl.tvinput/.TvPassThroughService/HDMI200004",
                "HDMI 2 • PlayStation 5",
                "4K 120Hz Gaming Console",
                "🎮"
        ));
        availableInputs.add(new HdmiOption(
                "com.tcl.tvinput/.TvPassThroughService/HDMI300008",
                "HDMI 3 • Fire TV Stick",
                "Streaming Media Player",
                "📺"
        ));
        availableInputs.add(new HdmiOption(
                "com.tcl.tvinput/.TvPassThroughService/HW18",
                "HDMI 4",
                "Auxiliary HDMI Port",
                "🔌"
        ));

        // 2. Installed Google TV Apps
        PackageManager pm = getPackageManager();
        Intent leanbackIntent = new Intent(Intent.ACTION_MAIN, null);
        leanbackIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> activities = pm.queryIntentActivities(leanbackIntent, 0);

        if (activities != null) {
            for (ResolveInfo ri : activities) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) ||
                    pkg.equals("com.google.android.apps.tv.launcherx") ||
                    pkg.equals("com.android.tv.settings") ||
                    pkg.equals("com.tcl.suspension") ||
                    pkg.equals("com.tcl.dashboard")) {
                    continue;
                }
                CharSequence labelSeq = ri.loadLabel(pm);
                String label = labelSeq != null ? labelSeq.toString() : pkg;
                String icon = getAppIconEmoji(pkg, label);
                availableInputs.add(new HdmiOption(
                        "pkg:" + pkg,
                        label,
                        "Google TV App",
                        icon
                ));
            }
        }
    }

    private String getAppIconEmoji(String pkg, String label) {
        String p = pkg.toLowerCase();
        String l = label.toLowerCase();
        if (p.contains("stremio") || l.contains("stremio")) return "🎬";
        if (p.contains("amazon") || l.contains("prime")) return "🎬";
        if (p.contains("youtube") || l.contains("youtube")) return "▶️";
        if (p.contains("netflix") || l.contains("netflix")) return "🍿";
        if (p.contains("spotify") || l.contains("spotify")) return "🎵";
        if (p.contains("iptv") || p.contains("stc") || l.contains("iptv") || l.contains("sky")) return "📡";
        if (p.contains("disney") || l.contains("disney")) return "✨";
        if (p.contains("apple") || l.contains("apple")) return "🍎";
        if (p.contains("peacock") || l.contains("peacock")) return "🦚";
        if (p.contains("paramount") || p.contains("cbs") || l.contains("paramount")) return "⛰️";
        if (p.contains("hulu") || l.contains("hulu")) return "🟢";
        if (p.contains("stream") || p.contains("beam") || l.contains("max")) return "🍿";
        if (p.contains("trakt") || l.contains("trakt")) return "📊";
        if (p.contains("nba") || l.contains("nba")) return "🏀";
        if (p.contains("nfl") || l.contains("nfl")) return "🏈";
        if (p.contains("downloader")) return "⬇️";
        return "📱";
    }

    private void loadSavedPreferences() {
        SharedPreferences sp = getSharedPreferences(PipOverlayService.PREFS_PIP, MODE_PRIVATE);
        String inA = sp.getString(PipOverlayService.KEY_INPUT_A, PipOverlayService.DEFAULT_INPUT_A);
        String inB = sp.getString(PipOverlayService.KEY_INPUT_B, PipOverlayService.DEFAULT_INPUT_B);

        boolean foundA = false;
        boolean foundB = false;
        for (int i = 0; i < availableInputs.size(); i++) {
            if (availableInputs.get(i).id.equals(inA)) {
                selectedIndexA = i;
                foundA = true;
            }
            if (availableInputs.get(i).id.equals(inB)) {
                selectedIndexB = i;
                foundB = true;
            }
        }
        if (!foundA && !availableInputs.isEmpty()) {
            selectedIndexA = 1 < availableInputs.size() ? 1 : 0;
        }
        if (!foundB && availableInputs.size() > 1) {
            selectedIndexB = (selectedIndexA + 1) % availableInputs.size();
        }
        if (selectedIndexB == selectedIndexA && availableInputs.size() > 1) {
            selectedIndexB = (selectedIndexA + 1) % availableInputs.size();
        }
        selectedCorner = sp.getInt(PipOverlayService.KEY_CORNER, 0);
    }

    private void savePreferences() {
        HdmiOption optA = availableInputs.get(selectedIndexA);
        HdmiOption optB = availableInputs.get(selectedIndexB);

        SharedPreferences sp = getSharedPreferences(PipOverlayService.PREFS_PIP, MODE_PRIVATE);
        sp.edit()
                .putString(PipOverlayService.KEY_INPUT_A, optA.id)
                .putString(PipOverlayService.KEY_INPUT_A_NAME, optA.name)
                .putString(PipOverlayService.KEY_INPUT_B, optB.id)
                .putString(PipOverlayService.KEY_INPUT_B_NAME, optB.name)
                .putInt(PipOverlayService.KEY_CORNER, selectedCorner)
                .apply();
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0F1D);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(48), dp(36), dp(48), dp(36));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(container, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("DualView");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setPadding(0, 0, 0, dp(18));
        container.addView(title);

        // Setting Card 1: Primary Input A
        inputAValueView = createSettingRow(container, "Primary Input",
                availableInputs.get(selectedIndexA).icon + "  " + availableInputs.get(selectedIndexA).name,
                () -> cycleInput(0, false),
                () -> cycleInput(0, true),
                () -> showSourcePickerDialog(0));

        // Setting Card 2: Secondary Input B
        inputBValueView = createSettingRow(container, "Secondary Input",
                availableInputs.get(selectedIndexB).icon + "  " + availableInputs.get(selectedIndexB).name,
                () -> cycleInput(1, false),
                () -> cycleInput(1, true),
                () -> showSourcePickerDialog(1));

        // Setting Card 3: PiP Position
        cornerValueView = createSettingRow(container, "Position",
                getCornerName(selectedCorner),
                () -> cycleCorner(false),
                () -> cycleCorner(true),
                () -> cycleCorner(true));

        // Setting Card 4: Remote Hotkey
        hotkeyValueView = createSettingRow(container, "Swap Key",
                getHotkeyName(selectedHotkey),
                () -> cycleHotkey(false),
                () -> cycleHotkey(true),
                () -> cycleHotkey(true));

        // Action Buttons Section
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(20), 0, dp(20));
        container.addView(actionRow);

        // Button 1: Start Session
        View btnStart = createActionButton("▶  Start DualView Session", 0xFF0284C7, this::startDualViewSession);
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(dp(280), dp(48));
        startLp.rightMargin = dp(16);
        actionRow.addView(btnStart, startLp);

        // Button 2: Toggle Overlay Only
        statusOverlayToggleView = (TextView) createActionButton(
                PipOverlayService.isRunning ? "⏹  Stop PiP Overlay" : "⚡  Turn On Floating PiP",
                0xFF334155,
                this::toggleOverlayService
        );
        actionRow.addView(statusOverlayToggleView, new LinearLayout.LayoutParams(dp(220), dp(48)));

        // Button 3: Close
        View btnBack = createActionButton("Back to Home", 0xFF1E293B, this::finish);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(160), dp(48));
        backLp.leftMargin = dp(16);
        actionRow.addView(btnBack, backLp);

        btnStart.requestFocus();
        setContentView(root);
    }

    private void cycleInput(int slot, boolean forward) {
        if (availableInputs.isEmpty()) return;
        int delta = forward ? 1 : -1;
        if (slot == 0) {
            selectedIndexA = (selectedIndexA + delta + availableInputs.size()) % availableInputs.size();
            if (selectedIndexA == selectedIndexB && availableInputs.size() > 1) {
                selectedIndexA = (selectedIndexA + delta + availableInputs.size()) % availableInputs.size();
            }
        } else {
            selectedIndexB = (selectedIndexB + delta + availableInputs.size()) % availableInputs.size();
            if (selectedIndexB == selectedIndexA && availableInputs.size() > 1) {
                selectedIndexB = (selectedIndexB + delta + availableInputs.size()) % availableInputs.size();
            }
        }
        updateLabels();
        savePreferences();
    }

    private void cycleCorner(boolean forward) {
        int delta = forward ? 1 : 3;
        selectedCorner = (selectedCorner + delta) % 4;
        updateLabels();
        savePreferences();
    }

    private void cycleHotkey(boolean forward) {
        int delta = forward ? 1 : 2;
        selectedHotkey = (selectedHotkey + delta) % 3;
        updateLabels();
        assignRemoteHotkey();
    }

    private void showSourcePickerDialog(int slot) {
        if (availableInputs.isEmpty()) return;
        String[] items = new String[availableInputs.size()];
        for (int i = 0; i < availableInputs.size(); i++) {
            HdmiOption opt = availableInputs.get(i);
            items[i] = opt.icon + "  " + opt.name;
        }
        int current = slot == 0 ? selectedIndexA : selectedIndexB;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(slot == 0 ? "Select Primary Display" : "Select Secondary Display")
                .setSingleChoiceItems(items, current, (dialog, which) -> {
                    if (slot == 0) {
                        selectedIndexA = which;
                        if (selectedIndexA == selectedIndexB && availableInputs.size() > 1) {
                            selectedIndexB = (selectedIndexA + 1) % availableInputs.size();
                        }
                    } else {
                        selectedIndexB = which;
                        if (selectedIndexB == selectedIndexA && availableInputs.size() > 1) {
                            selectedIndexA = (selectedIndexB + 1) % availableInputs.size();
                        }
                    }
                    updateLabels();
                    savePreferences();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView createSettingRow(ViewGroup parent, String title, String value,
                                      Runnable onPrev, Runnable onNext, Runnable onSelect) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x18FFFFFF);
        bg.setCornerRadius(dp(8));
        row.setBackground(bg);
        row.setPadding(dp(18), dp(12), dp(18), dp(12));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleTv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        row.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueTv = new TextView(this);
        valueTv.setText(value);
        valueTv.setTextColor(0xFF38BDF8);
        valueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        valueTv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        row.addView(valueTv);

        row.setOnFocusChangeListener((v, hasFocus) -> {
            GradientDrawable d = (GradientDrawable) v.getBackground();
            if (hasFocus) {
                d.setColor(0x240284C7);
                d.setStroke(dp(2), 0xFF38BDF8);
            } else {
                d.setColor(0x10FFFFFF);
                d.setStroke(0, 0);
            }
        });

        row.setOnClickListener(v -> {
            if (onSelect != null) onSelect.run();
        });

        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && onPrev != null) {
                onPrev.run();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && onNext != null) {
                onNext.run();
                return true;
            } else if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && onSelect != null) {
                onSelect.run();
                return true;
            }
            return false;
        });

        parent.addView(row);
        return valueTv;
    }

    private View createActionButton(String label, int color, Runnable onClick) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setFocusable(true);
        btn.setClickable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        btn.setBackground(bg);

        btn.setOnFocusChangeListener((v, hasFocus) -> {
            GradientDrawable d = (GradientDrawable) v.getBackground();
            if (hasFocus) {
                d.setStroke(dp(2.5f), 0xFFFFFFFF);
            } else {
                d.setStroke(0, 0);
            }
        });

        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private void updateLabels() {
        if (inputAValueView != null) {
            inputAValueView.setText(availableInputs.get(selectedIndexA).icon + "  " + availableInputs.get(selectedIndexA).name);
        }
        if (inputBValueView != null) {
            inputBValueView.setText(availableInputs.get(selectedIndexB).icon + "  " + availableInputs.get(selectedIndexB).name);
        }
        if (cornerValueView != null) {
            cornerValueView.setText(getCornerName(selectedCorner));
        }
        if (hotkeyValueView != null) {
            hotkeyValueView.setText(getHotkeyName(selectedHotkey));
        }
        if (statusOverlayToggleView != null) {
            statusOverlayToggleView.setText(PipOverlayService.isRunning ? "⏹  Stop PiP Overlay" : "⚡  Turn On Floating PiP");
        }
    }

    private String getCornerName(int corner) {
        switch (corner % 4) {
            case 0: return "Top-Right (Default)";
            case 1: return "Bottom-Right";
            case 2: return "Bottom-Left";
            case 3: return "Top-Left";
            default: return "Top-Right";
        }
    }

    private String getHotkeyName(int hotkey) {
        switch (hotkey % 3) {
            case 0: return "🟡 Yellow Remote Button";
            case 1: return "ℹ Info / Window Button";
            case 2: return "🔵 Blue Remote Button";
            default: return "🟡 Yellow Remote Button";
        }
    }

    private void assignRemoteHotkey() {
        int keyCode = KeyEvent.KEYCODE_PROG_YELLOW;
        if (selectedHotkey == 1) keyCode = KeyEvent.KEYCODE_INFO;
        else if (selectedHotkey == 2) keyCode = KeyEvent.KEYCODE_PROG_BLUE;

        RemoteKeyMapperService.setMapping(this, keyCode, "ACTION_PIP_SWAP");
        Toast.makeText(this, "Remote swap button set to " + getHotkeyName(selectedHotkey), Toast.LENGTH_SHORT).show();
    }

    private void toggleOverlayService() {
        if (PipOverlayService.isRunning) {
            PipOverlayService.stop(this);
            Toast.makeText(this, "PiP Overlay stopped", Toast.LENGTH_SHORT).show();
        } else {
            savePreferences();
            PipOverlayService.start(this);
            Toast.makeText(this, "PiP Overlay active", Toast.LENGTH_SHORT).show();
        }
        updateLabels();
    }

    private void startDualViewSession() {
        savePreferences();
        assignRemoteHotkey();

        // 1. Start PiP Overlay
        PipOverlayService.start(this);

        // 2. Set current active input to Input A
        HdmiOption optA = availableInputs.get(selectedIndexA);
        getSharedPreferences(PipOverlayService.PREFS_PIP, MODE_PRIVATE).edit()
                .putString(PipOverlayService.KEY_CURRENT_ACTIVE, optA.id)
                .apply();

        // 3. Switch TV directly to Input A (App or HDMI port)
        PipOverlayService.launchInputSource(this, optA.id, optA.name);

        finish();
    }

    private int dp(float dpVal) {
        return (int) (dpVal * getResources().getDisplayMetrics().density + 0.5f);
    }
}
