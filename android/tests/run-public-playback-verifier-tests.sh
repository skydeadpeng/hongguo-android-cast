#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Compile the unchanged production logic, substituting only Android API boundaries.
# This test does not claim to demux/decode on Android or validate an emulator.
sed '/^import android.media./d' android/app/src/main/java/io/github/jackotom/hongguo/android/PublicPlaybackVerifier.java > "$work/PublicPlaybackVerifier.java"
cp android/tests/PublicPlaybackVerifierTest.java "$work/"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
if command -v "$javac_bin" >/dev/null 2>&1; then
  "$javac_bin" -source 17 -target 17 -encoding UTF-8 -d "$work/classes" "$work"/*.java
else
  # Some minimal JDK images ship the compiler module but not a javac launcher.
  "$java_bin" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -encoding UTF-8 -d "$work/classes" "$work"/*.java
fi
args=("$work")
if [[ $# -gt 0 ]]; then
  # Optional read-only replay of REAL media packets. Never generates test video.
  # ffprobe supplies a host demux boundary double; production parses MP4 boxes
  # and reads each packet's real bytes. Native Android demuxing remains untested.
  ffprobe -v error -show_streams -show_packets -show_entries stream=index,codec_name,codec_type,width,height:packet=stream_index,pts_time,size,pos,flags -of json "$1" > "$work/packets.json"
  python3 - "$work/packets.json" "$work/packets.txt" "${2:-139.034}" <<'PY'
import json, sys
from decimal import Decimal
with open(sys.argv[1]) as f:
    data = json.load(f)
streams = data['streams']
assert len(streams) == 2 and streams[0]['codec_name'] == 'h264' and streams[1]['codec_name'] == 'aac'
v = streams[0]
with open(sys.argv[2], 'w') as f:
    f.write(f"{sys.argv[3]} {v['width']} {v['height']}\n")
    for p in data['packets']:
        pts = int(Decimal(p['pts_time']) * 1_000_000)
        flags = 1 if 'K' in p['flags'] else 0
        f.write(f"{p['stream_index']} {pts} {p['size']} {flags} {p['pos']}\n")
PY
  args+=("$1" "$work/packets.txt")
fi
"$java_bin" -cp "$work/classes" io.github.jackotom.hongguo.android.PublicPlaybackVerifierTest "${args[@]}"
