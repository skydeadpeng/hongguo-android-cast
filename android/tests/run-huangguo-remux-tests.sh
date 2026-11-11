#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Host boundary doubles exercise control flow, not Android demuxing/MP4 validity.
sed '/^import android.media./d' android/app/src/main/java/io/github/jackotom/hongguo/android/HuangguoMp4Remuxer.java > "$work/HuangguoMp4Remuxer.java"
cp android/tests/HuangguoMp4RemuxerTest.java "$work/"
javac -d "$work/classes" "$work"/*.java
java -cp "$work/classes" io.github.jackotom.hongguo.android.HuangguoMp4RemuxerTest "$work"
