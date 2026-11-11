package io.github.jackotom.hongguo.android;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
/** Uses actual production web parser, two-route resolver and native App fallback coordinator. */
public final class ProtectedPlaybackTest {
    private static final String SID="970001",VID="970006",ORIGIN="https://hongguoduanju.com";
    private static final String[] KEYS={"player_(series_id)/(vid)/page","player_(series_id)/page"};
    private static final List<JSONArray> appRequests=new ArrayList<>();
    private static MainActivity host;
    private static int passed,failed,checks;
    private interface Checked{void run()throws Exception;}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void group(String name,Checked task){try{reset();task.run();passed++;System.out.println("PASS "+name);}catch(Throwable ex){failed++;System.out.println("FAIL "+name+": "+ex.getClass().getSimpleName()+" "+ex.getMessage());}}
    private static Exception rejects(Checked task,String label)throws Exception{try{task.run();}catch(Exception ex){checks++;return ex;}throw new AssertionError(label);}
    private static JSONObject model(Object protection)throws Exception {
        JSONObject data=new JSONObject().put("isSuccess",true).put("series_id",SID).put("vid",VID)
            .put("seriesDetail",new JSONObject().put("series_id",SID).put("vid_list",new JSONArray().put("970005").put(VID).put("970007")))
            .put("publicVideoSeo",new JSONObject().put("seriesId",SID).put("itemId",VID).put("available",true).put("duration",120));
        if(protection!=null)data.put("protectedPlayback",protection);
        return data;
    }
    private static String page(JSONObject data,String key,String duration)throws Exception {
        JSONObject video=new JSONObject().put("@type","VideoObject").put("url",ORIGIN+"/player/"+SID+"/"+VID)
            .put("contentUrl","https://cdn.example.net/preview.mp4").put("duration",duration);
        return "<script>_ROUTER_DATA="+new JSONObject().put("loaderData",new JSONObject().put(key,data))+";</script>"+
            "<script type=\"application/ld+json\">"+video+"</script>";
    }
    private static String page(Object protection)throws Exception{return page(model(protection),KEYS[0],"PT120S");}
    private static void rejectsCode(JSONObject data,String key,String code)throws Exception{
        Exception ex=rejects(()->EpisodeResolver.parse(page(data,key,"PT120S"),SID,VID),"accepted "+code);
        check(ex instanceof EpisodeResolver.ResolverException && code.equals(((EpisodeResolver.ResolverException)ex).code),"exact error "+code);
    }
    @SuppressWarnings("unchecked") private static void reset()throws Exception {
        NetworkGuard.reset();appRequests.clear();
        for(String name:new String[]{"plans","failedWebUntil"}){Field field=PlaybackSources.class.getDeclaredField(name);field.setAccessible(true);Map<?,?> rows=(Map<?,?>)field.get(null);synchronized(rows){rows.clear();}}
        host.callback=script->{try{
            JSONArray args=new JSONArray("["+script.substring(script.lastIndexOf('(')+1,script.length()-1)+"]");appRequests.add(args);
            JSONObject info=new JSONObject().put("seriesId",SID).put("vid",VID).put("source","app")
                .put("url","https://cdn.qznovelvod.com/full-episode.mp4").put("contentKey","00112233445566778899aabbccddeeff")
                .put("width",1920).put("height",1080).put("requestedQuality",args.getString(3));
            PlaybackSources.result(new JSONObject().put("requestId",args.getString(0)).put("seriesId",args.getString(1)).put("vid",args.getString(2)).put("requestedQuality",args.getString(3)).put("playInfo",info));
        }catch(Exception ex){throw new RuntimeException(ex);}};
    }
    private static void exactApp(String quality)throws Exception {
        check(appRequests.size()==1,"one App lookup");JSONArray args=appRequests.get(0);
        check(SID.equals(args.getString(1))&&VID.equals(args.getString(2))&&quality.equals(args.getString(3)),"App retains same exact series, VID and quality");
    }
    public static void main(String[]args)throws Exception{
        host=new MainActivity();PlaybackSources.attach(host);
        try {
            group("protected true rejects both loader keys despite full-duration SEO",()->{
                for(String key:KEYS)rejectsCode(model(true),key,"PREVIEW_ONLY");
            });
            group("strict protected flag preserves missing false null string numeric and short media compatibility",()->{
                for(Object flag:new Object[]{null,false,JSONObject.NULL,"true",1,new JSONObject(),new JSONArray()})for(String key:KEYS)
                    check("https://cdn.example.net/preview.mp4".equals(EpisodeResolver.parse(page(model(flag),key,"PT10S"),SID,VID)),"compatible nontrue flag "+flag);
            });
            group("identity and availability remain higher priority than preview",()->{
                rejectsCode(model(true).put("vid","970007"),KEYS[0],"IDENTITY_MISMATCH");
                JSONObject unavailable=model(true);unavailable.getJSONObject("publicVideoSeo").put("available",false);
                rejectsCode(unavailable,KEYS[0],"EPISODE_UNAVAILABLE");
            });
            group("production resolver skips protected route and keeps valid same-episode backup",()->{
                NetworkGuard.reset(page(true),page(false));
                check("https://cdn.example.net/preview.mp4".equals(EpisodeResolver.resolve(SID,VID)),"unprotected backup accepted");
                check(NetworkGuard.requests.equals(Arrays.asList(ORIGIN+"/player/"+SID+"/"+VID,ORIGIN+"/player/_/"+VID)),"two routes retain exact requested VID");
            });
            group("both protected routes fail with PREVIEW_ONLY",()->{
                NetworkGuard.reset(page(true),page(true));
                Exception ex=rejects(()->EpisodeResolver.resolve(SID,VID),"protected preview was selected");
                check(ex instanceof EpisodeResolver.ResolverException&&"PREVIEW_ONLY".equals(((EpisodeResolver.ResolverException)ex).code),"both routes retain PREVIEW_ONLY");
                check(NetworkGuard.requests.size()==2,"only two candidate routes");
            });
            group("actual PlaybackSources resolves exact App after rejecting both preview pages",()->{
                NetworkGuard.reset(page(true),page(true));PlaybackSources.Plan result=PlaybackSources.resolve(SID,VID,"auto");
                check(result.source.equals("app")&&result.url.equals("https://cdn.qznovelvod.com/full-episode.mp4"),"full App not preview selected");
                check(SID.equals(result.seriesId)&&VID.equals(result.vid),"returned plan exact episode binding");
                exactApp("auto");check(NetworkGuard.requests.size()==2&&NetworkGuard.heads==0,"rejected preview never probed as media");
            });
            group("App failure after preview rejection is terminal and does not publish preview plan",()->{
                NetworkGuard.reset(page(true),page(true));host.callback=script->{try{
                    JSONArray a=new JSONArray("["+script.substring(script.lastIndexOf('(')+1,script.length()-1)+"]");appRequests.add(a);
                    PlaybackSources.result(new JSONObject().put("requestId",a.getString(0)).put("seriesId",a.getString(1)).put("vid",a.getString(2)).put("requestedQuality",a.getString(3)).put("error","fixture App unavailable").put("errorCode","BUSINESS_REJECTED"));
                }catch(Exception ex){throw new RuntimeException(ex);}};
                rejects(()->PlaybackSources.resolve(SID,VID,"auto"),"App failure returned preview");exactApp("auto");
                check(NetworkGuard.requests.size()==2&&NetworkGuard.heads==0,"no route loop or preview media probe");
                check(PlaybackSources.find("https://cdn.example.net/preview.mp4","auto")==null,"preview not registered");
            });
            group("explicit 1080p and 720p stay measured App-only without webpage requests",()->{
                for(String quality:new String[]{"1080p","720p"}){reset();PlaybackSources.Plan plan=PlaybackSources.resolve(SID,VID,quality);
                    check(plan.source.equals("app")&&quality.equals(plan.requestedQuality),"strict App quality retained");exactApp(quality);
                    check(NetworkGuard.requests.isEmpty()&&NetworkGuard.heads==0,"explicit quality does not read protected webpage");}
            });
            System.out.println("PROTECTED_PLAYBACK_"+(failed==0?"OK":"FAILED")+" groupsPassed="+passed+" groupsFailed="+failed+" checks="+checks+" networkRequests=0 actualProductionParser=true actualProductionFallback=true");
            if(failed!=0)throw new AssertionError("protected playback regression");
        }finally{PlaybackSources.detach(host);Thread.interrupted();}
    }
}
