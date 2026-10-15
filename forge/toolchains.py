"""Android, Kotlin, Java, Gradle and Rust tools for Forge: one place that knows where they are, so the integrated
terminal, the agent's commands and the language servers all see the same environment.

adb is deliberately left alone: Debian's adb serves the Googlebook connection for other tools too, and a second adb
version on the PATH can kill the running adb server. Gradle's own installDebug would use the SDK's adb, so Forge
installs apps with `adb install` instead."""
from __future__ import annotations

import glob
import os
import re
import shutil
from typing import Dict, List, Optional

HOME = os.path.expanduser("~")
ANDROID_LIB = os.path.join(HOME, ".local", "lib", "forge-android")
FORGE_BIN = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "bin")   # forge-gradle, forge-device, ...
SDK_CANDIDATES = (os.environ.get("ANDROID_HOME", ""), os.path.join(HOME, "tools", "android-sdk"), os.path.join(HOME, "Android", "Sdk"))
JAVA_HOMES = ("/usr/lib/jvm/java-21-openjdk-amd64", "/usr/lib/jvm/default-java")

# Builds that can grow by gigabytes: they run inside a memory cap, and only when memory allows (see agent.py).
HEAVY_COMMAND = re.compile(r"(^|[\s;&|(/])(gradlew|gradle|cargo|rustc|kotlinc|kotlin|sdkmanager|bundletool|ndk-build|cmake)\b")
BUILD_MEMORY_CAP_MB = 2048


def scope_argv(kind: str, cap: str) -> tuple:
    """(argv prefix, unit name) to run a program inside its own named systemd scope with a hard memory cap."""
    import uuid
    unit = f"forge-{re.sub(r'[^a-z0-9]+', '-', kind.lower())}-{uuid.uuid4().hex[:8]}"
    return (["systemd-run", "--user", "--scope", "--quiet", f"--unit={unit}", "-p", f"MemoryMax={cap}",
             "-p", "MemorySwapMax=0"], unit)


