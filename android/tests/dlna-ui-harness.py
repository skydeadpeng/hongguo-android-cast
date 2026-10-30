#!/usr/bin/env python3
"""Execute actual PlayerActivity DLNA adapter methods; Android/service boundaries are doubles."""
from pathlib import Path
import sys,re
source=Path(sys.argv[1]).read_text();out=Path(sys.argv[2])
def methods(name):
 results=[]
 for m in re.finditer(r'(?m)^    (?:@Override )?(?:private|public|protected) [^\n{]*\b'+re.escape(name)+r'\([^;\n]*\)(?:\s+throws\s+[^\n{]+)?\s*\{',source):
  start=m.start();i=source.index('{',m.start());depth=0;quote=None;escape=False;comment=None
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
    if depth==0:results.append(source[start:i+1].replace('private ','',1));break
   i+=1
 return results
names=['onProtocolCastSnapshot','browserCastCommand','browserCastStatusDetail','castWifiAddress','casting','castCommand','onCastSnapshot','saveProgressBeforeCast','renderCastControls','saveProgress','loadAt','togglePlayback','seekBy','canHideControls','detachCast','playbackDuration','format','qualityLabel','castStatusDetail','notifyCastError','castDiagnosticValue','castDiagnostics','showCastDiagnostics','copyCastDiagnostics','showCastDevices','castMergeWindow','setCastMergeWindow','showMergeCastDialog']
if 'PlaybackPlaylist.' in source:names += ['owner','closeLocalWindow']
if 'void recordProgress(' in source:names+=['recordProgress']
parts=[]
for name in names:
 found=methods(name)
 if not found:raise SystemExit('DLNA_UI_MISSING actualMethod='+name)
 parts+=found
