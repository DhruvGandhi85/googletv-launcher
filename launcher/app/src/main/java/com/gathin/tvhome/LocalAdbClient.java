package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Java ADB-over-TCP client connecting to localhost:5555.
 * Uses the trusted pre-authorized RSA key to seamlessly toggle Google TV
 * and restore the custom launcher as the native system HOME handler without root.
 */
public class LocalAdbClient {

    private static final String TAG = "LocalAdbClient";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static final int CMD_CNXN = 0x4E584E43;
    private static final int CMD_AUTH = 0x48545541;
    private static final int CMD_OPEN = 0x4E45504F;
    private static final int CMD_OKAY = 0x59414B4F;
    private static final int CMD_CLSE = 0x45534C43;
    private static final int CMD_WRTE = 0x45545257;

    // Pre-authorized RSA key pair matching tools/adbkey/adbkey.json:
    private static final BigInteger RSA_N = new BigInteger(
            "23057880489045412951604399753001969971200104459211005924717567074442540344211367823435913315415814574180579012066682050569694792060354771132620940832614984996117002736339088734125596043026608882021129414456497040627130521523925606373070629384754211393921194968059546393445426689837292073155286876935063475570052532385735076796154574335406153272590153471346713298768579453613058978721127180197957979071833164674309101049941073064508784495663397025741063556542684692297150246150959925626381820712450362057219410055922760937737052548430654676431872189147103579441769666962796910983532910421810085173586438936614272173441");

    private static final BigInteger RSA_D = new BigInteger(
            "10795198850197299320903883266267290913931562401726830565758412965883706385422546026313138048078237309910168390790576397418709207085888359898411711362545212393546515631766670119614502699058324920686853438878323675138658250940973944189464365494490340848676076494269970581352305218796674133399814871675519975294971396305551190229382620281634625444293585643860562271488748213437177760824252928828195765999049060989080717840608874617277810524891984608609835775775234884887019871274488499020134326078686531512628781445535533620958186501546215741842637553379099838930722430987907054728304550367548871857398825155977262804781");

    private static final byte[] SHA1_PREFIX = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    public interface Callback {
        void onComplete(boolean success, String output);
    }

    public static boolean isGoogleTvEnabled(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            int state = pm.getApplicationEnabledSetting("com.google.android.apps.tv.launcherx");
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                   state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Enables Google TV, sets it as preferred home activity, and launches it.
     */
    public static void enableGoogleTvAndLaunch(Context ctx) {
        Toast.makeText(ctx, "Enabling Google TV...", Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            String script = "pm enable com.google.android.apps.tv.launcherx && " +
                    "cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity && " +
                    "am start -n com.google.android.apps.tv.launcherx/.home.HomeActivity";
            boolean ok = runCommandSync(script);
            MAIN_HANDLER.post(() -> {
                if (ok) {
                    Toast.makeText(ctx, "Google TV active (Home button now opens Google TV)", Toast.LENGTH_LONG).show();
                } else {
                    // Fallback to direct intent launch
                    try {
                        Intent i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage("com.google.android.apps.tv.launcherx");
                        if (i != null) {
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(i);
                        }
                    } catch (Exception ignored) {}
                }
            });
        });
    }

