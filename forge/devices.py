"""Devices for Forge: the Googlebook itself, virtual Android devices that run in containers inside the Linux VM, and
phones, tablets, watches and TVs paired over Wi-Fi. One of them is the target: builds install and run there, the
logcat reads it, and adb in Forge's terminal and in the agent's commands talks to it.

Two adb servers keep the rest of the Googlebook safe. The Googlebook stays alone on the shared adb server (port
5037), which other Googlebook tools use with a plain `adb shell`; a second device there would make those tools fail
with "more than one device". Virtual and paired devices live on Forge's own adb server (port 5041). Every adb call
names its server and its device and has a time limit, and every reply is read while adb writes it."""
from __future__ import annotations

import os
import re
import shlex
import shutil
import subprocess
import threading
import time
from typing import Dict, List, Optional, Tuple

ADB = shutil.which("adb") or "adb"
MAIN_PORT = 5037     # the shared adb server: the Googlebook only
FORGE_PORT = 5041    # Forge's adb server: virtual devices and devices paired over Wi-Fi
HOME = os.path.expanduser("~")
TARGET_FILE = os.path.join(HOME, ".forge-ide", "adb-target")   # "PORT SERIAL", read by the terminal's adb and the agent
ADDRESS_FILES = ("/mnt/shared/Documents/Sleep Guard/adb-port.txt", "/mnt/shared/Documents/Shadow Trackpad Fix/adb-port.txt")
KEYS = {"back": 4, "home": 3, "recents": 187, "enter": 66, "delete": 67, "tab": 61, "menu": 82, "power": 26,
        "volume_up": 24, "volume_down": 25, "up": 19, "down": 20, "left": 21, "right": 22, "center": 23}

# Screen profiles for virtual devices: (label, width, height, density). Switching is live (wm size and wm density).
PROFILES: Dict[str, Tuple[str, int, int, int]] = {
    "phone": ("Phone", 1080, 2400, 420),
    "small-phone": ("Small phone", 720, 1600, 320),
    "foldable": ("Foldable, open", 1856, 2160, 420),
    "foldable-cover": ("Foldable, closed", 904, 2316, 420),
    "tablet": ("Tablet", 1600, 2560, 320),
    "watch-round": ("Round watch", 454, 454, 320),
    "watch-square": ("Square watch", 384, 384, 320),
    "tv": ("TV", 1920, 1080, 320),
    "desktop": ("Desktop window", 1920, 1080, 160),
}

_lock = threading.Lock()
_probe_cache: Dict[Tuple[int, str], Tuple[float, dict]] = {}
_gb_cache: Tuple[float, Optional[str]] = (0.0, None)
_server_checked = 0.0


def adb(args: List[str], serial: Optional[str] = None, port: int = MAIN_PORT, timeout: float = 20.0, binary: bool = False):
    """Run adb against one server (and one device) with a time limit. Returns (code, stdout, stderr)."""
    cmd = [ADB, "-P", str(port)] + (["-s", serial] if serial else []) + args
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=timeout, text=not binary, stdin=subprocess.DEVNULL)
        return r.returncode, r.stdout, r.stderr
    except subprocess.TimeoutExpired:
        return 124, b"" if binary else "", "adb did not answer in time"
    except OSError as e:
        return 127, b"" if binary else "", str(e)


def ensure_forge_server() -> None:
    """Start Forge's own adb server when it is not running (checked at most once a minute)."""
    global _server_checked
    if time.time() - _server_checked < 60:
        return
    _server_checked = time.time()
    adb(["start-server"], port=FORGE_PORT, timeout=15)


def _listed(port: int) -> List[Tuple[str, str, dict]]:
    code, out, _ = adb(["devices", "-l"], port=port, timeout=10)
    found = []
    for line in (out or "").splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and not parts[0].startswith("*"):
            found.append((parts[0], parts[1], dict(p.split(":", 1) for p in parts[2:] if ":" in p)))
    return found


