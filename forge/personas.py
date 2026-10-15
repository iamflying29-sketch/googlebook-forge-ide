"""Device personas: a virtual device dressed as a specific phone, foldable, tablet, watch or TV.

The real software of the big Android makers (Samsung's One UI, Motorola's My UX, OnePlus's OxygenOS, Lenovo's ZUI,
Google's Pixel UI, Amazon's Fire OS) exists only for ARM phones and cannot run in a container. What an app actually
meets on those devices can be reproduced on real Android, though, and a persona does exactly that:

- the device's screen: its pixel size and density (so the same dp layout), its camera cutout and the status bar
  height that follows from it, and its navigation mode;
- its identity: Build.MANUFACTURER, BRAND, MODEL and DEVICE, so code that checks for a maker takes that branch;
- its system features (Fire TV's amazon.hardware.fire_tv, Samsung's experience flag, the TV features);
- the maker's battery behavior, applied to the app you test: Samsung's sleeping apps, OnePlus's deep optimization,
  Xiaomi's autostart block, Motorola's inactive app killing and Lenovo's swipe kill, made with Android's own controls.

A persona is labeled as such everywhere: it is Android with that device's screen, identity and behavior, not the
maker's own interface. For the real thing, Forge connects real devices (paired over Wi-Fi, or in Google's and
Samsung's device labs). Values marked "exact" come from the maker's or LineageOS's device trees; "approximate"
values are derived (for example Samsung's 450 dpi convention) and can be measured on a real unit."""
from __future__ import annotations

from typing import Dict, List, Optional


def _hole(x: float, y: float, r: float, status_px: int) -> dict:
    """A centered circular camera hole in panel pixels (origin top left) and the status bar strip it sets."""
    pad = 13
    return {"x": x, "y": y, "r": r, "status_px": status_px,
            "path": f"m {x + r:g},{y:g} a {r:g},{r:g} 0 0 0 {-2 * r:g},0 {r:g},{r:g} 0 0 0 {2 * r:g},0 z @left",
            "rect": f"m {x - r - pad:g},0 h {2 * r + 2 * pad:g} v {status_px} h {-(2 * r + 2 * pad):g} z @left"}


def _p(name, maker, skin, kind, w, h, dpi, cutout=None, corner=0, nav="gestural", props=None, features=(),
       behavior="stock", certainty="approximate", note="") -> dict:
    return {"name": name, "maker": maker, "skin": skin, "kind": kind, "w": w, "h": h, "dpi": dpi, "cutout": cutout,
            "corner": corner, "nav": nav, "props": props or {}, "features": list(features), "behavior": behavior,
            "certainty": certainty, "note": note}


SAMSUNG = ["com.samsung.feature.samsung_experience_mobile"]
TV = ["android.software.leanback", "android.hardware.type.television"]

