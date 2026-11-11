package io.github.jackotom.hongguo.android;

import android.webkit.JavascriptInterface;
import java.util.concurrent.*;
import org.json.*;

/** Local-page-only cancellable read bridge; no account, URL or write API. */
final class OfficialSeriesBridge {
    private final MainActivity host;private final OfficialSeriesService service;private volatile boolean closed,paused;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2));
    private final ConcurrentHashMap<String,Job> jobs=new ConcurrentHashMap<>();
    OfficialSeriesBridge(MainActivity host){this.host=host;service=new OfficialSeriesService(host);}
    @JavascriptInterface public void postMessage(String source){
        if(source==null||source.length()>4096)return;
        try{JSONObject request=new JSONObject(source);Object raw=request.opt("id");String method=request.optString("method");if(!(raw instanceof String)||((String)raw).length()>128||!(method.equals("load")||method.equals("cancel")))return;String id=(String)raw;
            host.runOnUiThread(()->{if(closed||!host.trusted())return;if(paused){if(method.equals("load"))respondCancelled(id);return;}try{if(method.equals("cancel")){String target=request.optString("requestId");Job job=jobs.get(target);if(job!=null)job.stop();return;}
                Object value=request.opt("seriesId");if(!(value instanceof String)||!OfficialSeasons.id((String)value))throw new Exception("系列剧ID无效");Job job=new Job(id,(String)value);if(jobs.putIfAbsent(id,job)!=null)throw new Exception("系列剧请求编号重复");try{worker.execute(job.task);}catch(RejectedExecutionException busy){job.stop();respond(id,null,"系列剧请求较多，请稍后重试");}
            }catch(Exception error){respond(id,null,"系列剧请求未完成，请重试");}});
        }catch(JSONException ignored){}
    }
    private final class Job {
        final String id;final FutureTask<Void> task;volatile OfficialApiClient client;
        Job(String id,String sid){this.id=id;task=new FutureTask<Void>(()->{
            try{if(closed||Thread.currentThread().isInterrupted())return null;JSONObject value=service.load(sid,c->{client=c;if(closed||Thread.currentThread().isInterrupted())c.close();});if(!closed&&!Thread.currentThread().isInterrupted())deliver(value,null);}
            catch(Exception error){if(!closed&&!Thread.currentThread().isInterrupted())deliver(null,"官方分季读取未完成，可重试或按标题查找");}
            finally{client=null;if(closed||Thread.currentThread().isInterrupted())jobs.remove(id,Job.this);}return null;
        }){@Override protected void done(){if(isCancelled()||closed)jobs.remove(id,Job.this);worker.remove(this);}};}
        void deliver(JSONObject value,String error){host.runOnUiThread(()->{try{if(!closed&&!paused&&!task.isCancelled()&&jobs.get(id)==this&&host.trusted())respond(id,value,error);}finally{jobs.remove(id,this);}});}
        void stop(){OfficialApiClient value=client;if(value!=null)value.close();task.cancel(true);worker.remove(task);jobs.remove(id,this);}
    }
    private void respond(String id,JSONObject result,String error){if(closed)return;try{host.script("window.__androidSeriesResponse && window.__androidSeriesResponse("+new JSONObject().put("id",id).put("result",result==null?JSONObject.NULL:result).put("error",error==null?JSONObject.NULL:error)+")");}catch(JSONException ignored){}}
    private void respondCancelled(String id){try{host.script("window.__androidSeriesResponse && window.__androidSeriesResponse("+new JSONObject().put("id",id).put("error","分季读取已暂停").put("errorCode","ERR_CANCELED")+")");}catch(JSONException ignored){}}
    void resume(){paused=false;}
    void suspend(){paused=true;for(Job job:jobs.values())job.stop();jobs.clear();}
    void close(){closed=true;suspend();worker.shutdownNow();}
}