    /**
     * Disables Google TV so custom launcher becomes the only active home activity.
     */
    public static void disableGoogleTv(Context ctx, boolean notifyUser) {
        EXECUTOR.execute(() -> {
            String script = "pm disable-user --user 0 com.google.android.apps.tv.launcherx && " +
                    "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity";
            boolean ok = runCommandSync(script);
            if (notifyUser && ok) {
                MAIN_HANDLER.post(() -> {
                    Toast.makeText(ctx, "Clean Launcher active (Home button now opens Clean Launcher)", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * Ensures Google TV Ambient Mode (dreamx) is enabled and set as the default screensaver.
     */
    public static void ensureGoogleTvScreensaver(Context ctx) {
        EXECUTOR.execute(() -> {
            String script = "pm enable com.google.android.apps.tv.dreamx && " +
                    "settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop && " +
                    "settings put secure screensaver_enabled 1";
            runCommandSync(script);
        });
    }

    public static void runCommand(String command, Callback callback) {
        EXECUTOR.execute(() -> {
            StringBuilder sb = new StringBuilder();
            boolean ok = runAdbShell(command, sb);
            if (callback != null) {
                MAIN_HANDLER.post(() -> callback.onComplete(ok, sb.toString()));
            }
        });
    }

    public static void injectKeyEvent(int keyCode) {
        EXECUTOR.execute(() -> {
            runCommandSync("input keyevent " + keyCode);
        });
    }

    public static boolean runCommandSync(String command) {
        return runAdbShell(command, new StringBuilder());
    }

    private static boolean runAdbShell(String command, StringBuilder outputBuffer) {
        Socket sock = null;
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            sock.setSoTimeout(5000);

            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();

            // 1. Send CNXN
            byte[] cnxnData = "host::\0".getBytes(StandardCharsets.UTF_8);
            sendPacket(out, CMD_CNXN, 0x01000000, 256 * 1024, cnxnData);

            // 2. Read response
            Packet p = readPacket(in);
            if (p == null) return false;

            if (p.cmd == CMD_AUTH && p.arg0 == 1) {
                byte[] sig = signToken(p.data, RSA_N, RSA_D);
                sendPacket(out, CMD_AUTH, 2, 0, sig);
                p = readPacket(in);
                if (p == null) return false;
            }

            if (p.cmd != CMD_CNXN) {
                Log.w(TAG, "ADB auth handshake rejected: " + Integer.toHexString(p.cmd));
                return false;
            }

            // 3. Open shell stream
            byte[] shellCmd = ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8);
            sendPacket(out, CMD_OPEN, 1, 0, shellCmd);

            // 4. Read stream until CLSE
            while (true) {
                Packet msg = readPacket(in);
                if (msg == null) break;
                if (msg.cmd == CMD_OKAY) {
                    // stream open confirmed
                } else if (msg.cmd == CMD_WRTE) {
                    if (msg.data != null && msg.data.length > 0) {
                        outputBuffer.append(new String(msg.data, StandardCharsets.UTF_8));
                    }
                    sendPacket(out, CMD_OKAY, 1, msg.arg0, new byte[0]);
                } else if (msg.cmd == CMD_CLSE) {
                    sendPacket(out, CMD_CLSE, 1, msg.arg0, new byte[0]);
                    break;
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute ADB command: " + command, e);
            return false;
        } finally {
            if (sock != null) {
                try { sock.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void sendPacket(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws Exception {
        int dlen = data != null ? data.length : 0;
        int crc = 0;
        if (data != null) {
            for (byte b : data) {
                crc += (b & 0xFF);
            }
        }
        int magic = cmd ^ 0xFFFFFFFF;

        byte[] hdr = new byte[24];
        writeIntLE(hdr, 0, cmd);
        writeIntLE(hdr, 4, arg0);
        writeIntLE(hdr, 8, arg1);
        writeIntLE(hdr, 12, dlen);
        writeIntLE(hdr, 16, crc);
        writeIntLE(hdr, 20, magic);

        out.write(hdr);
        if (dlen > 0) {
            out.write(data);
        }
        out.flush();
    }

    private static Packet readPacket(InputStream in) throws Exception {
        byte[] hdr = readExact(in, 24);
        if (hdr == null) return null;

        int cmd = readIntLE(hdr, 0);
        int arg0 = readIntLE(hdr, 4);
        int arg1 = readIntLE(hdr, 8);
        int dlen = readIntLE(hdr, 12);
        int crc = readIntLE(hdr, 16);
        int magic = readIntLE(hdr, 20);

        byte[] data = dlen > 0 ? readExact(in, dlen) : new byte[0];
        return new Packet(cmd, arg0, arg1, data);
    }

    private static byte[] readExact(InputStream in, int len) throws Exception {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int read = in.read(buf, off, len - off);
            if (read < 0) return null;
            off += read;
        }
        return buf;
    }

    private static void writeIntLE(byte[] b, int off, int val) {
        b[off] = (byte) (val & 0xFF);
        b[off + 1] = (byte) ((val >> 8) & 0xFF);
        b[off + 2] = (byte) ((val >> 16) & 0xFF);
        b[off + 3] = (byte) ((val >> 24) & 0xFF);
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) |
                ((b[off + 1] & 0xFF) << 8) |
                ((b[off + 2] & 0xFF) << 16) |
                ((b[off + 3] & 0xFF) << 24);
    }

    private static byte[] signToken(byte[] token, BigInteger n, BigInteger d) {
        byte[] padded = new byte[256];
        padded[0] = 0x00;
        padded[1] = 0x01;
        int padLen = 256 - 3 - (SHA1_PREFIX.length + token.length);
        for (int i = 0; i < padLen; i++) {
            padded[2 + i] = (byte) 0xFF;
        }
        padded[2 + padLen] = 0x00;
        System.arraycopy(SHA1_PREFIX, 0, padded, 3 + padLen, SHA1_PREFIX.length);
        System.arraycopy(token, 0, padded, 3 + padLen + SHA1_PREFIX.length, token.length);

        BigInteger m = new BigInteger(1, padded);
        BigInteger s = m.modPow(d, n);
        byte[] sigBytes = s.toByteArray();

        byte[] out = new byte[256];
        if (sigBytes.length >= 256) {
            System.arraycopy(sigBytes, sigBytes.length - 256, out, 0, 256);
        } else {
            System.arraycopy(sigBytes, 0, out, 256 - sigBytes.length, sigBytes.length);
        }
        return out;
    }

    private static class Packet {
        final int cmd;
        final int arg0;
        final int arg1;
        final byte[] data;

        Packet(int cmd, int arg0, int arg1, byte[] data) {
            this.cmd = cmd;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }
}
