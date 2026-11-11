#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE_ROOT="$ROOT"
if [[ "${1:-}" == '--source-root' ]]; then SOURCE_ROOT="$2"; shift 2; fi
[[ "$#" == 0 ]] || { echo 'USAGE run-public-socket-tests.sh [--source-root PROJECT]' >&2; exit 2; }
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TEMP="$(mktemp -d -t hongguo-public-socket.XXXXXX)";trap 'rm -rf "$TEMP"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$TEMP/classes" "$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/NetworkGuard.java" "$ROOT/android/tests/NetworkGuardAddressTest.java"
"$JAVA_HOME/bin/java" -cp "$TEMP/classes" io.github.jackotom.hongguo.android.NetworkGuardAddressTest