PERSONAS: Dict[str, dict] = {
    # Google (screen values from Google's and LineageOS's device trees)
    "pixel-10": _p("Pixel 10", "Google", "Pixel UI", "phone", 1080, 2424, 420, _hole(539.5, 86, 42, 173), 138, "gestural",
                   {"manufacturer": "Google", "brand": "google", "model": "Pixel 10", "device": "frankel"},
                   behavior="pixel", certainty="exact", note="Choose Android 16 with Google Play for Google's Pixel Launcher, system interface and apps."),
    "pixel-10-pro-xl": _p("Pixel 10 Pro XL", "Google", "Pixel UI", "phone", 1080, 2404, 386, _hole(539.5, 80.5, 38.5, 174), 123,
                          "gestural", {"manufacturer": "Google", "brand": "google", "model": "Pixel 10 Pro XL", "device": "mustang"},
                          behavior="pixel", certainty="exact", note="Its default 1080 px wide mode; the panel is 1344 px wide. Choose Android 16 with Google Play for Google's Pixel Launcher, system interface and apps."),
    "pixel-10-pro-fold": _p("Pixel 10 Pro Fold, open", "Google", "Pixel UI", "foldable", 2076, 2152, 390,
                            _hole(1987.5, 80, 39.5, 161), 85, "gestural",
                            {"manufacturer": "Google", "brand": "google", "model": "Pixel 10 Pro Fold", "device": "rango"},
                            behavior="pixel", certainty="exact", note="Choose Android 16 with Google Play for Google's Pixel Launcher, system interface and apps."),
    "pixel-10-pro-fold-cover": _p("Pixel 10 Pro Fold, closed", "Google", "Pixel UI", "phone", 1080, 2364, 390,
                                  _hole(539.5, 79.5, 37.5, 161), 115, "gestural",
                                  {"manufacturer": "Google", "brand": "google", "model": "Pixel 10 Pro Fold", "device": "rango"},
                                  behavior="pixel", certainty="exact", note="Choose Android 16 with Google Play for Google's Pixel Launcher, system interface and apps."),
    "pixel-tablet": _p("Pixel Tablet", "Google", "Pixel UI", "tablet", 2560, 1600, 320, None, 28, "gestural",
                       {"manufacturer": "Google", "brand": "google", "model": "Pixel Tablet", "device": "tangorpro"},
                       behavior="pixel", certainty="exact", note="Choose Android 16 with Google Play for Google's Pixel Launcher, system interface and apps."),
    "pixel-watch-4": _p("Pixel Watch 4 (45 mm)", "Google", "Wear OS", "watch-round", 456, 456, 320, None, 0, None,
                        {"manufacturer": "Google", "brand": "google", "model": "Pixel Watch 4"},
                        behavior="pixel", note="Choose Wear OS 6 for Google's real Wear OS (the Pixel Watch 4 runs Wear OS 6); on phone Android versions it is the round layout only."),
    # Samsung (sizes from Samsung's emulator skin pages; One UI's 450 dpi convention; holes approximate)
    "galaxy-s25": _p("Galaxy S25", "Samsung", "One UI", "phone", 1080, 2340, 450, _hole(540, 56, 28, 112), 0, "threebutton",
                     {"manufacturer": "samsung", "brand": "samsung", "model": "SM-S931B"}, SAMSUNG, "samsung"),
    "galaxy-s25-ultra": _p("Galaxy S25 Ultra", "Samsung", "One UI", "phone", 1080, 2340, 450, _hole(540, 54, 26, 108), 0,
                           "threebutton", {"manufacturer": "samsung", "brand": "samsung", "model": "SM-S938B"},
                           SAMSUNG, "samsung", note="Its default FHD+ mode; the panel is 1440 px wide."),
    "galaxy-a56": _p("Galaxy A56", "Samsung", "One UI", "phone", 1080, 2340, 450, _hole(540, 60, 30, 120), 0, "threebutton",
                     {"manufacturer": "samsung", "brand": "samsung", "model": "SM-A566B"}, SAMSUNG, "samsung"),
    "galaxy-z-fold7": _p("Galaxy Z Fold7, open", "Samsung", "One UI", "foldable", 1968, 2184, 390, _hole(1600, 60, 26, 120), 0,
                         "threebutton", {"manufacturer": "samsung", "brand": "samsung", "model": "SM-F966B"},
                         SAMSUNG, "samsung"),
    "galaxy-z-fold7-cover": _p("Galaxy Z Fold7, closed", "Samsung", "One UI", "phone", 1080, 2520, 450, _hole(540, 56, 26, 112), 0,
                               "threebutton", {"manufacturer": "samsung", "brand": "samsung", "model": "SM-F966B"},
                               SAMSUNG, "samsung"),
    "galaxy-z-flip7": _p("Galaxy Z Flip7", "Samsung", "One UI", "phone", 1080, 2520, 450, _hole(540, 58, 28, 116), 0, "threebutton",
                         {"manufacturer": "samsung", "brand": "samsung", "model": "SM-F766B"}, SAMSUNG, "samsung"),
    "galaxy-z-flip7-cover": _p("Galaxy Z Flip7 FlexWindow", "Samsung", "One UI", "phone", 948, 1048, 380, None, 0, None,
                               {"manufacturer": "samsung", "brand": "samsung", "model": "SM-F766B"}, SAMSUNG, "samsung",
                               note="The cover screen's camera lenses are not drawn."),
    "galaxy-tab-s11": _p("Galaxy Tab S11", "Samsung", "One UI", "tablet", 1600, 2560, 320, None, 0, "threebutton",
                         {"manufacturer": "samsung", "brand": "samsung", "model": "Galaxy Tab S11"}, SAMSUNG, "samsung"),
    "galaxy-watch8": _p("Galaxy Watch8 (44 mm)", "Samsung", "One UI Watch", "watch-round", 480, 480, 340, None, 0, None,
                        {"manufacturer": "samsung", "brand": "samsung", "model": "Galaxy Watch8"}, SAMSUNG, "samsung",
                        note="Choose Wear OS 6 for Google's real Wear OS with this watch's screen and identity; One UI Watch's own interface is on real Galaxy Watches and in Samsung's Remote Test Lab."),
    # OnePlus (exact values from LineageOS's device trees)
    "oneplus-13": _p("OnePlus 13", "OnePlus", "OxygenOS", "phone", 1440, 3168, 640, _hole(720, 80, 42, 160), 112, "gestural",
                     {"manufacturer": "OnePlus", "brand": "OnePlus", "model": "OnePlus 13", "device": "dodge"},
                     behavior="oneplus", certainty="exact"),
    "oneplus-13r": _p("OnePlus 13R", "OnePlus", "OxygenOS", "phone", 1264, 2780, 560, _hole(632, 79, 34, 140), 115, "gestural",
                      {"manufacturer": "OnePlus", "brand": "OnePlus", "model": "OnePlus 13R", "device": "giulia"},
                      behavior="oneplus", certainty="exact"),
    "oneplus-open": _p("OnePlus Open, open", "OnePlus", "OxygenOS", "foldable", 2268, 2440, 480, _hole(1800, 70, 30, 140), 0,
                       "gestural", {"manufacturer": "OnePlus", "brand": "OnePlus", "model": "OnePlus Open"}, behavior="oneplus"),
    # Motorola (sizes from GSMArena; Motorola's 432 dp convention from LineageOS trees)
    "moto-edge-2025": _p("Moto Edge 2025", "Motorola", "My UX", "phone", 1220, 2712, 452, _hole(610, 64, 30, 128), 0, "gestural",
                         {"manufacturer": "motorola", "brand": "motorola", "model": "motorola edge 2025"}, behavior="motorola"),
    "moto-razr-plus-2025": _p("Razr+ 2025, open", "Motorola", "My UX", "phone", 1080, 2640, 400, _hole(540, 60, 28, 120), 0,
                              "gestural", {"manufacturer": "motorola", "brand": "motorola", "model": "motorola razr plus 2025"},
                              behavior="motorola"),
    "moto-razr-plus-2025-cover": _p("Razr+ 2025 cover screen", "Motorola", "My UX", "phone", 1272, 1080, 400, None, 0, None,
                                    {"manufacturer": "motorola", "brand": "motorola", "model": "motorola razr plus 2025"},
                                    behavior="motorola", note="The cover screen's camera lenses are not drawn."),
    "moto-g-2025": _p("Moto G 2025", "Motorola", "My UX", "phone", 720, 1604, 280, _hole(360, 44, 20, 88), 0, "gestural",
                      {"manufacturer": "motorola", "brand": "motorola", "model": "moto g - 2025"}, behavior="motorola"),
    # Lenovo (ZUI tablets; densities approximate)
    "lenovo-legion-tab-gen3": _p("Legion Tab Gen 3", "Lenovo", "ZUI", "tablet", 1600, 2560, 320, None, 0, "gestural",
                                 {"manufacturer": "LENOVO", "brand": "Lenovo", "model": "Legion Tab Gen 3"}, behavior="lenovo"),
    "lenovo-yoga-tab-plus": _p("Yoga Tab Plus", "Lenovo", "ZUI", "tablet", 1840, 2944, 340, None, 0, "gestural",
                               {"manufacturer": "LENOVO", "brand": "Lenovo", "model": "Yoga Tab Plus"}, behavior="lenovo"),
    # Xiaomi
    "xiaomi-15": _p("Xiaomi 15", "Xiaomi", "HyperOS", "phone", 1200, 2670, 480, _hole(600, 62, 28, 124), 0, "gestural",
                    {"manufacturer": "Xiaomi", "brand": "Xiaomi", "model": "Xiaomi 15"}, behavior="xiaomi"),
    # TV (Amazon's and Google's published TV surfaces)
    "fire-tv-stick-4k-max": _p("Fire TV Stick 4K Max", "Amazon", "Fire OS", "tv", 1920, 1080, 320, None, 0, None,
                               {"manufacturer": "Amazon", "brand": "Amazon", "model": "AFTKRT"},
                               TV + ["amazon.hardware.fire_tv"], "stock", "exact",
                               "Use an image without Google apps: Fire TV has no Google Play services."),
    "google-tv-streamer": _p("Google TV Streamer", "Google", "Google TV", "tv", 1920, 1080, 320, None, 0, None,
                             {"manufacturer": "Google", "brand": "google", "model": "Google TV Streamer"}, TV, "stock", "exact",
                             "Choose Google TV 16 for Google TV's own home screen and apps."),
    "nvidia-shield-tv": _p("Nvidia Shield TV Pro", "Nvidia", "Android TV", "tv", 1920, 1080, 320, None, 0, None,
                           {"manufacturer": "NVIDIA", "brand": "NVIDIA", "model": "SHIELD Android TV", "device": "mdarcy"}, TV, "stock"),
}

