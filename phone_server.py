#!/usr/bin/env python3
"""Server otomasi HP root v3 - Muse phone control, semua fitur.

Jalankan sebagai pengguna Termux BIASA (bukan shell root).
Perintah perangkat dijalankan lewat `su -c` di dalam server.

BARU di v3 (terinspirasi proyek Artemis — google/artemis):
  /screen (screenshot + UI dump sekali panggil), /scroll arah,
  /type dengan clear/enter, /stop_app, current_package + screen_on
  di /health, /ocr (Tesseract), rekam layar (/record/start,
  /record/stop, /recording), /trace dan /logcat.
  Perbaikan 12 Nov 2026: run() memakai shlex.join (karakter khusus
  di /type tidak lagi rusak); /stop_app: `am force-stop` di XOS
  selalu gagal Binder, jadi fallback bunuh proses lewat SIGKILL
  dan verifikasi selalu lewat pidof sebagai root (pidof tanpa
  root buta terhadap proses aplikasi lain).

KONFIGURASI TERPISAH (supaya update server tidak menghapus token):
  Buat file ~/phone_config.json sekali saja, isinya contoh:
  {
    "token": "TOKEN-ACAK-PANJANG-BUATANMU",
    "host": "IP-TAILSCALE-HP",
    "port": 8765,
    "allowed_packages": ["org.telegram.messenger"],
    "idle_timeout_minutes": 30
  }
  Bila file itu tidak ada, server memakai nilai di bawah dan
  menolak semua permintaan sampai token diganti dari placeholder.
  JANGAN kirim isi file konfigurasi itu lewat chat.

HANYA bind ke alamat Tailscale HP, JANGAN ke 0.0.0.0.
Token wajib di header X-Token setiap permintaan.
Hanya perintah allowlist yang diizinkan - bukan shell bebas.
Screenshot dihapus otomatis setelah dibaca dan dikirim.
Log hanya teks (waktu, aksi, hasil) - tanpa screenshot, tanpa token.
"""
import base64
import json
import os
import re
import shlex
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlsplit

HOME = os.path.expanduser("~")
CONFIG_PATH = os.path.join(HOME, "phone_config.json")
LOG_PATH = os.path.join(HOME, "phone_server.log")
MACRO_DIR = os.path.join(HOME, "macros")
os.makedirs(MACRO_DIR, exist_ok=True)

DEFAULTS = {
    "token": "GANTI-DENGAN-TOKEN-ACAK-PANJANG",
    "host": "GANTI-DENGAN-IP-TAILSCALE-HP",
    "port": 8765,
    "allowed_packages": [],
    "idle_timeout_minutes": 30,
}
try:
    with open(CONFIG_PATH) as f:
        CFG = {**DEFAULTS, **json.load(f)}
except Exception:
    CFG = dict(DEFAULTS)

TOKEN = CFG["token"]
HOST = CFG["host"]
PORT = int(CFG["port"])
ALLOWED_PACKAGES = set(CFG.get("allowed_packages") or [])
IDLE_TIMEOUT = int(CFG.get("idle_timeout_minutes") or 30) * 60

# Kata yang menandakan aksi berisiko. Ketukan ke tombol dengan kata
# ini DITOLAK server, kecuali permintaan secara eksplisit membawa
# "allow_risky": true - dan Muse tidak boleh mengeset itu tanpa
# persetujuan spesifik pengguna setelah preview.
RISKY_WORDS = [
    "beli", "buy", "deposit", "bayar", "pay", "payment",
    "kirim uang", "send money", "transfer", "withdraw",
    "connect wallet", "hubungkan dompet", "approve", "setujui",
    "sign", "tanda tangan", "confirm payment", "konfirmasi pembayaran",
]
CAPTCHA_WORDS = [
    "captcha", "i'm not a robot", "i am not a robot",
    "verifikasi manusia", "human verification", "prove you are human",
    "pilih gambar", "select images",
]

LAST_SEEN = [time.time()]
SERVER = [None]
REC_PROC = [None]  # proses screenrecord yang sedang jalan (kalau ada)
REC_FILE = "/sdcard/phone_rec.mp4"

def log(action, result):
    try:
        with open(LOG_PATH, "a") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {action} -> {result}\n")
    except OSError:
        pass

def run(args):
    # shlex.join: argumen dengan karakter khusus (tanda kutip, ;, $)
    # dikutip dengan benar supaya tidak dirusak shell `su -c`.
    cmd = shlex.join(str(a) for a in args)
    r = subprocess.run(["su", "-c", cmd], capture_output=True, timeout=30)
    return r.returncode == 0, r.stdout.decode(errors="ignore"), r.stderr.decode(errors="ignore")

