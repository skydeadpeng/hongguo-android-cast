#!/bin/bash
set -euo pipefail
OWN="$(cd "$(dirname "$0")/../.." && pwd)";ROOT="$OWN"
if [[ "${1:-}" == --source-root ]];then ROOT="$2";shift 2;fi
[[ $# == 0 ]] || { echo 'Usage --source-root PROJECT' >&2;exit 2; }
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-search-budget.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/android/content" "$TMP/io/github/jackotom/hongguo/android"
cat > "$TMP/android/content/Context.java" <<'JAVA'
package android.content; public abstract class Context{public static final int MODE_PRIVATE=0;public abstract SharedPreferences getSharedPreferences(String name,int mode);}
JAVA
cat > "$TMP/android/content/SharedPreferences.java" <<'JAVA'
package android.content;public interface SharedPreferences{String getString(String key,String fallback);Editor edit();interface Editor{Editor putString(String key,String value);boolean commit();}}
JAVA
cat > "$TMP/io/github/jackotom/hongguo/android/AppTransport.java" <<'JAVA'
package io.github.jackotom.hongguo.android;import java.util.*;import org.json.*;final class AppTransport{static JSONObject searchRequest(String u,String m,Map<String,String>h,byte[]b,long d){throw new AssertionError("No actual network permitted in budget fixture");}}
JAVA
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP"/android/content/*.java "$TMP"/io/github/jackotom/hongguo/android/*.java "$SRC/SearchClient.java" "$SRC/SearchProtocol.java" "$OWN/android/tests/SearchBudgetTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.SearchBudgetTest