# The maker behaviors, built from Android's own controls (am, appops, deviceidle). Each runs on one app.
BEHAVIORS: Dict[str, dict] = {
    "samsung": {"label": "Samsung: sleeping app", "cmds": ["am set-bg-restriction-level --user 0 {pkg} restricted_bucket",
                                                          "am set-standby-bucket {pkg} rare"],
                "about": "One UI's \"Sleeping apps\" list and its stricter Adaptive battery: the app runs rarely in the background."},
    "samsung-deep": {"label": "Samsung: deep sleeping app", "cmds": ["am set-bg-restriction-level --user 0 {pkg} background_restricted",
                                                                   "am set-standby-bucket {pkg} restricted", "am stop-app {pkg}"],
                     "about": "One UI's \"Deep sleeping apps\": the app never runs in the background and only works while open."},
    "oneplus": {"label": "OnePlus: deep optimization", "cmds": ["dumpsys deviceidle force-idle", "am kill {pkg}",
                                                               "cmd appops set {pkg} RUN_ANY_IN_BACKGROUND ignore"],
                "about": "OxygenOS kills background apps after the screen goes off and blocks auto-launch."},
    "xiaomi": {"label": "Xiaomi: autostart off", "cmds": ["am force-stop {pkg}", "am set-bg-restriction-level --user 0 {pkg} background_restricted"],
               "about": "HyperOS's Autostart block: no boot start, alarms or broadcasts until you open the app again."},
    "motorola": {"label": "Motorola: inactive app killing", "cmds": ["am kill {pkg}", "cmd appops set {pkg} RUN_ANY_IN_BACKGROUND ignore"],
                 "about": "My UX's battery care kills apps that sit inactive."},
    "lenovo": {"label": "Lenovo: swipe kills", "cmds": ["am stop-app {pkg}"],
               "about": "ZUI ends an app completely when you swipe it away in Recents."},
    "doze": {"label": "Any maker: Doze now", "cmds": ["dumpsys battery unplug", "dumpsys deviceidle force-idle"],
             "about": "Android's deep sleep: no network, deferred alarms and jobs. Use Reset to leave it."},
}
RESET = ["am set-bg-restriction-level --user 0 {pkg} adaptive_bucket", "am set-standby-bucket {pkg} active", "cmd appops reset {pkg}",
         "dumpsys deviceidle unforce", "dumpsys battery reset"]


