#!/bin/bash
set -euo pipefail
OWN="$(cd "$(dirname "$0")/../.." && pwd)";ROOT="$OWN"
if [[ "${1:-}" == --source-root ]];then ROOT="$2";shift 2;fi
[[ $# == 0 ]] || exit 2
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-playback-cap-cache.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/android/content"
python3 - "$OWN/android/tests/run-native-media-contract-tests.sh" "$TMP/OfflineMediaStubs.java" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text();m='cat > "$TMP/OfflineMediaStubs.java" <<\'STUB\'\n';i=s.index(m)+len(m);s=s[i:s.index('\nSTUB',i)]
s=s.replace('static int downloads,heads;', 'static String blocked;static java.util.concurrent.CountDownLatch entered,allow;static int downloads,heads;')
s=s.replace('downloads++;lastReferer=', 'if(url.equals(blocked)){entered.countDown();if(!allow.await(8,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("fixture unblock timeout");} downloads++;lastReferer=')
s=s.replace('static final java.util.List<String> attempted=new java.util.ArrayList<>();','static final java.util.List<String> attempted=java.util.Collections.synchronizedList(new java.util.ArrayList<>());')
s=s.replace('static final java.util.List<Boolean> cleanAtStart=new java.util.ArrayList<>();','static final java.util.List<Boolean> cleanAtStart=java.util.Collections.synchronizedList(new java.util.ArrayList<>());')
s=s.replace('outcomes=new java.util.HashMap<>(),agents=new java.util.HashMap<>(),referers=new java.util.HashMap<>()','outcomes=new java.util.concurrent.ConcurrentHashMap<>(),agents=new java.util.concurrent.ConcurrentHashMap<>(),referers=new java.util.concurrent.ConcurrentHashMap<>()')
s+='\nfinal class PlaybackPlaylist {static org.json.JSONObject owner(org.json.JSONObject fallback,org.json.JSONObject episode){return fallback;}}\n'
Path(sys.argv[2]).write_text(s)
PY
cat > "$TMP/android/content/Context.java" <<'JAVA'
package android.content;public class Context{private final java.io.File root;public Context(java.io.File root){this.root=root;}public Context getApplicationContext(){return this;}public java.io.File getCacheDir(){return root;}}
JAVA
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/android/content/Context.java" "$TMP/OfflineMediaStubs.java" "$SRC/MediaQuality.java" "$SRC/SpadeDecoder.java" "$SRC/DanmakuSigner.java" "$SRC/AppMediaSource.java" "$SRC/PlaybackSources.java" "$SRC/PlaybackCachePolicy.java" "$SRC/PlaybackCache.java" "$OWN/android/tests/PlaybackResolutionCacheTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.PlaybackResolutionCacheTest
