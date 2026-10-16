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

import hashlib
import ipaddress
import json
import os
import re
import shlex
import shutil
import subprocess
import threading
import time
from typing import Callable, Dict, List, Optional, Tuple

from . import armvm, personas
from .devices import FORGE_PORT, PROFILES, adb, googlebook_free_mb, ensure_forge_server

HOME = os.path.expanduser("~")
VD_DIR = os.path.join(HOME, ".forge-ide", "vd")
DEFS = os.path.join(VD_DIR, "devices.json")
CONF = os.path.join(VD_DIR, "containers.conf")
BIN = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "bin")
HOST_RESERVE_MB = 2300
# SYS_BOOT: kexec and reboot (Android's init then just exits when it would restart), SYS_TIME: the VM's clock,
# SYS_MODULE: kernel modules, SYS_RAWIO: raw hardware ports, MAC_*: security policies.
CAP_DROP = "MAC_ADMIN,MAC_OVERRIDE,SYS_TIME,SYS_MODULE,SYS_RAWIO,SYS_BOOT"
# Podman allows 2048 threads per container by default; Android with Google's apps needs more (its system process
# crashed when it could not start a thread). 8192 still leaves most of the VM's 62,000 threads to everything else.
PIDS_LIMIT = 8192
SHIELD = 3          # the version of the startup script cleaning below; a new version makes new protected images
RC_DIR = os.path.join(VD_DIR, "shield")
FIRST_PORT = 6601   # outside 5555 to 5585, which every adb server scans for emulators

