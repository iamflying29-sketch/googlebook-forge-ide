#!/bin/bash
# Copies the Vulkan C++ headers and SPIR-V headers from the Debian packages into gpu-engine/vkinclude so the
# Android cross build can use them (the NDK ships only the C header). Run once after:
#   sudo apt-get install cmake ninja-build build-essential glslc libvulkan-dev spirv-headers
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p vkinclude
cp -r /usr/include/vulkan vkinclude/
cp -r /usr/include/vk_video vkinclude/ 2>/dev/null || true
cp -r /usr/include/spirv vkinclude/
echo "Headers staged in $(pwd)/vkinclude"
