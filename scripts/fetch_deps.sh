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
echo "done"