VERSIONS: Dict[str, dict] = {
    "17": {"label": "Android 17", "detail": "LineageOS 24.0, API 37", "image": "localhost/forge-android:17", "backend": "waydroid",
           "cap_mb": 3072, "build": "forge-vd-image 17", "size": "1.4 GB download, 3.3 GB on disk"},
    "17-google": {"label": "Android 17 with Google apps", "detail": "LineageOS 24.0 with Google Play and Play services, API 37",
                  "image": "localhost/forge-android:17-google", "backend": "waydroid", "cap_mb": 3072, "build": "forge-vd-image 17-google",
                  "size": "1.5 GB download (it shares Android 17's vendor files), 3.6 GB on disk",
                  "types": ["phone", "small-phone", "foldable", "tablet", "watch-round", "watch-square", "desktop"]},
    "tv16-google": {"label": "Android TV 16 with Google apps", "detail": "LineageOS 23.0 Android TV with Google Play and a Google TV mode, API 36",
                    "image": "localhost/forge-android:tv16-google", "backend": "waydroid", "cap_mb": 3072, "build": "forge-vd-image tv16-google",
                    "size": "1.2 GB download, 2.9 GB on disk", "types": ["tv"], "args": ["atv.setup.bt_remote_pairing=false"],
                    # Its start step predates WayDroid's Docker support: forge-init prepares /dev, and Forge writes the
                    # waydroid.prop and answers its network request (DHCP) as WayDroid's own container manager would.
                    "prop_file": True, "dhcp": True},
    "wear6": {"label": "Wear OS 6", "detail": "Google's official Wear OS 6.0 build, Android 16, API 36",
              "image": "localhost/forge-android:wear6", "backend": "google", "cap_mb": 2048, "build": "forge-vd-image wear6",
              "size": "1.1 GB download, about 6.5 GB on disk", "types": ["watch-round", "watch-square"]},
    "auto15": {"label": "Android Automotive 15 with Google Play", "detail": "Google's official Android Automotive OS build, API 35",
               "image": "localhost/forge-android:auto15", "backend": "google", "cap_mb": 3072,
               "build": "forge-vd-image auto15", "size": "download from Google's SDK repository, about 9 GB on disk", "types": ["car"]},
    "xr14": {"label": "Android XR 14 with Google Play (preview)", "detail": "Google's official Android XR developer preview build, API 34",
             "image": "localhost/forge-android:xr14", "backend": "google", "cap_mb": 3072,
             "build": "forge-vd-image xr14", "size": "download from Google's SDK repository, about 9 GB on disk", "types": ["xr"]},
    "desktop14": {"label": "Android Desktop 14", "detail": "Google's official Android desktop build, API 34",
                  "image": "localhost/forge-android:desktop14", "backend": "google", "cap_mb": 2560,
                  "build": "forge-vd-image desktop14", "size": "download from Google's SDK repository, about 6 GB on disk", "types": ["desktop"]},
    "googletv16": {"label": "Google TV 16", "detail": "Google's official Google TV build, Android TV 16, API 36",
                   "image": "localhost/forge-android:googletv16", "backend": "google", "cap_mb": 2560,
                   "build": "forge-vd-image googletv16", "size": "1 GB download, about 12 GB on disk", "types": ["tv"]},
    "pixel16": {"label": "Android 16 with Google Play", "detail": "Google's official Android 16 build with Google Play, API 36",
                "image": "localhost/forge-android:pixel16", "backend": "google", "cap_mb": 3072,
                "build": "forge-vd-image pixel16", "size": "1.9 GB download, about 9 GB on disk",
                "types": ["phone", "small-phone", "foldable", "tablet", "desktop"]},
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
VERSIONS.update(armvm.ARM_VERSIONS)   # the makers' own Android under ARM emulation (forge/armvm.py)
TYPES = {"phone": "phone", "small-phone": "small-phone", "foldable": "foldable", "tablet": "tablet",
         "watch-round": "watch-round", "watch-square": "watch-square", "tv": "tv", "desktop": "desktop", "car": "car", "xr": "xr"}
TYPE_NAMES = {"phone": "phone", "small-phone": "small phone", "foldable": "foldable", "tablet": "tablet",
              "watch-round": "round watch", "watch-square": "square watch", "tv": "TV", "desktop": "desktop",
              "car": "car", "xr": "XR headset"}
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
    for pid in personas.PERSONAS:
        xml = personas.features_xml(pid)
        if xml:
            _write_if_changed(os.path.join(VD_DIR, "features", f"persona-{pid}.xml"), xml)


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


# ------------------------------------------------------------------ protected images
# Android's startup scripts were written for a phone that Android owns. In a container they would change the VM's
# own kernel: global settings (memory overcommit and watermarks, panic when a task hangs, SysRq), the owners of kernel
# files (Android's "system" user is uid 1000, which in the VM is you, so it could suspend the VM through
# /sys/power/state or install a SafeSetID policy that kills sudo), kernel trace buffers, and the console log level.
# Forge runs every virtual device from a protected copy of its image whose startup scripts leave all of that out,
# and the root guard service puts back anything that still changes.
RC_PLACES = ("system/etc/init", "vendor/etc/init", "system_ext/etc/init", "product/etc/init", "odm/etc/init")
UEVENTD_FILES = ("system/etc/ueventd.rc", "vendor/etc/ueventd.rc", "vendor/ueventd.rc", "odm/etc/ueventd.rc")
_KERNEL_PATH = re.compile(r"^/(proc|sys)(/|$)")
_OWN_PATH = re.compile(r"^/(proc/(self|thread-self|\d+|net|sys/net)(/|$)|sys/fs/(cgroup|bpf)(/|$))")   # per process or container
_DROP_LINE = re.compile(r"^(swapon_all|loglevel|sysclktz|insmod|modprobe|start\s+llkd|exec\S*\s.*extra_free_kbytes"
                        r"|mount\s+(tracefs|debugfs))\b")


def clean_rc(text: str, ueventd: bool = False) -> Tuple[str, List[str]]:
    """Android startup script text without the lines that would change the VM. Returns the text and what was removed."""
    out, removed = [], []
    for line in text.splitlines():
        s = line.strip()
        drop = False
        if ueventd:
            drop = s.startswith("/sys/")   # permissions for the VM's own devices
        elif s and not s.startswith("#"):
            words = s.split()
            if words[0] == "onrestart" and len(words) > 1:
                words = words[1:]
            cmd = words[0]
            path = ""
            if cmd in ("write", "mkdir") and len(words) >= 2:
                path = words[1]
            elif cmd == "chmod" and len(words) >= 3:
                path = words[2]
            elif cmd == "chown" and len(words) >= 3:
                path = words[-1]
            elif cmd == "copy" and len(words) >= 3:
                path = words[2]   # copy <from> <to>: only writing into the kernel matters
            drop = bool(path and _KERNEL_PATH.match(path) and not _OWN_PATH.match(path)) or bool(_DROP_LINE.match(" ".join(words)))
        if drop:
            removed.append(s)
            out.append(line[:len(line) - len(line.lstrip())] + "# removed by Forge (it would change the Linux VM): " + s)
        else:
            out.append(line)
    return "\n".join(out) + ("\n" if text.endswith("\n") else ""), removed


# Google's official images (see forge-vd-image) need one more change. The network BPF loader of Google's Tethering
# module writes /proc/sys/net/core/bpf_jit_enable and bpf_jit_kallsyms, which exist only in the VM's own network
# namespace, and stops Android when it cannot. Right before it runs, the container's /proc/sys/net/core is covered
# with private copies of its files plus those two, so its writes stay in the container (this kernel always compiles
# BPF programs anyway). The VM's own settings are never reachable from a container's network namespace.
_NET_CORE = ("optmem_max", "rmem_default", "rmem_max", "rps_default_mask", "somaxconn", "txrehash", "wmem_default",
             "wmem_max", "xfrm_acq_expires", "xfrm_aevent_etime", "xfrm_aevent_rseqth")
# And Android's process starter loads libforge_compat.so (vd/compat.c, built into the image by forge-vd-image), which
# answers two questions a container cannot: the SELinux labels of the BPF files Google's connectivity module checks,
# and the owner change Android's system process makes on a sandboxed process's cgroup folder.
ZYGOTE_PRELOAD = "    setenv LD_PRELOAD /system/lib64/libforge_compat.so"


def with_preload(text: str) -> str:
    out = []
    for line in text.splitlines():
        out.append(line)
        if re.match(r"^service\s+zygote\S*\s", line):
            out.append(ZYGOTE_PRELOAD)
    return "\n".join(out) + ("\n" if text.endswith("\n") else "")


GOOGLE_INIT_RC = "\n".join(
    ["# Added by Forge for Google's images (see forge/vdevice.py): private stand-ins for the BPF loader.",
     "on load-bpf-programs", "    mkdir /dev/forge-netcore 0755 root root"] +
    [f"    copy /proc/sys/net/core/{f} /dev/forge-netcore/{f}" for f in _NET_CORE] +
    ["    write /dev/forge-netcore/bpf_jit_enable 1", "    write /dev/forge-netcore/bpf_jit_kallsyms 1",
     "    mount none /dev/forge-netcore /proc/sys/net/core bind", "", ""])


def _image_id(image: str) -> str:
    r = pod(["image", "inspect", "--format", "{{.Id}}", image], timeout=30)
    iid = r.stdout.strip()
    return iid if re.match(r"^[0-9a-f]{64}$", iid) else ""


def shield_image(image: str, progress: Optional[Callable[[str], None]] = None, google: bool = False) -> str:
    """The protected copy of an Android image (built on first use, in a few seconds). For Google's images it also
    gets GOOGLE_INIT_RC. Raises RuntimeError."""
    iid = _image_id(image)
    if not iid:
        raise RuntimeError(f"{image} is not downloaded.")
    tag = f"localhost/forge-shield:{iid[:12]}-v{SHIELD}" + ("-google2" if google else "")
    if tag in images():
        return tag
    if progress:
        progress("Preparing the protected copy of the Android image")
    work = os.path.join(RC_DIR, f"{iid[:12]}-v{SHIELD}{'-google2' if google else ''}-{int(time.time())}")   # a new folder each time, nothing is deleted
    files = os.path.join(work, "files")
    os.makedirs(files)
    r = pod(["image", "mount", image], timeout=120)
    mnt = r.stdout.strip()
    if r.returncode != 0 or not mnt.startswith("/"):
        raise RuntimeError("Could not open the image to check its startup scripts: " + (r.stderr or r.stdout).strip()[-200:])
    try:
        found = subprocess.run(["sudo", "-n", "find"] + [os.path.join(mnt, p) for p in RC_PLACES] +
                               ["-maxdepth", "2", "-type", "f", "-name", "*.rc"], capture_output=True, text=True, timeout=60).stdout.split()
        found += [os.path.join(mnt, p) for p in UEVENTD_FILES]
        report = []
        for path in found:
            rel = os.path.relpath(path, mnt)
            got = subprocess.run(["sudo", "-n", "cat", path], capture_output=True, timeout=30)
            if got.returncode != 0 or len(got.stdout) > 2_000_000:
                continue
            text = got.stdout.decode("utf-8", errors="surrogateescape")
            cleaned, removed = clean_rc(text, ueventd=rel in UEVENTD_FILES)
            if google and rel == "system/etc/init/hw/init.rc":
                cleaned = GOOGLE_INIT_RC + cleaned
                removed.append("added private stand-ins for the BPF loader")
            if google and re.match(r"^system/etc/init/hw/init\.zygote", rel):
                cleaned = with_preload(cleaned)
                removed.append("process starter loads libforge_compat.so")
            if removed:
                dest = os.path.join(files, rel)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, "w", encoding="utf-8", errors="surrogateescape") as fh:
                    fh.write(cleaned)
                os.chmod(dest, 0o644)
                report.append(f"/{rel}: " + "; ".join(removed))
    finally:
        pod(["image", "unmount", image], timeout=60)
    with open(os.path.join(work, "removed.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(report) + "\n")
    with open(os.path.join(work, "Containerfile"), "w", encoding="utf-8") as fh:
        fh.write(f"FROM {image}\nCOPY files/ /\nLABEL forge.shield={SHIELD} forge.base={iid}\n")
    b = pod(["build", "--pull=never", "-q", "-t", tag, "-f", os.path.join(work, "Containerfile"), work], timeout=600)
    if b.returncode != 0:
        raise RuntimeError("Could not build the protected image: " + (b.stderr or b.stdout).strip()[-300:])
    return tag


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
            ("/sys/devices/virtual/block/zram0/writeback", 0o200), ("/sys/fs/pstore", 0o750), ("/sys/firmware/acpi/tables", 0o755))


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


def create(version: str, dev_type: str, name: str = "", persona: str = "") -> dict:
    if version not in VERSIONS:
        raise ValueError("Unknown Android version.")
    pers = personas.PERSONAS.get(persona) if persona else None
    if persona and not pers:
        raise ValueError("Unknown persona.")
    if pers:
        dev_type = pers["kind"]
    if dev_type not in TYPES:
        raise ValueError("Unknown device type.")
    allowed = VERSIONS[version].get("types")
    if allowed and dev_type not in allowed:
        raise ValueError(f"{VERSIONS[version]['label']} is for " + ("TV devices." if allowed == ["tv"] else "phones, tablets, watches and desktops, not TVs."))
    with _lock:
        defs = definitions()
        base = f"android{version}-{persona or dev_type}"
        dev_id, n = base, 2
        while any(d["id"] == dev_id for d in defs):
            dev_id, n = f"{base}-{n}", n + 1
        used = {d["port"] for d in defs}
        port = next(p for p in range(FIRST_PORT, FIRST_PORT + 400) if p not in used)
        d = {"id": dev_id, "name": name.strip()[:60] or (f"{pers['name']} persona ({VERSIONS[version]['label']})" if pers else
                                                         f"{VERSIONS[version]['label']} {TYPE_NAMES[dev_type]}"),
             "version": version, "type": dev_type, "port": port, "created": time.time(),
             "look": {"profile": f"persona:{persona}" if pers else TYPES[dev_type]}}
        if pers:
            d["persona"] = persona
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
    """The device's current container. Each protected image gets its own container (the Android data lives in the
    device's volume, so a new container keeps it); older containers stay stopped until you remove them."""
    return d.get("container") or "forge-vd-" + d["id"]


def _use_container(d: dict, name: str) -> None:
    with _lock:
        defs = definitions()
        for e in defs:
            if e["id"] == d["id"] and e.get("container") != name:
                old = e.get("container") or "forge-vd-" + e["id"]
                e.setdefault("old_containers", [])
                if old not in e["old_containers"]:
                    e["old_containers"].append(old)
                e["container"] = name
        _save(defs)
    d["container"] = name


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
    if VERSIONS.get(d["version"], {}).get("backend") == "arm":
        return armvm.running(d)
    return conmon_running(container(d))


def memory_mb(d: dict) -> Optional[int]:
    if VERSIONS.get(d["version"], {}).get("backend") == "arm":
        return armvm.memory_mb(d)
    r = pod(["inspect", "-f", "{{.Id}}", container(d)], timeout=20)
    cid = r.stdout.strip()
    if r.returncode != 0 or not re.match(r"^[0-9a-f]{64}$", cid):
        return None
    # Android's own memory: anonymous, shared and kernel memory. The rest of the cgroup's usage is file cache, which
    # the kernel gives back whenever something needs it, so counting it would make a device look full.
    for base in ("/sys/fs/cgroup/machine.slice", "/sys/fs/cgroup"):
        p = os.path.join(base, f"libpod-{cid}.scope", "memory.stat")
        try:
            with open(p) as fh:
                stat = {k: int(v) for k, v in (line.split() for line in fh if line.count(" ") == 1)}
            return (stat.get("anon", 0) + stat.get("shmem", 0) + stat.get("kernel", 0)) // 1048576
        except (OSError, ValueError):
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
             "installed": armvm.installed(v) if info["backend"] == "arm" else info["image"] in present,
             "job": _jobs.get(v), "types": info.get("types"), "maker": info.get("maker")} for v, info in VERSIONS.items()]
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
    shield = {}
    try:
        with open("/run/forge-android-guard/shield.json") as fh:
            g = json.load(fh)
        recent = [r for r in g.get("recent", []) if time.time() - r.get("time", 0) < 900]
        shield = {"fixed": len(recent), "fresh": time.time() - g.get("time", 0) < 60,
                  "hibernation_blocked": bool(g.get("hibernation_blocked")),
                  "warning": next((r["fix"] for r in recent if r["fix"].startswith("WARNING")), "")}
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return {"available": podman_available(), "devices": devs, "versions": vers, "types": TYPES, "notices": notices,
            "guard": _guard_active(), "shield": shield}


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


