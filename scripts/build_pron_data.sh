#!/usr/bin/env bash
# Rebuild android/app/src/main/assets/pron.json (needs network: GitHub + PyPI)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W="$(mktemp -d)"
git clone -q --depth 1 https://github.com/BYVoid/OpenCC "$W/opencc"
git clone -q --depth 1 https://github.com/chewing/libchewing-data "$W/chewing"
git clone -q --depth 1 --filter=blob:none --no-checkout https://github.com/g0v/moedict-data "$W/moedict"
git -C "$W/moedict" checkout HEAD -- dict-revised.json.xz && xz -d "$W/moedict/dict-revised.json.xz"
pip install -q pypinyin jieba dragonmapper
python3 "$ROOT/scripts/build_pron_data.py" "$W/opencc/data/dictionary" "$W/chewing/dict/chewing/tsi.csv" "$W/moedict/dict-revised.json" \
  "$ROOT/android/app/src/main/assets/pron.json"