def profiles() -> Dict[str, tuple]:
    """Each persona's screen as a screen profile named persona:<id> (label, width, height, density)."""
    return {f"persona:{k}": (p["name"], p["w"], p["h"], p["dpi"]) for k, p in PERSONAS.items()}


def listing() -> List[dict]:
    out = []
    for k, p in PERSONAS.items():
        out.append({"id": k, "name": p["name"], "maker": p["maker"], "skin": p["skin"], "kind": p["kind"],
                    "width": p["w"], "height": p["h"], "density": p["dpi"], "certainty": p["certainty"], "note": p["note"],
                    "behavior": p["behavior"], "cutout": ({"x": p["cutout"]["x"], "y": p["cutout"]["y"], "r": p["cutout"]["r"]}
                                                          if p["cutout"] else None), "corner": p["corner"]})
    return out


def init_args(pid: Optional[str]) -> List[str]:
    """Android properties for the container's start: the persona's identity."""
    p = PERSONAS.get(pid or "")
    if not p:
        return []
    return [f"ro.product.{k}={v}" for k, v in p["props"].items()]


def features_xml(pid: Optional[str]) -> str:
    p = PERSONAS.get(pid or "")
    if not p or not p["features"]:
        return ""
    lines = "\n".join(f'    <feature name="{f}" />' for f in p["features"])
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Forge persona {p["name"]}: the features apps check for. -->\n'
            f"<permissions>\n{lines}\n</permissions>\n")


