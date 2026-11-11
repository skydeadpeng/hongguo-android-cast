package io.github.jackotom.hongguo.android;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Android-independent cast coordinator. Only a confirmed current URI + PLAYING adopts a candidate. */
public final class DlnaSession implements AutoCloseable {
    interface Task { void cancel(); }
    interface Scheduler {
        boolean execute(Runnable action);
        Task prepare(Runnable action);
        Task repeat(Runnable action, long delayMs);
        long nowMs();
        void close();
    }
    interface Controller {
        void setMedia(String url, String title) throws Exception;
        void play() throws Exception;
        void pause() throws Exception;
        void stop() throws Exception;
        void seek(long positionMs) throws Exception;
        Status status() throws Exception;
    }
    static final class Status {
        final String state, mediaUri; final long positionMs, durationMs;
        Status(String state, String uri, long position, long duration) { this.state=state; mediaUri=uri; positionMs=Math.max(0,position); durationMs=Math.max(0,duration); }
    }
    interface Source {
        Prepared prepare(int position, String quality, BooleanSupplier alive) throws Exception;
        void confirmed(int position, long progressMs, boolean cleanup) throws Exception;
        default void progress(int position,long progressMs) { }
        default void progress(int position,long progressMs,long durationMs) { progress(position,progressMs); }
        default void selecting(int position) { }
        default void playbackConfirmed(int position,String actualQuality,boolean autoplay) { }
        default void autoplayChanged(boolean value) { }
        default void close() { }
    }
    interface Server {
        Publication publish(Prepared media) throws Exception;
        void revoke(Publication publication);
        void close();
    }
    static final class Publication { final String url; final Object handle; Publication(String url,Object handle){this.url=url;this.handle=handle;} }
    static final class Prepared {
        final File file; final String title, quality; final int episodeIndex;
        /** Episodes covered by one published media; a merged window advances the queue by this count. */
        final int windowCount;
        final Runnable retain,release; private final AtomicBoolean owner=new AtomicBoolean(true);
        Prepared(File file,String title,String quality,int episodeIndex,Runnable retain,Runnable release) {
            this(file,title,quality,episodeIndex,1,retain,release);
        }
        Prepared(File file,String title,String quality,int episodeIndex,int windowCount,Runnable retain,Runnable release) {
            if(windowCount<1)throw new IllegalArgumentException("合并窗口无效");
            this.file=file;this.title=title;this.quality=quality;this.episodeIndex=episodeIndex;this.windowCount=windowCount;this.retain=retain;this.release=release;
        }
        void releaseOwner(){if(owner.compareAndSet(true,false))release.run();}
    }
    public interface Listener { void onSnapshot(Snapshot snapshot); }
    public static final class Snapshot {
        public final boolean active,preparing,playing,autoplay;
        public final int current,targetCurrent,episodeIndex;
        public final long positionMs,durationMs;
        public final String title,deviceName,state,error,actualQuality,seriesId,preferredQuality;
        Snapshot(boolean active,boolean preparing,boolean playing,int current,int target,int episodeIndex,long position,long duration,String title,String device,String state,String error,String quality,String sid,boolean autoplay,String preferred) {
            this.active=active;this.preparing=preparing;this.playing=playing;this.current=current;targetCurrent=target;this.episodeIndex=episodeIndex;positionMs=position;durationMs=duration;this.title=title;deviceName=device;this.state=state;this.error=error;actualQuality=quality;seriesId=sid;this.autoplay=autoplay;preferredQuality=preferred;
        }
        static Snapshot idle(String error){return new Snapshot(false,false,false,0,0,0,0,0,"","","IDLE",error,"auto","",true,"auto");}
        static Snapshot opening(String sid,String title,String device,int position,String quality,boolean autoplay){return new Snapshot(true,true,false,position,position,0,0,0,title,device,"CONNECTING","",quality,sid,autoplay,quality);}
        Snapshot withError(String message){return new Snapshot(active,preparing,playing,current,targetCurrent,episodeIndex,positionMs,durationMs,title,deviceName,state,message,actualQuality,seriesId,autoplay,preferredQuality);}
    }
    private static final int MAX_POLL_FAILURES=3,MAX_CONFIRM_POLLS=12;
    private static final long END_EVIDENCE_MAX_AGE_MS=15000, PAUSED_CONFIRM_MAX_AGE_MS=30000, PAUSED_TAIL_GAP_MS=1000;
    private final Scheduler scheduler;private final Controller control;private final Source source;private final Server server;private final Listener listener;
    private final String sid,seriesTitle,device;private final int count;private final boolean cleanup;
    private final AtomicLong generation=new AtomicLong(),userIntent=new AtomicLong();
    private final boolean pausedEofProfile;
    private final List<Publication> exposed=new ArrayList<>();
    private volatile Snapshot snapshot=Snapshot.idle("");
    private volatile boolean closed;private boolean working;private boolean autoplay,desiredPlaying=true,disconnected,stopping,ended,seenPlaying,nearEnd;
    private int current=-1,target,pollFailures,confirmPolls;private long position,duration,resume,lastHistory,pendingToken,confirmedToken;
    private String preferred,actual,state="CONNECTING",error="",lastState="";
    private Prepared currentMedia,pendingMedia;private Publication currentPublication,pendingPublication;
    private Task preparation,poll;
    private boolean endArmed;private int terminalPolls;private long lastPlayingAt=-1,recoverToken=-1;
    private long pausedEpoch=-1,pausedIntent=-1,pausedDuration,pausedPosition=-1,pausedForwardAt=-1,pausedFirstAt=-1,pausedCandidateDuration;private int pausedSamples;
    private long pausedPreviousPosition=-1,pausedPreviousForwardAt=-1,pausedWindowAt=-1,pausedLastTail=-1;
    private String pausedBlock="WAIT_PLAYING_PROGRESS",pausedRejection="无";
    private final Deque<String> recentSamples=new ArrayDeque<>(); // Written only by the serial status poll.
    private long previousSampleAt=-1;
    private volatile String sampleTrace="无",eofDiagnostic="片尾判定：尚未取得播放状态";
    DlnaSession(Scheduler scheduler,Controller control,Source source,Server server,Listener listener,String sid,String title,String device,int count,String quality,boolean autoplay,boolean cleanup) {
        if(count<1||count>1000)throw new IllegalArgumentException("投屏队列无效");
        this.scheduler=scheduler;this.control=control;this.source=source;this.server=server;this.listener=listener;this.sid=sid;seriesTitle=title;this.device=device;this.count=count;preferred=MediaQuality.normalize(quality);actual=preferred;this.autoplay=autoplay;this.cleanup=cleanup;
        pausedEofProfile=device!=null&&device.matches("(?i)Zeekr-[A-Za-z0-9]{1,32}_投屏");
    }
    public Snapshot snapshot(){return snapshot;}
    /** Immutable local diagnostic snapshot: no receiver I/O, URLs or inferred playback success. */
    public String diagnostics(){return eofDiagnostic;}
    public void start(int selected,long resumeMs){selectInternal(selected,Math.max(0,resumeMs));}
    public void select(int selected){selectInternal(selected,0);}
    private void selectInternal(int selected,long resumeMs) {
        if(selected<0||selected>=count||closed)return;
        userIntent.incrementAndGet();long token=generation.incrementAndGet();
        scheduler.execute(()->begin(token,selected,resumeMs));
    }
    private boolean alive(long token){return !closed&&!stopping&&generation.get()==token;}
    private void begin(long token,int selected,long resumeMs) {
        if(!alive(token))return;
        pausedRejection="无";source.selecting(selected);
        if(preparation!=null)preparation.cancel();preparation=null;
        // An already exposed old candidate stays readable until a new URI is confirmed, never released merely by cancellation.
        pendingPublication=null;pendingMedia=null;target=selected;resume=resumeMs;disconnected=false;pollFailures=0;ended=false;recoverToken=-1;resetEndEvidence();
        state="PREPARING";error="";desiredPlaying=true;working=true;emit(true);
        String startQuality=currentMedia==null?preferred:actual;
        try { preparation=scheduler.prepare(()->{
            Prepared media=null;
            try {
                media=source.prepare(selected,startQuality,()->alive(token));
                if(!alive(token)){media.releaseOwner();return;}
                Prepared prepared=media;
                if(!scheduler.execute(()->install(token,selected,prepared)))prepared.releaseOwner();
            }catch(Exception failure){if(media!=null)media.releaseOwner();scheduler.execute(()->{if(alive(token)){working=false;String reason="本集投屏准备未成功："+message(failure);if(currentPublication!=null&&exposed.size()>1)candidateFailed(token,reason);else{if(currentPublication!=null)recoverToken=token;error=reason;state=currentMedia==null?"ERROR":lastState;emit(false);}}});}
        }); } catch(RuntimeException rejected){working=false;if(currentPublication!=null)recoverToken=token;state="ERROR";error="投屏准备任务繁忙，请重试本集";emit(false);}
        if(poll==null)poll=scheduler.repeat(this::poll,1000);
    }
    private void install(long token,int selected,Prepared media) {
        if(!alive(token)){media.releaseOwner();return;}
        Publication publication=null;
        try {
            if(exposed.size()>=4)throw new IOException("电视当前媒体尚未核实，请先停止投屏再连接");
            publication=server.publish(media);exposed.add(publication);
            pendingPublication=publication;pendingMedia=media;pendingToken=token;target=selected;confirmPolls=0;state="WAITING_TV";error="";emit(true);
            control.setMedia(publication.url,media.title);
            if(!alive(token))return;
            control.play();
            if(alive(token))poll();
        }catch(Exception failure){if(alive(token))candidateFailed(token,"电视未确认新视频："+message(failure));}
        finally {media.releaseOwner();}
    }
    private void candidateFailed(long token,String reason) {
        if(!alive(token))return;recoverToken=token;
        working=false;resetEndEvidence();error=reason;pendingPublication=null;pendingMedia=null;state=currentMedia==null?"ERROR":lastState;emit(false);
        if(currentPublication!=null){
            try{control.setMedia(currentPublication.url,currentMedia.title);control.play();if(position>0)control.seek(duration>0?Math.min(position,Math.max(0,duration-250)):position);if(!desiredPlaying||!"PLAYING".equals(lastState))control.pause();}
            catch(Exception restore){error=reason+"；旧片源仍保留，电视端恢复未确认";state="ERROR";emit(false);}
        }
    }
    private void poll() {
        if(closed||stopping||disconnected)return;
        // While preparing a replacement, the old receiver may have already discarded its media.
        // Its status must not disconnect the new generation before that URI is installed.
        if(working&&pendingPublication==null)return;
        try {
            long pollIntent=userIntent.get();
            Status status=control.status();if(closed||stopping)return;pollFailures=0;recordStatus(status);
            if(pollIntent!=userIntent.get())resetPausedEvidence();
            if(pendingPublication!=null){
                if(generation.get()!=pendingToken)return;
                if(pendingPublication.url.equals(status.mediaUri)&&"PLAYING".equals(status.state))adopt(status);
                else if(++confirmPolls>=MAX_CONFIRM_POLLS)candidateFailed(pendingToken,"电视在限定检查内未确认新视频播放；未跳集");
                return;
            }
            if(currentPublication==null)return;
            boolean sameUri=currentPublication.url.equals(status.mediaUri);
            boolean terminal="STOPPED".equals(status.state)||"NO_MEDIA_PRESENT".equals(status.state);
            // Some renderers erase TrackURI at EOF. A different non-empty URI is never ours.
            if(!sameUri&&!(terminal&&status.mediaUri.isEmpty())){
                resetEndEvidence();state="ERROR";error="电视当前媒体与本次投屏不一致；未自动跳集";emit(false);return;
            }
            long now=scheduler.nowMs();
            boolean recent=lastPlayingAt>=0&&now>=lastPlayingAt&&now-lastPlayingAt<=END_EVIDENCE_MAX_AGE_MS;
            boolean priorNearEnd=nearEnd;
            if(sameUri){
                if(status.durationMs>0)duration=status.durationMs;
                if(status.positionMs>0||!terminal)position=status.positionMs;
            }
            // A confirmed current URI can resume its epoch after a failed selection, never a queued newer intent.
            if(sameUri&&"PLAYING".equals(status.state)&&recoverToken==generation.get()){
                confirmedToken=recoverToken;recoverToken=-1;
                source.playbackConfirmed(current,actual,autoplay);
            }
            // A positive contradictory terminal sample is not a renderer's zero/reset quirk.
            if(terminal&&sameUri&&(status.positionMs>0||status.durationMs>0)&&!isNearEnd(position,duration)){
                resetEndEvidence();priorNearEnd=false;
            }
            // Consume the final valid position before judging completion: it may arrive only with STOPPED.
            boolean evidence=!working&&generation.get()==confirmedToken&&desiredPlaying&&endArmed&&seenPlaying&&recent&&!ended
                &&(priorNearEnd||(sameUri&&isNearEnd(position,duration)));
            boolean natural=false,verifying=false,pausedCompletion=false;
            if(terminal){
                resetPausedEvidence();
                if(evidence){
                    // Preserve the established explicit near-end PLAYING -> matching STOPPED path.
                    // Compatibility paths (cleared URI, NO_MEDIA, final-only position, transition) need two terminal samples.
                    natural=(sameUri&&"STOPPED".equals(status.state)&&"PLAYING".equals(lastState)&&priorNearEnd)||++terminalPolls>=2;
                    nearEnd=true;verifying=!natural;
                }else terminalPolls=0;
            }else if("PLAYING".equals(status.state)){
                observePausedEofPlaying(status,now,pollIntent);
                seenPlaying=true;endArmed=true;lastPlayingAt=now;nearEnd=isNearEnd(position,duration);terminalPolls=0;
            }else if("PAUSED_PLAYBACK".equals(status.state)&&pausedEofProfile){
                resetTerminalEvidence();
                natural=pausedCompletion=pausedEof(status,now,pollIntent);
                verifying=!natural&&!ended&&pausedSamples>0;
            }else if("TRANSITIONING".equals(status.state)&&recent){
                resetPausedEvidence();terminalPolls=0; // A short transition preserves evidence but never completes on its own.
            }else resetEndEvidence();
            lastState=status.state;state=ended&&(terminal||(pausedEofProfile&&"PAUSED_PLAYBACK".equals(status.state)))?"ENDED":status.state;error="";
            if(verifying)state="VERIFYING_END";
            if("NO_MEDIA_PRESENT".equals(state)){error="电视未报告当前媒体；未自动跳集";state="ERROR";}
            if(natural){ended=true;state="ENDED";}
            long endedToken=confirmedToken;
            // A merged media covers windowCount queue positions; natural end resumes after the whole window.
            int nextPosition=current+(currentMedia!=null?Math.max(1,currentMedia.windowCount):1);

            emit(false);
            if(scheduler.nowMs()-lastHistory>=5000){lastHistory=scheduler.nowMs();source.progress(current,position,duration);}
            if(natural&&autoplay&&nextPosition<count){
                if(pausedCompletion){
                    // A command may arrive while status() blocks, or after this task is queued.
                    // Delay the CAS too: cancelling automatic advancement must not orphan confirmedToken.
                    scheduler.execute(()->{
                        if(!closed&&!stopping&&userIntent.get()==pollIntent&&desiredPlaying&&autoplay
                            &&generation.compareAndSet(endedToken,endedToken+1))begin(endedToken+1,nextPosition,0);
                    });
                }else if(generation.compareAndSet(endedToken,endedToken+1)){
                    // Preserve the existing STOPPED/NO_MEDIA path and manual selection ordering.
                    scheduler.execute(()->begin(endedToken+1,nextPosition,0));
                }
            }
            if("PLAYING".equals(status.state))releaseExposedExcept(currentPublication);
        }catch(Exception failure){
            resetEndEvidence();
            if(++pollFailures>=MAX_POLL_FAILURES){disconnected=true;working=false;state="DISCONNECTED";error="连续 3 次未取得电视状态；最近错误："+message(failure)+"；停止尚未确认，未自动跳集";emit(false);}
        }
    }
    private void resetTerminalEvidence(){nearEnd=false;endArmed=false;terminalPolls=0;lastPlayingAt=-1;}
    private void resetEndEvidence(){resetTerminalEvidence();resetPausedEvidence();}
    private void resetPausedEvidence(){pausedEpoch=-1;pausedIntent=-1;pausedDuration=0;pausedPosition=-1;pausedForwardAt=-1;pausedFirstAt=-1;pausedCandidateDuration=0;pausedSamples=0;pausedPreviousPosition=-1;pausedPreviousForwardAt=-1;pausedWindowAt=-1;pausedLastTail=-1;pausedBlock="WAIT_PLAYING_PROGRESS";}
    private void observePausedEofPlaying(Status status,long now,long intent){
        if(!pausedEofProfile)return;
        if(working||ended||!desiredPlaying||generation.get()!=confirmedToken||userIntent.get()!=intent
            ||(status.durationMs>0&&status.positionMs>status.durationMs)){resetPausedEvidence();return;}
        pausedSamples=0;pausedFirstAt=-1;pausedCandidateDuration=0;pausedWindowAt=-1;pausedLastTail=-1;
        // Track real progress independently of metadata availability. Some receivers only
        // supply TrackDuration at EOF; a zero/unknown duration is not a playback reset.
        if(pausedEpoch!=confirmedToken||pausedIntent!=intent
            ||(pausedDuration>0&&status.durationMs>0&&pausedDuration!=status.durationMs)
            ||(pausedPosition>=0&&status.positionMs<pausedPosition)){
            resetPausedEvidence();pausedEpoch=confirmedToken;pausedIntent=intent;
        }
        if(status.durationMs>0)pausedDuration=status.durationMs;
        if(pausedDuration>0&&status.positionMs>pausedDuration){resetPausedEvidence();return;}
        // A fixed PLAYING sample at known EOF is not progress (notably after a seek).
        // With unknown duration, repeated equal positions likewise never arm completion.
        if(pausedDuration==0||status.positionMs<pausedDuration){
            if(pausedPosition>=0&&status.positionMs>pausedPosition){
                pausedPreviousPosition=pausedPosition;pausedPreviousForwardAt=pausedForwardAt;pausedForwardAt=now;pausedRejection="无";
            }
            pausedPosition=status.positionMs;
        }
        pausedBlock=pausedForwardAt>=0?"TRACKING_PROGRESS":"WAIT_FORWARD_PROGRESS";
    }
    private boolean rejectPaused(String reason,Status status){
        // Keep the triggering raw sample before reset; a later fixed EOF must not hide it.
        pausedRejection=reason+" raw="+status.positionMs+"/"+status.durationMs+" tracked="+pausedPosition
            +" prior="+pausedPreviousPosition+" samples="+pausedSamples;
        resetPausedEvidence();pausedBlock=reason;return false;
    }
    private boolean pausedEof(Status status,long now,long intent){
        if(ended)return false;
        if(working)return rejectPaused("PREPARING",status);
        if(!desiredPlaying)return rejectPaused("PHONE_PAUSED",status);
        if(generation.get()!=confirmedToken||userIntent.get()!=intent)return rejectPaused("INTENT_CHANGED",status);
        if(pausedEpoch!=confirmedToken||pausedIntent!=intent||pausedForwardAt<0){
            if(pausedBlock.equals("WAIT_PLAYING_PROGRESS")||pausedBlock.equals("WAIT_FORWARD_PROGRESS"))pausedBlock="NO_FORWARD_PROGRESS";
            return false;
        }
        if(status.durationMs<=0)return rejectPaused("DURATION_UNKNOWN",status);
        if((pausedDuration>0&&status.durationMs!=pausedDuration)
            ||(pausedWindowAt>=0&&status.durationMs!=pausedCandidateDuration))return rejectPaused("DURATION_CHANGED",status);
        if(pausedPosition>status.durationMs)return rejectPaused("PLAYING_BEYOND_END",status);
        // Unknown-duration PLAYING may reach EOF before duration is reported. Require
        // genuine progress BEFORE that endpoint, not just an initial/seeked EOF sample.
        long proofAt=pausedForwardAt;
        if(pausedPosition==status.durationMs){
            if(pausedPreviousPosition<0||pausedPreviousPosition>=status.durationMs||pausedPreviousForwardAt<0)
                return rejectPaused("NO_PRE_END_PROGRESS",status);
            proofAt=pausedPreviousForwardAt;
        }
        if(status.positionMs>status.durationMs)return rejectPaused("PAUSE_BEYOND_END",status);
        if(status.positionMs<pausedPosition||(pausedLastTail>=0&&status.positionMs<pausedLastTail))return rejectPaused("PAUSE_BACKWARD",status);
        if(status.durationMs-status.positionMs>PAUSED_TAIL_GAP_MS)return rejectPaused("PAUSE_BEFORE_TAIL",status);
        if(now<pausedForwardAt||now<proofAt)return rejectPaused("CLOCK_REVERSED",status);
        if(pausedWindowAt<0){
            if(now-proofAt>END_EVIDENCE_MAX_AGE_MS)return rejectPaused("STALE_PROGRESS",status);
            pausedWindowAt=now;pausedCandidateDuration=status.durationMs;
            if(pausedDuration==0)pausedDuration=status.durationMs; // Keep this known bound across a later zero-duration PLAYING sample.
        }else if(now<pausedWindowAt||now-pausedWindowAt>PAUSED_CONFIRM_MAX_AGE_MS)return rejectPaused("CONFIRM_EXPIRED",status);
        pausedLastTail=status.positionMs;
        // A transport/position pair can straddle EOF and report PAUSED one second early.
        // Preserve evidence briefly, but never count that sample as completed playback.
        if(status.positionMs<status.durationMs){
            pausedSamples=0;pausedFirstAt=-1;pausedBlock="WAIT_FINAL_POSITION";return false;
        }
        if(pausedSamples==0)pausedFirstAt=now;
        if(now<pausedFirstAt)return rejectPaused("CLOCK_REVERSED",status);
        boolean complete=++pausedSamples>=3&&now-pausedFirstAt>=2000;
        pausedBlock=complete?"CONFIRMED":"VERIFYING";
        return complete;
    }
    private void recordStatus(Status status){
        String name;
        switch(status.state){case "PLAYING":name="PLAY";break;case "PAUSED_PLAYBACK":name="PAUSE";break;case "STOPPED":name="STOP";break;case "NO_MEDIA_PRESENT":name="NONE";break;case "TRANSITIONING":name="TRANS";break;default:name="OTHER";}
        String own=(currentPublication!=null&&currentPublication.url.equals(status.mediaUri))
            ||(pendingPublication!=null&&pendingPublication.url.equals(status.mediaUri))?"=":"!";
        long now=scheduler.nowMs(),delta=previousSampleAt<0?-1:now-previousSampleAt;previousSampleAt=now;
        recentSamples.addLast(name+own+":"+status.positionMs+"/"+status.durationMs+"@"+delta);
        while(recentSamples.size()>6)recentSamples.removeFirst();
        sampleTrace=String.join(" > ",recentSamples);
    }
    private static boolean isNearEnd(long position,long duration){return duration>0&&position>=duration-endTolerance(duration)&&position<=duration+endTolerance(duration);}
    private static long endTolerance(long duration){return Math.min(Math.max(1,duration/4),Math.min(2500,Math.max(500,duration/50)));}
    private void adopt(Status status) {
        pausedRejection="无";working=false;current=target;confirmedToken=pendingToken;currentPublication=pendingPublication;currentMedia=pendingMedia;pendingPublication=null;pendingMedia=null;
        actual=currentMedia.quality;position=status.positionMs;duration=status.durationMs;lastState="PLAYING";state="PLAYING";seenPlaying=true;resetEndEvidence();endArmed=true;lastPlayingAt=scheduler.nowMs();nearEnd=isNearEnd(position,duration);ended=false;error="";
        releaseExposedExcept(currentPublication);
        observePausedEofPlaying(status,scheduler.nowMs(),userIntent.get());
        if(resume>0)try{resetPausedEvidence();control.seek(duration>0?Math.min(resume,Math.max(0,duration-250)):resume);}catch(Exception failure){error="电视已播放，恢复进度未确认";}resume=0;
        if(!desiredPlaying)try{control.pause();state="PAUSED_PLAYBACK";lastState=state;}catch(Exception failure){error="电视已播放，暂停尚未确认";}
        try{source.confirmed(current,position,cleanup);}catch(Exception failure){error="电视已播放，本机进度或缓存清理未完成";}
        source.playbackConfirmed(current,actual,autoplay);
        emit(false);
    }
    private void releaseExposedExcept(Publication keep){for(Publication item:new ArrayList<>(exposed))if(item!=keep){server.revoke(item);exposed.remove(item);}}
    public void play(){command("play",0);}
    public void pause(){command("pause",0);}
    public void seek(long ms){command("seek",Math.max(0,ms));}
    public void setAutoplay(boolean value){userIntent.incrementAndGet();scheduler.execute(()->{resetPausedEvidence();autoplay=value;source.autoplayChanged(value);emit(snapshot.preparing);});}
    private void command(String command,long ms){userIntent.incrementAndGet();scheduler.execute(()->{
        if(closed||stopping)return;disconnected=false;pollFailures=0;
        try{
            if(command.equals("pause")){desiredPlaying=false;resetEndEvidence();if(pendingPublication==null)control.pause();}
            else if(command.equals("play")){desiredPlaying=true;if(pendingPublication==null){if(ended){control.seek(0);position=0;}ended=false;resetEndEvidence();seenPlaying=false;control.play();}}
            else {if(currentPublication==null||pendingPublication!=null)throw new IOException("请等待电视确认本集后再定位");resetEndEvidence();if(pausedEofProfile)ended=false;control.seek(duration>0?Math.min(ms,duration):ms);}
            poll();
        }catch(Exception failure){error="投屏控制未确认："+message(failure);emit(snapshot.preparing);}
    });}
    public void stop(){
        if(closed)return;generation.incrementAndGet();
        scheduler.execute(()->{
            if(closed||stopping)return;stopping=true;working=false;source.close();if(preparation!=null)preparation.cancel();if(poll!=null)poll.cancel();state="STOPPING";emit(false);
            boolean confirmed=false;
            try{control.stop();Status status=control.status();confirmed="STOPPED".equals(status.state)||"NO_MEDIA_PRESENT".equals(status.state);}catch(Exception ignored){}
            state=confirmed?"STOPPED":"DISCONNECTED";error=confirmed?"":"本机供流已关闭；电视端停止尚未确认";finish();
        });
    }
    private void finish(){closed=true;working=false;source.close();generation.incrementAndGet();if(preparation!=null)preparation.cancel();if(poll!=null)poll.cancel();server.close();exposed.clear();emit(false);scheduler.close();}
    @Override public void close(){if(closed)return;closed=true;working=false;source.close();generation.incrementAndGet();if(preparation!=null)preparation.cancel();if(poll!=null)poll.cancel();server.close();state="DISCONNECTED";error="本机供流已关闭；电视端停止尚未确认";emit(false);scheduler.close();}
    private void emit(boolean preparing){
        long now=scheduler.nowMs();
        eofDiagnostic="片尾判定：profile="+(pausedEofProfile?"ZEEKR":"STANDARD")+"；连播="+autoplay+"；播放意图="+desiredPlaying
            +"；同集="+(generation.get()==confirmedToken)+"；推进位置="+pausedPosition+"；推进距今ms="+(pausedForwardAt<0?-1:Math.max(0,now-pausedForwardAt))
            +"；末尾样本="+pausedSamples+"/3；确认ms="+(pausedFirstAt<0?-1:Math.max(0,now-pausedFirstAt))+"；原因="+pausedBlock+"\n片尾首拒："+pausedRejection+"\n片尾采样（=本集/!其他，@间隔ms）："+sampleTrace;
        int selected=current<0?target:current;Prepared media=currentMedia;
        boolean playing=!disconnected&&currentPublication!=null&&"PLAYING".equals(lastState)&&!closed;
        snapshot=new Snapshot(!closed,working,playing,selected,target,media==null?0:media.episodeIndex,position,duration,media==null?seriesTitle:media.title,device,state,error,actual,sid,autoplay,preferred);
        try{listener.onSnapshot(snapshot);}catch(RuntimeException ignored){}
    }
    private static String message(Exception failure){
        boolean timeout=false,interrupted=false,interruptedIo=false;
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=failure;cause!=null&&seen.add(cause);cause=cause.getCause()){
            if(cause instanceof java.net.SocketTimeoutException)timeout=true;
            else if(cause instanceof InterruptedException)interrupted=true;
            else if(cause instanceof InterruptedIOException)interruptedIo=true;
        }
        // SocketTimeoutException is an InterruptedIOException, but a receiver read
        // timeout is not cancellation. A real thread/operation interrupt still wins.
        if(Thread.currentThread().isInterrupted()||interrupted||interruptedIo)return "投屏准备已取消";
        String value=failure.getMessage();
        if(value==null||value.trim().isEmpty())value=timeout?"电视状态请求超时":"操作未完成";
        else if(timeout)value="电视状态请求超时："+value;
        value=value.replaceAll("https?://[^\\s\"'<>]+","[地址]").replaceAll("[\\p{Cntrl}]"," ");
        return value.substring(0,Math.min(160,value.length()));
    }
}