def run_out(args):
    ok, out, _ = run(args)
    return out if ok else ""

def screen_size():
    """Ukuran layar (lebar, tinggi) dari `wm size`; fallback 720x1640."""
    out = run_out(["wm", "size"])
    m = re.search(r"(\d+)\s*x\s*(\d+)", out)
    if m:
        return int(m.group(1)), int(m.group(2))
    return 720, 1640

def screenshot_bytes(path="/sdcard/shot.png"):
    """Tangkap screenshot, kembalikan bytes PNG atau None kalau gagal."""
    if not run(["screencap", "-p", path])[0]:
        return None
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError:
        return None
    try:
        os.remove(path)
    except OSError:
        pass
    return data

def current_package():
    """Paket aplikasi yang sedang di depan (best-effort, None kalau gagal)."""
    try:
        out = run_out(["dumpsys", "window", "windows"])
        m = re.search(r"mCurrentFocus=Window\{[^}]*?u\d+\s+([A-Za-z0-9_.]+)/", out)
        if m:
            return m.group(1)
        out = run_out(["dumpsys", "activity", "activities"])
        m = re.search(r"(?:topResumedActivity|ResumedActivity)[=:]\s*ActivityRecord\{[^}]*?u\d+\s+([A-Za-z0-9_.]+)/", out)
        if m:
            return m.group(1)
    except Exception:
        pass
    return None

def screen_on():
    """Apakah layar menyala (best-effort, None kalau tidak ketemu)."""
    try:
        out = run_out(["dumpsys", "power"])
        m = re.search(r"mWakefulness=(\w+)", out)
        if m:
            return m.group(1) == "Awake"
        m = re.search(r"mScreenOn=(true|false)", out)
        if m:
            return m.group(1) == "true"
    except Exception:
        pass
    return None

def ui_nodes():
    """Ambil struktur layar lewat uiautomator, kembalikan daftar node."""
    run(["uiautomator", "dump", "/sdcard/window_dump.xml"])
    try:
        with open("/sdcard/window_dump.xml", encoding="utf-8", errors="ignore") as f:
            root = ET.fromstring(f.read())
    except Exception:
        return []
    nodes = []
    for el in root.iter("node"):
        b = el.get("bounds", "")
        m = re.findall(r"\d+", b)
        cx = cy = None
        if len(m) == 4:
            cx = (int(m[0]) + int(m[2])) // 2
            cy = (int(m[1]) + int(m[3])) // 2
        nodes.append({
            "text": el.get("text") or "",
            "desc": el.get("content-desc") or "",
            "resource": el.get("resource-id") or "",
            "class": el.get("class") or "",
            "clickable": el.get("clickable") == "true",
            "x": cx, "y": cy,
        })
    try:
        os.remove("/sdcard/window_dump.xml")
    except OSError:
        pass
    return nodes

def find_node(nodes, text=None, resource=None):
    text = (text or "").lower()
    for n in nodes:
        if resource and resource in n["resource"]:
            return n
        if text and (text in n["text"].lower() or text in n["desc"].lower()):
            return n
    return None

def is_risky(text):
    t = (text or "").lower()
    return any(w in t for w in RISKY_WORDS)

def is_captcha(nodes):
    alltext = " ".join((n["text"] + " " + n["desc"]).lower() for n in nodes)
    return any(w in alltext for w in CAPTCHA_WORDS)

