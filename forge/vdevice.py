"""Virtual Android devices for Forge: real Android (11 to 17) running in containers inside the Linux VM, without
hardware virtualization, the way Android's own cloud test farms run it.

Android 11 to 16 come from the open-source Redroid project (Docker Hub). Android 17 is LineageOS 24.0 from the
WayDroid-ATV project, packaged into a container image by `forge-vd-image 17`; it draws into its own invisible Weston
display. Each virtual device is a container with its own data volume, so apps and settings stay between runs.

Safety, all measured on this Googlebook (2026-10-15):
- Memory: a hard cap per device (Android 16 used 1.5 GB, Android 17 about 1 GB plus file cache that the cap
  reclaims), a start gate that needs the cap plus 2.3 GB free on the Googlebook, and a watchdog (forge-vd-watchdog,
  its own systemd unit) that returns the VM's file cache below 3.8 GB free and stops the device below 2.9 GB.
- Android's own shutdown can signal every process in the VM through /proc/sysrq-trigger, so Forge never asks
  Android to power off: it stops devices from outside, and the root service forge-android-guard ends any Android
  that starts to shut down by itself, before it gets that far.
- Containers drop the capabilities to load SELinux policies or kernel modules, do raw I/O and set the clock.
- Forge's adb server (port 5041) holds the devices, never the shared one other Googlebook tools use."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import threading
import time
from typing import Callable, Dict, List, Optional

from .devices import FORGE_PORT, PROFILES, adb, googlebook_free_mb, ensure_forge_server

HOME = os.path.expanduser("~")
VD_DIR = os.path.join(HOME, ".forge-ide", "vd")
DEFS = os.path.join(VD_DIR, "devices.json")
CONF = os.path.join(VD_DIR, "containers.conf")
BIN = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "bin")
HOST_RESERVE_MB = 2300
CAP_DROP = "MAC_ADMIN,MAC_OVERRIDE,SYS_TIME,SYS_MODULE,SYS_RAWIO"
FIRST_PORT = 6601   # outside 5555 to 5585, which every adb server scans for emulators

VERSIONS: Dict[str, dict] = {
    "17": {"label": "Android 17", "detail": "LineageOS 24.0, API 37", "image": "localhost/forge-android:17", "backend": "waydroid",
           "cap_mb": 3072, "build": "forge-vd-image 17", "size": "1.4 GB download, 3.3 GB on disk"},
    "16": {"label": "Android 16", "detail": "AOSP, API 36", "image": "docker.io/redroid/redroid:16.0.0_64only-latest", "backend": "redroid", "cap_mb": 2560, "size": "0.8 GB download"},
    "15": {"label": "Android 15", "detail": "AOSP, API 35", "image": "docker.io/redroid/redroid:15.0.0_64only-latest", "backend": "redroid", "cap_mb": 2560, "size": "0.7 GB download"},
    "14": {"label": "Android 14", "detail": "AOSP, API 34", "image": "docker.io/redroid/redroid:14.0.0_64only-latest", "backend": "redroid", "cap_mb": 2560, "size": "0.7 GB download"},
    "13": {"label": "Android 13", "detail": "AOSP, API 33", "image": "docker.io/redroid/redroid:13.0.0_64only-latest", "backend": "redroid", "cap_mb": 2560, "size": "0.7 GB download"},
    "12": {"label": "Android 12", "detail": "AOSP, API 31", "image": "docker.io/redroid/redroid:12.0.0_64only-latest", "backend": "redroid", "cap_mb": 2560, "size": "0.6 GB download"},
    "11": {"label": "Android 11", "detail": "AOSP, API 30", "image": "docker.io/redroid/redroid:11.0.0-latest", "backend": "redroid", "cap_mb": 2560, "size": "0.9 GB download"},
}
# Device types: the screen profile a device starts with, and Android features for the type. A true watch type is
# not possible: phone builds of Android crash when told they are a watch (the Wear OS services are missing), so
# watches are watch-sized, round-masked screens.
TYPES = {"phone": "phone", "small-phone": "small-phone", "foldable": "foldable", "tablet": "tablet",
         "watch-round": "watch-round", "watch-square": "watch-square", "tv": "tv", "desktop": "desktop"}
TYPE_NAMES = {"phone": "phone", "small-phone": "small phone", "foldable": "foldable", "tablet": "tablet",
              "watch-round": "round watch", "watch-square": "square watch", "tv": "TV", "desktop": "desktop"}
TV_FEATURES = """<?xml version="1.0" encoding="utf-8"?>
<!-- Forge virtual device type: TV. Declares the TV features so Android uses its television UI mode. -->
<permissions>
    <feature name="android.software.leanback" />
    <feature name="android.hardware.type.television" />
