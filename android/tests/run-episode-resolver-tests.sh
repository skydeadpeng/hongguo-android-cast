#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE_ROOT="$ROOT/.."
if [ "${1:-}" = "--source-root" ] && [ "$#" -eq 2 ]; then
  SOURCE_ROOT="$(cd "$2" && pwd)"
elif [ "$#" -ne 0 ]; then
  echo 'usage: run-episode-resolver-tests.sh [--source-root SOURCE_ROOT]'; exit 2
fi
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# The Android-compatible org.json jar is test-only; the APK uses Android's built-in org.json.
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
if [ ! -f "$JSON_JAR" ]; then
  JSON_JAR="$(find "$HOME/.gradle/caches" "$HOME/.m2/repository/org/json/json" -name 'json-*.jar' ! -name '*sources*' ! -name '*javadoc*' -print 2>/dev/null | head -1)"
fi
if [ ! -f "$JSON_JAR" ]; then echo 'TEST_DEPENDENCY_MISSING set JSON_JAR to an org.json runtime jar'; exit 2; fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
# JDK compilation does not diagnose flags rejected by Android's ICU regex implementation.
if grep -nE 'UNICODE_CHARACTER_CLASS|\(\?[^)]*U' "$SRC/EpisodeResolver.java"; then
  echo 'FAIL EpisodeResolver contains an Android-unsupported Unicode regex flag'
  exit 1
fi
echo 'PASS EpisodeResolver Android regex compatibility guard'
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/parser" "$SRC/NetworkGuard.java" "$SRC/EpisodeResolver.java" "$ROOT/tests/EpisodeResolverTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/parser:$JSON_JAR" io.github.jackotom.hongguo.android.EpisodeResolverTest
cat > "$TMP/NetworkGuard.java" <<'STUB'
package io.github.jackotom.hongguo.android;
final class NetworkGuard {
    static final class Reply { final String body, url; final int status; Reply(String body, int status, String url) { this.body = body; this.status = status; this.url = url; } }
    // A queue entry is a Reply or a thrown exception (a synthetic transport timeout).
    static Object[] replies; static int totalRequests;
    static final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.List<String> requested = new java.util.concurrent.CopyOnWriteArrayList<>();
    static void reset(Object... next) { replies = next; requests.set(0); requested.clear(); }
    static int requests() { return requests.get(); }
    static Reply get(String url) throws Exception { return get(url, 0, 0L); }
    static Reply get(String url, int timeoutMs, long deadlineMs) throws Exception {
        requested.add(url); totalRequests++;
        int at = requests.getAndIncrement();
        if (at >= replies.length) throw new AssertionError("unexpected extra GET " + url);
        Object next = replies[at];
        if (next instanceof RuntimeException) throw (RuntimeException) next;
        if (next instanceof Exception) throw (Exception) next;
        return (Reply) next;
    }
}
STUB
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/transport" "$TMP/NetworkGuard.java" "$SRC/EpisodeResolver.java" "$ROOT/tests/EpisodeResolverTransportTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/transport:$JSON_JAR" io.github.jackotom.hongguo.android.EpisodeResolverTransportTest
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/android-sdk35" "$SRC/NetworkGuard.java" "$SRC/EpisodeResolver.java"
echo 'PASS EpisodeResolver SDK35 compile release=17'