def display_script(pid: Optional[str]) -> str:
    """A root shell script (run inside the container) that gives the screen the persona's cutout and navigation mode.
    Overlays Forge made earlier are switched off first, so changing persona never stacks two cutouts."""
    p = PERSONAS.get(pid or "")
    lines = ["for o in $(cmd overlay list | grep -o 'com.android.shell:forge_[a-z]*'); do cmd overlay disable $o; done"]
    if p and p["cutout"]:
        c = p["cutout"]
        lines += [f'cmd overlay fabricate --target android --name forge_cut android:string/config_mainBuiltInDisplayCutout string "{c["path"]}"',
                  f'cmd overlay fabricate --target android --name forge_rect android:string/config_mainBuiltInDisplayCutoutRectApproximation string "{c["rect"]}"',
                  "cmd overlay fabricate --target android --name forge_fill android:bool/config_fillMainBuiltInDisplayCutout 0x12 1",
                  "for o in forge_cut forge_rect forge_fill; do cmd overlay enable com.android.shell:$o; done"]
    if p and p["kind"] == "watch-round":
        # Apps learn the screen is round from this flag (Configuration.isScreenRound and the round window insets).
        lines += ["cmd overlay fabricate --target android --name forge_round android:bool/config_mainBuiltInDisplayIsRound 0x12 1",
                  "cmd overlay enable com.android.shell:forge_round"]
    if p and p["nav"]:
        lines.append(f"cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.{p['nav']}")
    # SystemUI reads the cutout and the navigation mode when it starts; Android starts it again by itself.
    lines.append("sleep 1; kill $(pidof com.android.systemui) 2>/dev/null; true")
    return "\n".join(lines)


def behavior_script(name: str, pkg: str) -> str:
    cmds = RESET if name == "reset" else BEHAVIORS[name]["cmds"]
    return "; ".join(c.format(pkg=pkg) + " 2>&1" for c in cmds)
