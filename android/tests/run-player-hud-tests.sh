#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE_ROOT="$ROOT"
ACTIVITY=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-root) SOURCE_ROOT="$2"; shift 2 ;;
    --activity-source) ACTIVITY="$2"; shift 2 ;;
    *) echo 'usage: run-player-hud-tests.sh [--source-root PROJECT] [--activity-source FILE]' >&2; exit 64 ;;
  esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
if [[ -z "$ACTIVITY" ]]; then ACTIVITY="$SRC/PlayerActivity.java"; else SRC="$(cd "$(dirname "$ACTIVITY")" && pwd)"; fi
TMP="$(mktemp -d -t hongguo-hud-tests.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
python3 - "$ACTIVITY" "$TMP/PlayerHudHarness.java" <<'PY'
from pathlib import Path
import re,sys
source=Path(sys.argv[1]).read_text()
def method(name,optional=False):
    m=re.search(r'(?m)^    (?:@Override )?(?:private|public|protected) [^\n{]*\b'+re.escape(name)+r'\([^;\n]*\)\s*\{',source)
    if not m:
        if optional:return ''
        raise SystemExit('Missing actual Activity method: '+name)
    start=m.start(); brace=source.index('{',m.start()); depth=0; quote=None; escaped=False; comment=None; i=brace
    while i<len(source):
        ch=source[i]; pair=source[i:i+2]
        if comment=='line':
            if ch=='\n':comment=None
        elif comment=='block':
            if pair=='*/':comment=None;i+=1
        elif quote:
            if escaped:escaped=False
            elif ch=='\\':escaped=True
            elif ch==quote:quote=None
        elif pair=='//':comment='line';i+=1
        elif pair=='/*':comment='block';i+=1
        elif ch in ('"',"'"):quote=ch
        elif ch=='{':depth+=1
        elif ch=='}':
            depth-=1
            if depth==0:return source[start:i+1].replace('private ', '',1)
        i+=1
    raise SystemExit('Unclosed real Activity method: '+name)
modern='controlsHud = new PlayerHud(' in source
methods='\n'.join(method(name) for name in ['installVideoTouch','showControls','exitPlayer','dispatchKeyEvent','onKeyDown','onPause','onResume','onDestroy'])
methods+='\n'+ '\n'.join(method(name,True) for name in ['canHideControls','updateControlsState','showPlayerDialog','dispatchTouchEvent','cancelPlaybackWatchdogs','validPlaybackCallback','armPreparationTimeout','updateBufferingWatchdog'])
setup=''
if modern:
    start=source.index('controlsHud = new PlayerHud(')
    end=source.index('        video.requestFocus(); showControls();',start)
    setup=source[start:end]