def _published_addresses() -> List[str]:
    addrs = []
    for f in ADDRESS_FILES:
        try:
            with open(f, "r", encoding="utf-8") as fh:
                a = fh.read(100).split()[0]
            if re.match(r"^[\w.\-]+:\d+$", a):
                addrs.append(a)
        except (OSError, IndexError):
            pass
    return addrs


def googlebook_serial(fresh: bool = False) -> Optional[str]:
    """The Googlebook's serial on the shared adb server (its published Wireless debugging address when it is
    connected, otherwise the one device that is not Waydroid or a local container)."""
    global _gb_cache
    if not fresh and time.time() - _gb_cache[0] < 15:
        return _gb_cache[1]
    online = [s for s, st, _ in _listed(MAIN_PORT) if st == "device"]
    serial = next((a for a in _published_addresses() if a in online), None)
    if not serial:
        others = [s for s in online if not s.startswith(("127.", "192.168.240.", "emulator-", "localhost"))]
        serial = others[0] if len(others) == 1 else (others[0] if others else None)
    _gb_cache = (time.time(), serial)
    return serial


def googlebook_free_mb() -> Optional[float]:
    """MemAvailable on the Googlebook (a few hundred bytes over adb, with a time limit), or None."""
    serial = googlebook_serial()
    if not serial:
        return None
    code, out, _ = adb(["shell", "grep MemAvailable /proc/meminfo"], serial=serial, timeout=5)
    m = re.search(r"MemAvailable:\s+(\d+)\s+kB", out or "")
    return int(m.group(1)) / 1024.0 if code == 0 and m else None


def connect_googlebook() -> dict:
    """Connect the shared adb server to this Googlebook through its published Wireless debugging address."""
    addrs = _published_addresses()
    for a in addrs:
        code, out, _ = adb(["connect", a], timeout=15)
        if "connected" in (out or "") and "unable" not in (out or ""):
            googlebook_serial(fresh=True)
            return {"ok": True, "message": (out or "").strip()}
    if not addrs:
        return {"ok": False, "message": "No published Wireless debugging address was found. Turn on Wireless debugging and pair adb once."}
    return {"ok": False, "message": "adb could not connect. Check that Wireless debugging is on."}


PROBE = ("echo rel=$(getprop ro.build.version.release); echo sdk=$(getprop ro.build.version.sdk); "
         "echo make=$(getprop ro.product.manufacturer); echo model=$(getprop ro.product.model); "
         "echo chars=$(getprop ro.build.characteristics); echo oneui=$(getprop ro.build.version.oneui); "
         "echo size=$(wm size | tail -n 1); echo density=$(wm density | tail -n 1); "
         "echo watch=$(pm has-feature android.hardware.type.watch); echo tv=$(pm has-feature android.software.leanback); "
         "echo hinge=$(pm has-feature android.hardware.sensor.hinge_angle)")


def probe(serial: str, port: int, fresh: bool = False) -> dict:
    """Android version, maker, model, screen and device type, read in one short adb call (cached for a minute)."""
    key = (port, serial)
    with _lock:
        hit = _probe_cache.get(key)
    if hit and not fresh and time.time() - hit[0] < 60:
        return hit[1]
    code, out, _ = adb(["shell", PROBE], serial=serial, port=port, timeout=12)
    vals = dict(line.split("=", 1) for line in (out or "").splitlines() if "=" in line)
    size = re.search(r"(\d+)x(\d+)", vals.get("size", ""))
    dens = re.search(r"(\d+)", vals.get("density", ""))
    info = {"android": vals.get("rel", ""), "sdk": vals.get("sdk", ""), "maker": vals.get("make", ""), "model": vals.get("model", ""),
            "characteristics": vals.get("chars", ""), "width": int(size.group(1)) if size else None,
            "height": int(size.group(2)) if size else None, "density": int(dens.group(1)) if dens else None,
            "watch": vals.get("watch") == "true", "tv": vals.get("tv") == "true", "foldable": vals.get("hinge") == "true"}
    oneui = vals.get("oneui", "")
    if oneui.isdigit() and int(oneui) > 0:
        v = int(oneui)
        info["one_ui"] = f"One UI{' Watch' if info['watch'] else ''} {v // 10000}.{(v % 10000) // 100}"
    if code == 0 and info["android"]:
        with _lock:
            _probe_cache[key] = (time.time(), info)
    return info


