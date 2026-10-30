package io.github.jackotom.hongguo.android;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Browser-only coordinator. It never interprets a DLNA transport state as browser EOF. */
final class BrowserCastSession implements AutoCloseable {
    private static final long HEARTBEAT_TIMEOUT_MS=15000, MAX_MEDIA_TIME_MS=24L*60*60*1000;
    private final DlnaSession.Scheduler scheduler;
    private final DlnaSession.Source source;
    private final DlnaSession.Server server;
    private final DlnaSession.Listener listener;
    private final String sid,title,sessionId=UUID.randomUUID().toString(),preferred;
    private final int count;
    private final boolean cleanup;
    private final AtomicLong generation=new AtomicLong(),intentEpoch=new AtomicLong(),heartbeat=new AtomicLong(-1);
    private final AtomicBoolean closing=new AtomicBoolean(),startRequested=new AtomicBoolean();
    private final List<DlnaSession.Publication> exposed=new ArrayList<>();
    // All mutable state below is owned by scheduler.execute/repeat. Published strings/DTOs are immutable.
    private volatile DlnaSession.Snapshot snapshot=DlnaSession.Snapshot.idle("");
    private volatile String json="{}",diagnostic="浏览器接收：尚未开始";
    private volatile boolean closed;
    private boolean started,paired,preparing,autoplay,desiredPlaying=true,confirmed,seenPlaying,forwardProgress,seeking,ended,disconnected;
    private int current=-1,target;
    private long appliedIntent,selectedGeneration,mediaGeneration,commandId,revision,lastEventSeq,position,duration,adoptedPosition,adoptedDuration,resume,seekMs=-1,lastHistory=-1,progressAnchor=-1;
    private String state="IDLE",error="",device="浏览器接收端",mediaId="",actual;
    private DlnaSession.Prepared media,adopted;
    private DlnaSession.Publication publication;
    private DlnaSession.Task preparation,watchdog;

