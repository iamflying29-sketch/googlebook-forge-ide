#!/bin/bash
# Installs the optional parts of Forge IDE. Each part is independent; Forge works without any of them and
# turns the matching feature off when a part is missing.
#
#   ./install-extras.sh lsp        language servers (suggestions, hover, go to definition, live errors)
#   ./install-extras.sh debug      the Python debugger (debugpy)
#   ./install-extras.sh index      the meaning-based codebase index (a small llama.cpp server and nomic-embed-text)
#   ./install-extras.sh docs       PDF reading for attachments (pdftotext) and text recognition (tesseract)
#   ./install-extras.sh android    Android SDK packages, Kotlin, Gradle, bundletool, and the Kotlin, Java and XML
#                                  language servers (code intelligence for Android projects)
#   ./install-extras.sh rust       Rust through rustup: rustc, cargo, rust-analyzer, clippy and rustfmt
#   ./install-extras.sh all        everything above
#
# Safety: builds run at low priority inside a systemd scope with a memory cap, and every step first checks that
# the VM has enough free memory. Downloads are checked against published SHA-256 hashes. Nothing is ever deleted.
set -euo pipefail
WANT="${1:-all}"
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"

need_mem() {   # need_mem MB: stop if the VM has less free memory than that
  local free
  free=$(free -m | awk 'NR==2 {print $7}')
  if [ "$free" -lt "$1" ]; then
    echo "Only $free MB of memory is free in the VM and this step needs $1 MB. Close other programs (or unload the"
    echo "model with 'forge-ide' Unload in the command palette) and run this again."
    exit 1
  fi
}

capped() {     # capped MB command...: run a heavy command with a memory cap at low priority
  local cap=$1; shift
  if command -v systemd-run >/dev/null 2>&1; then
    systemd-run --user --scope --quiet -p "MemoryMax=${cap}M" -p MemorySwapMax=0 nice -n 10 "$@"
  else
    nice -n 10 "$@"
  fi
}

fetch_checked() {   # fetch_checked URL SHA256 TARGET
  local url=$1 sum=$2 target=$3
  if [ -f "$target" ] && echo "$sum  $target" | sha256sum -c --status; then
    echo "Already present: $target"
    return
  fi
  mkdir -p "$(dirname "$target")"
  curl -fL --max-filesize 600000000 -o "$target.part" "$url"
  if ! echo "$sum  $target.part" | sha256sum -c --status; then
    echo "The download of $url does not match its published hash. It was kept as $target.part for inspection."
    exit 1
  fi
  mv "$target.part" "$target"
}

do_lsp() {
  echo "== Language servers"
  sudo apt-get install -y python3-pylsp clangd nodejs npm
  mkdir -p ~/.local/lib/forge-lsp
  cd ~/.local/lib/forge-lsp
  [ -f package.json ] || echo '{}' > package.json
  need_mem 800
  capped 1500 npm install --no-audit --no-fund \
    bash-language-server@5.4.2 typescript@5.6.3 typescript-language-server@4.3.3 vscode-langservers-extracted@4.10.0
}

do_debug() {
  echo "== Debuggers: debugpy (Python), GDB (C, C++ and Rust) and js-debug 1.140.0 (JavaScript and TypeScript)"
  sudo apt-get install -y python3-debugpy gdb
  local D=$HOME/.local/lib/forge-jsdebug
  fetch_checked https://github.com/microsoft/vscode-js-debug/releases/download/v1.140.0/js-debug-dap-v1.140.0.tar.gz \
    27dab92937ec1ab35821ae955aac867544fe06a1b6307229049f2d789af10968 "$D/dl-1.140.0/js-debug-dap-v1.140.0.tar.gz"
  mkdir -p "$D/v1.140.0"
  tar -xzf "$D/dl-1.140.0/js-debug-dap-v1.140.0.tar.gz" -C "$D/v1.140.0"
}

do_docs() {
  echo "== PDF reading and text recognition"
  sudo apt-get install -y poppler-utils tesseract-ocr tesseract-ocr-eng
}