</permissions>
"""
WESTON_INI = "[core]\nidle-time=0\n\n[shell]\npanel-position=none\nlocking=false\n"

_lock = threading.RLock()
_progress: Dict[str, dict] = {}     # id -> {"phase", "message", "since"}
_jobs: Dict[str, dict] = {}         # version -> {"running", "lines", "ok", "started"}


# ------------------------------------------------------------------ plumbing
def _write_if_changed(path: str, text: str) -> None:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            if fh.read() == text:
                return
    except OSError:
        pass
    with open(path + ".new", "w", encoding="utf-8") as fh:
        fh.write(text)
    os.replace(path + ".new", path)


def setup_files() -> None:
    os.makedirs(os.path.join(VD_DIR, "features"), exist_ok=True)
    # This kernel lacks nftables pieces that Podman's network tool needs, so Forge's Podman uses iptables.
    _write_if_changed(CONF, '[network]\nfirewall_driver = "iptables"\n')
    _write_if_changed(os.path.join(VD_DIR, "features", "tv.xml"), TV_FEATURES)
    _write_if_changed(os.path.join(VD_DIR, "weston-vd.ini"), WESTON_INI)
    _write_if_changed(os.path.join(VD_DIR, "empty"), "")


def podman_available() -> bool:
    return bool(shutil.which("podman")) and subprocess.run(["sudo", "-n", "true"], capture_output=True, timeout=10).returncode == 0


def pod(args: List[str], timeout: float = 60.0) -> subprocess.CompletedProcess:
    cmd = ["sudo", "-n", "env", f"CONTAINERS_CONF_OVERRIDE={CONF}", "podman"] + args
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(cmd, 124, "", "podman did not answer in time")


def images() -> set:
    r = pod(["images", "--format", "{{.Repository}}:{{.Tag}}"], timeout=30)
    return set(r.stdout.split()) if r.returncode == 0 else set()


def mem_info_vm() -> int:
    try:
        with open("/proc/meminfo") as fh:
            for line in fh:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) // 1024
    except OSError:
        pass
    return 0


# Android's start scripts hand some kernel files to its "system" user (uid and gid 1000, which in the VM is you) and
# change their modes; in a privileged container those files are the VM's own. Among them are /sys/power/state (an
# Android power service writing there would suspend the whole VM), /proc/sysrq-trigger and /proc/cmdline (which the
# Terminal's display setup reads). Forge puts them back after each start and the watchdog checks them every 10 s.
VM_FILES = (("/proc/cmdline", 0o444), ("/proc/bootconfig", 0o444), ("/proc/kmsg", 0o400), ("/proc/sysrq-trigger", 0o200),
            ("/sys/power/state", 0o644), ("/sys/power/wakeup_count", 0o644), ("/sys/devices/virtual/block/zram0/idle", 0o200),
            ("/sys/devices/virtual/block/zram0/writeback", 0o200), ("/sys/fs/pstore", 0o755), ("/sys/firmware/acpi/tables", 0o755))


def restore_vm_permissions(full: bool = False) -> List[str]:
    """Give the VM's kernel files back to root with their normal modes. `full` also looks through /sys for anything
    else Android handed to uid or gid 1000. Returns what was fixed."""
    fix = []
    for path, mode in VM_FILES:
        try:
            s = os.stat(path)
        except OSError:
            continue
        if s.st_uid or s.st_gid or (s.st_mode & 0o7777) != mode:
            fix.append(f"chown root:root {path} && chmod {mode:o} {path}")
    if full:
        try:
            r = subprocess.run(["sudo", "-n", "find", "/sys", "-xdev", "(", "-uid", "1000", "-o", "-gid", "1000", ")"],
                               capture_output=True, text=True, timeout=60)
            known = {p for p, _ in VM_FILES}
            fix += [f"chown root:root {p}" for p in r.stdout.split() if p not in known and p.startswith("/sys/")]
        except (OSError, subprocess.TimeoutExpired):
            pass
    if fix:
        subprocess.run(["sudo", "-n", "sh", "-c", "; ".join(fix)], capture_output=True, timeout=30)
    return fix


# ------------------------------------------------------------------ definitions
def definitions() -> List[dict]:
    try:
        with open(DEFS, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, list) else []
    except (OSError, ValueError):
        return []


def _save(defs: List[dict]) -> None:
    os.makedirs(VD_DIR, exist_ok=True)
    _write_if_changed(DEFS, json.dumps(defs, indent=1))


def get(dev_id: str) -> Optional[dict]:
    return next((d for d in definitions() if d["id"] == dev_id), None)


def by_serial(serial: str) -> Optional[dict]:
    return next((d for d in definitions() if f"127.0.0.1:{d['port']}" == serial), None)


def save_look(dev_id: str, look: dict) -> None:
    with _lock:
        defs = definitions()
        for d in defs:
            if d["id"] == dev_id:
                d["look"] = look
        _save(defs)


def create(version: str, dev_type: str, name: str = "") -> dict:
    if version not in VERSIONS:
        raise ValueError("Unknown Android version.")
    if dev_type not in TYPES:
        raise ValueError("Unknown device type.")
    with _lock:
        defs = definitions()
        base = f"android{version}-{dev_type}"
        dev_id, n = base, 2
        while any(d["id"] == dev_id for d in defs):
            dev_id, n = f"{base}-{n}", n + 1
        used = {d["port"] for d in defs}
        port = next(p for p in range(FIRST_PORT, FIRST_PORT + 400) if p not in used)
        d = {"id": dev_id, "name": name.strip()[:60] or f"{VERSIONS[version]['label']} {TYPE_NAMES[dev_type]}", "version": version,
             "type": dev_type, "port": port, "created": time.time(), "look": {"profile": TYPES[dev_type]}}
        defs.append(d)
        _save(defs)
        return d


def rename(dev_id: str, name: str) -> None:
    with _lock:
        defs = definitions()
        for d in defs:
            if d["id"] == dev_id:
                d["name"] = name.strip()[:60] or d["name"]
        _save(defs)


# ------------------------------------------------------------------ state
def container(d: dict) -> str:
    return "forge-vd-" + d["id"]


def conmon_running(name: str) -> bool:
    """True while Podman's monitor process for this container exists (read from /proc, so no sudo is needed)."""
    want = b"\0-n\0" + name.encode() + b"\0"
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        try:
            with open(f"/proc/{pid}/cmdline", "rb") as fh:
                cmd = fh.read(4096)
        except OSError:
            continue
        if cmd.startswith(b"/usr/bin/conmon\0") and want in cmd:
            return True
    return False


