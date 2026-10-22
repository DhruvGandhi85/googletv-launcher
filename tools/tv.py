#!/usr/bin/env python3
"""Minimal pure-Python ADB-over-TCP client (zero external dependencies, uses stdlib + openssl CLI).

Set TV_HOST to the TV's IP. Usage:
    tv.py --probe                      connect and print model / Android version
    tv.py <shell command...>           run one shell command
    tv.py --batch < commands.txt       one command per line, same connection
    tv.py --push <local> <remote>      push file
    tv.py --pull <remote> <local>      pull file
"""
import base64
import json
import os
import re
import socket
import struct
import subprocess
import sys

HOST = os.environ.get("TV_HOST", "192.168.0.87")
PORT = int(os.environ.get("TV_PORT", "5555"))
KEYDIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "adbkey")
KEY_JSON = os.path.join(KEYDIR, "adbkey.json")

CMD_CNXN = 0x4E584E43
CMD_AUTH = 0x48545541
CMD_OPEN = 0x4E45504F
CMD_OKAY = 0x59414B4F
CMD_CLSE = 0x45534C43
CMD_WRTE = 0x45545257

SHA1_DIGESTINFO_PREFIX = b"\x30\x21\x30\x09\x06\x05\x2b\x0e\x03\x02\x1a\x05\x00\x04\x14"


def _parse_openssl_hex(text, label):
    m = re.search(rf"{label}:\s*\n((?:\s+[0-9a-f:]+\n)+)", text)
    if not m:
        raise RuntimeError(f"Could not parse {label} from openssl output")
    hex_str = re.sub(r"[^0-9a-f]", "", m.group(1))
    return int(hex_str, 16)


def get_key():
    os.makedirs(KEYDIR, exist_ok=True)
    if os.path.exists(KEY_JSON):
        with open(KEY_JSON) as f:
            data = json.load(f)
            return data["n"], data["e"], data["d"], data["pub"].encode("ascii")

    pem_path = os.path.join(KEYDIR, "adbkey.pem")
    subprocess.run(["/usr/bin/openssl", "genrsa", "-out", pem_path, "2048"], check=True, capture_output=True)
    out = subprocess.check_output(["/usr/bin/openssl", "rsa", "-in", pem_path, "-text", "-noout"], text=True)
    n = _parse_openssl_hex(out, "modulus")
    d = _parse_openssl_hex(out, "privateExponent")
    e = 65537

    n0inv = (-pow(n & 0xFFFFFFFF, -1, 1 << 32)) & 0xFFFFFFFF
    rr = (1 << 4096) % n
    pub_struct = (
        struct.pack("<2I", 64, n0inv)
        + n.to_bytes(256, "little")
        + rr.to_bytes(256, "little")
        + struct.pack("<I", e)
    )
    pub_str = base64.b64encode(pub_struct).decode("ascii") + " adb@tv-vibehack\x00"
    with open(KEY_JSON, "w") as f:
        json.dump({"n": n, "e": e, "d": d, "pub": pub_str}, f)
    return n, e, d, pub_str.encode("ascii")


def sign_token(token, n, d):
    digest_info = SHA1_DIGESTINFO_PREFIX + token
    pad_len = 256 - 3 - len(digest_info)
    padded = b"\x00\x01" + (b"\xff" * pad_len) + b"\x00" + digest_info
    m = int.from_bytes(padded, "big")
    s = pow(m, d, n)
    return s.to_bytes(256, "big")


