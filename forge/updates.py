"""Updates and upgrades for Forge's virtual device images: every emulator image Forge offers can be brought to its
source's newest build (an update) and a newer Android of the same family can be added (an upgrade), by a click in the
Android view or by itself.

Where each family's builds come from, and what counts as an update or an upgrade:
- Android 11 to 16 (Redroid, Docker Hub): the floating tags (16.0.0_64only-latest and so on) are rebuilt by the project
  now and then, which is an update; a tag for the next Android (17.0.0_64only-latest) is an upgrade.
- Android 17, Android 17 with Google apps and Android TV 16 with Google apps (LineageOS from WayDroid-ATV, SourceForge):
  a newer dated system or vendor build of the same LineageOS version is an update; a newer LineageOS version (24.0 is
  Android 17, 25.0 would be Android 18) is an upgrade. The files are checked against the MD5 sums the project's file
  feed publishes.
- Google's own builds (Wear OS, Google TV, Android with Google Play, Automotive, Desktop, XR; Google's SDK repository
  through sdkmanager): a newer revision of the same package is an update; the same kind of package for a higher API
  level is an upgrade. An upgrade needs a Redroid vendor partition of the same Android, so it is offered only when
  Docker Hub has one.
- The makers' Android under ARM emulation (Mystic GSI ports, SourceForge): a newer build of the same port (the same
  source device) is an update; a port of the same maker for a newer Android is an upgrade. The ARM virtual machine
  itself (android-lineage-qemu, GitHub) gets updates too, and new devices use the updated machine.

Rules the installs follow: one install at a time, inside a memory cap, never while a device of that version runs,
never with less than 8 GB of disk free, and nothing is deleted (a replaced system image is kept next to the new one).
Devices keep their data: an update replaces the system under a device's own data, like a phone's system update, and
"Upgrade" on a device moves it to the newer installed version of its family with its data (Android migrates the data
as it does on a phone; an app that does not survive that can be reinstalled).

The checks run daily by themselves (setting vd_updates: "check" shows what is available, "install" also downloads and
installs it, "install-move" also moves stopped devices to the newest installed version of their family, "off"
does nothing); Check now in the Android view runs them at once. Every check has a time limit and runs in its own
thread, so a slow or absent network never blocks Forge.
"""
from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
import threading
import time
import uuid
import urllib.request
from typing import Callable, Dict, List, Optional, Tuple

from . import armvm, vdevice
from .vdevice import VERSIONS, VD_DIR, BIN, CONF

STATE_FILE = os.path.join(VD_DIR, "updates.json")
OVERRIDES = os.path.join(VD_DIR, "catalog-overrides.json")   # read by armvm and vdevice when they load
SDKROOT = os.environ.get("ANDROID_HOME") or os.path.join(os.path.expanduser("~"), "tools", "android-sdk")
SDKMANAGER = os.path.join(SDKROOT, "cmdline-tools", "latest", "bin", "sdkmanager")
DOCKER_TAGS = "https://hub.docker.com/v2/repositories/redroid/redroid/tags?page_size=100"
WAYDROID_RSS = "https://sourceforge.net/projects/waydroid-atv/rss?path=/images/{kind}/{arch}"
MYSTIC_RSS = "https://sourceforge.net/projects/mystic-gsi-updates/rss?path=/{folder}"
QEMU_RELEASES = "https://api.github.com/repos/jqssun/android-lineage-qemu/releases?per_page=30"
CHECK_EVERY = 23.5 * 3600
NET_TIMEOUT = 25

# The built-in families: which versions can replace one another (an upgrade moves a device within its family), and
# each version's rank in the family (the Android or API number).
FAMILY: Dict[str, Tuple[str, int]] = {
    "11": ("aosp", 11), "12": ("aosp", 12), "13": ("aosp", 13), "14": ("aosp", 14), "15": ("aosp", 15), "16": ("aosp", 16),
    "17": ("lineage", 17), "17-google": ("lineage-google", 17), "tv16-google": ("lineage-tv-google", 16),
    "wear6": ("google:android-wear-signed", 36), "googletv16": ("google:google-tv", 36), "pixel16": ("google:google_apis_playstore", 36),
    "auto15": ("google:android-automotive-playstore", 35), "xr14": ("google:android-xr-preview-playstore", 34), "desktop14": ("google:android-desktop", 34),
}
# The WayDroid-ATV recipes behind the built-in LineageOS versions (the same values bin/forge-vd-image has).
WAYDROID: Dict[str, dict] = {
    "17": {"variant": "VANILLA", "arch": "waydroid_x86_64", "lineage": "24.0", "base": "a17", "keep_etc": False, "google": False},
    "17-google": {"variant": "GAPPS", "arch": "waydroid_x86_64", "lineage": "24.0", "base": "a17", "keep_etc": False, "google": True},
    "tv16-google": {"variant": "GAPPS", "arch": "waydroid_tv_x86_64", "lineage": "23.0", "base": "atv16", "keep_etc": True, "google": True},
}
GOOGLE_PKG: Dict[str, str] = {
    "wear6": "system-images;android-36;android-wear-signed;x86_64", "googletv16": "system-images;android-36;google-tv;x86_64",
    "pixel16": "system-images;android-36;google_apis_playstore;x86_64", "auto15": "system-images;android-35-ext15;android-automotive-playstore;x86_64",
    "xr14": "system-images;android-34;android-xr-preview-playstore;x86_64", "desktop14": "system-images;android-34;android-desktop;x86_64",
}
GOOGLE_LABEL = {"android-wear-signed": "Wear OS", "google-tv": "Google TV", "google_apis_playstore": "Android with Google Play",
                "android-automotive-playstore": "Android Automotive with Google Play", "android-xr-preview-playstore": "Android XR with Google Play (preview)",
                "android-desktop": "Android Desktop"}
