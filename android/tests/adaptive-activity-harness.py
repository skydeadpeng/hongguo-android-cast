#!/usr/bin/env python3
"""Extract complete production methods. Doubles implement only Android/cache/transport boundaries."""
from pathlib import Path
import re,sys
source=Path(sys.argv[1]).read_text()
def blocks(pattern):
    results=[]
    for m in re.finditer(pattern,source,re.M):
        start=m.start();i=source.index('{',m.start());depth=0;quote=None;escaped=False;comment=None
        while i<len(source):
            ch=source[i];pair=source[i:i+2]
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
                if depth==0:
                    results.append(source[start:i+1].replace('private ',''));break
            i+=1
    return results
modern='AdaptiveQuality.prepare(' in source
playlist='PlaybackPlaylist.' in source
names=['selectEpisode']
if modern:
    names+=['releaseCurrent','reserveMedia','releasePrepared','discardPendingMedia','adoptMedia','prepareMedia','stageProgress','loadAt','loadAtInternal','bindPlayback','recoverMedia','lowerCurrentMedia','validSwitch','releaseSwitch','cancelQualitySwitch','failQualitySwitch','selectQuality','switchQuality','resolveQualitySwitch','prepareQualitySwitch','validCandidate','lowerQualitySwitch','finishQualitySwitch','schedulePrefetch','renderStatus','qualityLabel']
for extra in ['stageCeiling','prepareStage','cancelPlaybackWatchdogs','validPlaybackCallback','armPreparationTimeout','updateBufferingWatchdog','observedShortEdge','failCurrentMedia','restoreAutomaticCeiling','cacheEpisode']:
    if re.search(r'\b'+extra+r'\(',source): names.append(extra)
if playlist: names+=['owner','mediaKey','closeLocalWindow','pruneReleasedWindow','saveProgress','restoreSelection','savePlaybackState']
if 'void recordProgress(' in source: names+=['recordProgress','watchProgressSeeks']
methods=[]
for name in names:
    found=blocks(r'^    (?:private|public|protected) (?:synchronized )?(?:static )?[^\n{]*\b'+re.escape(name)+r'\([^;\n]*\)(?: throws [^\n{]+)?\s*\{')
    if not found:raise SystemExit('MISSING_ACTUAL_ACTIVITY_METHOD '+name)
    methods+=found
nested=[]
for name in (['RecoveryBudget','PreparedMedia','QualitySwitch'] if modern else []):
    found=blocks(r'^    (?:private )?static (?:final )?class '+name+r'\s*\{')
    if not found:raise SystemExit('MISSING_ACTUAL_ACTIVITY_CLASS '+name)
    nested+=found
header='''package io.github.jackotom.hongguo.android;
import java.io.*;import java.util.*;import java.util.concurrent.*;import org.json.*;
final class AdaptiveActivityHarness {
 final MainLoop main=new MainLoop();final Worker resolver=new Worker(),cacheWorker=new Worker();Future<?> resolution;
 final Set<String> caching=new HashSet<>();final PlaybackCache cache=new PlaybackCache();final LibraryStore store=new LibraryStore();
 VideoView video=new VideoView(this);FrameLayout playerRoot=new FrameLayout();MediaPlayer mediaPlayer;
 String quality="1080p",actualQuality="1080p",protectedQuality="auto",currentKind="fixture",lastMediaError;float requestedSpeed=1,actualSpeed=1;
 final Label title=new Label(),status=new Label(),time=new Label();final SeekBar seek=new SeekBar();
 JSONObject series,episode,queueSeries,protectedEpisode;String protectedSeriesId="",queueIdentity="";PlaybackCache.QueueWindow localWindow,protectedWindow;boolean cleanup=true;final List<JSONObject>queue=new ArrayList<>();int current,generation=7,resumeAt,lastGoodPosition,qualitySerial,videoWidth,videoHeight,sourceShortEdge,automaticCeiling,protectedCeiling;Runnable playbackPrepareTimeout,bufferingTimeout;boolean decoderPreparing;
 boolean destroyed,offline,autoplay,prepared,completed,userPaused,active=true,protectedCurrent,currentManual,currentCache,currentWeb,buffering,castWasActive;
 long lastProgressSavedAt;
 int loads,loadedAt,loadedPosition,errors,updates,saves,prunes;Uri manualFile;AlertDialog dialog;
 // Danmaku is an independent display boundary, certified by DanmakuControllerTest.
 DanmakuLayer danmaku;static final class DanmakuLayer{void suspend(){}}
 NESTED
 MODERN_FIELD
 AdaptiveActivityHarness()throws Exception{series=new JSONObject().put("seriesId","123").put("title","fixture");for(int i=1;i<=3;i++)queue.add(new JSONObject().put("vid","v"+i).put("index",i));episode=queue.get(0);MODERN_INIT}
 // DLNA is an external boundary here: this existing harness certifies only
 // local playback. Dedicated DlnaUi tests execute the real cast methods.
 boolean casting(){return false;}boolean castCommand(String command,long value){return false;}
 boolean renderCastControls(){return false;}void detachCast(){}
 void showPlayerDialog(AlertDialog d){dialog=d;}void showControls(){}void updateControlsState(){}void installVideoTouch(VideoView v){}
 void updateButtons(){updates++;}void pruneCache(){prunes++;}void saveProgress(){saves++;}
 Uri downloaded(JSONObject e){return manualFile;}void playbackError(int token,String message){if(token==generation&&!destroyed){errors++;status.setText(message);}}
 Intent getIntent(){return new Intent();}static class Intent{String getStringExtra(String name){return null;}}
 void pump(){for(int i=0;i<8;i++){resolver.flush();main.flush();cacheWorker.flush();main.flush();}}
 BASELINE_LOAD
 METHODS
}
'''
if playlist: header=header.replace('void saveProgress(){saves++;}','')
header=header.replace('NESTED','\n'.join(nested))
header=header.replace('MODERN_FIELD','final Set<PreparedMedia> pendingMedia=new HashSet<>();QualitySwitch qualitySwitch;final RecoveryBudget recoveryBudget=new RecoveryBudget();' if modern else '')
header=header.replace('MODERN_INIT','recoveryBudget.reset(generation);' if modern else '')
header=header.replace('BASELINE_LOAD','' if modern else 'void loadAt(int index,String url,int position){loads++;loadedAt=index;loadedPosition=position;}')
header=header.replace('METHODS','\n'.join(methods))
Path(sys.argv[2]).write_text(header)
print('ADAPTIVE_ACTIVITY_BINDING actualMethods=true modern='+str(modern).lower()+' methods='+str(len(methods))+' source='+sys.argv[1])
