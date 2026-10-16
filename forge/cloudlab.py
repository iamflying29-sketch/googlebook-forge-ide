"""Real devices with the makers' own software, from device labs in the cloud.

Samsung's One UI, Motorola's My UX, OnePlus's OxygenOS and Google's Pixel UI are built for the makers' ARM hardware;
besides the emulated ARM devices and your own devices over Wi-Fi, the fast way to test on the real thing is a lab:

- Google's device lab, through Google's Android command line tool (`android device remote`), which reserves a real
  phone (Pixel, Samsung, Motorola, OnePlus, OPPO, Xiaomi, Sony and more) and connects it to adb over SSL. Reserving
  costs money after a free allowance, so Forge reserves and extends only when you confirm in the Android view, never
  from the agent, and it shows Google's price first. You sign in yourself with `android auth login`.
- Samsung's Remote Test Lab: free (20 credits a day after signing in on Samsung's site), with the newest Galaxy
  phones, foldables, tablets and Galaxy Watches. Reserving happens on Samsung's web page; Samsung's Remote Debug
  Bridge then gives the device a local adb port, and Forge connects it like any other device.

Remote devices always join Forge's own adb server, never the Googlebook's. Every call has a timeout."""
from __future__ import annotations

import json
import os
import re
import subprocess
import threading
import time
import urllib.request
from typing import Dict, List, Optional

ANDROID = os.path.expanduser("~/.local/bin/android")
CLOUD_DIR = os.path.expanduser("~/.forge-ide/cloud")
FORGE_ADB_PORT = 5041
PRICE = ("Google's device lab gives 30 free minutes per Google Cloud project each month, then charges 0.15 US dollars "
         "a minute while a device is reserved (Google's prices in October 2026). Google may ask for a billing account "
         "even for the free minutes. The device is wiped when the reservation ends.")
RTL_URL = "https://developer.samsung.com/remotetestlab/"
RTL_API = "https://developer.samsung.com/remotetestlab/rtl/api/v1/devices?pageNum={n}&pageSize=100"
AMAZON_URL = "https://developer.amazon.com/docs/app-testing/live-device-interaction-virtual.html"

_lock = threading.Lock()
_jobs: Dict[str, dict] = {}     # id -> {"running", "lines", "ok", "started", "what"}


def available() -> bool:
    return os.path.isfile(ANDROID) and os.access(ANDROID, os.X_OK)


def _run(args: List[str], timeout: float) -> tuple:
    env = dict(os.environ, ANDROID_ADB_SERVER_PORT=str(FORGE_ADB_PORT))   # remote devices join Forge's adb server
    try:
        r = subprocess.run([ANDROID] + args, capture_output=True, text=True, timeout=timeout, env=env, stdin=subprocess.DEVNULL)
        return r.returncode, (r.stdout + r.stderr).strip()
    except subprocess.TimeoutExpired:
        return 124, "Google's Android tool did not answer in time."
    except OSError as e:
        return 127, str(e)


def _cache(name: str, max_age: float):
    try:
        path = os.path.join(CLOUD_DIR, name)
        if time.time() - os.path.getmtime(path) < max_age:
            with open(path, "r", encoding="utf-8") as fh:
                return json.load(fh)
    except (OSError, ValueError):
        pass
    return None


def _store(name: str, data) -> None:
    os.makedirs(CLOUD_DIR, exist_ok=True)
    path = os.path.join(CLOUD_DIR, name)
    with open(path + ".new", "w", encoding="utf-8") as fh:
        json.dump(data, fh)
    os.replace(path + ".new", path)


# ------------------------------------------------------------------ Google's device lab
def google_status() -> dict:
    if not available():
        return {"installed": False, "signed_in": False, "message": "Google's Android command line tool is not installed."}
    rc, text = _run(["auth", "status"], 30)
    signed = rc == 0 and "not logged in" not in text.lower()
    return {"installed": True, "signed_in": signed, "message": text[-300:], "price": PRICE,
            "jobs": {k: dict(v) for k, v in _jobs.items()}}


def google_models(refresh: bool = False) -> dict:
    """The lab's catalog, grouped by maker: [{"maker", "models": [{"name", "targets": ["codename/api", ...]}]}]."""
    cached = None if refresh else _cache("google-models.json", 6 * 3600)
    if cached:
        return cached
    rc, text = _run(["device", "remote", "models"], 90)
    makers: List[dict] = []
    for line in text.splitlines():
        m = re.match(r"^[├└]─ (.+?) \[(\d+)\]\s*$", line)
        if m:
            makers.append({"maker": m.group(1).strip(), "models": []})
            continue
        m = re.match(r"^[│ ]\s+[├└]─ (.+?)\s{2,}(\S+/\d+(?:\s+\S+/\d+)*)\s*$", line)
        if m and makers:
            makers[-1]["models"].append({"name": m.group(1).strip(), "targets": m.group(2).split()})
    data = {"ok": rc == 0 and bool(makers), "makers": makers, "time": time.time(), "message": "" if makers else text[-300:]}
    if data["ok"]:
        _store("google-models.json", data)
    return data


def google_projects() -> dict:
    rc, text = _run(["device", "remote", "projects"], 60)
    ids = re.findall(r"^\s*([a-z][a-z0-9-]{4,29})\b", text, re.M)
    return {"ok": rc == 0, "projects": [i for i in ids if i not in ("project", "projects")], "message": text[-600:]}


