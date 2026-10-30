// SPDX-License-Identifier: GPL-3.0-only
package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.os.Handler;
import java.util.*;
import java.util.concurrent.*;

/** One cancellable lane, bounded near-playhead cache, and generation ownership independent of source/media work. */
final class DanmakuController {
    interface Listener {void notice(String value);}
    interface Source {DanmakuClient.Result window(String sid,String vid,long start,long duration)throws Exception;}
    private final Context context;private final Handler main;private final DanmakuOverlay view;private final Listener listener;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1));
    private final LinkedHashMap<Long,Window> cache=new LinkedHashMap<Long,Window>(16,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<Long,Window> e){return size()>8;}};
    private final Source source;
    private DanmakuClient client;private Future<?> pending;private String sid="",vid="";private int generation,epoch;private long pendingWindow=-1,lastPosition=-1,retryAt;private boolean enabled,closed;
    private static final class Window {final DanmakuClient.Result result;final long at;Window(DanmakuClient.Result result){this.result=result;this.at=System.nanoTime();}}
    DanmakuController(Context context,Handler main,DanmakuOverlay view,Listener listener){this(context,main,view,listener,null);}
    DanmakuController(Context context,Handler main,DanmakuOverlay view,Listener listener,Source source){this.context=context==null?null:context.getApplicationContext();this.main=main;this.view=view;this.listener=listener;this.source=source==null?(sid,vid,start,duration)->{if(client==null)client=new DanmakuClient(this.context);return client.window(sid,vid,start,duration);}:source;}
    void update(String series,String video,int token,long position,long duration,boolean available,boolean on){
        if(closed)return;
        if(token!=generation||!sid.equals(series)||!vid.equals(video)){cancel();generation=token;sid=series;vid=video;cache.clear();view.clear();lastPosition=-1;retryAt=0;}
        if(!available||!on){if(enabled||pending!=null){cancel();view.enabled(false);}enabled=false;return;}
        if(!enabled){enabled=true;view.enabled(true);retryAt=0;listener.notice("正在读取弹幕…");}
        if(lastPosition>=0&&(position<lastPosition-500||position>lastPosition+5000)){cancel();view.seek(position);retryAt=0;}
        lastPosition=position;view.pulse();long window=position/DanmakuClient.WINDOW_MS*DanmakuClient.WINDOW_MS;
        Window saved=cache.get(window);if(saved!=null&&System.nanoTime()-saved.at<300_000_000_000L){merge(position);return;}
        if(pendingWindow==window||System.nanoTime()<retryAt)return;
        cancel();final int expected=epoch;final String targetSid=sid,targetVid=vid;pendingWindow=window;listener.notice("正在读取弹幕…");
        try{pending=worker.submit(()->{try{DanmakuClient.Result result=source.window(targetSid,targetVid,window,Math.max(1,Math.min(86400000,duration)));main.post(()->{if(closed||expected!=epoch||!enabled)return;pending=null;pendingWindow=-1;cache.put(window,new Window(result));listener.notice(result.notice.isEmpty()?(result.items.isEmpty()?"当前时间段暂无弹幕":"弹幕已开启"):result.notice);merge(lastPosition);});}catch(Exception error){main.post(()->{if(closed||expected!=epoch||!enabled)return;pending=null;pendingWindow=-1;retryAt=System.nanoTime()+30_000_000_000L;listener.notice("弹幕暂未载入；播放不受影响，可关闭后重新开启重试");});}});}catch(RejectedExecutionException busy){pending=null;pendingWindow=-1;retryAt=System.nanoTime()+2_000_000_000L;listener.notice("弹幕正在切换，请稍后");}
    }
    private void merge(long position){List<DanmakuClient.Item> items=new ArrayList<>();for(Map.Entry<Long,Window> row:cache.entrySet())if(row.getKey()>=position-40000&&row.getKey()<=position+DanmakuClient.WINDOW_MS)items.addAll(row.getValue().result.items);view.items(items,position);}
    private void cancel(){epoch++;if(pending!=null)pending.cancel(true);pending=null;pendingWindow=-1;worker.purge();}
    void suspend(){cancel();enabled=false;view.enabled(false);}
    void close(){if(closed)return;closed=true;suspend();cache.clear();worker.shutdownNow();}
}
