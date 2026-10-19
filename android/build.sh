#!/bin/bash
# Builds and signs Forge.apk (the native window for Forge IDE) with the Android SDK in ~/tools/android-sdk.
# A fresh build directory is used each time; nothing is ever deleted.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-$HOME/tools/android-sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
KS=keystore.jks
KS_PASS=forge-local
B=build/$(date +%Y%m%d-%H%M%S)
mkdir -p "$B/classes" "$B/gen"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml -A assets \
    --min-sdk-version 33 --target-sdk-version 35 --version-code 7 --version-name 1.6 \
    --java "$B/gen" "$B/res.zip"
javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d "$B/classes" \
    "$B/gen/local/forge/R.java" src/local/forge/*.java
"$BT/d8" --min-api 33 --lib "$JAR" --output "$B" $(find "$B/classes" -name '*.class')
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
