#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-complete-source.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/android/content"
cat > "$TMP/android/content/Context.java" <<'JAVA'
package android.content;
import java.io.File;
public final class Context {private final File cache;public Context(File cache){this.cache=cache;}public Context getApplicationContext(){return this;}public File getCacheDir(){return cache;}}
JAVA
cat > "$TMP/Stubs.java" <<'JAVA'
package io.github.jackotom.hongguo.android;
import java.io.File;import java.nio.file.Files;import org.json.JSONObject;
final class PlaybackSources {static void download(String u,File f,long m)throws Exception{Files.write(f.toPath(),new byte[]{2,3});}static void download(String u,File f,long m,String q)throws Exception{download(u,f,m);}}
final class PlaybackPlaylist {static JSONObject owner(JSONObject fallback,JSONObject episode){return fallback;}}
JAVA
S="$ROOT/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/android/content/Context.java" "$TMP/Stubs.java" "$S/MediaQuality.java" "$S/PlaybackCachePolicy.java" "$S/PlaybackCache.java" "$ROOT/tests/CompleteSourceCacheTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.CompleteSourceCacheTest