def forget_probe(serial: str, port: int) -> None:
    with _lock:
        _probe_cache.pop((port, serial), None)


def _kind(serial: str, port: int, info: dict, gb: Optional[str]) -> str:
    chars = (info.get("characteristics") or "").lower()
    if port == FORGE_PORT and serial.startswith("127.0.0.1:"):
        return "virtual"
    if serial.startswith("192.168.240."):
        return "waydroid"
    if serial == gb or "googlebook" in (info.get("model") or "").lower():
        return "googlebook"
    if info.get("watch") or "watch" in chars:
        return "watch"
    if info.get("tv") or "tv" in chars:
        return "tv"
    if info.get("foldable"):
        return "foldable"
    if "tablet" in chars:
        return "tablet"
    return "phone"


def list_devices() -> List[dict]:
    """Every device on both adb servers, with its facts. Offline and unauthorized devices are listed too."""
    from . import vdevice
    ensure_forge_server()
    vdevice.reconnect_running()
    gb = googlebook_serial(fresh=True)
    virtual = {f"127.0.0.1:{d['port']}": d for d in vdevice.definitions()}
    out = []
    for port in (MAIN_PORT, FORGE_PORT):
        for serial, state, extra in _listed(port):
            info = probe(serial, port) if state == "device" else {}
            d = {"serial": serial, "port": port, "state": state, "model": info.get("model") or extra.get("model", "").replace("_", " "),
                 **info, "kind": _kind(serial, port, info, gb)}
            vd = virtual.get(serial) if port == FORGE_PORT else None
            if vd:
                d.update({"virtual_id": vd["id"], "name": vd["name"], "type": vd["type"]})
            elif d["kind"] == "googlebook":
                d["name"] = "This Googlebook"
            else:
                d["name"] = " ".join(x for x in (info.get("maker", "").title() if info.get("maker", "").lower() not in (info.get("model") or "").lower() else "", d["model"]) if x) or serial
            out.append(d)
    return out


# ------------------------------------------------------------------ the target device
def _read_target() -> Optional[Tuple[int, str]]:
    try:
        with open(TARGET_FILE, "r", encoding="utf-8") as fh:
            port, serial = fh.read(300).split()[:2]
        return int(port), serial
    except (OSError, ValueError):
        return None


def set_target(serial: str, port: int) -> None:
    os.makedirs(os.path.dirname(TARGET_FILE), exist_ok=True)
    with open(TARGET_FILE + ".new", "w", encoding="utf-8") as fh:
        fh.write(f"{int(port)} {serial}\n")
    os.replace(TARGET_FILE + ".new", TARGET_FILE)


def target(devs: Optional[List[dict]] = None) -> Optional[dict]:
    """The device builds and commands use: the one chosen in the Android view when it is online, otherwise a
    running virtual device, otherwise the Googlebook, otherwise any online device."""
    devs = devs if devs is not None else list_devices()
    online = [d for d in devs if d["state"] == "device"]
    saved = _read_target()
    if saved:
        for d in online:
            if (d["port"], d["serial"]) == saved:
                return d
    for kind in ("virtual", "googlebook"):
        for d in online:
            if d["kind"] == kind:
                return d
    return online[0] if online else None


def resolve(serial: Optional[str], port: Optional[int]) -> Tuple[str, int]:
    """The device a request names, or the target. Raises ValueError when there is none."""
    if serial:
        if not re.match(r"^[\w.:\-]+$", serial):
            raise ValueError("That is not a device serial.")
        p = int(port or MAIN_PORT)
        if p not in (MAIN_PORT, FORGE_PORT):
            raise ValueError("Unknown adb server.")
        return serial, p
    t = target()
    if not t:
        raise ValueError("No device is connected. Connect the Googlebook, start a virtual device or pair a device in the Android view.")
    return t["serial"], t["port"]


