package io.github.jackotom.hongguo.android;

import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import org.json.*;
/** Deterministic boundary doubles: no Android process, disk media, decoder or network traffic. */
class SystemClock {static long elapsedRealtime(){return 0;}}
class Uri {
 final String value;Uri(String s){value=s;}static Uri parse(String s){return new Uri(s);}static Uri fromFile(File f){return new Uri("file:"+f.getPath());}String getScheme(){return value.split(":")[0];}String getPath(){try{return new java.net.URI(value).getPath();}catch(Exception e){return null;}}
}
class MediaPlayer {
 static final int MEDIA_ERROR_UNSUPPORTED=-1010,MEDIA_INFO_BUFFERING_START=701,MEDIA_INFO_BUFFERING_END=702;
 interface Size {void changed(MediaPlayer p,int w,int h);}interface Seek {void complete(MediaPlayer p);}
 int width=1080,height=1920;float rate=1;boolean playing;Size size;Seek seek;
 int getVideoWidth(){return width;}int getVideoHeight(){return height;}void setOnVideoSizeChangedListener(Size s){size=s;}void setOnSeekCompleteListener(Seek s){seek=s;}boolean isPlaying(){return playing;}void pause(){playing=false;}void setVolume(float l,float r){}
}
class VideoView {
 interface Prepared {void prepared(MediaPlayer p);}interface Error {boolean error(MediaPlayer p,int what,int extra);}interface Info {boolean info(MediaPlayer p,int what,int extra);}interface Completion {void complete(MediaPlayer p);}
 Prepared prepared;Error error;Info info;Completion completion;MediaPlayer player;Uri uri;int position,stops;boolean playing,deferSeek;
 VideoView(Object owner){}void setFocusable(boolean b){}void setFocusableInTouchMode(boolean b){}void setOnPreparedListener(Prepared p){prepared=p;}void setOnErrorListener(Error e){error=e;}void setOnInfoListener(Info i){info=i;}void setOnCompletionListener(Completion c){completion=c;}
 void setVideoURI(Uri u,Map<String,String> h){uri=u;}
 int getCurrentPosition(){return position;}int getDuration(){return 180000;}boolean isPlaying(){return playing;}void start(){playing=true;if(player!=null)player.playing=true;}void pause(){playing=false;if(player!=null)player.playing=false;}void stopPlayback(){playing=false;stops++;}
 void seekTo(int v){position=v;if(!deferSeek&&player!=null&&player.seek!=null)player.seek.complete(player);}void complete(int width,int height){player=new MediaPlayer();player.width=width;player.height=height;if(prepared!=null)prepared.prepared(player);}void fail(int what,int extra){if(error!=null)error.error(player,what,extra);}
}
class Label {String text="";boolean enabled;void setText(String s){text=s;}void setEnabled(boolean v){enabled=v;}}
class SeekBar {int value;void setProgress(int v){value=v;}}
class FrameLayout {static class LayoutParams {LayoutParams(int w,int h,int gravity){}}final List<VideoView> views=new ArrayList<>();void addView(VideoView v,int index,LayoutParams p){views.add(index,v);}void removeView(VideoView v){views.remove(v);}}
class Gravity {static final int CENTER=17;}
class Toast {static final int LENGTH_LONG=1;static final List<String> messages=new ArrayList<>();static Toast makeText(Object a,String s,int duration){messages.add(s);return new Toast();}void show(){}}
class AlertDialog {
 interface Choice {void click(AlertDialog d,int which);}String[] labels;Choice choice;boolean dismissed;
 void dismiss(){dismissed=true;}void click(int which){choice.click(this,which);}
 static class Builder {final AlertDialog dialog=new AlertDialog();Builder(Object a){}Builder setTitle(String title){return this;}Builder setSingleChoiceItems(String[] labels,int current,Choice choice){dialog.labels=labels;dialog.choice=choice;return this;}Builder setNegativeButton(String text,Choice c){return this;}AlertDialog create(){return dialog;}}
}
class MainLoop {
 final Deque<Runnable> callbacks=new ArrayDeque<>();final Set<Runnable> delayed=new HashSet<>();final Map<Runnable,Long> delays=new HashMap<>();void post(Runnable r){callbacks.addLast(r);}void postDelayed(Runnable r,long delay){delayed.add(r);delays.put(r,delay);}void removeCallbacks(Runnable r){delayed.remove(r);delays.remove(r);callbacks.remove(r);}void flush(){while(!callbacks.isEmpty())callbacks.removeFirst().run();}
}
class Worker {
 static class Job extends FutureTask<Void> {Job(Runnable r){super(r,null);}}
 final Deque<Job> pending=new ArrayDeque<>();Future<?> submit(Runnable r){Job j=new Job(r);pending.addLast(j);return j;}void execute(Runnable r){submit(r);}void flush(){while(!pending.isEmpty())pending.removeFirst().run();}
}
class PlaybackCache {
 static final class QueueWindow {int position,prunes;boolean closed;QueueWindow(int p){position=p;}void moveTo(int p){position=p;}void prune(){prunes++;}void close(){closed=true;}}
 QueueWindow openQueueWindow(JSONObject fallback,List<JSONObject> episodes,int p,boolean cleanup,int ahead,boolean casting){return new QueueWindow(p);}
 final Map<String,Integer> ownerReservations=new HashMap<>();final List<String> releasedOwners=new ArrayList<>(),fetchedOwners=new ArrayList<>(),invalidatedOwners=new ArrayList<>();
 String ownerKey(String sid,String vid,int index,String quality){return sid+":"+vid+":"+index+":"+quality;}