# ------------------------------------------------------------------ network for WayDroid's older builds
# Android's Ethernet service takes over eth0: it clears the address Podman gave the container and asks for one by
# DHCP, as a TV does on a home network. WayDroid's own container manager answers that with dnsmasq on its bridge, and
# Forge does the same on Podman's bridge for the images that need it: each such device gets a fixed hardware address
# and network address, and one small dnsmasq (DHCP only for those addresses, plus DNS passed on to the VM's resolver)
# runs while any of them runs. It listens only on Podman's bridge.
DHCP_UNIT = "forge-vd-dhcp"
# The images built by forge-vd-image (WayDroid-ATV and Google's) start from a fresh container every time (see _boot).
EPHEMERAL = ("waydroid", "google")


def _adb_public_key() -> str:
    """The public key of the adb servers on this VM (Forge's included), for Google's signed builds."""
    with open(os.path.join(HOME, ".android", "adbkey.pub")) as f:
        return f.read().strip()
_net_cache: Dict[str, tuple] = {}


def _podman_net() -> Tuple[str, ipaddress.IPv4Interface]:
    """Podman's default network as Podman reports it: the bridge's name, and its gateway with the network's prefix."""
    if "net" not in _net_cache:
        r = pod(["network", "inspect", "podman"], timeout=20)
        net = json.loads(r.stdout)[0]
        sub = next(x for x in net["subnets"] if ":" not in x["subnet"])   # the IPv4 network
        _net_cache["net"] = (net["network_interface"], ipaddress.ip_interface(f"{sub['gateway']}/{sub['subnet'].split('/')[1]}"))
    return _net_cache["net"]