def stop_scope(unit: Optional[str]) -> None:
    """Stop everything still running in a scope, including processes that started their own session (a Gradle
    daemon started by a language server, for example), so nothing lingers and holds memory."""
    if not unit:
        return
    import subprocess
    try:
        subprocess.run(["systemctl", "--user", "stop", unit + ".scope"], capture_output=True, timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        pass


def sdk_root() -> Optional[str]:
    for p in SDK_CANDIDATES:
        if p and os.path.isdir(os.path.join(p, "platforms")):
            return p
    return None


def _newest(pattern: str) -> Optional[str]:
    found = sorted(glob.glob(pattern), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", os.path.basename(p))])
    return found[-1] if found else None


def paths() -> Dict[str, Optional[str]]:
    sdk = sdk_root()
    return {
        "sdk": sdk,
        "cmdline_tools": os.path.join(sdk, "cmdline-tools", "latest", "bin") if sdk else None,
        "build_tools": _newest(os.path.join(sdk, "build-tools", "*")) if sdk else None,
        "ndk": _newest(os.path.join(sdk, "ndk", "*")) if sdk else None,
        "cmake": _newest(os.path.join(sdk, "cmake", "*")) if sdk else None,
        "kotlinc": os.path.join(ANDROID_LIB, "kotlinc"),
        "gradle": _newest(os.path.join(ANDROID_LIB, "gradle-*")),
        "bin": os.path.join(ANDROID_LIB, "bin"),
        "cargo": os.path.join(HOME, ".cargo", "bin"),
        "java_home": next((j for j in JAVA_HOMES if os.path.isdir(j)), None),
        "kotlin_lsp": _newest(os.path.join(ANDROID_LIB, "kotlin-lsp", "kotlin-server-*")),
        "jdtls": os.path.join(ANDROID_LIB, "jdtls"),
        "lemminx": os.path.join(ANDROID_LIB, "lemminx", "lemminx-linux-x86_64"),
    }


def tool_env(base: Optional[Dict[str, str]] = None) -> Dict[str, str]:
    """The environment for the terminal and the agent's commands, with every installed toolchain on the PATH."""
    env = dict(base if base is not None else os.environ)
    p = paths()
    add: List[str] = [FORGE_BIN]
    if p["sdk"]:
        env.setdefault("ANDROID_HOME", p["sdk"])
        env.setdefault("ANDROID_SDK_ROOT", p["sdk"])
        for k in ("cmdline_tools", "build_tools"):
            if p[k] and os.path.isdir(p[k]):
                add.append(p[k])
        if p["ndk"]:
            env.setdefault("ANDROID_NDK_HOME", p["ndk"])
            env.setdefault("ANDROID_NDK_ROOT", p["ndk"])
        if p["cmake"] and os.path.isdir(os.path.join(p["cmake"], "bin")):
            add.append(os.path.join(p["cmake"], "bin"))
    if p["java_home"]:
        env.setdefault("JAVA_HOME", p["java_home"])
    for k in ("kotlinc", "gradle"):
        if p[k] and os.path.isdir(os.path.join(p[k], "bin")):
            add.append(os.path.join(p[k], "bin"))
    for k in ("bin", "cargo"):
        if p[k] and os.path.isdir(p[k]):
            add.append(p[k])
    have = env.get("PATH", "").split(os.pathsep)
    env["PATH"] = os.pathsep.join([a for a in add if a not in have] + have)
    # Login shells reset PATH in /etc/profile, so the additions travel separately and are added back after it.
    env["FORGE_PATH_PREPEND"] = os.pathsep.join(add)
    # Cargo uses every CPU by default, and each rustc job can take a gigabyte: three jobs keep builds inside memory.
    env.setdefault("CARGO_BUILD_JOBS", "3")
    return env


SHELL_RC = """# Forge IDE terminal: start like a login shell (system profile, then yours), then add Forge's toolchains to PATH.
[ -f /etc/profile ] && . /etc/profile
if [ -f "$HOME/.bash_profile" ]; then . "$HOME/.bash_profile"
elif [ -f "$HOME/.bash_login" ]; then . "$HOME/.bash_login"
elif [ -f "$HOME/.profile" ]; then . "$HOME/.profile"
fi
[ -n "$FORGE_PATH_PREPEND" ] && export PATH="$FORGE_PATH_PREPEND:$PATH"
# adb talks to the device chosen in Forge's Android view (virtual devices and paired devices are on Forge's own adb
# server, port 5041), unless you name a device or server yourself or set ANDROID_SERIAL.
adb() {
    local port serial
    if [ -z "$ANDROID_SERIAL" ] && [ -r "$HOME/.forge-ide/adb-target" ] && read -r port serial < "$HOME/.forge-ide/adb-target"; then
        case " $* " in
            *" -s "*|*" -P "*|*" -H "*|*" -L "*|*" -t "*) command adb "$@" ;;
            *) ANDROID_ADB_SERVER_PORT="$port" ANDROID_SERIAL="$serial" command adb "$@" ;;
        esac
    else
        command adb "$@"
    fi
}
"""


def shell_rc() -> str:
    """The startup file for Forge's terminal (rewritten each time, never removed)."""
    d = os.path.join(HOME, ".forge-ide", "shell")
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, "bashrc")
    try:
        with open(p, "r", encoding="utf-8") as fh:
            if fh.read() == SHELL_RC:
                return p
    except OSError:
        pass
    with open(p, "w", encoding="utf-8") as fh:
        fh.write(SHELL_RC)
    return p


def status() -> dict:
    """What is installed, without starting any JVM (versions are read from files)."""
    p = paths()
    env = tool_env()

    def which(name: str) -> Optional[str]:
        return shutil.which(name, path=env["PATH"])

    def version_of(path: Optional[str], filename: str, key: str) -> Optional[str]:
        if not path:
            return None
        try:
            with open(os.path.join(path, filename), "r", encoding="utf-8") as fh:
                for line in fh:
                    if line.startswith(key):
                        return line.split("=", 1)[1].strip().strip('"')
        except OSError:
            return None
        return None

    sdk = p["sdk"]
    platforms = sorted(os.path.basename(x) for x in glob.glob(os.path.join(sdk, "platforms", "*"))) if sdk else []
    build_tools = sorted(os.path.basename(x) for x in glob.glob(os.path.join(sdk, "build-tools", "*"))) if sdk else []
    toolchain = None
    rustc = which("rustc")
    if rustc:
        tc = glob.glob(os.path.join(HOME, ".rustup", "toolchains", "stable-*"))
        toolchain = os.path.basename(tc[0]) if tc else "installed"
    return {
        "android_sdk": sdk, "platforms": platforms, "build_tools": build_tools,
        "ndk": os.path.basename(p["ndk"]) if p["ndk"] else None, "cmake": os.path.basename(p["cmake"]) if p["cmake"] else None,
        "sdkmanager": bool(which("sdkmanager")), "apksigner": bool(which("apksigner")), "aapt2": bool(which("aapt2")),
        "d8": bool(which("d8")), "lint": bool(which("lint")), "apkanalyzer": bool(which("apkanalyzer")),
        "bundletool": bool(which("bundletool")), "adb": shutil.which("adb"),
        "java": version_of(p["java_home"], "release", "JAVA_VERSION"),
        "kotlinc": (open(os.path.join(p["kotlinc"], "build.txt")).read().strip() if p["kotlinc"] and os.path.isfile(os.path.join(p["kotlinc"], "build.txt")) else None),
        "gradle": os.path.basename(p["gradle"]).replace("gradle-", "") if p["gradle"] else None,
        "rust": toolchain, "cargo": bool(which("cargo")), "rust_analyzer": bool(which("rust-analyzer")),
        "kotlin_lsp": os.path.basename(p["kotlin_lsp"]).replace("kotlin-server-", "") if p["kotlin_lsp"] else None,
        "jdtls": os.path.isdir(os.path.join(p["jdtls"], "plugins")),
        "lemminx": os.access(p["lemminx"], os.X_OK) if p["lemminx"] else False,
    }


def is_android_project(root: str) -> bool:
    files = [os.path.join(root, n) for n in ("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")]
    files += sorted(glob.glob(os.path.join(root, "*", "build.gradle")) + glob.glob(os.path.join(root, "*", "build.gradle.kts")))[:30]
    for f in files:   # the project's own build files and its modules' (a multiplatform module names the Android plugin itself)
        if os.path.isfile(f):
            try:
                with open(f, "r", encoding="utf-8", errors="replace") as fh:
                    if "com.android" in fh.read(200000):
                        return True
            except OSError:
                pass
    for pattern in ("AndroidManifest.xml", "*/AndroidManifest.xml", "*/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml"):
        if glob.glob(os.path.join(root, pattern)):
            return True
    return False


def prompt_section(root: str) -> str:
    """A short note for the agent's system prompt about the toolchains, so the model uses them correctly."""
    s = status()
    lines = []
    android = is_android_project(root)
    jvm = android or bool(glob.glob(os.path.join(root, "*.kt")) or glob.glob(os.path.join(root, "*", "*.kt"))
                          or glob.glob(os.path.join(root, "*.java")) or glob.glob(os.path.join(root, "*.gradle*")))
    rust = bool(glob.glob(os.path.join(root, "Cargo.toml")) or glob.glob(os.path.join(root, "*", "Cargo.toml")))
    if s["android_sdk"] and jvm:
        lines.append(f"- Android SDK at $ANDROID_HOME ({s['android_sdk']}): platforms {', '.join(s['platforms']) or 'none'}; "
                     f"build-tools {', '.join(s['build_tools']) or 'none'}; NDK {s['ndk'] or 'not installed'}. On the PATH: "
                     "sdkmanager, avdmanager, lint, apkanalyzer, aapt2, d8, apksigner, zipalign, bundletool, kotlinc, gradle, java "
                     f"{s['java'] or ''}.".replace("  ", " "))
        lines.append("- Devices: the target device is chosen in the Android view (`forge-device` lists them). Plain `adb` in your "
                     "commands already talks to the target. There are virtual devices (Android 11 to 17 in containers, as phone, "
                     "small phone, foldable, tablet, watch-sized, TV or desktop screens), phones, tablets and watches paired over "
                     "Wi-Fi, and the Googlebook itself (Android 17). To test on another device or screen shape: `forge-device use "
                     "<name or serial>`, `forge-device start <virtual device id>`, `forge-device profile <phone|tablet|foldable|"
                     "watch-round|tv|...>`, `forge-device dark on`, `forge-device font 1.3`.")
        lines.append("- Build with `forge-gradle assembleDebug` (it runs ./gradlew or Gradle inside the memory limit), install with "
                     "`adb install -r <apk>` (never gradlew installDebug), and start the app with "
                     "`adb shell monkey -p <applicationId> -c android.intent.category.LAUNCHER 1`. Never run `adb reboot` or "
                     "power a virtual device off from inside Android; stop it with `forge-device stop <id>`.")
        lines.append("- To see the running app, save a screenshot with `adb exec-out screencap -p > ~/.forge-ide/screen.png`, then "
                     "call look_at_image with that path and a question; the vision model looks at it for you. For errors, read "
                     "`adb logcat -d -t 300 '*:E'`.")
        lines.append("- Builds run inside a 2 GB memory limit and need free memory; keep Gradle builds to one at a time.")
    try:
        from .android import kmp_info
        kmp = kmp_info(root) if jvm else []
    except Exception:  # noqa: BLE001
        kmp = []
    for m in kmp:
        lines.append(f"- Kotlin Multiplatform module {m['module']} (targets: {', '.join(m['targets']) or 'none'}). Source sets are "
                     "src/<set>/kotlin (commonMain, jvmMain, androidMain, ...); put shared code in commonMain with expect "
                     "declarations and the actual ones in each platform set. Build and test with forge-gradle, for example "
                     f"`forge-gradle {m['gradle_path']}:compileKotlinJvm`, `{m['gradle_path']}:jvmTest` or `{m['gradle_path']}:allTests`. "
                     "iOS and macOS targets cannot be compiled on Linux.")
    if s["cargo"] and rust:
        lines.append(f"- Rust toolchain ({s['rust']}): cargo, rustc, clippy and rustfmt are on the PATH. Use cargo check or cargo build.")
    if not lines:
        return ""
    head = "## Toolchains on this computer" + (" (this is an Android project)" if android else "")
    return head + "\n" + "\n".join(lines)
