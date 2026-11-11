package io.github.jackotom.hongguo.android;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Production lookup/quality owners; injected bridge replies and private temporary files only. */
public final class EmptyAppResponseTest {
    static final String SID="7690112243203050521", VID="7700000000000000076"; // Synthetic VID, not a live episode mapping.
    static int passed,failed,checks;
    interface Checked { void run() throws Exception; }
    static void check(boolean value,String label) { checks++; if(!value)throw new AssertionError(label); }
    static Exception rejected(Checked task) throws Exception {try{task.run();}catch(Exception e){checks++;return e;}throw new AssertionError("Expected failure");}
    static JSONArray arguments(String script)throws Exception {return new JSONArray("["+script.substring(script.lastIndexOf('(')+1,script.length()-1)+"]");}
    static JSONObject envelope(JSONArray args)throws Exception {return new JSONObject().put("requestId",args.getString(0)).put("seriesId",args.getString(1)).put("vid",args.getString(2)).put("requestedQuality",args.getString(3));}
    static JSONObject model(String marker,String quality)throws Exception {
        JSONObject info=new JSONObject().put("url","https://cdn.qznovelvod.com/empty-response-"+marker+".mp4").put("seriesId",SID).put("vid",VID).put("source","app").put("requestedQuality",quality);
        if(!quality.equals("auto")){int pixels=Integer.parseInt(quality.substring(0,quality.length()-1));info.put("width",pixels).put("height",pixels*2);}
        return info;
    }
    static void error(JSONArray args,String code)throws Exception {PlaybackSources.result(envelope(args).put("error","App 模型接口返回空数据，请稍后重试").put("errorCode",code));}
    static void success(JSONArray args,String marker)throws Exception {PlaybackSources.result(envelope(args).put("playInfo",model(marker,args.getString(3))));}
    static int pending()throws Exception {Field f=PlaybackSources.class.getDeclaredField("pending");f.setAccessible(true);return ((Map<?,?>)f.get(null)).size();}
    static String code(Exception error)throws Exception {try{Field f=error.getClass().getDeclaredField("code");f.setAccessible(true);return (String)f.get(error);}catch(NoSuchFieldException ignored){return "";}}
    static void group(String name,Checked task){try{NetworkGuard.reset();task.run();check(pending()==0,"all lookup ownership released");passed++;System.out.println("PASS "+name);}catch(Throwable e){failed++;System.out.println("FAIL "+name+": "+e.getClass().getSimpleName()+" "+e.getMessage());}finally{Thread.interrupted();}}
    public static void main(String[] unused)throws Exception {
        MainActivity host=new MainActivity();PlaybackSources.attach(host);File directory=Files.createTempDirectory("empty-app-response-").toFile();
        try {
            group("first empty response re-signs once with exact identity and selected quality",()->{
                for(String quality:new String[]{"1080p","720p","auto"}){
                    List<JSONArray> calls=new ArrayList<>();
                    host.callback=script->{try{JSONArray a=arguments(script);calls.add(a);if(calls.size()==1)error(a,"APP_EMPTY_RESPONSE");else{check(!calls.get(0).getString(0).equals(a.getString(0)),"fresh opaque id after empty reply");success(calls.get(0),"late-old");success(a,"fresh-"+quality);}}catch(Exception e){throw new RuntimeException(e);}};
                    long started=System.nanoTime();PlaybackSources.Plan plan=PlaybackSources.resolve(SID,VID,quality);long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                    check(calls.size()==2,"one retry, not a nested retry loop");
                    for(JSONArray a:calls)check(a.getString(1).equals(SID)&&a.getString(2).equals(VID)&&a.getString(3).equals(quality),"SID/VID/quality remain exact");
                    check(plan.url.contains("fresh-")&&plan.vid.equals(VID)&&plan.requestedQuality.equals(quality),"late old model cannot replace fresh matching result");
                    check(elapsed>=650&&elapsed<8000,"response retry uses short bounded 700ms backoff, under existing total budget");
                }
                check(NetworkGuard.downloads==0,"lookup recovery never downloads media");
            });
            group("two empty responses stop after two lookups without lowering quality",()->{
                List<JSONArray> calls=new ArrayList<>();List<String> stages=new ArrayList<>();AtomicInteger published=new AtomicInteger();
                host.callback=script->{try{JSONArray a=arguments(script);calls.add(a);error(a,"APP_EMPTY_RESPONSE");}catch(Exception e){throw new RuntimeException(e);}};
                Exception e=rejected(()->{AdaptiveQuality.Result<PlaybackSources.Plan> result=AdaptiveQuality.prepare("1080p",q->{stages.add(q);return PlaybackSources.resolve(SID,VID,q);},PlaybackSources::canLowerQuality,()->true,(q,n,last)->{},p->{});published.incrementAndGet();});
                check(calls.size()==2&&stages.equals(Collections.singletonList("1080p")),"empty data does not prove 1080p unavailable; no 720p/auto or episode77");
                check(code(e).equals("APP_EMPTY_RESPONSE"),"typed empty code survives the native callback");
                check(e.getMessage().contains("已重取1次")&&e.getMessage().contains("重试"),"exhaustion distinguishes already-retried response from initial error");
                check(!PlaybackSources.canLowerQuality(e)&&published.get()==0&&NetworkGuard.downloads==0,"no fake stage success, file or lower-quality result");
            });
            group("business model identity format and generic invalid errors stay terminal",()->{
                for(String code:new String[]{"BUSINESS_REJECTED","MISSING_MODEL","INVALID_RESPONSE","APP_INVALID_RESPONSE","SECURITY_BLOCKED","UNKNOWN",""}){
                    AtomicInteger calls=new AtomicInteger();host.callback=script->{try{calls.incrementAndGet();error(arguments(script),code);}catch(Exception e){throw new RuntimeException(e);}};
                    Exception e=rejected(()->PlaybackSources.resolve(SID,VID,"1080p"));
                    check(calls.get()==1&&!PlaybackSources.canLowerQuality(e),"no retry/downgrade for "+code);
                    if(code.equals("APP_INVALID_RESPONSE"))check(code(e).equals(code),"local invalid-response diagnostic preserved without retry");
                }
                check(NetworkGuard.downloads==0,"terminal protocol failures produce no media");
            });
            group("caller-owned fresh model lookup does not nest the empty response retry",()->{
                AtomicInteger calls=new AtomicInteger();host.callback=script->{try{calls.incrementAndGet();error(arguments(script),"APP_EMPTY_RESPONSE");}catch(Exception e){throw new RuntimeException(e);}};
                Exception e=rejected(()->PlaybackSources.recover(SID,VID,"1080p",false));
                check(calls.get()==1&&code(e).equals("APP_EMPTY_RESPONSE"),"recover(...false) remains single lookup");
                check(!e.getMessage().contains("已重取1次"),"single fresh-model lookup does not claim two attempts");
                check(!PlaybackSources.canLowerQuality(e),"response format is not quality availability");
            });
            group("media403 refresh keeps transfer cap and removes incomplete file",()->{
                JSONObject old=model("media403-old","1080p");String url=old.getString("url");PlaybackSources.register(old,VID,false);NetworkGuard.outcomes.put(url,"http403");
                AtomicInteger calls=new AtomicInteger();host.callback=script->{try{calls.incrementAndGet();JSONArray a=arguments(script);check(a.getString(3).equals("1080p")&&a.getString(2).equals(VID),"media refresh identity stays same");error(a,"APP_EMPTY_RESPONSE");}catch(Exception e){throw new RuntimeException(e);}};
                File part=new File(directory,"failed-76.part");Exception e=rejected(()->PlaybackSources.download(url,part,64,"1080p"));
                check(calls.get()==1&&NetworkGuard.downloads==2,"one Referer-switch retry and one caller-owned model refresh, no nested lookup");
                check(!part.exists()&&!PlaybackSources.canLowerQuality(e),"partial file not published and no fake lower stage");
            });
            group("retry backoff is interruptible and no second callback escapes cancellation",()->{
                AtomicInteger calls=new AtomicInteger();CountDownLatch first=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean interrupted=new AtomicBoolean();
                host.callback=script->{try{calls.incrementAndGet();error(arguments(script),"APP_EMPTY_RESPONSE");first.countDown();}catch(Exception e){throw new RuntimeException(e);}};
                Thread work=new Thread(()->{try{PlaybackSources.resolve(SID,VID,"1080p");}catch(Throwable e){failure.set(e);interrupted.set(Thread.currentThread().isInterrupted());}});
                work.start();check(first.await(1,TimeUnit.SECONDS),"first empty callback arrived");long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while(work.isAlive()&&work.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<end)Thread.sleep(1);
                check(work.isAlive(),"empty result entered bounded response retry backoff");work.interrupt();work.join(1500);
                check(!work.isAlive()&&failure.get() instanceof InterruptedException&&interrupted.get()&&calls.get()==1,"cancel preserves interrupt and sends no second model lookup");
            });
            group("identity mismatch after response retry cannot publish a foreign episode",()->{
                AtomicInteger calls=new AtomicInteger();host.callback=script->{try{JSONArray a=arguments(script);if(calls.incrementAndGet()==1)error(a,"APP_EMPTY_RESPONSE");else{try{PlaybackSources.result(envelope(a).put("vid","7700000000000000077").put("playInfo",model("foreign","1080p")));}catch(Exception expected){/* native rejects and completes the original future */}}}catch(Exception e){throw new RuntimeException(e);}};
                Exception e=rejected(()->PlaybackSources.resolve(SID,VID,"1080p"));check(calls.get()==2&&!PlaybackSources.canLowerQuality(e),"second reply still passes identity contract, never advances episode");
            });
            group("pre-interrupted lookup sends no request",()->{
                AtomicInteger calls=new AtomicInteger();host.callback=script->calls.incrementAndGet();Thread.currentThread().interrupt();
                Exception e=rejected(()->PlaybackSources.resolve(SID,VID,"1080p"));check(e instanceof InterruptedIOException&&calls.get()==0,"pre-cancelled source cannot start a retry or network");Thread.interrupted();
            });
            System.out.println("EMPTY_APP_RESPONSE_"+(failed==0?"OK":"FAILED")+" groupsPassed="+passed+" groupsFailed="+failed+" checks="+checks+" maxInitialLookups=2 qualityDowngrade=false mediaRetryUnchanged=true externalNetwork=0");
            if(failed>0)throw new AssertionError("empty response recovery regression");
        } finally { PlaybackSources.detach(host);Thread.interrupted();for(File p:directory.listFiles())p.delete();directory.delete(); }
    }
}