def _net_identity(d: dict) -> Tuple[str, str]:
    """A fixed hardware address and network address for a device. The addresses come from the top of Podman's
    network, and Podman hands out its own from the bottom, so the two do not meet."""
    i = d["port"] - FIRST_PORT
    mac = "02:f0:" + ":".join(f"{b:02x}" for b in hashlib.sha1(d["id"].encode()).digest()[:4])
    return mac, str(_podman_net()[1].network.broadcast_address - 2 - i)


def _dhcp_devices() -> List[dict]:
    return [d for d in definitions() if VERSIONS.get(d["version"], {}).get("dhcp")]


def _sudo(args: List[str], timeout: float = 30) -> subprocess.CompletedProcess:
    return subprocess.run(["sudo", "-n"] + args, capture_output=True, text=True, timeout=timeout)


def ensure_dhcp() -> Optional[str]:
    """(Re)starts the DHCP service for the devices that need it, right after such a device's container started: Podman
    creates its bridge only while a container runs, and a fresh start never sits on a bridge that was replaced. Their
    addresses are fixed, so a running device simply renews with the new instance. Returns an error text or None."""
    try:
        bridge, gw = _podman_net()
        hosts = [f"--dhcp-host={mac},{ip}" for mac, ip in (_net_identity(d) for d in _dhcp_devices())]
    except (OSError, ValueError, KeyError, IndexError, StopIteration, subprocess.SubprocessError) as e:
        return f"Podman's network could not be read: {e}"
    cmd = ["/usr/sbin/dnsmasq", "--keep-in-foreground", "--conf-file=/dev/null", "--pid-file=", f"--interface={bridge}",
           "--except-interface=lo", "--bind-interfaces", f"--listen-address={gw.ip}", "--no-hosts", "--leasefile-ro",
           f"--dhcp-range={gw.network.network_address},static,{gw.network.netmask},12h", "--dhcp-ignore=tag:!known",
           f"--dhcp-option=option:router,{gw.ip}", f"--dhcp-option=option:dns-server,{gw.ip}", "--log-facility=-"] + hosts
    _sudo(["systemctl", "stop", DHCP_UNIT])
    _sudo(["systemctl", "reset-failed", DHCP_UNIT])
    r = _sudo(["systemd-run", "--quiet", f"--unit={DHCP_UNIT}", "-p", "MemoryMax=32M", "-p", "TasksMax=8",
               "--description=Forge: DHCP and DNS for virtual Android TVs on the Podman bridge"] + cmd)
    if r.returncode != 0:
        return "The network service for the device did not start: " + (r.stderr or r.stdout).strip()[-200:]
    time.sleep(1)
    if _sudo(["systemctl", "is-active", "--quiet", DHCP_UNIT]).returncode != 0:
        log = _sudo(["journalctl", "-u", DHCP_UNIT, "-n", "3", "--no-pager", "-o", "cat"]).stdout.strip()
        return "The network service for the device stopped: " + log[-200:]
    return None