def do_action(step):
    """Satu langkah makro/aksi. Kembalikan (ok, info)."""
    kind = step.get("action")
    if kind == "tap":
        return run(["input", "tap", str(step["x"]), str(step["y"])])[0], "tap"
    if kind == "tap_text":
        nodes = ui_nodes()
        target = step.get("text", "")
        if is_risky(target) and not step.get("allow_risky"):
            return False, "blocked_risky"
        n = find_node(nodes, text=target)
        if not n or n["x"] is None:
            return False, "not_found"
        return run(["input", "tap", str(n["x"]), str(n["y"])])[0], "tap_text"
    if kind == "tap_resource":
        nodes = ui_nodes()
        n = find_node(nodes, resource=step.get("resource", ""))
        if not n or n["x"] is None:
            return False, "not_found"
        return run(["input", "tap", str(n["x"]), str(n["y"])])[0], "tap_resource"
    if kind == "swipe":
        return run(["input", "swipe", str(step["x1"]), str(step["y1"]),
                    str(step["x2"]), str(step["y2"])])[0], "swipe"
    if kind == "scroll":
        # Pola Artemis: titik tengah 60% lebar layar (hindari tepi layar
        # dan fast-scroll sidebar); jarak 70% -> 30% dalam ~800ms.
        direction = str(step.get("direction", "")).lower()
        w, h = screen_size()
        x = int(w * 0.6)
        y = int(h * 0.5)
        if direction == "down":
            coords = (x, int(h * 0.7), x, int(h * 0.3))
        elif direction == "up":
            coords = (x, int(h * 0.3), x, int(h * 0.7))
        elif direction == "left":
            coords = (int(w * 0.7), y, int(w * 0.3), y)
        elif direction == "right":
            coords = (int(w * 0.3), y, int(w * 0.7), y)
        else:
            return False, "bad_direction"
        return run(["input", "swipe", str(coords[0]), str(coords[1]),
                    str(coords[2]), str(coords[3]), "800"])[0], "scroll"
    if kind == "long_press":
        return run(["input", "swipe", str(step["x"]), str(step["y"]),
                    str(step["x"]), str(step["y"]), "1000"])[0], "long_press"
    if kind == "double_tap":
        ok1, _, _ = run(["input", "tap", str(step["x"]), str(step["y"])])
        time.sleep(0.2)
        ok2, _, _ = run(["input", "tap", str(step["x"]), str(step["y"])])
        return ok1 and ok2, "double_tap"
    if kind == "type":
        if step.get("clear"):
            run(["input", "keyevent", "123"])  # KEYCODE_MOVE_END
            run(["input", "keyevent"] + ["67"] * 50)  # KEYCODE_DEL x50
        txt = str(step["text"]).replace(" ", "%s")
        ok = run(["input", "text", txt])[0]
        if step.get("enter"):
            run(["input", "keyevent", "66"])  # KEYCODE_ENTER
        return ok, "type"
    if kind == "key":
        return run(["input", "keyevent", str(step["key"])])[0], "key"
    if kind == "launch":
        pkg = str(step["package"])
        if ALLOWED_PACKAGES and pkg not in ALLOWED_PACKAGES:
            return False, "blocked_not_allowed_package"
        ok, _, _ = run(["monkey", "-p", pkg, "-c",
                        "android.intent.category.LAUNCHER", "1"])
        return ok, "launch"
    if kind == "stop_app":
        pkg = str(step["package"])
        if ALLOWED_PACKAGES and pkg not in ALLOWED_PACKAGES:
            return False, "blocked_not_allowed_package"
        # `am force-stop` di beberapa ROM (mis. XOS Infinix) selalu
        # gagal dengan error Binder "Failed transaction" — operasi
        # tidak pernah sampai ke system_server. Coba dulu cara resmi,
        # kalau tidak mempan bunuh prosesnya langsung dengan SIGKILL
        # (tanpa Binder, lewat root). Verifikasi selalu lewat pidof
        # sebagai root: pidof tanpa root buta terhadap proses
        # aplikasi lain (hidepid) dan selalu mengembalikan kosong.
        run(["am", "force-stop", pkg])
        for _ in range(6):
            time.sleep(0.5)
            _, out, _ = run(["pidof", pkg])
            if not out.strip():
                return True, "stop_app"
        _, out, _ = run(["pidof", pkg])
        for pid in out.split():
            if pid.isdigit():
                run(["kill", "-9", pid])
        time.sleep(0.5)
        _, out, _ = run(["pidof", pkg])
        return (not out.strip()), "stop_app"
    if kind == "wait":
        time.sleep(float(step.get("seconds", 1)))
        return True, "wait"
    return False, "unknown_action"