 final Map<String,Integer> protectedKeys=new HashMap<>();final List<String> fetches=new ArrayList<>(),invalidations=new ArrayList<>();final Set<String> hits=new HashSet<>();final Map<String,Exception> failures=new HashMap<>();
 String key(String vid,String quality){return vid+":"+quality;}void protect(String s,String vid,int i,String q){String owned=ownerKey(s,vid,i,q);ownerReservations.put(owned,ownerReservations.getOrDefault(owned,0)+1);String k=key(vid,q);protectedKeys.put(k,protectedKeys.getOrDefault(k,0)+1);}void release(String s,String vid,int i,String q){String owned=ownerKey(s,vid,i,q);releasedOwners.add(owned);int count=ownerReservations.getOrDefault(owned,0);if(count<=1)ownerReservations.remove(owned);else ownerReservations.put(owned,count-1);String k=key(vid,q);int n=protectedKeys.getOrDefault(k,0);if(n<=1)protectedKeys.remove(k);else protectedKeys.put(k,n-1);}
 final Map<String,Integer> scopedReservations=new HashMap<>();final List<String> scopedFinds=new ArrayList<>(),scopedFetches=new ArrayList<>(),backgroundFetches=new ArrayList<>();final List<PlaybackSources.Plan> fetchedPlans=new ArrayList<>();
 String scoped(String sid,String vid,int i,String q,int ceiling){return ownerKey(sid,vid,i,q)+":"+ceiling;}
 void protect(String sid,String vid,int i,String q,int ceiling){protect(sid,vid,i,q);String k=scoped(sid,vid,i,q,ceiling);scopedReservations.put(k,scopedReservations.getOrDefault(k,0)+1);}
 void release(String sid,String vid,int i,String q,int ceiling){release(sid,vid,i,q);String k=scoped(sid,vid,i,q,ceiling);int n=scopedReservations.getOrDefault(k,0);if(n<=1)scopedReservations.remove(k);else scopedReservations.put(k,n-1);}
 File find(String sid,String vid,int i,String q,int ceiling){scopedFinds.add(scoped(sid,vid,i,q,ceiling));if(ceiling==0)return find(sid,vid,i,q);return hits.contains(key(vid,q)+":"+ceiling)?new File("fixture-bound-"+vid+"-"+q+"-"+ceiling+".mp4"):null;}
 File fetchForeground(String sid,String vid,int i,PlaybackSources.Plan plan,String q)throws Exception{fetchedPlans.add(plan);scopedFetches.add(scoped(sid,vid,i,q,plan.playbackCeiling));return fetchForeground(sid,vid,i,plan.url,q);}
 File fetch(String sid,String vid,int i,PlaybackSources.Plan plan,String q)throws Exception{fetchedPlans.add(plan);backgroundFetches.add(vid+":"+q);scopedFetches.add(scoped(sid,vid,i,q,plan.playbackCeiling));return new File("fixture-background-"+vid+"-"+q+".mp4");}
 File fetch(String sid,String vid,int i,String url,String q)throws Exception{backgroundFetches.add(vid+":"+q);return new File("fixture-background-"+vid+"-"+q+".mp4");}
 void invalidate(String sid,String vid,int i,String q,int ceiling){invalidatedOwners.add(ownerKey(sid,vid,i,q));invalidations.add(key(vid,q));hits.remove(ceiling==0?key(vid,q):key(vid,q)+":"+ceiling);}
 File find(String sid,String vid,int i,String q){return hits.contains(key(vid,q))?new File("fixture-"+vid+"-"+q+".mp4"):null;}
 File fetchForeground(String sid,String vid,int i,String url,String q)throws Exception{fetches.add(key(vid,q));fetchedOwners.add(ownerKey(sid,vid,i,q));Exception failure=failures.get(q);if(failure!=null)throw failure;return new File("fixture-"+vid+"-"+q+".mp4");}
 void invalidate(String sid,String vid,int i,String q){invalidatedOwners.add(ownerKey(sid,vid,i,q));invalidations.add(key(vid,q));hits.remove(key(vid,q));}
}
class NetworkGuard {static final String UA="fixture-ua",REFERER="https://fixture.invalid/";}
class PlaybackSources {
 static final class QualityUnavailableException extends IOException {QualityUnavailableException(String m){super(m);}}
 static final class Plan {int width,height,playbackCeiling;String source="app",url="https://fixture.invalid/media",userAgent="fixture-ua",referer="https://fixture.invalid/";}
 static final List<String> calls=new ArrayList<>(),owners=new ArrayList<>();static final Map<String,Exception> failures=new HashMap<>(),onceFailures=new HashMap<>();static final Map<String,String> sourceKinds=new HashMap<>();static Runnable onResolve;static final Map<String,Integer> ceilings=new HashMap<>(),pixelEdges=new HashMap<>();static final List<String> playbackRequests=new ArrayList<>();
 static void reset(){calls.clear();owners.clear();failures.clear();onceFailures.clear();sourceKinds.clear();ceilings.clear();pixelEdges.clear();playbackRequests.clear();onResolve=null;PlayerOptions.saved.clear();PlayerOptions.failSave=false;Toast.messages.clear();}
 static Plan resolve(String sid,String vid,String q)throws Exception{calls.add("resolve:"+vid+":"+q);owners.add(sid+":"+vid+":"+q);Runnable action=onResolve;onResolve=null;if(action!=null)action.run();Exception e=onceFailures.remove(q);if(e==null)e=failures.get(q);if(e!=null)throw e;Plan p=new Plan();p.source=sourceKinds.getOrDefault(q,"app");return p;}
 static Plan recover(String sid,String vid,String q,boolean web)throws Exception{int index=calls.size();try{return resolve(sid,vid,q);}finally{if(calls.size()>index)calls.set(index,"recover:"+vid+":"+q);}}
 static Plan resolvePlayback(String sid,String vid,String q,int ceiling,boolean fresh,boolean failedWeb)throws Exception{playbackRequests.add(q+":"+ceiling+":"+fresh+":"+failedWeb);Plan p=fresh?recover(sid,vid,q,failedWeb):resolve(sid,vid,q);p.playbackCeiling=ceiling;p.width=pixelEdges.getOrDefault(q,0);p.height=p.width*2;ceilings.put(q,ceiling);return p;}
 static boolean canRecoverPlayback(Exception e){return !(e instanceof java.io.InterruptedIOException)&&!(e instanceof javax.net.ssl.SSLException)&&canLowerQuality(e)||e instanceof java.net.SocketTimeoutException;}
 static Plan find(String u,String q){return null;}static Plan register(JSONObject info,String vid,boolean checked){return new Plan();}
 static boolean canLowerQuality(Exception e){return e instanceof QualityUnavailableException||e instanceof UnknownHostException||e instanceof SocketTimeoutException||e instanceof IOException&&"片源响应 HTTP 403".equals(e.getMessage());}
}
class PlayerOptions {
 static final List<String> saved=new ArrayList<>();static boolean failSave;
 static float observedSpeed(MediaPlayer p){return p.rate;}static float setVerifiedSpeed(MediaPlayer p,float requested,boolean playing){p.rate=requested;p.playing=playing;return requested;}
 static void update(Object context,JSONObject value)throws Exception{if(failSave)throw new IOException("fixture preference commit failed");saved.add(value.getString("quality"));}
}
class LibraryStore {final List<String> histories=new ArrayList<>(),ownedHistories=new ArrayList<>();void history(JSONObject s,JSONObject e,int position){history(s,e,position,0);}void history(JSONObject s,JSONObject e,long position,long duration){histories.add(e.optString("vid")+":"+position);ownedHistories.add(s.optString("seriesId")+":"+e.optString("vid")+":"+e.optInt("index")+":"+position);}}

class Bundle {
 final Map<String,Object> values=new HashMap<>();void putString(String k,String v){values.put(k,v);}void putInt(String k,int v){values.put(k,v);}void putBoolean(String k,boolean v){values.put(k,v);}void putDouble(String k,double v){values.put(k,v);}
 String getString(String k,String d){return values.get(k) instanceof String?(String)values.get(k):d;}int getInt(String k,int d){return values.get(k) instanceof Number?((Number)values.get(k)).intValue():d;}
}
