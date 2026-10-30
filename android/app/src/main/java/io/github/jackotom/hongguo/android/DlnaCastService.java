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
import org.json.*;

/** Started foreground service owns local supply/control, never the player Activity. */
public final class DlnaCastService extends Service {
    private static final String KEEP="io.github.jackotom.hongguo.DLNA_KEEP",STOP="io.github.jackotom.hongguo.DLNA_STOP",CHANNEL="hongguo_dlna";
    private static final int NOTIFICATION=4202;
    public interface Listener { void onSnapshot(DlnaSession.Snapshot snapshot); }
    public final class LocalBinder extends Binder { public DlnaCastService service(){return DlnaCastService.this;} }
    private final LocalBinder binder=new LocalBinder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners=new CopyOnWriteArraySet<>();
    private final ExecutorService lifecycle=Executors.newSingleThreadExecutor();
    private volatile DlnaSession session;
    private volatile java.util.function.Supplier<String> diagnosticReader;
    private volatile DlnaSession.Snapshot snapshot=DlnaSession.Snapshot.idle("");
    private volatile boolean destroyed;private boolean channelCreated;private int serial;private Future<?> connecting;
    private PowerManager.WakeLock cpu;private WifiManager.WifiLock wifi;private WifiManager.MulticastLock multicast;
    private Request pending;
    private static final class Request {
        final DlnaDevice device;final JSONObject series;final List<JSONObject> episodes;final int current;final String quality,identity;final boolean autoplay,cleanup;final long resume;final int mergeWindow;
        Request(DlnaDevice device,JSONObject series,List<JSONObject> episodes,int current,String quality,boolean autoplay,boolean cleanup,long resume,int mergeWindow)throws JSONException{this.device=device;this.series=series;this.episodes=episodes;this.current=current;this.quality=quality;this.autoplay=autoplay;this.cleanup=cleanup;this.resume=resume;this.mergeWindow=Math.max(1,mergeWindow);this.identity=PlaybackPlaylist.identity(series,episodes);}
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){if(intent!=null&&STOP.equals(intent.getAction()))stopCast();else if(snapshot.active)ensureForeground();else{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);}return START_NOT_STICKY;}
    public DlnaSession.Snapshot snapshot(){return snapshot;}
    /** Read counters only; never sends a control request or changes the current cast. */
    public String diagnostics(){java.util.function.Supplier<String> reader=diagnosticReader;return reader==null?"控制/供流：会话尚未连接":reader.get();}
    public void addListener(Listener listener){if(listener!=null){listeners.add(listener);main.post(()->{if(listeners.contains(listener))listener.onSnapshot(snapshot);});}}
    public void removeListener(Listener listener){listeners.remove(listener);}
    public void connect(DlnaDevice device,JSONObject series,JSONArray episodes,int selectedPosition,String preferredQuality,boolean autoplay,boolean cleanup,long resumeMs,int mergeWindow) {
        try {
            if(device==null||episodes==null||selectedPosition<0||selectedPosition>=episodes.length())throw new IOException("请选择有效设备和分集");
            JSONObject cleanSeries=LibraryStore.series(series),selected=episodes.getJSONObject(selectedPosition);
            JSONArray clean=PlaybackPlaylist.normalize(cleanSeries,episodes,selected);List<JSONObject> queue=new ArrayList<>();
            for(int i=0;i<clean.length();i++)queue.add(clean.getJSONObject(i));
            int position=PlaybackPlaylist.find(queue,PlaybackPlaylist.owner(cleanSeries,selected).getString("seriesId"),selected.getString("vid"),selected.getInt("index"),cleanSeries);
            if(position<0)throw new IOException("分集不在队列中");
            Request request=new Request(device,LibraryStore.series(PlaybackPlaylist.owner(cleanSeries,selected)),Collections.unmodifiableList(queue),position,MediaQuality.normalize(preferredQuality),autoplay,cleanup,Math.max(0,resumeMs),mergeWindow);
            if(destroyed)return;
            if(session!=null&&session.snapshot().active){pending=request;session.stop();return;}
            begin(request);
        }catch(Exception failure){String error="投屏启动未完成："+cleanMessage(failure);publish(snapshot.active?snapshot.withError(error):DlnaSession.Snapshot.idle(error));}
    }
    private void begin(Request request) {
        final int token=++serial;pending=null;diagnosticReader=null;
        if(connecting!=null)connecting.cancel(true);
        try {startForegroundService(new Intent(this,DlnaCastService.class).setAction(KEEP));ensureForeground();acquireLocks();}
        catch(RuntimeException failure){publish(DlnaSession.Snapshot.idle("投屏前台服务启动未完成"));releaseLocks();stopSelf();return;}
        publish(DlnaSession.Snapshot.opening(request.identity,request.series.optString("title"),request.device.name,request.current,request.quality,request.autoplay));
        connecting=lifecycle.submit(()->{
            DlnaMediaServer mediaServer=null;DlnaSession made=null;
            try {
                InetAddress tv=privateAddress(request.device.host);InetAddress bind;
                try(DatagramSocket route=new DatagramSocket()){route.connect(tv,9);bind=route.getLocalAddress();}
                mediaServer=new DlnaMediaServer(bind,tv);final DlnaMediaServer server=mediaServer;
                DlnaControl remote=new DlnaControl(request.device);
                DlnaSession.Controller controller=new DlnaSession.Controller(){
                    public void setMedia(String url,String title)throws Exception{remote.setMedia(url,title);}public void play()throws Exception{remote.play();}public void pause()throws Exception{remote.pause();}public void stop()throws Exception{remote.stop();}public void seek(long ms)throws Exception{remote.seek(ms);}
                    public DlnaSession.Status status()throws Exception{DlnaControl.Status status=remote.status();return new DlnaSession.Status(status.state,status.mediaUri,status.positionMs,status.durationMs);}
                };
                DlnaSession.Server supply=new DlnaSession.Server(){
                    public DlnaSession.Publication publish(DlnaSession.Prepared media)throws Exception{DlnaMediaServer.Publication item=server.publish(media.file,media.title,media.retain,media.release);return new DlnaSession.Publication(item.url,item);}
                    public void revoke(DlnaSession.Publication item){server.revoke((DlnaMediaServer.Publication)item.handle);}public void close(){server.close();}
                };
                FileSource fileSource=new FileSource(request);
                DlnaSession created=new DlnaSession(new RuntimeScheduler(),controller,fileSource,supply,value->main.post(()->accept(token,value)),request.identity,request.series.optString("title"),request.device.name,request.episodes.size(),request.quality,request.autoplay,request.cleanup);made=created;
                main.post(()->{if(destroyed||token!=serial){created.close();return;}diagnosticReader=()->remote.diagnostics()+"\n"+server.diagnostics()+"\n"+fileSource.diagnostics()+"\n"+created.diagnostics();session=created;created.start(request.current,request.resume);});
            }catch(Exception failure){if(made!=null)made.close();else if(mediaServer!=null)mediaServer.close();main.post(()->{if(token==serial&&!destroyed){publish(DlnaSession.Snapshot.idle("投屏连接未完成："+cleanMessage(failure)));releaseLocks();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}});}
        });
    }
    private void accept(int token,DlnaSession.Snapshot value){
        if(destroyed||token!=serial)return;
        if(!value.active&&pending!=null){session=null;Request next=pending;pending=null;begin(next);return;}
        publish(value);
        if(!value.active){session=null;releaseLocks();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    }
    private void publish(DlnaSession.Snapshot value){snapshot=value;main.post(()->{if(destroyed||value!=snapshot)return;for(Listener listener:listeners)try{listener.onSnapshot(value);}catch(RuntimeException ignored){}if(value.active)ensureForeground();});}
    public void select(int queuePosition){DlnaSession current=session;if(current!=null)current.select(queuePosition);}
    public void play(){DlnaSession current=session;if(current!=null)current.play();}
    public void pause(){DlnaSession current=session;if(current!=null)current.pause();}
    public void seek(long ms){DlnaSession current=session;if(current!=null)current.seek(ms);}
    public void setAutoplay(boolean value){DlnaSession current=session;if(current!=null)current.setAutoplay(value);}
    public void stopCast(){pending=null;if(connecting!=null)connecting.cancel(true);DlnaSession current=session;if(current!=null)current.stop();else{++serial;publish(DlnaSession.Snapshot.idle("本机供流已关闭"));releaseLocks();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}
    private final class FileSource implements DlnaSession.Source {
        private final Request request;private final PlaybackCache cache=PlaybackCache.get(DlnaCastService.this);private final LibraryStore store=new LibraryStore(DlnaCastService.this);
        private final PlaybackCache.QueueWindow window;
        private final DlnaPrefetch prefetch;
        private final File mergedDirectory=new File(getCacheDir(),"hongguo-dlna-merged");
        /** Prepared window timing: maps a merged-file position back to a real queue position. */
        private volatile WindowMap pendingMap,activeMap;
        FileSource(Request request){
            this.request=request;
            window=cache.openQueueWindow(request.series,request.episodes,request.current,request.cleanup,DlnaPrefetch.AHEAD,true);
            prefetch=new DlnaPrefetch(request.episodes.size(),this::prepareMedia);
        }
        public void selecting(int position){pendingMap=null;activeMap=null;window.moveTo(position);prefetch.selecting(position);}
        public DlnaSession.Prepared prepare(int position,String quality,java.util.function.BooleanSupplier alive)throws Exception{
            return request.mergeWindow>1?prepareMerged(position,quality,alive):prefetch.prepare(position,quality,alive);
        }
        /** One published URI covers up to mergeWindow episodes; incompatible encodings fall back to a single episode. */
        private DlnaSession.Prepared prepareMerged(int position,String requested,java.util.function.BooleanSupplier alive)throws Exception {
            int end=Math.min(position+request.mergeWindow,request.episodes.size());
            java.util.List<DlnaSession.Prepared> parts=new ArrayList<>();
            boolean assembled=false;File merged=null;
            try {
                for (int p=position;p<end;p++) {
                    if(!alive.getAsBoolean())throw new InterruptedIOException("投屏准备已取消");
                    parts.add(prepareMedia(p,requested,p==position,alive));
                }
                if (parts.size()<2) { DlnaSession.Prepared only=parts.get(0); parts.clear(); assembled=true; return only; }
                if (!mergedDirectory.exists()&&!mergedDirectory.mkdirs()) throw new IOException("合并目录创建失败");
                // A retry can overlap an old published URI at the same queue position.
                merged=new File(mergedDirectory, "window-"+UUID.randomUUID()+".mp4");
                java.util.List<Mp4Concat.Timing> timings;
                try { timings=Mp4Concat.concat(filesOf(parts),merged); }
                catch (IOException incompatible) {
                    if (!String.valueOf(incompatible.getMessage()).startsWith(Mp4Concat.INCOMPATIBLE_PREFIX)) { if (merged.exists()&&!merged.delete()) throw new IOException("合并临时文件清理失败"); throw incompatible; }
                    // No silent quality mixing: incompatible episodes keep the existing per-episode flow.
                    if (!merged.exists()||merged.delete()) {
                        DlnaSession.Prepared only=parts.get(0); parts.remove(0); assembled=true;
                        for (DlnaSession.Prepared rest : parts) rest.releaseOwner();
                        parts.clear();
                        return only;
                    }
                    throw new IOException("合并临时文件清理失败");
                }
                if(!alive.getAsBoolean())throw new InterruptedIOException("投屏准备已取消");
                long[] starts=new long[timings.size()+1];
                for (int i=0;i<timings.size();i++) starts[i+1]=starts[i]+timings.get(i).durationMs;
                pendingMap=new WindowMap(position,starts);
                JSONObject first=request.episodes.get(position);
                int lastIndex=position+parts.size()-1;JSONObject last=request.episodes.get(lastIndex);
                String title=PlaybackPlaylist.owner(request.series,first).optString("title")+" · 第 "+first.optInt("index")+"-"+last.optInt("index")+" 集（合并"+parts.size()+"集）";
                DlnaSession.Prepared result=DlnaMergedLease.prepare(merged,title,parts);
                parts.clear();assembled=true;
                return result;
            } finally {
                if (!assembled) {
                    for (DlnaSession.Prepared part:parts) part.releaseOwner();
                    if(merged!=null){DlnaMergedLease.delete(merged);DlnaMergedLease.delete(new File(merged.getPath()+".part"));}
                }
            }
        }
        private java.util.List<File> filesOf(java.util.List<DlnaSession.Prepared> parts){ java.util.List<File> files=new ArrayList<>(); for (DlnaSession.Prepared part:parts) files.add(part.file); return files; }
        public void close(){prefetch.close();window.close();pendingMap=null;activeMap=null;}
        public void playbackConfirmed(int position,String quality,boolean autoplay){window.moveTo(position);prefetch.confirmed(position,quality,autoplay);}
        public void autoplayChanged(boolean value){prefetch.autoplay(value);}
        String diagnostics(){return prefetch.diagnostics();}
        private DlnaSession.Prepared prepareMedia(int position,String initial,boolean foreground,java.util.function.BooleanSupplier alive)throws Exception {
            JSONObject episode=request.episodes.get(position),owner=PlaybackPlaylist.owner(request.series,episode);String sid=owner.optString("seriesId"),vid=episode.optString("vid");int index=episode.optInt("index");
            AdaptiveQuality.Result<DlnaSession.Prepared> result=AdaptiveQuality.prepare(initial,quality->{
                if(!alive.getAsBoolean())throw new InterruptedIOException("投屏准备已取消");
                if(quality.equals("auto")){
                    JSONArray rows=store.downloads();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;JSONObject series=row.optJSONObject("series"),item=row.optJSONObject("episode");if(series==null||item==null||!sid.equals(series.optString("seriesId"))||!vid.equals(item.optString("vid")))continue;File manual=LibraryStore.preparedFile(DlnaCastService.this,row);if(manual!=null)try{DlnaSessionFile.validate(manual);return new DlnaSession.Prepared(manual,title(episode),quality,index,()->{},()->{});}catch(InterruptedIOException cancelled){throw cancelled;}catch(IOException invalidManual){/* Keep the manual file and try an owned cache/source copy. */}}
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
        public void confirmed(int position,long progress,boolean cleanup)throws Exception{
            activeMap=pendingMap!=null&&pendingMap.position==position?pendingMap:null;pendingMap=null;
            int[] located=locate(position,progress);JSONObject episode=request.episodes.get(located[0]);
            store.history(PlaybackPlaylist.owner(request.series,episode),episode,located[1]);
            window.moveTo(located[0]);if(cleanup)window.prune();
        }
        public void progress(int position,long progress){progress(position,progress,0);}
        public void progress(int position,long progress,long duration){
            if(position<0||position>=request.episodes.size())return;
            WindowMap map=activeMap;int[] located=locate(position,progress,map);JSONObject episode=request.episodes.get(located[0]);long episodeDuration=Math.max(0,duration);
            if(map!=null&&map.position==position){int offset=located[0]-position;episodeDuration=offset>=0&&offset+1<map.starts.length?Math.max(0,map.starts[offset+1]-map.starts[offset]):0;}
            store.history(PlaybackPlaylist.owner(request.series,episode),episode,located[1],episodeDuration);
        }
        private int[] locate(int position,long progress){return locate(position,progress,activeMap);}
        private int[] locate(int position,long progress,WindowMap map){
            if (map==null||map.position!=position) return new int[]{position,(int)Math.min(Integer.MAX_VALUE,Math.max(0,progress))};
            long clamped=Math.min(map.starts[map.starts.length-1],Math.max(0,progress));int index=0;
            for (int i=0;i<map.starts.length-1;i++) if (clamped>=map.starts[i]) index=i;
            long offset=clamped-map.starts[index];
            int queue=position+index;
            return queue<request.episodes.size()?new int[]{queue,(int)Math.min(Integer.MAX_VALUE,Math.max(0,offset))}:new int[]{position,(int)Math.min(Integer.MAX_VALUE,clamped)};
        }
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
    /** Immutable mapping from a merged media position to queue positions and per-episode offsets. */
    private static final class WindowMap { final int position; final long[] starts; WindowMap(int position,long[] starts){this.position=position;this.starts=starts;} }
    private static InetAddress privateAddress(String host)throws Exception{if(!DlnaNetwork.isPrivateIpv4(host))throw new IOException("设备不在支持的局域网 IPv4 地址范围");String[] parts=host.split("\\.");byte[] bytes=new byte[4];for(int i=0;i<4;i++)bytes[i]=(byte)Integer.parseInt(parts[i]);return InetAddress.getByAddress(bytes);}
    private void ensureForeground(){
        if(destroyed)return;
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(!channelCreated){manager.createNotificationChannel(new NotificationChannel(CHANNEL,"红果投屏",NotificationManager.IMPORTANCE_LOW));channelCreated=true;}
        PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,DlnaCastService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String text=snapshot.title.isEmpty()?"正在连接电视":snapshot.deviceName+" · "+snapshot.title;
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("红果 · 局域网投屏").setContentText(text).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"停止投屏",stop).build()).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(NOTIFICATION,notification);
    }
    private void acquireLocks(){
        if(cpu==null){PowerManager manager=(PowerManager)getSystemService(POWER_SERVICE);cpu=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"hongguo:dlna");cpu.setReferenceCounted(false);cpu.acquire();}
        WifiManager manager=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if(manager!=null){if(wifi==null){wifi=manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"hongguo:dlna");wifi.setReferenceCounted(false);wifi.acquire();}if(multicast==null){multicast=manager.createMulticastLock("hongguo:dlna");multicast.setReferenceCounted(false);multicast.acquire();}}
    }
    private void releaseLocks(){if(multicast!=null){if(multicast.isHeld())multicast.release();multicast=null;}if(wifi!=null){if(wifi.isHeld())wifi.release();wifi=null;}if(cpu!=null){if(cpu.isHeld())cpu.release();cpu=null;}}
    private static String cleanMessage(Exception failure){String text=failure.getMessage();if(text==null)return "操作未完成";text=text.replaceAll("https?://[^\\s\"'<>]+","[地址]").replaceAll("[\\p{Cntrl}]"," ");return text.substring(0,Math.min(160,text.length()));}
    @Override public void onDestroy(){destroyed=true;++serial;pending=null;if(connecting!=null)connecting.cancel(true);DlnaSession current=session;session=null;if(current!=null){current.close();snapshot=current.snapshot();}else snapshot=DlnaSession.Snapshot.idle("本机投屏服务已关闭");lifecycle.shutdownNow();releaseLocks();listeners.clear();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
