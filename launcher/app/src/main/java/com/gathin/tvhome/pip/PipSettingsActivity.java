package com.gathin.tvhome.pip;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
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
 * Dedicated TV App & Input Settings for HDMI DualView & Picture-in-Picture.
 * Allows pairing any two HDMI inputs (e.g. PlayStation 5 on HDMI 2 + Fire TV Stick on HDMI 3),
 * configuring floating PiP placement, and activating the seamless one-click swap session.
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
        availableInputs.add(new HdmiOption(
                "com.tcl.tvinput/.TvPassThroughService/HW15",
                "HDMI 1",
                "Standard HDMI Port",
                "🔌"
        ));
        availableInputs.add(new HdmiOption(
                "com.tcl.tvinput/.TvPassThroughService/HDMI200004",
                "HDMI 2 • PlayStation 5",
                "eARC / 4K 120Hz Gaming Console",
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
    }

    private void loadSavedPreferences() {
        SharedPreferences sp = getSharedPreferences(PipOverlayService.PREFS_PIP, MODE_PRIVATE);
        String inA = sp.getString(PipOverlayService.KEY_INPUT_A, PipOverlayService.DEFAULT_INPUT_A);
        String inB = sp.getString(PipOverlayService.KEY_INPUT_B, PipOverlayService.DEFAULT_INPUT_B);

        for (int i = 0; i < availableInputs.size(); i++) {
            if (availableInputs.get(i).id.equals(inA)) selectedIndexA = i;
            if (availableInputs.get(i).id.equals(inB)) selectedIndexB = i;
        }
        if (selectedIndexB == selectedIndexA) {
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

        // Header Title
        TextView pill = new TextView(this);
        pill.setText("⚡ TV INPUT SETTING • PICTURE-IN-PICTURE");
        pill.setTextColor(0xFF38BDF8);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pill.setLetterSpacing(0.06f);
        container.addView(pill);

        TextView title = new TextView(this);
        title.setText("HDMI DualView & PiP");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(4), 0, dp(4));
        container.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Pair any 2 HDMI inputs together. Enjoy your primary game or console full-screen with real-time PiP overlay and instant 1-click remote swapping.");
        sub.setTextColor(0xFF94A3B8);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, 0, 0, dp(24));
        container.addView(sub);

        // Setting Card 1: Primary Input A
        inputAValueView = createSettingRow(container, "Primary Input (Display A)",
                availableInputs.get(selectedIndexA).icon + "  " + availableInputs.get(selectedIndexA).name,
                "Press OK to cycle HDMI port for Primary Screen",
                () -> {
                    selectedIndexA = (selectedIndexA + 1) % availableInputs.size();
                    if (selectedIndexA == selectedIndexB) selectedIndexA = (selectedIndexA + 1) % availableInputs.size();
                    updateLabels();
                    savePreferences();
                });

        // Setting Card 2: Secondary Input B
        inputBValueView = createSettingRow(container, "Secondary Input (Display B / PiP)",
                availableInputs.get(selectedIndexB).icon + "  " + availableInputs.get(selectedIndexB).name,
                "Press OK to cycle HDMI port for PiP & Quick-Swap target",
                () -> {
                    selectedIndexB = (selectedIndexB + 1) % availableInputs.size();
                    if (selectedIndexB == selectedIndexA) selectedIndexB = (selectedIndexB + 1) % availableInputs.size();
                    updateLabels();
                    savePreferences();
                });

        // Setting Card 3: PiP Position
        cornerValueView = createSettingRow(container, "PiP Corner Position",
                getCornerName(selectedCorner),
                "Location of the floating overlay card",
                () -> {
                    selectedCorner = (selectedCorner + 1) % 4;
                    updateLabels();
                    savePreferences();
                });

        // Setting Card 4: Remote Hotkey
        hotkeyValueView = createSettingRow(container, "Instant Swap Remote Button",
                getHotkeyName(selectedHotkey),
                "Remote button to swap inputs back and forth instantly while playing",
                () -> {
                    selectedHotkey = (selectedHotkey + 1) % 3;
                    updateLabels();
                    assignRemoteHotkey();
                });

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

    private TextView createSettingRow(ViewGroup parent, String title, String value, String hint, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x331E293B);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0x22FFFFFF);
        row.setBackground(bg);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textCol, textLp);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextColor(0xFFE2E8F0);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textCol.addView(titleTv);

        TextView hintTv = new TextView(this);
        hintTv.setText(hint);
        hintTv.setTextColor(0xFF64748B);
        hintTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        textCol.addView(hintTv);

        TextView valueTv = new TextView(this);
        valueTv.setText(value);
        valueTv.setTextColor(0xFF38BDF8);
        valueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        valueTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        valueTv.setGravity(Gravity.END);
        row.addView(valueTv);

        row.setOnFocusChangeListener((v, hasFocus) -> {
            GradientDrawable d = (GradientDrawable) v.getBackground();
            if (hasFocus) {
                d.setColor(0x5538BDF8);
                d.setStroke(dp(2), 0xFFFFFFFF);
            } else {
                d.setColor(0x331E293B);
                d.setStroke(dp(1), 0x22FFFFFF);
            }
        });

        row.setOnClickListener(v -> onClick.run());
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

        // 3. Switch TV directly to Input A
        try {
            Uri uri = TvContract.buildChannelsUriForInput(optA.id);
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Opening " + optA.name + "...", Toast.LENGTH_SHORT).show();
        }

        finish();
    }

    private int dp(float dpVal) {
        return (int) (dpVal * getResources().getDisplayMetrics().density + 0.5f);
    }
}
