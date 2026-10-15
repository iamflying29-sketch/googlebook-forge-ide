"""Kotlin light mode: a project model for JetBrains' Kotlin language server that needs no Gradle run.

In a Gradle project the Kotlin server normally runs a Gradle import every time it starts, which starts a second large
Java process next to it. The server reads a `workspace.json` project description first when one exists, so Forge
writes one: source folders per module, the JDK, the Kotlin standard library, Android's android.jar for Android
modules, jars in libs folders, and the project's declared libraries when Gradle has already downloaded them.

The file lives in Forge's own cache folder (a "shadow" project directory whose module paths point at the real
folder), so nothing is written into your project. The format follows JetBrains' own model
(kotlin-lsp, workspace-import/src/com/jetbrains/ls/imports/json/model.kt)."""
from __future__ import annotations

import glob
import hashlib
import json
import os
import re
from typing import Dict, List, Optional, Tuple

HOME = os.path.expanduser("~")
SHADOW_ROOT = os.path.join(HOME, ".forge-ide", "lsp-cache", "kotlin-light")
GRADLE_CACHE = os.path.join(HOME, ".gradle", "caches")
SKIP = {"build", ".gradle", ".git", ".idea", "node_modules", ".forge", ".cxx", "out"}


def _modules(root: str) -> List[str]:
    """Module folders: every folder (up to three levels) with a Gradle build file, or the folder itself."""
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        depth = dirpath[len(root):].count(os.sep)
        dirnames[:] = [d for d in dirnames if d not in SKIP and not d.startswith(".")] if depth < 3 else []
        if any(f in ("build.gradle", "build.gradle.kts", "pom.xml") for f in filenames):
            found.append(dirpath)
    return found or [root]


def _source_roots(mod: str, loose: bool) -> List[dict]:
    if loose:
        return [{"path": mod, "type": "java-source"}]
    roots = []
    for sset in sorted(glob.glob(os.path.join(mod, "src", "*"))):
        name = os.path.basename(sset)
        test = name in ("test", "androidTest", "testDebug", "testRelease") or name.endswith("Test")
        for lang in ("kotlin", "java"):
            p = os.path.join(sset, lang)
            if os.path.isdir(p):
                roots.append({"path": p, "type": "java-test" if test else "java-source"})
    return roots


R_JAR_PATTERNS = (  # where the Android Gradle Plugin puts the compiled R class after a build (newest layouts first)
    "build/intermediates/compile_r_class_jar/*/*/R.jar",
    "build/intermediates/compile_and_runtime_r_class_jar/*/*/R.jar",
    "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/*/*/R.jar",
    "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/*/R.jar",
)
GENERATED_SOURCES = ("build/generated/source/buildConfig/*", "build/generated/ksp/*/kotlin", "build/generated/ksp/*/java",
                     "build/generated/source/kapt/*", "build/generated/ap_generated_sources/*/out")


def _android_generated(mod: str) -> Tuple[List[str], List[dict]]:
    """The R class jar and generated source folders of an Android module that was built at least once (the debug
    variant is preferred, as Forge's builds use it)."""
    # AGP keeps several R jars (an app's current one is in compile_and_runtime_r_class_jar in AGP 9); the newest
    # debug one is the one the last build produced.
    found = [p for pattern in R_JAR_PATTERNS for p in glob.glob(os.path.join(mod, pattern))]
    found.sort(key=lambda p: ("/debug/" not in p, -os.path.getmtime(p)))
    jars = found[:1]
    roots = []
    for pattern in GENERATED_SOURCES:
        dirs = sorted(glob.glob(os.path.join(mod, pattern)), key=lambda p: "/debug" not in p)
        if dirs and os.path.isdir(dirs[0]):
            roots.append({"path": dirs[0], "type": "java-source"})   # only path and type: the server rejects unknown keys
    return jars, roots


def _coordinates(root: str) -> List[Tuple[str, str, str]]:
    """group:artifact:version strings in build files and the version catalog."""
    coords = set()
    texts = []
    for f in glob.glob(os.path.join(root, "**", "build.gradle*"), recursive=True)[:50]:
        if "/build/" in f:
            continue
        try:
            texts.append(open(f, encoding="utf-8", errors="replace").read(400000))
        except OSError:
            pass
    for t in texts:
        for g, a, v in re.findall(r"[\"']([\w.\-]+):([\w.\-]+):([\w.\-+]+)[\"']", t):
            coords.add((g, a, v))
    toml = os.path.join(root, "gradle", "libs.versions.toml")
    if os.path.isfile(toml):
        try:
            text = open(toml, encoding="utf-8", errors="replace").read(400000)
        except OSError:
            text = ""
        versions = dict(re.findall(r"^\s*([\w.\-]+)\s*=\s*\"([^\"]+)\"", text.split("[libraries]")[0], flags=re.M))
        libs = text.split("[libraries]", 1)[1].split("\n[", 1)[0] if "[libraries]" in text else ""
        for line in libs.splitlines():
            m = re.search(r"module\s*=\s*\"([\w.\-]+):([\w.\-]+)\"", line) or re.search(r"group\s*=\s*\"([\w.\-]+)\".*name\s*=\s*\"([\w.\-]+)\"", line)
            if not m:
                continue
            v = re.search(r"version\.ref\s*=\s*\"([\w.\-]+)\"", line)
            ver = versions.get(v.group(1)) if v else (re.search(r"version\s*=\s*\"([^\"]+)\"", line) or [None, None])[1]
            if ver:
                coords.add((m.group(1), m.group(2), ver))
    return sorted(coords)