def is_googlebook(serial: str, port: int) -> bool:
    return port == MAIN_PORT and (serial == googlebook_serial() or serial in _published_addresses())


def env_for_commands() -> Dict[str, str]:
    """ANDROID_SERIAL and ANDROID_ADB_SERVER_PORT for one agent command, so plain `adb` reaches the target device."""
    saved = _read_target()
    if not saved:
        return {}
    return {"ANDROID_ADB_SERVER_PORT": str(saved[0]), "ANDROID_SERIAL": saved[1]}


# ------------------------------------------------------------------ pairing over Wi-Fi
ADDRESS = re.compile(r"^[A-Za-z0-9.\-]+:\d{2,5}$|^\[[0-9a-fA-F:]+\]:\d{2,5}$")


def pair(address: str, code: str) -> dict:
    address, code = address.strip(), code.strip()
    if not ADDRESS.match(address):
        return {"ok": False, "message": "Enter the pairing address and port exactly as the device shows them under Pair device with pairing code."}
    if not re.match(r"^\d{6}$", code):
        return {"ok": False, "message": "The pairing code is the six digits shown on the device."}
    ensure_forge_server()
    rc, out, err = adb(["pair", address, code], port=FORGE_PORT, timeout=30)
    text = ((out or "") + (err or "")).strip()
    return {"ok": rc == 0 and "Successfully paired" in text, "message": text[-300:] or "adb gave no answer."}


def connect(address: str) -> dict:
    address = address.strip()
    if not ADDRESS.match(address):
        return {"ok": False, "message": "Enter the device's address and port exactly as shown under Wireless debugging."}
    ensure_forge_server()
    rc, out, err = adb(["connect", address], port=FORGE_PORT, timeout=20)
    text = ((out or "") + (err or "")).strip()
    return {"ok": "connected to" in text and "unable" not in text and "failed" not in text, "message": text[-300:]}


def disconnect(serial: str, port: int) -> dict:
    if is_googlebook(serial, port):
        return {"ok": False, "message": "The Googlebook's own connection is shared with other tools, so Forge leaves it connected."}
    rc, out, err = adb(["disconnect", serial], port=port, timeout=10)
    forget_probe(serial, port)
    return {"ok": rc == 0, "message": ((out or "") + (err or "")).strip()[-300:]}


def scan() -> List[dict]:
    """Devices that announce Wireless debugging on the local network (mDNS). The Linux VM often cannot see these
    announcements, so typing the address shown on the device is the reliable way."""
    ensure_forge_server()
    rc, out, _ = adb(["mdns", "services"], port=FORGE_PORT, timeout=10)
    found = []
    for line in (out or "").splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[1].startswith("_adb"):
            found.append({"name": parts[0], "service": "pairing" if "pairing" in parts[1] else "connect", "address": parts[-1]})
    return found


# ------------------------------------------------------------------ screen, input and appearance
def screen_png(serial: str, port: int) -> Optional[bytes]:
    rc, out, _ = adb(["exec-out", "screencap", "-p"], serial=serial, port=port, timeout=12, binary=True)
    if rc != 0 or not out or not out.startswith(b"\x89PNG") or len(out) > 40_000_000:
        return None
    return out


