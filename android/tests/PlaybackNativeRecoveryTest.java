package io.github.jackotom.hongguo.android;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Executes the production explicit playback boundary, not the JavaScript resolver. */
public final class PlaybackNativeRecoveryTest {
    static final String SID="880001",VID="880006";
    static int passed,failed,checks;
    interface Checked {void run() throws Exception;}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Exception reject(Checked action)throws Exception{try{action.run();}catch(Exception error){checks++;return error;}throw new AssertionError("unexpected successful playback plan");}
    static void group(String name,Checked action){try{NetworkGuard.reset();action.run();passed++;System.out.println("PASS "+name);}catch(Throwable error){failed++;System.out.println("FAIL "+name+": "+error.getClass().getSimpleName()+" "+error.getMessage());}finally{Thread.interrupted();try{timing(null);}catch(Exception ignored){}}}
    static AppMediaSource.AppSourceException empty(){return new AppMediaSource.AppSourceException("synthetic empty model","APP_EMPTY_RESPONSE");}
    static JSONObject model(String quality,int ceiling)throws Exception{
        JSONObject info=new JSONObject().put("url","https://cdn.qznovelvod.com/native-budget.mp4").put("source","app").put("seriesId",SID).put("vid",VID).put("requestedQuality",quality).put("width",720).put("height",1280);
        if(ceiling>0)info.put("playbackCeiling",ceiling);
        return info;
    }
    static PlaybackSources.Plan play(PlaybackSources.NativeLookup lookup)throws Exception{return PlaybackSources.resolvePlayback(SID,VID,"720p",1079,false,false,lookup);}
    static void timing(FakeTime value)throws Exception{
        Class<?> type=Class.forName(PlaybackSources.class.getName()+"$RecoveryTiming");
        Method setter=PlaybackSources.class.getDeclaredMethod("setPlaybackRecoveryTimingForTests",type);setter.setAccessible(true);
        Object proxy=value==null?null:Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
            if(m.getName().equals("nanoTime"))return value.nanos;
            if(m.getName().equals("sleep")){value.sleep(((Long)a[0]).longValue());return null;}
            throw new AssertionError("unexpected timing method "+m.getName());
        });
        setter.invoke(null,proxy);
    }
    static final class FakeTime{
        long nanos,oversleep;boolean interruptSleep;final List<Long> sleeps=new ArrayList<>();
        void advance(long ms){nanos+=ms*1_000_000L;}
        void sleep(long ms)throws InterruptedException{sleeps.add(ms);if(interruptSleep)throw new InterruptedException("synthetic cancelled backoff");advance(ms+oversleep);}
    }
    static void baseline()throws Exception{
        group("explicit native playback retries three empty replies then keeps the same quality",()->{
            int[] calls={0};PlaybackSources.Plan result=null;Exception failure=null;
            try{result=play((sid,vid,q,c,t)->{calls[0]++;if(calls[0]<4)throw empty();return model(q,c);});}catch(Exception error){failure=error;}
            check(calls[0]==4,"four bounded native lookups required, got "+calls[0]);if(failure!=null)throw failure;
            check(calls[0]==4&&result.requestedQuality.equals("720p"),"four bounded native lookups required, got "+calls[0]);
        });
    }
    public static void main(String[]args)throws Exception{
        if(args.length>0&&args[0].equals("--baseline")){baseline();finish();return;}
        group("explicit native playback retries three empty replies with identical episode quality and ceiling",()->{
            FakeTime time=new FakeTime();timing(time);List<Long> timeouts=new ArrayList<>();int[]calls={0};
            PlaybackSources.Plan plan=play((sid,vid,q,c,t)->{check(SID.equals(sid)&&VID.equals(vid)&&q.equals("720p")&&c==1079,"request identity changed");timeouts.add(t);time.advance(200);if(++calls[0]<4)throw empty();return model(q,c);});
            check(calls[0]==4&&plan.requestedQuality.equals("720p")&&plan.playbackCeiling==1079,"retry changed playback contract");
            check(time.sleeps.equals(Arrays.asList(1000L,2500L,5000L)),"unexpected retry cadence "+time.sleeps);
            check(timeouts.equals(Arrays.asList(20000L,18800L,16100L,10900L)),"timeouts must consume the same total budget "+timeouts);
            check(NetworkGuard.downloads==0,"lookup retry touched media transfer");
        });
        group("permanent empty response stops after four total native requests",()->{
            FakeTime time=new FakeTime();timing(time);int[]calls={0};Exception error=reject(()->play((s,v,q,c,t)->{calls[0]++;throw empty();}));
            check(calls[0]==4&&time.sleeps.size()==3&&time.nanos==8_500_000_000L,"retry cap exceeded");check(error.getMessage().contains("synthetic empty"),"lost original empty error");
        });
        group("normal Auto and fixed 1080p retries never change requested quality",()->{
            for(String selected:new String[]{"auto","1080p"}){
                FakeTime time=new FakeTime();timing(time);int[]calls={0};PlaybackSources.Plan plan=PlaybackSources.resolvePlayback(SID,VID,selected,0,false,false,(s,v,q,c,t)->{check(q.equals(selected)&&c==0,"initial quality or ceiling changed");if(++calls[0]==1)throw empty();return model(q,c).put("width",1080).put("height",1920);});
                check(calls[0]==2&&plan.requestedQuality.equals(selected)&&plan.playbackCeiling==0,"native retry silently lowered quality");check(time.sleeps.equals(Collections.singletonList(1000L)),"normal retry cadence changed");
            }
        });
        group("business quality malformed and unsupported codec failures never repeat",()->{
            for(String code:new String[]{"APP_BUSINESS_REJECTED","BUSINESS_REJECTED","QUALITY_UNAVAILABLE","APP_INVALID_RESPONSE","UNSUPPORTED_CODEC",""}){
                FakeTime time=new FakeTime();timing(time);int[]calls={0};reject(()->play((s,v,q,c,t)->{calls[0]++;throw new AppMediaSource.AppSourceException("synthetic terminal",code);}));check(calls[0]==1&&time.sleeps.isEmpty(),"terminal code retried: "+code);
            }
            FakeTime time=new FakeTime();timing(time);int[]calls={0};reject(()->play((s,v,q,c,t)->{calls[0]++;return model(q,c).put("requestedQuality","1080p");}));check(calls[0]==1&&time.sleeps.isEmpty(),"invalid model retried");
        });
        group("insufficient budget for the next delay stops before another request",()->{
            FakeTime time=new FakeTime();timing(time);List<Long> timeouts=new ArrayList<>();Exception error=reject(()->play((s,v,q,c,t)->{timeouts.add(t);time.advance(4000);throw empty();}));
            check(timeouts.equals(Arrays.asList(20000L,15000L,8500L))&&time.sleeps.equals(Arrays.asList(1000L,2500L)),"next over-budget delay/request was dispatched");check(error.getMessage().contains("超时"),"budget exhaustion not reported as timeout");
        });
        group("last attempt receives remaining time and its late success is discarded",()->{
            FakeTime time=new FakeTime();timing(time);List<Long> timeouts=new ArrayList<>();int[]calls={0};Exception error=reject(()->play((s,v,q,c,t)->{timeouts.add(t);if(++calls[0]<4){time.advance(1000);throw empty();}time.advance(t);return model(q,c);}));
            check(timeouts.equals(Arrays.asList(20000L,18000L,14500L,8500L))&&time.nanos==20_000_000_000L,"last request exceeded remaining budget");check(error.getMessage().contains("超时"),"late success escaped the deadline");
        });
        group("overslept backoff and a slow terminal reply cannot bypass the total budget",()->{
            FakeTime time=new FakeTime();time.oversleep=25000;timing(time);int[]calls={0};reject(()->play((s,v,q,c,t)->{calls[0]++;throw empty();}));check(calls[0]==1,"request began after overslept deadline");
            FakeTime slow=new FakeTime();timing(slow);calls[0]=0;Exception error=reject(()->play((s,v,q,c,t)->{calls[0]++;slow.advance(t+1);throw new IOException("synthetic terminal late");}));check(calls[0]==1&&error.getMessage().contains("超时"),"late exception escaped deadline");
        });
        group("fresh outer recovery and captured CDN refresh stay single shot",()->{
            FakeTime time=new FakeTime();timing(time);int[]calls={0};List<Long> timeouts=new ArrayList<>();reject(()->PlaybackSources.resolvePlayback(SID,VID,"720p",1079,true,false,(s,v,q,c,t)->{calls[0]++;timeouts.add(t);throw empty();}));check(calls[0]==1&&time.sleeps.isEmpty()&&timeouts.equals(Collections.singletonList(20000L)),"fresh recovery nested an initial retry");
            calls[0]=0;PlaybackSources.Plan plan=play((s,v,q,c,t)->{if(++calls[0]==1)return model(q,c);throw empty();});NetworkGuard.outcomes.put(plan.url,"eof");File dir=Files.createTempDirectory("native-refresh-bound").toFile();File target=new File(dir,"media.part");
            try{reject(()->PlaybackSources.download(plan,target,1024));check(calls[0]==2&&NetworkGuard.downloads==1&&!target.exists(),"CDN refresh multiplied native retries or kept partial bytes");}finally{target.delete();dir.delete();}
        });
        group("legacy page resolver and non-playback native lookup retain one retry only",()->{
            MainActivity host=new MainActivity();PlaybackSources.attach(host);int[]calls={0};host.callback=script->{try{JSONArray a=new JSONArray("["+script.substring(script.lastIndexOf('(')+1,script.length()-1)+"]");calls[0]++;PlaybackSources.result(new JSONObject().put("requestId",a.getString(0)).put("seriesId",SID).put("vid",VID).put("requestedQuality","720p").put("error","synthetic empty").put("errorCode","APP_EMPTY_RESPONSE"));}catch(Exception error){throw new RuntimeException(error);}};
            try{reject(()->PlaybackSources.resolve(SID,VID,"720p"));check(calls[0]==2,"legacy JavaScript boundary gained extra retries");}finally{PlaybackSources.detach(host);}
            calls[0]=0;PlaybackSources.NativeLookup legacy=(s,v,q,c,t)->{calls[0]++;throw empty();};PlaybackSources.attachNative(legacy);try{reject(()->PlaybackSources.resolve(SID,VID,"720p"));check(calls[0]==2,"download/default native policy changed");}finally{PlaybackSources.detachNative(legacy);}
        });
        group("cancelled successes and wrapped interruptions preserve the interrupt flag",()->{
            FakeTime time=new FakeTime();timing(time);int[]calls={0};Exception first=reject(()->play((s,v,q,c,t)->{calls[0]++;Thread.currentThread().interrupt();return model(q,c);}));check(first instanceof InterruptedIOException&&Thread.currentThread().isInterrupted()&&calls[0]==1,"cancelled success was published");Thread.interrupted();
            Exception wrapped=reject(()->play((s,v,q,c,t)->{throw new IOException("synthetic wrapped cancellation",new InterruptedException("cancelled"));}));check(Thread.currentThread().isInterrupted()&&wrapped.getCause() instanceof InterruptedException,"wrapped interruption lost status");Thread.interrupted();
            Exception mapped=reject(()->play((s,v,q,c,t)->{throw new AppMediaSource.AppSourceException("synthetic mapped cancellation","ERR_CANCELED");}));check(mapped instanceof InterruptedIOException&&Thread.currentThread().isInterrupted(),"typed native cancellation lost status");Thread.interrupted();
            time.interruptSleep=true;calls[0]=0;Exception sleep=reject(()->play((s,v,q,c,t)->{calls[0]++;throw empty();}));check(sleep instanceof InterruptedException&&Thread.currentThread().isInterrupted()&&calls[0]==1,"cancelled backoff repeated lookup");Thread.interrupted();
            calls[0]=0;Thread.currentThread().interrupt();reject(()->play((s,v,q,c,t)->{calls[0]++;return model(q,c);}));check(calls[0]==0&&Thread.currentThread().isInterrupted(),"pre-cancelled lookup was dispatched");
        });
        group("real sleeping native retry cancels promptly without a worker leak",()->{
            timing(null);AtomicInteger calls=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean interrupted=new AtomicBoolean();Thread worker=new Thread(()->{try{play((s,v,q,c,t)->{calls.incrementAndGet();throw empty();});}catch(Throwable error){failure.set(error);interrupted.set(Thread.currentThread().isInterrupted());}},"native-recovery-interrupt");
            worker.start();long until=System.nanoTime()+2_000_000_000L;while(worker.isAlive()&&worker.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<until)Thread.sleep(2);check(worker.getState()==Thread.State.TIMED_WAITING,"worker never entered retry sleep");worker.interrupt();worker.join(1000);check(!worker.isAlive()&&failure.get() instanceof InterruptedException&&interrupted.get()&&calls.get()==1,"cancelled worker leaked or dispatched another lookup");
        });
        finish();
    }
    static void finish(){System.out.println("PLAYBACK_NATIVE_RECOVERY_"+(failed==0?"OK":"FAILED")+" groupsPassed="+passed+" groupsFailed="+failed+" checks="+checks+" networkRequests=0 productionResolver=true");if(failed!=0)throw new AssertionError("explicit native recovery gate failed");}
}
