"""Emulated ARM devices: the makers' own Android (One UI, OxygenOS, ColorOS, HyperOS and the others) on this x86 computer.

The makers build their software for ARM processors only. QEMU emulates an ARM64 computer in software (there is no
hardware virtualization in the Linux VM), which runs a LineageOS virtual machine made for QEMU by the
android-lineage-qemu project, and that virtual machine boots a maker's system image (a community "GSI" port from the
Mystic GSI project) from a third disk through its own boot menu. It is slow: a full Android takes about 15 to 30
minutes to its first screen, and the emulated clock runs at about a third of real time. It runs inside a memory cap
and a CPU quota, with a hard time limit, and its screen is reachable through QEMU's monitor (screenshots and keys)
and, once Android is up, through adb on a forwarded port like every other virtual device.
"""
from __future__ import annotations

import os
import shutil
import socket
import subprocess
import threading
import time
from typing import Callable, Dict, List, Optional

from .devices import FORGE_PORT, adb

HOME = os.path.expanduser("~")
ARM_DIR = os.path.join(HOME, ".forge-ide", "vd", "arm")
BIN = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "bin")
FIRMWARE = "/usr/share/AAVMF/AAVMF_CODE.fd"
CAP_MB = 3584          # QEMU with 2 GB of guest memory and a 256 MB translation cache measured 2.65 GB anonymous plus file cache; 3 GB was too tight
DEADLINE_MIN = 240     # a running emulated device ends after four hours at the latest
BOOT_MIN = 45          # how long Forge waits for Android's first screen

