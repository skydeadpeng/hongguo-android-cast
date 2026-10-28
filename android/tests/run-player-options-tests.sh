#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-player-options.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
mkdir -p "$TMP/android/content" "$TMP/android/media" "$TMP/io/github/jackotom/hongguo/android"
cat > "$TMP/android/content/Context.java" <<'STUB'
package android.content;
public abstract class Context {public static final int MODE_PRIVATE=0;public abstract Context getApplicationContext();public abstract SharedPreferences getSharedPreferences(String name,int mode);}
STUB
cat > "$TMP/android/content/SharedPreferences.java" <<'STUB'
package android.content;
public interface SharedPreferences {String getString(String key,String fallback);Editor edit();interface Editor {Editor putString(String key,String value);boolean commit();void apply();}}
STUB
cat > "$TMP/android/media/PlaybackParams.java" <<'STUB'
package android.media;
public final class PlaybackParams {private float speed=1;public PlaybackParams allowDefaults(){return this;}public PlaybackParams setSpeed(float speed){this.speed=speed;return this;}public float getSpeed(){return speed;}}
STUB
cat > "$TMP/android/media/MediaPlayer.java" <<'STUB'
package android.media;
public class MediaPlayer {public float rate=1,rejectAbove=Float.POSITIVE_INFINITY,ignoreAbove=Float.POSITIVE_INFINITY;public boolean playing,readUnsupported,pausedReadsZero,ignoreAllSets;public int setCalls;public PlaybackParams getPlaybackParams(){if(readUnsupported)throw new UnsupportedOperationException();return new PlaybackParams().setSpeed(pausedReadsZero&&!playing?0:rate);}public void setPlaybackParams(PlaybackParams params){setCalls++;if(ignoreAllSets)return;if(params.getSpeed()>rejectAbove)throw new IllegalArgumentException();if(params.getSpeed()<=ignoreAbove)rate=params.getSpeed();playing=true;}public boolean isPlaying(){return playing;}public void start(){playing=true;}public void pause(){playing=false;}}
STUB
SRC="$ROOT/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
 "$TMP/android/content/Context.java" "$TMP/android/content/SharedPreferences.java" "$TMP/android/media/PlaybackParams.java" "$TMP/android/media/MediaPlayer.java" "$SRC/PlayerOptions.java" "$ROOT/tests/PlayerOptionsTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.PlayerOptionsTest
cat > "$TMP/io/github/jackotom/hongguo/android/BuildConfig.java" <<'STUB'
package io.github.jackotom.hongguo.android;public final class BuildConfig {public static final boolean IS_TV=false,NATIVE_UI=false,DEBUG=true;public static final String VERSION_NAME="0.4.0";}
STUB
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/sdk35" "$TMP/io/github/jackotom/hongguo/android/BuildConfig.java" "$SRC/"*.java
echo 'PASS PlayerOptions SDK35 allProductionCompile=true deviceUntouched=true'
