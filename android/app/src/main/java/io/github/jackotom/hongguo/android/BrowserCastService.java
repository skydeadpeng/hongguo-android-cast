package io.github.jackotom.hongguo.android;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Phone-owned browser receiver session. DLNA's control and end-of-file logic stay independent. */
public final class BrowserCastService extends Service {
    private static final String KEEP="io.github.jackotom.hongguo.BROWSER_KEEP", STOP="io.github.jackotom.hongguo.BROWSER_STOP", CHANNEL="hongguo_browser_cast";
    private static final int NOTIFICATION=4203;
    public interface Listener { void onSnapshot(DlnaSession.Snapshot snapshot); }
    public final class LocalBinder extends Binder { public BrowserCastService service(){return BrowserCastService.this;} }
    private final LocalBinder binder=new LocalBinder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners=new CopyOnWriteArraySet<>();
    private final ExecutorService lifecycle=Executors.newSingleThreadExecutor();
    private volatile BrowserCastSession session;
    private volatile BrowserCastServer server;
    private volatile java.util.function.Supplier<String> diagnosticReader;
    private volatile DlnaSession.Snapshot snapshot=DlnaSession.Snapshot.idle("");
    private volatile boolean destroyed;
    private boolean channelCreated;private int serial;private Future<?> connecting;
    private PowerManager.WakeLock cpu;private WifiManager.WifiLock wifi;
    private Request pending;
    private static final class Request {
        final String bind;final JSONObject series;final List<JSONObject> episodes;final int current;final String quality,identity;final boolean autoplay,cleanup;final long resume;
        Request(String bind,JSONObject series,List<JSONObject> episodes,int current,String quality,boolean autoplay,boolean cleanup,long resume)throws JSONException{this.bind=bind;this.series=series;this.episodes=episodes;this.current=current;this.quality=quality;this.autoplay=autoplay;this.cleanup=cleanup;this.resume=resume;this.identity=PlaybackPlaylist.identity(series,episodes);}
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){if(intent!=null&&STOP.equals(intent.getAction()))stopCast();else if(snapshot.active)ensureForeground();else{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);}return START_NOT_STICKY;}
    public DlnaSession.Snapshot snapshot(){return snapshot;}
    public String landingUrl(){BrowserCastServer value=server;return value==null?"":value.landingUrl();}
    public String pairCode(){BrowserCastServer value=server;return value==null?"":value.pairCode();}
    public String diagnostics(){java.util.function.Supplier<String> reader=diagnosticReader;return reader==null?"浏览器接收：服务尚未就绪":reader.get();}
    public void addListener(Listener listener){if(listener!=null){listeners.add(listener);main.post(()->{if(listeners.contains(listener))listener.onSnapshot(snapshot);});}}
    public void removeListener(Listener listener){listeners.remove(listener);}
    public void connect(String bind,JSONObject series,JSONArray episodes,int selectedPosition,String preferredQuality,boolean autoplay,boolean cleanup,long resumeMs){
        try{
            if(!DlnaNetwork.isPrivateIpv4(bind)||episodes==null||selectedPosition<0||selectedPosition>=episodes.length())throw new IOException("请选择 Wi-Fi 和有效分集");
            JSONObject cleanSeries=LibraryStore.series(series),selected=episodes.getJSONObject(selectedPosition);
            JSONArray clean=PlaybackPlaylist.normalize(cleanSeries,episodes,selected);List<JSONObject> queue=new ArrayList<>();
            for(int i=0;i<clean.length();i++)queue.add(clean.getJSONObject(i));
            int position=PlaybackPlaylist.find(queue,PlaybackPlaylist.owner(cleanSeries,selected).getString("seriesId"),selected.getString("vid"),selected.getInt("index"),cleanSeries);
            if(position<0)throw new IOException("分集不在队列中");
            Request request=new Request(bind,LibraryStore.series(PlaybackPlaylist.owner(cleanSeries,selected)),Collections.unmodifiableList(queue),position,MediaQuality.normalize(preferredQuality),autoplay,cleanup,Math.max(0,resumeMs));
            if(destroyed)return;
            if(session!=null&&session.snapshot().active){pending=request;session.stop();return;}
            begin(request);
        }catch(Exception failure){String error="浏览器投屏启动未完成："+cleanMessage(failure);publish(snapshot.active?snapshot.withError(error):DlnaSession.Snapshot.idle(error));}
    }
    private void begin(Request request){
        final int token=++serial;pending=null;diagnosticReader=null;server=null;
        if(connecting!=null)connecting.cancel(true);
        try{startForegroundService(new Intent(this,BrowserCastService.class).setAction(KEEP));ensureForeground();acquireLocks();}
        catch(RuntimeException failure){publish(DlnaSession.Snapshot.idle("浏览器投屏前台服务启动未完成"));releaseLocks();stopSelf();return;}
        publish(DlnaSession.Snapshot.opening(request.identity,request.series.optString("title"),"苹果／浏览器接收端",request.current,request.quality,request.autoplay));
        connecting=lifecycle.submit(()->{
            BrowserCastServer madeServer=null;BrowserCastSession madeSession=null;FileSource fileSource=null;
            try{
                Map<String,byte[]> assets=new HashMap<>();assets.put("/",readAsset("receiver/index.html"));assets.put("/receiver.js",readAsset("receiver/receiver.js"));assets.put("/receiver.css",readAsset("receiver/receiver.css"));
                AtomicReference<BrowserCastSession> ref=new AtomicReference<>();
                madeServer=new BrowserCastServer(InetAddress.getByName(request.bind),assets,new BrowserCastServer.Handler(){
                    public JSONObject state(){BrowserCastSession value=ref.get();return value==null?new JSONObject():value.receiverState();}
                    public void event(JSONObject event){BrowserCastSession value=ref.get();if(value!=null)value.receiverEvent(event);}
                    public void command(JSONObject command){BrowserCastSession value=ref.get();if(value!=null)value.receiverCommand(command);}
                    public void paired(String name){BrowserCastSession value=ref.get();if(value!=null)value.receiverPaired(name);}
                });
                fileSource=new FileSource(request);
                BrowserCastSession created=new BrowserCastSession(new RuntimeScheduler(),fileSource,madeServer,value->main.post(()->accept(token,value)),request.identity,request.series.optString("title"),request.episodes.size(),request.quality,request.autoplay,request.cleanup);
                madeSession=created;ref.set(created);final BrowserCastServer supply=madeServer;final FileSource source=fileSource;
                main.post(()->{if(destroyed||token!=serial){created.close();return;}session=created;server=supply;diagnosticReader=()->supply.diagnostics()+"\n"+source.diagnostics()+"\n"+created.diagnostics();created.start(request.current,request.resume);});
            }catch(Exception failure){if(madeSession!=null)madeSession.close();else{if(madeServer!=null)madeServer.close();if(fileSource!=null)fileSource.close();}main.post(()->{if(token==serial&&!destroyed){publish(DlnaSession.Snapshot.idle("浏览器投屏连接未完成："+cleanMessage(failure)));releaseLocks();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}});}
        });
    }
    private byte[] readAsset(String name)throws IOException{try(InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>256*1024)throw new IOException("接收页面过大");out.write(buffer,0,n);}return out.toByteArray();}}
    private void accept(int token,DlnaSession.Snapshot value){
        if(destroyed||token!=serial)return;
        if(!value.active&&pending!=null){session=null;server=null;Request next=pending;pending=null;begin(next);return;}
        publish(value);
        if(!value.active){session=null;server=null;releaseLocks();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    }
    private void publish(DlnaSession.Snapshot value){snapshot=value;main.post(()->{if(destroyed||value!=snapshot)return;for(Listener listener:listeners)try{listener.onSnapshot(value);}catch(RuntimeException ignored){}if(value.active)ensureForeground();});}
    public void select(int value){BrowserCastSession current=session;if(current!=null)current.select(value);}
    public void play(){BrowserCastSession current=session;if(current!=null)current.play();}
    public void pause(){BrowserCastSession current=session;if(current!=null)current.pause();}
    public void seek(long value){BrowserCastSession current=session;if(current!=null)current.seek(value);}
    public void setAutoplay(boolean value){BrowserCastSession current=session;if(current!=null)current.setAutoplay(value);}
    public void stopCast(){pending=null;if(connecting!=null)connecting.cancel(true);BrowserCastSession current=session;if(current!=null)current.stop();else{++serial;server=null;publish(DlnaSession.Snapshot.idle("本机供流已关闭"));releaseLocks();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}
    private final class FileSource implements DlnaSession.Source {
        private final Request request;private final PlaybackCache cache=PlaybackCache.get(BrowserCastService.this);private final LibraryStore store=new LibraryStore(BrowserCastService.this);
        private final PlaybackCache.QueueWindow window;
        private final DlnaPrefetch prefetch;
        FileSource(Request request){
            this.request=request;
            window=cache.openQueueWindow(request.series,request.episodes,request.current,request.cleanup,DlnaPrefetch.AHEAD,true);
            prefetch=new DlnaPrefetch(request.episodes.size(),this::prepareMedia);
        }
        public void selecting(int position){window.moveTo(position);prefetch.selecting(position);}
        public DlnaSession.Prepared prepare(int position,String quality,java.util.function.BooleanSupplier alive)throws Exception{return prefetch.prepare(position,quality,alive);}
        public void playbackConfirmed(int position,String quality,boolean autoplay){window.moveTo(position);prefetch.confirmed(position,quality,autoplay);}
        public void autoplayChanged(boolean value){prefetch.autoplay(value);}
        public void close(){prefetch.close();window.close();}
        String diagnostics(){return prefetch.diagnostics();}
        private DlnaSession.Prepared prepareMedia(int position,String initial,boolean foreground,java.util.function.BooleanSupplier alive)throws Exception {
            JSONObject episode=request.episodes.get(position),owner=PlaybackPlaylist.owner(request.series,episode);String sid=owner.optString("seriesId"),vid=episode.optString("vid");int index=episode.optInt("index");
            AdaptiveQuality.Result<DlnaSession.Prepared> result=AdaptiveQuality.prepare(initial,quality->{
                if(!alive.getAsBoolean())throw new InterruptedIOException("投屏准备已取消");
                if(quality.equals("auto")){
                    JSONArray rows=store.downloads();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;JSONObject series=row.optJSONObject("series"),item=row.optJSONObject("episode");if(series==null||item==null||!sid.equals(series.optString("seriesId"))||!vid.equals(item.optString("vid")))continue;File manual=LibraryStore.preparedFile(BrowserCastService.this,row);if(manual!=null)try{DlnaSessionFile.validate(manual);return new DlnaSession.Prepared(manual,title(episode),quality,index,()->{},()->{});}catch(InterruptedIOException cancelled){throw cancelled;}catch(IOException invalidManual){/* Keep the manual file and try an owned cache/source copy. */}}
                }
                cache.protect(sid,vid,index,quality);boolean success=false;
                try{
                    File cached=cache.find(sid,vid,index,quality);
                    final PlaybackSources.Plan plan=cached==null?PlaybackSources.resolve(sid,vid,quality):null;
                    File file=DlnaSessionFile.prepare(
                        ()->cached!=null?cached:fetch(sid,vid,index,plan.url,quality,foreground),
                        cached!=null?quality.equals("auto"):plan.source.equals("web"),
                        ()->{PlaybackSources.Plan app=PlaybackSources.recover(sid,vid,quality,true);return fetch(sid,vid,index,app.url,quality,foreground);},
                        ()->cache.invalidate(sid,vid,index,quality),alive);
                    if(!alive.getAsBoolean())throw new InterruptedIOException("投屏准备已取消");
                    DlnaSession.Prepared prepared=new DlnaSession.Prepared(file,title(episode),quality,index,()->cache.protect(sid,vid,index,quality),()->releaseLease(sid,vid,index,quality));success=true;return prepared;
                }finally{if(!success)releaseLease(sid,vid,index,quality);}
            },PlaybackSources::canLowerQuality,alive::getAsBoolean,(quality,stage,error)->{},DlnaSession.Prepared::releaseOwner);
            return result.value;
        }
        private File fetch(String sid,String vid,int index,String url,String quality,boolean foreground)throws Exception {
            return foreground?cache.fetchForeground(sid,vid,index,url,quality):cache.fetch(sid,vid,index,url,quality);
        }
        private String title(JSONObject episode){return PlaybackPlaylist.owner(request.series,episode).optString("title")+" · 第 "+episode.optInt("index")+" 集";}
        private void releaseLease(String sid,String vid,int index,String quality){cache.release(sid,vid,index,quality);window.prune();}
        public void confirmed(int position,long progress,boolean cleanup)throws Exception{JSONObject episode=request.episodes.get(position);store.history(PlaybackPlaylist.owner(request.series,episode),episode,progress);window.moveTo(position);if(cleanup)window.prune();}
        public void progress(int position,long progress){progress(position,progress,0);}
        public void progress(int position,long progress,long duration){if(position>=0&&position<request.episodes.size()){JSONObject episode=request.episodes.get(position);store.history(PlaybackPlaylist.owner(request.series,episode),episode,progress,Math.max(0,duration));}}
    }
    private static final class RuntimeScheduler implements DlnaSession.Scheduler {
        private final ScheduledExecutorService control=Executors.newSingleThreadScheduledExecutor();
        private final ThreadPoolExecutor prepare=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4));
        public boolean execute(Runnable action){try{control.execute(action);return true;}catch(RejectedExecutionException closed){return false;}}
        public DlnaSession.Task prepare(Runnable action){prepare.purge();Future<?> future=prepare.submit(action);return ()->{future.cancel(true);if(future instanceof Runnable)prepare.remove((Runnable)future);};}
        public DlnaSession.Task repeat(Runnable action,long ms){ScheduledFuture<?> future=control.scheduleWithFixedDelay(action,ms,ms,TimeUnit.MILLISECONDS);return ()->future.cancel(false);}
        public long nowMs(){return System.nanoTime()/1000000L;}
        public void close(){prepare.shutdownNow();control.shutdown();}
    }
    private void ensureForeground(){
        if(destroyed)return;
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(!channelCreated){manager.createNotificationChannel(new NotificationChannel(CHANNEL,"红果浏览器投屏",NotificationManager.IMPORTANCE_LOW));channelCreated=true;}
        PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,BrowserCastService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("红果 · 浏览器投屏").setContentText(snapshot.deviceName+" · "+snapshot.title).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"停止投屏",stop).build()).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(NOTIFICATION,notification);
    }
    private void acquireLocks(){
        if(cpu==null){PowerManager manager=(PowerManager)getSystemService(POWER_SERVICE);cpu=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"hongguo:browser-cast");cpu.setReferenceCounted(false);cpu.acquire();}
        WifiManager manager=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if(manager!=null&&wifi==null){wifi=manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"hongguo:browser-cast");wifi.setReferenceCounted(false);wifi.acquire();}
    }
    private void releaseLocks(){if(wifi!=null){if(wifi.isHeld())wifi.release();wifi=null;}if(cpu!=null){if(cpu.isHeld())cpu.release();cpu=null;}}
    private static String cleanMessage(Exception failure){String text=failure.getMessage();if(text==null)return "操作未完成";text=text.replaceAll("https?://[^\\s\"'<>]+","[地址]").replaceAll("[\\p{Cntrl}]"," ");return text.substring(0,Math.min(160,text.length()));}
    @Override public void onDestroy(){destroyed=true;++serial;pending=null;if(connecting!=null)connecting.cancel(true);BrowserCastSession current=session;session=null;server=null;if(current!=null)current.close();snapshot=DlnaSession.Snapshot.idle("本机浏览器投屏服务已关闭");lifecycle.shutdownNow();releaseLocks();listeners.clear();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