info=method('bindPlayback')
info=info[info.index('{')+1:info.index('        view.setOnCompletionListener')]
text='''package io.github.jackotom.hongguo.android;
import java.util.*;
// Episode metadata is not used by the HUD info callback; playback ownership is tested separately.
final class JSONObject {}
final class KeyEvent {
 static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_UNKNOWN=0,KEYCODE_BACK=4,KEYCODE_DPAD_UP=19,KEYCODE_DPAD_DOWN=20,KEYCODE_DPAD_LEFT=21,KEYCODE_DPAD_RIGHT=22,KEYCODE_DPAD_CENTER=23,KEYCODE_ENTER=66,KEYCODE_MEDIA_PLAY_PAUSE=85,KEYCODE_MEDIA_NEXT=87,KEYCODE_MEDIA_PREVIOUS=88,KEYCODE_MEDIA_REWIND=89,KEYCODE_MEDIA_FAST_FORWARD=90,KEYCODE_MEDIA_PLAY=126,KEYCODE_MEDIA_PAUSE=127;
 final int action,key; KeyEvent(int action,int key){this.action=action;this.key=key;} int getAction(){return action;}int getKeyCode(){return key;}
}
final class MotionEvent {
 static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_CANCEL=3;final int action;MotionEvent(int action){this.action=action;}int getAction(){return action;}int getActionMasked(){return action;}
}
class View {
 static final int VISIBLE=0,GONE=8;static View focused; int visibility=VISIBLE;boolean enabled=true;
 void setVisibility(int value){visibility=value;}int getVisibility(){return visibility;}boolean requestFocus(){focused=this;return true;}boolean hasFocus(){return focused==this;}boolean isEnabled(){return enabled;}
}
final class VideoView extends View {
 interface Touch {boolean touch(View view,MotionEvent event);} interface Info {boolean info(MediaPlayer player,int what,int extra);}
 boolean playing;int stops;Touch touch;Info info;
 void setOnTouchListener(Touch listener){touch=listener;}void setOnInfoListener(Info listener){info=listener;}void info(int code){if(info!=null)info.info(new MediaPlayer(),code,0);}
 boolean isPlaying(){return playing;}void start(){playing=true;}void pause(){playing=false;}void stopPlayback(){playing=false;stops++;}void seekTo(int value){}int getDuration(){return 100000;}int getCurrentPosition(){return 50000;}
}
final class MediaPlayer {static final int MEDIA_INFO_BUFFERING_START=701,MEDIA_INFO_BUFFERING_END=702;}
final class AlertDialog {interface Dismiss {void dismiss(AlertDialog dialog);}boolean shown;Dismiss listener;void setOnDismissListener(Dismiss value){listener=value;}void show(){shown=true;}void dismiss(){shown=false;if(listener!=null){Dismiss old=listener;listener=null;old.dismiss(this);}}}
final class MainLoop {
 long now;final Map<Runnable,Long> jobs=new HashMap<>();
 void removeCallbacks(Runnable r){jobs.remove(r);}void postDelayed(Runnable r,long delay){jobs.put(r,now+delay);}void removeCallbacksAndMessages(Object token){jobs.clear();}
 void advance(long delta){long target=now+delta;while(!jobs.isEmpty()){Map.Entry<Runnable,Long> item=Collections.min(jobs.entrySet(),Map.Entry.comparingByValue());if(item.getValue()>target)break;now=item.getValue();Runnable r=item.getKey();jobs.remove(r);r.run();}now=target;}
}
final class Worker {boolean shutdown;void shutdownNow(){shutdown=true;}}
class HudActivityBase {
 int finishes,saves,baseKeyCalls,baseTouchCalls,baseSawTop;boolean finishing;
 boolean isFinishing(){return finishing;}void finish(){finishes++;finishing=true;}
 public boolean dispatchKeyEvent(KeyEvent event){baseKeyCalls++;return event.getAction()==KeyEvent.ACTION_DOWN&&onKeyDown(event.getKeyCode(),event);}
 public boolean onKeyDown(int key,KeyEvent event){return false;}
 public boolean dispatchTouchEvent(MotionEvent event){baseTouchCalls++;PlayerHudHarness p=(PlayerHudHarness)this;baseSawTop=p.top.visibility;return p.video.touch!=null&&p.video.touch.touch(p.video,event);}
 public void onBackPressed(){}protected void onPause(){}protected void onResume(){}protected void onDestroy(){}
}
final class PlayerHudHarness extends HudActivityBase {
 final MainLoop main=new MainLoop();final Worker resolver=new Worker(),cacheWorker=new Worker();
 final View top=new View(),bottom=new View(),toggle=new View(),back=new View();VideoView video=new VideoView();
 boolean active,prepared,completed,userPaused,buffering,dragging,touchingControls,destroyed,decoderPreparing,castWasActive;Runnable playbackPrepareTimeout,bufferingTimeout;int generation,hudDialogs,wakeKey=KeyEvent.KEYCODE_UNKNOWN,toggleCalls,seekCalls;Object qualitySwitch;
 // Independent danmaku and install-lock lifecycle boundaries.
 boolean registeredPlayer;static final java.util.concurrent.atomic.AtomicInteger LIVE_PLAYERS=new java.util.concurrent.atomic.AtomicInteger();
 DanmakuLayer danmaku;static final class DanmakuLayer{void suspend(){}void close(){}}
 HUD_FIELD
 PlayerHudHarness(){installVideoTouch(video);HUD_SETUP video.requestFocus();}
 void sync(){HUD_SYNC}
 void openDialog(AlertDialog dialog){HUD_DIALOG}
 void attachInfo(VideoView view,int token){HUD_INFO}
 // Local/TV HUD regression boundary; actual cast lifecycle has its own suite.
 boolean casting(){return false;}boolean castCommand(String command,long value){return false;}
 boolean renderCastControls(){return false;}void detachCast(){}
 final JSONObject target=new JSONObject();JSONObject owner(JSONObject value){return value;}void closeLocalWindow(){}
 // Source recovery is outside the HUD boundary, separately exercised by PlaybackFailureActivityTest.
 void failCurrentMedia(int token,String reason,boolean unsupported){}
 void saveProgress(){saves++;}void releaseCurrent(){}void cancelQualitySwitch(){}void discardPendingMedia(){}
 void togglePlayback(){toggleCalls++;video.playing=!video.playing;userPaused=!video.playing;sync();}void move(int delta){}void updateButtons(){sync();}void seekBy(int delta){seekCalls++;}
 ACTIVITY_METHODS
}
'''
text=text.replace('HUD_FIELD','PlayerHud controlsHud;' if modern else '')
text=text.replace('HUD_SETUP',setup)
text=text.replace('HUD_SYNC','updateControlsState();' if modern else '')
text=text.replace('HUD_DIALOG','showPlayerDialog(dialog);' if modern else 'dialog.show();')
text=text.replace('HUD_INFO',info).replace('ACTIVITY_METHODS',methods)
Path(sys.argv[2]).write_text(text)
print('HUD_ACTIVITY_BINDING actualMethods=true baselineWithoutPolicy='+str(not modern).lower()+' source='+sys.argv[1])
PY
SOURCES=("$TMP/PlayerHudHarness.java" "$ROOT/android/tests/PlayerHudActivityTest.java")
if [[ -f "$SRC/PlayerHud.java" ]]; then SOURCES+=("$SRC/PlayerHud.java" "$ROOT/android/tests/PlayerHudTest.java"); fi
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -d "$TMP/classes" "${SOURCES[@]}"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.PlayerHudActivityTest
if [[ -f "$SRC/PlayerHud.java" ]]; then
  "$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.PlayerHudTest
else
  echo 'FAIL PlayerHudPolicy actualSource=absent'; exit 1
fi
echo 'PLAYER_HUD_OK activityMethods=real policy=real timing=deterministic AndroidUI=doubles networkRequests=0 emulator=not_used'