def _find_jars(group: str, artifact: str, version: str) -> List[str]:
    """Jars Gradle already downloaded or unpacked (AARs become classes.jar in the transforms cache)."""
    jars = glob.glob(os.path.join(GRADLE_CACHE, "modules-2", "files-2.1", group, artifact, version, "*", f"{artifact}-{version}.jar"))
    if not jars:
        for pattern in (os.path.join(GRADLE_CACHE, "transforms-*", "*", "transformed", f"{artifact}-{version}", "jars", "classes.jar"),
                        os.path.join(GRADLE_CACHE, "*", "transforms", "*", "transformed", f"{artifact}-{version}", "jars", "classes.jar")):
            jars = glob.glob(pattern)
            if jars:
                break
    return jars[:1]


def build_model(root: str) -> Tuple[dict, dict]:
    """The workspace model and a short summary of what it contains."""
    from .toolchains import is_android_project, paths
    tp = paths()
    root = os.path.realpath(root)
    mods = _modules(root)
    loose = mods == [root] and not any(os.path.isfile(os.path.join(root, f)) for f in ("build.gradle", "build.gradle.kts", "pom.xml"))
    libraries: List[dict] = []
    lib_names: List[str] = []

    def add_lib(name: str, classes: List[str], sources: Optional[List[str]] = None) -> None:
        if name in lib_names or not classes:
            return
        # Plain paths, as JetBrains' own export writes them; the importer turns jar files into jar roots itself.
        roots = [{"path": c, "type": "CLASSES"} for c in classes]
        roots += [{"path": s, "type": "SOURCES"} for s in (sources or [])]
        libraries.append({"name": name, "type": None, "roots": roots})
        lib_names.append(name)

    kotlinc_lib = os.path.join(tp["kotlinc"] or "", "lib")
    stdlib = os.path.join(kotlinc_lib, "kotlin-stdlib.jar")
    if os.path.isfile(stdlib):
        add_lib("kotlin-stdlib", [stdlib], [p for p in [os.path.join(kotlinc_lib, "kotlin-stdlib-sources.jar")] if os.path.isfile(p)])
    android = is_android_project(root)
    if android and tp["sdk"]:
        jars = sorted(glob.glob(os.path.join(tp["sdk"], "platforms", "android-*", "android.jar")))
        if jars:
            # Classes only: the Android sources are thousands of files, and indexing them pushed the server past its
            # memory cap (2026-10-14). Every Android class still resolves; only hover documentation for Android's
            # own methods is shorter.
            add_lib("android-sdk", [jars[-1]])
    found_coords, missing_coords = [], []
    for g, a, v in _coordinates(root):
        jars = _find_jars(g, a, v)
        if jars:
            add_lib(f"{g}:{a}:{v}", jars)
            found_coords.append(f"{g}:{a}:{v}")
        else:
            missing_coords.append(f"{g}:{a}:{v}")
    local_jars = []
    for mod in mods:
        for j in glob.glob(os.path.join(mod, "libs", "**", "*.jar"), recursive=True)[:200]:
            local_jars.append(j)
    if local_jars:
        add_lib("local-jars", local_jars)

    sdk_name = "java-21"
    deps = [{"type": "moduleSource"}, {"type": "sdk", "name": sdk_name, "kind": "JavaSDK"}]
    deps += [{"type": "library", "name": n, "scope": "compile"} for n in lib_names]
    modules = []
    used = set()
    for mod in mods:
        roots = _source_roots(mod, loose)
        if not roots and not loose and len(mods) > 1:
            continue   # a folder with only build settings (the root of a multi-module project) has no code
        mod_deps = list(deps)
        if android:
            r_jars, gen_roots = _android_generated(mod)
            roots = roots + gen_roots
            if r_jars:
                lib = "R:" + (os.path.relpath(mod, root) if mod != root else "app")
                add_lib(lib, r_jars)
                mod_deps.append({"type": "library", "name": lib, "scope": "compile"})
        name = os.path.relpath(mod, root).replace(os.sep, ".") if mod != root else os.path.basename(root) or "project"
        while name in used:
            name += "_"
        used.add(name)
        modules.append({"name": name, "type": "JAVA_MODULE", "dependencies": mod_deps,
                        "contentRoots": [{"path": mod, "sourceRoots": roots}]})
    sdks = []
    if tp["java_home"]:
        sdks.append({"name": sdk_name, "type": "JavaSDK", "version": "21", "homePath": tp["java_home"], "additionalData": ""})
    model = {"modules": modules, "libraries": libraries, "sdks": sdks, "kotlinSettings": [], "javaSettings": []}
    summary = {"modules": len(modules), "libraries": lib_names, "found": found_coords, "missing": missing_coords,
               "android": android, "loose": loose}
    return model, summary


def shadow_dir(root: str) -> str:
    """Forge's cache folder that stands in for the project when the Kotlin server runs in light mode."""
    root = os.path.realpath(root)
    d = os.path.join(SHADOW_ROOT, hashlib.sha1(root.encode()).hexdigest()[:16])
    os.makedirs(d, exist_ok=True)
    model, summary = build_model(root)
    text = json.dumps(model, indent=1)
    target = os.path.join(d, "workspace.json")
    try:
        with open(target, "r", encoding="utf-8") as fh:
            same = fh.read() == text
    except OSError:
        same = False
    if not same:
        with open(target + ".new", "w", encoding="utf-8") as fh:
            fh.write(text)
        os.replace(target + ".new", target)
    with open(os.path.join(d, "forge-summary.json"), "w", encoding="utf-8") as fh:
        json.dump({"root": root, **summary}, fh, indent=1)
    return d
