#!/usr/bin/env bash
# Fetch the native sources used by the Android app (pinned versions).
set -euo pipefail
cd "$(dirname "$0")/../android"
mkdir -p third_party && cd third_party

QWEN_COMMIT=f1a6924789883bd92eeb59176793a94af55730b3   # ServeurpersoCom/qwentts.cpp (MIT)
SHINE_COMMIT=ab5e3526b64af1a2eaa43aa6f441a7312e013519  # toots/shine (LGPL-2.0)

fetch() { # url dir commit
  if [ -f "$2/.vs_commit" ] && [ "$(cat "$2/.vs_commit")" = "$3" ]; then echo "[OK] $2"; return; fi
  rm -rf "$2"; mkdir -p "$2"
  git -C "$2" init -q
  git -C "$2" remote add origin "$1"
  git -C "$2" fetch -q --depth 1 origin "$3"
  git -C "$2" checkout -q FETCH_HEAD
  echo "$3" > "$2/.vs_commit"
}

fetch https://github.com/ServeurpersoCom/qwentts.cpp.git qwentts.cpp "$QWEN_COMMIT"
git -C qwentts.cpp submodule update --init --depth 1 ggml
fetch https://github.com/toots/shine.git shine "$SHINE_COMMIT"

# Vulkan (GPU, experimental): C++ headers + SPIR-V headers, same SDK version
VK_TAG=vulkan-sdk-1.4.363.0
fetch https://github.com/KhronosGroup/Vulkan-Headers.git Vulkan-Headers "refs/tags/$VK_TAG"
fetch https://github.com/KhronosGroup/SPIRV-Headers.git SPIRV-Headers "refs/tags/$VK_TAG"
if [ ! -f spirv-install/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake ]; then
  cmake -S SPIRV-Headers -B spirv-build -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DCMAKE_INSTALL_PREFIX="$PWD/spirv-install" > /dev/null
  cmake --install spirv-build > /dev/null
fi

# CPU threads: let the app choose (VS_THREADS), upstream uses half the cores
if ! grep -q VS_THREADS qwentts.cpp/src/backend.h; then
  sed -i 's|    int n = (int) std::thread::hardware_concurrency() / 2;|    const char * vs = std::getenv("VS_THREADS");\n    if (vs \&\& std::atoi(vs) > 0) return std::atoi(vs);\n    int n = (int) std::thread::hardware_concurrency() / 2;|' qwentts.cpp/src/backend.h
  grep -q VS_THREADS qwentts.cpp/src/backend.h || { echo "patch failed"; exit 1; }
  grep -q "<cstdlib>" qwentts.cpp/src/backend.h || sed -i '0,/#include/s//#include <cstdlib>\n#include/' qwentts.cpp/src/backend.h
fi
echo "done"