def stop_dhcp_if_idle(stopping: str = "") -> None:
    """Stops the DHCP service when no device that needs it runs (the one being stopped does not count, because its
    container can take a moment to disappear)."""
    if not any(running(d) for d in _dhcp_devices() if d["id"] != stopping):
        _sudo(["systemctl", "stop", DHCP_UNIT])


def _run_args(d: dict, image: str) -> List[str]:
    info = VERSIONS[d["version"]]
    prof = PROFILES[(d.get("look") or {}).get("profile") or TYPES[d["type"]]]
    cap = info["cap_mb"]
    args = ["run", "-d"] + (["--rm"] if info["backend"] in EPHEMERAL else []) + ["--privileged", f"--cap-drop={CAP_DROP}", "--name", container(d), "--label", "forge.vd=" + d["id"],
            f"--memory={cap}m", f"--memory-swap={cap}m", "--cpus=4", f"--pids-limit={PIDS_LIMIT}", "-p", f"127.0.0.1:{d['port']}:5555",
            "-v", f"forge-vd-{d['id']}-data:/data"]
    if d["type"] == "tv":
        args += ["-v", f"{os.path.join(VD_DIR, 'features', 'tv.xml')}:/vendor/etc/permissions/forge-device-type.xml:ro"]
    if personas.features_xml(d.get("persona")):
        args += ["-v", f"{os.path.join(VD_DIR, 'features', 'persona-' + d['persona'] + '.xml')}:/vendor/etc/permissions/forge-persona.xml:ro"]
    if info.get("dhcp"):
        mac, ip = _net_identity(d)
        args += ["--mac-address", mac, "--ip", ip]
    if info.get("prop_file"):
        props = ["gralloc.gbm.device=/dev/dri/renderD128", "persist.waydroid.multi_windows=false", "ro.adb.secure=0",
                 "sys.use_memfd=true", "waydroid.background_start=true", f"waydroid.host.gid={os.getgid()}",
                 f"waydroid.host.uid={os.getuid()}", "waydroid.pulse_runtime_path=/run/xdg/pulse", "waydroid.stub_sensors_hal=1",
                 "waydroid.wayland_display=wayland-0", "waydroid.xdg_runtime_dir=/run/xdg",
                 # The graphics settings WayDroid's container manager writes for a machine with a render node.
                 "ro.hardware.gralloc=minigbm_gbm_mesa", "ro.hardware.egl=mesa", "ro.opengles.version=196610",
                 "ro.vndk.lite=true", "waydroid.updater.disabled=true"] + info.get("args", [])
        # This build does not turn the start arguments into properties, so the type and persona ones go here too.
        props += (["ro.build.characteristics=tv"] if d["type"] == "tv" else []) + personas.init_args(d.get("persona"))
        prop = os.path.join(VD_DIR, "props", f"{d['id']}.prop")
        os.makedirs(os.path.dirname(prop), exist_ok=True)
        _write_if_changed(prop, "# Written by Forge, as WayDroid's container manager would\n" + "\n".join(props) + "\n")
        args += ["-v", f"{prop}:/vendor/waydroid.prop:ro"]
    if info["backend"] == "waydroid":
        args += ["-v", f"{os.path.join(VD_DIR, 'empty')}:/.dockerenv:ro",
                 "-v", f"/run/user/{os.getuid()}/forge-vd-{d['id']}:/run/xdg/wayland-0",
                 image, "gralloc.gbm.device=/dev/dri/renderD128", "waydroid.xdg_runtime_dir=/run/xdg",
                 "waydroid.wayland_display=wayland-0", "ro.adb.secure=0", f"waydroid.host.uid={os.getuid()}",
                 f"waydroid.host.gid={os.getgid()}", "waydroid.background_start=true", "persist.waydroid.multi_windows=false"]
    else:
        args += [image, f"androidboot.redroid_width={prof[1]}", f"androidboot.redroid_height={prof[2]}",
                 f"androidboot.redroid_dpi={prof[3]}", "androidboot.redroid_gpu_mode=guest", "androidboot.use_memfd=true"]
    if d["type"] == "tv":
        args.append("ro.build.characteristics=tv")
    persona = personas.init_args(d.get("persona"))
    if info["backend"] in ("redroid", "google"):
        # Redroid's init reads its arguments as a kernel command line, which splits at spaces unless a value is quoted
        # (a model such as "Galaxy Watch8" would otherwise become "Galaxy").
        persona = [f'{a.split("=", 1)[0]}="{a.split("=", 1)[1]}"' if " " in a else a for a in persona]
    args += info.get("args", []) + persona
    return args


