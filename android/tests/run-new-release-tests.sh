#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-new-release.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/io/github/jackotom/hongguo/android"
SOURCE="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
cat > "$TMP/io/github/jackotom/hongguo/android/OfficialApiClient.java" <<'STUB'
package io.github.jackotom.hongguo.android;
import java.util.ArrayDeque;
import java.util.Queue;
import org.json.JSONObject;
final class OfficialApiClient implements AutoCloseable {
    static final class HTTPError extends java.io.IOException { final int status,retryAfter; HTTPError(int status,int retry){this.status=status;this.retryAfter=retry;} }
    interface Cloud { JSONObject call(String path,String method,JSONObject body) throws Exception; }
    private final Cloud cloud; final ArrayDeque<Runnable> closes = new ArrayDeque<>();
    OfficialApiClient(Cloud cloud){this.cloud=cloud;}
    static JSONObject newSession(){return new JSONObject();}
    void registerDevice() throws Exception { }
    void deadlineNanos(long value) { }
    JSONObject cloud(String path,String method,JSONObject query,JSONObject body,boolean gzip) throws Exception { return cloud.call(path,method,body); }
    public void close() { }
}
STUB
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
  "$TMP/io/github/jackotom/hongguo/android/OfficialApiClient.java" "$SOURCE/NewReleaseCatalog.java" \
  "$ROOT/android/tests/NewReleaseCatalogTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.NewReleaseCatalogTest
