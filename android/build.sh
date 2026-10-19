#!/bin/bash
# Builds and signs Forge.apk (the native Kotlin window for Forge IDE) with the Android SDK in ~/tools/android-sdk
# and the Kotlin compiler in ~/tools/kotlin. A fresh build directory is used each time; nothing is ever deleted.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-$HOME/tools/android-sdk}
KOTLIN=${KOTLIN_HOME:-$HOME/tools/kotlin/kotlinc}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
KS=keystore.jks
KS_PASS=forge-local
B=build/$(date +%Y%m%d-%H%M%S)
mkdir -p "$B/classes" "$B/gen" assets
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml -A assets \
    --min-sdk-version 33 --target-sdk-version 35 --version-code 30 --version-name 3.0 \
    --java "$B/gen" "$B/res.zip"
# 1. The generated R class, so both languages can use the resources.
javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d "$B/classes" "$B/gen/local/forge/R.java"
# 2. Kotlin: the whole app (the window, the chat, the editor, the terminal, the settings, the GPU engine service,
#    the vision model control, the memory budget and voice input).
"$KOTLIN/bin/kotlinc" -jvm-target 11 -no-reflect -cp "$JAR:$B/classes" -d "$B/classes" \
    $(find src/kotlin -name '*.kt') 2>&1 | grep -vE "^warning:|^w: " || true
test -f "$B/classes/local/forge/MainActivity.class" || { echo "Kotlin compilation failed"; exit 1; }
test -f "$B/classes/local/forge/EngineService.class" || { echo "Kotlin compilation failed (EngineService)"; exit 1; }
# 3. Dex: the app's classes plus the Kotlin standard library.
"$BT/d8" --min-api 33 --lib "$JAR" --output "$B" $(find "$B/classes" -name '*.class') "$KOTLIN/lib/kotlin-stdlib.jar"
python3 -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex")' \
    "$B/unsigned.apk" "$B/classes.dex"
# Native engine binary (built by ../../tools/gpu-build/build-android.sh): packaged as lib/x86_64/libllama-server.so
if [ -f jni/x86_64/libllama-server.so ]; then
    python3 -I -c 'import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], "a", compression=zipfile.ZIP_DEFLATED); z.write(sys.argv[2], "lib/x86_64/libllama-server.so"); z.close()' \
        "$B/unsigned.apk" jni/x86_64/libllama-server.so
    echo "packaged GPU engine binary ($(du -h jni/x86_64/libllama-server.so | cut -f1))"
fi
"$BT/zipalign" -p -f 4 "$B/unsigned.apk" "$B/aligned.apk"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias forge \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Forge (local build)"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out Forge.apk "$B/aligned.apk"
"$BT/apksigner" verify Forge.apk
echo "Built $(pwd)/Forge.apk"