do_index() {
  echo "== Codebase index (llama.cpp CPU server and nomic-embed-text v1.5)"
  sudo apt-get install -y git cmake g++ curl
  local src=~/.local/lib/forge-embed/llama.cpp
  if [ ! -d "$src" ]; then
    git clone https://github.com/ggml-org/llama.cpp.git "$src"
    git -C "$src" checkout -q 091b6a7fc561b78da90a0ed2d524ec995458c657
  fi
  need_mem 2000
  capped 1500 bash -c "cmake -S '$src' -B '$src/build-cpu' -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=ON -DGGML_VULKAN=OFF -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF >/dev/null && cmake --build '$src/build-cpu' -j2 --target llama-server"
  cp "$src/build-cpu/bin/llama-server" ~/.local/lib/forge-embed/llama-server
  fetch_checked https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q8_0.gguf \
    3e24342164b3d94991ba9692fdc0dd08e3fd7362e0aacc396a9a5c54a544c3b7 ~/.forge-ide/models/embed/nomic-embed-text-v1.5.Q8_0.gguf
}

do_android() {
  echo "== Android and Kotlin"
  local A=$HOME/.local/lib/forge-android SDK=${ANDROID_HOME:-$HOME/tools/android-sdk}
  mkdir -p "$A/dl" "$A/bin"
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "The Android SDK command-line tools are needed in $SDK/cmdline-tools/latest (see the hub's GETTING-STARTED guide)."
    exit 1
  fi
  need_mem 1500
  # Input is closed: if Google ever asks for a new license, sdkmanager stops instead of accepting it for you.
  capped 1200 "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-36" "platforms;android-36.1" "platforms;android-37.2" \
    "build-tools;36.1.0" "build-tools;37.0.0" "sources;android-35" "sources;android-36" "ndk;29.0.14206865" "cmake;4.1.2" < /dev/null
  local K=https://download.jetbrains.com/language-server/kotlin-server/263.6379.0/kotlin-server-263.6379.0.tar.gz
  fetch_checked "$K" "$(curl -fsSL "$K.sha256" | awk '{print $1}')" "$A/dl/kotlin-server-263.6379.0.tar.gz"
  local J=https://download.eclipse.org/jdtls/milestones/1.61.0/jdt-language-server-1.61.0-202609031315.tar.gz
  fetch_checked "$J" "$(curl -fsSL "$J.sha256" | awk '{print $1}')" "$A/dl/jdt-language-server-1.61.0.tar.gz"
  fetch_checked https://github.com/JetBrains/kotlin/releases/download/v2.4.21/kotlin-compiler-2.4.21.zip \
    7cc140e76daf416a0424a5557f99afd7ca89ebd463ea1101261e3e01c33e75cd "$A/dl/kotlin-compiler-2.4.21.zip"
  local G=https://services.gradle.org/distributions/gradle-9.8.1-bin.zip
  fetch_checked "$G" "$(curl -fsSL "$G.sha256" | awk '{print $1}')" "$A/dl/gradle-9.8.1-bin.zip"
  fetch_checked https://github.com/redhat-developer/vscode-xml/releases/download/0.29.3/lemminx-linux-x86_64.zip \
    1acc44e24201c1d2f5ccb4e43e7426ed0df6909207a81ff199810e2808104d89 "$A/dl/lemminx-linux-x86_64-0.29.3.zip"
  fetch_checked https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar \
    a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29 "$A/bundletool-all-1.18.3.jar"
  mkdir -p "$A/kotlin-lsp" "$A/jdtls" "$A/lemminx"
  [ -d "$A/kotlin-lsp/kotlin-server-263.6379.0" ] || tar -xzf "$A/dl/kotlin-server-263.6379.0.tar.gz" -C "$A/kotlin-lsp"
  [ -d "$A/jdtls/plugins" ] || tar -xzf "$A/dl/jdt-language-server-1.61.0.tar.gz" -C "$A/jdtls"
  [ -d "$A/kotlinc" ] || unzip -q -o "$A/dl/kotlin-compiler-2.4.21.zip" -d "$A"
  [ -d "$A/gradle-9.8.1" ] || unzip -q -o "$A/dl/gradle-9.8.1-bin.zip" -d "$A"
  [ -x "$A/lemminx/lemminx-linux-x86_64" ] || unzip -q -o "$A/dl/lemminx-linux-x86_64-0.29.3.zip" -d "$A/lemminx"
  # The Kotlin server's own default is a 2 GB Java heap; 1 GB keeps it next to the model on this machine.
  local VMO="$A/kotlin-lsp/kotlin-server-263.6379.0/bin/intellij-server.vmoptions"
  [ -f "$VMO.orig" ] || cp "$VMO" "$VMO.orig"
  sed -i 's/^-Xmx2048m$/-Xmx1024m/; s/^-XX:ReservedCodeCacheSize=512m$/-XX:ReservedCodeCacheSize=240m/' "$VMO"
  install -m 755 "$HERE/bin/forge-gradle" "$HERE/bin/forge-cargo" "$A/bin/"
  printf '#!/bin/sh\nexec java -Xmx768m -jar "$HOME/.local/lib/forge-android/bundletool-all-1.18.3.jar" "$@"\n' > "$A/bin/bundletool"
  chmod +x "$A/bin/bundletool"
  mkdir -p "$HOME/.gradle"
  if [ ! -f "$HOME/.gradle/gradle.properties" ]; then
    cat > "$HOME/.gradle/gradle.properties" <<'GP'
# Memory limits for Gradle, written by Forge IDE's install-extras.sh. They take precedence over project settings.
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseParallelGC -Dfile.encoding=UTF-8
kotlin.compiler.execution.strategy=in-process
org.gradle.workers.max=2
org.gradle.parallel=false
org.gradle.daemon.idletimeout=900000
GP
  else
    echo "Kept your existing ~/.gradle/gradle.properties. Forge recommends org.gradle.jvmargs=-Xmx1024m on this machine."
  fi
}

