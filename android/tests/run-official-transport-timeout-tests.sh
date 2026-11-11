#!/bin/bash
set -euo pipefail
OWN="$(cd "$(dirname "$0")/../.." && pwd)"; ROOT="$OWN"
if [[ "${1:-}" == --source-root ]]; then ROOT="$2"; shift 2; fi
[[ $# == 0 ]] || exit 2
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d -t hongguo-official-timeout.XXXXXX)"; trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$TMP/classes" \
 "$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/OfficialTransport.java" \
 "$OWN/android/tests/OfficialTransportTimeoutTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.OfficialTransportTimeoutTest