GOOGLE_TYPES = {"android-wear-signed": ["watch-round", "watch-square"], "google-tv": ["tv"], "android-automotive-playstore": ["car"],
                "android-xr-preview-playstore": ["xr"], "android-desktop": ["desktop"],
                "google_apis_playstore": ["phone", "small-phone", "foldable", "tablet", "desktop"]}
API_ANDROID = {30: 11, 31: 12, 32: 12, 33: 13, 34: 14, 35: 15, 36: 16, 37: 17, 38: 18, 39: 19}   # API level to Android version
LINEAGE_ANDROID = {"20": 13, "21": 14, "22": 15, "23": 16, "24": 17, "25": 18, "26": 19}

_lock = threading.RLock()
_state: Dict[str, object] = {}
_checking = False
_applying: Optional[str] = None     # the version whose install runs now
_auto_started = False


# ------------------------------------------------------------------ state
def _load() -> Dict[str, object]:
    global _state
    if not _state:
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            _state = data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            _state = {}
        _state.setdefault("checked", 0)
        _state.setdefault("results", {})
        _state.setdefault("installed", {})
        _state.setdefault("log", [])
    return _state


def _save() -> None:
    os.makedirs(VD_DIR, exist_ok=True)
    with open(STATE_FILE + ".new", "w", encoding="utf-8") as fh:
        json.dump(_state, fh, indent=1)
    os.replace(STATE_FILE + ".new", STATE_FILE)


def _log(message: str) -> None:
    st = _load()
    st["log"] = (st["log"] + [{"time": time.time(), "message": message[:300]}])[-40:]
    _save()