# The base virtual machine (kernel, firmware, LineageOS vendor partition) and the makers' system images. Each GSI
# needs a base of the same or an older Android version; the Android 16 ports run on the Android 15 base (the
# Android 16 base's vendor policy uses a permission Samsung's policy does not know, tested 2026-10-15).
BASE = {
    # Also the source of Android 16's standard keystore2 for Samsung's ports (see forge-arm-image).
    "lineage23": {"label": "LineageOS 23.2 (Android 16) ARM virtual machine", "zip": "UTM-VM-lineage-23.2-20260917-jqssun-virtio_arm64only.zip",
                  "url": "https://github.com/jqssun/android-lineage-qemu/releases/download/v2026.09.17/UTM-VM-lineage-23.2-20260917-jqssun-virtio_arm64only.zip",
                  "size": "1.1 GB download"},
    "lineage22": {"label": "LineageOS 22.2 (Android 15) ARM virtual machine", "zip": "UTM-VM-lineage-22.2-20251011-jqssun-virtio_arm64only.zip",
                  "url": "https://github.com/jqssun/android-lineage-qemu/releases/download/v2025.10.11/UTM-VM-lineage-22.2-20251011-jqssun-virtio_arm64only.zip",
                  "size": "1 GB download"},
}
MYSTIC = "https://sourceforge.net/projects/mystic-gsi-updates/files"
ARM_VERSIONS: Dict[str, dict] = {
    "arm-oneui8": {"label": "Samsung One UI 8 (ARM, emulated)", "detail": "Samsung's One UI 8 on Android 16, a Galaxy Tab S9 port by Mystic GSI, under ARM emulation",
                   "base": "lineage22", "folder": "OneUI", "zip": "Oneui-gts9wifi-16-X710ZCS5DZA1-AB-20260211-MysticGSI.zip",
                   "size": "3.1 GB download plus the 1 GB base, 7 GB on disk", "maker": "Samsung"},
    "arm-oxygenos16": {"label": "OnePlus OxygenOS 16 (ARM, emulated)", "detail": "OnePlus's OxygenOS 16 on Android 16, a Mystic GSI port, under ARM emulation",
                       "base": "lineage22", "folder": "Oxygen", "zip": "Oxygenos-OP595DL1-16-1763388908922-AB-20260104-MysticGSI.zip",
                       "size": "5.2 GB download plus the 1 GB base", "maker": "OnePlus"},
    "arm-coloros16": {"label": "OPPO ColorOS 16 (ARM, emulated)", "detail": "OPPO's ColorOS 16 on Android 16, a Mystic GSI port, under ARM emulation",
                      "base": "lineage22", "folder": "ColorOS", "zip": "Coloros-OP64DDL1-16-1769698117230-AB-20260315-MysticGSI.zip",
                      "size": "4.5 GB download plus the 1 GB base", "maker": "OPPO"},
    "arm-hyperos3": {"label": "Xiaomi HyperOS 3 (ARM, emulated)", "detail": "Xiaomi's HyperOS 3 on Android 16, a Mystic GSI port, under ARM emulation",
                     "base": "lineage22", "folder": "HyperOS", "zip": "Hyperos-pudding-16-OS3.0.50.2.W-AB-20260122-MysticGSI.zip",
                     "size": "4 GB download plus the 1 GB base", "maker": "Xiaomi"},
    "arm-magicos10": {"label": "Honor MagicOS 10 (ARM, emulated)", "detail": "Honor's MagicOS on Android 16, a Mystic GSI port, under ARM emulation",
                      "base": "lineage22", "folder": "Honor", "zip": "Magicos-BKQ-AN00-16-eng.root-AB-20260125-MysticGSI.zip",
                      "size": "6.6 GB download plus the 1 GB base", "maker": "Honor"},
    "arm-nothingos4": {"label": "Nothing OS 4 (ARM, emulated)", "detail": "Nothing OS 4 on Android 16, a Mystic GSI port, under ARM emulation",
                       "base": "lineage22", "folder": "Nothing", "zip": "Nothing-Asteroids-16-2511181551-AB-20260111-MysticGSI.zip",
                       "size": "2.3 GB download plus the 1 GB base", "maker": "Nothing"},
    "arm-originos5": {"label": "vivo OriginOS 5 (ARM, emulated)", "detail": "vivo's OriginOS 5 on Android 15, a Mystic GSI port, under ARM emulation",
                      "base": "lineage22", "folder": "OriginOS", "zip": "Originos-PD2266-15-compiler250711173629-AB-20260208-MysticGSI.zip",
                      "size": "6.4 GB download plus the 1 GB base", "maker": "vivo"},
    "arm-realmeui7": {"label": "Realme UI 7 (ARM, emulated)", "detail": "Realme UI 7 on Android 16, a Mystic GSI port, under ARM emulation",
                      "base": "lineage22", "folder": "", "zip": "Realmeui-RE6030L1-CinnamonBun-1773651170858-AB-20260326-MysticGSI.zip",
                      "size": "7 GB download plus the 1 GB base", "maker": "Realme"},
    "arm-pixel17": {"label": "Google Pixel software, Android 17 (ARM, emulated)", "detail": "A Pixel 7 Pro Android 17 build, a Mystic GSI port, under ARM emulation",
                    "base": "lineage22", "folder": "Pixel", "zip": "Pixel-cheetah-Baklava-12992050-AB-20251223-MysticGSI.zip",
                    "size": "2.1 GB download plus the 1 GB base", "maker": "Google"},
}
for _v in ARM_VERSIONS.values():
    _v.update({"backend": "arm", "cap_mb": CAP_MB, "image": "", "types": ["tablet", "phone"], "build": "forge-arm-image"})

_procs: Dict[str, subprocess.Popen] = {}
_lock = threading.Lock()


def gsi_path(version: str) -> str:
    return os.path.join(ARM_DIR, "gsi", version, "system.img")


def base_dir(base: str) -> str:
    return os.path.join(ARM_DIR, "base", base)


def installed(version: str) -> bool:
    info = ARM_VERSIONS.get(version)
    if not info:
        return False
    b = base_dir(info["base"])
    return os.path.exists(gsi_path(version)) and _find(b, "efi_vars.fd") is not None and _find(b, ("vda.img", "vda.qcow2")) is not None


