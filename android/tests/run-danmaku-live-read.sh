#!/bin/bash
# Explicit anonymous read-only probe. Not part of offline tests; prints counts, never comments/URLs/identifiers.
set -euo pipefail
[[ $# == 2 && "$1" =~ ^[1-9][0-9]{0,29}$ && "$2" =~ ^[1-9][0-9]{0,29}$ ]] || { echo 'USAGE: run-danmaku-live-read.sh SERIES_ID VID' >&2;exit 64; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-danmaku-live.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
mkdir -p "$TMP/android/content" "$TMP/io/github/jackotom/hongguo/android"
cat > "$TMP/android/content/Context.java" <<'JAVA'
package android.content;public abstract class Context {public static final int MODE_PRIVATE=0;public abstract Context getApplicationContext();public abstract SharedPreferences getSharedPreferences(String name,int mode);}
JAVA
cat > "$TMP/android/content/SharedPreferences.java" <<'JAVA'
package android.content;public interface SharedPreferences {String getString(String key,String fallback);Editor edit();interface Editor {Editor putString(String key,String value);boolean commit();}}
JAVA
cat > "$TMP/io/github/jackotom/hongguo/android/DanmakuAnonymousProbe.java" <<'JAVA'
package io.github.jackotom.hongguo.android;
import android.content.*;import java.util.*;
public final class DanmakuAnonymousProbe {
 public static void main(String[]args)throws Exception{Map<String,String> prefs=new HashMap<>();Context context=new Context(){public Context getApplicationContext(){return this;}public SharedPreferences getSharedPreferences(String n,int mode){return new SharedPreferences(){public String getString(String key,String fallback){return prefs.getOrDefault(key,fallback);}public Editor edit(){return new Editor(){public Editor putString(String key,String value){prefs.put(key,value);return this;}public boolean commit(){return true;}};}};}};
 long before=System.nanoTime();try{DanmakuClient.Result result=new DanmakuClient(context).window(args[0],args[1],0,120000);System.out.println("ANONYMOUS_DANMAKU_READ success=true count="+result.items.size()+" partial="+result.partial+" millis="+((System.nanoTime()-before)/1000000)+" commentBodiesLogged=false accountWrites=0 mediaRequests=0");}catch(Exception error){System.out.println("ANONYMOUS_DANMAKU_READ success=false error="+error.getClass().getSimpleName()+" message="+error.getMessage()+" millis="+((System.nanoTime()-before)/1000000)+" accountWrites=0 mediaRequests=0");System.exit(1);}
 }
}
JAVA
SRC="$ROOT/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/android/content/"*.java "$TMP/io/github/jackotom/hongguo/android/"*.java "$SRC/DanmakuSigner.java" "$SRC/DanmakuClient.java" "$SRC/NetworkGuard.java" "$SRC/AppTransport.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.DanmakuAnonymousProbe "$1" "$2"
