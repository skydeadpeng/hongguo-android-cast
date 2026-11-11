#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SOURCE="${1:-$ROOT/app/src/main/java/io/github/jackotom/hongguo/android}"
TMP="$(mktemp -d -t hongguo-cache-recovery.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$TMP/classes" "$SOURCE/PlaybackCachePolicy.java" "$ROOT/tests/CacheForegroundRecoveryTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.CacheForegroundRecoveryTest
python3 - "$SOURCE/PlayerActivity.java" "$TMP/RecoveryBudget.java" "$TMP/PlayerRecoveryHarness.java" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text();start=s.index('static final class RecoveryBudget');opening=s.index('{',start);depth=1;end=opening+1
while depth:
 if s[end]=='{':depth+=1
 elif s[end]=='}':depth-=1
 end+=1
code=s[start:end].replace('static final class','final class',1)
Path(sys.argv[2]).write_text('package io.github.jackotom.hongguo.android;\n'+code+'\n')
assert 'recoveryBudget.take(token, offline, currentManual, unsupported)' in s
assert 'if (recovering) recoveryBudget.carry(previousToken, token)' in s
assert 'cache.invalidate(' in s and 'PlaybackSources.resolvePlayback(' in s
start=s.index('private boolean recoverMedia(');opening=s.index('{',start);depth=1;end=opening+1
while depth:
 if s[end]=='{':depth+=1
 elif s[end]=='}':depth-=1
 end+=1
method=s[start:end].replace('private boolean recoverMedia','boolean recoverMedia',1)
start=s.index('private boolean validPlaybackCallback(');opening=s.index('{',start);end=s.index('}',opening)+1
guard=s[start:end].replace('private boolean validPlaybackCallback','boolean validPlaybackCallback',1)
Path(sys.argv[3]).write_text('package io.github.jackotom.hongguo.android;\nfinal class PlayerRecoveryHarness {\n final RecoveryBudget recoveryBudget=new RecoveryBudget();\n boolean offline,currentManual,currentWeb,userPaused,destroyed,castWasActive; int current,lastGoodPosition,resumeAt,generation=7;\n float actualSpeed=Float.NaN,requestedSpeed=1; String lastMediaError,currentKind;\n MediaPlayer mediaPlayer=new MediaPlayer(); VideoView video=new VideoView();\n int calls,selected,position; boolean paused,recovering,web; float carriedSpeed;\n static final class MediaPlayer {static final int MEDIA_ERROR_UNSUPPORTED=-1010;float rate=Float.NaN;}\n static final class PlayerOptions {static float observedSpeed(MediaPlayer player){return player.rate;}}\n static final class VideoView {int position;boolean throwsPosition;int getCurrentPosition(){if(throwsPosition)throw new IllegalStateException();return position;}}\n boolean casting(){return false;}\n void renderStatus(){}\n void loadAtInternal(int selected,String url,int position,boolean paused,boolean recovering,boolean web){calls++;this.selected=selected;this.position=position;this.paused=paused;this.recovering=recovering;this.web=web;carriedSpeed=requestedSpeed;}\n'+guard+'\n'+method+'\n}\n')
print('PASS STATIC Player recovery gate/invalidation/fresh-source wiring (not device evidence)')
PY
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$TMP/budget" "$TMP/RecoveryBudget.java" "$ROOT/tests/PlayerRecoveryBudgetTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/budget" io.github.jackotom.hongguo.android.PlayerRecoveryBudgetTest

"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$TMP/state" "$TMP/RecoveryBudget.java" "$TMP/PlayerRecoveryHarness.java" "$ROOT/tests/PlayerRecoveryStateTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/state" io.github.jackotom.hongguo.android.PlayerRecoveryStateTest
