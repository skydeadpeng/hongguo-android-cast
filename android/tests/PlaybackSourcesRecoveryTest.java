package io.github.jackotom.hongguo.android;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Narrow source-recovery gate. Production resolver; synthetic transport/UI, no network. */
public final class PlaybackSourcesRecoveryTest {
    private static int checks,passed,failed;
    private static final String SID="710001",VID="710004",KEY="00112233445566778899aabbccddeeff";
    private interface Checked {void run()throws Exception;}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void rejects(Checked action,String label)throws Exception{try{action.run();}catch(Exception expected){checks++;return;}throw new AssertionError(label);}
    private static void group(String name,Checked action){try{action.run();passed++;System.out.println("PASS "+name);}catch(Throwable error){failed++;System.out.println("FAIL "+name+": "+error.getClass().getSimpleName()+" "+error.getMessage());}finally{Thread.interrupted();}}
    private static PlaybackSources.Plan recover(String sid,String vid,String quality,boolean web)throws Exception{
        Method method;
        try{method=PlaybackSources.class.getDeclaredMethod("recover",String.class,String.class,String.class,boolean.class);}catch(NoSuchMethodException error){throw new AssertionError("missing bounded source recover API");}
        try{return (PlaybackSources.Plan)method.invoke(null,sid,vid,quality,web);}catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw(Exception)cause;throw(Error)cause;}
    }
    @SuppressWarnings("unchecked") private static Map<String,Long> penalties()throws Exception{Field field=PlaybackSources.class.getDeclaredField("failedWebUntil");field.setAccessible(true);return(Map<String,Long>)field.get(null);}
    private static JSONObject model(String name,String quality)throws Exception{
        JSONObject info=new JSONObject().put("url","https://cdn.qznovelvod.com/synthetic-"+name+".mp4").put("vid",VID).put("seriesId",SID).put("source","app").put("contentKey",KEY).put("requestedQuality",quality);
        if(!quality.equals("auto")){int edge=Integer.parseInt(quality.substring(0,quality.length()-1));info.put("width",edge).put("height",edge*2);}
        return info;
    }
    private static JSONObject variants(JSONObject primary,JSONObject[] extras)throws Exception{JSONArray rows=new JSONArray();for(JSONObject row:extras)rows.put(row);return new JSONObject(primary.toString()).put("variants",rows);}
    private static JSONArray args(String script)throws Exception{return new JSONArray("["+script.substring(script.lastIndexOf('(')+1,script.length()-1)+"]");}
    private static void reply(MainActivity host,JSONObject info,int[]calls,boolean error){host.callback=script->{try{calls[0]++;JSONArray values=args(script);JSONObject response=new JSONObject().put("requestId",values.getString(0)).put("seriesId",values.getString(1)).put("vid",values.getString(2)).put("requestedQuality",values.getString(3));if(error)response.put("error","synthetic App unavailable");else response.put("playInfo",info);PlaybackSources.result(response);}catch(Exception failure){throw new RuntimeException(failure);}};}
    private static void resetPenalty()throws Exception{try{Map<String,Long> map=penalties();synchronized(map){map.clear();}}catch(NoSuchFieldException baseline){/* Baseline still runs its real transfer path. */}}
    private static void clean(File file)throws Exception{if(file.exists())check(file.delete(),"synthetic prepared file removed");}
    private static void pausedInterrupt(Checked action)throws Exception{
        AtomicReference<Throwable> failure=new AtomicReference<>();boolean[]flag={false};
        Thread worker=new Thread(()->{try{action.run();failure.set(new AssertionError("interrupted recovery returned success"));}catch(Throwable error){failure.set(error);flag[0]=Thread.currentThread().isInterrupted();}},"source-recovery-interrupt");
        worker.start();long limit=System.nanoTime()+2_000_000_000L;while(worker.isAlive()&&worker.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<limit)Thread.sleep(2);
        check(worker.isAlive()&&worker.getState()==Thread.State.TIMED_WAITING,"recovery reaches its bounded backoff");worker.interrupt();worker.join(1500);
        check(!worker.isAlive()&&failure.get() instanceof InterruptedException&&flag[0],"backoff interruption stops promptly and preserves interrupt status");
    }
    public static void main(String[]unused)throws Exception{
        File dir=Files.createTempDirectory("hongguo-source-recovery-").toFile();MainActivity host=new MainActivity();PlaybackSources.attach(host);
        try{
            group("failed web reconnect is fresh App and scoped five-minute priority",()->{
                resetPenalty();NetworkGuard.reset();EpisodeResolver.result="https://cdn.example.net/synthetic-valid-web.mp4";int[]calls={0};reply(host,model("recover-web","auto"),calls,false);
                long started=System.nanoTime();PlaybackSources.Plan recovered=recover(SID,VID,"auto",true);long elapsed=System.nanoTime()-started;
                check(recovered.source.equals("app")&&calls[0]==1&&EpisodeResolver.calls==0,"reconnect skips failed web and obtains exactly one fresh App model");check(elapsed>=450_000_000L&&elapsed<3_000_000_000L,"one bounded 500ms backoff precedes recovery");
                Map<String,Long> map=penalties();long remaining=map.get(SID+"\n"+VID)-System.nanoTime();check(remaining>295_000_000_000L&&remaining<=300_000_000_000L,"web failure priority is five minutes rather than permanent");
                PlaybackSources.Plan same=PlaybackSources.resolve(SID,VID,"auto");check(same.source.equals("app")&&calls[0]==2&&EpisodeResolver.calls==0,"ordinary auto resolution respects App-first penalty");
                PlaybackSources.Plan otherSeries=PlaybackSources.resolve("710002",VID,"auto"),otherEpisode=PlaybackSources.resolve(SID,"710005","auto");check(otherSeries.source.equals("web")&&otherEpisode.source.equals("web")&&EpisodeResolver.calls==2,"penalty is scoped by both series and episode");
                synchronized(map){map.put(SID+"\n"+VID,System.nanoTime()-1);}check(PlaybackSources.resolve(SID,VID).source.equals("web")&&EpisodeResolver.calls==3,"expired failure restores original web priority");
            });
            group("penalty retains one bounded auto web last-resort but explicit never downgrades",()->{
                resetPenalty();NetworkGuard.reset();int[]calls={0};reply(host,model("penalty-primer","auto"),calls,false);recover(SID,VID,"auto",true);
                calls[0]=0;EpisodeResolver.result="https://cdn.example.net/synthetic-last-resort.mp4";reply(host,null,calls,true);
                check(PlaybackSources.resolve(SID,VID).source.equals("web")&&calls[0]==1&&EpisodeResolver.calls==1,"App unavailable permits one auto web last-resort");
                EpisodeResolver.result=null;calls[0]=0;int before=EpisodeResolver.calls;rejects(()->PlaybackSources.resolve(SID,VID),"all unavailable sources falsely resolved");check(calls[0]==1&&EpisodeResolver.calls==before+1,"failed App and web do not cycle again");
                EpisodeResolver.result="https://cdn.example.net/must-not-downgrade.mp4";calls[0]=0;before=EpisodeResolver.calls;rejects(()->PlaybackSources.resolve(SID,VID,"1080p"),"explicit request fell back to auto web");check(calls[0]==1&&EpisodeResolver.calls==before,"explicit quality never uses unmeasured last-resort web");
            });
            group("reconnect preserves explicit quality and verifies fresh dimensions",()->{
                resetPenalty();NetworkGuard.reset();for(String quality:new String[]{"720p","1080p"}){int[]calls={0};reply(host,model("explicit-recover-"+quality,quality),calls,false);PlaybackSources.Plan plan=recover(SID,VID,quality,false);check(plan.requestedQuality.equals(quality)&&MediaQuality.meets(plan.width,plan.height,quality)&&calls[0]==1&&EpisodeResolver.calls==0,"fresh reconnect preserves exact requested quality");}
                int[]calls={0};reply(host,model("explicit-low","1080p").put("width",720),calls,false);rejects(()->recover(SID,VID,"1080p",false),"reconnect accepted downgraded fresh metadata");check(calls[0]==1&&NetworkGuard.downloads==0,"wrong resolution stops before transfer");
            });
            group("EOF reset and typed non-media response get exactly one clean fresh model",()->{
                resetPenalty();for(String outcome:new String[]{"eof","reset","media-response"}){NetworkGuard.reset();JSONObject old=model("old-"+outcome,"auto"),fresh=model("fresh-"+outcome,"auto");String url=old.getString("url");PlaybackSources.Plan original=PlaybackSources.register(old,VID,false);NetworkGuard.outcomes.put(url,outcome);int[]calls={0};reply(host,fresh,calls,false);File part=new File(dir,outcome+".part");long began=System.nanoTime();PlaybackSources.download(url,part,64);check(calls[0]==1&&NetworkGuard.attempted.equals(Arrays.asList(url,fresh.getString("url"))),"temporary transfer failure refreshes model once: "+outcome);check(System.nanoTime()-began>=450_000_000L,"download refresh uses bounded backoff: "+outcome);check(NetworkGuard.cleanAtStart.equals(Arrays.asList(true,true)),"failed body removed before renewed candidate: "+outcome);check(PlaybackSources.find(url)==original&&PlaybackSources.find(fresh.getString("url"))==null,"refresh does not change original job or registry identity");clean(part);}
            });
            group("mixed temporary candidates remain bounded to two rounds of eight",()->{
                NetworkGuard.reset();JSONObject old=model("cap-old","auto"),fresh=model("cap-new","auto");JSONObject[]a=new JSONObject[7],b=new JSONObject[7];for(int i=0;i<7;i++){a[i]=model("cap-old-"+i,"auto");b[i]=model("cap-new-"+i,"auto");NetworkGuard.outcomes.put(a[i].getString("url"),i%2==0?"reset":"http503");NetworkGuard.outcomes.put(b[i].getString("url"),"media-response");}NetworkGuard.outcomes.put(old.getString("url"),"eof");NetworkGuard.outcomes.put(fresh.getString("url"),"media-response");PlaybackSources.register(variants(old,a),VID,false);int[]calls={0};reply(host,variants(fresh,b),calls,false);File part=new File(dir,"bounded.part");rejects(()->PlaybackSources.download(old.getString("url"),part,64),"exhausted transfers returned playable media");check(calls[0]==1&&NetworkGuard.attempted.size()==16&&!part.exists(),"only one model refresh, sixteen transfers maximum, no failed bytes retained");
            });
            group("web binary failure also changes the following ordinary source priority",()->{
                resetPenalty();NetworkGuard.reset();JSONObject web=new JSONObject().put("url","https://cdn.example.net/synthetic-failed-web.mp4").put("source","web").put("seriesId",SID).put("vid",VID);PlaybackSources.register(web,VID,false);NetworkGuard.outcomes.put(web.getString("url"),"eof");int[]calls={0};reply(host,model("web-binary-fresh","auto"),calls,false);File part=new File(dir,"web-binary.part");PlaybackSources.download(web.getString("url"),part,64);EpisodeResolver.result="https://cdn.example.net/old-web-still-looks-valid.mp4";check(PlaybackSources.resolve(SID,VID).source.equals("app")&&calls[0]==2&&EpisodeResolver.calls==0,"real web GET failure affects later auto resolve rather than selecting same web again");clean(part);
            });
            group("both reconnect and download backoff are promptly cancellable",()->{
                resetPenalty();NetworkGuard.reset();int[]calls={0};reply(host,model("never-called","auto"),calls,false);pausedInterrupt(()->recover(SID,VID,"auto",false));check(calls[0]==0&&EpisodeResolver.calls==0,"cancelled reconnect never dispatches App request");
                NetworkGuard.reset();JSONObject old=model("cancel-download","auto");PlaybackSources.register(old,VID,false);NetworkGuard.outcomes.put(old.getString("url"),"eof");File part=new File(dir,"cancelled.part");pausedInterrupt(()->PlaybackSources.download(old.getString("url"),part,64));check(calls[0]==0&&NetworkGuard.downloads==1&&!part.exists(),"cancelled refresh neither retries nor keeps partial media");
            });
            group("fatal CENC and generic errors still do not refresh",()->{
                NetworkGuard.reset();int[]calls={0};reply(host,model("unused-fatal","auto"),calls,false);for(String outcome:new String[]{"decrypt","fail","http401"}){JSONObject old=model("fatal-"+outcome,"auto");String url=old.getString("url");PlaybackSources.register(old,VID,false);if(outcome.equals("decrypt"))CencDecryptor.failures.add(url);else NetworkGuard.outcomes.put(url,outcome);File part=new File(dir,"fatal-"+outcome+".part");rejects(()->PlaybackSources.download(url,part,64),"fatal failure returned success");check(calls[0]==0&&!part.exists(),"fatal failure is not refreshed: "+outcome);}
            });
            group("invalid recovery identity and quality reject before any work",()->{
                NetworkGuard.reset();int[]calls={0};reply(host,model("unused-invalid","auto"),calls,false);rejects(()->recover("_",VID,"auto",true),"invalid series accepted");rejects(()->recover(SID,"../", "auto",true),"invalid episode accepted");rejects(()->recover(SID,VID,"4k",true),"invalid quality accepted");check(calls[0]==0&&EpisodeResolver.calls==0&&NetworkGuard.downloads==0,"invalid reconnect never reaches provider or media transport");
            });
            System.out.println("SOURCE_RECOVERY_"+(failed==0?"OK":"FAILED")+" groupsPassed="+passed+" groupsFailed="+failed+" checks="+checks+" networkRequests=0 deviceTests=not_run");
            if(failed!=0)throw new AssertionError("source recovery gate failed");
        }finally{PlaybackSources.detach(host);Thread.interrupted();for(File file:dir.listFiles())file.delete();dir.delete();}
    }
}
