package com.gathin.tvhome.settings;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gathin.tvhome.HubDataFetcher;
import com.gathin.tvhome.RemoteKeyMapperService;
import com.gathin.tvhome.pip.PipOverlayService;
import com.gathin.tvhome.pip.PipSettingsActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Settings app for Clean TV Launcher.
 * Designed with Jakub Krehel's "Less is more" principles:
 * No eyebrows, no gratuitous descriptive text, minimal cognitive load.
 */
public class LauncherSettingsActivity extends Activity {

    public static final String PREFS_SETTINGS = "tvhome_launcher_settings";
    public static final String KEY_STARTUP_TAB = "startup_tab";
    public static final String KEY_HERO_BACKDROP = "hero_backdrop_enabled";
    public static final String KEY_MARGIN_MODE = "margin_mode";
    public static final String KEY_SHOW_SPORTS_SHELF = "show_sports_shelf";

    private static final String[] CATEGORIES = {
            "DualView",
            "Remote",
            "Layout",
            "Sports",
            "Trakt",
            "Memory",
            "System"
    };

    private int activeCategoryIndex = 0;
    private final List<TextView> categoryTabViews = new ArrayList<>();
    private LinearLayout rightContentContainer;
    private ScrollView rightScrollView;
    private TextView categoryHeaderTitle;
    private TextView lastPressedKeyLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF070B12);

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(mainLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top Header
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(36), dp(28), dp(36), dp(16));
        mainLayout.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView appTitle = new TextView(this);
        appTitle.setText("Settings");
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        appTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        topBar.addView(appTitle);

        // Subtle divider
        View topDivider = new View(this);
        topDivider.setBackgroundColor(0x12FFFFFF);
        mainLayout.addView(topDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Body: Left Rail + Right Content
        LinearLayout bodyPanes = new LinearLayout(this);
        bodyPanes.setOrientation(LinearLayout.HORIZONTAL);
        mainLayout.addView(bodyPanes, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // Left Navigation Rail
        ScrollView leftRailScroll = new ScrollView(this);
        leftRailScroll.setClipToPadding(false);
        leftRailScroll.setPadding(dp(32), dp(20), dp(16), dp(20));
        bodyPanes.addView(leftRailScroll, new LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout leftRail = new LinearLayout(this);
        leftRail.setOrientation(LinearLayout.VERTICAL);
        leftRailScroll.addView(leftRail, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        categoryTabViews.clear();
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int index = i;
            TextView tab = new TextView(this);
            tab.setText(CATEGORIES[i]);
            tab.setTextColor(i == 0 ? Color.WHITE : 0xFF8E9BAE);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tab.setPadding(dp(16), dp(11), dp(16), dp(11));
            tab.setFocusable(true);
            tab.setFocusableInTouchMode(true);

            updateTabDrawable(tab, i == activeCategoryIndex, false);

            tab.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    activeCategoryIndex = index;
                    refreshCategoryTabs();
                    loadCategoryContent(index);
                }
                updateTabDrawable(tab, index == activeCategoryIndex, hasFocus);
            });

            tab.setOnClickListener(v -> {
                activeCategoryIndex = index;
                refreshCategoryTabs();
                loadCategoryContent(index);
                focusFirstRightChild();
            });

            tab.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    focusFirstRightChild();
                    return true;
                }
                return false;
            });

            LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tabLp.bottomMargin = dp(4);
            leftRail.addView(tab, tabLp);
            categoryTabViews.add(tab);
        }

        View verticalDivider = new View(this);
        verticalDivider.setBackgroundColor(0x12FFFFFF);
        bodyPanes.addView(verticalDivider, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        // Right Content Pane
        LinearLayout rightPane = new LinearLayout(this);
        rightPane.setOrientation(LinearLayout.VERTICAL);
        rightPane.setPadding(dp(36), dp(20), dp(44), dp(20));
        bodyPanes.addView(rightPane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));

        categoryHeaderTitle = new TextView(this);
        categoryHeaderTitle.setTextColor(Color.WHITE);
        categoryHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        categoryHeaderTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        categoryHeaderTitle.setPadding(0, 0, 0, dp(16));
        rightPane.addView(categoryHeaderTitle);

        rightScrollView = new ScrollView(this);
        rightScrollView.setClipToPadding(false);
        rightPane.addView(rightScrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rightContentContainer = new LinearLayout(this);
        rightContentContainer.setOrientation(LinearLayout.VERTICAL);
        rightScrollView.addView(rightContentContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        refreshCategoryTabs();
        loadCategoryContent(0);
        if (!categoryTabViews.isEmpty()) {
            categoryTabViews.get(0).requestFocus();
        }
    }

    private void updateTabDrawable(TextView tab, boolean isActive, boolean isFocused) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(8));
        if (isFocused) {
            gd.setColor(0xFF0284C7);
            tab.setTextColor(Color.WHITE);
        } else if (isActive) {
            gd.setColor(0x1F38BDF8);
            tab.setTextColor(0xFF38BDF8);
        } else {
            gd.setColor(Color.TRANSPARENT);
            tab.setTextColor(0xFF8E9BAE);
        }
        tab.setBackground(gd);
    }

    private void refreshCategoryTabs() {
        for (int i = 0; i < categoryTabViews.size(); i++) {
            updateTabDrawable(categoryTabViews.get(i), i == activeCategoryIndex, categoryTabViews.get(i).isFocused());
        }
    }

    private void focusFirstRightChild() {
        if (rightContentContainer != null && rightContentContainer.getChildCount() > 0) {
            for (int i = 0; i < rightContentContainer.getChildCount(); i++) {
                View child = rightContentContainer.getChildAt(i);
                if (child.isFocusable()) {
                    child.requestFocus();
                    return;
                }
            }
        }
    }

    private void loadCategoryContent(int index) {
        categoryHeaderTitle.setText(CATEGORIES[index]);
        rightContentContainer.removeAllViews();

        switch (index) {
            case 0:
                buildDualViewContent();
                break;
            case 1:
                buildRemoteContent();
                break;
            case 2:
                buildLayoutContent();
                break;
            case 3:
                buildSportsContent();
                break;
            case 4:
                buildTraktContent();
                break;
            case 5:
                buildMemoryContent();
                break;
            case 6:
                buildSystemContent();
                break;
        }
    }

    // 0. DUALVIEW
    private void buildDualViewContent() {
        SharedPreferences sp = getSharedPreferences(PipOverlayService.PREFS_PIP, MODE_PRIVATE);
        String inAName = sp.getString(PipOverlayService.KEY_INPUT_A_NAME, "HDMI 2");
        String inBName = sp.getString(PipOverlayService.KEY_INPUT_B_NAME, "HDMI 3");
        int corner = sp.getInt(PipOverlayService.KEY_CORNER, 0);
        String cornerText = corner == 0 ? "Top-Right" : corner == 1 ? "Bottom-Right" : corner == 2 ? "Bottom-Left" : "Top-Left";

        createRow("Active Inputs", inAName + " ⮂ " + inBName, () -> {
            startActivity(new Intent(this, PipSettingsActivity.class));
        });

        createRow("Overlay Position", cornerText, () -> {
            int next = (corner + 1) % 4;
            sp.edit().putInt(PipOverlayService.KEY_CORNER, next).apply();
            loadCategoryContent(0);
        });

        createRow("Swap Key", "'123' Remote Key", () -> {});

        boolean isRunning = PipOverlayService.isRunning;
        createRow("Overlay Session", isRunning ? "Active" : "Stopped", () -> {
            if (PipOverlayService.isRunning) {
                stopService(new Intent(this, PipOverlayService.class));
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    return;
                }
                startService(new Intent(this, PipOverlayService.class));
            }
            new Handler(Looper.getMainLooper()).postDelayed(() -> loadCategoryContent(0), 300);
        });

        createRow("Configure HDMI Ports", "Open", () -> {
            startActivity(new Intent(this, PipSettingsActivity.class));
        });
    }

    // 1. REMOTE
    private void buildRemoteContent() {
        String button123Target = RemoteKeyMapperService.getMapping(this, 171);
        if (button123Target == null || button123Target.isEmpty() || "ACTION_PIP_SWAP".equals(button123Target)) {
            button123Target = "DualView Swap";
        } else if (RemoteKeyMapperService.ACTION_INPUTS.equals(button123Target)) {
            button123Target = "Inputs Menu";
        } else if ("com.google.android.apps.tv.launcherx".equals(button123Target)) {
            button123Target = "Google TV";
        } else if (RemoteKeyMapperService.ACTION_HOME.equals(button123Target)) {
            button123Target = "Home";
        }

        createRow("'123' Key", button123Target, () -> {
            String curr = RemoteKeyMapperService.getMapping(this, 171);
            if (curr == null || "ACTION_PIP_SWAP".equals(curr)) {
                RemoteKeyMapperService.setMapping(this, 171, RemoteKeyMapperService.ACTION_INPUTS);
            } else if (RemoteKeyMapperService.ACTION_INPUTS.equals(curr)) {
                RemoteKeyMapperService.setMapping(this, 171, "com.google.android.apps.tv.launcherx");
            } else if ("com.google.android.apps.tv.launcherx".equals(curr)) {
                RemoteKeyMapperService.setMapping(this, 171, RemoteKeyMapperService.ACTION_HOME);
            } else {
                RemoteKeyMapperService.setMapping(this, 171, RemoteKeyMapperService.ACTION_PIP_SWAP);
            }
            loadCategoryContent(1);
        });

        createRow("Inputs Key", "Inputs Switcher", () -> {});

        createRow("Key Mapper", "Active", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {}
        });

        createKeySnifferRow();

        createRow("Reset Remappings", "Reset", () -> {
            getSharedPreferences(RemoteKeyMapperService.PREFS_REMAP, MODE_PRIVATE).edit().clear().apply();
            Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show();
            loadCategoryContent(1);
        });
    }

    private void createKeySnifferRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(12), dp(18), dp(12));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x10FFFFFF);
        gd.setCornerRadius(dp(8));
        row.setBackground(gd);

        TextView label = new TextView(this);
        label.setText("Key Tester");
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        lastPressedKeyLabel = new TextView(this);
        lastPressedKeyLabel.setText("Press any remote key");
        lastPressedKeyLabel.setTextColor(0xFF38BDF8);
        lastPressedKeyLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(lastPressedKeyLabel);

        row.setOnFocusChangeListener((v, hasFocus) -> applyRowFocus(row, hasFocus));

        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    focusCategoryTab(activeCategoryIndex);
                    return true;
                }
                String name = KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "");
                lastPressedKeyLabel.setText(name + " (" + keyCode + ")");
                return true;
            }
            return false;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        rightContentContainer.addView(row, lp);
    }

    // 2. LAYOUT
    private void buildLayoutContent() {
        SharedPreferences sp = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        int startupTab = sp.getInt(KEY_STARTUP_TAB, 0);
        String startupText = startupTab == 0 ? "Home" : startupTab == 1 ? "Sports" : startupTab == 2 ? "Trakt" : "Apps";

        createRow("Startup Tab", startupText, () -> {
            int next = (startupTab + 1) % 4;
            sp.edit().putInt(KEY_STARTUP_TAB, next).apply();
            loadCategoryContent(2);
        });

        boolean heroBackdrop = sp.getBoolean(KEY_HERO_BACKDROP, true);
        createRow("Hero Backdrop", heroBackdrop ? "On" : "Off", () -> {
            sp.edit().putBoolean(KEY_HERO_BACKDROP, !heroBackdrop).apply();
            loadCategoryContent(2);
        });

        int marginMode = sp.getInt(KEY_MARGIN_MODE, 0);
        createRow("Margins", marginMode == 0 ? "28dp" : "58dp", () -> {
            sp.edit().putInt(KEY_MARGIN_MODE, marginMode == 0 ? 1 : 0).apply();
            loadCategoryContent(2);
        });

        boolean showSports = sp.getBoolean(KEY_SHOW_SPORTS_SHELF, true);
        createRow("Live Sports Shelf", showSports ? "Visible" : "Hidden", () -> {
            sp.edit().putBoolean(KEY_SHOW_SPORTS_SHELF, !showSports).apply();
            loadCategoryContent(2);
        });
    }

    // 3. SPORTS
    private void buildSportsContent() {
        Set<String> enabled = HubDataFetcher.getEnabledLeagues(this);

        createLeagueRow("NBA", enabled.contains("NBA"));
        createLeagueRow("NFL", enabled.contains("NFL"));
        createLeagueRow("Premier League", enabled.contains("EPL"));
        createLeagueRow("Champions League", enabled.contains("UCL"));
        createLeagueRow("MLB", enabled.contains("MLB"));
        createLeagueRow("NHL", enabled.contains("NHL"));
        createLeagueRow("College Basketball", enabled.contains("CBB"));

        createRow("Priority Teams", "Rockets, Spurs", () -> {});
        createRow("Refresh Scores", "Refresh", () -> {
            HubDataFetcher.fetchLiveSports(this, games -> {
                Toast.makeText(this, "Updated " + games.size() + " games", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void createLeagueRow(String code, boolean isEnabled) {
        createRow(code, isEnabled ? "On" : "Off", () -> {
            HubDataFetcher.toggleLeague(this, code.equals("Premier League") ? "EPL" : code.equals("Champions League") ? "UCL" : code.equals("College Basketball") ? "CBB" : code);
            loadCategoryContent(3);
        });
    }

    // 4. TRAKT
    private void buildTraktContent() {
        String username = HubDataFetcher.getTraktUsername(this);
        createRow("Account", "@" + username, () -> {});

        createRow("Stremio", "Open", () -> {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage("com.stremio.one");
            if (i == null) i = pm.getLaunchIntentForPackage("com.stremio.one");
            if (i != null) startActivity(i);
        });

        createRow("Clear Cache", "Clear", () -> {
            HubDataFetcher.clearMemoryCache();
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show();
        });
    }

    // 5. MEMORY
    private void buildMemoryContent() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);

        long totalMb = mi.totalMem / (1024 * 1024);
        long availMb = mi.availMem / (1024 * 1024);
        long usedMb = totalMb - availMb;

        createRow("RAM", usedMb + " / " + totalMb + " MB", () -> {});

        createRow("Trim Memory", "Trim", () -> {
            HubDataFetcher.clearMemoryCache();
            System.gc();
            Toast.makeText(this, "Memory trimmed", Toast.LENGTH_SHORT).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> loadCategoryContent(5), 250);
        });

        Display display = getWindowManager().getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        createRow("Display", dm.widthPixels + "x" + dm.heightPixels + " @ " + Math.round(display.getRefreshRate()) + "Hz", () -> {});
    }

    // 6. SYSTEM
    private void buildSystemContent() {
        createRow("Google TV Launcher", "Switch", () -> {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage("com.google.android.apps.tv.launcherx");
            if (i == null) i = pm.getLaunchIntentForPackage("com.google.android.apps.tv.launcherx");
            if (i != null) startActivity(i);
        });

        createRow("TV Settings", "Open", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {}
        });

        createRow("TV Inputs", "Open", () -> {
            try {
                startActivity(new Intent("android.settings.TV_INPUT_SETTINGS"));
            } catch (Exception e) {
                try {
                    startActivity(new Intent("com.android.tv.action.VIEW_INPUTS"));
                } catch (Exception ignored) {}
            }
        });

        createRow("Network", "Open", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception ignored) {}
        });

        createRow("Applications", "Open", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS));
            } catch (Exception ignored) {}
        });
    }

    // ROW BUILDER
    private void createRow(String title, String value, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(12), dp(18), dp(12));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x10FFFFFF);
        gd.setCornerRadius(dp(8));
        row.setBackground(gd);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        if (value != null && !value.isEmpty()) {
            TextView val = new TextView(this);
            val.setText(value);
            val.setTextColor(0xFF38BDF8);
            val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            val.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.addView(val);
        }

        row.setOnFocusChangeListener((v, hasFocus) -> applyRowFocus(row, hasFocus));

        row.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });

        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    focusCategoryTab(activeCategoryIndex);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (onClick != null) onClick.run();
                    return true;
                }
            }
            return false;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        rightContentContainer.addView(row, lp);
    }

    private void applyRowFocus(View v, boolean hasFocus) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(8));
        if (hasFocus) {
            gd.setColor(0x240284C7);
            gd.setStroke(dp(2), 0xFF38BDF8);
        } else {
            gd.setColor(0x10FFFFFF);
        }
        v.setBackground(gd);
    }

    private void focusCategoryTab(int index) {
        if (index >= 0 && index < categoryTabViews.size()) {
            categoryTabViews.get(index).requestFocus();
        }
    }

    private int dp(int val) {
        return (int) (val * getResources().getDisplayMetrics().density);
    }
}