    BrowserCastSession(DlnaSession.Scheduler scheduler,DlnaSession.Source source,DlnaSession.Server server,
                       DlnaSession.Listener listener,String sid,String title,int count,String quality,boolean autoplay,boolean cleanup) {
        if(count<1||count>1000)throw new IllegalArgumentException("投屏队列无效");
        this.scheduler=scheduler;this.source=source;this.server=server;this.listener=listener;
        this.sid=sid;this.title=title;this.count=count;preferred=MediaQuality.normalize(quality);actual=preferred;
        this.autoplay=autoplay;this.cleanup=cleanup;publish();
    }
    DlnaSession.Snapshot snapshot(){return snapshot;}
    String diagnostics(){return diagnostic;}
    JSONObject receiverState(){
        if(!closed)heartbeat.set(scheduler.nowMs());
        try{return new JSONObject(json);}catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }
    void start(int selected,long resumeMs){
        if(!valid(selected)||closing.get()||!startRequested.compareAndSet(false,true))return;
        long epoch=intentEpoch.incrementAndGet(),token=generation.incrementAndGet();
        scheduler.execute(()->{
            if(closing.get()||started)return;
            started=true;appliedIntent=epoch;selectedGeneration=token;target=selected;resume=boundedTime(resumeMs);position=resume;commandId++;
            state="WAITING_RECEIVER";watchdog=scheduler.repeat(this::checkHeartbeat,1000);publish();
            if(paired&&token==generation.get())begin(token,selected,resume,epoch);
        });
    }
    void receiverPaired(String name){
        String clean=clean(name,60);scheduler.execute(()->{
            if(closing.get())return;
            paired=true;if(!clean.isEmpty())device=clean;heartbeat.set(scheduler.nowMs());
            if(started&&media==null&&!preparing&&selectedGeneration==generation.get())begin(selectedGeneration,target,resume,appliedIntent);else publish();
        });
    }
    void select(int selected){
        if(!valid(selected)||closing.get())return;
        long epoch=intentEpoch.incrementAndGet(),token=generation.incrementAndGet();
        scheduler.execute(()->{
            if(closing.get()||token!=generation.get())return;
            appliedIntent=epoch;selectedGeneration=token;target=selected;resume=0;commandId++;
            if(!paired){desiredPlaying=true;state="WAITING_RECEIVER";publish();return;}
            begin(token,selected,0,epoch);
        });
    }
    private boolean valid(int selected){return selected>=0&&selected<count;}
    private boolean alive(long token){return !closing.get()&&!closed&&generation.get()==token;}
    private void begin(long token,int selected,long resumeMs,long epoch){
        if(!alive(token))return;
        appliedIntent=epoch;selectedGeneration=token;
        if(preparation!=null)preparation.cancel();preparation=null;
        target=selected;resume=resumeMs;desiredPlaying=true;preparing=true;media=null;publication=null;mediaId="";
        position=resumeMs;duration=0;seekMs=resumeMs>0?resumeMs:-1;confirmed=false;ended=false;disconnected=false;
        disarm();state="PREPARING";error="";publish();
        try{
            source.selecting(selected);
            final String quality=adopted==null?preferred:actual;
            preparation=scheduler.prepare(()->{
                DlnaSession.Prepared prepared=null;
                try{
                    prepared=source.prepare(selected,quality,()->alive(token));
                    if(prepared==null)throw new IOException("片源为空");
                    if(!alive(token)){prepared.releaseOwner();return;}
                    DlnaSession.Prepared result=prepared;
                    if(!scheduler.execute(()->install(token,result)))result.releaseOwner();
                }catch(Exception failure){
                    if(prepared!=null)prepared.releaseOwner();
                    scheduler.execute(()->{if(alive(token)){preparing=false;state="ERROR";error="浏览器片源准备未成功："+message(failure);publish();}});
                }
            });
        }catch(RuntimeException failure){preparing=false;state="ERROR";error="浏览器片源准备未成功："+message(failure);publish();}
    }
    private void install(long token,DlnaSession.Prepared prepared){
        try{
            if(!alive(token))return;
            if(exposed.size()>=4)throw new IOException("接收端尚未确认新视频，请停止投屏后重连");
            DlnaSession.Publication registered=server.publish(prepared);
            if(!alive(token)){server.revoke(registered);return;}
            exposed.add(registered);
            media=prepared;publication=registered;mediaGeneration=token;mediaId=UUID.randomUUID().toString();
            preparing=false;state=disconnected?"DISCONNECTED":"WAITING_RECEIVER_PLAY";error="";publish();
        }catch(Exception failure){if(alive(token)){preparing=false;state="ERROR";error="浏览器片源发布未成功："+message(failure);publish();}}
        finally{prepared.releaseOwner();}
    }
    void play(){command(()->{
        boolean alreadyPlaying=desiredPlaying&&seenPlaying&&!seeking&&!disconnected&&!ended&&"PLAYING".equals(state);
        desiredPlaying=true;commandId++;seekMs=ended?0:-1;if(ended){position=0;ended=false;}
        if(!alreadyPlaying){disarm();if(media!=null)state="WAITING_RECEIVER_PLAY";}error="";
    });}
    void pause(){command(()->{desiredPlaying=false;commandId++;seekMs=-1;disarm();if(media!=null)state="PAUSED";});}
    void seek(long value){long requested=boundedTime(value);command(()->{
        seekMs=duration>0?Math.min(requested,Math.max(0,duration-250)):requested;
        commandId++;ended=false;disarm();seeking=true;if(media!=null)state=desiredPlaying?"WAITING_RECEIVER_PLAY":"PAUSED";
    });}
    void setAutoplay(boolean value){command(()->{autoplay=value;source.autoplayChanged(value);});}
    private void command(Runnable action){
        if(closing.get())return;long epoch=intentEpoch.incrementAndGet();
        scheduler.execute(()->{if(closing.get())return;appliedIntent=epoch;action.run();publish();});
    }
    void receiverCommand(JSONObject input){
        final JSONObject command=copy(input);if(command==null||closing.get())return;
        // Cancellation is immediate even if the serial loop currently has an ended callback queued.
        final String expectedMedia=command.optString("mediaId","");
        if(!sessionId.equals(command.optString("sessionId")))return;
        String type=command.optString("type");
        if(!type.matches("play|pause|seek|next|previous|select|autoplay|stop"))return;
        if(!"stop".equals(type)&&!currentMediaId().equals(expectedMedia))return;
        final long epoch=intentEpoch.incrementAndGet();
        scheduler.execute(()->{
            if(closing.get()||(!"stop".equals(type)&&!mediaId.equals(expectedMedia)))return;
            appliedIntent=epoch;
            switch(type){
                case "stop":stop();return;
                case "next":select(target+1);return;
                case "previous":select(target-1);return;
                case "select":if(command.has("value"))select(command.optInt("value",-1));return;
                case "play":play();return;
                case "pause":pause();return;
                case "seek":if(command.has("value"))seek(command.optLong("value",0));return;
                case "autoplay":if(command.opt("value") instanceof Boolean)setAutoplay(command.optBoolean("value"));return;
                default:break;
            }
        });
    }
    private String currentMediaId(){
        try{JSONObject value=new JSONObject(json).optJSONObject("media");return value==null?"":value.optString("id");}
        catch(JSONException ignored){return "";}
    }
    void receiverEvent(JSONObject input){
        JSONObject event=copy(input);if(event==null||closing.get())return;
        long epoch=intentEpoch.get();scheduler.execute(()->accept(event,epoch));
    }
    private void accept(JSONObject event,long epoch){
        if(closing.get()||!started||media==null||!alive(mediaGeneration)||epoch!=intentEpoch.get()||appliedIntent!=epoch)return;
        if(!sessionId.equals(event.optString("sessionId"))||!mediaId.equals(event.optString("mediaId")))return;
        long seq=event.optLong("eventSeq",-1),cmd=event.optLong("commandId",-1);
        if(seq<=lastEventSeq||seq<1||cmd!=commandId)return;
        String type=event.optString("type");
        if(!type.matches("playing|timeupdate|pause|waiting|ended|error|blocked|seeking|seeked"))return;
        long p=event.optLong("positionMs",-1),d=event.optLong("durationMs",-1);
        if(p<0||p>MAX_MEDIA_TIME_MS||d<0||d>MAX_MEDIA_TIME_MS||(d>0&&p>d+2000))return;
        long now=scheduler.nowMs(),last=heartbeat.get();
        if(last>=0&&now-last>HEARTBEAT_TIMEOUT_MS){disconnected=true;disarm();state="DISCONNECTED";}
        lastEventSeq=seq;heartbeat.set(now);
        if(disconnected&&!"playing".equals(type)){publish();return;}
        if(duration>0&&d>0&&Math.abs(duration-d)>2000)disarm();
        if(d>0)duration=d;
        switch(type){
            case "playing":
                if(!desiredPlaying||ended)break;
                disconnected=false;seeking=false;seenPlaying=true;progressAnchor=p;forwardProgress=false;position=p;state="PLAYING";error="";
                if(!confirmed){
                    confirmed=true;current=target;actual=media.quality;adopted=media;
                    try{source.confirmed(current,position,cleanup);}catch(Exception failure){error="播放已确认；历史保存未完成："+message(failure);}
                    try{source.playbackConfirmed(current,actual,autoplay);}catch(RuntimeException failure){error="播放已确认；后续缓存启动未完成";}
                    for(DlnaSession.Publication old:new ArrayList<>(exposed))if(old!=publication){server.revoke(old);exposed.remove(old);}
                }
                break;
            case "timeupdate":
                if(seenPlaying&&desiredPlaying&&!seeking&&"PLAYING".equals(state)){
                    if(progressAnchor>=0&&p-progressAnchor>=250)forwardProgress=true;
                    if(p<position){forwardProgress=false;progressAnchor=p;}
                }
                position=p;saveProgress();break;
            case "pause":position=p;disarm();if(!ended)state="PAUSED";break;
            case "waiting":position=p;disarm();state="BUFFERING";break;
            case "blocked":position=p;disarm();state="WAITING_GESTURE";error="请在接收端点按开始播放";break;
            case "error":position=p;disarm();state="ERROR";error="接收端视频播放失败（"+mediaError(event.optInt("errorCode",0))+"），请重试当前集";break;
            case "seeking":disarm();seeking=true;break;
            case "seeked":position=p;seeking=false;progressAnchor=p;forwardProgress=false;break;
            case "ended":
                if(ended||!desiredPlaying||!confirmed||!seenPlaying||!forwardProgress||seeking||!"PLAYING".equals(state)
                    ||duration<=0||p<duration-1000||p>duration+1000)break;
                position=p;ended=true;state="ENDED";updateAdoptedProgress();saveProgress();
                long completedGeneration=mediaGeneration,completedIntent=epoch;
                if(autoplay&&target+1<count){final int next=target+1;scheduler.execute(()->{
                    if(!closing.get()&&ended&&autoplay&&desiredPlaying&&intentEpoch.get()==completedIntent
                        &&generation.compareAndSet(completedGeneration,completedGeneration+1)){
                        commandId++;begin(completedGeneration+1,next,0,completedIntent);
                    }
                });}
                break;
            default:break;
        }
        publish();
    }
    private void saveProgress(){
        if(!confirmed||current!=target)return;long now=scheduler.nowMs();
        if(ended||lastHistory<0||now-lastHistory>=5000){lastHistory=now;try{source.progress(current,position,duration);}catch(RuntimeException ignored){}}
    }
    private void checkHeartbeat(){
        if(closing.get()||!paired||!started)return;
        long last=heartbeat.get(),now=scheduler.nowMs();
        if(last>=0&&now-last>HEARTBEAT_TIMEOUT_MS&&!disconnected){
            disconnected=true;disarm();state="DISCONNECTED";error="接收端超过 15 秒未响应，未自动跳集";publish();
        }
    }
    private void disarm(){seenPlaying=false;forwardProgress=false;progressAnchor=-1;seeking=false;}
    void stop(){close();}
    @Override public void close(){
        if(!closing.compareAndSet(false,true))return;
        generation.incrementAndGet();intentEpoch.incrementAndGet();
        if(!scheduler.execute(this::finish))finish();
    }
    private void finish(){
        if(closed)return;closed=true;preparing=false;desiredPlaying=false;disarm();state="IDLE";error="";
        if(preparation!=null)preparation.cancel();if(watchdog!=null)watchdog.cancel();
        try{source.close();}finally{try{server.close();}finally{exposed.clear();publish();scheduler.close();}}
    }
    private void updateAdoptedProgress(){if(confirmed&&media==adopted&&current==target){adoptedPosition=position;adoptedDuration=duration;}}
    private void publish(){
        updateAdoptedProgress();revision++;
        boolean active=started&&!closed,playing=active&&!disconnected&&desiredPlaying&&"PLAYING".equals(state);
        DlnaSession.Prepared shown=confirmed?media:adopted;
        long shownPosition=!confirmed&&adopted!=null?adoptedPosition:position,shownDuration=!confirmed&&adopted!=null?adoptedDuration:duration;
        snapshot=new DlnaSession.Snapshot(active,preparing,playing,current<0?target:current,target,shown==null?0:shown.episodeIndex,
            shownPosition,shownDuration,shown==null?title:shown.title,device,state,error,actual,sid,autoplay,preferred);
        JSONObject result=new JSONObject(),intent=new JSONObject();
        try{
            result.put("sessionId",sessionId).put("revision",revision).put("active",active).put("state",state).put("autoplay",autoplay)
                .put("positionMs",position).put("durationMs",duration).put("lastEventSeq",lastEventSeq).put("error",error);
            intent.put("play",desiredPlaying).put("seekMs",seekMs).put("commandId",commandId);result.put("intent",intent);
            if(media==null||publication==null||closed)result.put("media",JSONObject.NULL);
            else result.put("media",new JSONObject().put("id",mediaId).put("url",publication.url).put("title",media.title)
                .put("quality",media.quality).put("episodeIndex",media.episodeIndex).put("queuePosition",target).put("count",count));
            json=result.toString();
        }catch(JSONException impossible){throw new IllegalStateException(impossible);}
        diagnostic="浏览器接收：state="+state+"；已配对="+paired+"；播放确认="+confirmed+"；有效推进="+forwardProgress
            +"；连播="+autoplay+"；已发布片源="+exposed.size()+"/4\n片尾只接受同会话、同视频及当前命令的真实 ended 事件";
        if(started||closed)try{listener.onSnapshot(snapshot);}catch(RuntimeException ignored){}
    }
    private static JSONObject copy(JSONObject value){if(value==null)return null;try{return new JSONObject(value.toString());}catch(JSONException ignored){return null;}}
    private static long boundedTime(long value){return Math.max(0,Math.min(MAX_MEDIA_TIME_MS,value));}
    private static String mediaError(int code){switch(code){case 1:return "已中断";case 2:return "网络";case 3:return "解码";case 4:return "格式不支持";default:return "未知";}}
    private static String message(Exception value){String text=value.getMessage();return clean(text==null?"操作未完成":text,160);}
    private static String clean(String value,int limit){String text=value==null?"":value.replaceAll("https?://[^\\s\"'<>]+","[地址]").replaceAll("[\\p{Cntrl}]"," ").trim();return text.substring(0,Math.min(text.length(),limit));}
}