header='''package io.github.jackotom.hongguo.android;
import org.json.*;import java.util.*;import java.util.concurrent.*;
final class BuildConfig {static boolean IS_TV=false;static final String VERSION_NAME="fixture";}
class TextView {String text="";boolean enabled;void setText(String s){text=s;}String getText(){return text;}void setEnabled(boolean b){enabled=b;}void setContentDescription(String s){}}
class Button extends TextView {}
class SeekBar extends TextView {int max,progress;void setMax(int n){max=n;}void setProgress(int n){progress=n;}}
class VideoView {boolean playing;int position=27000,duration=100000,stops,starts,seeks;boolean isPlaying(){return playing;}int getCurrentPosition(){return position;}int getDuration(){return duration;}void stopPlayback(){playing=false;stops++;}void pause(){playing=false;}void start(){playing=true;starts++;}void seekTo(int n){position=n;seeks++;}}
class Toast {static final int LENGTH_LONG=1;static final List<String>shown=new ArrayList<>();final String text;Toast(String s){text=s;}static Toast makeText(Object c,String s,int d){return new Toast(s);}void show(){shown.add(text);}}
class ClipData {final String label,text;ClipData(String l,String t){label=l;text=t;}static ClipData newPlainText(String l,String t){return new ClipData(l,t);}}
class ClipboardManager {int writes;ClipData clip;void setPrimaryClip(ClipData c){writes++;clip=c;}}
class DialogInterface {void dismiss(){}}
class AlertDialog extends DialogInterface {interface OnClickListener{void onClick(DialogInterface dialog,int which);}String title,message;String[]items,choices;OnClickListener itemAction,positiveAction,choiceAction;int choiceSelected=-1;void clickItem(int n){itemAction.onClick(this,n);}void clickPositive(){positiveAction.onClick(this,-1);}void clickChoice(int n){choiceAction.onClick(this,n);}static class Builder {final AlertDialog dialog=new AlertDialog();Builder(Object c){}Builder setTitle(String s){dialog.title=s;return this;}Builder setMessage(String s){dialog.message=s;return this;}Builder setItems(String[]s,OnClickListener a){dialog.items=s;dialog.itemAction=a;return this;}Builder setSingleChoiceItems(String[]s,int selected,OnClickListener a){dialog.choices=s;dialog.choiceSelected=selected;dialog.choiceAction=a;return this;}Builder setPositiveButton(String s,OnClickListener a){dialog.positiveAction=a;return this;}Builder setNegativeButton(String s,OnClickListener a){return this;}Builder setNeutralButton(String s,OnClickListener a){return this;}AlertDialog create(){return dialog;}}}
class SystemClock {static long elapsedRealtime(){return 0;}}
class LibraryStore {int writes,lastPosition;String lastSeries;void history(JSONObject s,JSONObject e,int p){history(s,e,p,0);}void history(JSONObject s,JSONObject e,long p,long d){writes++;lastPosition=(int)p;lastSeries=s.optString("seriesId");}}
class PlaybackCache {static class QueueWindow {boolean closed;void close(){closed=true;}}}
class DlnaSession {static class Snapshot {boolean active,preparing,playing,autoplay=true;int current,targetCurrent,episodeIndex;long positionMs,durationMs;String title="",deviceName="MOCK TV",state="PLAYING",error="",actualQuality="720p",preferredQuality="1080p",seriesId="123";}}
class DlnaCastService {interface Listener {void onSnapshot(DlnaSession.Snapshot s);}DlnaSession.Snapshot state=new DlnaSession.Snapshot();DlnaSession.Snapshot snapshot(){return state;}List<String>calls=new ArrayList<>();void select(int n){calls.add("select:"+n);}void play(){calls.add("play");}void pause(){calls.add("pause");}void seek(long n){calls.add("seek:"+n);}void setAutoplay(boolean b){calls.add("autoplay:"+b);}void removeListener(Listener l){calls.add("removeListener");}void stopCast(){calls.add("stopCast");}String diagnosticText="";String diagnostics(){return diagnosticText;}}
class BrowserCastService extends DlnaCastService {String url="http://192.168.1.2:1234/";String landingUrl(){return url;}interface Listener {void onSnapshot(DlnaSession.Snapshot s);}void removeListener(Listener l){calls.add("removeListener");}}
interface SharedPreferences {int getInt(String key,int fallback);Editor edit();interface Editor {Editor putInt(String key,int value);void apply();}}
class MemoryCastPreferences implements SharedPreferences {final java.util.Map<String,Integer> ints=new java.util.HashMap<>();public int getInt(String key,int fallback){Integer value=ints.get(key);return value==null?fallback:value;}public Editor edit(){return new Editor(){public Editor putInt(String key,int value){ints.put(key,value);return this;}public void apply(){}};}}
class DlnaUiHarness {
 long lastProgressSavedAt;
 static final String CONNECTIVITY_SERVICE="connectivity",CLIPBOARD_SERVICE="clipboard";
 static final int MODE_PRIVATE=0;
 final MemoryCastPreferences castPreferences=new MemoryCastPreferences();
 SharedPreferences getSharedPreferences(String name,int mode){return castPreferences;}
 final android.net.ConnectivityManager connectivity=new android.net.ConnectivityManager();
 final ClipboardManager clipboard=new ClipboardManager();
 Object getSystemService(String name){return CLIPBOARD_SERVICE.equals(name)?clipboard:connectivity;}

 BrowserCastService browserCastService;boolean browserCastSelected,browserCastBound,showBrowserPairWhenReady;int pairDialogs;void showBrowserPairing(){pairDialogs++;}final BrowserCastService.Listener browserCastListener=s->{};final Object browserCastConnection=new Object();
 DlnaCastService castService=new DlnaCastService();DlnaSession.Snapshot castSnapshot;boolean castWasActive,castBound=true;String lastCastError="";AlertDialog dialog;int searches;
 final DlnaCastService.Listener castListener=s->{};final Object castConnection=new Object();final ExecutorService castDiscoveryWorker=Executors.newSingleThreadExecutor();
 final VideoView video=new VideoView();final LibraryStore store=new LibraryStore();Object mediaPlayer;
 final List<JSONObject>queue=new ArrayList<>();JSONObject series,episode,queueSeries;String queueIdentity="123";PlaybackCache.QueueWindow localWindow;int current,generation=3,resumeAt,lastGoodPosition,loads,loadedAt,loadedPosition,cancelled,released,discards,unbinds;
 boolean destroyed,prepared=true,completed,buffering,dragging,autoplay=true,active=true,userPaused,touchingControls,offline;int hudDialogs;Object qualitySwitch;String quality="1080p",actualQuality="1080p";
 final TextView title=new TextView(),status=new TextView(),time=new TextView();final Button previous=new Button(),next=new Button(),toggle=new Button(),qualityButton=new Button(),speedButton=new Button(),autoplayButton=new Button(),castButton=new Button();final SeekBar seek=new SeekBar();Future<?> resolution;
 DlnaUiHarness()throws Exception {series=new JSONObject().put("seriesId","123").put("title","MOCK剧");for(int i=1;i<=4;i++)queue.add(new JSONObject().put("vid","v"+i).put("index",i));episode=queue.get(0);queueSeries=series;video.playing=true;}
 void showPlayerDialog(AlertDialog d){dialog=d;}void searchCastDevices(){searches++;}
 boolean isFinishing(){return false;}void cancelQualitySwitch(){cancelled++;}void discardPendingMedia(){discards++;}void releaseCurrent(){released++;}void showControls(){}void updateControlsState(){}void updateButtons(){renderCastControls();}void cancelCastDiscovery(){}void unbindService(Object c){unbinds++;}
 void loadAtInternal(int n,String u,int p,boolean paused,boolean recovering,boolean failedWeb){loads++;loadedAt=n;loadedPosition=p;}
 METHODS
}
'''
out.write_text(header.replace('METHODS','\n'.join(parts)))
print('DLNA_UI_BINDING actualPlayerMethods=true methods='+str(len(parts)))

# Android network-query boundaries only; the actual Activity selection method runs above.
network=out.parent/'android/net';network.mkdir(parents=True,exist_ok=True)
(network/'ConnectivityManager.java').write_text('package android.net; public class ConnectivityManager { public Network[] networks=new Network[0]; public Network[] getAllNetworks(){return networks;}public NetworkCapabilities getNetworkCapabilities(Network n){return n.capabilities;}public LinkProperties getLinkProperties(Network n){return n.properties;}}')
(network/'Network.java').write_text('package android.net;public class Network {public NetworkCapabilities capabilities;public LinkProperties properties;public Network(NetworkCapabilities c,LinkProperties p){capabilities=c;properties=p;}}')
(network/'NetworkCapabilities.java').write_text('package android.net;public class NetworkCapabilities {public static final int TRANSPORT_WIFI=1,TRANSPORT_VPN=4,TRANSPORT_CELLULAR=0;final int[] transports;public NetworkCapabilities(int...t){transports=t;}public boolean hasTransport(int v){for(int t:transports)if(t==v)return true;return false;}}')
(network/'LinkProperties.java').write_text('package android.net;public class LinkProperties {final java.util.List<LinkAddress>addresses=new java.util.ArrayList<>();public LinkProperties(String...hosts)throws Exception{for(String h:hosts)addresses.add(new LinkAddress(java.net.InetAddress.getByName(h)));}public java.util.List<LinkAddress>getLinkAddresses(){return addresses;}}')
(network/'LinkAddress.java').write_text('package android.net;public class LinkAddress {final java.net.InetAddress address;public LinkAddress(java.net.InetAddress a){address=a;}public java.net.InetAddress getAddress(){return address;}}')
