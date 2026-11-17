#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE_ROOT="$ROOT/.."
if [ "${1:-}" = "--source-root" ] && [ "$#" -eq 2 ]; then
  SOURCE_ROOT="$(cd "$2" && pwd)"
elif [ "$#" -ne 0 ]; then
  echo 'usage: run-guest-playback-resolver-tests.sh [--source-root SOURCE_ROOT]'; exit 2
fi
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
SDK_JAR="${SDK_JAR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platforms/android-35/android.jar}"
if [ ! -f "$JSON_JAR" ]; then echo 'TEST_DEPENDENCY_MISSING set JSON_JAR to an Android-compatible org.json runtime jar'; exit 2; fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TEST="$ROOT/tests/GuestPlaybackResolverTest.java"
cat > "$TMP/NetworkGuard.java" <<'STUB'
package io.github.jackotom.hongguo.android;
final class NetworkGuard {
    static final class Reply {
        final String body, url; final int status;
        Reply(String body, int status, String url) { this.body=body; this.status=status; this.url=url; }
    }
    // Declared only so the shared test source also compiles in real-NetworkGuard mode.
    static volatile java.util.function.Function<String, java.net.InetAddress[]> dohProbe;
    static Reply get(String url, int timeoutMs, long deadlineMs) throws Exception {
        return GuestPlaybackResolverTest.FixtureTransport.get(url, timeoutMs, deadlineMs);
    }
    static Reply guestPlaybackPost(String itemId, long deadline) throws Exception {
        return GuestPlaybackResolverTest.FixtureTransport.post(itemId, deadline);
    }
}
STUB
# Host-JVM Java 17 source/target check, deliberately NOT an SDK35 or full JDK17 claim.
JAVAC=("$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -Xlint:-options -cp "$JSON_JAR:$SDK_JAR")
# This TLS contract exercises API35; SDK android.jar is a compile stub with SDK_INT=0.
HOST_BUILD="$ROOT/tests/host-platform/android/os/Build.java"
"${JAVAC[@]}" -d "$TMP/resolver" "$HOST_BUILD" "$TMP/NetworkGuard.java" "$SRC/LegacyText.java" "$SRC/EpisodeResolver.java" "$TEST"
"$JAVA_HOME/bin/java" -cp "$TMP/resolver:$JSON_JAR:$SDK_JAR" io.github.jackotom.hongguo.android.GuestPlaybackResolverTest
"${JAVAC[@]}" -d "$TMP/transport" "$HOST_BUILD" "$SRC/NetworkGuard.java" "$SRC/LegacyText.java" "$SRC/EpisodeResolver.java" "$TEST"
# Both system DNS and DoH are replaced. Every TCP/TLS byte is handled by the test's
# SocketImpl/SSLContext doubles; no listening socket or upstream request is used.
printf '93.184.216.34 hongguoduanju.com\n' > "$TMP/hosts"
"$JAVA_HOME/bin/java" -Djdk.net.hosts.file="$TMP/hosts" -cp "$TMP/transport:$JSON_JAR:$SDK_JAR" io.github.jackotom.hongguo.android.GuestPlaybackResolverTest --network-guard
echo 'PASS guest playback production resolver/transport contracts (offline host JVM, Java 17 source/target)'
