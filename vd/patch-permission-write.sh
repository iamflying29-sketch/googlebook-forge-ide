#!/bin/bash
# patch-permission-write.sh <mounted system root> <work dir>: patches the system server's services.jar so that the
# legacy permission write survives when the new permission service has not finished initializing. On an emulated CPU
# the system server's delayed "write settings" message fires while the permission service is still reading its
# persisted state, and the write then dies with "lateinit property state has not been initialized", which kills the
# system server at every boot after the first (OxygenOS and ColorOS, found 2026-10-17). The patch wraps the one call
# in PermissionManagerService$PermissionManagerServiceInternalImpl.writeLegacyPermissionsTEMP in a try/catch that
# ignores the failure; the next write settings message repeats the write once the state exists. Part of Forge IDE.
# Needs baksmali and smali (Debian package smali). Exit 0 when patched or already patched, 2 when the class is absent.
set -euo pipefail
M=$1; W=$2
JAR=$M/system/framework/services.jar
[ -f "$JAR" ] || { echo "no services.jar"; exit 2; }
command -v baksmali >/dev/null 2>&1 || sudo -n apt-get install -y -q smali >/dev/null
mkdir -p "$W"; W=$(mktemp -d "$W/run-XXXXXX")   # a fresh work folder for every run; nothing is ever deleted here
mkdir -p "$W/dex"; cp "$JAR" "$W/services.jar"
(cd "$W/dex" && unzip -o -q ../services.jar 'classes*.dex')
CLASS='Lcom/android/server/pm/permission/PermissionManagerService$PermissionManagerServiceInternalImpl;'
DEX=""
for d in "$W"/dex/classes*.dex; do
  if timeout 300 baksmali list classes "$d" 2>/dev/null | grep -q -F "$CLASS"; then DEX=$d; break; fi
done
[ -n "$DEX" ] || { echo "permission manager class not found"; exit 2; }
OUT="$W/smali"
timeout 1200 baksmali disassemble -o "$OUT" "$DEX" >/dev/null
F="$OUT/com/android/server/pm/permission/PermissionManagerService\$PermissionManagerServiceInternalImpl.smali"
# baksmali names labels itself, so the patch is recognized by the catch it added to the method, not by a label name.
awk '/\.method public writeLegacyPermissionsTEMP\(/,/\.end method/' "$F" | grep -q 'catch Ljava/lang/Throwable;' && { echo "already patched"; exit 0; }
python3 - "$F" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
m = re.search(r"(\.method public writeLegacyPermissionsTEMP\(Lcom/android/server/pm/permission/LegacyPermissionSettings;\)V\n.*?)(    invoke-interface \{[vp][0-9]+, p1\}, Lcom/android/server/pm/permission/PermissionManagerServiceInterface;->writeLegacyPermissionsTEMP\(Lcom/android/server/pm/permission/LegacyPermissionSettings;\)V\n)(.*?\n)(    return-void\n\.end method)", s, re.S)
assert m, "method shape not recognized"
new = (m.group(1) + "    :try_start_forge\n" + m.group(2) + "    :try_end_forge\n"
       "    .catch Ljava/lang/Throwable; {:try_start_forge .. :try_end_forge} :catch_forge\n" + m.group(3) + "    :catch_forge\n" + m.group(4))
s = s[:m.start()] + new + s[m.end():]
open(p, "w").write(s); print("smali patched")
PY
API=$(sudo -n grep -o 'ro.build.version.sdk=[0-9]*' "$M/system/build.prop" 2>/dev/null | head -1 | cut -d= -f2 || true); API=${API:-35}
timeout 1200 smali assemble --api "$API" -o "$W/patched.dex" "$OUT" >/dev/null
python3 - "$W/services.jar" "$(basename "$DEX")" "$W/patched.dex" "$W/services.patched.jar" <<'PY'
import sys, zipfile
src, name, dex, dst = sys.argv[1:5]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for item in zin.infolist():
        data = open(dex, "rb").read() if item.filename == name else zin.read(item.filename)
        zi = zipfile.ZipInfo(item.filename, date_time=item.date_time); zi.compress_type = item.compress_type; zi.external_attr = item.external_attr
        zout.writestr(zi, data)
print("jar rebuilt")
PY
sudo -n test -f "$JAR.before-forge" || sudo -n cp -p "$JAR" "$JAR.before-forge"   # the maker's original, kept once
sudo -n install -o 0 -g 0 -m 0644 "$W/services.patched.jar" "$JAR"
sudo -n setfattr -h -n security.selinux -v u:object_r:system_file:s0 "$JAR"
echo "patched services.jar ($(basename "$DEX"), api $API)"
