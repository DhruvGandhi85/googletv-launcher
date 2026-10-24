package com.gathin.tvhome.pip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.gathin.tvhome.HubDataFetcher;

import java.util.List;

/**
 * Floating Picture-in-Picture (PiP) and Quick-Swap Overlay Service.
 * Renders on top of active HDMI inputs (PlayStation 5, Fire TV, etc.)
 * providing real-time secondary source monitoring and instantaneous HDMI input swapping.
 */
public class PipOverlayService extends Service {

    public static final String ACTION_START = "com.gathin.tvhome.pip.ACTION_START";
    public static final String ACTION_STOP = "com.gathin.tvhome.pip.ACTION_STOP";
    public static final String ACTION_SWAP = "com.gathin.tvhome.pip.ACTION_SWAP";
    public static final String ACTION_TOGGLE_CORNER = "com.gathin.tvhome.pip.ACTION_TOGGLE_CORNER";

    public static final String PREFS_PIP = "tvhome_pip_prefs";
    public static final String KEY_INPUT_A = "pip_input_a";
    public static final String KEY_INPUT_B = "pip_input_b";
    public static final String KEY_INPUT_A_NAME = "pip_input_a_name";
    public static final String KEY_INPUT_B_NAME = "pip_input_b_name";
    public static final String KEY_CURRENT_ACTIVE = "pip_current_active";
    public static final String KEY_CORNER = "pip_corner";
    public static final String KEY_MODE = "pip_mode";

    public static final String DEFAULT_INPUT_A = "com.tcl.tvinput/.TvPassThroughService/HDMI200004"; // PS5
    public static final String DEFAULT_INPUT_A_NAME = "PlayStation 5 (HDMI 2)";
    public static final String DEFAULT_INPUT_B = "com.tcl.tvinput/.TvPassThroughService/HDMI300008"; // Fire TV
    public static final String DEFAULT_INPUT_B_NAME = "Fire TV Stick (HDMI 3)";

    public static volatile boolean isRunning = false;
    private static PipOverlayService activeInstance = null;

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams windowParams;
    private Handler updateHandler;
    private Runnable tickerRunnable;

    private TextView titleView;
    private TextView subView;
    private TextView scoreView;
    private TextView btnSwap;
    private TextView btnMove;
    private TextView btnClose;