class PureAdbDevice:
    def __init__(self, host, port, timeout=120.0):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.sock = None
        self.local_id = 1

    def _send(self, cmd, arg0, arg1, data=b""):
        crc = sum(data) & 0xFFFFFFFF
        magic = cmd ^ 0xFFFFFFFF
        hdr = struct.pack("<6I", cmd, arg0, arg1, len(data), crc, magic)
        self.sock.sendall(hdr + data)

    def _recv_exact(self, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise EOFError("Connection closed by TV")
            buf.extend(chunk)
        return bytes(buf)

    def _recv(self):
        hdr = self._recv_exact(24)
        cmd, arg0, arg1, data_len, crc, magic = struct.unpack("<6I", hdr)
        data = self._recv_exact(data_len) if data_len else b""
        return cmd, arg0, arg1, data

    def connect(self):
        n, e, d, pub = get_key()
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self.sock.settimeout(self.timeout)
        self._send(CMD_CNXN, 0x01000000, 256 * 1024, b"host::\x00")
        cmd, arg0, arg1, data = self._recv()
        if cmd == CMD_AUTH and arg0 == 1:
            sig = sign_token(data, n, d)
            self._send(CMD_AUTH, 2, 0, sig)
            cmd, arg0, arg1, data = self._recv()
            if cmd == CMD_AUTH and arg0 == 1:
                print(">>> Look at your TV screen now and accept 'Allow USB debugging?' <<<", file=sys.stderr, flush=True)
                self.sock.settimeout(90.0)
                self._send(CMD_AUTH, 3, 0, pub)
                cmd, arg0, arg1, data = self._recv()
                self.sock.settimeout(self.timeout)
        if cmd != CMD_CNXN:
            raise RuntimeError(f"ADB connect failed: unexpected response {hex(cmd)}")
        return self

    def shell_bytes(self, command, read_timeout_s=120.0):
        self.sock.settimeout(read_timeout_s)
        lid = self.local_id
        self.local_id += 1
        payload = f"shell:{command}\x00".encode("utf-8")
        self._send(CMD_OPEN, lid, 0, payload)
        out = bytearray()
        rid = 0
        while True:
            cmd, arg0, arg1, data = self._recv()
            if cmd == CMD_OKAY and arg1 == lid:
                rid = arg0
            elif cmd == CMD_WRTE and arg1 == lid:
                rid = arg0
                out.extend(data)
                self._send(CMD_OKAY, lid, rid)
            elif cmd == CMD_CLSE and arg1 == lid:
                self._send(CMD_CLSE, lid, arg0)
                break
        return bytes(out)

    def shell(self, command, read_timeout_s=120.0):
        raw = self.shell_bytes(command, read_timeout_s=read_timeout_s)
        return raw.replace(b"\r\n", b"\n").decode("utf-8", errors="replace")

    def push(self, local_path, remote_path, transport_timeout_s=300.0, read_timeout_s=300.0):
        data = open(local_path, "rb").read()
        tmp = remote_path + ".b64"
        self.shell(f"rm -f {remote_path} {tmp}", read_timeout_s=read_timeout_s)
        chunk_size = 48 * 1024
        n = (len(data) + chunk_size - 1) // chunk_size
        for i in range(n):
            part = base64.b64encode(data[i * chunk_size:(i + 1) * chunk_size]).decode("ascii")
            self.shell(f"echo '{part}' >> {tmp}", read_timeout_s=read_timeout_s)
        self.shell(f"base64 -d {tmp} > {remote_path} && rm -f {tmp}", read_timeout_s=read_timeout_s)

    def pull(self, remote_path, local_path, transport_timeout_s=300.0, read_timeout_s=300.0):
        b64 = self.shell(f"base64 < {remote_path}", read_timeout_s=read_timeout_s)
        raw = base64.b64decode("".join(b64.split()))
        with open(local_path, "wb") as f:
            f.write(raw)

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None


def discover_tv():
    for svc in ["_adb._tcp", "_adb-tls-connect._tcp", "_googlecast._tcp"]:
        p = subprocess.Popen(["dns-sd", "-B", svc, "local."], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            out, _ = p.communicate(timeout=2.0)
        except subprocess.TimeoutExpired:
            p.kill()
            out, _ = p.communicate()
        print(f"[mDNS {svc}]\n{out.strip()}", flush=True)
    for host in ["Android.local.", "Smart-TV-Pro.local."]:
        p = subprocess.Popen(["dns-sd", "-G", "v4", host], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            out, _ = p.communicate(timeout=2.0)
        except subprocess.TimeoutExpired:
            p.kill()
            out, _ = p.communicate()
        print(f"[mDNS host {host}]\n{out.strip()}", flush=True)


def connect(timeout=15):
    try:
        return PureAdbDevice(HOST, PORT, timeout=float(timeout)).connect()
    except OSError as e:
        print(f"Could not connect to {HOST}:{PORT} ({e}). Discovering TV on network...", flush=True)
        discover_tv()
        raise



def backup_and_inspect(dev):
    import time
    bdir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "backup")
    os.makedirs(bdir, exist_ok=True)
    queries = {
        "packages_all.txt": "pm list packages -f",
        "packages_enabled.txt": "pm list packages -e",
        "packages_disabled.txt": "pm list packages -d",
        "packages_system.txt": "pm list packages -s",
        "packages_thirdparty.txt": "pm list packages -3",
        "getprop.txt": "getprop",
        "settings_system.txt": "settings list system",
        "settings_secure.txt": "settings list secure",
        "settings_global.txt": "settings list global",
        "home_activity.txt": "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
        "leanback_apps.txt": "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER",
        "main_apps.txt": "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER",
    }
    for fname, cmd in queries.items():
        out = dev.shell(cmd, read_timeout_s=120.0)
        with open(os.path.join(bdir, fname), "w") as f:
            f.write(out)
        print(f"saved {fname} ({len(out.splitlines())} lines)")

    dev.shell("input keyevent KEYCODE_HOME")
    time.sleep(1.5)
    dev.shell("screencap -p /data/local/tmp/screen.png")
    dev.pull("/data/local/tmp/screen.png", os.path.join(bdir, "before.png"))
    dev.shell("rm -f /data/local/tmp/screen.png")
    print("saved before.png")


def apply_debloat(dev, list_path):
    with open(list_path) as f:
        lines = f.readlines()
    for raw in lines:
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        cmd = f"pm disable-user --user 0 {line}"
        out = dev.shell(cmd, read_timeout_s=60.0).strip()
        print(f"$ {cmd} -> {out}", flush=True)
    disabled = dev.shell("pm list packages -d", read_timeout_s=60.0)
    print("\n--- Currently disabled packages ---")
    print(disabled.strip())


def build_and_install_launcher():
    import hashlib
    import time

    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    tc_dir = os.path.join(root_dir, ".toolchain")
    jdk_dir = os.path.join(tc_dir, "jdk")
    java_home = os.path.join(jdk_dir, "Contents", "Home")
    sdk_dir = os.path.join(tc_dir, "android-sdk")
    os.makedirs(tc_dir, exist_ok=True)

    if not os.path.exists(os.path.join(java_home, "bin", "javac")):
        print("Downloading portable OpenJDK 17 (Temurin arm64)...", flush=True)
        jdk_tgz = os.path.join(tc_dir, "jdk17.tar.gz")
        subprocess.run(
            ["curl", "-fSL", "-o", jdk_tgz, "https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse"],
            check=True,
        )
        os.makedirs(jdk_dir, exist_ok=True)
        subprocess.run(["tar", "-xzf", jdk_tgz, "-C", jdk_dir, "--strip-components=1"], check=True)
        os.remove(jdk_tgz)
        print("OpenJDK 17 ready.", flush=True)

    env = os.environ.copy()
    env["JAVA_HOME"] = java_home
    env["ANDROID_HOME"] = sdk_dir
    env["PATH"] = f"{os.path.join(java_home, 'bin')}:{env.get('PATH', '')}"

    if not os.path.exists(os.path.join(sdk_dir, "platforms", "android-34")):
        print("Downloading Android Command Line Tools...", flush=True)
        cmd_zip = os.path.join(tc_dir, "cmdtools.zip")
        subprocess.run(
            ["curl", "-fSL", "-o", cmd_zip, "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"],
            check=True,
        )
        os.makedirs(sdk_dir, exist_ok=True)
        subprocess.run(["unzip", "-q", "-o", cmd_zip, "-d", sdk_dir], check=True)
        os.remove(cmd_zip)
        sdkmanager = os.path.join(sdk_dir, "cmdline-tools", "bin", "sdkmanager")
        print("Installing Android SDK Platform 34 & Build-Tools 34.0.0...", flush=True)
        subprocess.run(
            [sdkmanager, f"--sdk_root={sdk_dir}", "platforms;android-34", "build-tools;34.0.0"],
            input="y\n" * 40,
            text=True,
            env=env,
            check=True,
        )
        print("Android SDK 34 ready.", flush=True)

    launcher_dir = os.path.join(root_dir, "launcher")
    with open(os.path.join(launcher_dir, "local.properties"), "w") as f:
        f.write(f"sdk.dir={sdk_dir}\n")

    apk_path = os.path.join(launcher_dir, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    print("Building tv-vibehack launcher APK with Gradle...", flush=True)
    subprocess.run(["./gradlew", "assembleDebug", "--console=plain"], cwd=launcher_dir, env=env, check=True)
    data = open(apk_path, "rb").read()
    want_md5 = hashlib.md5(data).hexdigest()
    print(f"APK ready ({len(data)} bytes, md5={want_md5}). Uploading to TV...", flush=True)

    dev = connect()
    remote_apk = "/data/local/tmp/tvhome.apk"
    tmp_b64 = remote_apk + ".b64"
    dev.shell(f"rm -f {remote_apk} {tmp_b64}")
    chunk_size = 48 * 1024
    n = (len(data) + chunk_size - 1) // chunk_size
    for i in range(n):
        part = base64.b64encode(data[i * chunk_size:(i + 1) * chunk_size]).decode("ascii")
        out = dev.shell(f"echo '{part}' >> {tmp_b64} && echo ok{i}", read_timeout_s=60.0)
        if f"ok{i}" not in out:
            raise RuntimeError(f"Chunk {i} upload failed: {out}")
        print(f"\rUploaded chunk {i + 1}/{n}", end="", flush=True)
    print()
    out = dev.shell(f"base64 -d {tmp_b64} > {remote_apk} && rm -f {tmp_b64} && md5sum {remote_apk}", read_timeout_s=120.0)
    got_md5 = out.split()[0] if out.strip() else ""
    print(f"MD5 check: remote={got_md5} local={want_md5}", flush=True)
    if got_md5 != want_md5:
        raise RuntimeError("APK MD5 mismatch on TV!")

    dev.shell("settings put global verifier_verify_adb_installs 0")
    dev.shell("settings put global package_verifier_enable 0")
    install_out = dev.shell(f"pm install -r --bypass-low-target-sdk-block {remote_apk}", read_timeout_s=120.0).strip()
    print(f"$ pm install -> {install_out}", flush=True)
    if "Success" not in install_out:
        print("Play Protect verifier intercepted install; temporarily pausing com.android.vending for install...", flush=True)
        dev.shell("pm disable-user --user 0 com.android.vending")
        try:
            install_out = dev.shell(f"pm install -r --bypass-low-target-sdk-block {remote_apk}", read_timeout_s=120.0).strip()
            print(f"$ pm install (with verifier paused) -> {install_out}", flush=True)
        finally:
            dev.shell("pm enable com.android.vending")

    dev.shell(f"rm -f {remote_apk}")
    if "Success" not in install_out:
        dev.shell("pm enable com.google.android.apps.tv.launcherx")
        raise RuntimeError(f"APK installation failed: {install_out}")

    cmds = [
        "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted",
        "pm disable-user --user 0 com.google.android.apps.tv.launcherx",
        "pm disable-user --user 0 com.google.android.tungsten.setupwraith",
        "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity",
        "pm enable com.google.android.apps.tv.dreamx",
        "settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop",
        "settings put secure screensaver_enabled 1",
    ]
    for c in cmds:
        res = dev.shell(c, read_timeout_s=120.0).strip()
        print(f"$ {c} -> {res}", flush=True)

    verify_home(dev)
    dev.close()
    print("All done!", flush=True)


def verify_home(dev):
    import time
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    bdir = os.path.join(root_dir, "backup")
    os.makedirs(bdir, exist_ok=True)

    res = dev.shell("cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity").strip()
    print(f"set-home-activity -> {res}", flush=True)
    resolved = dev.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").strip()
    print(f"resolve HOME ->\n{resolved}", flush=True)

    dev.shell("input keyevent KEYCODE_HOME")
    time.sleep(5.5)
    dev.shell("screencap -p /data/local/tmp/screen.png")
    dev.pull("/data/local/tmp/screen.png", os.path.join(bdir, "after_launcher.png"))
    print("Saved backup/after_launcher.png", flush=True)

    dev.shell("input keyevent KEYCODE_DPAD_DOWN")
    time.sleep(1.5)
    dev.shell("input keyevent KEYCODE_DPAD_DOWN")
    time.sleep(2.5)
    dev.shell("screencap -p /data/local/tmp/screen.png")
    dev.pull("/data/local/tmp/screen.png", os.path.join(bdir, "after_launcher_grid.png"))
    print("Saved backup/after_launcher_grid.png", flush=True)

    dev.shell("input keyevent KEYCODE_DPAD_UP")
    time.sleep(0.5)
    dev.shell("input keyevent KEYCODE_DPAD_UP")
    dev.shell("rm -f /data/local/tmp/screen.png")


def restore_all(dev):
    pkgs = [
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tungsten.setupwraith",
        "com.google.android.apps.tv.dreamx",
        "com.android.vending",
        "com.android.adservices.api",
        "com.android.ondevicepersonalization.services",
        "com.android.federatedcompute.services",
        "com.android.tv.feedbackconsent",
        "com.google.android.feedback",
        "android.autoinstalls.config.google.gtvpai",
        "com.tcl.tv.plus",
        "com.tcl.channelplus",
        "com.tcl.tv.tclhome_passive",
        "com.tcl.partnercustomizer",
        "com.tcl.interactive",
        "com.tcl.smartlink.core",
        "com.tcl.logkit",
        "com.tcl.ttvs",
        "com.tcl.messagebox",
        "com.tcl.usercenter",
        "com.tcl.useragreement",
        "com.tcl.exhibit",
        "com.tcl.esticker",
        "com.tcl.browser",
        "com.tcl.ocean.instructions",
        "com.tcl.repairguide",
        "com.tcl.smartalexa",
        "com.tcl.UpdatePeripheral",
        "com.tcl.hearaid",
    ]
    pkg_str = " ".join(pkgs)
    script = (
        f"for p in {pkg_str}; do "
        "  for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do "
        "    out=$(cmd package enable $p 2>&1); "
        "    case \"$out\" in *'new state'*) echo \"OK: $out\"; break;; esac; "
        "    sleep 0.3; "
        "  done; "
        "done; "
        "cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity 2>&1; "
        "cmd package uninstall com.gathin.tvhome 2>&1; "
        "settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop 2>&1; "
        "settings put secure screensaver_default_component com.google.android.apps.tv.dreamx/.service.Backdrop 2>&1; "
        "settings put secure screensaver_enabled 1 2>&1; "
        "echo '--- DISABLED LIST ---'; "
        "cmd package list packages -d 2>&1"
    )
    print("Running fast native restore on TV...", flush=True)
    out = dev.shell(script, read_timeout_s=120.0)
    print(out, flush=True)




def main():
    args = sys.argv[1:]
    if args == ["--build-and-install-launcher"]:
        build_and_install_launcher()
        return
    dev = connect()
    if not args or args == ["--probe"]:
        for p in ("ro.product.model", "ro.product.device", "ro.build.version.release",
                  "ro.build.version.sdk", "ro.build.display.id", "ro.product.manufacturer"):
            print(f"{p} = {dev.shell(f'getprop {p}').strip()}")
    elif args == ["--restore-all"]:
        restore_all(dev)
    elif args == ["--verify-home"]:
        verify_home(dev)
    elif args == ["--backup-and-inspect"]:
        backup_and_inspect(dev)
    elif len(args) == 2 and args[0] == "--apply-debloat":
        apply_debloat(dev, args[1])
    elif args[0] == "--screenshot" and len(args) == 2:
        dev.shell("screencap -p /data/local/tmp/screen.png")
        dev.pull("/data/local/tmp/screen.png", args[1])
        dev.shell("rm -f /data/local/tmp/screen.png")
        print(f"saved screenshot -> {args[1]}")
    elif args[0] == "--push" and len(args) == 3:
        dev.push(args[1], args[2])
        print(f"pushed {args[1]} -> {args[2]}")
    elif args[0] == "--pull" and len(args) == 3:
        dev.pull(args[1], args[2])
        print(f"pulled {args[1]} -> {args[2]}")
    elif args[0] == "--batch":
        sys.stdin.reconfigure(encoding="utf-8-sig")
        for line in sys.stdin:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            out = dev.shell(line, read_timeout_s=120.0)
            sys.stdout.write(f"$ {line}\n{out}")
            if out and not out.endswith("\n"):
                sys.stdout.write("\n")
            sys.stdout.flush()
    else:
        out = dev.shell(" ".join(args), read_timeout_s=120.0)
        sys.stdout.write(out)
    dev.close()


if __name__ == "__main__":
    main()





