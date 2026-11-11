#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
python3 "$HERE/huangguo-api-harness.py" "$SRC" "$TMP/generated"
javac -encoding UTF-8 -d "$TMP/classes" "$TMP/generated/HuangguoApi.java" \
 "$SRC/HuangguoHls.java" "$SRC/DlnaSessionFile.java" "$HERE/HuangguoApiTest.java"
java -cp "$TMP/classes" io.github.jackotom.hongguo.android.HuangguoApiTest