def _in_android(d: dict, script: str, timeout: float = 30) -> subprocess.CompletedProcess:
    return pod(["exec", container(d), "/system/bin/sh", "-c", script], timeout=timeout)


def persona_display_ok(d: dict) -> bool:
    """True when the persona's cutout overlay is switched on (or the persona has none)."""
    p = personas.PERSONAS.get(d.get("persona") or "")
    if not p or not p["cutout"]:
        return True
    r = _in_android(d, "cmd overlay list android", timeout=20)
    return "[x] com.android.shell:forge_cut" in r.stdout


def apply_persona_display(d: dict) -> str:
    """The persona's cutout and navigation mode, through overlays that need Android's root shell (so they are made
    from outside, with podman exec). Older Android versions cannot take a custom cutout; the rest still applies."""
    r = _in_android(d, personas.display_script(d.get("persona")), timeout=40)
    out = (r.stdout + r.stderr).strip()
    return "" if r.returncode == 0 and "Error" not in out else out[-300:]


def start(dev_id: str, on_ready: Optional[Callable[[dict], None]] = None) -> dict:
    d = get(dev_id)
    if not d:
        return {"ok": False, "message": "There is no such virtual device."}
    if _progress.get(dev_id, {}).get("phase") in ("starting", "booting"):
        return {"ok": True, "message": f"{d['name']} is already starting."}
    if running(d, fresh=True):
        return {"ok": True, "message": f"{d['name']} is already running."}
    info = VERSIONS[d["version"]]
    if info["backend"] == "arm":
        why = memory_block(d)
        if why:
            return {"ok": False, "message": why}
        _set(dev_id, "starting", "Starting the ARM emulation")
        r = armvm.start(d, lambda phase, msg: _set(dev_id, phase, msg), on_ready)
        if not r["ok"]:
            _set(dev_id, "failed", r["message"])
        return r
    if not podman_available():
        return {"ok": False, "message": "Podman is not installed (or sudo needs a password), so virtual devices cannot run."}
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
        try:
            image = shield_image(info["image"], lambda m: _set(d["id"], "starting", m), google=info["backend"] == "google")
        except RuntimeError as e:
            _set(d["id"], "failed", str(e))
            _stop_display(d)
            return
        # WayDroid's start step converts /etc on the first boot and then deletes files in /system/etc (among them
        # task_profiles.json, without which Android's storage service aborts) when it finds that already done. So the
        # WayDroid-based images start from a fresh container every time; Podman discards it when the device stops,
        # and the Android data stays in the device's volume. Redroid's containers are reused.
        ephemeral = info["backend"] in EPHEMERAL
        name = f"forge-vd-{d['id']}-{image.split(':')[-1]}" + ("-e" if ephemeral else "")
        n = 2
        while ephemeral and pod(["container", "exists", name], timeout=20).returncode == 0:
            name = f"forge-vd-{d['id']}-{image.split(':')[-1]}-e{n}"   # one left behind when the VM stopped abruptly
            n += 1
        _use_container(d, name)
        exists = pod(["container", "exists", container(d)], timeout=20).returncode == 0
        if exists:   # containers made before the thread limit was raised get it now
            pod(["update", "--pids-limit", str(PIDS_LIMIT), container(d)], timeout=30)
        r = pod(["start", container(d)] if exists else _run_args(d, image), timeout=120)
        if r.returncode != 0:
            _set(d["id"], "failed", "The container did not start: " + (r.stderr or r.stdout).strip()[-300:])
            _stop_display(d)
            return
        if info.get("dhcp"):
            err = ensure_dhcp()
            if err:
                pod(["kill", container(d)], timeout=40)
                _set(d["id"], "failed", err)
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
                stop_dhcp_if_idle(d["id"])
                return
            if info["backend"] in EPHEMERAL and not provisioned:
                b = _in_android(d, "getprop sys.boot_completed", timeout=15).stdout.strip()
                if b == "1" and info["backend"] == "google":
                    # Google's signed builds accept only authorized adb keys: Forge's key is installed, as accepting
                    # "Allow USB debugging" on a real device does, and adb listens on the container's port.
                    _in_android(d, f"mkdir -p /data/misc/adb; echo {shlex.quote(_adb_public_key())} > /data/misc/adb/adb_keys; "
                                   "chown 1000:2000 /data/misc/adb/adb_keys; chmod 0640 /data/misc/adb/adb_keys; "
                                   "settings put global adb_enabled 1; setprop service.adb.tcp.port 5555; "
                                   "setprop ctl.restart adbd", timeout=40)
                if b == "1":
                    # A fresh LineageOS waits in its setup wizard and refuses adb until the device counts as set up.
                    # A container has no battery, and Wear OS will not start before 20 percent: it is full and plugged in.
                    _in_android(d, "settings put global device_provisioned 1; settings put secure user_setup_complete 1; "
                                   "settings put global development_settings_enabled 1; settings put global adb_enabled 1; "
                                   "for w in org.lineageos.setupwizard com.google.android.setupwizard com.google.android.tungsten.setupwraith "
                                   "com.google.android.wearable.setupwizard; do pm disable-user --user 0 $w >/dev/null 2>&1; done; "
                                   + ("cmd battery set ac 1; cmd battery set level 100; " if info["backend"] == "google" else "") +
                                   "setprop ctl.start adbd; input keyevent 3", timeout=40)
                    provisioned = True
            adb(["connect", serial], port=FORGE_PORT, timeout=8)
            rc, out, _ = adb(["shell", "getprop sys.boot_completed"], serial=serial, port=FORGE_PORT, timeout=8)
            if rc == 0 and (out or "").strip() == "1":
                break
            time.sleep(2)
        else:
            # A device that never finished starting is stopped, so it does not keep its memory while nobody uses it.
            pod(["kill", container(d)], timeout=40)
            subprocess.run(["systemctl", "--user", "stop", f"forge-vd-{d['id']}-watchdog"], capture_output=True, timeout=20)
            _stop_display(d)
            stop_dhcp_if_idle(d["id"])
            _set(d["id"], "failed", "Android did not finish starting within 4 minutes, so Forge stopped it. "
                                    "The Android view's notices or the Forge log say why.")
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
        # Android reports itself booted before its window and overlay services have settled (the Google build takes
        # longest), so the look is applied again until Android shows it took effect.
        want_density = f"Override density: {prof[3]}" if prof and info["backend"] == "waydroid" else ""
        for _ in range(6):
            adb(["shell", "; ".join(cmds)], serial=serial, port=FORGE_PORT, timeout=20)
            if not want_density:
                break
            rc, out, _ = adb(["shell", "wm density"], serial=serial, port=FORGE_PORT, timeout=10)
            if want_density in (out or ""):
                break
            time.sleep(3)
        if not d.get("persona") and (look.get("profile") or TYPES[d["type"]]) == "watch-round":
            # Apps learn that a watch is round from this flag (Configuration.isScreenRound); Google's emulator sets
            # it from its own vendor partition, which is not used here.
            _in_android(d, "cmd overlay fabricate --target android --name forge_round "
                           "android:bool/config_mainBuiltInDisplayIsRound 0x12 1; cmd overlay enable com.android.shell:forge_round", timeout=40)
        if d.get("persona"):
            _set(d["id"], "booting", "Giving the screen the persona's cutout and navigation")
            for _ in range(6):
                if apply_persona_display(d) == "" and persona_display_ok(d):
                    break
                time.sleep(3)
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
    if VERSIONS[d["version"]]["backend"] == "arm":
        r = armvm.stop(d)
        _set(dev_id, "stopped", "")
        return r
    adb(["shell", "sync"], serial=serial, port=FORGE_PORT, timeout=15)
    r = pod(["kill", container(d)], timeout=40)
    adb(["disconnect", serial], port=FORGE_PORT, timeout=8)
    subprocess.run(["systemctl", "--user", "stop", f"forge-vd-{d['id']}-watchdog"], capture_output=True, timeout=20)
    _stop_display(d)
    if VERSIONS.get(d["version"], {}).get("dhcp"):
        stop_dhcp_if_idle(d["id"])
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
    if info["backend"] == "arm":
        cmd = ["systemd-run", "--user", "--wait", "--quiet", "--pipe", "-p", "MemoryMax=1300M", "-p", "MemorySwapMax=0"] + armvm.download_command(version)
    elif info["backend"] in EPHEMERAL:
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
