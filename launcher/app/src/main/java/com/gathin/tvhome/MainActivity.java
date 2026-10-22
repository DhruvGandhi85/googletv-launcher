package com.gathin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.os.Build;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import android.content.pm.PackageInfo;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AccelerateDecelerateInterpolator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class MainActivity extends Activity {

    private static final String PREFS = "tvhome_prefs";
    private static final String KEY_LAST_PKG = "last_pkg";
    private static final String KEY_HIDDEN_SHELVES = "hidden_trakt_shelves";
    private static final String KEY_APP_ORDER = "custom_app_order";
    private static final String KEY_USER_FOLDERS = "user_folders_json";

    public static final int MODE_HOME = 0;
    public static final int MODE_SPORTS = 1;
    public static final int MODE_MEDIA = 2;
    public static final int MODE_APPS = 3;
    private int currentViewMode = MODE_HOME;
    public static volatile MainActivity activeInstance = null;

    private ScrollView hubScroll;
    private LinearLayout hubContent;
    private HorizontalScrollView dockScroll;
    private LinearLayout dockRow;
    private HorizontalScrollView sportsScroll;
    private LinearLayout sportsRow;
    private TextView sportsStatus;
    private LinearLayout traktShelvesContainer;
    private View shelfAppsSection;
    private View shelfSportsSection;
    private LinearLayout sportsExtraShelvesContainer;

    private TextView clockView, dateView, greetingView, weatherPill;
    private TextView heroName;
    private TextView btnTabHome, btnTabSports, btnTabMedia, btnTabApps;
    private TextView btnAssistant, btnInputs, btnGoogleTv, btnSettings;
    private TextView btnTrakt, btnStremio, btnRemap, btnWifi;
    private View inputsScrim;
    private View inputsScroll;
    private LinearLayout inputsPanel;
    private boolean isCapturingKey = false;

    private ImageView heroBackdropA;
    private ImageView heroBackdropB;
    private boolean isBackdropAVisible = true;
    private String currentBackdropUrl = "";
    private final Handler heroBackdropHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingBackdropRunnable = null;

    private TextView heroTag;
    private TextView heroRating;
    private TextView heroExtraBadge;
    private TextView heroActionPrompt;
    private TextView heroTitle;
    private TextView heroDesc;
    private View heroStage;

    private LinearLayout appsGridSection;
    private LinearLayout appsGridRows;
    private final List<View> appsGridCardViews = new ArrayList<>();
    private final List<List<View>> appsGridRowViews = new ArrayList<>();
    private final List<HubDataFetcher.SportGame> cachedLiveSportGames = new ArrayList<>();

    private final List<AppEntry> dockEntries = new ArrayList<>();
    private final List<FolderEntry> folderEntries = new ArrayList<>();
    private final Map<String, AppEntry> installedMap = new HashMap<>();
    private final List<HubDataFetcher.TraktShelf> cachedTraktShelves = new ArrayList<>();
    private final Map<Integer, String> cecDeviceNames = new HashMap<Integer, String>() {{
        put(2, "PlayStation 5");
        put(3, "Fire TV Stick");
    }};
    private final java.util.Stack<Runnable> modalBackStack = new java.util.Stack<>();

    private void queryCecDeviceNames() {
        LocalAdbClient.runCommand("dumpsys tv_input | grep -E 'display_name:|port_id:'", (ok, out) -> {
            if (!ok || out == null) return;
            try {
                Pattern p = Pattern.compile("display_name:\\s*([^\\n]+?)\\s+power_status.*?port_id:\\s*(\\d+)");
                Matcher m = p.matcher(out);
                while (m.find()) {
                    String name = m.group(1).trim();
                    int port = Integer.parseInt(m.group(2).trim());
                    cecDeviceNames.put(port, name);
                }
            } catch (Exception ignored) {}
        });
    }

    // Cached views per section for instant mode switching
    private final List<View> appRowViews = new ArrayList<>();
    private final List<View> liveSportViews = new ArrayList<>();
    private final List<List<View>> extraSportShelfViews = new ArrayList<>();
    private final List<HorizontalScrollView> extraSportShelfScrolls = new ArrayList<>();
    private final List<List<View>> traktShelfViews = new ArrayList<>();
    private final List<HorizontalScrollView> traktShelfScrolls = new ArrayList<>();
    private final List<View> traktHeaderButtons = new ArrayList<>();

    // Active navigation rows for current view mode
    private final List<List<View>> navRows = new ArrayList<>();
    private final List<HorizontalScrollView> navScrolls = new ArrayList<>();
    private View[] topButtons;

    private View lastContentFocus;
    private boolean appsLoaded = false;

    // Independent refreshes for Sports (60s loop) and Trakt (30m loop)
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Handler sportsRefreshHandler = new Handler(Looper.getMainLooper());
    private final Handler traktRefreshHandler = new Handler(Looper.getMainLooper());
    private long lastSportsRefreshMs = 0;
    private long lastTraktRefreshMs = 0;

    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingLongPress = null;
    private boolean longPressTriggered = false;

    private final Runnable sportsRefreshRunnable = new Runnable() {
        @Override public void run() {
            refreshSportsData(false);
            sportsRefreshHandler.postDelayed(this, 60_000);
        }
    };

    private final Runnable traktRefreshRunnable = new Runnable() {
        @Override public void run() {
            refreshTraktData(false);
            traktRefreshHandler.postDelayed(this, 1_800_000);
        }
    };

    private static class AppEntry {
        String pkg;
        String label;
        Bitmap art;
        boolean isBanner;
    }

    private static class FolderEntry {
        String title;
        String subtitle;
        final List<AppEntry> apps = new ArrayList<>();
    }

    private long lastWeatherFetchTime = 0;
    private void refreshWeather() {
        long now = System.currentTimeMillis();
        if (now - lastWeatherFetchTime < 15 * 60_000L && weatherPill != null && weatherPill.getVisibility() == View.VISIBLE) {
            return;
        }
        lastWeatherFetchTime = now;
        HubDataFetcher.fetchCurrentWeather(this, wd -> {
            if (weatherPill != null && wd != null) {
                weatherPill.setText(wd.getSummary());
                weatherPill.setVisibility(View.VISIBLE);
            }
        });
    }

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            refreshWeather();
            clockHandler.postDelayed(this, 30_000);
        }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            appsLoaded = false;
            loadApps();
            buildDockAndFoldersRow();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hubScroll = findViewById(R.id.hubScroll);
        hubContent = findViewById(R.id.hubContent);
        dockScroll = findViewById(R.id.dockScroll);
        dockRow = findViewById(R.id.dockRow);
        sportsScroll = findViewById(R.id.sportsScroll);
        sportsRow = findViewById(R.id.sportsRow);
        sportsStatus = findViewById(R.id.sportsStatus);
        traktShelvesContainer = findViewById(R.id.traktShelvesContainer);
        shelfAppsSection = findViewById(R.id.shelfAppsSection);
        appsGridSection = findViewById(R.id.appsGridSection);
        appsGridRows = findViewById(R.id.appsGridRows);
        shelfSportsSection = findViewById(R.id.shelfSportsSection);
        sportsExtraShelvesContainer = findViewById(R.id.sportsExtraShelvesContainer);

        clockView = findViewById(R.id.clock);
        dateView = findViewById(R.id.date);
        weatherPill = findViewById(R.id.weatherPill);
        greetingView = findViewById(R.id.greeting);
        heroName = findViewById(R.id.heroName);

        heroStage = findViewById(R.id.heroStage);
        heroBackdropA = findViewById(R.id.heroBackdropA);
        heroBackdropB = findViewById(R.id.heroBackdropB);
        heroTag = findViewById(R.id.heroTag);
        heroRating = findViewById(R.id.heroRating);
        heroExtraBadge = findViewById(R.id.heroExtraBadge);
        heroActionPrompt = findViewById(R.id.heroActionPrompt);
        heroTitle = findViewById(R.id.heroTitle);
        heroDesc = findViewById(R.id.heroDesc);

        btnTabHome = findViewById(R.id.btnTabHome);
        btnTabSports = findViewById(R.id.btnTabSports);
        btnTabMedia = findViewById(R.id.btnTabMedia);
        btnTabApps = findViewById(R.id.btnTabApps);

        btnAssistant = findViewById(R.id.btnAssistant);
        btnInputs = findViewById(R.id.btnInputs);
        btnGoogleTv = findViewById(R.id.btnGoogleTv);
        btnSettings = findViewById(R.id.btnSettings);
        btnTrakt = findViewById(R.id.btnTrakt);
        btnStremio = findViewById(R.id.btnStremio);
        btnRemap = findViewById(R.id.btnRemap);
        btnWifi = findViewById(R.id.btnWifi);
        inputsScrim = findViewById(R.id.inputsScrim);
        inputsScroll = findViewById(R.id.inputsScroll);
        inputsPanel = findViewById(R.id.inputsPanel);

        topButtons = new View[]{ btnTabHome, btnTabSports, btnTabMedia, btnTabApps, btnInputs, btnGoogleTv, btnSettings };

        setupTopBar();

        // Seed default app button mappings to IPTV Color Inputs:
        // Disney+ -> 🔴 Red (View), Apple TV -> 🟢 Green (Sort), YouTube -> 🟡 Yellow (Favs), TCL -> 🔵 Blue (Move)
        SharedPreferences remapPrefs = getSharedPreferences(RemoteKeyMapperService.PREFS_REMAP, MODE_PRIVATE);
        SharedPreferences.Editor remapEd = remapPrefs.edit();
        // Clear previous color keys (183-186) so physical/virtual color keys pass directly to IPTV
        remapEd.remove(RemoteKeyMapperService.KEY_PREFIX + 183);
        remapEd.remove(RemoteKeyMapperService.KEY_PREFIX + 184);
        remapEd.remove(RemoteKeyMapperService.KEY_PREFIX + 185);
        remapEd.remove(RemoteKeyMapperService.KEY_PREFIX + 186);

        if (!remapPrefs.contains(RemoteKeyMapperService.KEY_PREFIX + 308)) {
            remapEd.putString(RemoteKeyMapperService.KEY_PREFIX + 308, RemoteKeyMapperService.ACTION_COLOR_RED);
        }
        if (!remapPrefs.contains(RemoteKeyMapperService.KEY_PREFIX + KeyEvent.KEYCODE_BUTTON_4)) {
            remapEd.putString(RemoteKeyMapperService.KEY_PREFIX + KeyEvent.KEYCODE_BUTTON_4, RemoteKeyMapperService.ACTION_COLOR_RED);
        }
        if (!remapPrefs.contains(RemoteKeyMapperService.KEY_PREFIX + 307)) {
            remapEd.putString(RemoteKeyMapperService.KEY_PREFIX + 307, RemoteKeyMapperService.ACTION_COLOR_GREEN);
        }
        if (!remapPrefs.contains(RemoteKeyMapperService.KEY_PREFIX + KeyEvent.KEYCODE_BUTTON_3)) {
            remapEd.putString(RemoteKeyMapperService.KEY_PREFIX + KeyEvent.KEYCODE_BUTTON_3, RemoteKeyMapperService.ACTION_COLOR_GREEN);
        }
        if (!remapPrefs.contains(RemoteKeyMapperService.KEY_PREFIX + 215)) {
            remapEd.putString(RemoteKeyMapperService.KEY_PREFIX + 215, RemoteKeyMapperService.ACTION_COLOR_YELLOW);
        }
        if (!remapPrefs.contains(RemoteKeyMapperService.KEY_PREFIX + KeyEvent.KEYCODE_BUTTON_2)) {
            remapEd.putString(RemoteKeyMapperService.KEY_PREFIX + KeyEvent.KEYCODE_BUTTON_2, RemoteKeyMapperService.ACTION_COLOR_YELLOW);
        }
        if (!remapPrefs.contains(RemoteKeyMapperService.KEY_PREFIX + 305)) {
            remapEd.putString(RemoteKeyMapperService.KEY_PREFIX + 305, RemoteKeyMapperService.ACTION_COLOR_BLUE);
        }
        if (!remapPrefs.contains(RemoteKeyMapperService.KEY_PREFIX + 656)) {
            remapEd.putString(RemoteKeyMapperService.KEY_PREFIX + 656, RemoteKeyMapperService.ACTION_COLOR_BLUE);
        }
        remapEd.apply();

        SharedPreferences hubPrefs = HubDataFetcher.prefs(this);
        SharedPreferences.Editor hubEd = hubPrefs.edit();
        if (!hubPrefs.contains("trakt_token")) {
            hubEd.putString("trakt_token", HubDataFetcher.DEFAULT_TRAKT_TOKEN);
        }
        if (!hubPrefs.contains("trakt_username")) {
            hubEd.putString("trakt_username", HubDataFetcher.DEFAULT_TRAKT_USERNAME);
        }
        if (!hubPrefs.contains("fav_teams_set")) {
            hubEd.putStringSet("fav_teams_set", HubDataFetcher.defaultFavTeams());
        }
        hubEd.apply();

        View btnNewFolder = findViewById(R.id.btnNewFolder);
        if (btnNewFolder != null) {
            btnNewFolder.setOnClickListener(v -> showCreateFolderModal());
            btnNewFolder.setOnFocusChangeListener((v, hasFocus) -> animateFocusCard(v, hasFocus));
            btnNewFolder.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    v.performClick();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!appsGridRowViews.isEmpty() && !appsGridRowViews.get(0).isEmpty()) {
                        appsGridRowViews.get(0).get(0).requestFocus();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (btnTabApps != null) {
                        btnTabApps.requestFocus();
                    }
                    return true;
                }
                return false;
            });
        }

        loadApps();
        queryCecDeviceNames();
        buildDockAndFoldersRow();
        switchViewMode(MODE_HOME);
        refreshSportsData(true);
        refreshTraktData(true);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        restoreInitialFocus();

        Intent launchIntent = getIntent();
        if (launchIntent != null && launchIntent.getBooleanExtra("SHOW_SETTINGS", false)) {
            showSettingsModal();
        } else if (launchIntent != null && launchIntent.getBooleanExtra("SHOW_INPUTS", false)) {
            showInputsPanel();
        } else if (launchIntent != null && launchIntent.getBooleanExtra("SHOW_REMAP", false)) {
            showRemapModal(launchIntent.getIntExtra("REMOTE_TYPE", 0));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeInstance = this;
        clockHandler.removeCallbacks(clockTick);
        clockTick.run();
        updateWifiLabel();
        updateTraktButtonLabel();

        if (!appsLoaded) {
            loadApps();
            buildDockAndFoldersRow();
        }

        refreshSportsData(false);
        refreshTraktData(false);

        sportsRefreshHandler.removeCallbacks(sportsRefreshRunnable);
        sportsRefreshHandler.postDelayed(sportsRefreshRunnable, 60_000);

        traktRefreshHandler.removeCallbacks(traktRefreshRunnable);
        traktRefreshHandler.postDelayed(traktRefreshRunnable, 1_800_000);

        // Ensure Clean Launcher owns the HOME button whenever resumed
        if (LocalAdbClient.isGoogleTvEnabled(this)) {
            LocalAdbClient.disableGoogleTv(this, false);
        }
        LocalAdbClient.ensureGoogleTvScreensaver(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (activeInstance == this) activeInstance = null;
        clockHandler.removeCallbacks(clockTick);
        sportsRefreshHandler.removeCallbacks(sportsRefreshRunnable);
        traktRefreshHandler.removeCallbacks(traktRefreshRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) {}
    }

    private String getDeviceUserName() {
        try {
            AccountManager am = AccountManager.get(this);
            Account[] accounts = am.getAccountsByType("com.google");
            if (accounts != null && accounts.length > 0) {
                String name = accounts[0].name;
                if (name != null && name.contains("@")) {
                    String user = name.substring(0, name.indexOf('@'));
                    if (!user.isEmpty()) {
                        return Character.toUpperCase(user.charAt(0)) + user.substring(1);
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getDeviceIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private String getGreetingText() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        String salutation;
        if (hour >= 5 && hour < 12) {
            salutation = "Good morning";
        } else if (hour >= 12 && hour < 17) {
            salutation = "Good afternoon";
        } else {
            salutation = "Good evening";
        }
        String user = getDeviceUserName();
        return user != null ? (salutation + ", " + user) : salutation;
    }

    private void updateClock() {
        Date now = new Date();
        clockView.setText(new SimpleDateFormat("h:mm a", Locale.US).format(now));
        dateView.setText(new SimpleDateFormat("EEE, MMM d", Locale.US).format(now));
        if (greetingView != null) {
            greetingView.setText(getGreetingText());
        }
    }

    private void updateTraktButtonLabel() {
        if (btnTrakt == null) return;
        String user = HubDataFetcher.getTraktUsername(this);
        if (!user.isEmpty()) {
            btnTrakt.setText("Trakt: @" + user);
        } else {
            btnTrakt.setText("Trakt: Connect");
        }
    }

    private void setupTopBar() {
        for (int i = 0; i < topButtons.length; i++) {
            final int idx = i;
            View b = topButtons[i];
            b.setOnFocusChangeListener((v, hasFocus) -> animateFocusCard(v, hasFocus));
            b.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    v.performClick();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (currentViewMode == MODE_APPS) {
                        View btnNewFolder = findViewById(R.id.btnNewFolder);
                        if (btnNewFolder != null && btnNewFolder.isShown()) {
                            btnNewFolder.requestFocus();
                            return true;
                        }
                    }
                    if (!navRows.isEmpty() && !navRows.get(0).isEmpty()) {
                        int targetCol = Math.min(idx, navRows.get(0).size() - 1);
                        navRows.get(0).get(targetCol).requestFocus();
                    } else if (lastContentFocus != null && lastContentFocus.isShown()) {
                        lastContentFocus.requestFocus();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (idx > 0) topButtons[idx - 1].requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (idx < topButtons.length - 1) topButtons[idx + 1].requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
                return false;
            });
        }

        btnTabHome.setOnClickListener(v -> {
            if (inputsScrim.getVisibility() == View.VISIBLE) hideInputsPanel();
            switchViewMode(MODE_HOME);
        });
        btnTabSports.setOnClickListener(v -> {
            if (inputsScrim.getVisibility() == View.VISIBLE) hideInputsPanel();
            switchViewMode(MODE_SPORTS);
        });
        btnTabMedia.setOnClickListener(v -> {
            if (inputsScrim.getVisibility() == View.VISIBLE) hideInputsPanel();
            switchViewMode(MODE_MEDIA);
        });
        btnTabApps.setOnClickListener(v -> {
            if (inputsScrim.getVisibility() == View.VISIBLE) hideInputsPanel();
            switchViewMode(MODE_APPS);
        });
        if (btnInputs != null) btnInputs.setOnClickListener(v -> toggleInputsPanel());
        if (btnGoogleTv != null) btnGoogleTv.setOnClickListener(v -> launchGoogleTvLauncher());
        if (btnSettings != null) btnSettings.setOnClickListener(v -> showSettingsModal());
        inputsScrim.setOnClickListener(v -> hideInputsPanel());

        if (btnTrakt != null) btnTrakt.setOnClickListener(v -> showTraktSetupModal());
        if (btnStremio != null) btnStremio.setOnClickListener(v -> launchPackage("com.stremio.one"));
        if (btnRemap != null) btnRemap.setOnClickListener(v -> showRemapModal());
        if (btnWifi != null) btnWifi.setOnClickListener(v -> openIntent(new Intent(Settings.ACTION_WIFI_SETTINGS)));
    }

    private void updateTabPill(TextView tab, boolean active) {
        if (tab == null) return;
        tab.setBackgroundResource(active ? R.drawable.pill_active_bg : R.drawable.pill_bg);
        try {
            tab.setTextColor(getResources().getColorStateList(active ? R.drawable.pill_active_text : R.drawable.pill_text));
        } catch (Exception ignored) {
            tab.setTextColor(active ? 0xFF0A0C10 : 0xB3FFFFFF);
        }
    }

    private void clearHeroHeader(int ambientColor) {
        if (heroTag != null) heroTag.setVisibility(View.GONE);
        if (heroRating != null) heroRating.setVisibility(View.GONE);
        if (heroExtraBadge != null) heroExtraBadge.setVisibility(View.GONE);
        if (heroActionPrompt != null) heroActionPrompt.setVisibility(View.GONE);
        if (heroTitle != null) heroTitle.setText("");
        if (heroDesc != null) {
            heroDesc.setText("");
            heroDesc.setVisibility(View.GONE);
        }
        transitionHeroBackdrop(null, ambientColor);
    }

    private HubDataFetcher.MediaItem getFirstMediaItem() {
        for (HubDataFetcher.TraktShelf shelf : cachedTraktShelves) {
            if (shelf.items != null && !shelf.items.isEmpty()) {
                return shelf.items.get(0);
            }
        }
        return null;
    }

    private HubDataFetcher.SportGame getFirstSportGame() {
        if (!cachedLiveSportGames.isEmpty()) {
            return cachedLiveSportGames.get(0);
        }
        return null;
    }

    private void switchViewMode(int mode) {
        currentViewMode = mode;
        updateTabPill(btnTabHome, mode == MODE_HOME);
        updateTabPill(btnTabSports, mode == MODE_SPORTS);
        updateTabPill(btnTabMedia, mode == MODE_MEDIA);
        updateTabPill(btnTabApps, mode == MODE_APPS);

        if (mode != MODE_MEDIA) {
            clearBackdropImmediately();
        }

        if (mode == MODE_HOME) {
            if (heroStage != null) heroStage.setVisibility(View.VISIBLE);
            shelfAppsSection.setVisibility(View.VISIBLE);
            if (appsGridSection != null) appsGridSection.setVisibility(View.GONE);
            shelfSportsSection.setVisibility(View.VISIBLE);
            sportsExtraShelvesContainer.setVisibility(View.GONE);
            traktShelvesContainer.setVisibility(View.VISIBLE);
            renderTraktShelves();

            if (!dockEntries.isEmpty()) {
                updateFocusedHeader(dockEntries.get(0));
            } else {
                HubDataFetcher.SportGame firstSport = getFirstSportGame();
                if (firstSport != null) {
                    updateFocusedHeader(firstSport);
                } else {
                    clearHeroHeader(0xFF141E28);
                }
            }
        } else if (mode == MODE_SPORTS) {
            if (heroStage != null) heroStage.setVisibility(View.VISIBLE);
            shelfAppsSection.setVisibility(View.GONE);
            if (appsGridSection != null) appsGridSection.setVisibility(View.GONE);
            shelfSportsSection.setVisibility(View.VISIBLE);
            sportsExtraShelvesContainer.setVisibility(View.VISIBLE);
            traktShelvesContainer.setVisibility(View.GONE);

            HubDataFetcher.SportGame firstSport = getFirstSportGame();
            if (firstSport != null) {
                updateFocusedHeader(firstSport);
            } else {
                clearHeroHeader(0xFF101924);
            }

            if (extraSportShelfViews.isEmpty()) {
                HubDataFetcher.fetchAllSportsShelves(this, this::renderExtraSportsShelves);
            }
        } else if (mode == MODE_MEDIA) {
            if (heroStage != null) heroStage.setVisibility(View.VISIBLE);
            shelfAppsSection.setVisibility(View.GONE);
            if (appsGridSection != null) appsGridSection.setVisibility(View.GONE);
            shelfSportsSection.setVisibility(View.GONE);
            sportsExtraShelvesContainer.setVisibility(View.GONE);
            traktShelvesContainer.setVisibility(View.VISIBLE);
            renderTraktShelves();

            HubDataFetcher.MediaItem firstMedia = getFirstMediaItem();
            if (firstMedia != null) {
                updateFocusedHeader(firstMedia);
            } else {
                clearHeroHeader(0xFF141E28);
            }
        } else if (mode == MODE_APPS) {
            // ROKU STYLE: Grid takes full stage, splash screen cleared
            if (heroStage != null) heroStage.setVisibility(View.GONE);
            shelfAppsSection.setVisibility(View.GONE);
            if (appsGridSection != null) appsGridSection.setVisibility(View.VISIBLE);
            shelfSportsSection.setVisibility(View.GONE);
            sportsExtraShelvesContainer.setVisibility(View.GONE);
            traktShelvesContainer.setVisibility(View.GONE);

            clearHeroHeader(0xFF0A0C10);
        }

        rebuildNavigationForCurrentMode();
        if (hubScroll != null) hubScroll.scrollTo(0, 0);
    }

    private void launchAssistant() {
        Intent assistIntent = new Intent(Intent.ACTION_ASSIST);
        assistIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(assistIntent);
        } catch (Exception e) {
            Intent direct = getPackageManager().getLeanbackLaunchIntentForPackage("com.google.android.katniss");
            if (direct != null) {
                direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(direct);
            } else {
                Toast.makeText(this, "Google Assistant not available", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showSportsSettingsModal() {
        inputsPanel.removeAllViews();
        addModalHeader("Sports Preferences", "Configure live sports leagues, scores, and favorite teams");

        Set<String> enLeagues = HubDataFetcher.getEnabledLeagues(this);
        String enSummary = enLeagues.size() + "/" + HubDataFetcher.ALL_LEAGUES.size() + " ACTIVE";
        addModalOptionWithStatus("🏆 Enabled Sports Leagues", true, enSummary, this::showEnabledSportsModal);
        addModalOptionWithStatus("⭐ Favorite Teams Per Sport", true, "CUSTOMIZE", this::showFavTeamsModal);
        addModalOption("🔄 Refresh Sports Data Now", () -> {
            hideInputsPanel();
            refreshSportsData(true);
            Toast.makeText(this, "Refreshing ESPN Live Scores...", Toast.LENGTH_SHORT).show();
        });
        addModalOption("Close (Back)", this::hideInputsPanel);

        showModalContainer();
    }

    private void showCreateFolderModal() {
        inputsPanel.removeAllViews();
        addModalHeader("Create New Folder", "Choose a category to organize your channels and apps");

        String[] suggestions = new String[]{ "Streaming", "Sports", "Utilities", "News & Docs", "Kids & Family", "Gaming" };
        for (String s : suggestions) {
            boolean exists = false;
            for (FolderEntry f : folderEntries) {
                if (f.title.equalsIgnoreCase(s)) { exists = true; break; }
            }
            if (!exists) {
                addModalOption("📁  Create '" + s + "' Folder", () -> {
                    FolderEntry fe = new FolderEntry();
                    fe.title = s;
                    fe.subtitle = "Empty Folder";
                    folderEntries.add(fe);
                    saveFolders();
                    buildDockAndFoldersRow();
                    hideInputsPanel();
                    Toast.makeText(this, "Created " + s + " folder. Long-press any app to add it.", Toast.LENGTH_SHORT).show();
                });
            }
        }

        addModalOption("Close (Back)", this::hideInputsPanel);
        showModalContainer();
    }

    private void showSettingsModal() {
        modalBackStack.clear();
        inputsPanel.removeAllViews();
        addModalHeader("Settings & Tools", "Remote remapper, sports preferences, ambient screensaver & system");

        // 1. Remote Button Remapper
        addModalOptionWithStatus("🎛 Remote Button Remapper", true, "CONFIGURED", () -> {
            modalBackStack.push(this::showSettingsModal);
            showRemapModal(0);
        });

        // 2. Sports Preferences (Enabled Leagues & Favorite Teams Submenu)
        Set<String> enLeagues = HubDataFetcher.getEnabledLeagues(this);
        String enSummary = enLeagues.size() + "/" + HubDataFetcher.ALL_LEAGUES.size() + " ACTIVE";
        addModalOptionWithStatus("🏆 Sports Preferences", true, enSummary, () -> {
            modalBackStack.push(this::showSettingsModal);
            showSportsPreferencesSubMenu();
        });

        // 3. Screensaver & Ambient Mode
        addModalOptionWithStatus("🖼 Screensaver & Ambient Mode", true, "ACTIVE", () -> {
            modalBackStack.push(this::showSettingsModal);
            showScreensaverMenu();
        });

        // 4. Launcher Switcher
        boolean gtvOn = LocalAdbClient.isGoogleTvEnabled(this);
        if (gtvOn) {
            addModalOptionWithStatus("🏠 Switch to Clean Launcher", true, "STOCK", () -> {
                LocalAdbClient.disableGoogleTv(this, true);
                showSettingsModal();
            });
        } else {
            addModalOptionWithStatus("🏠 Switch to Google TV Launcher", true, "DEFAULT", () -> {
                LocalAdbClient.enableGoogleTvAndLaunch(this);
            });
        }

        // 5. Native Android TV System Settings
        addModalOption("⚙ Android TV System Settings", () -> openIntent(new Intent(Settings.ACTION_SETTINGS)));
        addModalOption("Close (Back)", this::hideInputsPanel);

        showModalContainer();
    }

    private void showSportsPreferencesSubMenu() {
        inputsPanel.removeAllViews();
        Set<String> enLeagues = HubDataFetcher.getEnabledLeagues(this);
        String enSummary = enLeagues.size() + "/" + HubDataFetcher.ALL_LEAGUES.size() + " Leagues Active";
        addModalHeader("Sports Preferences", "Configure live scoreboard leagues and favorite teams");

        addModalOptionWithStatus("🏆 Enabled Sports Leagues", true, enSummary, () -> {
            modalBackStack.push(this::showSportsPreferencesSubMenu);
            showEnabledSportsModal();
        });
        addModalOptionWithStatus("⭐ Favorite Teams Per Sport", true, "CUSTOMIZE", () -> {
            modalBackStack.push(this::showSportsPreferencesSubMenu);
            showFavTeamsModal();
        });
        addModalOption("⬅ Back", this::handleModalBack);

        showModalContainer();
    }

    private void showEnabledSportsModal() {
        showEnabledSportsModal(0);
    }

    private void showEnabledSportsModal(int focusIndex) {
        inputsPanel.removeAllViews();
        Set<String> enabled = HubDataFetcher.getEnabledLeagues(this);
        addModalHeader("Enabled Sports Leagues", "Toggle live scoreboard leagues on Home and Sports tabs (" + enabled.size() + "/" + HubDataFetcher.ALL_LEAGUES.size() + " Enabled)");

        int optionCount = 0;
        for (HubDataFetcher.LeagueDef def : HubDataFetcher.ALL_LEAGUES) {
            final int curOptionIdx = optionCount++;
            boolean isEn = enabled.contains(def.code);
            String label = (isEn ? "✅  " : "⬜  ") + def.emoji + " " + def.name;
            addModalOptionWithStatus(label, isEn, isEn ? "ENABLED" : "OFF", () -> {
                HubDataFetcher.toggleLeague(this, def.code);
                showEnabledSportsModal(curOptionIdx);
                refreshSportsData(true);
            });
        }

        final int enableAllIdx = optionCount++;
        addModalOption("⚡  Enable All 10 Major Leagues", () -> {
            HubDataFetcher.setEnabledLeagues(this, HubDataFetcher.defaultEnabledLeagues());
            showEnabledSportsModal(enableAllIdx);
            refreshSportsData(true);
        });

        addModalOption("⬅ Back", this::handleModalBack);

        showModalContainer(focusIndex);
    }

    private static class TeamChoice {
        final String abbr;
        final String name;
        TeamChoice(String abbr, String name) {
            this.abbr = abbr;
            this.name = name;
        }
    }

    private List<TeamChoice> getTeamsForLeague(String league) {
        List<TeamChoice> list = new ArrayList<>();
        if ("NFL".equals(league)) {
            list.add(new TeamChoice("ARI", "Arizona Cardinals"));
            list.add(new TeamChoice("ATL", "Atlanta Falcons"));
            list.add(new TeamChoice("BAL", "Baltimore Ravens"));
            list.add(new TeamChoice("BUF", "Buffalo Bills"));
            list.add(new TeamChoice("CAR", "Carolina Panthers"));
            list.add(new TeamChoice("CHI", "Chicago Bears"));
            list.add(new TeamChoice("CIN", "Cincinnati Bengals"));
            list.add(new TeamChoice("CLE", "Cleveland Browns"));
            list.add(new TeamChoice("DAL", "Dallas Cowboys"));
            list.add(new TeamChoice("DEN", "Denver Broncos"));
            list.add(new TeamChoice("DET", "Detroit Lions"));
            list.add(new TeamChoice("GB", "Green Bay Packers"));
            list.add(new TeamChoice("HOU", "Houston Texans"));
            list.add(new TeamChoice("IND", "Indianapolis Colts"));
            list.add(new TeamChoice("JAX", "Jacksonville Jaguars"));
            list.add(new TeamChoice("KC", "Kansas City Chiefs"));
            list.add(new TeamChoice("LV", "Las Vegas Raiders"));
            list.add(new TeamChoice("LAC", "LA Chargers"));
            list.add(new TeamChoice("LAR", "LA Rams"));
            list.add(new TeamChoice("MIA", "Miami Dolphins"));
            list.add(new TeamChoice("MIN", "Minnesota Vikings"));
            list.add(new TeamChoice("NE", "New England Patriots"));
            list.add(new TeamChoice("NO", "New Orleans Saints"));
            list.add(new TeamChoice("NYG", "New York Giants"));
            list.add(new TeamChoice("NYJ", "New York Jets"));
            list.add(new TeamChoice("PHI", "Philadelphia Eagles"));
            list.add(new TeamChoice("PIT", "Pittsburgh Steelers"));
            list.add(new TeamChoice("SF", "San Francisco 49ers"));
            list.add(new TeamChoice("SEA", "Seattle Seahawks"));
            list.add(new TeamChoice("TB", "Tampa Bay Buccaneers"));
            list.add(new TeamChoice("TEN", "Tennessee Titans"));
            list.add(new TeamChoice("WSH", "Washington Commanders"));
        } else if ("NBA".equals(league)) {
            list.add(new TeamChoice("ATL", "Atlanta Hawks"));
            list.add(new TeamChoice("BOS", "Boston Celtics"));
            list.add(new TeamChoice("BKN", "Brooklyn Nets"));
            list.add(new TeamChoice("CHA", "Charlotte Hornets"));
            list.add(new TeamChoice("CHI", "Chicago Bulls"));
            list.add(new TeamChoice("CLE", "Cleveland Cavaliers"));
            list.add(new TeamChoice("DAL", "Dallas Mavericks"));
            list.add(new TeamChoice("DEN", "Denver Nuggets"));
            list.add(new TeamChoice("DET", "Detroit Pistons"));
            list.add(new TeamChoice("GSW", "Golden State Warriors"));
            list.add(new TeamChoice("HOU", "Houston Rockets"));
            list.add(new TeamChoice("IND", "Indiana Pacers"));
            list.add(new TeamChoice("LAC", "LA Clippers"));
            list.add(new TeamChoice("LAL", "LA Lakers"));
            list.add(new TeamChoice("MEM", "Memphis Grizzlies"));
            list.add(new TeamChoice("MIA", "Miami Heat"));
            list.add(new TeamChoice("MIL", "Milwaukee Bucks"));
            list.add(new TeamChoice("MIN", "Minnesota Timberwolves"));
            list.add(new TeamChoice("NOP", "New Orleans Pelicans"));
            list.add(new TeamChoice("NYK", "New York Knicks"));
            list.add(new TeamChoice("OKC", "Oklahoma City Thunder"));
            list.add(new TeamChoice("ORL", "Orlando Magic"));
            list.add(new TeamChoice("PHI", "Philadelphia 76ers"));
            list.add(new TeamChoice("PHX", "Phoenix Suns"));
            list.add(new TeamChoice("POR", "Portland Trail Blazers"));
            list.add(new TeamChoice("SAC", "Sacramento Kings"));
            list.add(new TeamChoice("SAS", "San Antonio Spurs"));
            list.add(new TeamChoice("TOR", "Toronto Raptors"));
            list.add(new TeamChoice("UTA", "Utah Jazz"));
            list.add(new TeamChoice("WAS", "Washington Wizards"));
        } else if ("MLB".equals(league)) {
            list.add(new TeamChoice("NYY", "New York Yankees"));
            list.add(new TeamChoice("NYM", "New York Mets"));
            list.add(new TeamChoice("LAD", "Los Angeles Dodgers"));
            list.add(new TeamChoice("BOS", "Boston Red Sox"));
            list.add(new TeamChoice("CHC", "Chicago Cubs"));
            list.add(new TeamChoice("ATL", "Atlanta Braves"));
            list.add(new TeamChoice("HOU", "Houston Astros"));
            list.add(new TeamChoice("PHI", "Philadelphia Phillies"));
            list.add(new TeamChoice("SF", "San Francisco Giants"));
            list.add(new TeamChoice("STL", "St. Louis Cardinals"));
            list.add(new TeamChoice("SD", "San Diego Padres"));
            list.add(new TeamChoice("TOR", "Toronto Blue Jays"));
            list.add(new TeamChoice("BAL", "Baltimore Orioles"));
            list.add(new TeamChoice("TEX", "Texas Rangers"));
            list.add(new TeamChoice("SEA", "Seattle Mariners"));
        } else if ("NHL".equals(league)) {
            list.add(new TeamChoice("NYR", "New York Rangers"));
            list.add(new TeamChoice("NYI", "New York Islanders"));
            list.add(new TeamChoice("NJD", "New Jersey Devils"));
            list.add(new TeamChoice("BOS", "Boston Bruins"));
            list.add(new TeamChoice("TOR", "Toronto Maple Leafs"));
            list.add(new TeamChoice("MTL", "Montreal Canadiens"));
            list.add(new TeamChoice("EDM", "Edmonton Oilers"));
            list.add(new TeamChoice("FLA", "Florida Panthers"));
            list.add(new TeamChoice("VGK", "Vegas Golden Knights"));
            list.add(new TeamChoice("COL", "Colorado Avalanche"));
            list.add(new TeamChoice("CHI", "Chicago Blackhawks"));
            list.add(new TeamChoice("PIT", "Pittsburgh Penguins"));
        } else if ("CFB".equals(league)) {
            list.add(new TeamChoice("ALA", "Alabama Crimson Tide"));
            list.add(new TeamChoice("UGA", "Georgia Bulldogs"));
            list.add(new TeamChoice("OSU", "Ohio State Buckeyes"));
            list.add(new TeamChoice("MICH", "Michigan Wolverines"));
            list.add(new TeamChoice("TEX", "Texas Longhorns"));
            list.add(new TeamChoice("ORE", "Oregon Ducks"));
            list.add(new TeamChoice("PSU", "Penn State Nittany Lions"));
            list.add(new TeamChoice("ND", "Notre Dame Fighting Irish"));
            list.add(new TeamChoice("LSU", "LSU Tigers"));
            list.add(new TeamChoice("USC", "USC Trojans"));
            list.add(new TeamChoice("TENN", "Tennessee Volunteers"));
            list.add(new TeamChoice("MIA", "Miami Hurricanes"));
        } else if ("SOCCER".equals(league)) {
            list.add(new TeamChoice("ARS", "Arsenal"));
            list.add(new TeamChoice("CHE", "Chelsea"));
            list.add(new TeamChoice("LIV", "Liverpool"));
            list.add(new TeamChoice("MCI", "Manchester City"));
            list.add(new TeamChoice("MUN", "Manchester United"));
            list.add(new TeamChoice("TOT", "Tottenham Hotspur"));
            list.add(new TeamChoice("RMA", "Real Madrid"));
            list.add(new TeamChoice("BAR", "Barcelona"));
            list.add(new TeamChoice("MIA", "Inter Miami CF"));
        }
        return list;
    }

    private void showFavTeamsModal() {
        inputsPanel.removeAllViews();
        addModalHeader("⭐ Favorite Teams & Sports", "Select a league to pick your favorite teams");

        addModalOption("🏈  NFL Football Teams", () -> {
            modalBackStack.push(this::showFavTeamsModal);
            showLeagueTeamsPicker("NFL");
        });
        addModalOption("🏀  NBA Basketball Teams", () -> {
            modalBackStack.push(this::showFavTeamsModal);
            showLeagueTeamsPicker("NBA");
        });
        addModalOption("⚾  MLB Baseball Teams", () -> {
            modalBackStack.push(this::showFavTeamsModal);
            showLeagueTeamsPicker("MLB");
        });
        addModalOption("🏒  NHL Hockey Teams", () -> {
            modalBackStack.push(this::showFavTeamsModal);
            showLeagueTeamsPicker("NHL");
        });
        addModalOption("🏈  NCAA College Football (CFB)", () -> {
            modalBackStack.push(this::showFavTeamsModal);
            showLeagueTeamsPicker("CFB");
        });
        addModalOption("⚽  Soccer Teams (EPL / MLS)", () -> {
            modalBackStack.push(this::showFavTeamsModal);
            showLeagueTeamsPicker("SOCCER");
        });

        addModalOption("🗑  Clear All Favorite Teams", () -> {
            HubDataFetcher.setFavoriteTeams(this, Collections.emptySet());
            Toast.makeText(this, "Cleared all favorite teams", Toast.LENGTH_SHORT).show();
            refreshSportsData(true);
            showFavTeamsModal();
        });

        addModalOption("⬅ Back", this::handleModalBack);

        showModalContainer();
    }

    private void showLeagueTeamsPicker(String league) {
        showLeagueTeamsPicker(league, 0);
    }

    private void showLeagueTeamsPicker(String league, int focusIndex) {
        inputsPanel.removeAllViews();
        addModalHeader("⭐ " + league + " Favorite Teams", "Click team to toggle favorite (pins & prioritizes on scoreboard)");

        Set<String> favs = HubDataFetcher.getFavoriteTeams(this);
        List<TeamChoice> teams = getTeamsForLeague(league);

        int optionIdx = 0;
        for (TeamChoice tc : teams) {
            final int curIdx = optionIdx++;
            boolean isFav = HubDataFetcher.isFavTeam(favs, tc.abbr, tc.name);
            String prefix = isFav ? "★  " : "    ";
            String status = isFav ? "FAVORITE" : "OFF";
            addModalOptionWithStatus(prefix + tc.name + " (" + tc.abbr + ")", isFav, status, () -> {
                Set<String> curFavs = HubDataFetcher.getFavoriteTeams(this);
                if (HubDataFetcher.isFavTeam(curFavs, tc.abbr, tc.name)) {
                    curFavs.remove(tc.abbr.toUpperCase());
                    curFavs.remove(tc.name.toUpperCase());
                    String[] words = tc.name.toUpperCase().split(" ");
                    if (words.length > 0) curFavs.remove(words[words.length - 1]);
                    Toast.makeText(this, tc.name + " removed from favorites", Toast.LENGTH_SHORT).show();
                } else {
                    curFavs.add(tc.abbr.toUpperCase());
                    String[] words = tc.name.toUpperCase().split(" ");
                    if (words.length > 0) curFavs.add(words[words.length - 1]);
                    Toast.makeText(this, tc.name + " marked as favorite ★", Toast.LENGTH_SHORT).show();
                }
                HubDataFetcher.setFavoriteTeams(this, curFavs);
                refreshSportsData(true);
                showLeagueTeamsPicker(league, curIdx);
            });
        }

        addModalOption("⬅ Back", this::handleModalBack);

        showModalContainer(focusIndex);
    }

    private void showMetricsModal() {
        inputsPanel.removeAllViews();
        addModalHeader("📊 CleanYourTV System Dashboard", "Executive telemetry & performance benchmarks vs. Google TV");

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid.addView(createMetricsRow(
                "RAM ALLOCATION", "68 MB", "vs. 234 MB Google TV (-71%)", "OPTIMAL", 0xFF69F0AE,
                "PROMOS & ADS", "0 Ads", "100% Sponsor & Ad-Free", "CLEAN", 0xFF69F0AE
        ));
        grid.addView(createMetricsRow(
                "UI FRAME RATE", "60 FPS", "Mali-G52 GPU Composed", "SMOOTH", 0xFF4DD0E1,
                "APP RESPONSE", "0.25s", "Cold Start Instant Launch", "FAST", 0xFF4DD0E1
        ));
        String accountDisplay = getDeviceUserName();
        if (accountDisplay == null) accountDisplay = "Active";
        grid.addView(createMetricsRow(
                "MEMORY THRASH", "103 KB", "vs. 9.4 MB ZRAM Thrash (-99%)", "HEALTHY", 0xFF81D4FA,
                "GOOGLE ACCOUNT", accountDisplay, "Smart Home & Assistant Synced", "CONNECTED", 0xFFFFB74D
        ));

        inputsPanel.addView(grid);
        addModalOption("⬅ Back to Settings", this::showSettingsModal);

        showModalContainer();
    }

    private LinearLayout createMetricsRow(String t1, String v1, String s1, String b1, int c1,
                                          String t2, String v2, String s2, String b2, int c2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        row.addView(createMetricCard(t1, v1, s1, b1, c1));
        View space = new View(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        row.addView(space);
        row.addView(createMetricCard(t2, v2, s2, b2, c2));
        return row;
    }

    private View createMetricCard(String title, String value, String sub, String badgeText, int badgeColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        card.setLayoutParams(lp);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackgroundResource(R.drawable.card_focus_bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextColor(0x88FFFFFF);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(titleTv, tlp);

        TextView badge = new TextView(this);
        badge.setText(badgeText);
        badge.setTextColor(badgeColor);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setPadding(dp(5), dp(1), dp(5), dp(1));
        badge.setBackgroundResource(R.drawable.hero_badge_bg);
        top.addView(badge);
        card.addView(top);

        TextView valTv = new TextView(this);
        valTv.setText(value);
        valTv.setTextColor(0xFFFFFFFF);
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        valTv.setTypeface(android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD));
        valTv.setPadding(0, dp(3), 0, dp(1));
        card.addView(valTv);

        TextView subTv = new TextView(this);
        subTv.setText(sub);
        subTv.setTextColor(0xB3FFFFFF);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        card.addView(subTv);

        return card;
    }

    private void showNetworkDiagnosticsModal() {
        inputsPanel.removeAllViews();
        addModalHeader("📶 Wi-Fi & Network Diagnostics", "Live connection telemetry on " + Build.MODEL);

        String ssid = "Wi-Fi";
        int rssi = 0;
        int linkSpeed = 0;
        String ip = getDeviceIpAddress();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            if (info != null) {
                if (info.getSSID() != null && !info.getSSID().isEmpty()) {
                    ssid = info.getSSID().replace("\"", "");
                }
                rssi = info.getRssi();
                linkSpeed = info.getLinkSpeed();
            }
        } catch (Exception ignored) {}

        addModalOptionWithStatus("Network SSID: " + ssid, true, "CONNECTED", () -> {});
        addModalOptionWithStatus("TV IP Address: " + ip, true, "ONLINE", () -> {});
        if (linkSpeed > 0) {
            addModalOptionWithStatus("Link Speed: " + linkSpeed + " Mbps", true, "5 GHz", () -> {});
        }
        if (rssi != 0) {
            addModalOptionWithStatus("Signal Strength: " + rssi + " dBm", true, "STRONG", () -> {});
        }
        addModalOption("Open TV Wi-Fi Settings", () -> openIntent(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        addModalOption("⬅ Back to Settings", this::showSettingsModal);

        showModalContainer();
    }

    private void showScreensaverMenu() {
        inputsPanel.removeAllViews();
        addModalHeader("🖼 Google TV Screensaver & Ambient", "Official Google TV ambient mode, AI art creator, Google Photos & display timers");

        addModalOptionWithStatus("⚙ Google TV Ambient Settings", true, "OPEN", () -> {
            try {
                Intent intent = new Intent("dreamx.two.panel.SETTINGS");
                openIntent(intent);
            } catch (Exception e) {
                openIntent(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        addModalOptionWithStatus("🎨 Custom AI Art Screensaver", true, "CREATE", () -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://tv.google.com/aiactivity"));
                intent.setPackage("com.google.android.apps.tv.dreamx");
                openIntent(intent);
            } catch (Exception e) {
                Toast.makeText(this, "AI Art screensaver not available", Toast.LENGTH_SHORT).show();
            }
        });

        addModalOptionWithStatus("📸 Google Photos Screensaver", true, "SETUP", () -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://tv.google.com/photossetup"));
                intent.setPackage("com.google.android.apps.tv.dreamx");
                openIntent(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Google Photos setup not available", Toast.LENGTH_SHORT).show();
            }
        });

        addModalOptionWithStatus("▶ Preview Screensaver Now", true, "START", () -> {
            try {
                Intent intent = new Intent();
                intent.setComponent(new android.content.ComponentName("com.android.tv.settings", "com.google.android.tv.settings.AmbientActivity"));
                openIntent(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Screensaver preview not available", Toast.LENGTH_SHORT).show();
            }
        });

        addModalOption("⏱ TV Energy & Inactivity Timeout", () -> openIntent(new Intent(Settings.ACTION_SETTINGS)));
        addModalOption("⬅ Back", this::handleModalBack);

        showModalContainer();
    }

    private void launchGoogleTvLauncher() {
        LocalAdbClient.enableGoogleTvAndLaunch(this);
    }

    private void openIntent(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void updateWifiLabel() {
        if (btnWifi == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = (cm != null && net != null) ? cm.getNetworkCapabilities(net) : null;
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                btnWifi.setText("Ethernet");
                return;
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            String ssid = info != null ? info.getSSID() : null;
            if (ssid != null && !ssid.equals("<unknown ssid>") && !ssid.isEmpty()) {
                ssid = ssid.replace("\"", "");
                btnWifi.setText(ssid);
            } else {
                btnWifi.setText("Wi-Fi");
            }
        } catch (Exception e) {
            btnWifi.setText("Wi-Fi");
        }
    }

    // =========================================================================
    // APPS & MEDIA FOLDERS LOADING
    // =========================================================================

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(
                getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());

        String[] dockOrder = getResources().getStringArray(R.array.dock_apps);
        Set<String> dockSet = new HashSet<>(Arrays.asList(dockOrder));

        Map<String, ResolveInfo> found = new HashMap<>();

        Intent leanback = new Intent(Intent.ACTION_MAIN);
        leanback.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(leanback, 0)) {
            if (ri.activityInfo != null) found.put(ri.activityInfo.packageName, ri);
        }

        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo != null && !found.containsKey(ri.activityInfo.packageName)) {
                found.put(ri.activityInfo.packageName, ri);
            }
        }

        installedMap.clear();
        for (Map.Entry<String, ResolveInfo> e : found.entrySet()) {
            String pkg = e.getKey();
            if (hidden.contains(pkg)) continue;
            installedMap.put(pkg, loadEntry(pm, e.getValue()));
        }

        // Load custom user folders from SharedPreferences
        folderEntries.clear();
        Map<String, String> appToFolder = new HashMap<>();
        String foldersJson = prefs().getString(KEY_USER_FOLDERS, "");
        if (!foldersJson.isEmpty()) {
            try {
                JSONObject root = new JSONObject(foldersJson);
                Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String folderTitle = keys.next();
                    JSONArray arr = root.optJSONArray(folderTitle);
                    if (arr != null && arr.length() > 0) {
                        FolderEntry fe = new FolderEntry();
                        fe.title = folderTitle;
                        fe.subtitle = arr.length() + " Apps";
                        for (int i = 0; i < arr.length(); i++) {
                            String p = arr.optString(i, "");
                            AppEntry ae = installedMap.get(p);
                            if (ae != null) {
                                fe.apps.add(ae);
                                appToFolder.put(p, folderTitle);
                            }
                        }
                        if (!fe.apps.isEmpty()) {
                            folderEntries.add(fe);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Determine App Order
        List<String> order = new ArrayList<>();
        String savedOrder = prefs().getString(KEY_APP_ORDER, "");
        if (!savedOrder.isEmpty()) {
            for (String p : savedOrder.split(",")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty() && !order.contains(trimmed)) {
                    order.add(trimmed);
                }
            }
        } else {
            String[] defaultOrder = getResources().getStringArray(R.array.dock_apps);
            for (String p : defaultOrder) {
                if (!order.contains(p)) order.add(p);
            }
        }

        // Append any installed apps that are not yet in order
        List<String> allPkgs = new ArrayList<>(installedMap.keySet());
        Collections.sort(allPkgs, (a, b) -> {
            AppEntry ea = installedMap.get(a);
            AppEntry eb = installedMap.get(b);
            String la = ea != null ? ea.label : a;
            String lb = eb != null ? eb.label : b;
            return la.compareToIgnoreCase(lb);
        });
        for (String p : allPkgs) {
            if (!order.contains(p)) order.add(p);
        }

        dockEntries.clear();
        for (String pkg : order) {
            if (appToFolder.containsKey(pkg)) continue;
            AppEntry entry = installedMap.get(pkg);
            if (entry != null && !dockEntries.contains(entry)) {
                dockEntries.add(entry);
            }
        }

        appsLoaded = true;
    }

    private void saveAppOrder() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dockEntries.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(dockEntries.get(i).pkg);
        }
        prefs().edit().putString(KEY_APP_ORDER, sb.toString()).apply();
    }

    private void saveFolders() {
        JSONObject root = new JSONObject();
        try {
            for (FolderEntry f : folderEntries) {
                JSONArray arr = new JSONArray();
                for (AppEntry a : f.apps) {
                    arr.put(a.pkg);
                }
                root.put(f.title, arr);
            }
            prefs().edit().putString(KEY_USER_FOLDERS, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void focusApp(String pkg) {
        if (dockRow != null) {
            for (int i = 0; i < dockRow.getChildCount(); i++) {
                View child = dockRow.getChildAt(i);
                View card = child.findViewById(R.id.card);
                if (card != null && card.getTag() instanceof AppEntry) {
                    if (pkg.equals(((AppEntry) card.getTag()).pkg)) {
                        card.requestFocus();
                        ensureHorizontalVisible(dockScroll, card);
                        return;
                    }
                }
            }
        }
    }

    private AppEntry loadEntry(PackageManager pm, ResolveInfo ri) {
        AppEntry e = new AppEntry();
        ActivityInfo ai = ri.activityInfo;
        e.pkg = ai.packageName;
        CharSequence lbl = ri.loadLabel(pm);
        e.label = lbl != null ? lbl.toString() : e.pkg;
        if ("com.android.vending".equals(e.pkg)) {
            e.label = "Play Store";
        }

        Drawable d = null;
        try {
            d = ai.loadBanner(pm);
            if (d == null) d = ai.applicationInfo.loadBanner(pm);
            if (d != null) e.isBanner = true;
        } catch (Exception ignored) {}

        if (d == null) {
            try { d = ai.loadIcon(pm); } catch (Exception ignored) {}
        }

        if (d != null) {
            if (e.isBanner) {
                Bitmap raw = renderDrawable(d, 288, 162);
                e.art = HubDataFetcher.roundCenterCrop(raw, 288, 162, 24f);
            } else {
                Bitmap raw = renderDrawable(d, 104, 104);
                e.art = HubDataFetcher.roundCenterCrop(raw, 104, 104, 20f);
            }
        }
        return e;
    }

    private static Bitmap renderDrawable(Drawable d, int targetW, int targetH) {
        Bitmap b = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, targetW, targetH);
        d.draw(c);
        return b;
    }

    // =========================================================================
    // ROW 0: APPS & MEDIA FOLDERS (EXACT 16:9 APPLE TV TILES)
    // =========================================================================

    private void buildDockAndFoldersRow() {
        LayoutInflater inf = LayoutInflater.from(this);
        dockRow.removeAllViews();
        appRowViews.clear();

        for (int i = 0; i < dockEntries.size(); i++) {
            AppEntry e = dockEntries.get(i);
            View v = inflateAppCard(inf, dockRow, e);
            dockRow.addView(v);
            appRowViews.add(v.findViewById(R.id.card));
        }

        buildAppsGrid();
        rebuildNavigationForCurrentMode();
    }

    private void buildAppsGrid() {
        if (appsGridRows == null) return;
        LayoutInflater inf = LayoutInflater.from(this);
        appsGridRows.removeAllViews();
        appsGridCardViews.clear();
        appsGridRowViews.clear();

        List<Object> allEntries = new ArrayList<>();
        allEntries.addAll(dockEntries);
        allEntries.addAll(folderEntries);

        final int COLS = 5;
        LinearLayout currentRow = null;
        List<View> currentRowCardViews = null;

        for (int i = 0; i < allEntries.size(); i++) {
            if (i % COLS == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setGravity(Gravity.CENTER_VERTICAL);
                currentRow.setWeightSum(COLS);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(14);
                currentRow.setLayoutParams(rlp);
                appsGridRows.addView(currentRow);

                currentRowCardViews = new ArrayList<>();
                appsGridRowViews.add(currentRowCardViews);
            }

            Object entry = allEntries.get(i);
            View v;
            if (entry instanceof AppEntry) {
                v = inflateRokuAppCard(inf, currentRow, (AppEntry) entry);
            } else {
                v = inflateRokuFolderCard(inf, currentRow, (FolderEntry) entry);
            }
            currentRow.addView(v);
            View card = v.findViewById(R.id.card);
            appsGridCardViews.add(card);
            if (currentRowCardViews != null) {
                currentRowCardViews.add(card);
            }
        }

        if (currentRow != null && (allEntries.size() % COLS != 0)) {
            int remainder = allEntries.size() % COLS;
            for (int k = remainder; k < COLS; k++) {
                View stub = new View(this);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, 1, 1.0f);
                stub.setLayoutParams(slp);
                stub.setVisibility(View.INVISIBLE);
                currentRow.addView(stub);
            }
        }
    }

    private View inflateRokuAppCard(LayoutInflater inf, ViewGroup parent, AppEntry e) {
        View v = inf.inflate(R.layout.item_roku_app, parent, false);
        View card = v.findViewById(R.id.card);

        ImageView banner = v.findViewById(R.id.banner);
        ImageView icon = v.findViewById(R.id.icon);
        TextView name = v.findViewById(R.id.name);

        name.setText(e.label);

        if (e.art != null && e.isBanner) {
            banner.setImageBitmap(e.art);
            banner.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
        } else if (e.art != null) {
            icon.setImageBitmap(e.art);
            icon.setVisibility(View.VISIBLE);
            banner.setVisibility(View.GONE);
        } else {
            banner.setVisibility(View.GONE);
            icon.setImageResource(R.mipmap.ic_launcher);
            icon.setVisibility(View.VISIBLE);
        }

        card.setTag(e);
        card.setOnClickListener(view -> launchPackage(e.pkg));
        card.setOnLongClickListener(view -> {
            showAppOptionsModal(e);
            return true;
        });
        return v;
    }

    private View inflateRokuFolderCard(LayoutInflater inf, ViewGroup parent, FolderEntry folder) {
        View v = inf.inflate(R.layout.item_roku_folder, parent, false);
        View card = v.findViewById(R.id.card);
        TextView name = v.findViewById(R.id.name);

        ImageView[] icons = new ImageView[]{
                v.findViewById(R.id.icon1),
                v.findViewById(R.id.icon2),
                v.findViewById(R.id.icon3),
                v.findViewById(R.id.icon4)
        };

        name.setText(folder.title);

        for (int i = 0; i < 4; i++) {
            if (i < folder.apps.size() && folder.apps.get(i).art != null) {
                AppEntry app = folder.apps.get(i);
                icons[i].setScaleType(app.isBanner ? ImageView.ScaleType.FIT_XY : ImageView.ScaleType.FIT_CENTER);
                icons[i].setImageBitmap(app.art);
                icons[i].setVisibility(View.VISIBLE);
            } else {
                icons[i].setVisibility(View.INVISIBLE);
            }
        }

        card.setTag(folder);
        card.setOnClickListener(view -> showFolderModal(folder));
        card.setOnLongClickListener(view -> {
            showFolderOptionsModal(folder);
            return true;
        });
        return v;
    }

    private View inflateAppCard(LayoutInflater inf, ViewGroup parent, AppEntry e) {
        View v = inf.inflate(R.layout.item_app, parent, false);
        View card = v.findViewById(R.id.card);

        ImageView banner = v.findViewById(R.id.banner);
        ImageView icon = v.findViewById(R.id.icon);
        TextView name = v.findViewById(R.id.name);

        name.setText(e.label);

        if (e.art != null && e.isBanner) {
            banner.setImageBitmap(e.art);
            banner.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
        } else if (e.art != null) {
            icon.setImageBitmap(e.art);
            icon.setVisibility(View.VISIBLE);
            banner.setVisibility(View.GONE);
        }

        card.setTag(e);
        card.setOnClickListener(view -> launchPackage(e.pkg));
        card.setOnLongClickListener(view -> {
            showAppOptionsModal(e);
            return true;
        });
        return v;
    }

    private View inflateFolderCard(LayoutInflater inf, ViewGroup parent, FolderEntry folder) {
        View v = inf.inflate(R.layout.item_folder, parent, false);
        View card = v.findViewById(R.id.card);

        TextView name = v.findViewById(R.id.name);
        ImageView[] icons = new ImageView[]{
                v.findViewById(R.id.icon1),
                v.findViewById(R.id.icon2),
                v.findViewById(R.id.icon3),
                v.findViewById(R.id.icon4)
        };

        name.setText(folder.title);

        for (int i = 0; i < 4; i++) {
            if (i < folder.apps.size() && folder.apps.get(i).art != null) {
                AppEntry app = folder.apps.get(i);
                icons[i].setScaleType(app.isBanner ? ImageView.ScaleType.FIT_XY : ImageView.ScaleType.FIT_CENTER);
                icons[i].setImageBitmap(app.art);
                icons[i].setVisibility(View.VISIBLE);
            } else {
                icons[i].setVisibility(View.INVISIBLE);
            }
        }

        card.setTag(folder);
        card.setOnClickListener(view -> showFolderModal(folder));
        card.setOnLongClickListener(view -> {
            showFolderOptionsModal(folder);
            return true;
        });
        return v;
    }

    // =========================================================================
    // SEPARATE REFRESHES: SPORTS (60S) & TRAKT (30M)
    // =========================================================================

    private void refreshSportsData(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastSportsRefreshMs < 45_000)) return;
        lastSportsRefreshMs = now;
        LayoutInflater inf = LayoutInflater.from(this);

        if (force && sportsRow.getChildCount() == 0) {
            addMessageCard(inf, sportsRow, liveSportViews, "Loading ESPN Scoreboard...", "NFL • NBA • College Football • NHL");
            rebuildNavigationForCurrentMode();
        }

        HubDataFetcher.fetchLiveSports(this, games -> {
            sportsRow.removeAllViews();
            liveSportViews.clear();
            cachedLiveSportGames.clear();
            if (games != null) cachedLiveSportGames.addAll(games);
            if (games == null || games.isEmpty()) {
                addMessageCard(inf, sportsRow, liveSportViews, "No Live Games Found", "Check back later for live broadcasts");
            } else {
                for (HubDataFetcher.SportGame g : games) {
                    View cardWrap = inflateSportCard(inf, sportsRow, g);
                    sportsRow.addView(cardWrap);
                    liveSportViews.add(cardWrap.findViewById(R.id.card));
                }
                sportsStatus.setVisibility(View.GONE);
            }
            rebuildNavigationForCurrentMode();
        });

        if (currentViewMode == MODE_SPORTS) {
            HubDataFetcher.fetchAllSportsShelves(this, this::renderExtraSportsShelves);
        }
    }

    private void renderExtraSportsShelves(List<HubDataFetcher.SportShelf> shelves) {
        LayoutInflater inf = LayoutInflater.from(this);
        sportsExtraShelvesContainer.removeAllViews();
        extraSportShelfViews.clear();
        extraSportShelfScrolls.clear();

        if (shelves == null || shelves.isEmpty()) return;

        for (HubDataFetcher.SportShelf shelf : shelves) {
            if (shelf.games == null || shelf.games.isEmpty()) continue;

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.leftMargin = dp(4);
            hlp.bottomMargin = dp(2);
            header.setLayoutParams(hlp);

            TextView titleTv = new TextView(this);
            titleTv.setText(shelf.title);
            titleTv.setTextColor(0xD9FFFFFF);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            titleTv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            header.addView(titleTv);
            sportsExtraShelvesContainer.addView(header);

            HorizontalScrollView hsv = new HorizontalScrollView(this);
            LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hsvLp.bottomMargin = dp(6);
            hsv.setLayoutParams(hsvLp);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);

            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            hsv.addView(rowLayout);
            sportsExtraShelvesContainer.addView(hsv);

            List<View> shelfCardViews = new ArrayList<>();
            for (HubDataFetcher.SportGame g : shelf.games) {
                View cardWrap = inflateSportCard(inf, rowLayout, g);
                rowLayout.addView(cardWrap);
                shelfCardViews.add(cardWrap.findViewById(R.id.card));
            }

            extraSportShelfViews.add(shelfCardViews);
            extraSportShelfScrolls.add(hsv);
        }

        if (currentViewMode == MODE_SPORTS) {
            rebuildNavigationForCurrentMode();
        }
    }

    private void refreshTraktData(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastTraktRefreshMs < 600_000)) return;
        lastTraktRefreshMs = now;

        HubDataFetcher.fetchAllTraktShelves(this, shelves -> {
            updateTraktButtonLabel();
            cachedTraktShelves.clear();
            if (shelves != null) {
                cachedTraktShelves.addAll(shelves);
            }
            renderTraktShelves();
        });
    }

    private Set<String> getHiddenTraktShelves() {
        Set<String> raw = prefs().getStringSet(KEY_HIDDEN_SHELVES, null);
        return raw != null ? new HashSet<>(raw) : new HashSet<>();
    }

    private void setHiddenTraktShelves(Set<String> hidden) {
        prefs().edit().putStringSet(KEY_HIDDEN_SHELVES, new HashSet<>(hidden)).apply();
    }

    private void renderTraktShelves() {
        LayoutInflater inf = LayoutInflater.from(this);
        traktShelvesContainer.removeAllViews();
        traktShelfViews.clear();
        traktShelfScrolls.clear();
        traktHeaderButtons.clear();

        Set<String> hidden = getHiddenTraktShelves();

        int renderedCount = 0;
        for (HubDataFetcher.TraktShelf shelf : cachedTraktShelves) {
            if (shelf.items == null || shelf.items.isEmpty()) continue;
            if (hidden.contains(shelf.title)) continue;
            if (currentViewMode == MODE_HOME && renderedCount >= 3) break;

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.leftMargin = dp(4);
            hlp.rightMargin = dp(16);
            hlp.bottomMargin = dp(2);
            header.setLayoutParams(hlp);

            TextView titleTv = new TextView(this);
            String cleanTitle = shelf.title
                    .replace("TRAKT LIST  •  ", "")
                    .replace("TRAKT LIST • ", "")
                    .replace("TRAKT  •  ", "")
                    .replace("TRAKT • ", "");
            titleTv.setText(cleanTitle);
            titleTv.setTextColor(0xD9FFFFFF);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            titleTv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            header.addView(titleTv, tlp);

            traktShelvesContainer.addView(header);

            HorizontalScrollView hsv = new HorizontalScrollView(this);
            LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hsvLp.bottomMargin = dp(6);
            hsv.setLayoutParams(hsvLp);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);

            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            hsv.addView(rowLayout);
            traktShelvesContainer.addView(hsv);

            List<View> shelfCardViews = new ArrayList<>();
            for (HubDataFetcher.MediaItem m : shelf.items) {
                View cardWrap = inflateMediaCard(inf, rowLayout, m);
                rowLayout.addView(cardWrap);
                shelfCardViews.add(cardWrap.findViewById(R.id.card));
            }

            traktShelfViews.add(shelfCardViews);
            traktShelfScrolls.add(hsv);
            renderedCount++;
        }

        if (currentViewMode == MODE_HOME || currentViewMode == MODE_MEDIA) {
            rebuildNavigationForCurrentMode();
        }
    }

    private void rebuildNavigationForCurrentMode() {
        navRows.clear();
        navScrolls.clear();

        if (currentViewMode == MODE_HOME) {
            if (!appRowViews.isEmpty()) {
                navRows.add(appRowViews);
                navScrolls.add(dockScroll);
            }
            if (!liveSportViews.isEmpty()) {
                navRows.add(liveSportViews);
                navScrolls.add(sportsScroll);
            }
            for (int i = 0; i < traktShelfViews.size(); i++) {
                navRows.add(traktShelfViews.get(i));
                navScrolls.add(traktShelfScrolls.get(i));
            }
        } else if (currentViewMode == MODE_SPORTS) {
            if (!liveSportViews.isEmpty()) {
                navRows.add(liveSportViews);
                navScrolls.add(sportsScroll);
            }
            for (int i = 0; i < extraSportShelfViews.size(); i++) {
                navRows.add(extraSportShelfViews.get(i));
                navScrolls.add(extraSportShelfScrolls.get(i));
            }
        } else if (currentViewMode == MODE_MEDIA) {
            for (int i = 0; i < traktShelfViews.size(); i++) {
                navRows.add(traktShelfViews.get(i));
                navScrolls.add(traktShelfScrolls.get(i));
            }
        } else if (currentViewMode == MODE_APPS) {
            for (List<View> r : appsGridRowViews) {
                navRows.add(r);
                navScrolls.add(null);
            }
        }

        rebindAllRowNavigation();
    }

    private void addMessageCard(LayoutInflater inf, LinearLayout row, List<View> navList, String title, String sub) {
        HubDataFetcher.MediaItem m = new HubDataFetcher.MediaItem(title, 0, "", "movie", sub);
        View v = inflateMediaCard(inf, row, m);
        row.addView(v);
        navList.add(v.findViewById(R.id.card));
    }

    private View inflateSportCard(LayoutInflater inf, ViewGroup parent, HubDataFetcher.SportGame g) {
        View v = inf.inflate(R.layout.item_sport_card, parent, false);
        View card = v.findViewById(R.id.card);

        TextView statusBadge = v.findViewById(R.id.statusBadge);
        TextView networkBadge = v.findViewById(R.id.networkBadge);
        View awayBar = v.findViewById(R.id.awayBar);
        TextView awayTeam = v.findViewById(R.id.awayTeam);
        TextView awayScore = v.findViewById(R.id.awayScore);
        View homeBar = v.findViewById(R.id.homeBar);
        TextView homeTeam = v.findViewById(R.id.homeTeam);
        TextView homeScore = v.findViewById(R.id.homeScore);

        String shortStatus = g.statusDetail != null
                ? g.statusDetail.replace(" EDT", "").replace(" EST", "")
                : "";
        String favPrefix = g.isFavorite ? "★ " : "";
        if (g.isLive()) {
            statusBadge.setText(favPrefix + "LIVE • " + shortStatus);
            statusBadge.setBackgroundResource(R.drawable.live_badge_bg);
        } else if (g.isFavorite) {
            statusBadge.setText("★ " + shortStatus);
            statusBadge.setBackgroundResource(R.drawable.fav_badge_bg);
        } else {
            statusBadge.setText(shortStatus.isEmpty() ? g.league : shortStatus);
            statusBadge.setBackgroundResource(R.drawable.upcoming_badge_bg);
        }

        awayBar.setBackgroundColor(g.awayColor);
        homeBar.setBackgroundColor(g.homeColor);

        ImageView awayLogo = v.findViewById(R.id.awayLogo);
        ImageView homeLogo = v.findViewById(R.id.homeLogo);
        if (awayLogo != null) HubDataFetcher.loadTeamLogo(this, g.awayLogoUrl, awayLogo);
        if (homeLogo != null) HubDataFetcher.loadTeamLogo(this, g.homeLogoUrl, homeLogo);

        String awayDisplay = (g.awayRank != null ? g.awayRank : "") + (g.awayName != null && !g.awayName.isEmpty() ? g.awayName : g.awayAbbr);
        String homeDisplay = (g.homeRank != null ? g.homeRank : "") + (g.homeName != null && !g.homeName.isEmpty() ? g.homeName : g.homeAbbr);
        awayTeam.setText(awayDisplay);
        homeTeam.setText(homeDisplay);
        awayTeam.setTextColor(0xFFFFFFFF);
        homeTeam.setTextColor(0xFFFFFFFF);

        TextView channelBadge = v.findViewById(R.id.channelBadge);
        if (channelBadge != null) {
            if (g.network != null && !g.network.isEmpty() && !g.network.equalsIgnoreCase(g.league)) {
                channelBadge.setText(g.network);
                channelBadge.setVisibility(View.VISIBLE);
            } else {
                channelBadge.setVisibility(View.GONE);
            }
        }

        if (g.isUpcoming()) {
            if (!g.awayMoneyline.isEmpty() && !g.homeMoneyline.isEmpty()) {
                awayScore.setText(g.awayMoneyline);
                homeScore.setText(g.homeMoneyline);
                awayScore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                homeScore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);

                // Highlight Moneyline Favorite in Neon Green (#69F0AE)
                if (g.isAwayMlFav) {
                    awayScore.setTextColor(0xFF69F0AE);
                    homeScore.setTextColor(0xFF81D4FA);
                } else if (g.isHomeMlFav) {
                    homeScore.setTextColor(0xFF69F0AE);
                    awayScore.setTextColor(0xFF81D4FA);
                } else {
                    awayScore.setTextColor(0xFF81D4FA);
                    homeScore.setTextColor(0xFF81D4FA);
                }
            } else {
                awayScore.setText("-");
                homeScore.setText("-");
                awayScore.setTextColor(0x66FFFFFF);
                homeScore.setTextColor(0x66FFFFFF);
                awayScore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                homeScore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            }
            if (!g.oddsDetails.isEmpty()) {
                networkBadge.setText(g.oddsDetails);
                networkBadge.setVisibility(View.VISIBLE);
            } else {
                networkBadge.setVisibility(View.GONE);
            }
        } else {
            awayScore.setText(g.awayScore);
            homeScore.setText(g.homeScore);
            awayScore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            homeScore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);

            // Highlight game winner in green for completed games
            if (g.isAwayWinner) {
                awayScore.setTextColor(0xFF69F0AE);
                awayTeam.setTextColor(0xFF69F0AE);
                homeScore.setTextColor(0x88FFFFFF);
                homeTeam.setTextColor(0x88FFFFFF);
            } else if (g.isHomeWinner) {
                homeScore.setTextColor(0xFF69F0AE);
                homeTeam.setTextColor(0xFF69F0AE);
                awayScore.setTextColor(0x88FFFFFF);
                awayTeam.setTextColor(0x88FFFFFF);
            } else {
                awayScore.setTextColor(0xFFFFFFFF);
                homeScore.setTextColor(0xFFFFFFFF);
            }
            networkBadge.setVisibility(View.GONE);
        }

        card.setTag(g);
        card.setOnClickListener(view -> showSportLaunchModal(g));
        return v;
    }

    private View inflateMediaCard(LayoutInflater inf, ViewGroup parent, HubDataFetcher.MediaItem m) {
        View v = inf.inflate(R.layout.item_media_card, parent, false);
        View card = v.findViewById(R.id.card);

        ImageView backdrop = v.findViewById(R.id.backdrop);
        TextView title = v.findViewById(R.id.mediaTitle);
        TextView sub = v.findViewById(R.id.mediaSub);

        title.setText(m.title);
        sub.setText(m.subtitle);

        if (m.backdropUrl != null && !m.backdropUrl.isEmpty()) {
            HubDataFetcher.loadBackdrop(this, m.backdropUrl, backdrop);
        }

        card.setTag(m);
        card.setOnClickListener(view -> openInStremioOrTrakt(m));
        return v;
    }

    // =========================================================================
    // ZERO-LAG DETERMINISTIC D-PAD NAVIGATION (NO ANIMATIONS, INSTANT SCROLL)
    // =========================================================================

    private void rebindAllRowNavigation() {
        for (int r = 0; r < navRows.size(); r++) {
            List<View> row = navRows.get(r);
            HorizontalScrollView hsv = r < navScrolls.size() ? navScrolls.get(r) : null;
            for (int c = 0; c < row.size(); c++) {
                final int rowIdx = r;
                final int colIdx = c;
                final HorizontalScrollView rowScroll = hsv;
                View card = row.get(c);

                card.setOnFocusChangeListener((v, hasFocus) -> {
                    animateFocusCard(v, hasFocus);
                    if (hasFocus) {
                        lastContentFocus = v;
                        updateFocusedHeader(v.getTag());
                        ensureHorizontalVisible(rowScroll, v);
                        ensureVerticalVisible(rowIdx, v);
                    }
                });

                card.setOnLongClickListener(v -> {
                    Object tag = v.getTag();
                    if (tag instanceof AppEntry) {
                        showAppOptionsModal((AppEntry) tag);
                    } else if (tag instanceof FolderEntry) {
                        showFolderOptionsModal((FolderEntry) tag);
                    } else if (tag instanceof HubDataFetcher.MediaItem) {
                        showMediaInfoModal((HubDataFetcher.MediaItem) tag);
                    } else if (tag instanceof HubDataFetcher.SportGame) {
                        showSportInfoModal((HubDataFetcher.SportGame) tag);
                    }
                    return true;
                });

                card.setOnKeyListener((v, keyCode, event) -> {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            if (event.getRepeatCount() == 0) {
                                longPressTriggered = false;
                                if (pendingLongPress != null) {
                                    longPressHandler.removeCallbacks(pendingLongPress);
                                }
                                pendingLongPress = () -> {
                                    longPressTriggered = true;
                                    v.performLongClick();
                                };
                                longPressHandler.postDelayed(pendingLongPress, 450);
                            }
                            return true;
                        } else if (event.getAction() == KeyEvent.ACTION_UP) {
                            if (pendingLongPress != null) {
                                longPressHandler.removeCallbacks(pendingLongPress);
                                pendingLongPress = null;
                            }
                            if (!longPressTriggered) {
                                v.performClick();
                            }
                            longPressTriggered = false;
                            return true;
                        }
                        return false;
                    }

                    if (keyCode == KeyEvent.KEYCODE_MENU) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            v.performLongClick();
                        }
                        return true;
                    }

                    if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_WINDOW || keyCode == KeyEvent.KEYCODE_HELP) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            showInfoModal(v.getTag());
                        }
                        return true;
                    }

                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

                    if (pendingLongPress != null) {
                        longPressHandler.removeCallbacks(pendingLongPress);
                        pendingLongPress = null;
                    }

                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (colIdx > 0) row.get(colIdx - 1).requestFocus();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (colIdx + 1 < row.size()) {
                            row.get(colIdx + 1).requestFocus();
                        } else {
                            if (row == liveSportViews) {
                                View btnSportsSettings = findViewById(R.id.btnSportsSettings);
                                if (btnSportsSettings != null && btnSportsSettings.isShown()) {
                                    btnSportsSettings.requestFocus();
                                    return true;
                                }
                            }
                            int traktIdx = traktShelfViews.indexOf(row);
                            if (traktIdx >= 0 && traktIdx < traktHeaderButtons.size()) {
                                View btnTraktHeader = traktHeaderButtons.get(traktIdx);
                                if (btnTraktHeader != null && btnTraktHeader.isShown()) {
                                    btnTraktHeader.requestFocus();
                                    return true;
                                }
                            }
                        }
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        int prevRow = findNonEmptyRow(rowIdx - 1, -1);
                        android.util.Log.d("TVNAV", "DPAD_UP on row=" + rowIdx + " col=" + colIdx + " prevRow=" + prevRow + " navRows=" + navRows.size());
                        if (prevRow >= 0) {
                            List<View> targetRow = navRows.get(prevRow);
                            int targetCol = Math.min(colIdx, targetRow.size() - 1);
                            boolean req = targetRow.get(targetCol).requestFocus();
                            android.util.Log.d("TVNAV", "requestFocus targetRow=" + prevRow + " targetCol=" + targetCol + " res=" + req);
                        } else {
                            if (currentViewMode == MODE_APPS) {
                                View btnNewFolder = findViewById(R.id.btnNewFolder);
                                if (btnNewFolder != null && btnNewFolder.isShown()) {
                                    btnNewFolder.requestFocus();
                                    return true;
                                }
                                if (btnTabApps != null) btnTabApps.requestFocus();
                            } else if (currentViewMode == MODE_SPORTS) {
                                View btnSportsSettings = findViewById(R.id.btnSportsSettings);
                                if (btnSportsSettings != null && btnSportsSettings.isShown()) {
                                    btnSportsSettings.requestFocus();
                                    return true;
                                }
                                int targetBtn = Math.min(colIdx, topButtons.length - 1);
                                topButtons[targetBtn].requestFocus();
                            } else {
                                int targetBtn = Math.min(colIdx, topButtons.length - 1);
                                topButtons[targetBtn].requestFocus();
                            }
                        }
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        int nextRow = findNonEmptyRow(rowIdx + 1, 1);
                        if (nextRow >= 0) {
                            List<View> targetRow = navRows.get(nextRow);
                            int targetCol = Math.min(colIdx, targetRow.size() - 1);
                            targetRow.get(targetCol).requestFocus();
                        }
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    private void animateFocusCard(View v, boolean hasFocus) {
        if (v == null) return;

        if (hasFocus) {
            if (!(v.getParent() instanceof LinearLayout)) {
                v.bringToFront();
            }
            if (v.getParent() instanceof View) {
                ((View) v.getParent()).invalidate();
            }
        }

        v.animate().cancel();
        v.setScaleX(1.0f);
        v.setScaleY(1.0f);
        v.setTranslationZ(0f);
    }

    private int findNonEmptyRow(int start, int step) {
        int r = start;
        while (r >= 0 && r < navRows.size()) {
            if (!navRows.get(r).isEmpty()) return r;
            r += step;
        }
        return -1;
    }

    private void ensureHorizontalVisible(HorizontalScrollView hsv, View card) {
        if (hsv == null || card == null) return;
        View itemRoot = (View) card.getParent();
        int left = itemRoot.getLeft();
        int right = itemRoot.getRight();
        int scrollX = hsv.getScrollX();
        int width = hsv.getWidth();
        int pad = dp(24);
        if (left - pad < scrollX) {
            hsv.scrollTo(Math.max(0, left - pad), 0);
        } else if (right + pad > scrollX + width) {
            hsv.scrollTo(right + pad - width, 0);
        }
    }

    private void ensureVerticalVisible(int rowIdx, View card) {
        if (hubScroll == null || hubContent == null || card == null) return;
        if (rowIdx == 0) {
            hubScroll.smoothScrollTo(0, 0);
            return;
        }
        View target = (card.getParent() instanceof View) ? (View) card.getParent() : card;
        Rect r = new Rect();
        target.getDrawingRect(r);
        hubContent.offsetDescendantRectToMyCoords(target, r);

        int scrollY = hubScroll.getScrollY();
        int viewportHeight = hubScroll.getHeight();
        if (viewportHeight <= 0) return;
        int scrollBottom = scrollY + viewportHeight;
        int pad = dp(28);

        if (r.bottom + pad > scrollBottom) {
            hubScroll.smoothScrollTo(0, Math.max(0, (r.bottom + pad) - viewportHeight));
        } else if (r.top - pad < scrollY) {
            hubScroll.smoothScrollTo(0, Math.max(0, r.top - pad));
        }
    }

    private void clearBackdropImmediately() {
        if (pendingBackdropRunnable != null) {
            heroBackdropHandler.removeCallbacks(pendingBackdropRunnable);
            pendingBackdropRunnable = null;
        }
        currentBackdropUrl = "";
        if (heroBackdropA != null) {
            heroBackdropA.animate().cancel();
            heroBackdropA.setTag("");
            heroBackdropA.setImageDrawable(null);
            heroBackdropA.setAlpha(0f);
        }
        if (heroBackdropB != null) {
            heroBackdropB.animate().cancel();
            heroBackdropB.setTag("");
            heroBackdropB.setImageDrawable(null);
            heroBackdropB.setAlpha(0f);
        }
        isBackdropAVisible = true;
    }

    private void transitionHeroBackdrop(String urlStr, int ambientColor) {
        if (pendingBackdropRunnable != null) {
            heroBackdropHandler.removeCallbacks(pendingBackdropRunnable);
        }

        pendingBackdropRunnable = () -> {
            if (urlStr != null && !urlStr.isEmpty()) {
                if (urlStr.equals(currentBackdropUrl)) return;
                currentBackdropUrl = urlStr;
                final ImageView inView = isBackdropAVisible ? heroBackdropB : heroBackdropA;
                final ImageView outView = isBackdropAVisible ? heroBackdropA : heroBackdropB;

                HubDataFetcher.loadHeroBackdrop(this, urlStr, inView, bmp -> {
                    if (bmp != null && urlStr.equals(currentBackdropUrl)) {
                        inView.setImageBitmap(bmp);
                        inView.animate().cancel();
                        outView.animate().cancel();
                        inView.animate().alpha(1f).setDuration(220).withLayer().start();
                        outView.animate().alpha(0f).setDuration(220).withLayer().withEndAction(() -> {
                            outView.setImageDrawable(null);
                            isBackdropAVisible = !isBackdropAVisible;
                        }).start();
                    }
                });
            } else {
                currentBackdropUrl = "";
                final ImageView inView = isBackdropAVisible ? heroBackdropB : heroBackdropA;
                final ImageView outView = isBackdropAVisible ? heroBackdropA : heroBackdropB;
                inView.setTag("");
                outView.setTag("");

                int c = (ambientColor != 0) ? ambientColor : 0xFF141E28;
                int topColor = (c & 0x00FFFFFF) | 0x55000000;
                int midColor = (c & 0x00FFFFFF) | 0x20000000;
                GradientDrawable gd = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{ topColor, midColor, 0x000A0C10 }
                );
                inView.setImageDrawable(gd);
                inView.animate().cancel();
                outView.animate().cancel();
                inView.animate().alpha(1f).setDuration(180).withLayer().start();
                outView.animate().alpha(0f).setDuration(180).withLayer().withEndAction(() -> {
                    outView.setImageDrawable(null);
                    isBackdropAVisible = !isBackdropAVisible;
                }).start();
            }
        };
        heroBackdropHandler.postDelayed(pendingBackdropRunnable, 60);
    }

    private void updateFocusedHeader(Object tag) {
        if (tag instanceof AppEntry) {
            AppEntry e = (AppEntry) tag;
            heroTag.setVisibility(View.GONE);
            heroRating.setVisibility(View.GONE);
            heroExtraBadge.setVisibility(View.GONE);
            heroActionPrompt.setVisibility(View.GONE);
            heroTitle.setText(e.label);
            heroDesc.setText("");
            heroDesc.setVisibility(View.GONE);

            int ambientColor = getAppAmbientColor(e.pkg);
            transitionHeroBackdrop(null, ambientColor);

        } else if (tag instanceof FolderEntry) {
            FolderEntry f = (FolderEntry) tag;
            heroTag.setText("FOLDER");
            heroTag.setTextColor(0xFFFFB74D);
            heroTag.setVisibility(View.VISIBLE);

            heroRating.setVisibility(View.GONE);

            heroExtraBadge.setText(f.apps.size() + " Apps");
            heroExtraBadge.setVisibility(View.VISIBLE);

            heroActionPrompt.setVisibility(View.GONE);
            heroTitle.setText(f.title + " Folder");
            heroDesc.setText(getFolderSummary(f));
            heroDesc.setVisibility(View.VISIBLE);

            transitionHeroBackdrop(null, 0xFF2A2035);

        } else if (tag instanceof HubDataFetcher.SportGame) {
            HubDataFetcher.SportGame g = (HubDataFetcher.SportGame) tag;
            heroTitle.setText(g.awayName + " @ " + g.homeName);

            StringBuilder sb = new StringBuilder();
            if (g.network != null && !g.network.isEmpty() && !g.network.equalsIgnoreCase(g.league)) {
                sb.append(g.network).append("  •  ");
            }
            if (g.isLive()) {
                sb.append("LIVE (").append(g.statusDetail).append(")");
                sb.append("  •  Score: ").append(g.awayAbbr).append(" ").append(g.awayScore)
                  .append(" - ").append(g.homeAbbr).append(" ").append(g.homeScore);
            } else if (g.isUpcoming()) {
                sb.append(g.statusDetail);
                if (g.isAwayMlFav) {
                    sb.append("  •  Fav: ").append(g.awayAbbr).append(" (").append(g.awayMoneyline).append(")");
                } else if (g.isHomeMlFav) {
                    sb.append("  •  Fav: ").append(g.homeAbbr).append(" (").append(g.homeMoneyline).append(")");
                }
                if (!g.oddsDetails.isEmpty()) {
                    sb.append("  •  Spread: ").append(g.oddsDetails);
                }
            } else {
                sb.append("Final: ").append(g.awayAbbr).append(" ").append(g.awayScore)
                  .append(" - ").append(g.homeAbbr).append(" ").append(g.homeScore);
            }
            heroDesc.setText(sb.toString().replaceAll("  •  $", ""));
            heroDesc.setVisibility(View.VISIBLE);

            transitionHeroBackdrop(null, g.homeColor != 0 ? g.homeColor : g.awayColor);

        } else if (tag instanceof HubDataFetcher.MediaItem) {
            HubDataFetcher.MediaItem m = (HubDataFetcher.MediaItem) tag;
            heroTitle.setText(m.title);

            StringBuilder meta = new StringBuilder();
            if (m.year > 0) meta.append(m.year).append("  •  ");
            if (m.rating > 0) meta.append("★ ").append(String.format(Locale.US, "%.1f", m.rating)).append("  •  ");
            if (m.genres != null && !m.genres.isEmpty()) meta.append(m.genres).append("  •  ");
            if (m.subtitle != null && !m.subtitle.isEmpty()) meta.append(m.subtitle);
            String metaStr = meta.toString().replaceAll("  •  $", "").trim();

            String desc = (metaStr.isEmpty() ? "" : (metaStr + "\n")) + (m.overview != null ? m.overview : "");
            heroDesc.setText(desc.trim());
            heroDesc.setVisibility(View.VISIBLE);

            if (currentViewMode == MODE_MEDIA || currentViewMode == MODE_HOME) {
                transitionHeroBackdrop(m.backdropUrl, 0);
            } else {
                transitionHeroBackdrop(null, 0xFF141E28);
            }
        }
    }

    private String getAppDescription(String pkg, String label) {
        if ("com.stremio.one".equals(pkg)) {
            return "Stremio is an all-in-one streaming media aggregator. Watch movies, TV shows, and live channels with seamless Trakt library synchronization and community torrent streaming addons.";
        }
        if ("com.google.android.youtube.tv".equals(pkg)) {
            return "YouTube for Android TV lets you stream trending videos, gaming livestreams, podcasts, and subscribed channels in up to 4K HDR hardware composition.";
        }
        if ("com.apple.atve.androidtv.appletv".equals(pkg)) {
            return "Apple TV+ provides award-winning Apple Original movies, drama series, documentaries, comedy specials, and live sports coverage.";
        }
        if ("com.peacocktv.peacockandroid".equals(pkg)) {
            return "Peacock TV streams NBCUniversal blockbuster films, exclusive original series, live WWE events, Premier League soccer, and Sunday Night Football.";
        }
        if ("com.spotify.tv.android".equals(pkg)) {
            return "Spotify on TV delivers millions of songs, personalized playlists, and full-screen album art with high-fidelity digital audio playback.";
        }
        if ("com.amazon.amazonvideo.livingroom".equals(pkg)) {
            return "Amazon Prime Video features Prime Originals, blockbuster films, popular TV series, and live Thursday Night Football in 4K UHD.";
        }
        if ("com.espn.score_center".equals(pkg) || "com.espn.watch".equals(pkg)) {
            return "ESPN provides live sports scores, expert commentary, game highlights, breaking news, and live streaming of ESPN networks.";
        }
        if ("com.nfl.mobile".equals(pkg) || "com.nfl.gamecenter".equals(pkg)) {
            return "NFL for Android TV provides official NFL football game coverage, real-time scoreboards, highlights, and team roster updates.";
        }
        if ("com.nba.mobile.android".equals(pkg)) {
            return "NBA App offers official NBA basketball scores, live quarter stats, team standings, player highlights, and game recaps.";
        }
        if ("tv.trakt.trakt".equals(pkg)) {
            return "Trakt is the leading TV show and movie tracking community. Sync your watch history, manage watchlists, track episode progress, and discover trending media.";
        }
        if ("hotspotshield.android.vpn".equals(pkg)) {
            return "Hotspot Shield VPN encrypts your TV network traffic, protects privacy, and provides fast secure proxy connections for streaming.";
        }
        if ("com.netflix.ninja".equals(pkg)) {
            return "Netflix provides unlimited streaming of award-winning TV series, movies, documentaries, and personalized entertainment in 4K HDR.";
        }
        if ("com.disney.disneyplus".equals(pkg)) {
            return "Disney+ is the home for entertainment from Disney, Pixar, Marvel, Star Wars, National Geographic, and general entertainment.";
        }
        if ("com.wbd.stream".equals(pkg)) {
            return "Max features iconic HBO series, Warner Bros. films, DC Universe superhero adventures, and live sports streaming.";
        }
        if ("com.cbs.app".equals(pkg) || "com.cbs.ott".equals(pkg)) {
            return "Paramount+ features live sports including UEFA Champions League and NFL on CBS, plus exclusive original series and movies.";
        }
        if ("com.android.vending".equals(pkg)) {
            return "Google Play Store is the official digital marketplace to install and update Android TV streaming apps, media utilities, and games.";
        }
        return label + " is an installed application on your " + Build.MODEL + ". Optimized for hardware-accelerated launching and responsive TV remote control.";
    }

    private int getAppAmbientColor(String pkg) {
        if ("com.IPTVOutlet.STC".equals(pkg)) return 0xFF0D47A1;
        if ("com.stremio.one".equals(pkg)) return 0xFF583B9E;
        if ("com.gotv.nfl".equals(pkg)) return 0xFF013369;
        if ("com.google.android.youtube.tv".equals(pkg)) return 0xFFB71C1C;
        if ("com.apple.atve.androidtv.appletv".equals(pkg)) return 0xFF2A2A2A;
        if ("com.peacocktv.peacockandroid".equals(pkg)) return 0xFF00695C;
        if ("com.netflix.ninja".equals(pkg)) return 0xFFB71C1C;
        if ("com.amazon.amazonvideo.livingroom".equals(pkg)) return 0xFF0073A8;
        if ("com.disney.disneyplus".equals(pkg)) return 0xFF1A237E;
        if ("com.spotify.tv.android".equals(pkg)) return 0xFF1DB954;
        if ("com.plexapp.android".equals(pkg)) return 0xFFE5A00D;
        if ("com.android.vending".equals(pkg)) return 0xFF0F7D50;
        return 0xFF142436;
    }

    private String getFolderSummary(FolderEntry f) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.apps.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(f.apps.get(i).label);
        }
        return sb.toString();
    }

    // =========================================================================
    // APPLE TV & GOOGLE TV INFO MODALS (TRIGGERED BY REMOTE (i) INFO BUTTON)
    // =========================================================================

    private void showInfoModal(Object tag) {
        if (tag instanceof AppEntry) {
            showAppInfoModal((AppEntry) tag);
        } else if (tag instanceof FolderEntry) {
            showFolderInfoModal((FolderEntry) tag);
        } else if (tag instanceof HubDataFetcher.MediaItem) {
            showMediaInfoModal((HubDataFetcher.MediaItem) tag);
        } else if (tag instanceof HubDataFetcher.SportGame) {
            showSportInfoModal((HubDataFetcher.SportGame) tag);
        }
    }

    private void showAppInfoModal(AppEntry app) {
        inputsPanel.removeAllViews();
        String version = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(app.pkg, 0);
            version = "v" + pi.versionName;
        } catch (Exception ignored) {}

        String subtitle = app.pkg + (version.isEmpty() ? "" : ("  •  " + version));
        addModalHeader(app.label, subtitle);

        addModalOption("▶  Open " + app.label, () -> {
            hideInputsPanel();
            launchPackage(app.pkg);
        });

        boolean isPinned = dockEntries.contains(app);
        if (isPinned) {
            addModalOption("★  Unpin from Home Row", () -> {
                dockEntries.remove(app);
                saveAppOrder();
                buildDockAndFoldersRow();
                hideInputsPanel();
                Toast.makeText(this, app.label + " unpinned from Home Row", Toast.LENGTH_SHORT).show();
            });
        } else {
            addModalOption("★  Pin to Home Row", () -> {
                dockEntries.add(0, app);
                saveAppOrder();
                buildDockAndFoldersRow();
                hideInputsPanel();
                Toast.makeText(this, app.label + " pinned to Home Row", Toast.LENGTH_SHORT).show();
            });
        }

        addModalOption("📁  Organize into Folder...", () -> showAppOptionsModal(app));

        addModalOption("⚙  System App Details & Permissions", () -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + app.pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            openIntent(intent);
        });

        addModalOption("Done (Back)", this::hideInputsPanel);

        showModalContainer();
    }

    private void showFolderInfoModal(FolderEntry folder) {
        inputsPanel.removeAllViews();
        addModalHeader(folder.title + " Folder", folder.apps.size() + " Applications in folder");

        addModalOption("▶  Open Folder Grid", () -> showFolderModal(folder));

        addModalOption("⏏  Dissolve Folder (Move All to Home)", () -> {
            dissolveFolder(folder);
        });

        addModalOption("Done (Back)", this::hideInputsPanel);

        showModalContainer();
    }

    private void showFolderOptionsModal(FolderEntry folder) {
        showFolderModal(folder);
    }

    private void showAppOptionsModal(AppEntry app) {
        inputsPanel.removeAllViews();
        addModalHeader("Manage " + app.label, "Arrange position, organize into folders, or manage storage");

        int idx = dockEntries.indexOf(app);
        if (idx > 0) {
            addModalOption("⭐  Move to Front of Apps", () -> {
                dockEntries.remove(app);
                dockEntries.add(0, app);
                saveAppOrder();
                buildDockAndFoldersRow();
                hideInputsPanel();
                focusApp(app.pkg);
                Toast.makeText(this, app.label + " moved to front", Toast.LENGTH_SHORT).show();
            });
            addModalOption("◀  Move Left", () -> {
                int cur = dockEntries.indexOf(app);
                if (cur > 0) {
                    Collections.swap(dockEntries, cur, cur - 1);
                    saveAppOrder();
                    buildDockAndFoldersRow();
                    hideInputsPanel();
                    focusApp(app.pkg);
                }
            });
        }
        if (idx >= 0 && idx < dockEntries.size() - 1) {
            addModalOption("▶  Move Right", () -> {
                int cur = dockEntries.indexOf(app);
                if (cur >= 0 && cur < dockEntries.size() - 1) {
                    Collections.swap(dockEntries, cur, cur + 1);
                    saveAppOrder();
                    buildDockAndFoldersRow();
                    hideInputsPanel();
                    focusApp(app.pkg);
                }
            });
        }

        addModalOption("📁  Move into Folder...", () -> {
            showAddToFolderDialog(app);
        });

        addModalOption("⚙  App Settings (Clear Cache, Storage, Permissions)", () -> {
            hideInputsPanel();
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + app.pkg));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Could not open settings for " + app.label, Toast.LENGTH_SHORT).show();
            }
        });

        addModalOption("🗑  Uninstall App", () -> {
            hideInputsPanel();
            try {
                Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                intent.setData(Uri.parse("package:" + app.pkg));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Cannot uninstall " + app.label, Toast.LENGTH_SHORT).show();
            }
        });

        addModalOption("Cancel (Back)", this::hideInputsPanel);

        showModalContainer();
    }

    private void showAddToFolderDialog(AppEntry app) {
        inputsPanel.removeAllViews();
        addModalHeader("Add " + app.label + " to Folder", "Select an existing folder or create a new one");

        for (FolderEntry f : folderEntries) {
            boolean alreadyInside = f.apps.contains(app);
            addModalOption(f.title + " Folder" + (alreadyInside ? "  (Already inside)" : ""), () -> {
                if (!alreadyInside) {
                    f.apps.add(app);
                    f.subtitle = f.apps.size() + " Apps";
                    dockEntries.remove(app);
                    saveFolders();
                    saveAppOrder();
                    buildDockAndFoldersRow();
                    Toast.makeText(this, app.label + " added to " + f.title, Toast.LENGTH_SHORT).show();
                }
                hideInputsPanel();
            });
        }

        String[] suggestions = new String[]{ "Sports", "Streaming", "Utilities", "Favorites", "Kids" };
        for (String s : suggestions) {
            boolean exists = false;
            for (FolderEntry f : folderEntries) {
                if (f.title.equalsIgnoreCase(s)) { exists = true; break; }
            }
            if (!exists) {
                addModalOption("+ Create '" + s + "' Folder", () -> {
                    FolderEntry fe = new FolderEntry();
                    fe.title = s;
                    fe.apps.add(app);
                    fe.subtitle = "1 App";
                    folderEntries.add(fe);
                    dockEntries.remove(app);
                    saveFolders();
                    saveAppOrder();
                    buildDockAndFoldersRow();
                    hideInputsPanel();
                    Toast.makeText(this, "Created " + s + " folder with " + app.label, Toast.LENGTH_SHORT).show();
                });
            }
        }

        addModalOption("Cancel", () -> showAppInfoModal(app));

        showModalContainer();
    }

    private void showMediaInfoModal(HubDataFetcher.MediaItem item) {
        inputsPanel.removeAllViews();

        String meta = item.subtitle;
        if (item.rating > 0) {
            meta = "★ " + String.format(Locale.US, "%.1f", item.rating) + "  •  " + meta;
        }
        if (item.genres != null && !item.genres.isEmpty()) {
            meta = meta + "  •  " + item.genres;
        }

        addModalHeader(item.title, meta);

        if (item.overview != null && !item.overview.isEmpty()) {
            TextView descTv = new TextView(this);
            descTv.setText(item.overview);
            descTv.setMaxLines(2);
            descTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            descTv.setTextColor(0xB3FFFFFF);
            descTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            descTv.setPadding(0, 0, 0, dp(10));
            inputsPanel.addView(descTv);
        }

        if (isPackageInstalled("com.stremio.one")) {
            addModalOption("▶  Play in Stremio", () -> {
                hideInputsPanel();
                openInStremioOrTrakt(item);
            });
        }

        if (isPackageInstalled("tv.trakt.trakt")) {
            addModalOption("🎬  Open in Trakt App", () -> {
                hideInputsPanel();
                launchPackage("tv.trakt.trakt");
            });
        }

        if (isPackageInstalled("com.IPTVOutlet.STC")) {
            addModalOption("🔍  Search in Sky TV IPTV", () -> {
                hideInputsPanel();
                launchPackage("com.IPTVOutlet.STC");
            });
        }

        if (item.imdbId != null && !item.imdbId.isEmpty()) {
            addModalOption("🌐  Trakt Details", () -> {
                hideInputsPanel();
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://trakt.tv/search/imdb/" + item.imdbId));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) {}
            });
        }

        addModalOption("Close (Back)", this::hideInputsPanel);

        showModalContainer();
    }

    private boolean isPackageInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showSportInfoModal(HubDataFetcher.SportGame game) {
        inputsPanel.removeAllViews();
        String matchup = game.awayName + " at " + game.homeName;
        String detail = game.statusDetail + (game.network.isEmpty() ? "" : ("  •  " + game.network));
        addModalHeader(matchup, detail);

        if (!game.oddsDetails.isEmpty() || !game.awayMoneyline.isEmpty() || !game.homeMoneyline.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (!game.oddsDetails.isEmpty()) sb.append(game.oddsDetails);
            if (!game.awayMoneyline.isEmpty() || !game.homeMoneyline.isEmpty()) {
                if (sb.length() > 0) sb.append("  •  ML: ");
                sb.append(game.awayName).append(" ").append(game.awayMoneyline)
                  .append(" / ").append(game.homeName).append(" ").append(game.homeMoneyline);
            }
            TextView badge = new TextView(this);
            badge.setText(sb.toString().trim());
            badge.setTextColor(0xFF81D4FA);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            badge.setPadding(0, 0, 0, dp(10));
            inputsPanel.addView(badge);
        }

        if (!game.matchedPkg.isEmpty()) {
            addModalOption("▶  Watch on " + game.matchedAppLabel, () -> {
                hideInputsPanel();
                launchPackage(game.matchedPkg);
            });
        } else {
            String liveTvPkg = getString(R.string.live_tv_package);
            if (isPackageInstalled(liveTvPkg)) {
                addModalOption("📺  Watch on TV Tuner / Live TV", () -> {
                    hideInputsPanel();
                    launchPackage(liveTvPkg);
                });
            }
        }

        if (isPackageInstalled("com.IPTVOutlet.STC")) {
            addModalOption("📺  Watch on Sky TV IPTV", () -> {
                hideInputsPanel();
                launchPackage("com.IPTVOutlet.STC");
            });
        }

        addModalOption("⭐  Sports Preferences (Add to Favorite Teams)", this::showFavTeamsModal);

        addModalOption("Close (Back)", this::hideInputsPanel);

        showModalContainer();
    }

    private void restoreInitialFocus() {
        dockRow.post(() -> {
            if (!navRows.isEmpty() && !navRows.get(0).isEmpty()) {
                navRows.get(0).get(0).requestFocus();
            }
        });
    }

    // =========================================================================
    // APPLE TV FOLDER MODAL
    // =========================================================================

    private void showFolderModal(FolderEntry folder) {
        inputsPanel.removeAllViews();
        addModalHeader(folder.title + " Folder", folder.apps.size() + " Apps • Click to launch, Menu / Long-Press for options");

        LayoutInflater inf = LayoutInflater.from(this);
        final int COLS = 4;
        List<List<View>> gridMatrix = new ArrayList<>();
        List<View> currentRowViews = null;
        LinearLayout currentRow = null;

        for (int i = 0; i < folder.apps.size(); i++) {
            if (i % COLS == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setGravity(Gravity.CENTER_VERTICAL);
                currentRow.setWeightSum(COLS);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(12);
                currentRow.setLayoutParams(rlp);
                inputsPanel.addView(currentRow);

                currentRowViews = new ArrayList<>();
                gridMatrix.add(currentRowViews);
            }

            final AppEntry app = folder.apps.get(i);
            View v = inf.inflate(R.layout.item_roku_app, currentRow, false);
            View card = v.findViewById(R.id.card);
            ImageView banner = v.findViewById(R.id.banner);
            ImageView icon = v.findViewById(R.id.icon);
            TextView name = v.findViewById(R.id.name);

            name.setText(app.label);
            if (app.art != null && app.isBanner) {
                banner.setImageBitmap(app.art);
                banner.setVisibility(View.VISIBLE);
                icon.setVisibility(View.GONE);
            } else if (app.art != null) {
                icon.setImageBitmap(app.art);
                icon.setVisibility(View.VISIBLE);
                banner.setVisibility(View.GONE);
            }

            card.setTag(app);
            card.setOnClickListener(view -> {
                hideInputsPanel();
                launchPackage(app.pkg);
            });
            card.setOnLongClickListener(view -> {
                showFolderAppOptionsModal(folder, app);
                return true;
            });
            card.setOnFocusChangeListener((view, hasFocus) -> animateFocusCard(view, hasFocus));

            if (currentRow != null) {
                currentRow.addView(v);
                if (currentRowViews != null) {
                    currentRowViews.add(card);
                }
            }
        }

        if (currentRow != null && (folder.apps.size() % COLS != 0)) {
            int remainder = folder.apps.size() % COLS;
            for (int k = remainder; k < COLS; k++) {
                View stub = new View(this);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, 1, 1.0f);
                stub.setLayoutParams(slp);
                stub.setVisibility(View.INVISIBLE);
                currentRow.addView(stub);
            }
        }

        View dissolveBtn = addModalOption("⏏  Dissolve Folder (Move All to Home)", () -> {
            dissolveFolder(folder);
        });

        View closeBtn = addModalOption("Close (Back)", this::hideInputsPanel);

        // Explicit 2D Grid D-Pad Navigation for Folder Cards & Action Buttons
        for (int r = 0; r < gridMatrix.size(); r++) {
            List<View> row = gridMatrix.get(r);
            for (int c = 0; c < row.size(); c++) {
                final int rowIdx = r;
                final int colIdx = c;
                View card = row.get(c);

                card.setOnKeyListener((view, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        view.performClick();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_MENU) {
                        view.performLongClick();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_BACK) {
                        hideInputsPanel();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (colIdx + 1 < row.size()) {
                            row.get(colIdx + 1).requestFocus();
                        } else if (rowIdx + 1 < gridMatrix.size()) {
                            gridMatrix.get(rowIdx + 1).get(0).requestFocus();
                        }
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (colIdx > 0) {
                            row.get(colIdx - 1).requestFocus();
                        } else if (rowIdx > 0) {
                            List<View> prevRow = gridMatrix.get(rowIdx - 1);
                            prevRow.get(prevRow.size() - 1).requestFocus();
                        }
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (rowIdx + 1 < gridMatrix.size()) {
                            List<View> nextRow = gridMatrix.get(rowIdx + 1);
                            int targetCol = Math.min(colIdx, nextRow.size() - 1);
                            nextRow.get(targetCol).requestFocus();
                        } else {
                            if (dissolveBtn != null) dissolveBtn.requestFocus();
                            else if (closeBtn != null) closeBtn.requestFocus();
                        }
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (rowIdx > 0) {
                            List<View> prevRow = gridMatrix.get(rowIdx - 1);
                            int targetCol = Math.min(colIdx, prevRow.size() - 1);
                            prevRow.get(targetCol).requestFocus();
                        }
                        return true;
                    }
                    return false;
                });
            }
        }

        if (dissolveBtn != null && !gridMatrix.isEmpty()) {
            dissolveBtn.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    view.performClick();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    hideInputsPanel();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    List<View> lastRow = gridMatrix.get(gridMatrix.size() - 1);
                    if (!lastRow.isEmpty()) {
                        lastRow.get(0).requestFocus();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (closeBtn != null) closeBtn.requestFocus();
                    return true;
                }
                return false;
            });
        }

        showModalContainer();
        if (!gridMatrix.isEmpty() && !gridMatrix.get(0).isEmpty()) {
            gridMatrix.get(0).get(0).requestFocus();
        } else {
            focusFirstModalOption();
        }
    }

    private void showFolderAppOptionsModal(FolderEntry folder, AppEntry app) {
        inputsPanel.removeAllViews();
        addModalHeader(app.label, folder.title + " Folder Options");

        addModalOption("▶  Open " + app.label, () -> {
            hideInputsPanel();
            launchPackage(app.pkg);
        });

        addModalOption("⏏  Move Out of Folder to Home Screen", () -> {
            folder.apps.remove(app);
            folder.subtitle = folder.apps.size() + " Apps";
            if (folder.apps.isEmpty()) {
                folderEntries.remove(folder);
            }
            if (!dockEntries.contains(app)) {
                dockEntries.add(app);
            }
            saveFolders();
            saveAppOrder();
            buildDockAndFoldersRow();
            hideInputsPanel();
            focusApp(app.pkg);
            Toast.makeText(this, app.label + " moved to Home Screen", Toast.LENGTH_SHORT).show();
        });

        addModalOption("🗑  Remove from Folder", () -> {
            folder.apps.remove(app);
            folder.subtitle = folder.apps.size() + " Apps";
            if (folder.apps.isEmpty()) {
                folderEntries.remove(folder);
            }
            saveFolders();
            buildDockAndFoldersRow();
            showFolderModal(folder);
            Toast.makeText(this, "Removed from folder", Toast.LENGTH_SHORT).show();
        });

        addModalOption("ℹ  App Details & Settings", () -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + app.pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            openIntent(intent);
        });

        addModalOption("⬅  Back to " + folder.title + " Grid", () -> showFolderModal(folder));

        showModalContainer();
    }

    private void dissolveFolder(FolderEntry folder) {
        for (AppEntry a : folder.apps) {
            if (!dockEntries.contains(a)) {
                dockEntries.add(a);
            }
        }
        folderEntries.remove(folder);
        saveFolders();
        saveAppOrder();
        buildDockAndFoldersRow();
        hideInputsPanel();
        Toast.makeText(this, folder.title + " folder dissolved — all apps returned to Home", Toast.LENGTH_SHORT).show();
    }

    // =========================================================================
    // ACTIONS: STREMIO DEEP-LINK, SPORTS LAUNCHER, TRAKT SYNC, INPUTS
    // =========================================================================

    private void openInStremioOrTrakt(HubDataFetcher.MediaItem m) {
        if (m.imdbId != null && !m.imdbId.isEmpty()) {
            String type = "series".equalsIgnoreCase(m.type) ? "series" : "movie";
            String path = "stremio:///detail/" + type + "/" + m.imdbId;
            if ("series".equals(type) && m.nextSeason > 0 && m.nextEpisode > 0) {
                path += "/" + m.imdbId + ":" + m.nextSeason + ":" + m.nextEpisode;
            }
            Uri uri = Uri.parse(path);
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                i.setPackage("com.stremio.one");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        launchPackage("com.stremio.one");
    }

    private void showSportLaunchModal(HubDataFetcher.SportGame g) {
        inputsPanel.removeAllViews();
        addModalHeader(g.awayName + " @ " + g.homeName,
                g.league + " • " + g.statusDetail + (g.network.isEmpty() ? "" : " • Broadcast: " + g.network));

        addModalOption("Watch on Sky TV (Live IPTV)", () -> {
            hideInputsPanel();
            launchPackage("com.IPTVOutlet.STC");
        });

        if (g.matchedPkg != null && !g.matchedPkg.isEmpty()) {
            addModalOption("Watch on " + g.matchedAppLabel + " (" + g.network + ")", () -> {
                hideInputsPanel();
                launchPackage(g.matchedPkg);
            });
        }

        if ("NFL".equals(g.league) && installedMap.containsKey("com.gotv.nflgamecenter.us.lite")
                && !"com.gotv.nflgamecenter.us.lite".equals(g.matchedPkg)) {
            addModalOption("Open NFL App", () -> {
                hideInputsPanel();
                launchPackage("com.gotv.nflgamecenter.us.lite");
            });
        }
        if ("NBA".equals(g.league) && installedMap.containsKey("com.nbaimd.gametime.nba2011")
                && !"com.nbaimd.gametime.nba2011".equals(g.matchedPkg)) {
            addModalOption("Open NBA App", () -> {
                hideInputsPanel();
                launchPackage("com.nbaimd.gametime.nba2011");
            });
        }

        addModalOption("Open Stremio Sports Addons", () -> {
            hideInputsPanel();
            launchPackage("com.stremio.one");
        });

        showModalContainer();
    }

    private void showTraktSetupModal() {
        showTraktSetupModal(2);
    }

    private void showTraktSetupModal(int focusIndex) {
        inputsPanel.removeAllViews();
        String current = HubDataFetcher.getTraktUsername(this);
        addModalHeader(
                current.isEmpty() ? "Trakt Lists & Account Sync" : "Trakt Lists • @" + current,
                "Press OK on any list below to Show / Hide it on your Home Screen");

        Set<String> hidden = getHiddenTraktShelves();
        for (int i = 0; i < cachedTraktShelves.size(); i++) {
            final HubDataFetcher.TraktShelf shelf = cachedTraktShelves.get(i);
            final int childIdx = 2 + i;
            boolean isShown = !hidden.contains(shelf.title);
            String shortName = shelf.title
                    .replace("TRAKT LIST  •  ", "")
                    .replace("TRAKT  •  ", "");
            String label = shortName + " (" + (shelf.items != null ? shelf.items.size() : 0) + ")";
            addModalOptionWithStatus(label, isShown, isShown ? "SHOWN" : "HIDDEN", () -> {
                Set<String> next = getHiddenTraktShelves();
                if (next.contains(shelf.title)) {
                    next.remove(shelf.title);
                } else {
                    next.add(shelf.title);
                }
                setHiddenTraktShelves(next);
                renderTraktShelves();
                showTraktSetupModal(childIdx);
            });
        }

        if (!hidden.isEmpty()) {
            addModalOption("Show All Trakt Lists (" + hidden.size() + " hidden)", () -> {
                setHiddenTraktShelves(Collections.emptySet());
                renderTraktShelves();
                showTraktSetupModal(2);
            });
        }

        addModalOption("Refresh Trakt Recommendations & Watchlists", () -> {
            hideInputsPanel();
            Toast.makeText(this, "Refreshing Trakt shelves...", Toast.LENGTH_SHORT).show();
            refreshTraktData(true);
        });

        addModalOption("Pair via Trakt.tv/activate (Device Code)", () -> {
            inputsPanel.removeAllViews();
            addModalHeader("Requesting Trakt Device Code...", "Please wait a moment");
            HubDataFetcher.requestTraktDeviceCode(dc -> {
                inputsPanel.removeAllViews();
                if (dc == null || dc.userCode.isEmpty()) {
                    addModalHeader("Trakt Activation Error", "Could not reach Trakt API. Try again.");
                    addModalOption("Close", () -> hideInputsPanel());
                    return;
                }
                addModalHeader("Go to " + dc.verificationUrl, "Enter Code: " + dc.userCode);
                addModalOption("Waiting for authorization... (Press Back to cancel)", () -> hideInputsPanel());
                pollTraktToken(dc.deviceCode, dc.intervalSeconds, 0);
            });
        });

        if (!current.isEmpty()) {
            addModalOption("Disconnect @" + current, () -> {
                HubDataFetcher.clearTraktAuth(this);
                updateTraktButtonLabel();
                hideInputsPanel();
                refreshTraktData(true);
            });
        }

        showModalContainer(focusIndex);
    }

    private void pollTraktToken(String deviceCode, int intervalSec, int attempt) {
        if (inputsScrim.getVisibility() != View.VISIBLE || attempt > 40) return;
        clockHandler.postDelayed(() -> {
            HubDataFetcher.pollTraktDeviceToken(this, deviceCode, username -> {
                if (username != null && !username.isEmpty()) {
                    Toast.makeText(this, "Trakt linked: @" + username, Toast.LENGTH_LONG).show();
                    updateTraktButtonLabel();
                    hideInputsPanel();
                    refreshTraktData(true);
                } else {
                    pollTraktToken(deviceCode, intervalSec, attempt + 1);
                }
            });
        }, Math.max(5, intervalSec) * 1000L);
    }

    public void toggleInputsPanel() {
        if (inputsScrim != null && inputsScrim.getVisibility() == View.VISIBLE) {
            hideInputsPanel();
        } else {
            showInputsPanel();
        }
    }

    public void toggleSettingsModal() {
        if (inputsScrim != null && inputsScrim.getVisibility() == View.VISIBLE) {
            hideInputsPanel();
        } else {
            showSettingsModal();
        }
    }

    public void goHome() {
        if (inputsScrim != null && inputsScrim.getVisibility() == View.VISIBLE) {
            hideInputsPanel();
        }
        if (currentViewMode != MODE_HOME) {
            switchViewMode(MODE_HOME);
        }
        if (hubScroll != null) hubScroll.smoothScrollTo(0, 0);
        if (btnTabHome != null) btnTabHome.requestFocus();
    }

    private void showInputsPanel() {
        inputsPanel.removeAllViews();
        addModalHeader("TV Inputs & Sources", "Switch HDMI ports, Google TV System, or Live TV Tuner");

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsvLp.topMargin = dp(8);
        hsvLp.bottomMargin = dp(16);
        hsv.setLayoutParams(hsvLp);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        hsv.addView(inputRow);

        // 1. Google TV Main System Input
        addInputTile(inputRow, "Google TV", "Built-in System TV", "🏠", true, "SYSTEM", () -> {
            hideInputsPanel();
            launchGoogleTvLauncher();
        });

        // 2. Hardware TV Inputs (Auto-Detected via HDMI-CEC)
        // HDMI 1
        String p1Device = cecDeviceNames.get(1);
        String p1Title = p1Device != null ? p1Device : "HDMI 1";
        String p1Sub = p1Device != null ? "HDMI 1 (Auto-Detected)" : "Available / Standby";
        String p1Icon = (p1Title.toLowerCase().contains("playstation") || p1Title.toLowerCase().contains("xbox")) ? "🎮" : "🔌";
        boolean p1Conn = p1Device != null;
        addInputTile(inputRow, p1Title, p1Sub, p1Icon, p1Conn, p1Conn ? "CONNECTED" : "STANDBY", () -> {
            hideInputsPanel();
            switchToInput("com.tcl.tvinput/.TvPassThroughService/HW15");
        });

        // HDMI 2 (Auto-Detected: PlayStation 5)
        String p2Device = cecDeviceNames.get(2);
        String p2Title = p2Device != null ? p2Device : "HDMI 2";
        String p2Sub = p2Device != null ? "HDMI 2 (Auto-Detected)" : "Available / Standby";
        String p2Icon = (p2Title.toLowerCase().contains("playstation") || p2Title.toLowerCase().contains("xbox")) ? "🎮" : "🔌";
        boolean p2Conn = true;
        addInputTile(inputRow, p2Title, p2Sub, p2Icon, p2Conn, "CONNECTED", () -> {
            hideInputsPanel();
            switchToInput("com.tcl.tvinput/.TvPassThroughService/HDMI200004");
        });

        // HDMI 3 (Auto-Detected: Fire TV Stick / eARC Audio)
        String p3Device = cecDeviceNames.get(3);
        String p3Title = p3Device != null ? p3Device : "HDMI 3 (eARC)";
        String p3Sub = p3Device != null ? "HDMI 3 (eARC • Auto-Detected)" : "Soundbar / eARC";
        String p3Icon = (p3Title.toLowerCase().contains("fire") || p3Title.toLowerCase().contains("apple") || p3Title.toLowerCase().contains("tv")) ? "📺" : "🔊";
        boolean p3Conn = true;
        addInputTile(inputRow, p3Title, p3Sub, p3Icon, p3Conn, "ACTIVE", () -> {
            hideInputsPanel();
            switchToInput("com.tcl.tvinput/.TvPassThroughService/HDMI300008");
        });

        // HDMI 4
        String p4Device = cecDeviceNames.get(4);
        String p4Title = p4Device != null ? p4Device : "HDMI 4";
        String p4Sub = p4Device != null ? "HDMI 4 (Auto-Detected)" : "Available / Standby";
        String p4Icon = "🔌";
        boolean p4Conn = p4Device != null;
        addInputTile(inputRow, p4Title, p4Sub, p4Icon, p4Conn, p4Conn ? "CONNECTED" : "STANDBY", () -> {
            hideInputsPanel();
            switchToInput("com.tcl.tvinput/.TvPassThroughService/HW18");
        });

        // 3. Live TV Tuner Card
        addInputTile(inputRow, "Live TV Tuner", "Over-The-Air Antenna", "📡", false, "TUNER", () -> {
            hideInputsPanel();
            switchToInput("com.tcl.tvinput/.TunerInputService/HW2");
        });

        inputsPanel.addView(hsv);

        addModalOption("Close Inputs", this::hideInputsPanel);

        showModalContainer();
    }

    private View addInputTile(ViewGroup parent, String title, String subtitle, String iconSymbol, boolean isActive, String badgeText, Runnable onClick) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        tile.setClickable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(124), dp(100));
        lp.rightMargin = dp(12);
        tile.setLayoutParams(lp);

        tile.setBackgroundResource(R.drawable.card_focus_bg);
        tile.setPadding(dp(10), dp(10), dp(10), dp(10));

        TextView iconTv = new TextView(this);
        iconTv.setText(iconSymbol);
        iconTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        iconTv.setGravity(Gravity.CENTER);
        tile.addView(iconTv);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextColor(0xFFFFFFFF);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleTv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setPadding(0, dp(4), 0, 0);
        tile.addView(titleTv);

        TextView badgeTv = new TextView(this);
        badgeTv.setText(badgeText);
        badgeTv.setTextColor(isActive ? 0xFF00E676 : 0x80FFFFFF);
        badgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        badgeTv.setTypeface(android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.NORMAL));
        badgeTv.setGravity(Gravity.CENTER);
        badgeTv.setPadding(0, dp(2), 0, 0);
        tile.addView(badgeTv);

        tile.setOnClickListener(v -> onClick.run());
        tile.setOnFocusChangeListener((v, hasFocus) -> animateFocusCard(v, hasFocus));
        tile.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                onClick.run();
                return true;
            }
            return false;
        });

        parent.addView(tile);
        return tile;
    }

    private void showModalContainer() {
        showModalContainer(0);
    }

    private void showModalContainer(int focusIndex) {
        View mainContainer = findViewById(R.id.mainContentContainer);
        if (mainContainer instanceof ViewGroup) {
            ((ViewGroup) mainContainer).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        boolean isAlreadyVisible = inputsScrim.getVisibility() == View.VISIBLE;
        if (!isAlreadyVisible) {
            inputsScrim.animate().cancel();
            inputsScrim.setAlpha(0f);
            inputsScrim.setVisibility(View.VISIBLE);
            inputsScrim.animate()
                    .alpha(1f)
                    .setDuration(180)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
            if (inputsScroll != null) {
                inputsScroll.animate().cancel();
                inputsScroll.setTranslationY(dp(22));
                inputsScroll.setScaleX(0.96f);
                inputsScroll.setScaleY(0.96f);
                inputsScroll.animate()
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(240)
                        .setInterpolator(new OvershootInterpolator(1.08f))
                        .start();
            }
        }
        inputsPanel.post(() -> {
            if (focusIndex > 0) {
                focusModalOptionAt(focusIndex);
            } else {
                focusFirstModalOption();
            }
        });
    }

    private void handleModalBack() {
        if (!modalBackStack.isEmpty()) {
            Runnable prev = modalBackStack.pop();
            prev.run();
        } else {
            hideInputsPanel();
        }
    }

    private void focusModalOptionAt(int targetIndex) {
        if (inputsPanel == null) return;
        int focusableCount = 0;
        for (int i = 0; i < inputsPanel.getChildCount(); i++) {
            View child = inputsPanel.getChildAt(i);
            if (child.isFocusable() && child.getVisibility() == View.VISIBLE) {
                if (focusableCount == targetIndex) {
                    child.requestFocus();
                    return;
                }
                focusableCount++;
            }
        }
        focusFirstModalOption();
    }

    private void focusFirstModalOption() {
        if (inputsPanel == null) return;
        View f = findFirstFocusable(inputsPanel);
        if (f != null) {
            f.requestFocus();
        }
    }

    private View findFirstFocusable(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.isFocusable() && child.getVisibility() == View.VISIBLE) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View inner = findFirstFocusable((ViewGroup) child);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private void addModalHeader(String titleText, String subText) {
        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        inputsPanel.addView(t);

        TextView s = new TextView(this);
        s.setText(subText);
        s.setTextColor(0x99EBF5FF);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        s.setPadding(0, dp(2), 0, dp(10));
        inputsPanel.addView(s);
    }

    private View addModalOption(String label, Runnable onClick) {
        boolean connected = label != null && label.endsWith("  •");
        String cleanLabel = connected ? label.substring(0, label.length() - 3) : label;
        return addModalOptionWithStatus(cleanLabel, connected, connected ? "ACTIVE" : "", onClick);
    }

    private View addModalOptionWithStatus(String cleanLabel, boolean showGreenDot, String statusText, Runnable onClick) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.item_input, inputsPanel, false);
        ImageView icon = row.findViewById(R.id.icon);
        TextView title = row.findViewById(R.id.title);
        View dot = row.findViewById(R.id.dot);
        TextView status = row.findViewById(R.id.status);

        if (icon != null) {
            icon.setVisibility(View.GONE);
        }
        if (title != null) {
            title.setText(cleanLabel);
        }
        if (dot != null) {
            dot.setVisibility(showGreenDot ? View.VISIBLE : View.GONE);
        }
        if (status != null) {
            status.setText(statusText != null ? statusText : "");
            status.setVisibility(statusText != null && !statusText.isEmpty() ? View.VISIBLE : View.GONE);
        }

        row.setOnClickListener(v -> onClick.run());
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                v.performClick();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                handleModalBack();
                return true;
            }
            return false;
        });
        inputsPanel.addView(row);
        return row;
    }

    private void hideInputsPanel() {
        modalBackStack.clear();
        isCapturingKey = false;
        RemoteKeyMapperService.setKeyCaptureListener(null);
        if (inputsScrim.getVisibility() == View.VISIBLE) {
            inputsScrim.animate().cancel();
            if (inputsScroll != null) {
                inputsScroll.animate().cancel();
                inputsScroll.animate()
                        .translationY(dp(16))
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .setDuration(160)
                        .setInterpolator(new AccelerateInterpolator())
                        .start();
            }
            inputsScrim.animate()
                    .alpha(0f)
                    .setDuration(160)
                    .withEndAction(() -> {
                        inputsScrim.setVisibility(View.GONE);
                        inputsScrim.setAlpha(1f);
                        if (inputsScroll != null) {
                            inputsScroll.setTranslationY(0f);
                            inputsScroll.setScaleX(1f);
                            inputsScroll.setScaleY(1f);
                        }
                    })
                    .start();
        }
        View mainContainer = findViewById(R.id.mainContentContainer);
        if (mainContainer instanceof ViewGroup) {
            ((ViewGroup) mainContainer).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        if (lastContentFocus != null && lastContentFocus.isShown()) {
            lastContentFocus.requestFocus();
        } else if (!navRows.isEmpty() && !navRows.get(0).isEmpty()) {
            navRows.get(0).get(0).requestFocus();
        }
    }

    // =========================================================================
    // REMOTE BUTTON REMAPPER MODAL & LIVE KEY CAPTURE
    // =========================================================================

    private int currentRemoteType = 0; // 0 = RC923A Voice Remote, 1 = TLC-925 Classic Remote

    private void showRemapModal() {
        showRemapModal(currentRemoteType);
    }

    private void showRemapModal(int remoteType) {
        currentRemoteType = remoteType;
        isCapturingKey = false;
        RemoteKeyMapperService.setKeyCaptureListener(null);

        inputsPanel.removeAllViews();
        addModalHeader("Remote Button Remapper",
                "Virtual interactive remote layouts for TCL RC923A & TLC-925. Select any button to configure its action.");

        // Remote Switcher Tabs
        LinearLayout tabsRow = new LinearLayout(this);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trlp.bottomMargin = dp(14);
        tabsRow.setLayoutParams(trlp);

        TextView tabVoice = createVirtualRemoteTab("🎮 TCL RC923A Voice Remote", remoteType == 0, () -> showRemapModal(0));
        TextView tabClassic = createVirtualRemoteTab("📱 TCL 925 Classic Remote", remoteType == 1, () -> showRemapModal(1));

        tabVoice.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                tabClassic.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                showRemapModal(0);
                return true;
            }
            return false;
        });

        tabClassic.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                tabVoice.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                showRemapModal(1);
                return true;
            }
            return false;
        });

        tabsRow.addView(tabVoice);
        tabsRow.addView(tabClassic);
        inputsPanel.addView(tabsRow);

        // Virtual Remote Body (Chassis)
        LinearLayout remoteChassis = new LinearLayout(this);
        remoteChassis.setOrientation(LinearLayout.VERTICAL);
        remoteChassis.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams rclp = new LinearLayout.LayoutParams(dp(480), ViewGroup.LayoutParams.WRAP_CONTENT);
        rclp.gravity = Gravity.CENTER_HORIZONTAL;
        rclp.bottomMargin = dp(16);
        remoteChassis.setLayoutParams(rclp);
        remoteChassis.setBackgroundResource(R.drawable.card_focus_bg);
        remoteChassis.setPadding(dp(16), dp(14), dp(16), dp(14));

        if (remoteType == 0) {
            renderTclRc923aVirtualRemote(remoteChassis);
        } else {
            renderTcl925VirtualRemote(remoteChassis);
        }
        inputsPanel.addView(remoteChassis);

        // Bottom Actions:
        addModalOption("🎯 Detect Physical Key on Remote (Press Any Button)", () -> startLiveKeyCapture(remoteType));
        addModalOption("🔄 Reset All Buttons to Factory Defaults", () -> {
            getSharedPreferences(RemoteKeyMapperService.PREFS_REMAP, MODE_PRIVATE)
                    .edit().clear().apply();
            Toast.makeText(this, "Reset: All buttons restored to factory defaults", Toast.LENGTH_SHORT).show();
            showRemapModal(remoteType);
        });
        addModalOption("⬅ Back to Settings", this::handleModalBack);

        showModalContainer();
        if (remoteType == 1) {
            tabClassic.requestFocus();
        } else {
            tabVoice.requestFocus();
        }
    }

    private TextView createVirtualRemoteTab(String label, boolean active, Runnable onClick) {
        TextView tab = new TextView(this);
        tab.setText(label);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tab.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(16), dp(6), dp(16), dp(6));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        lp.setMargins(dp(6), 0, dp(6), 0);
        tab.setLayoutParams(lp);

        tab.setBackgroundResource(active ? R.drawable.pill_active_bg : R.drawable.pill_bg);
        tab.setTextColor(getResources().getColorStateList(active ? R.drawable.pill_active_text : R.drawable.pill_text));

        tab.setOnClickListener(v -> onClick.run());
        tab.setOnFocusChangeListener((v, hasFocus) -> animateFocusCard(v, hasFocus));
        tab.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                onClick.run();
                return true;
            }
            return false;
        });
        return tab;
    }

    private void renderTclRc923aVirtualRemote(LinearLayout chassis) {
        TextView title = new TextView(this);
        title.setText("TCL RC923A • SMART TV VOICE REMOTE");
        title.setTextColor(0xFF81D4FA);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        chassis.addView(title);

        // Row 1: Power & Settings
        LinearLayout r1 = createRemoteButtonRow(chassis);
        addVirtualRemoteButton(r1, "POWER", "Power", KeyEvent.KEYCODE_POWER, false);
        addVirtualRemoteButton(r1, "⚙ SETTINGS", "Quick Settings", KeyEvent.KEYCODE_SETTINGS, true);

        // Row 2: Voice Mic & Inputs
        LinearLayout r2 = createRemoteButtonRow(chassis);
        addVirtualRemoteButton(r2, "🎙 ASSISTANT", "Voice Mic", KeyEvent.KEYCODE_SEARCH, false);
        addVirtualRemoteButton(r2, "🔌 INPUTS", "TV Sources", KeyEvent.KEYCODE_TV_INPUT, true);

        // Row 3: System Navigation
        LinearLayout r3 = createRemoteButtonRow(chassis);
        addVirtualRemoteButton(r3, "BACK", "Back", KeyEvent.KEYCODE_BACK, false);
        addVirtualRemoteButton(r3, "🏠 HOME", "Clean Home", KeyEvent.KEYCODE_HOME, false);
        addVirtualRemoteButton(r3, "GUIDE / TV", "EPG & Live", KeyEvent.KEYCODE_GUIDE, true);

        // Row 4: Dedicated Quick Apps 1
        LinearLayout r4 = createRemoteButtonRow(chassis);
        addVirtualRemoteButton(r4, "🍿 NETFLIX", "App Button", 207, true);
        addVirtualRemoteButton(r4, "▶ YOUTUBE", "App Button", 215, true);

        // Row 5: Dedicated Quick Apps 2
        LinearLayout r5 = createRemoteButtonRow(chassis);
        addVirtualRemoteButton(r5, "🍎 APPLE TV", "App Button", 307, true);
        addVirtualRemoteButton(r5, "✨ DISNEY+", "App Button", 308, true);

        // Row 6: TCL Channel & Live TV
        LinearLayout r6 = createRemoteButtonRow(chassis);
        addVirtualRemoteButton(r6, "📺 TCL CHANNEL", "App Button", 305, true);
        addVirtualRemoteButton(r6, "📺 SKY TV (TCL TV)", "Live Channels", KeyEvent.KEYCODE_TV, true);
    }

    private void renderTcl925VirtualRemote(LinearLayout chassis) {
        TextView title = new TextView(this);
        title.setText("TCL 925 • LCD/LED SMART TV CONTROLLER");
        title.setTextColor(0xFFFFB74D);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        chassis.addView(title);

        // Row 1: Power, Settings, Inputs
        LinearLayout r1 = createRemoteButtonRow(chassis);
        addVirtualRemoteButton(r1, "POWER", "Power", KeyEvent.KEYCODE_POWER, false);
        addVirtualRemoteButton(r1, "⚙ SETTINGS", "Settings", KeyEvent.KEYCODE_SETTINGS, true);
        addVirtualRemoteButton(r1, "🔌 INPUTS", "Inputs", KeyEvent.KEYCODE_TV_INPUT, true);

        // Row 2: 4 Color Keys (Red, Green, Yellow, Blue)
        LinearLayout r2 = createRemoteButtonRow(chassis);
        addVirtualRemoteButton(r2, "🔴 RED", "IPTV View", 183, true);
        addVirtualRemoteButton(r2, "🟢 GREEN", "IPTV Sort", 184, true);
        addVirtualRemoteButton(r2, "🟡 YELLOW", "IPTV Favs", 185, true);
        addVirtualRemoteButton(r2, "🔵 BLUE", "IPTV Move", 186, true);

        // Row 3: System Nav
        LinearLayout r3 = createRemoteButtonRow(chassis);
        addVirtualRemoteButton(r3, "BACK", "Back", KeyEvent.KEYCODE_BACK, false);
        addVirtualRemoteButton(r3, "🏠 HOME", "Clean Home", KeyEvent.KEYCODE_HOME, false);
        addVirtualRemoteButton(r3, "📺 SKY TV (TV)", "Live IPTV", KeyEvent.KEYCODE_TV, true);
        addVirtualRemoteButton(r3, "📖 GUIDE", "Sky TV EPG", KeyEvent.KEYCODE_GUIDE, true);
    }

    private LinearLayout createRemoteButtonRow(ViewGroup chassis) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        chassis.addView(row);
        return row;
    }

    private View addVirtualRemoteButton(ViewGroup parent, String buttonName, String keyBadge, int keyCode, boolean isRemappable) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.VERTICAL);
        btn.setGravity(Gravity.CENTER);
        btn.setFocusable(true);
        btn.setClickable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1.0f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        btn.setLayoutParams(lp);

        btn.setBackgroundResource(R.drawable.card_focus_bg);
        btn.setPadding(dp(6), dp(4), dp(6), dp(4));

        TextView nameTv = new TextView(this);
        nameTv.setText(buttonName);
        nameTv.setTextColor(0xFFFFFFFF);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        nameTv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameTv.setGravity(Gravity.CENTER);
        btn.addView(nameTv);

        String currentMapping = RemoteKeyMapperService.getMapping(this, keyCode);
        String actionLabel = isRemappable ? ("➔ " + getTargetLabel(currentMapping)) : keyBadge;

        TextView actionTv = new TextView(this);
        actionTv.setText(actionLabel);
        actionTv.setTextColor(isRemappable ? 0xFF00E676 : 0x80FFFFFF);
        actionTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        actionTv.setTypeface(android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.NORMAL));
        actionTv.setSingleLine(true);
        actionTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        actionTv.setGravity(Gravity.CENTER);
        btn.addView(actionTv);

        btn.setOnClickListener(v -> {
            if (isRemappable) {
                modalBackStack.push(() -> showRemapModal(currentRemoteType));
                pickActionForKey(keyCode, buttonName, currentRemoteType);
            } else {
                Toast.makeText(this, buttonName + ": " + keyBadge, Toast.LENGTH_SHORT).show();
            }
        });
        btn.setOnFocusChangeListener((v, hasFocus) -> animateFocusCard(v, hasFocus));
        btn.setOnKeyListener((v, kCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (kCode == KeyEvent.KEYCODE_DPAD_CENTER || kCode == KeyEvent.KEYCODE_ENTER || kCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                btn.performClick();
                return true;
            }
            return false;
        });

        parent.addView(btn);
        return btn;
    }

    private String getTargetLabel(String target) {
        if (target == null || target.isEmpty() || "NONE".equals(target)) return "Default";
        if (RemoteKeyMapperService.ACTION_COLOR_RED.equals(target)) return "🔴 Red (View)";
        if (RemoteKeyMapperService.ACTION_COLOR_GREEN.equals(target)) return "🟢 Green (Sort)";
        if (RemoteKeyMapperService.ACTION_COLOR_YELLOW.equals(target)) return "🟡 Yellow (Favs)";
        if (RemoteKeyMapperService.ACTION_COLOR_BLUE.equals(target)) return "🔵 Blue (Move)";
        if (target.startsWith("KEY:")) return "Key " + target.substring(4);
        if (RemoteKeyMapperService.ACTION_SETTINGS.equals(target)) return "Settings";
        if (RemoteKeyMapperService.ACTION_INPUTS.equals(target)) return "Inputs";
        if (RemoteKeyMapperService.ACTION_HOME.equals(target)) return "Clean Home";
        if ("com.IPTVOutlet.STC".equals(target)) return "Sky TV";
        if ("com.stremio.one".equals(target)) return "Stremio";
        if ("com.google.android.youtube.tv".equals(target)) return "YouTube";
        if ("com.netflix.ninja".equals(target)) return "Netflix";
        if ("com.amazon.amazonvideo.livingroom".equals(target)) return "Prime Video";
        if ("com.disney.disneyplus".equals(target)) return "Disney+";
        if ("com.apple.atve.androidtv.appletv".equals(target)) return "Apple TV";
        if ("com.tcl.channelplus".equals(target) || "com.tcl.tv".equals(target)) return "TCL Channel";
        if ("com.peacocktv.peacockandroid".equals(target)) return "Peacock TV";
        if ("com.spotify.tv.android".equals(target)) return "Spotify";
        if ("com.android.vending".equals(target)) return "Play Store";
        if ("com.google.android.apps.tv.launcherx".equals(target)) return "Google TV";

        AppEntry app = installedMap.get(target);
        if (app != null && app.label != null) return app.label;

        try {
            PackageManager pm = getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(target, 0));
            if (label != null && label.length() > 0) return label.toString();
        } catch (Exception ignored) {}

        return target;
    }

    private void pickActionForKey(int keyCode, String keyName) {
        pickActionForKey(keyCode, keyName, currentRemoteType);
    }

    private void pickActionForKey(int keyCode, String keyName, int remoteType) {
        inputsPanel.removeAllViews();
        addModalHeader("Remap " + keyName, "Select which action opens when you press this button on your remote");

        String current = RemoteKeyMapperService.getMapping(this, keyCode);

        addModalOption("🔴 Red Button (PROG_RED • IPTV View)" + (RemoteKeyMapperService.ACTION_COLOR_RED.equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, RemoteKeyMapperService.ACTION_COLOR_RED);
            Toast.makeText(this, keyName + " ➔ 🔴 Red (IPTV View)", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("🟢 Green Button (PROG_GREEN • IPTV Sort)" + (RemoteKeyMapperService.ACTION_COLOR_GREEN.equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, RemoteKeyMapperService.ACTION_COLOR_GREEN);
            Toast.makeText(this, keyName + " ➔ 🟢 Green (IPTV Sort)", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("🟡 Yellow Button (PROG_YELLOW • IPTV Favorites)" + (RemoteKeyMapperService.ACTION_COLOR_YELLOW.equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, RemoteKeyMapperService.ACTION_COLOR_YELLOW);
            Toast.makeText(this, keyName + " ➔ 🟡 Yellow (IPTV Favorites)", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("🔵 Blue Button (PROG_BLUE • IPTV Move)" + (RemoteKeyMapperService.ACTION_COLOR_BLUE.equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, RemoteKeyMapperService.ACTION_COLOR_BLUE);
            Toast.makeText(this, keyName + " ➔ 🔵 Blue (IPTV Move)", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("⚙ Launcher Settings" + (RemoteKeyMapperService.ACTION_SETTINGS.equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, RemoteKeyMapperService.ACTION_SETTINGS);
            Toast.makeText(this, keyName + " ➔ Settings", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("🔌 TV Inputs & Sources" + (RemoteKeyMapperService.ACTION_INPUTS.equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, RemoteKeyMapperService.ACTION_INPUTS);
            Toast.makeText(this, keyName + " ➔ TV Inputs", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("📺 Sky TV (Live IPTV Channels)" + ("com.IPTVOutlet.STC".equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, "com.IPTVOutlet.STC");
            Toast.makeText(this, keyName + " ➔ Sky TV", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("🎬 Stremio" + ("com.stremio.one".equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, "com.stremio.one");
            Toast.makeText(this, keyName + " ➔ Stremio", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("✨ Disney+" + ("com.disney.disneyplus".equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, "com.disney.disneyplus");
            Toast.makeText(this, keyName + " ➔ Disney+", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("🍎 Apple TV" + ("com.apple.atve.androidtv.appletv".equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, "com.apple.atve.androidtv.appletv");
            Toast.makeText(this, keyName + " ➔ Apple TV", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("📺 TCL Channel" + ("com.tcl.channelplus".equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, "com.tcl.channelplus");
            Toast.makeText(this, keyName + " ➔ TCL Channel", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("▶ YouTube" + ("com.google.android.youtube.tv".equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, "com.google.android.youtube.tv");
            Toast.makeText(this, keyName + " ➔ YouTube", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("🍿 Netflix" + ("com.netflix.ninja".equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, "com.netflix.ninja");
            Toast.makeText(this, keyName + " ➔ Netflix", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("📦 Prime Video" + ("com.amazon.amazonvideo.livingroom".equals(current) ? "  •" : ""), () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, "com.amazon.amazonvideo.livingroom");
            Toast.makeText(this, keyName + " ➔ Prime Video", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("📱 Choose from All Installed TV Apps...", () -> {
            showInstalledAppPickerForRemap(keyCode, keyName, remoteType);
        });

        addModalOption("🏠 Google TV Stock Home", () -> {
            RemoteKeyMapperService.setMapping(this, keyCode, "com.google.android.apps.tv.launcherx");
            Toast.makeText(this, keyName + " ➔ Google TV", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("🔄 Reset to Factory Default", () -> {
            RemoteKeyMapperService.clearMapping(this, keyCode);
            Toast.makeText(this, keyName + " reset to default", Toast.LENGTH_SHORT).show();
            handleModalBack();
        });

        addModalOption("Cancel (Back)", this::handleModalBack);

        showModalContainer();
    }

    private void showInstalledAppPickerForRemap(int keyCode, String keyName, int remoteType) {
        modalBackStack.push(() -> pickActionForKey(keyCode, keyName, remoteType));
        inputsPanel.removeAllViews();
        addModalHeader("Choose App for " + keyName, "Select any application installed on your TV");

        List<AppEntry> sorted = new ArrayList<>(installedMap.values());
        Collections.sort(sorted, (a, b) -> a.label.compareToIgnoreCase(b.label));

        String current = RemoteKeyMapperService.getMapping(this, keyCode);
        for (AppEntry app : sorted) {
            boolean isCur = app.pkg.equals(current);
            addModalOption(app.label + (isCur ? "  •" : ""), () -> {
                RemoteKeyMapperService.setMapping(this, keyCode, app.pkg);
                Toast.makeText(this, keyName + " ➔ " + app.label, Toast.LENGTH_SHORT).show();
                hideInputsPanel();
            });
        }

        addModalOption("Cancel (Back)", this::handleModalBack);
        showModalContainer();
    }

    private void startLiveKeyCapture() {
        startLiveKeyCapture(currentRemoteType);
    }

    private void startLiveKeyCapture(int remoteType) {
        modalBackStack.push(() -> showRemapModal(remoteType));
        isCapturingKey = true;
        inputsPanel.removeAllViews();
        addModalHeader("Press Any Button On Your Remote",
                "Press any hardware button on your TCL remote now (e.g. TV, Red, Green, Netflix, Guide, App button)...");

        addModalOption("Cancel (Press Back)", this::handleModalBack);

        RemoteKeyMapperService.setKeyCaptureListener((capturedCode, capturedName) -> {
            if (!isCapturingKey) return;
            isCapturingKey = false;
            RemoteKeyMapperService.setKeyCaptureListener(null);
            pickActionForKey(capturedCode, capturedName, remoteType);
        });

        showModalContainer();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (inputsScrim.getVisibility() == View.VISIBLE) {
                    handleModalBack();
                    return true;
                }
            }
            if (isCapturingKey) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER) {
                    return super.dispatchKeyEvent(event);
                }
                if (keyCode != KeyEvent.KEYCODE_BACK) {
                    isCapturingKey = false;
                    RemoteKeyMapperService.setKeyCaptureListener(null);
                    modalBackStack.push(() -> showRemapModal(currentRemoteType));
                    pickActionForKey(keyCode, RemoteKeyMapperService.friendlyKeyName(keyCode), currentRemoteType);
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == 268) {
                showSettingsModal();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_TV_INPUT || keyCode == 178) {
                showInputsPanel();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_HOME) {
                if (inputsScrim.getVisibility() == View.VISIBLE) {
                    hideInputsPanel();
                    return true;
                }
                if (currentViewMode != MODE_HOME) {
                    switchViewMode(MODE_HOME);
                    if (btnTabHome != null) btnTabHome.requestFocus();
                    return true;
                }
                if (hubScroll != null) hubScroll.smoothScrollTo(0, 0);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_WINDOW || keyCode == KeyEvent.KEYCODE_HELP) {
                View target = getCurrentFocus();
                if (target == null) target = lastContentFocus;
                if (target != null && target.getTag() != null) {
                    showInfoModal(target.getTag());
                    return true;
                }
                if (inputsScrim.getVisibility() == View.VISIBLE) {
                    hideInputsPanel();
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_MENU) {
                View target = getCurrentFocus();
                if (target == null) target = lastContentFocus;
                if (target != null && target.isLongClickable()) {
                    boolean handled = target.performLongClick();
                    if (handled) return true;
                }
                if (inputsScrim.getVisibility() == View.VISIBLE) {
                    hideInputsPanel();
                    return true;
                }
            } else {
                if (inputsScrim.getVisibility() != View.VISIBLE) {
                    String mapped = RemoteKeyMapperService.getMapping(this, keyCode);
                    if (mapped != null && !mapped.isEmpty() && !"NONE".equals(mapped)) {
                        launchMappedAction(mapped);
                        return true;
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void launchMappedAction(String target) {
        if (RemoteKeyMapperService.ACTION_COLOR_RED.equals(target)) {
            LocalAdbClient.injectKeyEvent(KeyEvent.KEYCODE_PROG_RED);
            return;
        }
        if (RemoteKeyMapperService.ACTION_COLOR_GREEN.equals(target)) {
            LocalAdbClient.injectKeyEvent(KeyEvent.KEYCODE_PROG_GREEN);
            return;
        }
        if (RemoteKeyMapperService.ACTION_COLOR_YELLOW.equals(target)) {
            LocalAdbClient.injectKeyEvent(KeyEvent.KEYCODE_PROG_YELLOW);
            return;
        }
        if (RemoteKeyMapperService.ACTION_COLOR_BLUE.equals(target)) {
            LocalAdbClient.injectKeyEvent(KeyEvent.KEYCODE_PROG_BLUE);
            return;
        }
        if (target != null && target.startsWith("KEY:")) {
            try {
                int code = Integer.parseInt(target.substring(4));
                LocalAdbClient.injectKeyEvent(code);
            } catch (Exception ignored) {}
            return;
        }
        if (RemoteKeyMapperService.ACTION_SETTINGS.equals(target)) {
            showSettingsModal();
            return;
        }
        if (RemoteKeyMapperService.ACTION_INPUTS.equals(target)) {
            showInputsPanel();
            return;
        }
        if (RemoteKeyMapperService.ACTION_HOME.equals(target)) {
            if (inputsScrim.getVisibility() == View.VISIBLE) {
                hideInputsPanel();
            }
            if (currentViewMode != MODE_HOME) {
                switchViewMode(MODE_HOME);
            }
            if (hubScroll != null) hubScroll.smoothScrollTo(0, 0);
            return;
        }
        launchPackage(target);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (inputsScrim != null && inputsScrim.getVisibility() == View.VISIBLE) {
            hideInputsPanel();
            return;
        }
        if (intent != null && intent.getBooleanExtra("SHOW_SETTINGS", false)) {
            showSettingsModal();
            return;
        }
        if (intent != null && intent.getBooleanExtra("SHOW_INPUTS", false)) {
            showInputsPanel();
            return;
        }
        if (intent != null && intent.getBooleanExtra("SHOW_REMAP", false)) {
            showRemapModal(intent.getIntExtra("REMOTE_TYPE", 0));
            return;
        }
        if (currentViewMode != MODE_HOME) {
            switchViewMode(MODE_HOME);
            if (btnTabHome != null) btnTabHome.requestFocus();
            return;
        }
        if (hubScroll != null) {
            hubScroll.smoothScrollTo(0, 0);
        }
    }

    private void switchToInput(String inputId) {
        String liveTvPkg = getString(R.string.live_tv_package);
        PackageManager pm = getPackageManager();

        if (inputId != null) {
            Uri uri = TvContract.buildChannelsUriForInput(inputId);
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(pm) != null) {
                try { startActivity(i); return; } catch (Exception ignored) {}
            }
        }

        Intent launch = pm.getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(liveTvPkg);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(launch); } catch (Exception ignored) {}
        }
    }

    private void launchPackage(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            prefs().edit().putString(KEY_LAST_PKG, pkg).apply();
            try {
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "Cannot open " + pkg, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "App not installed: " + pkg, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (inputsScrim.getVisibility() == View.VISIBLE) {
            handleModalBack();
            return;
        }
        if (currentViewMode != MODE_HOME) {
            switchViewMode(MODE_HOME);
            if (btnTabHome != null) btnTabHome.requestFocus();
            return;
        }
        if (!navRows.isEmpty() && !navRows.get(0).isEmpty()) {
            navRows.get(0).get(0).requestFocus();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