    private int currentCorner = 0; // 0=Top-Right, 1=Bottom-Right, 2=Bottom-Left, 3=Top-Left

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
        isRunning = true;
        updateHandler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        startForegroundNotification();
        buildOverlayView();
    }

    private void startForegroundNotification() {
        String channelId = "pip_overlay_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                    channelId,
                    "HDMI DualView PiP Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(chan);
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("HDMI DualView PiP Active")
               .setContentText("Overlay running over HDMI input")
               .setSmallIcon(android.R.drawable.ic_media_play);

        startForeground(9876, builder.build());
    }

    private FrameLayout videoContainer;
    private TvView tvView;
    private View placeholderView;
    private TextView statusBadge;
    private TextView btnAudio;
    private boolean isMuted = true;
    private String currentTunedInput = null;

    private void buildOverlayView() {
        int overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        int pipWidth = dp(384);
        int pipHeight = dp(216);

        windowParams = new WindowManager.LayoutParams(
                pipWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        currentCorner = getSharedPreferences(PREFS_PIP, MODE_PRIVATE).getInt(KEY_CORNER, 0);
        setWindowGravityForCorner(currentCorner);
        windowParams.x = dp(24);
        windowParams.y = dp(24);

        // Root container with sleek minimalist border
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF00A0F1D); // Dark slate background
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1.5f), 0x8038BDF8); // Subtle cyan accent border
        root.setBackground(bg);
        root.setClipToOutline(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        root.addView(layout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 16:9 Video Container
        videoContainer = new FrameLayout(this);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, pipHeight);
        layout.addView(videoContainer, vLp);

        // Live TvView for hardware HDMI rendering
        try {
            tvView = new TvView(this);
            tvView.setZOrderMediaOverlay(true);
            tvView.setCallback(new TvView.TvInputCallback() {
                @Override
                public void onVideoAvailable(String inputId) {
                    if (statusBadge != null) {
                        statusBadge.setText("● LIVE");
                        statusBadge.setTextColor(0xFF4ADE80); // Green
                    }
                    if (placeholderView != null) {
                        placeholderView.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onVideoUnavailable(String inputId, int reason) {
                    if (statusBadge != null) {
                        statusBadge.setText("○ TUNING");
                        statusBadge.setTextColor(0xFF38BDF8);
                    }
                }

                @Override
                public void onConnectionFailed(String inputId) {
                    if (statusBadge != null) {
                        statusBadge.setText("⚠ NO SIGNAL");
                        statusBadge.setTextColor(0xFFF87171);
                    }
                    if (placeholderView != null) {
                        placeholderView.setVisibility(View.VISIBLE);
                    }
                }
            });
            videoContainer.addView(tvView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            android.util.Log.e("PipOverlayService", "Failed to create TvView", t);
        }

        // Placeholder / Info Layer (shown when loading, or when secondary source is an App)
        FrameLayout placeholder = new FrameLayout(this);
        placeholderView = placeholder;
        placeholder.setBackgroundColor(0xCC0F172A);
        LinearLayout placeContent = new LinearLayout(this);
        placeContent.setOrientation(LinearLayout.VERTICAL);
        placeContent.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        placeholder.addView(placeContent, pLp);

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        titleView.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        titleView.setGravity(Gravity.CENTER);
        placeContent.addView(titleView);

        subView = new TextView(this);
        subView.setTextColor(0xFF94A3B8);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        subView.setGravity(Gravity.CENTER);
        placeContent.addView(subView);

        videoContainer.addView(placeholder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top Overlay Header (Floating over video)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0x88000000);
        header.setPadding(dp(8), dp(4), dp(8), dp(4));

        statusBadge = new TextView(this);
        statusBadge.setText("● PIP");
        statusBadge.setTextColor(0xFF38BDF8);
        statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        statusBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(statusBadge, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        videoContainer.addView(header, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Live Score ticker strip
        scoreView = new TextView(this);
        scoreView.setTextColor(0xFFF1F5F9);
        scoreView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        scoreView.setBackgroundColor(0x331E293B);
        scoreView.setPadding(dp(8), dp(3), dp(8), dp(3));
        scoreView.setSingleLine(true);
        layout.addView(scoreView);

        // Action Controls Bar: [ Swap ] [ Audio 🔊/🔇 ] [ Move ] [ ✕ ]
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        btnRow.setPadding(dp(8), dp(6), dp(8), dp(6));
        layout.addView(btnRow);

        btnSwap = createActionButton("Swap", 0xFF0284C7, () -> swapInputs(this));
        LinearLayout.LayoutParams swapLp = new LinearLayout.LayoutParams(0, dp(26), 1.2f);
        swapLp.rightMargin = dp(4);
        btnRow.addView(btnSwap, swapLp);

        btnAudio = createActionButton(isMuted ? "🔇 Muted" : "🔊 Audio", 0xFF334155, this::toggleAudio);
        LinearLayout.LayoutParams audioLp = new LinearLayout.LayoutParams(0, dp(26), 1.0f);
        audioLp.rightMargin = dp(4);
        btnRow.addView(btnAudio, audioLp);

        btnMove = createActionButton("Move", 0xFF334155, this::cycleCorner);
        LinearLayout.LayoutParams moveLp = new LinearLayout.LayoutParams(0, dp(26), 0.8f);
        moveLp.rightMargin = dp(4);
        btnRow.addView(btnMove, moveLp);

        btnClose = createActionButton("✕", 0xFF475569, () -> stop(this));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(28), dp(26));
        btnRow.addView(btnClose, closeLp);

        overlayView = root;
        try {
            windowManager.addView(overlayView, windowParams);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot show overlay: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        tuneToSecondaryInput();
        startTicker();
    }

    private TextView createActionButton(String text, int bgColor, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setFocusable(true);
        tv.setClickable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(8));
        tv.setBackground(bg);

        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private void setWindowGravityForCorner(int corner) {
        switch (corner % 4) {
            case 0:
                windowParams.gravity = Gravity.TOP | Gravity.END;
                break;
            case 1:
                windowParams.gravity = Gravity.BOTTOM | Gravity.END;
                break;
            case 2:
                windowParams.gravity = Gravity.BOTTOM | Gravity.START;
                break;
            case 3:
                windowParams.gravity = Gravity.TOP | Gravity.START;
                break;
        }
    }

    public void cycleCorner() {
        currentCorner = (currentCorner + 1) % 4;
        getSharedPreferences(PREFS_PIP, MODE_PRIVATE).edit().putInt(KEY_CORNER, currentCorner).apply();
        setWindowGravityForCorner(currentCorner);
        try {
            if (overlayView != null && windowManager != null) {
                windowManager.updateViewLayout(overlayView, windowParams);
            }
        } catch (Exception ignored) {}
    }

    public void toggleAudio() {
        isMuted = !isMuted;
        if (tvView != null) {
            tvView.setStreamVolume(isMuted ? 0.0f : 1.0f);
        }
        if (btnAudio != null) {
            btnAudio.setText(isMuted ? "🔇 Muted" : "🔊 Audio");
        }
        Toast.makeText(this, isMuted ? "PiP Audio Muted" : "PiP Audio Active", Toast.LENGTH_SHORT).show();
    }

    public void tuneToSecondaryInput() {
        SharedPreferences sp = getSharedPreferences(PREFS_PIP, MODE_PRIVATE);
        String inA = sp.getString(KEY_INPUT_A, DEFAULT_INPUT_A);
        String inAName = sp.getString(KEY_INPUT_A_NAME, DEFAULT_INPUT_A_NAME);
        String inB = sp.getString(KEY_INPUT_B, DEFAULT_INPUT_B);
        String inBName = sp.getString(KEY_INPUT_B_NAME, DEFAULT_INPUT_B_NAME);
        String currentActive = sp.getString(KEY_CURRENT_ACTIVE, inA);

        boolean isAActive = currentActive.equals(inA);
        String targetInput = isAActive ? inB : inA;
        String targetName = isAActive ? inBName : inAName;
        String currentName = isAActive ? inAName : inBName;

        if (titleView != null) {
            titleView.setText(targetName);
        }
        if (subView != null) {
            subView.setText("Main: " + currentName + " • '123' to Swap");
        }

        if (targetInput != null && targetInput.startsWith("pkg:")) {
            // Target is an Android App
            if (tvView != null) {
                try {
                    tvView.reset();
                } catch (Exception ignored) {}
                tvView.setVisibility(View.GONE);
            }
            if (placeholderView != null) {
                placeholderView.setVisibility(View.VISIBLE);
            }
            if (statusBadge != null) {
                statusBadge.setText("● APP PIP");
                statusBadge.setTextColor(0xFF38BDF8);
            }
            currentTunedInput = null;
        } else if (targetInput != null && !targetInput.isEmpty()) {
            // Target is an HDMI or passthrough hardware input
            if (targetInput.equals(currentTunedInput)) {
                return;
            }
            currentTunedInput = targetInput;
            if (tvView != null) {
                tvView.setVisibility(View.VISIBLE);
                try {
                    tvView.reset();
                    Uri uri = TvContract.buildChannelUriForPassthroughInput(targetInput);
                    tvView.tune(targetInput, uri);
                    tvView.setStreamVolume(isMuted ? 0.0f : 1.0f);
                    if (statusBadge != null) {
                        statusBadge.setText("○ TUNING HDMI");
                        statusBadge.setTextColor(0xFF38BDF8);
                    }
                } catch (Exception e) {
                    android.util.Log.e("PipOverlayService", "Failed to tune to " + targetInput, e);
                    if (placeholderView != null) {
                        placeholderView.setVisibility(View.VISIBLE);
                    }
                }
            }
        }
    }

    private void startTicker() {
        tickerRunnable = new Runnable() {
            @Override
            public void run() {
                updateScoreTicker();
                updateHandler.postDelayed(this, 15_000);
            }
        };
        updateHandler.post(tickerRunnable);
    }

    private void updateScoreTicker() {
        HubDataFetcher.fetchLiveSports(this, games -> {
            if (games != null && !games.isEmpty()) {
                HubDataFetcher.SportGame top = games.get(0);
                String scoreStr = "🏀 " + top.awayName + " " + top.awayScore + " - " + top.homeScore + " " + top.homeName;
                if (!top.statusDetail.isEmpty()) scoreStr += " (" + top.statusDetail + ")";
                if (scoreView != null) {
                    scoreView.setText(scoreStr);
                    scoreView.setVisibility(View.VISIBLE);
                }
            } else {
                if (scoreView != null) {
                    scoreView.setText("Ready • Press '123' or click Swap");
                    scoreView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    public static void launchInputSource(Context ctx, String sourceId, String sourceName) {
        if (sourceId == null || sourceId.isEmpty()) return;
        try {
            if (sourceId.startsWith("pkg:")) {
                String pkg = sourceId.substring(4);
                PackageManager pm = ctx.getPackageManager();
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    ctx.startActivity(i);
                    return;
                }
            } else {
                PackageManager pm = ctx.getPackageManager();
                String finalSourceId = sourceId;
                try {
                    android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
                    if (tim != null) {
                        android.media.tv.TvInputInfo info = tim.getTvInputInfo(sourceId);
                        if (info != null && info.getParentId() != null) finalSourceId = info.getParentId();
                    }
                } catch (Exception ignored) {}

                Uri uri = TvContract.buildChannelsUriForInput(finalSourceId);
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (i.resolveActivity(pm) != null) {
                    ctx.startActivity(i);
                    return;
                }
                Intent launch = pm.getLeanbackLaunchIntentForPackage("com.tcl.tv");
                if (launch == null) launch = pm.getLaunchIntentForPackage("com.tcl.tv");
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(launch);
                    return;
                }
            }
        } catch (Exception e) {
            Toast.makeText(ctx, "Could not open " + sourceName, Toast.LENGTH_SHORT).show();
        }
    }

    public static void swapInputs(Context ctx) {
        if (!isRunning) {
            start(ctx);
        }

        SharedPreferences sp = ctx.getSharedPreferences(PREFS_PIP, MODE_PRIVATE);
        String inA = sp.getString(KEY_INPUT_A, DEFAULT_INPUT_A);
        String inAName = sp.getString(KEY_INPUT_A_NAME, DEFAULT_INPUT_A_NAME);
        String inB = sp.getString(KEY_INPUT_B, DEFAULT_INPUT_B);
        String inBName = sp.getString(KEY_INPUT_B_NAME, DEFAULT_INPUT_B_NAME);
        String currentActive = sp.getString(KEY_CURRENT_ACTIVE, inA);

        String nextInput = currentActive.equals(inA) ? inB : inA;
        String nextName = currentActive.equals(inA) ? inBName : inAName;

        sp.edit().putString(KEY_CURRENT_ACTIVE, nextInput).apply();

        launchInputSource(ctx, nextInput, nextName);

        if (activeInstance != null) {
            activeInstance.tuneToSecondaryInput();
        }
        Toast.makeText(ctx, "Swapped to " + nextName, Toast.LENGTH_SHORT).show();
    }

    public static void start(Context ctx) {
        Intent intent = new Intent(ctx, PipOverlayService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    public static void stop(Context ctx) {
        Intent intent = new Intent(ctx, PipOverlayService.class);
        ctx.stopService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            if (ACTION_SWAP.equals(intent.getAction())) {
                swapInputs(this);
            } else if (ACTION_TOGGLE_CORNER.equals(intent.getAction())) {
                cycleCorner();
            } else if (ACTION_STOP.equals(intent.getAction())) {
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        activeInstance = null;
        if (updateHandler != null && tickerRunnable != null) {
            updateHandler.removeCallbacks(tickerRunnable);
        }
        if (tvView != null) {
            try {
                tvView.reset();
            } catch (Exception ignored) {}
            tvView = null;
        }
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
            overlayView = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(float dpVal) {
        return (int) (dpVal * getResources().getDisplayMetrics().density + 0.5f);
    }
}