def running(d: dict, fresh: bool = False) -> bool:
    return conmon_running(container(d))


def memory_mb(d: dict) -> Optional[int]:
    r = pod(["inspect", "-f", "{{.Id}}", container(d)], timeout=20)
    cid = r.stdout.strip()
    if r.returncode != 0 or not re.match(r"^[0-9a-f]{64}$", cid):
        return None
    for base in ("/sys/fs/cgroup/machine.slice", "/sys/fs/cgroup"):
        p = os.path.join(base, f"libpod-{cid}.scope", "memory.current")
        try:
            with open(p) as fh:
                return int(fh.read()) // 1048576
        except OSError:
            continue
    return None


def touch_active(d: dict) -> None:
    try:
        with open(os.path.join(VD_DIR, d["id"] + ".active"), "w") as fh:
            fh.write(str(time.time()))
    except OSError:
        pass


def status() -> dict:
    present = images() if podman_available() else set()
    devs = []
    for d in definitions():
        run = running(d)
        p = dict(_progress.get(d["id"], {}))
        if not run and p.get("phase") in ("ready", "booting"):
            p = {"phase": "stopped", "message": ""}
        devs.append({**d, "running": run, "phase": p.get("phase") or ("ready" if run else "stopped"), "message": p.get("message", ""),
                     "serial": f"127.0.0.1:{d['port']}", "version_label": VERSIONS[d["version"]]["label"],
                     "memory_mb": memory_mb(d) if run else None})
    vers = [{"version": v, **{k: info[k] for k in ("label", "detail", "backend", "cap_mb", "size")},
             "installed": info["image"] in present, "job": _jobs.get(v)} for v, info in VERSIONS.items()]
    notices = []
    for path in (os.path.join(VD_DIR, "notices.jsonl"),):
        try:
            with open(path, "r", encoding="utf-8") as fh:
                notices = [json.loads(x) for x in fh.read().splitlines()[-5:] if x.strip()]
        except (OSError, ValueError):
            pass
    try:
        with open("/run/forge-android-guard/last.json") as fh:
            g = json.load(fh)
        notices.append({"time": g["time"], "message": "An Android in a container began to shut down by itself, so the Forge Android guard ended it to protect the Linux VM."})
    except (OSError, ValueError, KeyError):
        pass
    notices = [n for n in notices if time.time() - n.get("time", 0) < 900]
    return {"available": podman_available(), "devices": devs, "versions": vers, "types": TYPES, "notices": notices,
            "guard": _guard_active()}


