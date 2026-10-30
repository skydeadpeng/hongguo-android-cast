#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-danmaku.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
mkdir -p "$TMP/android/content" "$TMP/android/os" "$TMP/io/github/jackotom/hongguo/android"
cat > "$TMP/android/content/Context.java" <<'JAVA'
package android.content;
public abstract class Context {public static final int MODE_PRIVATE=0;public abstract Context getApplicationContext();public abstract SharedPreferences getSharedPreferences(String name,int mode);}
JAVA
cat > "$TMP/android/content/SharedPreferences.java" <<'JAVA'
package android.content;
public interface SharedPreferences {String getString(String key,String fallback);Editor edit();interface Editor {Editor putString(String key,String value);boolean commit();}}
JAVA
cat > "$TMP/android/os/Handler.java" <<'JAVA'
package android.os;
public final class Handler {private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> queue=new java.util.concurrent.ConcurrentLinkedQueue<>();public boolean post(Runnable r){queue.add(r);return true;}public int drain(){int n=0;for(Runnable r;(r=queue.poll())!=null;){r.run();n++;}return n;}}
JAVA
cat > "$TMP/io/github/jackotom/hongguo/android/AppTransport.java" <<'JAVA'
package io.github.jackotom.hongguo.android;
import java.net.Socket;
final class AppTransport {static final class SocketGuard implements AutoCloseable {public void close(){}}static SocketGuard guardSocket(Socket s,long d){return new SocketGuard();}}
JAVA
cat > "$TMP/io/github/jackotom/hongguo/android/NetworkGuard.java" <<'JAVA'
package io.github.jackotom.hongguo.android;
import java.net.*;import java.io.*;import java.util.Map;
final class NetworkGuard {static InetAddress[] publicAddresses(URI u,long d){throw new AssertionError("offline tests must not open network");}static void streamBody(InputStream in,OutputStream out,Map<String,String> headers,int max,long deadline){throw new AssertionError("offline tests must not read network");}}
JAVA
cat > "$TMP/io/github/jackotom/hongguo/android/DanmakuOverlay.java" <<'JAVA'
package io.github.jackotom.hongguo.android;
import java.util.*;
final class DanmakuOverlay {boolean enabled;List<DanmakuClient.Item> rows=Collections.emptyList();int clears,seeks;void clear(){clears++;rows=Collections.emptyList();}void enabled(boolean x){enabled=x;if(!x)clear();}void pulse(){}void seek(long x){seeks++;}void items(List<DanmakuClient.Item> input,long p){rows=input;}}
JAVA
SRC="${DANMAKU_SRC:-$ROOT/app/src/main/java/io/github/jackotom/hongguo/android}"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$JSON_JAR" -d "$TMP/classes" \
 "$TMP/android/content/Context.java" "$TMP/android/content/SharedPreferences.java" "$TMP/android/os/Handler.java" \
 "$TMP/io/github/jackotom/hongguo/android/"*.java \
 "$SRC/DanmakuSigner.java" "$SRC/DanmakuClient.java" "$SRC/DanmakuTimeline.java" "$SRC/DanmakuController.java" \
 "$ROOT/tests/DanmakuTest.java" "$ROOT/tests/DanmakuControllerTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.DanmakuTest "$ROOT/tests/danmaku-sign-vectors.json"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.DanmakuControllerTest
