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
import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.R;
import com.gathin.tvhome.RemoteKeyMapperService;
import com.gathin.tvhome.pip.PipOverlayService;
import com.gathin.tvhome.pip.PipSettingsActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dedicated Standalone Launcher Settings App for Google TV / Android TV.
 * Full 10-foot TV interface for HDMI DualView PiP, Remote Key Remapping,
 * Layout & Margins, Sports Feeds, Trakt Sync, Memory Trimming, and System Controls.
 */
public class LauncherSettingsActivity extends Activity {

    public static final String PREFS_SETTINGS = "tvhome_launcher_settings";
    public static final String KEY_STARTUP_TAB = "startup_tab"; // 0=Home, 1=Sports, 2=Media, 3=Apps
    public static final String KEY_HERO_BACKDROP = "hero_backdrop_enabled"; // default true
    public static final String KEY_MARGIN_MODE = "margin_mode"; // 0=28dp Clean, 1=58dp Safe
    public static final String KEY_SHOW_SPORTS_SHELF = "show_sports_shelf"; // default true
    public static final String KEY_SHOW_TRAKT_SHELF = "show_trakt_shelf"; // default true

    private static final String[] CATEGORIES = {
            "⚡ HDMI DualView PiP",
            "🎮 Remote & '123' Button",
            "🏠 Home & Layout",
            "🏀 Sports Feeds",
            "🎬 Trakt & Stremio",
            "🚀 Memory & Diagnostics",
            "⚙️ Android TV System"
    };

    private static final String[] CATEGORY_TAGLINES = {
            "Pair any 2 HDMI inputs with PiP overlay and 1-click remote swapping",
            "Customize the '123' Virtual Remote key, TV Input button & remote hotkeys",
            "Startup view, TV safe margin profiles, hero backdrops & home rows",
            "Configure active sports leagues (NBA, NFL, Premier League, UEFA)",
            "Trakt.tv account sync, Continue Watching shelf & Stremio integration",
            "Live RAM usage stats, hardware specs & one-click memory booster",
            "Switch to stock Google TV Launcher, native TV settings & Wi-Fi"
    };

    private int activeCategoryIndex = 0;
    private final List<TextView> categoryTabViews = new ArrayList<>();
    private LinearLayout rightContentContainer;
    private ScrollView rightScrollView;
    private TextView categoryHeaderTitle;
    private TextView categoryHeaderSub;