def _guard_active() -> bool:
    try:
        return subprocess.run(["systemctl", "is-active", "--quiet", "forge-android-guard"], timeout=10).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def reconnect_running() -> None:
    """Keep Forge's adb server connected to running virtual devices (after a Forge restart, for example)."""
    for d in definitions():
        if _progress.get(d["id"], {}).get("phase") in ("starting", "booting"):
            continue
        if running(d):
            adb(["connect", f"127.0.0.1:{d['port']}"], port=FORGE_PORT, timeout=8)


# ------------------------------------------------------------------ start and stop
def _set(dev_id: str, phase: str, message: str = "") -> None:
    _progress[dev_id] = {"phase": phase, "message": message, "since": time.time()}


def memory_block(d: dict, vm_free: Optional[int] = None) -> Optional[str]:
    cap = VERSIONS[d["version"]]["cap_mb"]
    host = googlebook_free_mb()
    if host is None:
        return "Forge cannot read the Googlebook's free memory, so it will not start a virtual device. Connect the Googlebook in the Android view first."
    if host < cap + HOST_RESERVE_MB:
        return (f"Not enough free memory on the Googlebook for {d['name']}: {int(host)} MB free, {cap + HOST_RESERVE_MB} MB needed "
                f"(its {cap} MB limit plus 2.3 GB for the Googlebook). Stop another device or unload the Llama model first.")
    vm = vm_free if vm_free is not None else mem_info_vm()
    if vm < cap + 300:
        return f"Not enough free memory in the Linux VM for {d['name']}: {vm} MB free, {cap + 300} MB needed."
    return None


def _display_unit(d: dict) -> str:
    return f"forge-vd-{d['id']}-display"