class H(BaseHTTPRequestHandler):
    def _auth(self):
        return self.headers.get("X-Token") == TOKEN
    def _send(self, code, obj, ctype="application/json"):
        body = obj if isinstance(obj, bytes) else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return {}
    def _touch(self):
        LAST_SEEN[0] = time.time()
    def do_GET(self):
        if not self._auth():
            return self._send(403, {"ok": False, "error": "forbidden"})
        self._touch()
        parts = urlsplit(self.path)
        path = parts.path
        query = parse_qs(parts.query)
        if path == "/ping":
            return self._send(200, {"ok": True, "version": 3})
        if path == "/health":
            batt = run_out(["dumpsys", "battery"])
            level = re.search(r"level:\s*(\d+)", batt)
            return self._send(200, {
                "ok": True, "version": 3,
                "battery_level": int(level.group(1)) if level else None,
                "uptime_seconds": int(time.time() - START[0]),
                "idle_timeout_minutes": IDLE_TIMEOUT // 60,
                "current_package": current_package(),
                "screen_on": screen_on(),
            })
        if path == "/screenshot":
            data = screenshot_bytes()
            if data is not None:
                log("screenshot", "ok")
                return self._send(200, data, "image/png")
            log("screenshot", "failed")
            return self._send(500, {"ok": False, "error": "screencap failed"})
        if path == "/screen":
            data = screenshot_bytes()
            if data is None:
                return self._send(500, {"ok": False, "error": "screencap failed"})
            w, h = screen_size()
            nodes = ui_nodes()
            log("screen", f"{len(nodes)} nodes")
            return self._send(200, {
                "ok": True,
                "screenshot": base64.b64encode(data).decode("ascii"),
                "nodes": nodes,
                "captcha_detected": is_captcha(nodes),
                "width": w, "height": h,
            })
        if path == "/ocr":
            shot = "/sdcard/ocr_shot.png"
            if not run(["screencap", "-p", shot])[0]:
                return self._send(500, {"ok": False, "error": "screencap failed"})
            try:
                # Cari tesseract: PATH root belum tentu mencakup bin Termux,
                # jadi cek juga jalur lengkapnya sebelum menyerah.
                tess = "tesseract"
                if not run(["which", "tesseract"])[0]:
                    if run(["test", "-x", "/data/data/com.termux/files/usr/bin/tesseract"])[0]:
                        tess = "/data/data/com.termux/files/usr/bin/tesseract"
                    else:
                        return self._send(200, {"ok": False,
                                                "error": "tesseract_not_installed",
                                                "hint": "pkg install tesseract"})
                ok, out, err = run([tess, shot, "stdout", "--psm", "6"])
                if not ok:
                    return self._send(200, {"ok": False, "error": "ocr_failed",
                                            "detail": (err or "").strip()[:200]})
                log("ocr", f"{len(out)} chars")
                return self._send(200, {"ok": True, "text": out})
            except Exception as e:
                return self._send(200, {"ok": False, "error": "ocr_failed",
                                        "detail": str(e)[:200]})
            finally:
                try:
                    os.remove(shot)
                except OSError:
                    pass
        if path == "/recording":
            if not os.path.exists(REC_FILE):
                return self._send(404, {"ok": False, "error": "no_recording"})
            try:
                with open(REC_FILE, "rb") as f:
                    data = f.read()
            except OSError:
                return self._send(404, {"ok": False, "error": "no_recording"})
            log("recording", f"{len(data)} bytes")
            return self._send(200, data, "video/mp4")
        if path == "/trace":
            try:
                with open(LOG_PATH, encoding="utf-8", errors="ignore") as f:
                    lines = f.read().splitlines()[-50:]
                return self._send(200, {"ok": True, "lines": lines})
            except Exception:
                return self._send(200, {"ok": False, "error": "trace_unavailable"})
        if path == "/logcat":
            try:
                n = int(query.get("n", ["200"])[0])
            except (ValueError, TypeError):
                n = 200
            n = max(1, min(1000, n))
            ok, out, _ = run(["logcat", "-d", "-t", str(n)])
            if not ok:
                return self._send(200, {"ok": False, "error": "logcat_failed"})
            return self._send(200, {"ok": True, "lines": out.splitlines()})
        if path == "/ui":
            nodes = ui_nodes()
            log("ui", f"{len(nodes)} nodes")
            return self._send(200, {
                "ok": True, "nodes": nodes,
                "captcha_detected": is_captcha(nodes),
            })
        if path == "/macro/list":
            names = sorted(f[:-5] for f in os.listdir(MACRO_DIR)
                           if f.endswith(".json"))
            return self._send(200, {"ok": True, "macros": names})
        return self._send(404, {"ok": False, "error": "not found"})
    def do_POST(self):
        if not self._auth():
            return self._send(403, {"ok": False, "error": "forbidden"})
        self._touch()
        d = self._body()
        path = self.path
        if path == "/stop":
            log("stop", "requested")
            self._send(200, {"ok": True, "stopping": True})
            threading.Thread(target=self._shutdown, daemon=True).start()
            return
        if path == "/rotate_token":
            # Hanya berlaku bila token lama benar (sudah dicek di atas)
            # dan token baru diberikan di body. Setelah ini, token lama
            # langsung tidak berlaku; pengguna harus memberi token baru
            # ke Muse lewat jalur yang disepakati, bukan lewat chat.
            new = str(d.get("new_token", ""))
            if len(new) < 20:
                return self._send(400, {"ok": False, "error": "token too short"})
            CFG["token"] = new
            try:
                with open(CONFIG_PATH, "w") as f:
                    json.dump(CFG, f, indent=2)
            except OSError:
                pass
            globals()["TOKEN"] = new
            log("rotate_token", "ok")
            return self._send(200, {"ok": True})
        if path == "/macro/save":
            name = re.sub(r"[^A-Za-z0-9_-]", "_", str(d.get("name", "")))
            steps = d.get("steps", [])
            if not name or not isinstance(steps, list):
                return self._send(400, {"ok": False, "error": "bad macro"})
            with open(os.path.join(MACRO_DIR, name + ".json"), "w") as f:
                json.dump(steps, f, indent=2)
            log("macro_save", name)
            return self._send(200, {"ok": True, "name": name})
        if path == "/macro/play":
            name = re.sub(r"[^A-Za-z0-9_-]", "_", str(d.get("name", "")))
            try:
                with open(os.path.join(MACRO_DIR, name + ".json")) as f:
                    steps = json.load(f)
            except Exception:
                return self._send(404, {"ok": False, "error": "macro not found"})
            for i, step in enumerate(steps):
                ok, info = do_action(step)
                log(f"macro_play:{name}:{i}", f"{step.get('action')} -> {info} ok={ok}")
                if not ok:
                    return self._send(200, {"ok": False, "failed_step": i, "info": info})
            return self._send(200, {"ok": True, "name": name})
        if path == "/record/start":
            try:
                n = int(d.get("time_limit", 30))
            except (ValueError, TypeError):
                n = 30
            n = max(1, min(180, n))
            proc = REC_PROC[0]
            if proc is not None and proc.poll() is None:
                return self._send(200, {"ok": False, "error": "already_recording"})
            try:
                REC_PROC[0] = subprocess.Popen(
                    ["su", "-c", f"screenrecord --time-limit {n} {REC_FILE}"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception:
                return self._send(500, {"ok": False, "error": "record_start_failed"})
            log("record_start", f"time_limit={n}")
            return self._send(200, {"ok": True, "recording": True, "time_limit": n})
        if path == "/record/stop":
            run(["pkill", "-f", "screenrecord"])
            proc = REC_PROC[0]
            if proc is not None:
                deadline = time.time() + 3
                while proc.poll() is None and time.time() < deadline:
                    time.sleep(0.2)
                REC_PROC[0] = None
            size = None
            try:
                if os.path.exists(REC_FILE):
                    size = os.path.getsize(REC_FILE)
            except OSError:
                pass
            log("record_stop", f"size={size}")
            return self._send(200, {"ok": True, "file": REC_FILE, "size_bytes": size})
        # Aksi tunggal: /tap, /tap_text, /tap_resource, /swipe, /scroll,
        # /long_press, /double_tap, /type, /key, /launch, /stop_app, /wait
        kind = path.strip("/")
        if kind in ("tap", "tap_text", "tap_resource", "swipe", "scroll",
                    "long_press", "double_tap", "type", "key", "launch",
                    "stop_app", "wait"):
            step = dict(d)
            step["action"] = kind
            ok, info = do_action(step)
            log(kind, f"{info} ok={ok}")
            return self._send(200, {"ok": ok, "info": info})
        return self._send(404, {"ok": False, "error": "not found"})
    def _shutdown(self):
        time.sleep(0.5)
        if SERVER[0]:
            SERVER[0].shutdown()
    def log_message(self, *a):
        print(self.address_string(), self.command, self.path)

START = [time.time()]

def idle_watchdog():
    while True:
        time.sleep(60)
        if IDLE_TIMEOUT and time.time() - LAST_SEEN[0] > IDLE_TIMEOUT:
            log("idle_timeout", "stopping")
            if SERVER[0]:
                SERVER[0].shutdown()
            break

if __name__ == "__main__":
    # Coba kunci bangun Termux bila tersedia, abaikan bila tidak ada.
    try:
        subprocess.run(["termux-wake-lock"], timeout=5)
    except Exception:
        pass
    threading.Thread(target=idle_watchdog, daemon=True).start()
    srv = HTTPServer((HOST, PORT), H)
    SERVER[0] = srv
    print(f"Listening on {HOST}:{PORT} (Tailscale only, v3)")
    srv.serve_forever()
