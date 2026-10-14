#!/bin/bash
# Bounded GPU smoke test on the Googlebook (host side) via adb, as the shell user.
# Usage: smoke-test.sh <model.gguf> [n_predict]   (binaries from llama.cpp/build-android-x86_64/bin)
# Safety: the test process gets oom_score_adj 1000 (first to be reclaimed), everything has a timeout,
# and the model is the small one unless you pass the big one explicitly.
set -uo pipefail
MODEL="$1"; N="${2:-32}"
BIN=${LLAMA_CPP_DIR:-$(dirname "$0")/llama.cpp}/build-android-x86_64/bin
D=/data/local/tmp/forge-gpu
adb shell "mkdir -p $D" >/dev/null
for b in llama-cli llama-bench; do
  if ! adb shell "test -f $D/$b" 2>/dev/null; then adb push "$BIN/$b" "$D/$b" >/dev/null && adb shell "chmod 755 $D/$b"; fi
done
name=$(basename "$MODEL")
if ! adb shell "test -f $D/$name" 2>/dev/null; then echo "pushing $name ..."; adb push "$MODEL" "$D/$name" | tail -1; fi
echo "== host memory before =="; adb shell 'grep -E "MemTotal|MemAvailable" /proc/meminfo'
echo "== llama-cli, Vulkan, all layers on GPU, $N tokens =="
timeout 300 adb shell "cd $D && echo 1000 > /proc/self/oom_score_adj; HOME=$D TMPDIR=$D timeout 280 ./llama-cli -m $D/$name -ngl 99 -n $N -t 4 -no-cnv -p 'Write one sentence about the sea.' 2>&1 | grep -v '^$' | grep -i -E 'vulkan|device|offload|llama_perf|error|fail|abort|tokens per|eval time|load time|Intel|sentence|sea' | head -40"
echo "== host memory after =="; adb shell 'grep -E "MemAvailable" /proc/meminfo'