def _start_display(d: dict) -> Optional[str]:
    """Android 17 draws into a Wayland display: an invisible Weston of its own, sized like the device."""
    prof = PROFILES[(d.get("look") or {}).get("profile") or TYPES[d["type"]]]
    sock = f"/run/user/{os.getuid()}/forge-vd-{d['id']}"
    unit = _display_unit(d)
    if subprocess.run(["systemctl", "--user", "is-active", "--quiet", unit], timeout=10).returncode != 0:
        r = subprocess.run(["systemd-run", "--user", "--quiet", f"--unit={unit}", "-p", "MemoryMax=256M", "-p", "MemorySwapMax=0",
                            "/usr/bin/weston", "--backend=headless", "--renderer=pixman", f"--width={prof[1]}", f"--height={prof[2]}",
                            f"--socket=forge-vd-{d['id']}", f"--config={os.path.join(VD_DIR, 'weston-vd.ini')}"],
                           capture_output=True, text=True, timeout=20)
        if r.returncode != 0:
            return "The invisible display for Android 17 did not start: " + (r.stderr or r.stdout).strip()[-200:]
    for _ in range(40):
        if os.path.exists(sock):
            return None
        time.sleep(0.25)
    return "The invisible display for Android 17 did not appear."


def _run_args(d: dict) -> List[str]:
    info = VERSIONS[d["version"]]
    prof = PROFILES[(d.get("look") or {}).get("profile") or TYPES[d["type"]]]
    cap = info["cap_mb"]
    args = ["run", "-d", "--privileged", f"--cap-drop={CAP_DROP}", "--name", container(d), "--label", "forge.vd=" + d["id"],
            f"--memory={cap}m", f"--memory-swap={cap}m", "--cpus=4", "-p", f"127.0.0.1:{d['port']}:5555",
            "-v", f"forge-vd-{d['id']}-data:/data"]
    if d["type"] == "tv":
        args += ["-v", f"{os.path.join(VD_DIR, 'features', 'tv.xml')}:/vendor/etc/permissions/forge-device-type.xml:ro"]
    if info["backend"] == "waydroid":
        args += ["-v", f"{os.path.join(VD_DIR, 'empty')}:/.dockerenv:ro",
                 "-v", f"/run/user/{os.getuid()}/forge-vd-{d['id']}:/run/xdg/wayland-0",
                 info["image"], "gralloc.gbm.device=/dev/dri/renderD128", "waydroid.xdg_runtime_dir=/run/xdg",
                 "waydroid.wayland_display=wayland-0", "ro.adb.secure=0", f"waydroid.host.uid={os.getuid()}",
                 f"waydroid.host.gid={os.getgid()}", "waydroid.background_start=true", "persist.waydroid.multi_windows=false"]
    else:
        args += [info["image"], f"androidboot.redroid_width={prof[1]}", f"androidboot.redroid_height={prof[2]}",
                 f"androidboot.redroid_dpi={prof[3]}", "androidboot.redroid_gpu_mode=guest", "androidboot.use_memfd=true"]
    if d["type"] == "tv":
        args.append("ro.build.characteristics=tv")
    return args


def _in_android(d: dict, script: str, timeout: float = 30) -> subprocess.CompletedProcess:
    return pod(["exec", container(d), "/system/bin/sh", "-c", script], timeout=timeout)


def start(dev_id: str, on_ready: Optional[Callable[[dict], None]] = None) -> dict:
    d = get(dev_id)
    if not d:
        return {"ok": False, "message": "There is no such virtual device."}
    if _progress.get(dev_id, {}).get("phase") in ("starting", "booting"):
        return {"ok": True, "message": f"{d['name']} is already starting."}
    if running(d, fresh=True):
        return {"ok": True, "message": f"{d['name']} is already running."}
    if not podman_available():
        return {"ok": False, "message": "Podman is not installed (or sudo needs a password), so virtual devices cannot run."}
    info = VERSIONS[d["version"]]
    if info["image"] not in images():
        return {"ok": False, "message": f"{info['label']} is not downloaded yet. Use Download in the Virtual devices section first."}
    why = memory_block(d)
    if why:
        return {"ok": False, "message": why}
    if not _guard_active():
        return {"ok": False, "message": "The Forge Android guard service is not running, and it protects the Linux VM while Android runs. "
                                        "Install it with guard/install.sh in the Forge folder."}
    setup_files()
    _set(dev_id, "starting", "Starting the container")
    threading.Thread(target=_boot, args=(d, on_ready), name="vd-boot", daemon=True).start()
    return {"ok": True, "message": f"Starting {d['name']}."}