do_rust() {
  echo "== Rust"
  local A=$HOME/.local/lib/forge-android U=https://static.rust-lang.org/rustup/archive/1.29.1/x86_64-unknown-linux-gnu/rustup-init
  mkdir -p "$A/dl"
  fetch_checked "$U" "$(curl -fsSL "$U.sha256" | awk '{print $1}')" "$A/dl/rustup-init-1.29.1"
  chmod +x "$A/dl/rustup-init-1.29.1"
  need_mem 1200
  # --no-modify-path leaves your shell profile alone; Forge puts ~/.cargo/bin on its own PATH.
  capped 1200 "$A/dl/rustup-init-1.29.1" -y --no-modify-path --profile minimal --default-toolchain stable \
    -c rust-analyzer -c rust-src -c clippy -c rustfmt
}

do_kernels() {
  echo "== Notebook kernels: Kotlin (JetBrains) and Bash, through Jupyter"
  need_mem 800
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3-jupyter-client python3-zmq python3-ipykernel python3-pexpect python3-venv
  local J=$HOME/.local/lib/forge-jupyter
  mkdir -p "$J"
  [ -x "$J/venv/bin/python" ] || python3 -m venv --system-site-packages "$J/venv"
  cp "$HERE/jupyter/requirements.txt" "$J/requirements.txt"
  # Exact versions with their published SHA-256 sums; pip refuses anything else.
  capped 800 "$J/venv/bin/pip" install --no-deps --require-hashes -r "$J/requirements.txt"
  "$J/venv/bin/python" -m bash_kernel.install --prefix "$J/venv"
  echo "Other Jupyter kernels installed into $J/venv appear in the notebook's kernel menu too."
}

do_devices() {
  echo "== Virtual devices (Podman) and the Forge Android guard"
  need_mem 800
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends podman crun passt fuse-overlayfs
  "$HERE/guard/install.sh"
  echo "== Emulated ARM devices (the makers' own Android): QEMU for ARM, its firmware, the screenshot library, the SELinux policy compiler and the file label tool"
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends qemu-system-arm qemu-efi-aarch64 qemu-utils python3-pil secilc attr
  echo "Download Android versions in Forge's Android view, under Virtual devices (Android 17: forge-vd-image 17; makers' systems: forge-arm-image <version>)."
}

case "$WANT" in
  lsp) do_lsp ;;
  debug) do_debug ;;
  docs) do_docs ;;
  index) do_index ;;
  android) do_android ;;
  rust) do_rust ;;
  devices) do_devices ;;
  kernels) do_kernels ;;
  all) do_docs; do_debug; do_lsp; do_index; do_android; do_rust; do_kernels; do_devices ;;
  *) echo "Usage: $0 [lsp|debug|docs|index|android|rust|kernels|devices|all]"; exit 2 ;;
esac
echo "Done. Restart Forge with: forge-ide restart"
