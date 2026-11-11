package io.github.jackotom.hongguo.android;

import android.content.Context;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/**
 * Zero-network integration of the production source and private-cache classes.
 * Resolver, HTTP and MP4 inspection are boundary doubles, not video acceptance tests.
 */
public final class GuestPlaybackIntegrationTest {
    private static final String SID="123456789", VID="876543210";
    private static final String URL="https://fixture.invalid/official-public.mp4";
    private static int checks, cases;
    private static Path root;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value,String name) { checks++;if(!value)throw new AssertionError(name); }
    private static Exception fails(Checked task,String name) throws Exception {
        try { task.run(); } catch(Exception error) { return error; }
        throw new AssertionError("expected failure: "+name);
    }
    private static void terminal(Exception error,String name) {
        check(error instanceof EpisodeResolver.GuestFailure,name+": guest refusal remains terminal");
        check(!PlaybackSources.canRecoverPlayback(error),name+": no reconnect");
        check(!PlaybackSources.canLowerQuality(error),name+": no quality descent");
        check(!PlaybackSources.canRecoverPlayback(new IOException("wrapped",error)),name+": wrapped refusal no reconnect");
        check(!PlaybackSources.canLowerQuality(new IOException("wrapped",error)),name+": wrapped refusal no descent");
    }
    private static JSONObject basic(String source) throws Exception {
        return new JSONObject().put("url",source.equals("web")?URL:"https://fixture.qznovelvod.com/public.mp4")
            .put("source",source).put("seriesId",SID).put("vid",VID).put("requestedQuality","auto")
            .put("width",720).put("height",1280).put("userAgent","fixture-guest-agent")
            .put("referer",source.equals("web")?NetworkGuard.REFERER:"");
    }
    private static JSONObject guest(long expires) throws Exception {
        return basic("web").put("guestPlayback",true).put("expectedDurationSeconds",143.75).put("urlExpiresAt",expires);
    }
    private static PlaybackSources.Plan resolveGuest() throws Exception {
        EpisodeResolver.info=guest(System.currentTimeMillis()/1000L+3600);
        return PlaybackSources.resolve(SID,VID,"auto");
    }
    private static PlaybackCache cache(String suffix) throws Exception {
        Field field=PlaybackCache.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,null);
        return PlaybackCache.get(new Context(root.resolve(suffix).toFile()));
    }
    private static void noEscape(String name) {
        check(NetworkGuard.heads==0,name+": no HEAD/preflight");
        check(Fixture.appCalls==0,name+": no App fallback");
        check(CencDecryptor.calls==0,name+": no decrypt");
        for(NetworkGuard.Transfer request:NetworkGuard.transfers) {
            check(request.url.equals(URL),name+": signed URL unchanged");
            check(request.ua.equals("fixture-guest-agent"),name+": user agent unchanged");
            check(request.ref.equals(NetworkGuard.REFERER),name+": Referer unchanged");
        }
    }
    private static void noParts(Path directory,String name) throws Exception {
        if(!Files.exists(directory))return;
        try(java.util.stream.Stream<Path> paths=Files.walk(directory)) {
            check(paths.noneMatch(path->path.getFileName().toString().endsWith(".part")),name+": no partial files remain");
        }
    }
    private static void reset() throws Exception {
        Thread.interrupted();
        for(String name:new String[]{"plans","failedWebUntil","pending"}) {
            Field field=PlaybackSources.class.getDeclaredField(name);field.setAccessible(true);((Map<?,?>)field.get(null)).clear();
        }
        PlaybackSources.attach(null);
        PlaybackSources.attachNative((sid,vid,quality,ceiling,timeout)->{
            Fixture.appCalls++;throw new IOException("fixture: unexpected App lookup");
        });
        Fixture.appCalls=0;EpisodeResolver.calls=0;EpisodeResolver.info=null;EpisodeResolver.failure=null;
        NetworkGuard.heads=0;NetworkGuard.outcome="success";NetworkGuard.transfers.clear();
        PublicPlaybackVerifier.calls=0;PublicPlaybackVerifier.failure=null;PublicPlaybackVerifier.interrupt=false;
        PublicPlaybackVerifier.inspection=null;PublicPlaybackVerifier.file=null;PublicPlaybackVerifier.duration=0;
        PublicPlaybackVerifier.width=0;PublicPlaybackVerifier.height=0;CencDecryptor.calls=0;
    }
    private static void provenance() throws Exception {
        PlaybackSources.Plan ordinary=PlaybackSources.register(basic("web"),VID,false);
        check(!ordinary.guestPlayback,"ordinary bridge web plan has no guest provenance");
        check(ordinary.expectedDurationSeconds==0&&ordinary.urlExpiresAt==0,"ordinary plan has no guest timing");
        PlaybackSources.Plan plan=resolveGuest();
        check(EpisodeResolver.calls==1,"resolveInfo called once");
        check(plan.guestPlayback&&plan.source.equals("web"),"only resolver guest becomes guest plan");
        check(plan.seriesId.equals(SID)&&plan.vid.equals(VID)&&plan.requestedQuality.equals("auto"),"guest identity and auto quality preserved");
        check(plan.contentKey==null&&plan.variants.isEmpty()&&plan.playbackCeiling==0,"guest has no key, fallback candidates or ceiling");
        check(plan.expectedDurationSeconds==143.75&&plan.width==720&&plan.height==1280,"guest duration and dimensions preserved");
        check(plan.urlExpiresAt==EpisodeResolver.info.getLong("urlExpiresAt"),"guest expiry preserved");
        check(PlaybackSources.find(URL,"auto")==plan,"registered guest plan keeps object provenance");
        terminal(fails(()->PlaybackSources.register(plan.json(),VID,false),"serialized guest re-registration"),"serialized guest");
        noEscape("provenance");
    }
    private static void forgedMetadata() throws Exception {
        String[] names={"guestPlayback","expectedDurationSeconds","urlExpiresAt"};
        Object[] values={true,143.75,System.currentTimeMillis()/1000L+3600};
        for(int i=0;i<names.length;i++) {
            final String name=names[i];
            for(Object value:new Object[]{values[i],JSONObject.NULL,false}) {
                JSONObject forged=basic("web").put(name,value);
                terminal(fails(()->PlaybackSources.register(forged,VID,true),name),"bridge "+name);
                terminal(fails(()->PlaybackSources.registerInitial(forged,SID,VID,"auto"),name),"initial bridge "+name);
                JSONObject parent=basic("web").put("variants",new JSONArray().put(forged));
                terminal(fails(()->PlaybackSources.register(parent,VID,true),name),"variant bridge "+name);
            }
        }
        check(EpisodeResolver.calls==0&&NetworkGuard.transfers.isEmpty(),"forged bridge data never resolves or transfers");
        noEscape("forged bridge metadata");
    }
    private static void bridgeCallbackForgery() throws Exception {
        PlaybackSources.attachNative(null);
        EpisodeResolver.failure=new IOException("fixture: ordinary webpage not available");
        MainActivity activity=new MainActivity();
        PlaybackSources.attach(activity);
        try {
            for(String field:new String[]{"guestPlayback","expectedDurationSeconds","urlExpiresAt"}) {
                JSONObject forged=basic("app").put(field,field.equals("guestPlayback")?Boolean.TRUE:Long.valueOf(9999999999L));
                activity.callback=script->{
                    Fixture.appCalls++;
                    if(!script.startsWith("window.__androidAppResolve &&"))throw new AssertionError("unexpected bridge command");
                    JSONArray args=new JSONArray("["+script.substring(script.indexOf('(')+1,script.lastIndexOf(')'))+"]");
                    PlaybackSources.result(new JSONObject().put("requestId",args.getString(0)).put("seriesId",SID)
                        .put("vid",VID).put("requestedQuality","auto").put("playInfo",forged));
                };
                terminal(fails(()->PlaybackSources.resolve(SID,VID,"auto"),field),"actual bridge callback "+field);
            }
        } finally { PlaybackSources.detach(activity); }
        check(Fixture.appCalls==3&&EpisodeResolver.calls==3,"real bridge result callback exercised once per field");
        check(NetworkGuard.heads==0&&NetworkGuard.transfers.isEmpty()&&CencDecryptor.calls==0,"forged callback never reaches media");
    }
    private static void fullPrivateCache() throws Exception {
        PlaybackSources.Plan plan=resolveGuest();
        PlaybackCache cache=cache("success");
        Path directory=root.resolve("success/playback-v2");
        PublicPlaybackVerifier.inspection=()->{
            check(PublicPlaybackVerifier.file.getName().endsWith(".mp4.part"),"verifier receives unpublished part");
            check(PublicPlaybackVerifier.file.getCanonicalFile().getParentFile().equals(directory.toFile().getCanonicalFile()),"verifier receives private cache file");
            check(cache.find(SID,VID,1,"auto")==null,"cache not visible before verifier succeeds");
            check(Arrays.equals(Files.readAllBytes(PublicPlaybackVerifier.file.toPath()),Fixture.BYTES),"verifier receives downloaded bytes");
        };
        File published=cache.fetchForeground(SID,VID,1,plan,"auto");
        check(NetworkGuard.transfers.size()==1&&PublicPlaybackVerifier.calls==1,"one download and one validation");
        NetworkGuard.Transfer transfer=NetworkGuard.transfers.get(0);
        check(transfer.file.equals(PublicPlaybackVerifier.file),"download and verifier share same private part");
        check(transfer.max==Math.min(PlaybackCache.MAX_FILE_BYTES,PublicPlaybackVerifier.MAX_FILE_BYTES),"guest file limit applied");
        check(PublicPlaybackVerifier.duration==143.75&&PublicPlaybackVerifier.width==720&&PublicPlaybackVerifier.height==1280,"actual duration and dimensions forwarded to verifier");
        check(published.isFile()&&!published.getName().endsWith(".part"),"only verified MP4 is published");
        check(Arrays.equals(Files.readAllBytes(published.toPath()),Fixture.BYTES),"publication keeps verified bytes");
        check(cache.find(SID,VID,1,"auto").equals(published),"verified file can be found");
        check(cache.fetchForeground(SID,VID,1,plan,"auto").equals(published),"verified cache reused");
        check(NetworkGuard.transfers.size()==1&&PublicPlaybackVerifier.calls==1,"cache reuse never redownloads");
        noParts(directory,"successful publish");noEscape("successful publish");
    }
    private static void capturedPlanAndLimits() throws Exception {
        PlaybackSources.Plan guest=resolveGuest();
        PlaybackSources.register(basic("web"),VID,false);
        check(!PlaybackSources.find(URL,"auto").guestPlayback,"fixture replaces registry with non-guest plan");
        PlaybackCache cache=cache("captured");
        cache.fetchForeground(SID,VID,1,guest,"auto");
        check(PublicPlaybackVerifier.calls==1,"captured cache plan cannot lose guest verification after registry overwrite");
        File capped=root.resolve("limited.part").toFile();
        PlaybackSources.download(guest,capped,PublicPlaybackVerifier.MAX_FILE_BYTES+4096);
        check(NetworkGuard.transfers.get(1).max==PublicPlaybackVerifier.MAX_FILE_BYTES,"large caller budget clamped to guest cap");
        Files.delete(capped.toPath());
        PlaybackSources.download(guest,capped,4096);
        check(NetworkGuard.transfers.get(2).max==4096,"smaller caller budget preserved");
        Files.delete(capped.toPath());noEscape("captured plan");
    }
    private static void expired() throws Exception {
        EpisodeResolver.info=guest(System.currentTimeMillis()/1000L-1);
        PlaybackSources.Plan plan=PlaybackSources.resolve(SID,VID,"auto");
        File existing=root.resolve("expired.part").toFile();Files.write(existing.toPath(),Fixture.BYTES);
        terminal(fails(()->PlaybackSources.download(plan,existing,4096),"expired direct part"),"expired direct part");
        check(!existing.exists(),"expired direct part removed");
        PlaybackCache cache=cache("expired");
        terminal(fails(()->cache.fetchForeground(SID,VID,1,plan,"auto"),"expired cache"),"expired cache");
        check(NetworkGuard.transfers.isEmpty()&&PublicPlaybackVerifier.calls==0,"expired URL is never fetched or validated");
        check(cache.find(SID,VID,1,"auto")==null,"expired file not published");
        noParts(root.resolve("expired"),"expired");noEscape("expired");
    }
    private static void failure(String kind) throws Exception {
        PlaybackSources.Plan plan=resolveGuest();PlaybackCache cache=cache(kind);
        if(kind.equals("http403"))NetworkGuard.outcome="http403";
        else PublicPlaybackVerifier.failure=new IOException("fixture invalid full MP4 / duration / dimensions");
        Exception failure=fails(()->cache.fetchForeground(SID,VID,1,plan,"auto"),kind);
        terminal(failure,kind);
        check(NetworkGuard.transfers.size()==1,kind+": no transfer retry");
        check(PublicPlaybackVerifier.calls==(kind.equals("http403")?0:1),kind+": verifier invocation count");
        check(cache.find(SID,VID,1,"auto")==null,kind+": no completed cache");
        check(!NetworkGuard.transfers.get(0).file.exists(),kind+": actual downloaded part removed");
        check(EpisodeResolver.calls==1,kind+": no fresh resolver retry");
        noParts(root.resolve(kind),kind);noEscape(kind);
    }
    private static void cancelled(String stage) throws Exception {
        PlaybackSources.Plan plan=resolveGuest();PlaybackCache cache=cache("cancel-"+stage);
        if(stage.equals("download"))NetworkGuard.outcome="cancel";
        else if(stage.equals("verify"))PublicPlaybackVerifier.failure=new InterruptedIOException("fixture cancelled validator");
        else if(stage.equals("after-verify"))PublicPlaybackVerifier.interrupt=true;
        else Thread.currentThread().interrupt();
        Exception failure;
        try { failure=fails(()->cache.fetchForeground(SID,VID,1,plan,"auto"),"cancel "+stage); }
        finally { Thread.interrupted(); }
        check(failure instanceof InterruptedIOException,"cancel "+stage+": preserves cancellation");
        check(!(failure instanceof EpisodeResolver.GuestFailure),"cancel "+stage+": not converted into refusal");
        check(!PlaybackSources.canRecoverPlayback(failure)&&!PlaybackSources.canLowerQuality(failure),"cancel "+stage+": never retry or descend");
        int expected=stage.equals("before")?0:1;
        check(NetworkGuard.transfers.size()==expected,"cancel "+stage+": bounded transfer count");
        check(PublicPlaybackVerifier.calls==(stage.equals("verify")||stage.equals("after-verify")?1:0),"cancel "+stage+": verifier count");
        check(cache.find(SID,VID,1,"auto")==null,"cancel "+stage+": no completed cache");
        noParts(root.resolve("cancel-"+stage),"cancel "+stage);noEscape("cancel "+stage);
    }
    private static void directFailure(String kind) throws Exception {
        PlaybackSources.Plan plan=resolveGuest();File part=root.resolve("direct-"+kind+".part").toFile();
        if(kind.equals("invalid"))PublicPlaybackVerifier.failure=new IOException("fixture invalid MP4");
        else NetworkGuard.outcome=kind;
        Exception failure=fails(()->PlaybackSources.download(plan,part,4096),"direct "+kind);
        terminal(failure,"direct "+kind);
        check(NetworkGuard.transfers.size()==1,"direct "+kind+": exactly one transfer attempt");
        check(PublicPlaybackVerifier.calls==(kind.equals("invalid")?1:0),"direct "+kind+": verifier count");
        check(!part.exists(),"direct "+kind+": source cleans part without cache cleanup backstop");
        noEscape("direct "+kind);
    }
    private static void directVerifierCancellation() throws Exception {
        PlaybackSources.Plan plan=resolveGuest();File part=root.resolve("direct-cancel.part").toFile();
        InterruptedIOException expected=new InterruptedIOException("fixture cancelled validator");PublicPlaybackVerifier.failure=expected;
        Exception actual=fails(()->PlaybackSources.download(plan,part,4096),"direct verifier cancellation");
        check(actual==expected,"source rethrows exact validator cancellation");
        check(!part.exists(),"source alone removes cancelled validator part");
        check(NetworkGuard.transfers.size()==1&&PublicPlaybackVerifier.calls==1,"cancelled validation never retries");
        noEscape("direct validator cancellation");
    }
    private static void resolverRefusal() throws Exception {
        EpisodeResolver.failure=new EpisodeResolver.GuestFailure("fixture denied / malformed public response");
        Exception failure=fails(()->PlaybackSources.resolve(SID,VID,"auto"),"resolver denial");
        terminal(failure,"resolver denial");
        check(failure==EpisodeResolver.failure,"guest failure propagated unchanged");
        check(EpisodeResolver.calls==1&&NetworkGuard.transfers.isEmpty(),"resolver denial makes no media request");
        noEscape("resolver denial");
    }
    private static void fixedQuality() throws Exception {
        EpisodeResolver.info=guest(System.currentTimeMillis()/1000L+3600);
        List<String> requested=new ArrayList<>();
        PlaybackSources.attachNative((sid,vid,quality,ceiling,timeout)->{
            Fixture.appCalls++;requested.add(quality);
            return guest(System.currentTimeMillis()/1000L+3600).put("requestedQuality",quality);
        });
        for(String quality:new String[]{"1080p","720p"}) {
            terminal(fails(()->PlaybackSources.resolve(SID,VID,quality),"fixed "+quality),"fixed "+quality);
            terminal(fails(()->PlaybackSources.resolvePlayback(SID,VID,quality,0,false,false),"fixed playback "+quality),"fixed playback "+quality);
            check(PlaybackSources.find(URL,quality)==null,"fixed "+quality+": guest not registered");
        }
        check(requested.equals(Arrays.asList("1080p","1080p","720p","720p")),"fixed requests never become auto or lower quality");
        check(EpisodeResolver.calls==0&&NetworkGuard.heads==0&&NetworkGuard.transfers.isEmpty(),"fixed quality never enters guest resolver or media");
        check(CencDecryptor.calls==0,"fixed guest forgery never decrypts");
        PlaybackSources.attachNative((sid,vid,quality,ceiling,timeout)->basic("app").put("requestedQuality",quality).put("width",1920).put("height",1080));
        for(String quality:new String[]{"1080p","720p"}) {
            PlaybackSources.Plan app=PlaybackSources.resolve(SID,VID,quality);
            check(!app.guestPlayback&&app.source.equals("app")&&app.requestedQuality.equals(quality),"normal fixed App plan remains supported");
        }
    }
    private static void cacheRevision() throws Exception {
        File directory=root.resolve("revision/playback-v2").toFile();
        PlaybackCachePolicy old=new PlaybackCachePolicy(directory,PlaybackCache.BUDGET_BYTES,PlaybackCache.MAX_FILE_BYTES,
            (url,file,max)->Files.write(file.toPath(),new byte[]{9}));
        File unversioned=old.fetch(SID,VID,1,URL);
        File v1=old.fetch(SID,VID+"|quality=auto|complete=1",1,URL);
        File hd=old.fetch(SID,VID+"|quality=1080p",1,URL);
        File sd=old.fetch(SID,VID+"|quality=720p",1,URL);
        File manual=root.resolve("manual-fixture.mp4").toFile();Files.write(manual.toPath(),new byte[]{8});
        PlaybackCache cache=cache("revision");
        check(MediaQuality.cacheVid(VID,"auto").equals(VID+"|quality=auto|complete=2"),"guest complete source cache revision is 2");
        check(cache.find(SID,VID,1,"auto")==null,"neither old auto namespace is reused");
        check(!unversioned.exists(),"owned unversioned legacy auto removed");
        check(cache.find(SID,VID,1,"1080p").equals(hd)&&cache.find(SID,VID,1,"720p").equals(sd),"fixed quality cache identities preserved");
        PlaybackSources.Plan plan=resolveGuest();File current=cache.fetchForeground(SID,VID,1,plan,"auto");
        check(!current.equals(v1)&&!current.equals(unversioned),"verified guest publishes to new identity");
        check(Arrays.equals(Files.readAllBytes(current.toPath()),Fixture.BYTES),"new cache cannot serve old auto bytes");
        check(Arrays.equals(Files.readAllBytes(manual.toPath()),new byte[]{8}),"manual file untouched");
        check(cache("revision").find(SID,VID,1,"auto").equals(current),"revision 2 cache survives facade restart");
        check(NetworkGuard.transfers.size()==1&&PublicPlaybackVerifier.calls==1,"revision upgrade validates guest once");
        noParts(directory.toPath(),"revision upgrade");noEscape("revision upgrade");
    }
    private static void activityInvariant(Path source) throws Exception {
        String text=Files.readString(source,StandardCharsets.UTF_8);
        String compact=text.replaceAll("\\s+","");
        String condition="if(!huangguoStream&&(media.plan.guestPlayback||!media.plan.source.equals(\"web\")||!\"auto\".equals(stageQuality))){";
        int start=compact.indexOf(condition);
        check(start>=0,"PlayerActivity includes guest in mandatory cache branch");
        int end=compact.indexOf("}else{media.remote=media.plan.url",start);
        check(end>start,"guest cache branch ends before direct URL playback");
        String branch=compact.substring(start,end);
        check(branch.contains("cache.fetchForeground(media.seriesId,target.optString(\"vid\"),target.optInt(\"index\"),media.plan,stageQuality)"),"PlayerActivity uses captured plan foreground cache");
        check(branch.contains("media.uri=Uri.fromFile(ready)")&&branch.contains("media.cached=true"),"PlayerActivity plays only resulting local file");
    }
    private static String compact(Path source) throws Exception {
        return Files.readString(source,StandardCharsets.UTF_8).replaceAll("\\s+","");
    }
    private static void downloadEntryInvariants(Path sourceDirectory) throws Exception {
        String bridge=compact(sourceDirectory.resolve("NativeBridge.java"));
        int download=bridge.indexOf("case\"download\":{");
        int manager=bridge.indexOf("newDownloadManager.Request",download);
        int guest=bridge.indexOf("if(plan.guestPlayback){downloadPrepared(id,series,episode,plan);break;}",download);
        check(download>=0&&guest>download&&manager>guest,"NativeBridge guest enters prepared path before DownloadManager URL request");
        int prepared=bridge.indexOf("privatevoiddownloadPrepared(");
        int copied=bridge.indexOf("java.nio.file.Files.copy(prepared.toPath(),target.toPath())",prepared);
        int fetched=bridge.indexOf("cache.fetch(",prepared);
        check(prepared>=0&&fetched>prepared&&copied>fetched,"NativeBridge copies only after private cache preparation");
        check(bridge.substring(prepared,copied).contains("cache.fetch(sid,vid,index,plan,plan.requestedQuality)"),"NativeBridge retains captured guest provenance during preparation");
        String tv=compact(sourceDirectory.resolve("TvDownloads.java"));
        guest=tv.indexOf("if(plan.guestPlayback||!plan.source.equals(\"web\"))returnprepared(context,service,store,series,episode,plan);");
        manager=tv.indexOf("newDownloadManager.Request");
        check(guest>=0&&manager>guest,"TvDownloads guest returns prepared path before DownloadManager URL request");
        check(tv.contains("cache.fetch(sid,vid,index,plan,plan.requestedQuality)"),"TvDownloads uses captured plan for private preparation");
        for(String name:new String[]{"BrowserCastService.java","DlnaCastService.java"}) {
            String cast=compact(sourceDirectory.resolve(name));
            int session=cast.indexOf("Filefile=DlnaSessionFile.prepare(");
            int recover=cast.indexOf("PlaybackSources.recover(sid,vid,quality,true)",session);
            check(session>=0&&recover>session,name+": prepare and fallback contract is present");
            String arguments=cast.substring(session,recover);
            check(arguments.contains("cached==null&&plan.source.equals(\"web\")&&!plan.guestPlayback"),name+": neither fresh guest nor cached auto enables format-failure App fallback");
            check(arguments.contains("cached!=null?cached:fetch(sid,vid,index,plan,quality,foreground)"),name+": preparation captures resolved Plan");
            check(cast.contains("returnfetch(sid,vid,index,app,quality,foreground)"),name+": recovery also captures resolved Plan");
            check(cast.contains("returnforeground?cache.fetchForeground(sid,vid,index,plan,quality):cache.fetch(sid,vid,index,plan,quality)"),name+": both foreground and prefetch preserve provenance");
        }
    }
    private static void run(String name,Checked task,List<String> failures) throws Exception {
        reset();cases++;
        try { task.run();System.out.println("PASS "+name); }
        catch(Throwable failure) { failures.add(name+": "+failure);failure.printStackTrace(System.err); }
        finally { Thread.interrupted(); }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("PlayerActivity.java path required");
        java.net.URL.setURLStreamHandlerFactory(protocol->{
            if(!protocol.equals("https")&&!protocol.equals("http"))return null;
            return new URLStreamHandler(){protected URLConnection openConnection(URL url){throw new AssertionError("live network forbidden: "+url.getProtocol());}};
        });
        root=Files.createTempDirectory("hongguo-guest-integration-");
        List<String> failures=new ArrayList<>();
        try {
            run("resolver-only-provenance",GuestPlaybackIntegrationTest::provenance,failures);
            run("untrusted-reserved-metadata",GuestPlaybackIntegrationTest::forgedMetadata,failures);
            run("actual-bridge-callback-forgery",GuestPlaybackIntegrationTest::bridgeCallbackForgery,failures);
            run("private-cache-atomic-verification",GuestPlaybackIntegrationTest::fullPrivateCache,failures);
            run("captured-plan-and-byte-limits",GuestPlaybackIntegrationTest::capturedPlanAndLimits,failures);
            run("expired-url-cleans-part",GuestPlaybackIntegrationTest::expired,failures);
            run("http403-terminal",()->failure("http403"),failures);
            run("bad-verification-terminal",()->failure("invalid-mp4"),failures);
            for(String stage:new String[]{"before","download","verify","after-verify"})run("cancel-"+stage,()->cancelled(stage),failures);
            for(String kind:new String[]{"http403","http404","http500","dns","timeout","tls","generic","invalid"})run("direct-terminal-"+kind,()->directFailure(kind),failures);
            run("direct-validator-cancellation",GuestPlaybackIntegrationTest::directVerifierCancellation,failures);
            run("resolver-denial-no-fallback",GuestPlaybackIntegrationTest::resolverRefusal,failures);
            run("fixed-quality-no-guest",GuestPlaybackIntegrationTest::fixedQuality,failures);
            run("complete2-cache-isolation",GuestPlaybackIntegrationTest::cacheRevision,failures);
            run("activity-foreground-source-invariant",()->activityInvariant(Paths.get(args[0])),failures);
            run("download-and-cast-source-invariants",()->downloadEntryInvariants(Paths.get(args[0]).getParent()),failures);
            if(!failures.isEmpty())throw new AssertionError("GUEST_PLAYBACK_INTEGRATION_FAILED "+failures);
            System.out.println("GUEST_PLAYBACK_INTEGRATION_OK cases="+cases+" checks="+checks+" networkRequests=0 productionClasses=4 resolverHttpVerifier=boundaryDoubles activityCheck=sourceInvariant");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);
            }
        }
    }
}