def _boot(d: dict, on_ready: Optional[Callable[[dict], None]]) -> None:
    info = VERSIONS[d["version"]]
    try:
        if info["backend"] == "waydroid":
            err = _start_display(d)
            if err:
                _set(d["id"], "failed", err)
                return
        exists = pod(["container", "exists", container(d)], timeout=20).returncode == 0
        r = pod(["start", container(d)] if exists else _run_args(d), timeout=120)
        if r.returncode != 0:
            _set(d["id"], "failed", "The container did not start: " + (r.stderr or r.stdout).strip()[-300:])
            _stop_display(d)
            return
        touch_active(d)
        subprocess.run(["systemd-run", "--user", "--quiet", f"--unit=forge-vd-{d['id']}-watchdog", "-p", "MemoryMax=64M",
                        "/usr/bin/python3", "-I", os.path.join(BIN, "forge-vd-watchdog"), container(d), d["id"], _display_unit(d)],
                       capture_output=True, timeout=20)
        _set(d["id"], "booting", "Android is starting")
        serial = f"127.0.0.1:{d['port']}"
        ensure_forge_server()
        t0, provisioned = time.time(), False
        while time.time() - t0 < 240:
            if not running(d, fresh=True):
                _set(d["id"], "failed", "Android stopped while starting. The Android view's notices or the Forge log say why.")
                _stop_display(d)
                return
            if info["backend"] == "waydroid" and not provisioned:
                b = _in_android(d, "getprop sys.boot_completed", timeout=15).stdout.strip()
                if b == "1":
                    # A fresh LineageOS waits in its setup wizard and refuses adb until the device counts as set up.
                    _in_android(d, "settings put global device_provisioned 1; settings put secure user_setup_complete 1; "
                                   "settings put global development_settings_enabled 1; settings put global adb_enabled 1; "
                                   "pm disable-user --user 0 org.lineageos.setupwizard >/dev/null 2>&1; "
                                   "setprop ctl.start adbd; input keyevent 3", timeout=40)
                    provisioned = True
            adb(["connect", serial], port=FORGE_PORT, timeout=8)
            rc, out, _ = adb(["shell", "getprop sys.boot_completed"], serial=serial, port=FORGE_PORT, timeout=8)
            if rc == 0 and (out or "").strip() == "1":
                break
            time.sleep(2)
        else:
            _set(d["id"], "failed", "Android did not finish starting within 4 minutes.")
            return
        restore_vm_permissions(full=True)
        look = dict(d.get("look") or {})
        # The screen stays on: a test device should, and Android never starts its suspend logic (see VM_FILES).
        cmds = ["settings put global stay_on_while_plugged_in 7", "settings put system screen_off_timeout 2147483647", "svc power stayon true"]
        prof = PROFILES.get(look.get("profile") or TYPES[d["type"]])
        if prof and info["backend"] == "waydroid":
            cmds += [f"wm size {prof[1]}x{prof[2]}", f"wm density {prof[3]}"]
        if look.get("dark"):
            cmds.append("cmd uimode night yes")
        if look.get("font_scale"):
            cmds.append(f"settings put system font_scale {float(look['font_scale']):g}")
        if "rotation" in look:
            cmds += ["settings put system accelerometer_rotation 0", f"settings put system user_rotation {int(look['rotation']) % 4}"]
        adb(["shell", "; ".join(cmds)], serial=serial, port=FORGE_PORT, timeout=20)
        _set(d["id"], "ready", f"Ready after {int(time.time() - t0)} seconds")
        if on_ready:
            on_ready(d)
    except Exception as e:  # noqa: BLE001
        _set(d["id"], "failed", f"Starting failed: {e}")


