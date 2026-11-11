#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --source-root) SOURCE_ROOT="$2"; shift 2;;
        *) echo 'USAGE run-mobile-retry-state-tests.sh [--source-root PROJECT]' >&2; exit 64;;
    esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-mobile-retry-state.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/sdk35" "$TMP/classes" "$TMP/io/github/jackotom/hongguo/android"
printf '%s\n' 'package io.github.jackotom.hongguo.android; public final class BuildConfig {public static final boolean IS_TV=false,DEBUG=true;public static final String VERSION_NAME="fixture";}' > "$TMP/io/github/jackotom/hongguo/android/BuildConfig.java"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/sdk35" "$TMP/io/github/jackotom/hongguo/android/BuildConfig.java" "$SRC/"*.java
echo 'PASS MobileRetryState SDK35 allProductionCompile=true deviceUntouched=true'
python3 - "$TEST_ROOT" "$SRC/PlayerActivity.java" "$TMP" <<'PY'
from pathlib import Path
import runpy, sys
tests, activity, temp = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
generated = temp/'AdaptiveActivityHarness.java'
sys.argv = [str(tests/'adaptive-activity-harness.py'), str(activity), str(generated)]
binding = runpy.run_path(str(tests/'adaptive-activity-harness.py'))
assert binding['modern'], 'retry-state gate requires native AdaptiveQuality implementation'
terminal = binding['blocks'](r'^    (?:private|public|protected) [^\n{]*\bplaybackError\([^;\n]*\)\s*\{')
assert len(terminal)==1, 'missing real playbackError handler'
# A sole counter probe after the real stale/destroyed/finishing guard observes terminal entry.
real_terminal = terminal[0].replace('        cancelQualitySwitch();', '        ++errors; // test counter probe, original terminal body follows\n        cancelQualitySwitch();', 1)
assert '++errors;' in real_terminal, 'terminal instrumentation anchor missing'
text = generated.read_text()
old = 'void playbackError(int token,String message){if(token==generation&&!destroyed){errors++;status.setText(message);}}'
assert old in text, 'legacy harness terminal sink anchor changed'
text = text.replace(old, real_terminal)
text = text.replace(' Intent getIntent()', ' boolean isFinishing(){return false;}void exitPlayer(){}\n Intent getIntent()')
generated.write_text(text)
doubles = (tests/'AdaptiveActivityDoubles.java').read_text()
doubles = doubles.replace('class Label {String text="";', 'class Label {String getText(){return text;}String text="";')
doubles = doubles.replace('interface Choice {void click(AlertDialog d,int which);}', 'interface Choice {void click(AlertDialog d,int which);}interface Cancel {void cancel(AlertDialog d);}')
doubles = doubles.replace('Builder setTitle(String title){return this;}', 'Builder setTitle(String title){return this;}Builder setMessage(String message){return this;}Builder setPositiveButton(String text,Choice c){return this;}Builder setNeutralButton(String text,Choice c){return this;}Builder setOnCancelListener(Cancel c){return this;}')
(temp/'RetryActivityDoubles.java').write_text(doubles)
print('MOBILE_RETRY_BINDING actualLoadAt=true actualStageProgress=true actualBindPlayback=true actualPlaybackError=true counterProbe=afterRealGuard oldFixturesUnchanged=true')
PY
EXTRA=();if [[ -f "$SRC/PlaybackPlaylist.java" ]];then EXTRA+=("$SRC/PlaybackPlaylist.java");fi
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$JSON_JAR" -d "$TMP/classes" "${EXTRA[@]}" \
    "$TMP/RetryActivityDoubles.java" "$TMP/AdaptiveActivityHarness.java" \
    "$SRC/AdaptiveQuality.java" "$SRC/MediaQuality.java" "$TEST_ROOT/MobileRetryStateTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.MobileRetryStateTest