def google_list(project: str = "") -> dict:
    args = ["device", "remote", "list", "--all"] + ([f"--project={project}"] if project else [])
    rc, text = _run(args, 60)
    return {"ok": rc == 0, "text": text[-3000:]}


def _job(key: str, what: str, args: List[str], timeout: float) -> None:
    job = _jobs[key] = {"running": True, "lines": [], "ok": None, "started": time.time(), "what": what}
    rc, text = _run(args, timeout)
    job["lines"] = text.splitlines()[-15:]
    job["ok"] = rc == 0
    job["running"] = False


def _valid_project(project: str) -> bool:
    return bool(re.match(r"^[a-z][a-z0-9-]{4,29}$", project or ""))


def google_reserve(target: str, project: str, confirmed: bool) -> dict:
    """Reserve a real device and connect it to Forge's adb server. Only after you confirmed Google's price."""
    if not confirmed:
        return {"ok": False, "message": "Reserving needs your confirmation of Google's price first.", "price": PRICE}
    if not re.match(r"^[A-Za-z0-9_.-]+/\d+$", target or ""):
        return {"ok": False, "message": "Choose a device first."}
    if not _valid_project(project):
        return {"ok": False, "message": "Enter your Google Cloud project ID (lowercase letters, digits and dashes)."}
    with _lock:
        if any(j.get("running") for j in _jobs.values()):
            return {"ok": False, "message": "Another lab request is still running."}
        threading.Thread(target=_job, args=(f"reserve-{int(time.time())}", f"Reserving {target}",
                                             ["device", "remote", "create", "--connect", target, f"--project={project}"], 600),
                         daemon=True).start()
    return {"ok": True, "message": f"Reserving {target} in Google's device lab. It appears under Devices when it is connected."}


def google_extend(reservation: str, minutes: int, project: str, confirmed: bool) -> dict:
    if not confirmed:
        return {"ok": False, "message": "Extending needs your confirmation of Google's price first.", "price": PRICE}
    if not re.match(r"^[A-Za-z0-9_.-]+$", reservation or "") or not _valid_project(project):
        return {"ok": False, "message": "Unknown reservation or project."}
    minutes = max(5, min(120, int(minutes)))
    rc, text = _run(["device", "remote", "extend", f"--duration={minutes}", reservation, f"--project={project}"], 60)
    return {"ok": rc == 0, "message": text[-400:] or f"Extended by {minutes} minutes."}


def google_release(reservation: str, project: str) -> dict:
    """Disconnect and end a reservation, so it stops costing money."""
    if not re.match(r"^[A-Za-z0-9_.-]+$", reservation or "") or not _valid_project(project):
        return {"ok": False, "message": "Unknown reservation or project."}
    _run(["device", "remote", "disconnect", reservation, f"--project={project}"], 60)
    rc, text = _run(["device", "remote", "remove", reservation, f"--project={project}"], 60)
    return {"ok": rc == 0, "message": text[-400:] or "Returned the device."}


# ------------------------------------------------------------------ Samsung's Remote Test Lab
def samsung_summary(refresh: bool = False) -> dict:
    """What Samsung's lab has, from the public device list its web page uses (read at most once an hour)."""
    cached = None if refresh else _cache("samsung-devices.json", 3600)
    if cached is None:
        rows, n = [], 1
        try:
            while n <= 30:
                req = urllib.request.Request(RTL_API.format(n=n), headers={"User-Agent": "Forge IDE"})
                with urllib.request.urlopen(req, timeout=20) as r:
                    page = json.loads(r.read(4_000_000))
                rows += page.get("content") or []
                if n >= int(page.get("totalPage") or 0):
                    break
                n += 1
        except (OSError, ValueError) as e:
            if not rows:
                return {"ok": False, "message": f"Samsung's device list could not be read: {e}"}
        cached = {"time": time.time(), "rows": [{k: r.get(k) for k in ("productName", "deviceName", "osVersionName", "oneUiVer",
                                                                      "location", "workStatus", "waiting")} for r in rows]}
        _store("samsung-devices.json", cached)
    def num(text) -> float:
        m = re.search(r"(\d+(?:\.\d+)?)", str(text or ""))
        return float(m.group(1)) if m else 0.0

    products: Dict[str, dict] = {}
    other = 0
    for r in cached["rows"]:
        if "android" not in str(r.get("osVersionName") or "").lower():
            other += 1   # Samsung's TVs run Tizen, not Android
            continue
        name = r.get("productName") or r.get("deviceName") or "Galaxy"
        p = products.setdefault(name, {"name": name, "units": 0, "versions": {}})
        p["units"] += 1
        key = (num(r.get("osVersionName")), num(r.get("oneUiVer")))
        p["versions"][key] = f"One UI {r.get('oneUiVer') or '?'} on {r.get('osVersionName')}"
    newest = sorted(products.values(), key=lambda p: (max(p["versions"], default=(0, 0)), p["units"]), reverse=True)
    return {"ok": True, "time": cached["time"], "units": len(cached["rows"]) - other, "tizen_units": other, "url": RTL_URL,
            "products": [{"name": p["name"], "units": p["units"],
                          "versions": [p["versions"][k] for k in sorted(p["versions"], reverse=True)]} for p in newest[:60]]}