def _stop_display(d: dict) -> None:
    subprocess.run(["systemctl", "--user", "stop", _display_unit(d)], capture_output=True, timeout=20)


def stop(dev_id: str) -> dict:
    """Stop a device from outside: flush Android's file writes, then end the container. Android's own power off is
    never used (see the module note about sysrq)."""
    d = get(dev_id)
    if not d:
        return {"ok": False, "message": "There is no such virtual device."}
    _set(dev_id, "stopping", "Stopping")
    serial = f"127.0.0.1:{d['port']}"
    adb(["shell", "sync"], serial=serial, port=FORGE_PORT, timeout=15)
    r = pod(["kill", container(d)], timeout=40)
    adb(["disconnect", serial], port=FORGE_PORT, timeout=8)
    subprocess.run(["systemctl", "--user", "stop", f"forge-vd-{d['id']}-watchdog"], capture_output=True, timeout=20)
    _stop_display(d)
    _set(dev_id, "stopped", "")
    ok = r.returncode == 0 or "not running" in (r.stderr or "")
    return {"ok": ok, "message": f"Stopped {d['name']}." if ok else (r.stderr or r.stdout).strip()[-300:]}


def stop_all() -> None:
    for d in definitions():
        if running(d):
            stop(d["id"])


# ------------------------------------------------------------------ downloads
def download(version: str, gate: Callable[[], Optional[str]]) -> dict:
    if version not in VERSIONS:
        return {"ok": False, "message": "Unknown Android version."}
    job = _jobs.get(version)
    if job and job.get("running"):
        return {"ok": True, "message": "The download is already running."}
    why = gate()
    if why:
        return {"ok": False, "message": why}
    try:
        st = os.statvfs(HOME)
        if st.f_bavail * st.f_frsize < 8 * 1024 ** 3:
            return {"ok": False, "message": "Less than 8 GB of disk space is free in the Linux VM."}
    except OSError:
        pass
    info = VERSIONS[version]
    if info["backend"] == "waydroid":
        cmd = ["systemd-run", "--user", "--wait", "--quiet", "--pipe", "-p", "MemoryMax=1300M", "-p", "MemorySwapMax=0",
               os.path.join(BIN, "forge-vd-image"), version]
    else:
        # The download runs inside a memory cap, so the files it writes cannot fill the VM's memory with cache.
        cmd = ["sudo", "-n", "systemd-run", "--scope", "--quiet", "-p", "MemoryMax=1G", "-p", "MemorySwapMax=0", "--",
               "env", f"CONTAINERS_CONF_OVERRIDE={CONF}", "nice", "-n", "10", "podman", "pull", info["image"]]
    _jobs[version] = {"running": True, "lines": [], "ok": None, "started": time.time()}
    threading.Thread(target=_run_job, args=(version, cmd), name="vd-download", daemon=True).start()
    return {"ok": True, "message": f"Downloading {info['label']} ({info['size']})."}


def _run_job(version: str, cmd: List[str]) -> None:
    job = _jobs[version]
    try:
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, text=True)
        assert p.stdout is not None
        start = time.time()
        for line in p.stdout:   # read while it writes; the job has an overall time limit below
            line = line.strip()
            if line:
                job["lines"] = (job["lines"] + [line[-200:]])[-12:]
            if time.time() - start > 3 * 3600:
                p.kill()
                job["lines"].append("Stopped after 3 hours.")
                break
        p.wait(timeout=60)
        job["ok"] = p.returncode == 0
    except (OSError, subprocess.TimeoutExpired) as e:
        job["ok"] = False
        job["lines"].append(str(e))
    finally:
        job["running"] = False
        try:
            subprocess.run(["sudo", "-n", "sysctl", "-q", "-w", "vm.drop_caches=1"], capture_output=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired):
            pass
