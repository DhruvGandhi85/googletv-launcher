package com.gathin.tvhome;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Thread-safe ring buffer and file logger for remote, gamepad, and virtual input events.
 * Enables live debugging of swallowed/eaten remote key presses.
 */
public class InputLogBuffer {
    private static final String TAG = "TV_INPUT_LOG";
    private static final int MAX_ENTRIES = 250;
    private static final List<Entry> entries = new ArrayList<>();
    private static File logFile = null;

    public static class Entry {
        public final long timestamp;
        public final String timeStr;
        public final int keyCode;
        public final String keyName;
        public final String action;
        public final int repeatCount;
        public final String verdict;
        public final String source;
        public final boolean isConsumed;

        public Entry(int keyCode, String keyName, String action, int repeatCount, String verdict, String source, boolean isConsumed) {
            this.timestamp = System.currentTimeMillis();
            this.timeStr = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(this.timestamp));
            this.keyCode = keyCode;
            this.keyName = keyName != null ? keyName : ("KEYCODE_" + keyCode);
            this.action = action;
            this.repeatCount = repeatCount;
            this.verdict = verdict;
            this.source = source != null ? source : "Remote";
            this.isConsumed = isConsumed;
        }

        public String toLogLine() {
            String status = isConsumed ? "CONSUMED" : "PASSED";
            String rep = repeatCount > 0 ? " (rep=" + repeatCount + ")" : "";
            return String.format(Locale.US, "[%s] %-12s Key %3d (%-18s) %-4s%s -> %s [%s]",
                    timeStr, status, keyCode, keyName, action, rep, verdict, source);
        }

        public String toJson() {
            return String.format(Locale.US,
                    "{\"time\":\"%s\",\"code\":%d,\"name\":\"%s\",\"action\":\"%s\",\"rep\":%d,\"verdict\":\"%s\",\"src\":\"%s\",\"consumed\":%s}",
                    timeStr, keyCode, escapeJson(keyName), action, repeatCount, escapeJson(verdict), escapeJson(source), isConsumed ? "true" : "false");
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    public static synchronized void init(File filesDir) {
        if (filesDir != null) {
            logFile = new File(filesDir, "input_events.log");
        }
    }

    public static synchronized void log(int keyCode, String keyName, int actionCode, int repeatCount, String verdict, String source, boolean isConsumed) {
        String actionStr;
        switch (actionCode) {
            case 0: actionStr = "DOWN"; break;
            case 1: actionStr = "UP"; break;
            case 2: actionStr = "MULT"; break;
            default: actionStr = "ACT_" + actionCode; break;
        }

        Entry e = new Entry(keyCode, keyName, actionStr, repeatCount, verdict, source, isConsumed);
        if (entries.size() >= MAX_ENTRIES) {
            entries.remove(0);
        }
        entries.add(e);

        String line = e.toLogLine();
        Log.i(TAG, line);

        if (logFile != null) {
            try {
                if (logFile.length() > 250_000) {
                    // Rotate
                    File old = new File(logFile.getParentFile(), "input_events.log.old");
                    if (old.exists()) old.delete();
                    logFile.renameTo(old);
                    logFile = new File(logFile.getParentFile(), "input_events.log");
                }
                try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                    fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {}
        }
    }

    public static synchronized List<Entry> getEntries() {
        return new ArrayList<>(entries);
    }

    public static synchronized String getEntriesJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(entries.get(i).toJson());
        }
        sb.append("]");
        return sb.toString();
    }

    public static synchronized void clear() {
        entries.clear();
        if (logFile != null && logFile.exists()) {
            try {
                new FileOutputStream(logFile).close();
            } catch (Exception ignored) {}
        }
    }
}
