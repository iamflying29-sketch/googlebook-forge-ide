#!/bin/bash
# Builds forge_guard.bpf.o for the running kernel (needs clang, bpftool and libbpf-dev).
set -e
cd "$(dirname "$0")"
OUT=${1:-$HOME/.local/lib/forge-guard}
mkdir -p "$OUT"
/usr/sbin/bpftool btf dump file /sys/kernel/btf/vmlinux format c > "$OUT/vmlinux.h"
clang -O2 -g -target bpf -D__TARGET_ARCH_x86 -I"$OUT" -c forge_guard.bpf.c -o "$OUT/forge_guard.bpf.o"
echo "built $OUT/forge_guard.bpf.o"