def _overrides() -> Dict[str, dict]:
    try:
        with open(OVERRIDES, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


BUILD_KEYS = ("KIND", "SYS_FILE", "VEN_FILE", "SYS_SUM", "VEN_SUM", "SUM_ALGO", "ARCH_OVERRIDE", "SDK_OVERRIDE", "BASE_OVERRIDE",
              "KEEP_ETC_OVERRIDE", "DESC_OVERRIDE", "IMG_SUFFIX", "GOOGLE_PKG_OVERRIDE", "VENDOR_IMAGE_OVERRIDE")


def _save_build(version: str, env: Optional[Dict[str, str]]) -> None:
    """Records how bin/forge-vd-image builds a version now (its "build" recipe in the overrides file), so the script
    run by hand, or by Download, builds the same image Forge installed. FORCE is your choice, so it is not kept."""
    rec = {k: str(v) for k, v in (env or {}).items() if k in BUILD_KEYS}
    if not rec:
        return
    ov = _overrides()
    ov.setdefault("build", {}).setdefault(version, {}).update(rec)
    _save_overrides(ov)


def _has_build(version: str) -> bool:
    return bool((_overrides().get("build") or {}).get(version))


def _listdir(path: str) -> List[str]:
    try:
        return os.listdir(path)
    except OSError:
        return []


def _save_overrides(data: Dict[str, dict]) -> None:
    os.makedirs(VD_DIR, exist_ok=True)
    with open(OVERRIDES + ".new", "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=1)
    os.replace(OVERRIDES + ".new", OVERRIDES)


def family_of(version: str) -> Tuple[str, int]:
    if version in FAMILY:
        return FAMILY[version]
    info = VERSIONS.get(version) or {}
    if info.get("family"):
        return str(info["family"]), int(info.get("rank", 0))
    if info.get("backend") == "arm":
        return "arm-" + str(info.get("maker", version)).lower(), _mystic_fields(info.get("zip", "")).get("android", 0)
    return version, 0


# ------------------------------------------------------------------ fetching
def _get(url: str, timeout: float = NET_TIMEOUT) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "Forge IDE update check", "Accept": "application/json, text/xml, */*"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read(8 * 1024 * 1024).decode("utf-8", "replace")


def _rss_files(url: str) -> List[dict]:
    """The files a SourceForge folder feed lists: name, download url, size, date and MD5."""
    text = _get(url)
    out = []
    for item in re.findall(r"<item>(.*?)</item>", text, re.S):
        m = re.search(r'<media:content[^>]*url="([^"]+)"[^>]*filesize="(\d+)"', item)
        link = m.group(1) if m else (re.search(r"<link>([^<]+)</link>", item) or [None, ""])[1]
        if not link:
            continue
        name = link.rstrip("/").split("/download")[0].rsplit("/", 1)[-1]
        md5 = re.search(r'<media:hash algo="md5">([0-9a-f]{32})', item)
        date = re.search(r"<pubDate>([^<]+)</pubDate>", item)
        out.append({"name": name, "url": link, "size": int(m.group(2)) if m else 0, "md5": md5.group(1) if md5 else "",
                    "date": date.group(1).strip() if date else ""})
    return out


def _gb(n: int) -> str:
    return f"{n / 1e9:.1f} GB" if n else "size unknown"


# ------------------------------------------------------------------ the checks, one per family of sources
def _podman_digests() -> Dict[str, str]:
    """Each local image's manifest digest (what Docker Hub lists for a tag), so a rebuilt tag is told from the same build pushed again."""
    r = vdevice.pod(["images", "--digests", "--format", "{{.Repository}}:{{.Tag}} {{.Digest}} {{.CreatedAt}}"], timeout=30)
    out: Dict[str, str] = {}
    for line in (r.stdout if r.returncode == 0 else "").splitlines():
        parts = line.split()
        if len(parts) >= 3:
            out[parts[0]] = parts[1] + " " + parts[2]
    return out


def _podman_comment(image: str) -> str:
    r = vdevice.pod(["image", "inspect", image, "--format", "{{.Comment}}"], timeout=30)
    return r.stdout.strip() if r.returncode == 0 else ""


def _check_redroid(present: set) -> Dict[str, dict]:
    tags = {t["name"]: t for t in json.loads(_get(DOCKER_TAGS)).get("results", [])}
    out: Dict[str, dict] = {}
    local = _podman_digests()
    top = max(int(v) for v in VERSIONS if VERSIONS[v].get("backend") == "redroid" and v.isdigit())
    for v, info in VERSIONS.items():
        if info.get("backend") != "redroid":
            continue
        tag = info["image"].split(":")[-1]
        t = tags.get(tag)
        mine = local.get(info["image"], "")
        res: Dict[str, object] = {"installed": (f"build of {mine.split(' ')[1]}" if mine else "not downloaded"),
                                  "source": f"Docker Hub redroid/redroid:{tag}" + (f", last pushed {t['last_updated'][:10]}" if t else "")}
        if t and mine and t.get("digest") and t["digest"] != mine.split(" ")[0]:
            res["update"] = {"label": f"a build pushed {t['last_updated'][:10]} (yours is the build of {mine.split(' ')[1]})", "size": _gb(int(t.get("full_size") or 0))}
        if v == str(top):
            nxt = f"{top + 1}.0.0_64only-latest"
            if nxt in tags:
                newv = f"redroid{top + 1}"
                res["upgrade"] = {"version": newv, "label": f"Android {top + 1} (Redroid)",
                                  "entry": {"label": f"Android {top + 1}", "detail": f"AOSP, API {top + 21}, from Redroid's {nxt} tag", "image": f"docker.io/redroid/redroid:{nxt}",
                                            "backend": "redroid", "cap_mb": 2560, "size": _gb(int(tags[nxt].get("full_size") or 0)) + " download",
                                            "family": "aosp", "rank": top + 1}}
        out[v] = res
    return out


def _lineage_name(name: str) -> Optional[dict]:
    m = re.match(r"lineage-(\d+)\.(\d+)-(\d{8})-([A-Z]+)-([a-z_0-9]+)-(system|vendor)\.zip$", name)
    if not m:
        return None
    return {"major": m.group(1), "minor": m.group(2), "date": m.group(3), "variant": m.group(4), "arch": m.group(5), "kind": m.group(6), "name": name}


def _check_waydroid(present: set) -> Dict[str, dict]:
    out: Dict[str, dict] = {}
    feeds: Dict[Tuple[str, str], List[dict]] = {}
    for v, info in VERSIONS.items():
        rec = WAYDROID.get(v) or (info.get("waydroid") if info.get("backend") == "waydroid" else None)
        if not rec:
            continue
        for kind in ("system", "vendor"):
            key = (kind, rec["arch"])
            if key not in feeds:
                feeds[key] = [dict(f, **p) for f in _rss_files(WAYDROID_RSS.format(kind=kind, arch=rec["arch"])) for p in [_lineage_name(f["name"])] if p]
        systems = feeds[("system", rec["arch"])]
        vendors = feeds[("vendor", rec["arch"])]
        inst = _load()["installed"].get(v) or {}
        by_forge = bool(inst)   # an update Forge installed (the first build of a version is the script's own recipe)
        if not inst and info["image"] in present:
            c = _podman_comment(info["image"])
            m = re.search(r"(VANILLA|GAPPS) (\d{8})", c); mv = re.search(r"vendor (\d{8})", c)
            inst = {"system": m.group(2) if m else "", "vendor": mv.group(1) if mv else "", "lineage": rec["lineage"]}
        major = rec["lineage"].split(".")[0]
        same = sorted([f for f in systems if f["major"] == major and f["variant"] == rec["variant"]], key=lambda f: f["date"])
        vsame = sorted([f for f in vendors if f["major"] == major and f["variant"] == "MAINLINE"], key=lambda f: f["date"])
        if by_forge and not _has_build(v):
            # Installed before Forge recorded build recipes: the feed's entries for the installed files give their
            # names and MD5 sums, so bin/forge-vd-image builds what is installed.
            s = next((f for f in same if f["date"] == str(inst.get("system"))), None)
            vv = next((f for f in vsame if f["date"] == str(inst.get("vendor"))), None)
            if s and vv:
                _save_build(v, _waydroid_env(v, rec, s, vv))
        res: Dict[str, object] = {"installed": (f"system {inst.get('system')}, vendor {inst.get('vendor')}" if inst else "not downloaded"),
                                  "source": f"WayDroid-ATV LineageOS {rec['lineage']} {rec['variant']} for {rec['arch']}"}
        if inst and same and vsame and (same[-1]["date"] > str(inst.get("system", "")) or vsame[-1]["date"] > str(inst.get("vendor", ""))):
            res["update"] = {"label": f"system {same[-1]['date']} and vendor {vsame[-1]['date']}", "size": _gb(same[-1]["size"] + vsame[-1]["size"]),
                             "system": same[-1], "vendor": vsame[-1]}
        newer = sorted([f for f in systems if int(f["major"]) > int(major) and f["variant"] == rec["variant"]], key=lambda f: (int(f["major"]), f["date"]))
        if newer:
            n = newer[-1]
            nv = sorted([f for f in vendors if f["major"] == n["major"] and f["variant"] == "MAINLINE"], key=lambda f: f["date"])
            android = LINEAGE_ANDROID.get(n["major"], int(n["major"]) - 7)
            if nv:
                fam, _rank = family_of(v)
                newid = ("tv" if "tv" in rec["arch"] else "") + str(android) + ("-google" if rec["google"] else "")
                label = (f"Android TV {android}" if "tv" in rec["arch"] else f"Android {android}") + (" with Google apps" if rec["google"] else "")
                res["upgrade"] = {"version": newid, "label": label, "size": _gb(n["size"] + nv[-1]["size"]), "system": n, "vendor": nv[-1],
                                  "entry": {"label": label, "detail": f"LineageOS {n['major']}.{n['minor']}, API {android + 20}", "image": f"localhost/forge-android:{newid}",
                                            "backend": "waydroid", "cap_mb": 3072, "build": f"forge-vd-image {newid}", "size": _gb(n["size"] + nv[-1]["size"]) + " download",
                                            "types": info.get("types"), "family": fam, "rank": android,
                                            "waydroid": {"variant": rec["variant"], "arch": rec["arch"], "lineage": f"{n['major']}.{n['minor']}", "base": f"lineage{n['major']}",
                                                         "keep_etc": rec["keep_etc"], "google": rec["google"], "sdk": android + 20}}}
        out[v] = res
    return out


def _sdk_list(gate: Callable[[], Optional[str]]) -> Tuple[Dict[str, int], Dict[str, int], str]:
    """Installed and available revisions of Google's x86_64 system images (sdkmanager --list), or an error."""
    if not os.path.exists(SDKMANAGER):
        return {}, {}, "sdkmanager is not installed (install-extras.sh android)"
    why = gate()
    if why:
        return {}, {}, why
    try:
        r = subprocess.run([SDKMANAGER, f"--sdk_root={SDKROOT}", "--list"], capture_output=True, text=True, timeout=600,
                           stdin=subprocess.DEVNULL, env={**os.environ, "JAVA_OPTS": "-Xmx512m"})
    except (OSError, subprocess.TimeoutExpired) as e:
        return {}, {}, str(e)
    installed: Dict[str, int] = {}
    available: Dict[str, int] = {}
    section = ""
    for line in r.stdout.splitlines():
        if line.startswith("Installed packages"):
            section = "installed"
        elif line.startswith("Available Packages"):
            section = "available"
        elif line.startswith("Available Updates"):
            section = "updates"
        m = re.match(r"\s+(system-images;[^ |]+;x86_64)\s+\|\s+(\d+)", line)
        if m and section == "installed":
            installed[m.group(1)] = int(m.group(2))
        elif m and section == "available":
            available[m.group(1)] = int(m.group(2))
    if not available:
        return installed, available, "sdkmanager listed no packages: " + (r.stderr or r.stdout).strip()[-200:]
    return installed, available, ""


def _check_google(present: set, gate: Callable[[], Optional[str]], tags: Optional[dict]) -> Dict[str, dict]:
    installed, available, err = _sdk_list(gate)
    out: Dict[str, dict] = {}
    for v, info in VERSIONS.items():
        if info.get("backend") != "google":
            continue
        # The built-in versions' packages, or an upgraded version's own (kept in its "google" recipe).
        pkg = GOOGLE_PKG.get(v) or str(info.get("google_pkg") or (info.get("google") or {}).get("pkg") or "")
        kind = pkg.split(";")[2] if pkg.count(";") >= 3 else ""
        api_m = re.search(r"android-(\d+)", pkg)
        api = int(api_m.group(1)) if api_m else 0
        if v in GOOGLE_PKG and (_load()["installed"].get(v) or {}) and not _has_build(v):
            # Updated before Forge recorded build recipes: each update unpacked into a dated folder next to the first.
            dates = sorted(m.group(1) for m in (re.match(r"^img-\d{8}-(\d{8})$", n) for n in _listdir(os.path.join(VD_DIR, v))) if m)
            if dates:
                _save_build(v, {"IMG_SUFFIX": "-" + dates[-1]})
        res: Dict[str, object] = {"installed": (f"revision {installed[pkg]}" if pkg in installed else "not downloaded"), "source": "Google's SDK repository, " + pkg}
        if err:
            res["error"] = err
            out[v] = res
            continue
        if pkg in installed and available.get(pkg, 0) > installed[pkg]:
            res["update"] = {"label": f"revision {available[pkg]} (yours is revision {installed[pkg]})", "size": "about 1 to 2 GB", "package": pkg, "revision": available[pkg]}
        # An upgrade: the same kind of package for a higher API level, when Redroid has a vendor for that Android.
        higher = []
        for p in available:
            parts = p.split(";")
            if len(parts) == 4 and parts[2] == kind:
                am = re.match(r"android-(\d+)", parts[1])
                if am and int(am.group(1)) > api:
                    higher.append((int(am.group(1)), p))
        if higher:
            new_api, p = max(higher)
            android = API_ANDROID.get(new_api, new_api - 20)
            vendor_tag = f"{android}.0.0_64only-latest"
            if tags is not None and vendor_tag in tags:
                newid = f"{kind.replace('_', '-')}-api{new_api}"
                label = f"{GOOGLE_LABEL.get(kind, kind)} (API {new_api})"
                res["upgrade"] = {"version": newid, "label": label, "size": "about 1 to 2 GB download, 6 to 12 GB on disk", "package": p,
                                  "entry": {"label": label, "detail": f"Google's official build, API {new_api}, package {p}", "image": f"localhost/forge-android:{newid}",
                                            "backend": "google", "cap_mb": info.get("cap_mb", 3072), "build": f"forge-vd-image {newid}",
                                            "size": "about 1 to 2 GB download, 6 to 12 GB on disk", "types": GOOGLE_TYPES.get(kind, info.get("types")),
                                            "family": f"google:{kind}", "rank": new_api,
                                            "google": {"pkg": p, "sdk": new_api, "vendor_image": f"docker.io/redroid/redroid:{vendor_tag}", "base": newid}}}
            else:
                res["upgrade_blocked"] = f"Google offers {p}, but Redroid has no Android {android} vendor image yet, so Forge cannot build it."
        out[v] = res
    return out


def _mystic_fields(name: str) -> dict:
    m = re.match(r"([A-Za-z]+)-([A-Za-z0-9_]+)-(\d+)-(.+)-AB-(\d{8})-MysticGSI\.zip$", name)
    if not m:
        return {}
    return {"maker": m.group(1), "codename": m.group(2), "android": int(m.group(3)), "build": m.group(4), "date": m.group(5)}


def _check_arm() -> Dict[str, dict]:
    out: Dict[str, dict] = {}
    feeds: Dict[str, List[dict]] = {}
    for v, info in VERSIONS.items():
        if info.get("backend") != "arm":
            continue
        folder = info.get("folder", "")
        if folder not in feeds:
            feeds[folder] = [dict(f, **p) for f in _rss_files(MYSTIC_RSS.format(folder=folder)) for p in [_mystic_fields(f["name"])] if p]
        cur = _mystic_fields(info["zip"])
        files = [f for f in feeds[folder] if f["maker"].lower() == cur.get("maker", "").lower()]
        res: Dict[str, object] = {"installed": info["zip"] if armvm.installed(v) else "not downloaded", "source": f"Mystic GSI, SourceForge folder {folder}"}
        same = sorted([f for f in files if f["codename"] == cur.get("codename") and f["android"] == cur.get("android") and f["date"] > cur.get("date", "")], key=lambda f: f["date"])
        if same and armvm.installed(v):
            n = same[-1]
            res["update"] = {"label": f"{n['name']} ({n['date'][:4]}-{n['date'][4:6]}-{n['date'][6:]})", "size": _gb(n["size"]), "zip": n["name"], "md5": n["md5"]}
        newer = sorted([f for f in files if f["android"] > cur.get("android", 0)], key=lambda f: (f["android"], f["date"]))
        if newer:
            n = newer[-1]
            newid = f"arm-{cur.get('maker', 'port').lower()}{n['android']}"
            label = f"{info.get('maker', '')} {n['maker']} for Android {n['android']} ({n['codename']} port) (ARM, emulated)".replace("  ", " ")
            res["upgrade"] = {"version": newid, "label": label, "size": _gb(n["size"]) + " plus the base machine", "zip": n["name"], "md5": n["md5"],
                              "entry": {"label": label, "detail": f"{info.get('maker', '')}'s software on Android {n['android']}, a Mystic GSI port of the {n['codename']} build, under ARM emulation",
                                        "base": info.get("base", "lineage22"), "folder": folder, "zip": n["name"], "size": _gb(n["size"]) + " download plus the 1 GB base",
                                        "maker": info.get("maker", ""), "family": f"arm-{str(info.get('maker', '')).lower()}", "rank": n["android"]}}
        out[v] = res
    # The ARM virtual machine itself.
    try:
        rel = json.loads(_get(QEMU_RELEASES))
    except Exception:  # noqa: BLE001  (GitHub limits anonymous calls; gh is logged in)
        r = subprocess.run(["gh", "api", "repos/jqssun/android-lineage-qemu/releases?per_page=30"], capture_output=True, text=True, timeout=40, stdin=subprocess.DEVNULL)
        rel = json.loads(r.stdout) if r.returncode == 0 else []
    for b, binfo in armvm.BASE.items():
        cur = re.search(r"lineage-(\d+)\.(\d+)-(\d{8})", binfo["zip"])
        if not cur:
            continue
        best = None
        for r_ in rel:
            for a in r_.get("assets", []):
                m = re.search(r"UTM-VM-lineage-(\d+)\.(\d+)-(\d{8})-jqssun-virtio_arm64only\.zip$", a["name"])
                if m and m.group(1) == cur.group(1) and m.group(3) > cur.group(3) and (best is None or m.group(3) > best[0]):
                    best = (m.group(3), a["name"], a["browser_download_url"], int(a.get("size") or 0))
        res = {"installed": binfo["zip"] if os.path.isdir(armvm.base_dir(b)) and armvm._find(armvm.base_dir(b), "efi_vars.fd") else "not downloaded", "source": "android-lineage-qemu releases on GitHub"}
        if best and res["installed"] != "not downloaded":
            res["update"] = {"label": f"{best[1]} (new devices use it; existing devices keep their own copy)", "size": _gb(best[3]), "zip": best[1], "url": best[2]}
        out["base:" + b] = res
    return out


def check_all(gate: Callable[[], Optional[str]]) -> dict:
    """Runs every check with time limits and keeps the results; errors are kept per family, not raised."""
    global _checking
    with _lock:
        if _checking:
            return {"ok": True, "message": "A check is already running."}
        _checking = True
    try:
        present = vdevice.images() if vdevice.podman_available() else set()
        results: Dict[str, dict] = {}
        errors: List[str] = []
        tags = None
        try:
            tags = {t["name"]: t for t in json.loads(_get(DOCKER_TAGS)).get("results", [])}
            results.update(_check_redroid(present))
        except Exception as e:  # noqa: BLE001
            errors.append(f"Docker Hub: {e}")
        for name, fn in (("WayDroid-ATV", lambda: _check_waydroid(present)), ("Google's SDK repository", lambda: _check_google(present, gate, tags)),
                         ("Mystic GSI and the ARM machine", _check_arm)):
            try:
                results.update(fn())
            except Exception as e:  # noqa: BLE001
                errors.append(f"{name}: {e}")
        st = _load()
        st["results"] = results
        st["checked"] = time.time()
        st["errors"] = errors
        _save()
        n_up = sum(1 for r in results.values() if r.get("update"))
        n_upg = sum(1 for r in results.values() if r.get("upgrade"))
        msg = f"Checked: {n_up} update{'s' if n_up != 1 else ''} and {n_upg} upgrade{'s' if n_upg != 1 else ''} available." + (" Some sources failed: " + "; ".join(errors) if errors else "")
        _log(msg)
        return {"ok": True, "message": msg}
    finally:
        _checking = False


# ------------------------------------------------------------------ installing
def _systemd(cmd: List[str], env: Optional[Dict[str, str]] = None) -> List[str]:
    out = ["systemd-run", "--user", "--wait", "--quiet", "--pipe", "--unit=forge-update-" + uuid.uuid4().hex[:8], "-p", "MemoryMax=1300M", "-p", "MemorySwapMax=0"]
    for k, v in (env or {}).items():
        out += ["--setenv", f"{k}={v}"]
    return out + cmd


def _device_running(version: str) -> Optional[str]:
    for d in vdevice.definitions():
        if d["version"] == version and vdevice.running(d):
            return d["name"]
    return None


def _add_version(newid: str, entry: dict) -> None:
    """Adds an upgrade's version to the catalog (the overrides file, so every Forge process and script sees it)."""
    ov = _overrides()
    if entry.get("backend") == "arm" or "folder" in entry:
        ov.setdefault("arm", {})[newid] = entry
        armvm.ARM_VERSIONS[newid] = dict(entry)
        armvm.ARM_VERSIONS[newid].update({"backend": "arm", "cap_mb": armvm.CAP_MB, "image": "", "types": ["tablet", "phone"], "build": "forge-arm-image"})
        VERSIONS[newid] = armvm.ARM_VERSIONS[newid]
    else:
        ov.setdefault("extra", {})[newid] = entry
        VERSIONS[newid] = dict(entry)
    _save_overrides(ov)


def _set_arm_zip(version: str, zip_name: str, label: Optional[str] = None) -> None:
    ov = _overrides()
    cur = ov.setdefault("arm", {}).get(version) or {}
    cur["zip"] = zip_name
    f = _mystic_fields(zip_name)
    if f:
        cur["detail"] = (VERSIONS[version].get("detail", "").split(", a Mystic GSI")[0] + f", a Mystic GSI port of the {f['codename']} build from {f['date'][:4]}-{f['date'][4:6]}-{f['date'][6:]}, under ARM emulation")
    ov["arm"][version] = cur
    _save_overrides(ov)
    armvm.ARM_VERSIONS[version].update(cur)


def _plan(version: str, kind: str) -> Tuple[Optional[List[str]], Optional[Dict[str, str]], str, Optional[Callable[[], None]], str]:
    """What to run for an update or upgrade of a version: the command, its environment, the version the job belongs
    to, what to record on success, or a message why nothing can run."""
    res = (_load()["results"] or {}).get(version) or {}
    item = res.get(kind)
    if not item:
        return None, None, version, None, f"No {kind} is known for {version}; check first."
    if version.startswith("base:"):
        b = version.split(":", 1)[1]
        ov = _overrides(); ov.setdefault("base", {})[b] = {"zip": item["zip"], "url": item["url"]}; _save_overrides(ov)
        armvm.BASE[b].update({"zip": item["zip"], "url": item["url"]})
        first = next((v for v, i in VERSIONS.items() if i.get("backend") == "arm" and i.get("base") == b), None)
        if not first:
            return None, None, version, None, "No version uses this base machine."
        return [os.path.join(BIN, "forge-arm-image"), first], {}, version, lambda: _record(version, item["zip"]), ""
    info = VERSIONS.get(version) or {}
    backend = info.get("backend")
    if kind == "upgrade":
        newid, entry = item["version"], item["entry"]
        if newid not in VERSIONS:
            _add_version(newid, entry)
        target = newid
        if backend == "redroid":
            return ["sudo", "-n", "systemd-run", "--scope", "--quiet", "--unit=forge-pull-" + uuid.uuid4().hex[:8], "-p", "MemoryMax=1G", "-p", "MemorySwapMax=0", "--", "env", f"CONTAINERS_CONF_OVERRIDE={CONF}",
                    "nice", "-n", "10", "podman", "pull", entry["image"]], None, target, lambda: _record(target, entry["image"]), ""
        if backend == "waydroid":
            w = entry["waydroid"]
            env = _waydroid_env(newid, w, item["system"], item["vendor"])
            _save_build(newid, env)   # a new version: Download and the script need its recipe even if this build fails
            return [os.path.join(BIN, "forge-vd-image"), newid], env, target, lambda: _record(target, f"system {item['system']['date']}, vendor {item['vendor']['date']}",
                                                                                                   {"system": item["system"]["date"], "vendor": item["vendor"]["date"], "lineage": w["lineage"]}), ""
        if backend == "google":
            g = entry["google"]
            env = {"KIND": "google", "GOOGLE_PKG_OVERRIDE": g["pkg"], "SDK_OVERRIDE": str(g["sdk"]), "VENDOR_IMAGE_OVERRIDE": g["vendor_image"],
                   "BASE_OVERRIDE": os.path.join(VD_DIR, g["base"]), "IMG_SUFFIX": "-" + time.strftime("%Y%m%d"), "DESC_OVERRIDE": entry["detail"]}
            _save_build(newid, env)
            return [os.path.join(BIN, "forge-vd-image"), newid], env, target, lambda: _record(target, g["pkg"]), ""
        if backend == "arm":
            return [os.path.join(BIN, "forge-arm-image"), newid], {}, target, lambda: _record(target, entry["zip"]), ""
        return None, None, version, None, "This version cannot be upgraded by Forge."
    # an update of the same version
    if backend == "redroid":
        return ["sudo", "-n", "systemd-run", "--scope", "--quiet", "--unit=forge-pull-" + uuid.uuid4().hex[:8], "-p", "MemoryMax=1G", "-p", "MemorySwapMax=0", "--", "env", f"CONTAINERS_CONF_OVERRIDE={CONF}",
                "nice", "-n", "10", "podman", "pull", info["image"]], None, version, lambda: _record(version, "pulled " + time.strftime("%Y-%m-%d")), ""
    if backend == "waydroid":
        w = WAYDROID.get(version) or info.get("waydroid")
        env = _waydroid_env(version, w, item["system"], item["vendor"])
        env["FORCE"] = "1"

        def done_waydroid() -> None:
            _record(version, f"system {item['system']['date']}, vendor {item['vendor']['date']}",
                    {"system": item["system"]["date"], "vendor": item["vendor"]["date"], "lineage": w["lineage"]})
            _save_build(version, env)
        return [os.path.join(BIN, "forge-vd-image"), version], env, version, done_waydroid, ""
    if backend == "google":
        pkg = item["package"]
        env = {"FORCE": "1", "IMG_SUFFIX": "-" + time.strftime("%Y%m%d")}
        # sdkmanager would replace the installed revision in place, so it is moved aside first and kept (nothing is
        # deleted); when the install fails, it is put back.
        pkg_dir = os.path.join(SDKROOT, *pkg.split(";"))
        keep = os.path.join(VD_DIR, version, "sdk-kept-" + time.strftime("%Y%m%d%H%M%S"))
        install = "JAVA_OPTS=-Xmx512m " + shlex.join([SDKMANAGER, f"--sdk_root={SDKROOT}", "--install", pkg]) + " </dev/null | tail -n 2"
        script = (f"set -eo pipefail; moved=0; pkg={shlex.quote(pkg_dir)}; keep={shlex.quote(keep)}; "
                  'if [ -d "$pkg" ]; then mkdir -p "$(dirname "$keep")"; mv -- "$pkg" "$keep"; moved=1; '
                  'echo "Keeping the installed revision in $keep"; fi; '
                  f"if ! {install}; then "
                  'if [ "$moved" = 1 ] && [ ! -e "$pkg" ]; then mv -- "$keep" "$pkg"; echo "The installed revision was put back."; fi; '
                  "echo 'sdkmanager could not install the new revision.'; exit 1; fi; "
                  + shlex.join([os.path.join(BIN, "forge-vd-image"), version]))

        def done_google() -> None:
            _record(version, f"revision {item.get('revision', '?')}")
            _save_build(version, env)
        return ["bash", "-c", script], env, version, done_google, ""
    if backend == "arm":
        _set_arm_zip(version, item["zip"])
        return [os.path.join(BIN, "forge-arm-image"), version], {}, version, lambda: _record(version, item["zip"]), ""
    return None, None, version, None, "This version cannot be updated by Forge."


def _waydroid_env(variant: str, w: dict, system: dict, vendor: dict) -> Dict[str, str]:
    android = LINEAGE_ANDROID.get(w["lineage"].split(".")[0], 17)
    desc = (f"LineageOS {w['lineage']} ({'Android TV' if 'tv' in w['arch'] else 'Android'} {android}) {w['variant']} {system['date']} and MAINLINE vendor {vendor['date']} "
            f"from WayDroid-ATV, MD5 checked against the project's file feed")
    return {"KIND": "waydroid", "SYS_FILE": system["name"], "VEN_FILE": vendor["name"], "SYS_SUM": system["md5"], "VEN_SUM": vendor["md5"], "SUM_ALGO": "md5",
            "ARCH_OVERRIDE": w["arch"], "SDK_OVERRIDE": str(w.get("sdk") or android + 20), "BASE_OVERRIDE": os.path.join(VD_DIR, w["base"]),
            "KEEP_ETC_OVERRIDE": "1" if w.get("keep_etc") else "0", "DESC_OVERRIDE": desc, "IMG_SUFFIX": "-" + system["date"]}


def _record(version: str, build: str, extra: Optional[dict] = None) -> None:
    st = _load()
    st["installed"][version] = {"build": build, "when": time.time(), **(extra or {})}
    r = st["results"].get(version)
    if r:
        r.pop("update", None)
        r["installed"] = build
    _save()
    _log(f"Installed {version}: {build}")


def apply(version: str, kind: str, gate: Callable[[], Optional[str]]) -> dict:
    """Starts one update or upgrade as a download job (the Android view shows its progress like a download)."""
    global _applying
    kind = "upgrade" if kind == "upgrade" else "update"
    with _lock:
        if _applying:
            return {"ok": False, "message": f"An install of {_applying} is still running; one at a time."}
        job = vdevice._jobs.get(version)
        if job and job.get("running"):
            return {"ok": False, "message": "A download of this version is still running."}
        why = gate()
        if why:
            return {"ok": False, "message": why}
        try:
            stv = os.statvfs(os.path.expanduser("~"))
            if stv.f_bavail * stv.f_frsize < 8 * 1024 ** 3:
                return {"ok": False, "message": "Less than 8 GB of disk space is free in the Linux VM."}
        except OSError:
            pass
        if not version.startswith("base:"):
            busy = _device_running(version)
            if busy:
                return {"ok": False, "message": f"Stop {busy} first; its system image is about to change."}
        cmd, env, target, on_ok, problem = _plan(version, kind)
        if not cmd:
            return {"ok": False, "message": problem}
        full = cmd if cmd[0] == "sudo" else _systemd(cmd, env)
        _applying = target
        vdevice._jobs[target] = {"running": True, "lines": [], "ok": None, "started": time.time()}

    def go() -> None:
        global _applying
        try:
            vdevice._run_job(target, full)
            if vdevice._jobs[target].get("ok") and on_ok:
                on_ok()
            elif not vdevice._jobs[target].get("ok"):
                _log(f"The {kind} of {version} failed: " + " ".join(vdevice._jobs[target].get("lines", [])[-2:]))
        finally:
            with _lock:
                _applying = None
    threading.Thread(target=go, name="vd-update", daemon=True).start()
    label = (VERSIONS.get(target) or {}).get("label", target)
    return {"ok": True, "message": f"Installing the {kind} of {label}. The Android view shows the progress."}


def apply_all(gate: Callable[[], Optional[str]], auto: bool = False) -> dict:
    """Every available update and upgrade, one after another, in a thread."""
    todo = [(v, k) for v, r in (_load()["results"] or {}).items() for k in ("update", "upgrade") if r.get(k)]
    if not todo:
        return {"ok": True, "message": "Nothing to install: everything is current."}

    def go() -> None:
        for v, k in todo:
            r = apply(v, k, gate)
            if not r["ok"]:
                _log(f"Skipped the {k} of {v}: {r['message']}")
                continue
            while _applying:
                time.sleep(5)
    threading.Thread(target=go, name="vd-update-all", daemon=True).start()
    return {"ok": True, "message": f"Installing {len(todo)} item{'s' if len(todo) != 1 else ''}, one after another." + (" The Android view shows the progress." if not auto else "")}


# ------------------------------------------------------------------ devices
def upgrade_targets(d: dict, present: Optional[set] = None) -> List[dict]:
    """The installed versions a device could move to: the same family, a higher rank."""
    fam, rank = family_of(d["version"])
    if present is None:
        present = vdevice.images() if vdevice.podman_available() else set()
    out = []
    for v, info in VERSIONS.items():
        f2, r2 = family_of(v)
        if f2 == fam and r2 > rank:
            inst = armvm.installed(v) if info.get("backend") == "arm" else info["image"] in present
            if inst:
                out.append({"version": v, "label": info["label"], "rank": r2})
    return sorted(out, key=lambda x: x["rank"])


def move(dev_id: str, version: str) -> dict:
    d = vdevice.get(dev_id)
    if not d:
        return {"ok": False, "message": "There is no such virtual device."}
    if version not in VERSIONS:
        return {"ok": False, "message": "Unknown Android version."}
    if vdevice.running(d):
        return {"ok": False, "message": f"Stop {d['name']} first."}
    if version not in [t["version"] for t in upgrade_targets(d)]:
        return {"ok": False, "message": "That version is not a newer, installed member of this device's family."}
    with vdevice._lock:
        defs = vdevice.definitions()
        for e in defs:
            if e["id"] == dev_id:
                e["version"] = version
                e["upgraded"] = time.time()
        vdevice._save(defs)
    _log(f"Moved {d['name']} to {VERSIONS[version]['label']}")
    return {"ok": True, "message": f"{d['name']} now runs {VERSIONS[version]['label']} with its data; start it to let Android migrate."}


# ------------------------------------------------------------------ the view's data and the daily run
def _installed(version: str, present: set) -> bool:
    info = VERSIONS.get(version)
    if not info:
        return False
    return armvm.installed(version) if info.get("backend") == "arm" else info.get("image", "") in present


def summary(present: Optional[set] = None) -> dict:
    st = _load()
    res = st.get("results") or {}
    items = []
    if present is None:
        present = vdevice.images() if vdevice.podman_available() else set()
    for v, r in res.items():
        label = (VERSIONS.get(v) or {}).get("label") or (armvm.BASE.get(v.split(":", 1)[1], {}).get("label", v) if v.startswith("base:") else v)
        # Texts are never null: the Android app reads them as text (Android's optString turns a null into "null").
        item = {"version": v, "label": label or v, "installed": r.get("installed") or "", "source": r.get("source") or "",
                "error": r.get("error") or "", "upgrade_blocked": r.get("upgrade_blocked") or ""}
        if r.get("update"):
            item["update"] = {"label": r["update"].get("label") or "", "size": r["update"].get("size") or ""}
        if r.get("upgrade"):
            item["upgrade"] = {"label": r["upgrade"].get("label") or "", "size": r["upgrade"].get("size") or "", "version": r["upgrade"]["version"],
                               "installed": _installed(r["upgrade"]["version"], present)}
        items.append(item)
    return {"checked": st.get("checked") or 0, "checking": _checking, "applying": _applying or "", "items": items,
            "errors": [str(e) for e in (st.get("errors") or [])], "log": (st.get("log") or [])[-6:],
            "available": sum(1 for i in items if i.get("update") or (i.get("upgrade") and not i["upgrade"]["installed"]))}


def start_auto(settings: Callable[[], dict], gate: Callable[[], Optional[str]]) -> None:
    """The daily run: a check, then installs and device moves as the vd_updates setting allows."""
    global _auto_started
    if _auto_started:
        return
    _auto_started = True

    def loop() -> None:
        time.sleep(600)
        while True:
            try:
                mode = str(settings().get("vd_updates", "check"))
                if mode != "off" and time.time() - float(_load().get("checked") or 0) > CHECK_EVERY:
                    check_all(gate)
                    if mode in ("install", "install-move"):
                        apply_all(gate, auto=True)
                        while _applying or threading.active_count() and any(t.name == "vd-update-all" for t in threading.enumerate()):
                            time.sleep(10)
                    if mode == "install-move":
                        for d in vdevice.definitions():
                            targets = upgrade_targets(d)
                            if targets and not vdevice.running(d):
                                move(d["id"], targets[-1]["version"])
            except Exception as e:  # noqa: BLE001
                _log(f"The daily update run failed: {e}")
            time.sleep(3600)
    threading.Thread(target=loop, name="vd-updates-daily", daemon=True).start()