def _find(folder: str, names) -> Optional[str]:
    names = (names,) if isinstance(names, str) else names
    for root, _dirs, files in os.walk(folder):
        for n in names:
            if n in files:
                return os.path.join(root, n)
    return None


def download_command(version: str) -> List[str]:
    return [os.path.join(BIN, "forge-arm-image"), version]


def _dev_dir(d: dict) -> str:
    return os.path.join(ARM_DIR, "devices", d["id"])


def _prepare_device(d: dict) -> None:
    """A device keeps its own copy of the base disks (its data lives on the second disk)."""
    info = ARM_VERSIONS[d["version"]]
    dd = _dev_dir(d)
    if _find(dd, "efi_vars.fd"):
        return
    os.makedirs(dd, exist_ok=True)
    b = base_dir(info["base"])
    for n in ("efi_vars.fd", "vda.img", "vda.qcow2", "vdb.qcow2", "vdb.img"):
        src = _find(b, n)
        if src:
            shutil.copy2(src, os.path.join(dd, n))


def _monitor(d: dict) -> str:
    return os.path.join(_dev_dir(d), "monitor")


def monitor(d: dict, commands: List[str], wait: float = 0.6) -> str:
    """Sends human monitor commands to the device's QEMU and returns what it answered."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(8)
    out = b""
    try:
        s.connect(_monitor(d))
        s.sendall(b"\n")
        time.sleep(0.3)
        for c in commands:
            s.sendall((c + "\n").encode())
            time.sleep(wait)
            try:
                out += s.recv(65536)
            except socket.timeout:
                pass
    except OSError as e:
        return f"monitor error: {e}"
    finally:
        s.close()
    return out.decode(errors="replace")


def screenshot(d: dict, path: str) -> bool:
    """The emulated screen as a PNG, through QEMU (works before Android's adb is up)."""
    ppm = path[:-4] + ".ppm" if path.endswith(".png") else path + ".ppm"
    monitor(d, [f"screendump {ppm}"], wait=1.2)
    try:
        from PIL import Image
        Image.open(ppm).save(path)
        return True
    except Exception:  # noqa: BLE001
        return False


QEMU_KEYS = {"back": "esc", "home": "home", "recents": "f3", "enter": "ret", "backspace": "backspace", "tab": "tab",
             "up": "up", "down": "down", "left": "left", "right": "right", "space": "spc", "escape": "esc"}


def send_input(d: dict, kind: str, a: dict) -> dict:
    """Taps, swipes and keys through QEMU's monitor, for when Android's adb is not up (or never comes up). The screen
    size comes from the last QEMU screenshot; QEMU's tablet takes positions on a 0 to 32767 scale."""
    shot = os.path.join(_dev_dir(d), "screen.png")
    w, h = 1280, 800
    try:
        from PIL import Image
        with Image.open(shot) as im:
            w, h = im.size
    except Exception:  # noqa: BLE001
        pass

    def pos(xk: str, yk: str) -> str:
        x = max(0, min(w, int(float(a.get(xk, 0))))); y = max(0, min(h, int(float(a.get(yk, 0)))))
        return f"mouse_move {x * 32767 // max(1, w)} {y * 32767 // max(1, h)}"

    if kind == "tap":
        cmds = [pos("x", "y"), "mouse_button 1", "mouse_button 0"]
    elif kind == "long":
        cmds = [pos("x", "y"), "mouse_button 1", "mouse_button 1", "mouse_button 0"]
    elif kind == "swipe":
        cmds = [pos("x", "y"), "mouse_button 1", pos("x2", "y2"), "mouse_button 0"]
    elif kind == "key":
        k = QEMU_KEYS.get(str(a.get("key", "")))
        if not k:
            return {"ok": False, "message": "That key is not available through the emulator before adb is up."}
        cmds = [f"sendkey {k}"]
    elif kind == "text":
        cmds = []
        for ch in str(a.get("text", ""))[:200]:
            if ch.isalnum():
                cmds.append("sendkey " + (("shift-" + ch.lower()) if ch.isupper() else ch))
            elif ch == " ":
                cmds.append("sendkey spc")
            elif ch == "\n":
                cmds.append("sendkey ret")
    else:
        return {"ok": False, "message": "Unknown input."}
    out = monitor(d, cmds, wait=0.25 if kind != "long" else 0.7)
    return {"ok": "error" not in out.lower(), "message": ""}


def running(d: dict) -> bool:
    p = _procs.get(d["id"])
    return p is not None and p.poll() is None


def memory_mb(d: dict) -> Optional[int]:
    p = _procs.get(d["id"])
    if not p:
        return None
    try:
        r = subprocess.run(["systemctl", "--user", "show", "-p", "MemoryCurrent", f"forge-arm-{d['id']}.scope"],
                           capture_output=True, text=True, timeout=10)
        v = r.stdout.strip().split("=", 1)[-1]
        return int(v) // (1024 * 1024) if v.isdigit() else None
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return None


def start(d: dict, set_phase: Callable[[str, str], None], on_ready: Optional[Callable[[dict], None]]) -> dict:
    info = ARM_VERSIONS[d["version"]]
    if not installed(d["version"]):
        return {"ok": False, "message": f"{info['label']} is not downloaded yet. Use Download in the Virtual devices section first."}
    if not shutil.which("qemu-system-aarch64") or not os.path.exists(FIRMWARE):
        return {"ok": False, "message": "QEMU for ARM is not installed (install-extras.sh devices installs qemu-system-arm and qemu-efi-aarch64)."}
    with _lock:
        if running(d):
            return {"ok": True, "message": f"{d['name']} is already running."}
        _prepare_device(d)
        dd = _dev_dir(d)
        vars_ = _find(dd, "efi_vars.fd"); vda = _find(dd, ("vda.img", "vda.qcow2")); vdb = _find(dd, ("vdb.qcow2", "vdb.img"))
        mon = _monitor(d)
        if os.path.exists(mon):
            os.replace(mon, mon + ".old")   # a socket left by an earlier run; replaced, not deleted
        vnc = 7 + (d["port"] - 6601) % 60
        cmd = ["systemd-run", "--user", "--scope", "--quiet", f"--unit=forge-arm-{d['id']}", "-p", f"MemoryMax={CAP_MB}M",
               "-p", "MemorySwapMax=0", "-p", "CPUQuota=400%", "timeout", "--signal=TERM", "--kill-after=20", str(DEADLINE_MIN * 60),
               "nice", "-n", "5", "qemu-system-aarch64", "-machine", "virt", "-cpu", "max,pauth-impdef=on",
               "-accel", "tcg,tb-size=256,thread=multi", "-smp", "4", "-m", "2048",
               "-drive", f"if=pflash,unit=0,file={FIRMWARE},file.locking=off,format=raw,readonly=on",
               "-drive", f"if=pflash,unit=1,file={vars_}",
               "-device", "virtio-gpu-pci", "-display", f"vnc=127.0.0.1:{vnc}",
               "-device", "usb-ehci,id=usb-bus", "-device", "usb-tablet,bus=usb-bus.0", "-device", "usb-kbd,bus=usb-bus.0",
               "-device", "virtio-blk-pci,drive=vda,bootindex=0", "-device", "virtio-blk-pci,drive=vdb,bootindex=1",
               "-drive", f"file={vda},if=none,id=vda,discard=unmap,detect-zeroes=unmap",
               "-drive", f"file={vdb},if=none,id=vdb,discard=unmap,detect-zeroes=unmap",
               "-device", "virtio-blk-pci,drive=vdc",
               "-drive", f"file={gsi_path(d['version'])},if=none,id=vdc,format=raw,discard=unmap,detect-zeroes=unmap",
               "-device", "virtio-net-pci,netdev=net0", "-netdev", f"user,id=net0,hostfwd=tcp:127.0.0.1:{d['port']}-:5555",
               "-device", "virtio-serial", "-device", "virtio-rng-pci",
               "-serial", f"file:{os.path.join(dd, 'serial.txt')}", "-monitor", f"unix:{mon},server,nowait", "-nodefaults"]
        try:
            _procs[d["id"]] = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError as e:
            return {"ok": False, "message": f"QEMU did not start: {e}"}
    set_phase("booting", "ARM emulation: starting the virtual machine")
    threading.Thread(target=_boot, args=(d, set_phase, on_ready), name="arm-boot", daemon=True).start()
    return {"ok": True, "message": f"Starting {d['name']} under ARM emulation. The first screen takes 15 to 30 minutes."}


def _boot(d: dict, set_phase: Callable[[str, str], None], on_ready: Optional[Callable[[dict], None]]) -> None:
    mon = _monitor(d)
    t0 = time.time()
    for _ in range(120):
        if os.path.exists(mon):
            break
        time.sleep(0.5)
    time.sleep(12)   # the boot menu appears after about 10 seconds and counts down for 10 more
    # The LineageOS menu: the fourth line is "Advanced options"; in it, the last line boots the GSI from the third disk.
    monitor(d, ["sendkey down", "sendkey down", "sendkey down", "sendkey ret"], wait=0.8)
    time.sleep(3)
    monitor(d, ["sendkey end", "sendkey ret"], wait=0.8)
    serial = f"127.0.0.1:{d['port']}"
    while time.time() - t0 < BOOT_MIN * 60:
        if not running(d):
            set_phase("failed", "The emulated machine stopped while starting. Its serial log is in " + _dev_dir(d))
            return
        mins = int((time.time() - t0) / 60)
        set_phase("booting", f"ARM emulation: the maker's Android is starting ({mins} min so far; the first screen takes 15 to 30)")
        adb(["connect", serial], port=FORGE_PORT, timeout=8)
        rc, out, _ = adb(["shell", "getprop sys.boot_completed"], serial=serial, port=FORGE_PORT, timeout=8)
        if rc == 0 and (out or "").strip() == "1":
            set_phase("ready", f"Ready after {mins} minutes (emulated ARM; expect a slow screen)")
            if on_ready:
                on_ready(d)
            return
        time.sleep(10)
    # Slow images take longer; the device keeps running and is checked every half minute until it stops.
    while running(d):
        set_phase("booting", f"ARM emulation: still starting after {int((time.time() - t0) / 60)} minutes; it keeps running, and Screen shows the emulated display")
        adb(["connect", serial], port=FORGE_PORT, timeout=8)
        rc, out, _ = adb(["shell", "getprop sys.boot_completed"], serial=serial, port=FORGE_PORT, timeout=8)
        if rc == 0 and (out or "").strip() == "1":
            set_phase("ready", f"Ready after {int((time.time() - t0) / 60)} minutes (emulated ARM; expect a slow screen)")
            if on_ready:
                on_ready(d)
            return
        time.sleep(30)
    set_phase("failed", "The emulated machine stopped while starting. Its serial log is in " + _dev_dir(d))


def stop(d: dict) -> dict:
    p = _procs.get(d["id"])
    if not p or p.poll() is not None:
        return {"ok": True, "message": f"{d['name']} is not running."}
    monitor(d, ["quit"])
    try:
        p.wait(timeout=30)
    except subprocess.TimeoutExpired:
        subprocess.run(["systemctl", "--user", "stop", f"forge-arm-{d['id']}.scope"], capture_output=True, timeout=30)
    adb(["disconnect", f"127.0.0.1:{d['port']}"], port=FORGE_PORT, timeout=8)
    return {"ok": True, "message": f"Stopped {d['name']}."}
