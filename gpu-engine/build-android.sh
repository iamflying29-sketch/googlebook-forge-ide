#!/bin/bash
# Cross-compiles llama.cpp (llama-server, Vulkan backend) for Android x86_64 with NDK r27c. Static build: one binary.
set -euo pipefail
cd "${LLAMA_CPP_DIR:-$(dirname "$0")/llama.cpp}"
NDK=${ANDROID_NDK:-$HOME/tools/gpu-build/android-ndk-r27c}
SYSROOT=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot
cmake -S . -B build-android-x86_64 -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-33 -DANDROID_STL=c++_static \
  -DGGML_VULKAN=ON -DVulkan_INCLUDE_DIR=$(dirname "$0")/vkinclude \
  -DVulkan_LIBRARY=$SYSROOT/usr/lib/x86_64-linux-android/33/libvulkan.so \
  -DVulkan_GLSLC_EXECUTABLE=/usr/bin/glslc \
  -DGGML_NATIVE=OFF -DGGML_AVX=ON -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON \
  -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF \
  -DBUILD_SHARED_LIBS=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON \
  -DSPIRV-Headers_DIR=/usr/share/cmake/SPIRV-Headers -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH
nice -n 10 cmake --build build-android-x86_64 --config Release --parallel 4 --target llama-server llama-cli llama-bench
echo BUILD_OK
