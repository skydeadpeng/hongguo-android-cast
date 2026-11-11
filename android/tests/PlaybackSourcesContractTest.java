package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import org.json.*;

/** Production plan/cache contracts with explicit offline transport/UI/decryptor test doubles. */
public final class PlaybackSourcesContractTest {
    private static int checks;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value,String label) { checks++;if(!value)throw new AssertionError(label); }
    private static void fails(Checked action,String label) throws Exception { try{action.run();}catch(Exception expected){checks++;return;}throw new AssertionError(label); }
    private static JSONObject plan(String url,String source) throws Exception { return new JSONObject().put("url",url).put("vid","1004").put("source",source); }
    private static final String FIRST_KEY="00112233445566778899aabbccddeeff", SECOND_KEY="ffeeddccbbaa99887766554433221100";
    private static byte[] keyBytes(String text){byte[] result=new byte[16];for(int i=0;i<16;i++)result[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);return result;}
    private static JSONObject candidate(String name,String key) throws Exception {
        return plan("https://cdn.qznovelvod.com/"+name+".mp4","app").put("contentKey",key==null?JSONObject.NULL:key).put("userAgent","offline-"+name).put("referer","https://novelquickapp.com/");
    }
    private static JSONObject variants(JSONObject primary,JSONObject... candidates) throws Exception {
        JSONArray list=new JSONArray().put(new JSONObject(primary.toString()));for(JSONObject value:candidates)list.put(value);
        return new JSONObject(primary.toString()).put("variants",list);
    }
    private static PlaybackCachePolicy variantCache(File directory,String name){return new PlaybackCachePolicy(new File(directory,name),128,64,PlaybackSources::download);}
    private static PlaybackCachePolicy qualityCache(File directory,String name,String quality){return new PlaybackCachePolicy(new File(directory,name),128,64,(url,file,max)->PlaybackSources.download(url,file,max,quality));}
    private static void noParts(File directory,String name){File[] files=new File(directory,name).listFiles();check(files!=null&&Arrays.stream(files).noneMatch(file->file.getName().endsWith(".part")),"candidate attempts leave no partial file");}
    private static void variantContracts(File directory) throws Exception {
        NetworkGuard.reset();
        JSONObject first=candidate("get-fails-primary",FIRST_KEY), second=candidate("get-succeeds-backup",SECOND_KEY);
        String firstUrl=first.getString("url"), secondUrl=second.getString("url");
        PlaybackSources.Plan retained=PlaybackSources.register(variants(first,second),"1004",true);
        check(AppTransport.heads==0&&NetworkGuard.heads==0,"App registration never gates valid GET variants on HEAD support");
        check(retained.json().getJSONArray("variants").length()==1,"primary duplicate excluded from retained backup list");
        check(retained.json().getJSONArray("variants").getJSONObject(0).getString("contentKey").equals(SECOND_KEY),"backup retains its own preparation parameter");
        boolean immutable=false;try{retained.variants.add(null);}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"retained backup list immutable");
        NetworkGuard.outcomes.put(firstUrl,"fail");CencDecryptor.expectedKeys.put(secondUrl,keyBytes(SECOND_KEY));
        PlaybackCachePolicy cache=variantCache(directory,"variant-get");
        File ready=cache.fetch("series","1004",4,firstUrl);
        check(NetworkGuard.attempted.equals(Arrays.asList(firstUrl,secondUrl)),"primary full GET failure advances to exact backup URL");
        check(CencDecryptor.calls==1&&CencDecryptor.keyMatches.equals(Arrays.asList(true)),"only successful backup uses its own CENC key");
        check(NetworkGuard.agents.get(secondUrl).equals(second.getString("userAgent")),"each backup retains its own user agent");
        check(Arrays.equals(Files.readAllBytes(ready.toPath()),new byte[]{7,8,9})&&cache.status().files==1,"only prepared backup is published");
        check(NetworkGuard.cleanAtStart.equals(Arrays.asList(true,true)),"failed primary partial removed before backup begins");noParts(directory,"variant-get");

        NetworkGuard.reset();first=candidate("timeout-primary",FIRST_KEY);second=candidate("timeout-backup",SECOND_KEY);firstUrl=first.getString("url");secondUrl=second.getString("url");
        PlaybackSources.register(variants(first,second),"1004",false);NetworkGuard.outcomes.put(firstUrl,"timeout");CencDecryptor.expectedKeys.put(secondUrl,keyBytes(SECOND_KEY));
        ready=variantCache(directory,"variant-timeout").fetch("series","1004",4,firstUrl);
        check(NetworkGuard.attempted.equals(Arrays.asList(firstUrl,secondUrl))&&ready.isFile(),"SocketTimeoutException is retryable, not user cancellation");

        NetworkGuard.reset();first=candidate("decrypt-fails-primary",FIRST_KEY);second=candidate("decrypt-good-backup",SECOND_KEY);firstUrl=first.getString("url");secondUrl=second.getString("url");
        PlaybackSources.register(variants(first,second),"1004",false);
        CencDecryptor.expectedKeys.put(firstUrl,keyBytes(FIRST_KEY));CencDecryptor.expectedKeys.put(secondUrl,keyBytes(SECOND_KEY));CencDecryptor.failures.add(firstUrl);
        ready=variantCache(directory,"variant-decrypt").fetch("series","1004",4,firstUrl);
        check(CencDecryptor.calls==2&&CencDecryptor.keyMatches.equals(Arrays.asList(true,true)),"both candidates use distinct matching keys during decryption fallback");
        check(NetworkGuard.cleanAtStart.equals(Arrays.asList(true,true)),"failed decrypted sample removed before next candidate");
        check(Arrays.equals(Files.readAllBytes(ready.toPath()),new byte[]{7,8,9}),"decryption failure cannot publish primary ciphertext");

        NetworkGuard.reset();first=candidate("encrypted-fails-primary",FIRST_KEY);second=candidate("clear-backup",null);firstUrl=first.getString("url");secondUrl=second.getString("url");
        PlaybackSources.register(variants(first,second),"1004",false);NetworkGuard.outcomes.put(firstUrl,"fail");NetworkGuard.outcomes.put(secondUrl,"clear");
        ready=variantCache(directory,"variant-clear").fetch("series","1004",4,firstUrl);
        check(CencDecryptor.calls==0&&Arrays.equals(Files.readAllBytes(ready.toPath()),new byte[]{4,5,6}),"explicit null-key clear backup never inherits primary key");

        NetworkGuard.reset();first=candidate("inherit-primary",FIRST_KEY);second=new JSONObject().put("url","https://cdn.qznovelvod.com/inherit-backup.mp4");firstUrl=first.getString("url");secondUrl=second.getString("url");
        PlaybackSources.register(variants(first,second),"1004",false);NetworkGuard.outcomes.put(firstUrl,"fail");CencDecryptor.expectedKeys.put(secondUrl,keyBytes(FIRST_KEY));
        ready=variantCache(directory,"variant-inherit").fetch("series","1004",4,firstUrl);
        check(CencDecryptor.keyMatches.equals(Arrays.asList(true))&&ready.isFile(),"missing backup key inherits primary only when omitted");
        check(NetworkGuard.agents.get(secondUrl).equals(first.getString("userAgent")),"omitted backup headers inherit selected primary metadata");

        NetworkGuard.reset();first=candidate("all-fail-first",FIRST_KEY);second=candidate("all-fail-second",SECOND_KEY);final String allFailUrl=first.getString("url");
        PlaybackSources.register(variants(first,second),"1004",false);NetworkGuard.outcomes.put(allFailUrl,"fail");NetworkGuard.outcomes.put(second.getString("url"),"fail");
        PlaybackCachePolicy failed=variantCache(directory,"variant-all-fail");
        fails(()->failed.fetch("series","1004",4,allFailUrl),"all failed variants published data");
        check(NetworkGuard.attempted.size()==2&&failed.status().files==0,"all failures report failure rather than ready media");noParts(directory,"variant-all-fail");

        NetworkGuard.reset();first=candidate("cancel-first",FIRST_KEY);second=candidate("must-not-run-after-cancel",SECOND_KEY);final String cancelUrl=first.getString("url");
        PlaybackSources.register(variants(first,second),"1004",false);NetworkGuard.outcomes.put(cancelUrl,"interrupt");
        PlaybackCachePolicy cancelled=variantCache(directory,"variant-cancel");boolean interrupted=false;
        try{cancelled.fetch("series","1004",4,cancelUrl);}catch(Exception expected){interrupted=Thread.currentThread().isInterrupted();}finally{Thread.interrupted();}
        check(interrupted&&NetworkGuard.attempted.equals(Arrays.asList(cancelUrl)),"interrupted download stops without trying backup");
        check(cancelled.status().files==0,"cancelled variants never publish");noParts(directory,"variant-cancel");

        NetworkGuard.reset();first=candidate("cenc-cancel-first",FIRST_KEY);second=candidate("must-not-run-after-cenc-cancel",SECOND_KEY);final String cencCancelUrl=first.getString("url");
        PlaybackSources.register(variants(first,second),"1004",false);CencDecryptor.interruptions.add(cencCancelUrl);
        PlaybackCachePolicy cancelledCenc=variantCache(directory,"variant-cenc-cancel");interrupted=false;
        try{cancelledCenc.fetch("series","1004",4,cencCancelUrl);}catch(Exception expected){interrupted=Thread.currentThread().isInterrupted();}finally{Thread.interrupted();}
        check(interrupted&&NetworkGuard.attempted.equals(Arrays.asList(cencCancelUrl)),"CENC interruption stops without trying backup");
        check(cancelledCenc.status().files==0,"CENC-cancelled partial never published");noParts(directory,"variant-cenc-cancel");

        NetworkGuard.reset();first=candidate("limit-primary",FIRST_KEY);JSONObject[] many=new JSONObject[7];
        for(int i=0;i<many.length;i++)many[i]=candidate("limit-backup-"+i,SECOND_KEY);
        final String cappedUrl=first.getString("url");PlaybackSources.Plan capped=PlaybackSources.register(variants(first,many),"1004",false);
        check(capped.variants.size()==7&&capped.json().getJSONArray("variants").length()==7,"native plan retains primary plus seven backups");
        NetworkGuard.outcomes.put(cappedUrl,"fail");for(JSONObject item:many)NetworkGuard.outcomes.put(item.getString("url"),"fail");
        PlaybackCachePolicy limited=variantCache(directory,"variant-limit");fails(()->limited.fetch("series","1004",4,cappedUrl),"bounded exhausted variants published");
        check(NetworkGuard.attempted.size()==8,"candidate attempts bounded to eight including primary");noParts(directory,"variant-limit");
        final JSONObject limitedInput=variants(first,Arrays.copyOf(many,7));
        limitedInput.getJSONArray("variants").put(candidate("ninth-candidate",SECOND_KEY));
        fails(()->PlaybackSources.register(limitedInput,"1004",false),"more than eight input variant rows accepted");
        JSONArray eightBackups=new JSONArray();for(int i=0;i<8;i++)eightBackups.put(candidate("cap-unique-"+i,SECOND_KEY));
        PlaybackSources.Plan capUnique=PlaybackSources.register(new JSONObject(first.toString()).put("variants",eightBackups),"1004",false);
        check(capUnique.variants.size()==7,"eight backup-only rows capped to seven plus primary");
        eightBackups.getJSONObject(7).put("vid","1005");
        final JSONObject invalidTruncated=new JSONObject(first.toString()).put("variants",eightBackups);
        fails(()->PlaybackSources.register(invalidTruncated,"1004",false),"discarded-over-cap variant escaped identity validation");

        NetworkGuard.reset();JSONObject clean=candidate("identity-primary",FIRST_KEY);
        for(JSONObject invalid:new JSONObject[]{new JSONObject(candidate("foreign-episode",SECOND_KEY).toString()).put("vid","1005"),new JSONObject(candidate("bad-domain",SECOND_KEY).toString()).put("url","https://qznovelvod.com.evil.net/x"),new JSONObject(candidate("bad-key",SECOND_KEY).toString()).put("contentKey","invalid"),new JSONObject(candidate("bad-ua",SECOND_KEY).toString()).put("userAgent","bad\r\nInjected: x"),new JSONObject(candidate("bad-ref",SECOND_KEY).toString()).put("referer","https://evil.net/")}){
            boolean rejected=false;try{PlaybackSources.register(variants(clean,invalid),"1004",false);}catch(Exception expected){rejected=true;}
            check(rejected,"each variant enforces episode/domain/key/header identity, not just primary");
        }
        check(NetworkGuard.downloads==0,"invalid variant contracts rejected before any transfer");
        PlaybackSources.Plan deduplicated=PlaybackSources.register(variants(clean,new JSONObject(clean.toString()),candidate("identity-backup",SECOND_KEY)),"1004",false);
        check(deduplicated.json().getJSONArray("variants").length()==1,"identical URL/key candidates are not retried repeatedly");
        NetworkGuard.reset();PlaybackSources.register(plan("https://cdn.example.net/preflight-web.mp4","web"),"1004",true);
        check(NetworkGuard.heads==1,"web source retains its existing HEAD validation behavior");
    }
    private static final String SID="123456";
    private static JSONArray callbackArguments(String script) throws Exception {
        return new JSONArray("["+script.substring(script.lastIndexOf('(')+1,script.length()-1)+"]");
    }
    private static void refreshReply(MainActivity host,JSONObject model,int[] callbacks) {
        host.callback=script->{try{
            callbacks[0]++;JSONArray values=callbackArguments(script);
            check(values.length()==4&&SID.equals(values.getString(1))&&"1004".equals(values.getString(2))&&"auto".equals(values.getString(3)),"auto refresh requests exact original series, episode and quality with fourth argument");
            PlaybackSources.result(new JSONObject().put("requestId",values.getString(0)).put("seriesId",values.getString(1)).put("vid",values.getString(2)).put("playInfo",model));
        }catch(Exception error){throw new RuntimeException(error);}};
    }
    private static void refreshContracts(File directory) throws Exception {
        MainActivity host=new MainActivity();PlaybackSources.attach(host);
        try {
            NetworkGuard.reset();
            JSONObject primary=candidate("refresh-first-403",FIRST_KEY).put("seriesId",SID),backup=candidate("refresh-backup-404",FIRST_KEY);
            JSONObject fresh=candidate("refresh-fresh-410",FIRST_KEY),freshBackup=candidate("refresh-fresh-working",SECOND_KEY);
            String originalUrl=primary.getString("url"),finalUrl=freshBackup.getString("url");
            PlaybackSources.Plan original=PlaybackSources.register(variants(primary,backup),"1004",false);
            check(SID.equals(original.seriesId)&&SID.equals(original.json().getString("seriesId")),"series identity survives plan serialization");
            check(SID.equals(original.variants.get(0).seriesId),"variant inherits original series identity");
            NetworkGuard.outcomes.put(originalUrl,"http403");NetworkGuard.outcomes.put(backup.getString("url"),"http404");NetworkGuard.outcomes.put(fresh.getString("url"),"http410");
            CencDecryptor.expectedKeys.put(finalUrl,keyBytes(SECOND_KEY));int[] callbacks={0};refreshReply(host,variants(fresh,freshBackup),callbacks);
            PlaybackCachePolicy cache=variantCache(directory,"refresh-success");File ready=cache.fetch(SID,"1004",4,originalUrl);
            check(callbacks[0]==1,"exhausted transient candidates refresh the App model exactly once");
            check(NetworkGuard.attempted.equals(Arrays.asList(originalUrl,originalUrl,backup.getString("url"),fresh.getString("url"),finalUrl)),"both rounds keep candidate order including one same-URL 403 retry");
            check(NetworkGuard.cleanAtStart.equals(Arrays.asList(true,true,true,true,true)),"old and refreshed candidate partials are removed before every attempt");
            check(NetworkGuard.agents.get(finalUrl).equals(freshBackup.getString("userAgent"))&&CencDecryptor.keyMatches.equals(Arrays.asList(true)),"refreshed candidate keeps its own headers and CENC key");
            check(Arrays.equals(Files.readAllBytes(ready.toPath()),new byte[]{7,8,9}),"second-round prepared bytes published");
            check(PlaybackSources.find(originalUrl)==original&&PlaybackSources.find(fresh.getString("url"))==null,"refresh only updates local plan, not cache-job identity or global LRU");noParts(directory,"refresh-success");

            for(String outcome:new String[]{"http403","http404","http408","http410","http500","http503","http599","timeout","deadline"}) {
                NetworkGuard.reset();primary=candidate("temporary-"+outcome,FIRST_KEY).put("seriesId",SID);fresh=candidate("renewed-"+outcome,SECOND_KEY);
                final String url=primary.getString("url");PlaybackSources.register(primary,"1004",false);NetworkGuard.outcomes.put(url,outcome);
                callbacks=new int[]{0};refreshReply(host,fresh,callbacks);CencDecryptor.expectedKeys.put(fresh.getString("url"),keyBytes(SECOND_KEY));
                ready=variantCache(directory,"status-"+outcome).fetch(SID,"1004",4,url);
                check(callbacks[0]==1&&NetworkGuard.attempted.size()==(outcome.equals("http403")?3:2)&&ready.isFile(),"fixed temporary status/timeout refreshes once: "+outcome);
            }

            NetworkGuard.reset();primary=candidate("two-round-limit-old",FIRST_KEY).put("seriesId",SID);fresh=candidate("two-round-limit-new",SECOND_KEY);
            JSONObject[] oldBackups=new JSONObject[7],newBackups=new JSONObject[7];
            for(int i=0;i<7;i++){oldBackups[i]=candidate("two-round-old-"+i,FIRST_KEY);newBackups[i]=candidate("two-round-new-"+i,SECOND_KEY);}
            final String limitedUrl=primary.getString("url");PlaybackSources.register(variants(primary,oldBackups),"1004",false);
            for(JSONObject item:oldBackups)NetworkGuard.outcomes.put(item.getString("url"),"http403");
            for(JSONObject item:newBackups)NetworkGuard.outcomes.put(item.getString("url"),"http503");
            NetworkGuard.outcomes.put(limitedUrl,"http403");NetworkGuard.outcomes.put(fresh.getString("url"),"http503");
            callbacks=new int[]{0};refreshReply(host,variants(fresh,newBackups),callbacks);
            PlaybackCachePolicy limited=variantCache(directory,"refresh-limit");fails(()->limited.fetch(SID,"1004",4,limitedUrl),"two exhausted rounds falsely published media");
            check(callbacks[0]==1&&NetworkGuard.attempted.size()==16,"two rounds cap total transfer attempts at sixteen and refresh callbacks at one");
            check(limited.status().files==0,"two exhausted rounds never publish ciphertext");noParts(directory,"refresh-limit");

            for(String outcome:new String[]{"interrupt","decrypt","no-sid","fatal-http","invalid-transfer"}) {
                NetworkGuard.reset();primary=candidate("no-refresh-"+outcome,FIRST_KEY);if(!outcome.equals("no-sid"))primary.put("seriesId",SID);
                final String url=primary.getString("url");PlaybackSources.register(primary,"1004",false);
                if(outcome.equals("decrypt"))CencDecryptor.failures.add(url);
                else NetworkGuard.outcomes.put(url,outcome.equals("interrupt")?"interrupt":outcome.equals("fatal-http")?"http401":outcome.equals("invalid-transfer")?"fail":"http403");
                callbacks=new int[]{0};refreshReply(host,candidate("unused-"+outcome,SECOND_KEY),callbacks);
                PlaybackCachePolicy failed=variantCache(directory,"no-refresh-"+outcome);boolean rejected=false;
                try{failed.fetch(SID,"1004",4,url);}catch(Exception expected){rejected=true;}finally{Thread.interrupted();}
                check(rejected&&callbacks[0]==0&&NetworkGuard.attempted.size()==(outcome.equals("no-sid")?2:1),"no model refresh for cancellation/CENC/no-sid/non-temporary failure: "+outcome);
                check(failed.status().files==0,"non-refreshable failure never publishes");noParts(directory,"no-refresh-"+outcome);
            }

            for(String wrong:new String[]{"seriesId","vid"}) {
                NetworkGuard.reset();primary=candidate("bad-refreshed-"+wrong,FIRST_KEY).put("seriesId",SID);final String url=primary.getString("url");
                PlaybackSources.register(primary,"1004",false);NetworkGuard.outcomes.put(url,"http403");
                fresh=candidate("wrong-model-"+wrong,SECOND_KEY).put(wrong,"999999");callbacks=new int[]{0};refreshReply(host,fresh,callbacks);
                PlaybackCachePolicy failed=variantCache(directory,"refresh-identity-"+wrong);fails(()->failed.fetch(SID,"1004",4,url),"wrong refresh identity accepted");
                check(callbacks[0]==1&&NetworkGuard.attempted.size()==2,"wrong refreshed series/episode rejected before a second-round transfer");noParts(directory,"refresh-identity-"+wrong);
            }
            NetworkGuard.reset();primary=candidate("envelope-identity",FIRST_KEY).put("seriesId",SID);final String envelopeUrl=primary.getString("url");
            PlaybackSources.register(primary,"1004",false);NetworkGuard.outcomes.put(envelopeUrl,"http403");int[] envelopeRejected={0},envelopeCallbacks={0};
            host.callback=script->{try{envelopeCallbacks[0]++;JSONArray values=callbackArguments(script);
                try{PlaybackSources.result(new JSONObject().put("requestId",values.getString(0)).put("seriesId","999999").put("vid",values.getString(2)).put("playInfo",candidate("must-not-use",SECOND_KEY)));}catch(IOException expected){envelopeRejected[0]++;}
                PlaybackSources.result(new JSONObject().put("requestId",values.getString(0)).put("seriesId",values.getString(1)).put("vid",values.getString(2)).put("error","synthetic rejected callback"));
            }catch(Exception error){throw new RuntimeException(error);}};
            fails(()->variantCache(directory,"refresh-envelope").fetch(SID,"1004",4,envelopeUrl),"mismatched callback envelope accepted");
            check(envelopeCallbacks[0]==1&&envelopeRejected[0]==1&&NetworkGuard.attempted.size()==2,"requestId callback rejects a foreign series and never transfers its model");

            final JSONObject invalidVariant=variants(candidate("sid-owner",FIRST_KEY).put("seriesId",SID),candidate("sid-foreign",SECOND_KEY).put("seriesId","999999"));
            fails(()->PlaybackSources.register(invalidVariant,"1004",false),"foreign variant seriesId accepted");
            final JSONObject invalidSid=candidate("invalid-sid",FIRST_KEY).put("seriesId","_");
            fails(()->PlaybackSources.register(invalidSid,"1004",false),"non-numeric seriesId accepted");
        } finally { PlaybackSources.detach(host); Thread.interrupted(); }
    }
    private static JSONObject sized(String name,String requested,int width,int height) throws Exception {
        return candidate(name,FIRST_KEY).put("requestedQuality",requested).put("width",width).put("height",height).put("quality","server-label");
    }
    private static JSONObject envelope(JSONArray values) throws Exception {
        return new JSONObject().put("requestId",values.getString(0)).put("seriesId",values.getString(1)).put("vid",values.getString(2)).put("requestedQuality",values.getString(3));
    }
    private static void qualityReply(MainActivity host,JSONObject model,String expected,int[] callbacks) {
        host.callback=script->{try{
            callbacks[0]++;JSONArray values=callbackArguments(script);
            check(values.length()==4&&SID.equals(values.getString(1))&&"1004".equals(values.getString(2))&&expected.equals(values.getString(3)),"native App request binds requested quality as fourth argument");
            PlaybackSources.result(envelope(values).put("playInfo",model));
        }catch(Exception error){throw new RuntimeException(error);}};
    }
    private static void qualityContracts(File directory) throws Exception {
        NetworkGuard.reset();
        PlaybackSources.Plan automatic=PlaybackSources.register(candidate("quality-auto-unknown",FIRST_KEY).put("quality","1080p"),"1004",false);
        check(automatic.requestedQuality.equals("auto")&&automatic.width==0&&automatic.height==0,"old App plan without dimensions remains auto");
        check(automatic.json().getString("requestedQuality").equals("auto")&&automatic.json().getString("quality").equals("1080p")&&!automatic.json().has("width"),"request quality and optional server label serialize independently");
        for(String requested:new String[]{"720p","1080p"}) {
            int edge=Integer.parseInt(requested.substring(0,requested.length()-1));
            for(boolean portrait:new boolean[]{true,false}) {
                int width=portrait?edge:edge*2,height=portrait?edge*2:edge;
                PlaybackSources.Plan explicit=PlaybackSources.register(sized("quality-"+requested+"-"+portrait,requested,width,height),"1004",true);
                check(explicit.requestedQuality.equals(requested)&&explicit.width==width&&explicit.height==height,"explicit quality accepts real portrait or landscape short edge: "+requested);
                check(explicit.json().getInt("width")==width&&explicit.json().getInt("height")==height&&explicit.quality.equals("server-label"),"dimensions and server label survive plan serialization");
            }
        }
        PlaybackSources.Plan api=PlaybackSources.register(sized("quality-api","720p",1280,720).put("source","api"),"1004",true);
        check(api.source.equals("api")&&NetworkGuard.heads==0&&AppTransport.heads==0,"dimension-confirmed API source does not use web preflight");
        PlaybackSources.Plan numeric=PlaybackSources.register(sized("quality-integer-numbers","720p",720,1280).put("width",720.0d),"1004",false);
        check(numeric.width==720,"whole numeric JSON dimensions accepted without coercing strings");

        for(JSONObject invalid:new JSONObject[]{
            candidate("quality-unknown",FIRST_KEY).put("requestedQuality","720p").put("quality","1080p"),
            sized("quality-too-low","1080p",720,1920).put("quality","1080p"),
            sized("quality-short-edge","720p",719,4000),
            sized("quality-web","720p",1280,720).put("source","web").put("contentKey",JSONObject.NULL),
            sized("quality-missing-height","720p",1280,720).put("height",JSONObject.NULL),
            candidate("quality-missing-height-field",FIRST_KEY).put("requestedQuality","720p").put("width",1920),
            sized("quality-string-width","720p",1280,720).put("width","1280"),
            sized("quality-negative-width","720p",1280,720).put("width",-1280),
            sized("quality-zero-height","720p",1280,720).put("height",0),
            sized("quality-fractional","720p",1280,720).put("width",1280.5d),
            sized("quality-overflow","720p",1280,720).put("width",((long)Integer.MAX_VALUE)+1),
            sized("quality-boolean","720p",1280,720).put("height",true),
            candidate("quality-invalid-request",FIRST_KEY).put("requestedQuality","4k"),
            candidate("quality-null-request",FIRST_KEY).put("requestedQuality",JSONObject.NULL),
            candidate("quality-number-request",FIRST_KEY).put("requestedQuality",720),
            candidate("quality-auto-invalid-size",FIRST_KEY).put("width",0),
            candidate("quality-invalid-label",FIRST_KEY).put("quality",1080)
        }) fails(()->PlaybackSources.register(invalid,"1004",true),"invalid or unverifiable quality contract accepted");
        check(NetworkGuard.downloads==0&&NetworkGuard.heads==0,"invalid quality contracts fail before transport or web preflight");

        JSONObject owner=sized("quality-owner","1080p",1080,1920);
        JSONObject inherited=candidate("quality-inherited",SECOND_KEY).put("width",1920).put("height",1080);
        PlaybackSources.Plan inheritedPlan=PlaybackSources.register(variants(owner,inherited),"1004",false);
        check(inheritedPlan.variants.get(0).requestedQuality.equals("1080p")&&inheritedPlan.variants.get(0).width==1920,"backup inherits requested quality but keeps its own dimensions");
        for(JSONObject invalid:new JSONObject[]{
            sized("quality-variant-auto","auto",1080,1920),
            sized("quality-variant-other","720p",1080,1920),
            candidate("quality-variant-unknown",SECOND_KEY),
            candidate("quality-variant-low",SECOND_KEY).put("width",720).put("height",1280),
            new JSONObject(owner.toString()).put("height",720)
        }) fails(()->PlaybackSources.register(variants(owner,invalid),"1004",false),"variant lowered quality, inherited dimensions, or escaped duplicate validation");
        JSONObject[] backups=new JSONObject[8];for(int i=0;i<8;i++)backups[i]=sized("quality-cap-"+i,"1080p",1080,1920);
        backups[7].put("width",720);
        JSONArray rows=new JSONArray();for(JSONObject row:backups)rows.put(row);
        JSONObject capped=new JSONObject(owner.toString()).put("variants",rows);
        fails(()->PlaybackSources.register(capped,"1004",false),"over-cap discarded variant escaped dimensions check");

        NetworkGuard.reset();
        String shared="https://cdn.qznovelvod.com/shared-across-qualities.mp4";
        JSONObject highModel=sized("shared-high","1080p",1080,1920).put("url",shared).put("contentKey",SECOND_KEY);
        JSONObject mediumModel=sized("shared-medium","720p",720,1280).put("url",shared);
        JSONObject autoModel=candidate("shared-auto",FIRST_KEY).put("url",shared);
        JSONObject highBackup=sized("shared-high-backup","1080p",1080,1920).put("contentKey",SECOND_KEY);
        JSONObject mediumBackup=sized("shared-medium-backup","720p",720,1280);
        JSONObject autoBackup=candidate("shared-auto-low-backup",FIRST_KEY);
        PlaybackSources.Plan high=PlaybackSources.register(variants(highModel,highBackup),"1004",false);
        PlaybackSources.Plan medium=PlaybackSources.register(variants(mediumModel,mediumBackup),"1004",false);
        PlaybackSources.Plan low=PlaybackSources.register(variants(autoModel,autoBackup),"1004",false);
        check(PlaybackSources.find(shared,"1080p")==high&&PlaybackSources.find(shared,"720p")==medium&&PlaybackSources.find(shared)==low,"same media URL retains independently quality-bound plans; old find selects only auto");
        for(String requested:new String[]{"1080p","720p","auto"}) {
            NetworkGuard.reset();NetworkGuard.outcomes.put(shared,"http403");
            JSONObject expected=requested.equals("1080p")?highBackup:requested.equals("720p")?mediumBackup:autoBackup;
            CencDecryptor.expectedKeys.put(expected.getString("url"),keyBytes(requested.equals("1080p")?SECOND_KEY:FIRST_KEY));
            File target=new File(directory,"shared-quality-"+requested+".part");
            if(requested.equals("auto"))PlaybackSources.download(shared,target,64);else PlaybackSources.download(shared,target,64,requested);
            check(NetworkGuard.attempted.equals(Arrays.asList(shared,shared,expected.getString("url")))&&CencDecryptor.keyMatches.equals(Arrays.asList(true)),"same-URL job only transfers its own quality variants and key: "+requested);
            check(Arrays.equals(Files.readAllBytes(target.toPath()),new byte[]{7,8,9})&&target.delete(),"quality-bound downloader returns prepared bytes");
        }
        NetworkGuard.reset();File missing=new File(directory,"quality-missing-plan.part");
        fails(()->PlaybackSources.download("https://cdn.example.net/no-quality-plan.mp4",missing,64,"1080p"),"missing explicit plan fell through to unregistered web transfer");
        check(!missing.exists()&&NetworkGuard.downloads==0,"explicit missing plan fails before transfer regardless of media host");

        MainActivity host=new MainActivity();PlaybackSources.attach(host);
        try {
            NetworkGuard.reset();EpisodeResolver.result="https://cdn.example.net/quality-auto-web.mp4";int[] callbacks={0};
            qualityReply(host,candidate("unused-auto-app",FIRST_KEY),"auto",callbacks);
            PlaybackSources.Plan resolved=PlaybackSources.resolve(SID,"1004");
            check(resolved.source.equals("web")&&resolved.requestedQuality.equals("auto")&&EpisodeResolver.calls==1&&callbacks[0]==0&&NetworkGuard.heads==1,"old auto API preserves web-first resolution and preflight");
            NetworkGuard.reset();callbacks=new int[]{0};refreshReply(host,candidate("quality-auto-legacy-callback",FIRST_KEY).put("quality","720p"),callbacks);
            resolved=PlaybackSources.resolve(SID,"1004","auto");
            check(resolved.source.equals("app")&&resolved.requestedQuality.equals("auto")&&EpisodeResolver.calls==1&&callbacks[0]==1,"auto web miss accepts old callback without a requestedQuality field");
            for(String requested:new String[]{"720p","1080p"}) {
                NetworkGuard.reset();EpisodeResolver.result="https://cdn.example.net/must-skip-web.mp4";callbacks=new int[]{0};
                int edge=Integer.parseInt(requested.substring(0,requested.length()-1));
                qualityReply(host,candidate("quality-explicit-"+requested,FIRST_KEY).put("width",edge).put("height",edge*2).put("quality","different-server-label"),requested,callbacks);
                resolved=PlaybackSources.resolve(SID,"1004",requested);
                check(resolved.requestedQuality.equals(requested)&&resolved.width==edge&&EpisodeResolver.calls==0&&callbacks[0]==1&&NetworkGuard.heads==0,"explicit request skips all web resolution and binds verified App dimensions");
            }
            NetworkGuard.reset();int[] aliasCallbacks={0};
            host.callback=script->{try{aliasCallbacks[0]++;JSONArray values=callbackArguments(script);JSONObject reply=envelope(values);reply.remove("requestedQuality");reply.put("quality","720p");PlaybackSources.result(reply.put("playInfo",sized("quality-envelope-alias","720p",720,1280)));}catch(Exception error){throw new RuntimeException(error);}};
            check(PlaybackSources.resolve(SID,"1004","720p").requestedQuality.equals("720p")&&aliasCallbacks[0]==1,"quality envelope alias can bind a legacy explicit callback");

            for(String mismatch:new String[]{"missing-envelope","wrong-envelope","null-envelope","wrong-model","wrong-error-envelope"}) {
                NetworkGuard.reset();int[] rejected={0},seen={0};
                host.callback=script->{try{seen[0]++;JSONArray values=callbackArguments(script);JSONObject reply=envelope(values).put("playInfo",sized("quality-foreign-model-"+mismatch,"1080p",1080,1920));
                    if(mismatch.equals("missing-envelope"))reply.remove("requestedQuality");
                    else if(mismatch.equals("null-envelope"))reply.put("requestedQuality",JSONObject.NULL);
                    else if(mismatch.equals("wrong-model"))reply.getJSONObject("playInfo").put("requestedQuality","auto");
                    else reply.put("requestedQuality","720p");
                    if(mismatch.equals("wrong-error-envelope"))reply.put("error","synthetic error must still bind quality");
                    try{PlaybackSources.result(reply);}catch(IOException expected){rejected[0]++;}
                    PlaybackSources.result(envelope(values).put("error","synthetic rejected quality callback"));
                }catch(Exception error){throw new RuntimeException(error);}};
                fails(()->PlaybackSources.resolve(SID,"1004","1080p"),"mismatched quality callback accepted");
                check(rejected[0]==1&&seen[0]==1&&NetworkGuard.downloads==0&&EpisodeResolver.calls==0,"foreign or missing callback quality never completes a media plan: "+mismatch);
            }
            NetworkGuard.reset();int[] invalidCalls={0};qualityReply(host,candidate("unused-invalid-quality",FIRST_KEY),"auto",invalidCalls);
            fails(()->PlaybackSources.resolve(SID,"1004","4k"),"invalid resolve quality accepted");
            check(invalidCalls[0]==0&&EpisodeResolver.calls==0,"invalid resolve quality fails before any resolver");

            for(String requested:new String[]{"720p","1080p"}) {
                NetworkGuard.reset();int edge=Integer.parseInt(requested.substring(0,requested.length()-1));
                JSONObject primary=sized("quality-refresh-old-"+requested,requested,edge,edge*2).put("seriesId",SID);
                JSONObject fresh=candidate("quality-refresh-new-"+requested,SECOND_KEY).put("width",edge*2).put("height",edge);
                String url=primary.getString("url");PlaybackSources.Plan original=PlaybackSources.register(primary,"1004",false);NetworkGuard.outcomes.put(url,"http403");callbacks=new int[]{0};qualityReply(host,fresh,requested,callbacks);
                CencDecryptor.expectedKeys.put(fresh.getString("url"),keyBytes(SECOND_KEY));
                File ready=qualityCache(directory,"quality-refresh-"+requested,requested).fetch(SID,"1004",4,url);
                check(ready.isFile()&&callbacks[0]==1&&NetworkGuard.attempted.size()==3&&PlaybackSources.find(url,requested)==original,"finite refresh preserves original explicit quality and cache identity");
                noParts(directory,"quality-refresh-"+requested);
            }
            for(String downgrade:new String[]{"lower-primary","missing-dimensions","lower-variant","changed-request"}) {
                NetworkGuard.reset();JSONObject primary=sized("quality-refresh-owner-"+downgrade,"1080p",1080,1920).put("seriesId",SID);
                String url=primary.getString("url");PlaybackSources.register(primary,"1004",false);NetworkGuard.outcomes.put(url,"http403");
                JSONObject fresh=sized("quality-refresh-downgrade-"+downgrade,"1080p",1080,1920);
                if(downgrade.equals("lower-primary"))fresh.put("width",720);
                else if(downgrade.equals("missing-dimensions")){fresh.remove("width");fresh.remove("height");}
                else if(downgrade.equals("lower-variant"))fresh=variants(fresh,sized("quality-refreshed-low-backup","1080p",720,1280));
                else fresh.put("requestedQuality","auto");
                callbacks=new int[]{0};
                // A wrong model request value is rejected at callback ingress; finish the future
                // with a correctly bound error rather than waiting for the timeout in this test.
                final JSONObject replyModel=fresh;
                if(downgrade.equals("changed-request"))host.callback=script->{try{JSONArray values=callbackArguments(script);boolean rejected=false;try{PlaybackSources.result(envelope(values).put("playInfo",replyModel));}catch(IOException expected){rejected=true;}check(rejected,"refresh rejects changed model requestedQuality");PlaybackSources.result(envelope(values).put("error","synthetic downgrade rejected"));}catch(Exception error){throw new RuntimeException(error);}};
                else qualityReply(host,fresh,"1080p",callbacks);
                PlaybackCachePolicy failed=qualityCache(directory,"quality-refresh-reject-"+downgrade,"1080p");
                fails(()->failed.fetch(SID,"1004",4,url),"refreshed model silently downgraded explicit playback");
                check(NetworkGuard.attempted.size()==2&&failed.status().files==0,"invalid refreshed resolution stops before all second-round transfers: "+downgrade);
                noParts(directory,"quality-refresh-reject-"+downgrade);
            }
        } finally {PlaybackSources.detach(host);Thread.interrupted();}
    }
    public static void main(String[] args) throws Exception {
        String url="https://cdn.qznovelvod.com/offline-original.mp4";
        JSONObject input=plan(url,"app").put("contentKey","00112233445566778899aabbccddeeff"); // Synthetic only.
        PlaybackSources.Plan registered=PlaybackSources.register(input,"1004",false);
        check(registered.vid.equals("1004") && registered.contentKey!=null,"content preparation parameter preserved");
        check(registered.referer.isEmpty(),"App default omits Referer contract");
        check(PlaybackSources.find(url)==registered,"registered plan lookup");
        for(JSONObject invalid:new JSONObject[]{new JSONObject(input.toString()).put("vid","1005"),new JSONObject(input.toString()).put("source","invalid"),new JSONObject(input.toString()).put("url","https://qznovelvod.com.evil.net/x"),new JSONObject(input.toString()).put("contentKey","invalid"),new JSONObject(input.toString()).put("source","web"),new JSONObject(input.toString()).put("userAgent","agent\r\nInjected: true"),new JSONObject(input.toString()).put("referer","https://evil.net/")}) fails(()->PlaybackSources.register(invalid,"1004",false),"invalid media contract accepted");
        File directory=Files.createTempDirectory("hongguo-plan-contract-").toFile();
        try {
            PlaybackCachePolicy policy=new PlaybackCachePolicy(new File(directory,"cache"),128,64,PlaybackSources::download);
            policy.protect("series","1004",4);
            File ready=policy.fetch("series","1004",4,url);
            check(Arrays.equals(Files.readAllBytes(ready.toPath()),new byte[]{7,8,9}),"CENC hook completes before cache publication");
            check(CencDecryptor.calls==1 && NetworkGuard.downloads==1,"download and preparation called exactly once");
            check(NetworkGuard.lastReferer.isEmpty(),"App header omission reaches download transport");
            policy.release("series","1004",4);policy.clear();
            CencDecryptor.fail=true;
            fails(()->policy.fetch("series","1004",4,url),"failed preparation published");
            check(policy.status().files==0,"decryption failure has no published file");
            check(Arrays.stream(new File(directory,"cache").listFiles()).noneMatch(file->file.getName().endsWith(".part")),"decryption failure part removed");
            CencDecryptor.fail=false;
            for(int i=0;i<130;i++) PlaybackSources.register(plan("https://cdn.qznovelvod.com/evict-"+i+".mp4","app"),"1004",false);
            check(PlaybackSources.find(url)==null,"test really evicted old plan");
            int downloads=NetworkGuard.downloads; File missed=new File(directory,"expired.part");
            fails(()->PlaybackSources.download(url,missed,64),"expired App plan failed open");
            check(!missed.exists() && NetworkGuard.downloads==downloads,"expired App plan rejected before download/publication");
            File web=new File(directory,"web.part"); PlaybackSources.download("https://cdn.example.net/unregistered-web.mp4",web,64);
            check(web.isFile() && NetworkGuard.downloads==downloads+1,"legacy unregistered web transfer retained"); check(web.delete(),"web fixture removed");
            MainActivity host=new MainActivity();PlaybackSources.attach(host);
            host.callback=script->{ try {String argsText=script.substring(script.lastIndexOf('(')+1,script.length()-1);JSONArray values=new JSONArray("["+argsText+"]");PlaybackSources.result(new JSONObject().put("requestId",values.getString(0)).put("seriesId",values.getString(1)).put("vid",values.getString(2)).put("playInfo",input));}catch(Exception error){throw new RuntimeException(error);}};
            PlaybackSources.Plan resolved=PlaybackSources.resolve("123456","1004");
            check(resolved.source.equals("app") && resolved.vid.equals("1004"),"web failure completes native-to-JS callback without deadlock");
            host.callback=script->{PlaybackSources.detach(host);};
            fails(()->PlaybackSources.resolve("123456","1004"),"detached page did not fail pending resolver");
            PlaybackSources.result(new JSONObject().put("requestId","unknown-ignored"));checks++;
            variantContracts(directory);
            refreshContracts(directory);
            qualityContracts(directory);
            System.out.println("PASS PlaybackSourcesContractTest checks="+checks+" networkRequests=0 productionPlanAndCache=true transportAndUi=stubbed");
        } finally {File[] children=directory.listFiles();if(children!=null)for(File child:children){if(child.isDirectory())for(File file:child.listFiles())file.delete();child.delete();}directory.delete();}
    }
}