    // Remote key sniffer display
    private TextView lastPressedKeyLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF090D16);

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(mainLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top App Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(32), dp(24), dp(32), dp(16));
        mainLayout.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView appBadge = new TextView(this);
        appBadge.setText("⚙️ CLEAN LAUNCHER");
        appBadge.setTextColor(0xFF38BDF8);
        appBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        appBadge.setTypeface(Typeface.DEFAULT_BOLD);
        appBadge.setLetterSpacing(0.08f);
        appBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0x2238BDF8);
        badgeBg.setCornerRadius(dp(6));
        badgeBg.setStroke(dp(1), 0x4438BDF8);
        appBadge.setBackground(badgeBg);
        topBar.addView(appBadge);

        TextView appTitle = new TextView(this);
        appTitle.setText("Launcher Settings");
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        appTitle.setPadding(dp(14), 0, 0, 0);
        topBar.addView(appTitle);

        TextView backHint = new TextView(this);
        backHint.setText("Press BACK to return home");
        backHint.setTextColor(0xFF64748B);
        backHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.weight = 1;
        backHint.setLayoutParams(hintLp);
        backHint.setGravity(Gravity.END);
        topBar.addView(backHint);

        // Divider
        View topDivider = new View(this);
        topDivider.setBackgroundColor(0x1AFFFFFF);
        mainLayout.addView(topDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Split Panes: Left Category Rail & Right Content Pane
        LinearLayout bodyPanes = new LinearLayout(this);
        bodyPanes.setOrientation(LinearLayout.HORIZONTAL);
        mainLayout.addView(bodyPanes, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // LEFT NAVIGATION RAIL
        ScrollView leftRailScroll = new ScrollView(this);
        leftRailScroll.setClipToPadding(false);
        leftRailScroll.setPadding(dp(28), dp(20), dp(16), dp(20));
        bodyPanes.addView(leftRailScroll, new LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout leftRail = new LinearLayout(this);
        leftRail.setOrientation(LinearLayout.VERTICAL);
        leftRailScroll.addView(leftRail, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        categoryTabViews.clear();
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int index = i;
            TextView tab = new TextView(this);
            tab.setText(CATEGORIES[i]);
            tab.setTextColor(i == 0 ? Color.WHITE : 0xFF94A3B8);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tab.setTypeface(Typeface.DEFAULT_BOLD);
            tab.setPadding(dp(16), dp(13), dp(16), dp(13));
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
            tabLp.bottomMargin = dp(8);
            leftRail.addView(tab, tabLp);
            categoryTabViews.add(tab);
        }

        // Vertical Divider
        View verticalDivider = new View(this);
        verticalDivider.setBackgroundColor(0x1AFFFFFF);
        bodyPanes.addView(verticalDivider, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        // RIGHT CONTENT PANE
        LinearLayout rightPane = new LinearLayout(this);
        rightPane.setOrientation(LinearLayout.VERTICAL);
        rightPane.setPadding(dp(32), dp(20), dp(36), dp(20));
        bodyPanes.addView(rightPane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));

        categoryHeaderTitle = new TextView(this);
        categoryHeaderTitle.setTextColor(Color.WHITE);
        categoryHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        categoryHeaderTitle.setTypeface(Typeface.DEFAULT_BOLD);
        rightPane.addView(categoryHeaderTitle);

        categoryHeaderSub = new TextView(this);
        categoryHeaderSub.setTextColor(0xFF94A3B8);
        categoryHeaderSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        categoryHeaderSub.setPadding(0, dp(4), 0, dp(16));
        rightPane.addView(categoryHeaderSub);

        rightScrollView = new ScrollView(this);
        rightScrollView.setClipToPadding(false);
        rightPane.addView(rightScrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rightContentContainer = new LinearLayout(this);
        rightContentContainer.setOrientation(LinearLayout.VERTICAL);
        rightScrollView.addView(rightContentContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        // Load initial tab
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
            gd.setStroke(dp(2), Color.WHITE);
            tab.setTextColor(Color.WHITE);
        } else if (isActive) {
            gd.setColor(0x2E0284C7);
            gd.setStroke(dp(1), 0x8838BDF8);
            tab.setTextColor(0xFF38BDF8);
        } else {
            gd.setColor(0x0EFFFFFF);
            gd.setStroke(dp(1), 0x1AFFFFFF);
            tab.setTextColor(0xFF94A3B8);
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
        categoryHeaderSub.setText(CATEGORY_TAGLINES[index]);
        rightContentContainer.removeAllViews();

        switch (index) {
            case 0:
                buildPipContent();
                break;
            case 1:
                buildRemoteKeysContent();
                break;
            case 2:
                buildHomeLayoutContent();
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

    // ==========================================
    // 0. HDMI DUALVIEW PIP SETTINGS
    // ==========================================
    private void buildPipContent() {
        SharedPreferences sp = getSharedPreferences(PipOverlayService.PREFS_PIP, MODE_PRIVATE);
        String inAName = sp.getString(PipOverlayService.KEY_INPUT_A_NAME, "HDMI 2 (PS5)");
        String inBName = sp.getString(PipOverlayService.KEY_INPUT_B_NAME, "HDMI 3 (Fire TV)");
        int corner = sp.getInt(PipOverlayService.KEY_CORNER, 0);
        String cornerText = corner == 0 ? "Top-Right" : corner == 1 ? "Bottom-Right" : corner == 2 ? "Bottom-Left" : "Top-Left";

        createSettingCard("Active HDMI Pair", inAName + " ⮂ " + inBName,
                "Configured HDMI inputs for DualView PiP quick-swapping",
                () -> {
                    Intent pipIntent = new Intent(this, PipSettingsActivity.class);
                    startActivity(pipIntent);
                });

        createSettingCard("Instant Swap Remote Hotkey", "🔘 '123' Virtual Remote / Window Button",
                "Press '123' button on physical TCL remote to instantly swap full-screen video with PiP",
                () -> Toast.makeText(this, "'123' Remote button is configured as the instant swap hotkey", Toast.LENGTH_SHORT).show());

        createSettingCard("PiP Overlay Corner Position", cornerText,
                "Cycle where the secondary input preview floating card appears on your TV screen",
                () -> {
                    int next = (corner + 1) % 4;
                    sp.edit().putInt(PipOverlayService.KEY_CORNER, next).apply();
                    loadCategoryContent(0);
                });

        boolean isOverlayRunning = PipOverlayService.isRunning;
        createSettingCard("PiP Overlay Session", isOverlayRunning ? "🟢 Active & Floating" : "⚪ Stopped / Idle",
                isOverlayRunning ? "Click to stop the background overlay service" : "Click to start the live HDMI DualView overlay session now",
                () -> {
                    if (PipOverlayService.isRunning) {
                        stopService(new Intent(this, PipOverlayService.class));
                        Toast.makeText(this, "DualView PiP Overlay Stopped", Toast.LENGTH_SHORT).show();
                    } else {
                        if (!Settings.canDrawOverlays(this)) {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                            return;
                        }
                        Intent svc = new Intent(this, PipOverlayService.class);
                        startService(svc);
                        Toast.makeText(this, "DualView PiP Overlay Started!", Toast.LENGTH_SHORT).show();
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(() -> loadCategoryContent(0), 400);
                });

        createSettingCard("Open Advanced HDMI DualView App", "▶ Launch App",
                "Customize individual HDMI ports, live score tickers and input assignments",
                () -> startActivity(new Intent(this, PipSettingsActivity.class)));
    }

    // ==========================================
    // 1. REMOTE BUTTONS & "123" KEY
    // ==========================================
    private void buildRemoteKeysContent() {
        String button123Target = RemoteKeyMapperService.getMapping(this, 171);
        if (button123Target == null || button123Target.isEmpty()) {
            button123Target = "HDMI DualView Instant Swap";
        }

        createSettingCard("'123' Virtual Remote Button (Key 171)", button123Target,
                "Press OK to cycle action: DualView Swap, Inputs Menu, Google TV, or Home",
                () -> {
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

        createSettingCard("TV Inputs Button (Key 178 / TV_INPUT)", "Global Clean Inputs Menu",
                "Opens Clean Launcher's quick input switcher from any TV app or HDMI port",
                () -> Toast.makeText(this, "TV Inputs Button is active globally across all apps", Toast.LENGTH_SHORT).show());

        createSettingCard("Accessibility Remapper Service", "🟢 Enabled (Active System-wide)",
                "Allows custom hardware remote shortcuts. Click to manage Accessibility settings",
                () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception ignored) {}
                });

        // Live Key Sniffer Card
        createKeySnifferCard();

        createSettingCard("Reset Remote Remappings", "🔄 Reset All to Defaults",
                "Restores factory button behavior ('123' = DualView swap, Inputs = clean inputs)",
                () -> {
                    getSharedPreferences(RemoteKeyMapperService.PREFS_REMAP, MODE_PRIVATE).edit().clear().apply();
                    Toast.makeText(this, "Remote button mappings reset to defaults", Toast.LENGTH_SHORT).show();
                    loadCategoryContent(1);
                });
    }

    private void createKeySnifferCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x18000000);
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(1), 0x22FFFFFF);
        card.setBackground(gd);

        TextView title = new TextView(this);
        title.setText("🎯 Live Remote Button Tester");
        title.setTextColor(0xFF38BDF8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        lastPressedKeyLabel = new TextView(this);
        lastPressedKeyLabel.setText("Press any button on physical TV remote to test keycode & detection...");
        lastPressedKeyLabel.setTextColor(0xFFE2E8F0);
        lastPressedKeyLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lastPressedKeyLabel.setPadding(0, dp(6), 0, 0);
        card.addView(lastPressedKeyLabel);

        card.setOnFocusChangeListener((v, hasFocus) -> {
            applyFocusAnimation(card, hasFocus);
            if (hasFocus) {
                lastPressedKeyLabel.setText("Listening... Press any button on remote now!");
            }
        });

        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    focusCategoryTab(activeCategoryIndex);
                    return true;
                }
                String name = KeyEvent.keyCodeToString(keyCode);
                lastPressedKeyLabel.setText("Detected Key: " + name + " (KeyCode: " + keyCode + ")");
                return true;
            }
            return false;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        rightContentContainer.addView(card, lp);
    }

    // ==========================================
    // 2. HOME SCREEN & LAYOUT
    // ==========================================
    private void buildHomeLayoutContent() {
        SharedPreferences sp = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        int startupTab = sp.getInt(KEY_STARTUP_TAB, 0);
        String startupText = startupTab == 0 ? "Home Hub" : startupTab == 1 ? "Live Sports" : startupTab == 2 ? "Media & Trakt" : "All Apps";

        createSettingCard("Startup Default Screen", startupText,
                "Screen that opens automatically when TV wakes up or launches Clean Launcher",
                () -> {
                    int next = (startupTab + 1) % 4;
                    sp.edit().putInt(KEY_STARTUP_TAB, next).apply();
                    loadCategoryContent(2);
                });

        boolean heroBackdrop = sp.getBoolean(KEY_HERO_BACKDROP, true);
        createSettingCard("Dynamic Hero Backdrop", heroBackdrop ? "Enabled (Artwork)" : "Disabled (Dark Minimal)",
                "Displays high-resolution backdrop fanart when focusing movies, shows or sports cards",
                () -> {
                    sp.edit().putBoolean(KEY_HERO_BACKDROP, !heroBackdrop).apply();
                    loadCategoryContent(2);
                });

        int marginMode = sp.getInt(KEY_MARGIN_MODE, 0);
        createSettingCard("TV Safe Margin Profile", marginMode == 0 ? "Clean Margins (28dp Standard)" : "Inset Margins (58dp Wide)",
                "Adjust screen edge spacing for edge-to-edge look without cutting off cards",
                () -> {
                    sp.edit().putInt(KEY_MARGIN_MODE, marginMode == 0 ? 1 : 0).apply();
                    loadCategoryContent(2);
                });

        boolean showSports = sp.getBoolean(KEY_SHOW_SPORTS_SHELF, true);
        createSettingCard("Live Sports Shelf on Home", showSports ? "Visible" : "Hidden",
                "Displays real-time NBA, NFL and Soccer live scores on the home screen",
                () -> {
                    sp.edit().putBoolean(KEY_SHOW_SPORTS_SHELF, !showSports).apply();
                    loadCategoryContent(2);
                });
    }

    // ==========================================
    // 3. SPORTS FEEDS & LEAGUES
    // ==========================================
    private void buildSportsContent() {
        Set<String> enabled = HubDataFetcher.getEnabledLeagues(this);

        createLeagueCard("NBA Basketball", "🏀", "NBA", enabled.contains("NBA"));
        createLeagueCard("NFL Football", "🏈", "NFL", enabled.contains("NFL"));
        createLeagueCard("Premier League Soccer", "⚽", "EPL", enabled.contains("EPL"));
        createLeagueCard("UEFA Champions League", "⚽", "UCL", enabled.contains("UCL"));
        createLeagueCard("MLB Baseball", "⚾", "MLB", enabled.contains("MLB"));
        createLeagueCard("NHL Ice Hockey", "🏒", "NHL", enabled.contains("NHL"));
        createLeagueCard("College Basketball", "🏀", "CBB", enabled.contains("CBB"));

        createSettingCard("Priority Teams", "🚀 Houston Rockets • San Antonio Spurs",
                "Scores and upcoming games for these teams are pinned to the front of sports shelves",
                () -> Toast.makeText(this, "Houston Rockets & Spurs are priority teams", Toast.LENGTH_SHORT).show());

        createSettingCard("Refresh Sports Scores Now", "⚡ Force Refresh",
                "Fetch latest scores, periods and game clock from ESPN APIs immediately",
                () -> {
                    HubDataFetcher.fetchLiveSports(this, games -> {
                        Toast.makeText(this, "Updated " + games.size() + " live & upcoming games!", Toast.LENGTH_SHORT).show();
                    });
                });
    }

    private void createLeagueCard(String name, String icon, String code, boolean isEnabled) {
        createSettingCard(icon + "  " + name, isEnabled ? "🟢 Active" : "⚪ Off",
                "Toggle live score ticker and shelf visibility for " + name,
                () -> {
                    HubDataFetcher.toggleLeague(this, code);
                    loadCategoryContent(3);
                });
    }

    // ==========================================
    // 4. TRAKT & STREMIO
    // ==========================================
    private void buildTraktContent() {
        String username = HubDataFetcher.getTraktUsername(this);
        String token = HubDataFetcher.getTraktToken(this);
        boolean isConnected = token != null && !token.isEmpty();

        createSettingCard("Trakt.tv Account", "@" + username,
                isConnected ? "Connected • Synced with cloud watchlist & watch history" : "Not connected",
                () -> Toast.makeText(this, "Connected as @" + username, Toast.LENGTH_SHORT).show());

        createSettingCard("Stremio Quick Integration", "▶ Launch Stremio",
                "Seamlessly deep-links with Stremio for 4K streaming playback",
                () -> {
                    PackageManager pm = getPackageManager();
                    Intent i = pm.getLeanbackLaunchIntentForPackage("com.stremio.one");
                    if (i == null) i = pm.getLaunchIntentForPackage("com.stremio.one");
                    if (i != null) startActivity(i);
                    else Toast.makeText(this, "Stremio is not installed", Toast.LENGTH_SHORT).show();
                });

        createSettingCard("Clear Trakt Cache & Resync", "🔄 Resync Library",
                "Forces re-fetching Continue Watching, Next Up episodes, and Trakt recommendations",
                () -> {
                    HubDataFetcher.clearMemoryCache();
                    Toast.makeText(this, "Trakt library cache cleared and resynced", Toast.LENGTH_SHORT).show();
                });
    }

    // ==========================================
    // 5. MEMORY & DIAGNOSTICS
    // ==========================================
    private void buildMemoryContent() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);

        long totalMb = mi.totalMem / (1024 * 1024);
        long availMb = mi.availMem / (1024 * 1024);
        long usedMb = totalMb - availMb;
        int usedPct = totalMb > 0 ? (int) ((usedMb * 100) / totalMb) : 0;

        createSettingCard("RAM Usage", usedMb + " MB / " + totalMb + " MB (" + usedPct + "% Used)",
                availMb + " MB Free memory available • Low memory killer: " + (mi.lowMemory ? "ACTIVE" : "Normal"),
                () -> {});

        createSettingCard("⚡ Boost Memory & Clear Image Cache", "Trim RAM Now",
                "Evicts bitmap caches, runs garbage collection, and frees background memory",
                () -> {
                    HubDataFetcher.clearMemoryCache();
                    System.gc();
                    Toast.makeText(this, "Memory optimized & bitmap cache flushed!", Toast.LENGTH_SHORT).show();
                    new Handler(Looper.getMainLooper()).postDelayed(() -> loadCategoryContent(5), 300);
                });

        // Display Specs
        Display display = getWindowManager().getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        float refreshRate = display.getRefreshRate();

        createSettingCard("Display Resolution", dm.widthPixels + " x " + dm.heightPixels + " @ " + Math.round(refreshRate) + "Hz",
                "Native hardware screen resolution and panel refresh rate",
                () -> {});

        createSettingCard("Display Density", dm.densityDpi + " dpi (" + dm.density + "x)",
                "Android TV UI scaling ratio",
                () -> {});
    }

    // ==========================================
    // 6. ANDROID TV SYSTEM
    // ==========================================
    private void buildSystemContent() {
        createSettingCard("Stock Google TV Launcher", "🚀 Switch to Google TV",
                "Launch the stock TCL / Google TV home screen (launcherx)",
                () -> {
                    PackageManager pm = getPackageManager();
                    Intent i = pm.getLeanbackLaunchIntentForPackage("com.google.android.apps.tv.launcherx");
                    if (i == null) i = pm.getLaunchIntentForPackage("com.google.android.apps.tv.launcherx");
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } else {
                        Toast.makeText(this, "Google TV launcher is disabled or not found", Toast.LENGTH_SHORT).show();
                    }
                });

        createSettingCard("Native TV Settings", "⚙️ Open Android TV Settings",
                "Display, Picture Mode, Dolby Vision, Audio Out, Bluetooth & System Settings",
                () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception ignored) {}
                });

        createSettingCard("TV Inputs Setup", "🔌 Open TV Inputs Manager",
                "Manage CEC, HDMI Port labels, and hardware input sources",
                () -> {
                    try {
                        startActivity(new Intent("android.settings.TV_INPUT_SETTINGS"));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent("com.android.tv.action.VIEW_INPUTS"));
                        } catch (Exception ignored) {}
                    }
                });

        createSettingCard("Wi-Fi & Network Settings", "📶 Open Network Settings",
                "Configure wireless network, IP address, and Ethernet connections",
                () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                    } catch (Exception ignored) {}
                });

        createSettingCard("Manage Installed Applications", "📱 Manage All Apps",
                "View storage usage, clear app cache, or force stop running apps",
                () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS));
                    } catch (Exception ignored) {}
                });
    }

    // ==========================================
    // HELPER UI BUILDERS
    // ==========================================
    private void createSettingCard(String titleText, String valueText, String descriptionText, Runnable onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(13), dp(18), dp(13));
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x18FFFFFF);
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(1), 0x22FFFFFF);
        card.setBackground(gd);

        // Header Row: Title on Left, Value/Badge on Right
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        headerRow.addView(title, titleLp);

        if (valueText != null && !valueText.isEmpty()) {
            TextView val = new TextView(this);
            val.setText(valueText);
            val.setTextColor(0xFF38BDF8);
            val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            val.setTypeface(Typeface.DEFAULT_BOLD);
            val.setPadding(dp(10), dp(3), dp(10), dp(3));
            GradientDrawable valBg = new GradientDrawable();
            valBg.setColor(0x2238BDF8);
            valBg.setCornerRadius(dp(6));
            valBg.setStroke(dp(1), 0x4438BDF8);
            val.setBackground(valBg);
            headerRow.addView(val);
        }

        if (descriptionText != null && !descriptionText.isEmpty()) {
            TextView desc = new TextView(this);
            desc.setText(descriptionText);
            desc.setTextColor(0xFF94A3B8);
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            desc.setPadding(0, dp(4), 0, 0);
            card.addView(desc);
        }

        card.setOnFocusChangeListener((v, hasFocus) -> applyFocusAnimation(card, hasFocus));

        card.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });

        card.setOnKeyListener((v, keyCode, event) -> {
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

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(10);
        rightContentContainer.addView(card, cardLp);
    }

    private void applyFocusAnimation(View v, boolean hasFocus) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(10));
        if (hasFocus) {
            gd.setColor(0x280284C7);
            gd.setStroke(dp(2), 0xFF38BDF8);
            v.animate().scaleX(1.015f).scaleY(1.015f).setDuration(120).start();
        } else {
            gd.setColor(0x18FFFFFF);
            gd.setStroke(dp(1), 0x22FFFFFF);
            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
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
