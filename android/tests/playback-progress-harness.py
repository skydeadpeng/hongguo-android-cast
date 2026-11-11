#!/usr/bin/env python3
"""Execute unchanged production progress methods; watchdog scheduling is a separately-tested boundary."""
from pathlib import Path
import re,sys
source=Path(sys.argv[1]).read_text()
def block(pattern):
 m=re.search(pattern,source,re.M)
 if not m:return ''
 start=m.start();i=source.index('{',start);depth=0;quote=None;escape=False;comment=None
 while i<len(source):
  ch=source[i];pair=source[i:i+2]
  if comment=='line':
   if ch=='\n':comment=None
  elif comment=='block':
   if pair=='*/':comment=None;i+=1
  elif quote:
   if escape:escape=False
   elif ch=='\\':escape=True
   elif ch==quote:quote=None
  elif pair=='//':comment='line';i+=1
  elif pair=='/*':comment='block';i+=1
  elif ch in ('"',"'"):quote=ch
  elif ch=='{':depth+=1
  elif ch=='}':
   depth-=1
   if depth==0:return source[start:i+1].replace('private ','',1)
  i+=1
 raise AssertionError('Unclosed production method')
def method(name):return block(r'^    (?:private|public|protected) [^\n{]*\b'+name+r'\([^;\n]*\)\s*\{')
methods='\n'.join(filter(None,(method(x) for x in ['saveProgress','recordProgress','periodicProgress','watchProgressSeeks','togglePlayback','format'])))
tick=block(r'^    private final Runnable tick\s*=')+';'
assert 'Runnable tick' in tick and 'void saveProgress' in methods
watch=method('watchProgressSeeks')
text=r'''package io.github.jackotom.hongguo.android;
import java.util.*;import org.json.*;
final class SystemClock{static long now;static long elapsedRealtime(){return now;}}
final class VideoView{int position,duration=180000;boolean playing=true;int getCurrentPosition(){return position;}int getDuration(){return duration;}boolean isPlaying(){return playing;}void start(){playing=true;}void pause(){playing=false;}void seekTo(int value){position=value;}}
final class MediaPlayer{interface Seek{void complete(MediaPlayer p);}Seek seek;void setOnSeekCompleteListener(Seek value){seek=value;}int getVideoWidth(){return 720;}int getVideoHeight(){return 1280;}}
final class PlayerOptions{static float observedSpeed(MediaPlayer p){return 1;}}
final class LibraryStore{final List<JSONObject>writes=new ArrayList<>();void history(JSONObject s,JSONObject e,long p){history(s,e,p,0);}void history(JSONObject s,JSONObject e,long p,long d){try{writes.add(new JSONObject().put("seriesId",s.getString("seriesId")).put("vid",e.getString("vid")).put("positionMs",p).put("durationMs",d));}catch(JSONException ex){throw new RuntimeException(ex);}}}
final class SeekBar{void setMax(int x){}void setProgress(int x){}}
final class Label{void setText(String x){}}
final class Main{void postDelayed(Runnable x,long ms){}}
public final class PlaybackProgressHarness{
 static int checks,groups;static void check(boolean ok,String msg){checks++;if(!ok)throw new AssertionError(msg);}
 boolean active=true,destroyed,prepared=true,completed,dragging,buffering,userPaused,cast,decoderPreparing;
 Object qualitySwitch;int generation=3,resumeAt,lastGoodPosition,videoWidth,videoHeight;float actualSpeed;long lastProgressSavedAt;
 final VideoView video=new VideoView();MediaPlayer mediaPlayer=new MediaPlayer();final LibraryStore store=new LibraryStore();
 final JSONObject series,episode;final SeekBar seek=new SeekBar();final Label time=new Label();final Main main=new Main();
 PlaybackProgressHarness()throws Exception{series=new JSONObject().put("seriesId","123456789");episode=new JSONObject().put("vid","987654321");}
 boolean casting(){return cast;}JSONObject owner(JSONObject e){return series;}boolean castCommand(String c,long v){return cast;}void cancelPlaybackWatchdogs(){}void updateBufferingWatchdog(int token,VideoView view){}void updateDanmaku(){}void renderCastControls(){}void renderStatus(){}void updateButtons(){}
 METHODS
 TICK
 public static void main(String[]args)throws Exception{
  PlaybackProgressHarness h=new PlaybackProgressHarness();SystemClock.now=0;h.tick.run();SystemClock.now=14999;h.video.position=14999;h.tick.run();check(h.store.writes.isEmpty(),"no per-tick disk writes before 15s");SystemClock.now=15000;h.video.position=15000;h.tick.run();check(h.store.writes.size()==1,"active local playback must persist latest position at 15s, actualWrites="+h.store.writes.size());check(h.store.writes.get(0).getLong("durationMs")==180000,"real media duration persisted");h.tick.run();check(h.store.writes.size()==1,"same tick is throttled");SystemClock.now=30000;h.video.position=30000;h.tick.run();check(h.store.writes.size()==2&&h.store.writes.get(1).getLong("positionMs")==30000,"second interval checkpoints newest position");groups++;
  for(String gate:new String[]{"inactive","destroyed","unprepared","dragging","buffering","switching","paused","casting"}){PlaybackProgressHarness x=new PlaybackProgressHarness();switch(gate){case"inactive":x.active=false;break;case"destroyed":x.destroyed=true;break;case"unprepared":x.prepared=false;break;case"dragging":x.dragging=true;break;case"buffering":x.buffering=true;break;case"switching":x.qualitySwitch=new Object();break;case"paused":x.video.playing=false;break;case"casting":x.cast=true;break;}SystemClock.now=45000;x.tick.run();check(x.store.writes.isEmpty(),"no periodic local overwrite while "+gate);}groups++;
  PlaybackProgressHarness p=new PlaybackProgressHarness();p.video.position=43210;SystemClock.now=100;p.togglePlayback();check(p.userPaused&&!p.video.playing&&p.store.writes.size()==1,"pause checkpoints immediately before interval");check(p.store.writes.get(0).getLong("positionMs")==43210,"pause position exact");p.togglePlayback();check(!p.userPaused&&p.store.writes.size()==1,"resume does not write a fictitious new progress");groups++;
  WATCH_TEST
  PlaybackProgressHarness e=new PlaybackProgressHarness();e.completed=true;e.video.position=180000;e.saveProgress();check(e.store.writes.get(0).getLong("positionMs")==0&&e.store.writes.get(0).getLong("durationMs")==180000,"completion retains duration with existing replay-zero semantics");e.cast=true;e.saveProgress();check(e.store.writes.size()==1,"cast never overwritten by local zero");groups++;
  PlaybackProgressHarness r=new PlaybackProgressHarness();r.lastProgressSavedAt=999999;SystemClock.now=15;r.video.position=99;r.tick.run();check(r.store.writes.size()==1,"monotonic reset does not defer persistence indefinitely");groups++;
  System.out.println("PLAYBACK_PROGRESS_OK groups="+groups+" checks="+checks+" actualTick=true actualSave=true intervalMs=15000 pauseAndSeek=checkpointed duration=preserved networkRequests=0");
 }
}
'''
watchtest='''PlaybackProgressHarness s=new PlaybackProgressHarness();s.video.playing=false;s.watchProgressSeeks(s.mediaPlayer,s.generation,s.video);s.video.position=77777;s.mediaPlayer.seek.complete(s.mediaPlayer);check(s.store.writes.size()==1&&s.store.writes.get(0).getLong("positionMs")==77777,"acknowledged paused seek checkpoints exact position");s.generation++;s.mediaPlayer.seek.complete(s.mediaPlayer);check(s.store.writes.size()==1,"stale-generation seek cannot write");groups++;'''
if not watch:watchtest='check(false,"production seek completion checkpoint missing");'
text=text.replace('METHODS',methods).replace('TICK',tick).replace('WATCH_TEST',watchtest)
Path(sys.argv[2]).write_text(text)
print('PLAYBACK_PROGRESS_BINDING source='+sys.argv[1]+' unchangedMethodBodies=true')