def send_input(serial: str, port: int, kind: str, a: dict) -> dict:
    """Taps, swipes, text and keys for virtual and paired devices. The Googlebook's own screen is view only, because
    it is the screen you are working on."""
    if is_googlebook(serial, port):
        return {"ok": False, "message": "The Googlebook's own screen is view only in Forge."}

    def num(k: str, lo: int = 0, hi: int = 20000) -> str:
        v = int(float(a.get(k, 0)))
        return str(max(lo, min(hi, v)))

    if kind == "tap":
        cmd = f"input tap {num('x')} {num('y')}"
    elif kind == "long":
        cmd = f"input swipe {num('x')} {num('y')} {num('x')} {num('y')} 700"
    elif kind == "swipe":
        cmd = f"input swipe {num('x')} {num('y')} {num('x2')} {num('y2')} {num('ms', 30, 3000)}"
    elif kind == "text":
        text = str(a.get("text", ""))[:500]
        if not text:
            return {"ok": True, "message": ""}
        # `input text` takes %s for spaces; the whole text is quoted for the device's shell.
        cmd = "input text " + shlex.quote(text.replace("%", "\\%").replace(" ", "%s"))
    elif kind == "key":
        code = KEYS.get(str(a.get("key", "")))
        if code is None:
            return {"ok": False, "message": "Unknown key."}
        cmd = f"input keyevent {code}"
    else:
        return {"ok": False, "message": "Unknown input."}
    rc, out, err = adb(["shell", cmd], serial=serial, port=port, timeout=12)
    return {"ok": rc == 0, "message": ((out or "") + (err or "")).strip()[-200:]}


def set_look(serial: str, port: int, a: dict) -> dict:
    """Screen profile, dark theme, text size and rotation, for virtual devices only (on a real device these would
    change your own phone's settings)."""
    from . import vdevice
    vd = vdevice.by_serial(serial) if port == FORGE_PORT else None
    if not vd:
        return {"ok": False, "message": "Screen profiles, dark theme, text size and rotation can be changed on virtual devices."}
    cmds = []
    look = dict(vd.get("look") or {})
    if "profile" in a:
        prof = PROFILES.get(str(a["profile"]))
        if not prof:
            return {"ok": False, "message": "Unknown screen profile."}
        look["profile"] = str(a["profile"])
        cmds += [f"wm size {prof[1]}x{prof[2]}", f"wm density {prof[3]}"]
    if "dark" in a:
        look["dark"] = bool(a["dark"])
        cmds.append("cmd uimode night " + ("yes" if look["dark"] else "no"))
    if "font_scale" in a:
        fs = max(0.85, min(2.0, float(a["font_scale"])))
        look["font_scale"] = fs
        cmds.append(f"settings put system font_scale {fs:g}")
    if "rotation" in a:
        r = int(a["rotation"]) % 4
        look["rotation"] = r
        cmds += ["settings put system accelerometer_rotation 0", f"settings put system user_rotation {r}"]
    if not cmds:
        return {"ok": True, "message": ""}
    rc, out, err = adb(["shell", "; ".join(cmds)], serial=serial, port=port, timeout=20)
    vdevice.save_look(vd["id"], look)
    forget_probe(serial, port)
    return {"ok": rc == 0, "message": ((out or "") + (err or "")).strip()[-300:], "look": look}


# ------------------------------------------------------------------ install, run, screenshot on any device
def install(apk_path: str, serial: str, port: int) -> dict:
    rc, out, err = adb(["install", "-r", "-t", apk_path], serial=serial, port=port, timeout=240)
    text = ((out or "") + (err or "")).strip()
    return {"ok": rc == 0 and "Success" in text, "message": text[-600:]}


def launch(app_id: str, serial: str, port: int) -> dict:
    if not re.match(r"^[\w.]+$", app_id or ""):
        return {"ok": False, "message": "That is not a valid application id."}
    rc, out, err = adb(["shell", "monkey", "-p", app_id, "-c", "android.intent.category.LAUNCHER", "1"], serial=serial, port=port, timeout=25)
    text = ((out or "") + (err or "")).strip()
    if "No activities found" in text:
        # TV apps start from the Leanback launcher category.
        rc, out, err = adb(["shell", "monkey", "-p", app_id, "-c", "android.intent.category.LEANBACK_LAUNCHER", "1"], serial=serial, port=port, timeout=25)
        text = ((out or "") + (err or "")).strip()
    return {"ok": rc == 0 and "No activities found" not in text, "message": text[-400:]}
