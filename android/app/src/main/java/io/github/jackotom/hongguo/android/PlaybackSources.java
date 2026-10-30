package io.github.jackotom.hongguo.android;

import java.io.*;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** One media contract shared by initial playback, native next-episode and cache downloads. */
final class PlaybackSources {
    static final class Plan {
        final String url, contentKey, userAgent, referer, source, vid, seriesId, requestedQuality, quality;
        final int width, height, playbackCeiling;
        final List<Plan> variants;
        Plan(String url, String key, String ua, String ref, String source, String vid, String seriesId, String requestedQuality, int width, int height, String quality, List<Plan> variants) {
            this(url,key,ua,ref,source,vid,seriesId,requestedQuality,width,height,quality,variants,0);
        }
        Plan(String url,String key,String ua,String ref,String source,String vid,String seriesId,String requestedQuality,int width,int height,String quality,List<Plan> variants,int playbackCeiling) {
            this.playbackCeiling=playbackCeiling;
            this.url=url; contentKey=key; userAgent=ua; referer=ref; this.source=source; this.vid=vid; this.seriesId=seriesId;
            this.requestedQuality=requestedQuality;this.width=width;this.height=height;this.quality=quality;
            this.variants=Collections.unmodifiableList(new ArrayList<>(variants));
        }
        JSONObject json() throws JSONException {
            JSONObject result=new JSONObject().put("url",url).put("contentKey",contentKey==null?JSONObject.NULL:contentKey).put("userAgent",userAgent).put("referer",referer).put("source",source).put("vid",vid).put("requestedQuality",requestedQuality);
            if (seriesId!=null) result.put("seriesId",seriesId);
            if (width>0) result.put("width",width);
            if (height>0) result.put("height",height);
            if (quality!=null) result.put("quality",quality);
            if (playbackCeiling>0) result.put("playbackCeiling",playbackCeiling);
            if (!variants.isEmpty()) { JSONArray rows=new JSONArray();for(Plan variant:variants)rows.put(variant.json());result.put("variants",rows); }
            return result;
        }
    }
    private static final class Pending {
        final String sid, vid, requestedQuality; final int playbackCeiling; final CompletableFuture<JSONObject> future = new CompletableFuture<>();
        Pending(String sid, String vid, String requestedQuality,int playbackCeiling) { this.sid=sid;this.vid=vid;this.requestedQuality=requestedQuality;this.playbackCeiling=playbackCeiling; }
    }
    private static WeakReference<MainActivity> activity = new WeakReference<>(null);
    private static final Map<String,Pending> pending = new ConcurrentHashMap<>();
    private static final LinkedHashMap<String,Plan> plans = new LinkedHashMap<>(16,.75f,true);
    private static final LinkedHashMap<String,Long> failedWebUntil = new LinkedHashMap<>();
    private static final long WEB_FAILURE_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final int MAX_MEDIA_TRANSFERS = 16;
    private static final Set<String> LOOKUP_TRANSIENT_CODES = new HashSet<>(Arrays.asList("ENOTFOUND","EAI_AGAIN","ETIMEDOUT","ECONNABORTED","ECONNRESET","ECONNREFUSED","EHOSTUNREACH","ENETUNREACH","EPIPE"));
    // A valid HTTP response without data may be retried, but does not prove a lower quality exists.
    private static final String RESPONSE_RETRY_CODE = "APP_EMPTY_RESPONSE";
    private static final class AppLookupException extends IOException {
        final String code;
        AppLookupException(String message,String code){super(message);this.code=code;}
    }
    static final class QualityUnavailableException extends IOException {
        QualityUnavailableException(String message) { super(message); }
    }
    static synchronized void attach(MainActivity value) { activity = new WeakReference<>(value); }
    /** Native-UI lookup used when no WebView page owns resolution; phone/tv keep the page path. */
    interface NativeLookup { JSONObject lookup(String sid, String vid, String requestedQuality, int ceiling, long timeoutMs) throws Exception; }
    private static volatile NativeLookup nativeLookup;
    static void attachNative(NativeLookup lookup) { nativeLookup = lookup; }
    static void detachNative(NativeLookup lookup) { if (nativeLookup == lookup) nativeLookup = null; }
    static synchronized void detach(MainActivity value) {
        if (activity.get() != value) return;
        activity.clear();
        for (Pending request : pending.values()) request.future.completeExceptionally(new IOException("片源页面已关闭"));
        pending.clear();
    }
    static Plan register(JSONObject input, String vid, boolean preflight) throws Exception {
        Plan plan=preparePlan(input,vid,preflight);
        synchronized(plans) { plans.put(planKey(plan.url,plan.requestedQuality),plan); while(plans.size()>128) plans.remove(plans.keySet().iterator().next()); }
        return plan;
    }
    /** Initial play must have the same web-preflight fallback as native next-episode. */
    static Plan registerInitial(JSONObject input,String sid,String vid,String quality) throws Exception {
        requireIdentity(sid,vid);
        String requestedQuality=MediaQuality.normalize(quality);
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("播放准备已取消");
        JSONObject bound=bindSeries(input,sid);
        if(bound.has("requestedQuality")&&!requestedQuality.equals(bound.opt("requestedQuality")))throw new IOException("清晰度结果不匹配");
        bound.put("requestedQuality",requestedQuality);
        // Reject every primary/variant metadata error before preflight or fallback.
        Plan checked=preparePlan(bound,vid,false);
        if(!checked.source.equals("web"))return register(bound,vid,false);
        try { return register(bound,vid,true); }
        catch(Exception webError) {
            if(cancelled(webError))throw webError;
            markWebFailure(sid,vid);
            try { return register(bindSeries(resolveApp(sid,vid,requestedQuality),sid),vid,true); }
            catch(Exception appError) {
                if(cancelled(appError))throw appError;
                appError.addSuppressed(webError);throw appError;
            }
        }
    }
    private static Plan preparePlan(JSONObject input,String vid,boolean preflight) throws Exception {
        Plan primary=parse(input,vid,null);
        List<Plan> variants=new ArrayList<>(); Set<String> urls=new HashSet<>(); urls.add(primary.url);
        List<Plan> checked=new ArrayList<>();checked.add(primary);
        if (input.has("variants") && !input.isNull("variants") && !(input.opt("variants") instanceof JSONArray)) throw new IOException("备用片源列表无效");
        JSONArray rows=input.optJSONArray("variants");
        if (rows!=null) {
            if (rows.length()>8) throw new IOException("备用片源数量无效");
            for(int i=0;i<rows.length();i++) {
                Plan variant=parse(rows.getJSONObject(i),vid,primary);
                if (!variant.source.equals(primary.source)) throw new IOException("备用片源类型不一致");
                checked.add(variant);
                if(urls.add(variant.url) && variants.size()<7) variants.add(variant);
            }
        }
        // Validate identities, URLs, headers and keys for every candidate before
        // a missing resolution can qualify for playback-only quality fallback.
        for (Plan candidate:checked) {
            if (!candidate.requestedQuality.equals("auto") && (candidate.source.equals("web") || !MediaQuality.meets(candidate.width,candidate.height,candidate.requestedQuality)))
                throw new QualityUnavailableException("本集暂无尺寸可确认且满足 "+candidate.requestedQuality+" 的 App 片源，未降级播放");
            if (candidate.playbackCeiling>0 && (candidate.source.equals("web") || candidate.width<=0 || candidate.height<=0 || Math.min(candidate.width,candidate.height)>candidate.playbackCeiling))
                throw new QualityUnavailableException("本集暂无真实尺寸符合播放降档边界的 App 片源");
        }
        // App CDNs do not consistently support HEAD/Range probes. Prepare the actual full
        // GET below, like the desktop media path; never publish ciphertext as playable cache.
        String url=preflight && primary.source.equals("web")?NetworkGuard.media(primary.url):primary.url;
        return new Plan(url,primary.contentKey,primary.userAgent,primary.referer,primary.source,vid,primary.seriesId,primary.requestedQuality,primary.width,primary.height,primary.quality,variants,primary.playbackCeiling);
    }
    private static Plan parse(JSONObject input,String vid,Plan parent) throws Exception {
        if (input == null || !vid.equals(input.optString("vid",vid))) throw new IOException("片源与分集不一致");
        String sid=parent==null?null:parent.seriesId;
        if (input.has("seriesId") && !input.isNull("seriesId")) {
            Object value=input.get("seriesId");
            if (!(value instanceof String) || !((String)value).matches("[0-9]{1,30}") || (sid!=null&&!sid.equals(value))) throw new IOException("片源与剧集不一致");
            sid=(String)value;
        }
        String url=input.getString("url"), source=input.optString("source",parent==null?"web":parent.source);
        if (!Arrays.asList("web","app","api").contains(source)) throw new IOException("片源类型无效");
        String requestedQuality=parent==null?"auto":parent.requestedQuality;
        if (input.has("requestedQuality")) {
            Object value=input.get("requestedQuality");
            if (!(value instanceof String)) throw new IOException("片源清晰度无效");
            requestedQuality=MediaQuality.normalize((String)value);
            if (parent!=null&&!parent.requestedQuality.equals(requestedQuality)) throw new IOException("备用片源清晰度不一致");
        }
        int playbackCeiling=parent==null?0:parent.playbackCeiling;
        if(input.has("playbackCeiling")) {
            Object raw=input.opt("playbackCeiling");
            if(!(raw instanceof Number))throw new IOException("播放清晰度边界无效");
            double value=((Number)raw).doubleValue();
            if(!Double.isFinite(value)||value!=Math.rint(value)||value<0||value>16384||parent!=null&&value!=parent.playbackCeiling)throw new IOException("播放清晰度边界不匹配");
            playbackCeiling=(int)value;
        }
        int width=dimension(input,"width"),height=dimension(input,"height");
        String quality=null;
        if (input.has("quality")&&!input.isNull("quality")) {
            Object value=input.get("quality");
            if (!(value instanceof String)||((String)value).length()>128) throw new IOException("片源清晰度标签无效");
            quality=(String)value;
        }
        URI uri=NetworkGuard.validate(url,false);
        String key=input.has("contentKey")?(input.isNull("contentKey")?null:input.optString("contentKey",null)):(parent==null?null:parent.contentKey);
        if (key!=null && (key.isEmpty() || !key.matches("[a-fA-F0-9]{32}"))) throw new IOException("媒体准备参数无效");
        boolean app=!source.equals("web");
        if (app && !AppTransport.domain(uri.getHost(),"qznovelvod.com","fqnovelvod.com","douyinvod.com")) throw new IOException("App媒体服务地址无效");
        if (!app && key!=null) throw new IOException("网页片源准备参数无效");
        String ua=input.optString("userAgent",parent==null?NetworkGuard.UA:parent.userAgent);
        // Empty is an explicit no-Referer contract for App/API media, including variants.
        // Keep the old permitted value for models that still need the compatibility header.
        String ref=parent==null?(app?"":NetworkGuard.REFERER):parent.referer;
        if (input.has("referer")) {
            if (!(input.opt("referer") instanceof String)) throw new IOException("片源请求头无效");
            ref=input.getString("referer");
        }
        boolean validReferer=app?(ref.isEmpty()||ref.equals("https://novelquickapp.com/")):ref.equals(NetworkGuard.REFERER);
        if (ua.isEmpty() || ua.length()>2048 || ua.indexOf('\r')>=0 || ua.indexOf('\n')>=0 || !validReferer) throw new IOException("片源请求头无效");
        return new Plan(url,key,ua,ref,source,vid,sid,requestedQuality,width,height,quality,Collections.emptyList(),playbackCeiling);
    }
    private static int dimension(JSONObject input,String name) throws Exception {
        if (!input.has(name)) return 0;
        Object value=input.get(name);
        if (!(value instanceof Number)) throw new IOException("片源尺寸无效");
        double number=((Number)value).doubleValue();
        if (!Double.isFinite(number)||number<=0||number>Integer.MAX_VALUE||number!=Math.rint(number)) throw new IOException("片源尺寸无效");
        return (int)number;
    }
    private static String planKey(String url,String quality) { return url+"\n"+MediaQuality.normalize(quality); }
    static Plan find(String url) { return find(url,"auto"); }
    static Plan find(String url,String quality) { synchronized(plans) { return plans.get(planKey(url,quality)); } }
    static Plan resolve(String sid,String vid) throws Exception {
        return resolve(sid,vid,"auto");
    }
    static Plan resolve(String sid,String vid,String quality) throws Exception {
        String requestedQuality=MediaQuality.normalize(quality);
        requireIdentity(sid,vid);
        if (requestedQuality.equals("480p")||requestedQuality.equals("360p"))return resolvePlayback(sid,vid,requestedQuality,defaultPlaybackCeiling(requestedQuality),false,false);
        if (!requestedQuality.equals("auto")) return register(bindSeries(resolveApp(sid,vid,requestedQuality),sid),vid,true);
        if (webFailureActive(sid,vid)) {
            try { return register(bindSeries(resolveApp(sid,vid,requestedQuality),sid),vid,true); }
            catch (Exception appError) {
                if (cancelled(appError)) throw appError;
                // Like the desktop, the penalty changes priority, not permanent availability.
                try { return resolveWeb(sid,vid); }
                catch (Exception webError) { if(cancelled(webError))throw webError;appError.addSuppressed(webError);throw appError; }
            }
        }
        try {
            return resolveWeb(sid,vid);
        } catch (Exception webError) {
            if (cancelled(webError)) throw webError;
            return register(bindSeries(resolveApp(sid,vid,requestedQuality),sid),vid,true);
        }
    }
    static int defaultPlaybackCeiling(String quality) {
        switch(MediaQuality.normalize(quality)) {case "720p":return 1079;case "480p":return 719;case "360p":return 479;default:return 0;}
    }
    /** Fresh or bounded playback lookup; this opt-in path never changes manual download policy. */
    static Plan resolvePlayback(String sid,String vid,String quality,int ceiling,boolean fresh,boolean failedWeb) throws Exception {
        String requested=MediaQuality.normalize(quality);requireIdentity(sid,vid);
        if(ceiling<0||ceiling>16384)throw new IllegalArgumentException("播放清晰度边界无效");
        if(ceiling==0&&(requested.equals("480p")||requested.equals("360p")))ceiling=defaultPlaybackCeiling(requested);
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("播放恢复已取消");
        if(failedWeb)markWebFailure(sid,vid);
        if(ceiling==0&&!fresh)return resolve(sid,vid,requested);
        if(fresh)recoveryPause();
        return register(bindSeries(resolveApp(sid,vid,requested,!fresh,ceiling),sid),vid,true);
    }
    private static Plan resolveWeb(String sid,String vid) throws Exception {
        String url=EpisodeResolver.resolve(sid,vid);
        return register(new JSONObject().put("url",url).put("vid",vid).put("seriesId",sid).put("source","web"),vid,true);
    }
    /** One caller-owned reconnect; always obtains a fresh App model at the same quality. */
    static Plan recover(String sid,String vid,String quality,boolean failedWeb) throws Exception {
        String requestedQuality=MediaQuality.normalize(quality);requireIdentity(sid,vid);
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("播放恢复已取消");
        if(failedWeb)markWebFailure(sid,vid);
        recoveryPause();
        int ceiling=requestedQuality.equals("480p")||requestedQuality.equals("360p")?defaultPlaybackCeiling(requestedQuality):0;
        return register(bindSeries(resolveApp(sid,vid,requestedQuality,false,ceiling),sid),vid,true);
    }
    private static void requireIdentity(String sid,String vid) throws IOException {
        if(sid==null||vid==null||!sid.matches("[0-9]{1,30}")||!vid.matches("[0-9]{1,30}"))throw new IOException("分集请求信息无效");
    }
    private static void markWebFailure(String sid,String vid) {
        synchronized(failedWebUntil) {
            String key=sid+"\n"+vid;failedWebUntil.remove(key);failedWebUntil.put(key,System.nanoTime()+WEB_FAILURE_NANOS);
            while(failedWebUntil.size()>128)failedWebUntil.remove(failedWebUntil.keySet().iterator().next());
        }
    }
    private static boolean webFailureActive(String sid,String vid) {
        synchronized(failedWebUntil) {
            String key=sid+"\n"+vid;Long until=failedWebUntil.get(key);
            if(until==null)return false;
            if(System.nanoTime()-until>=0){failedWebUntil.remove(key);return false;}
            return true;
        }
    }
    private static boolean cancelled(Exception error) {
        if(Thread.currentThread().isInterrupted())return true;
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=error;cause!=null&&seen.add(cause);cause=cause.getCause())
            if(cause instanceof InterruptedException || cause instanceof InterruptedIOException && !(cause instanceof java.net.SocketTimeoutException))return true;
        return false;
    }
    private static void recoveryPause() throws InterruptedException,InterruptedIOException {
        recoveryPause(500);
    }
    private static void recoveryPause(long delayMs) throws InterruptedException,InterruptedIOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("播放恢复已取消");
        try { Thread.sleep(delayMs); } catch(InterruptedException error) { Thread.currentThread().interrupt();throw error; }
    }
    private static JSONObject bindSeries(JSONObject input,String sid) throws Exception {
        if (input.has("seriesId") && !input.isNull("seriesId") && !sid.equals(input.get("seriesId"))) throw new IOException("片源与剧集不一致");
        return new JSONObject(input.toString()).put("seriesId",sid);
    }
    private static JSONObject resolveApp(String sid,String vid,String requestedQuality) throws Exception {
        return resolveApp(sid,vid,requestedQuality,true);
    }
    private static JSONObject resolveApp(String sid,String vid,String requestedQuality,boolean retryInitialLookup) throws Exception {
        return resolveApp(sid,vid,requestedQuality,retryInitialLookup,0);
    }
    private static JSONObject resolveApp(String sid,String vid,String requestedQuality,boolean retryInitialLookup,int ceiling) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        for(int attempt=0;;attempt++) {
            long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
            if(remaining<=0)throw new AppLookupException("App片源准备超时，请重试","ETIMEDOUT");
            try {return resolveAppAttempt(sid,vid,requestedQuality,Math.min(28000,remaining),ceiling);}
            catch(AppLookupException error) {
                // Android 12 AddressCache retains negative DNS results for two seconds.
                long delayMs=RESPONSE_RETRY_CODE.equals(error.code)?700:error.code.equals("ENOTFOUND")||error.code.equals("EAI_AGAIN")?2100:500;
                boolean retryable=LOOKUP_TRANSIENT_CODES.contains(error.code)||RESPONSE_RETRY_CODE.equals(error.code);
                if(!retryInitialLookup||attempt!=0||!retryable||cancelled(error)||deadline-System.nanoTime()<=TimeUnit.MILLISECONDS.toNanos(delayMs)) {
                    if(retryInitialLookup&&attempt>0&&RESPONSE_RETRY_CODE.equals(error.code))
                        throw new AppLookupException(error.getMessage()+"（已重取1次仍未返回有效数据，请稍后重试）",error.code);
                    throw error;
                }
                recoveryPause(delayMs);
            }
        }
    }
    private static JSONObject resolveAppAttempt(String sid,String vid,String requestedQuality,long timeoutMs,int ceiling) throws Exception {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("播放恢复已取消");
        NativeLookup lookup = nativeLookup;
        if (lookup != null) {
            if (pending.size()>=8) throw new IOException("分集正在准备，请稍后重试");
            JSONObject info;
            try { info = lookup.lookup(sid, vid, requestedQuality, ceiling, timeoutMs); }
            catch (AppMediaSource.AppSourceException error) { throw new AppLookupException(error.getMessage()==null?"App片源暂未取得":error.getMessage(), error.code); }
            catch (java.net.SocketTimeoutException error) { throw new AppLookupException("App片源准备超时，请重试","ETIMEDOUT"); }
            catch (InterruptedIOException error) { throw error; }
            catch (Exception error) { throw new AppLookupException(error.getMessage()==null?"App片源暂未取得":error.getMessage(), ""); }
            if (!requestedQuality.equals(info.optString("requestedQuality", requestedQuality))) throw new IOException("片源清晰度结果不匹配");
            if (ceiling>0 && info.optInt("playbackCeiling",-1)!=ceiling) throw new IOException("播放清晰度边界结果不匹配");
            return info;
        }
        MainActivity host;
        synchronized(PlaybackSources.class) { host=activity.get(); }
        if (host==null || host.isFinishing() || host.isDestroyed()) throw new IOException("App片源服务尚未准备");
        if (pending.size()>=8) throw new IOException("分集正在准备，请稍后重试");
        String id=UUID.randomUUID().toString(); Pending request=new Pending(sid,vid,requestedQuality,ceiling);pending.put(id,request);
        try {
            host.script("window.__androidAppResolve && window.__androidAppResolve("+JSONObject.quote(id)+","+JSONObject.quote(sid)+","+JSONObject.quote(vid)+","+JSONObject.quote(requestedQuality)+(ceiling>0?","+ceiling:"")+")");
            return request.future.get(timeoutMs,TimeUnit.MILLISECONDS);
        } catch (ExecutionException error) {
            if(error.getCause() instanceof AppLookupException)throw(AppLookupException)error.getCause();
            throw new IOException(error.getCause().getMessage(), error.getCause());
        }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw error; }
        catch (TimeoutException error) { throw new AppLookupException("App片源准备超时，请重试","ETIMEDOUT"); }
        finally {
            pending.remove(id);
            // A timed-out or interrupted native waiter must also abort its JS/native
            // registration/model job; completed replies already own their cleanup.
            if (!request.future.isDone()) {
                request.future.cancel(false);
                host.script("window.__androidAppCancelResolve && window.__androidAppCancelResolve("+JSONObject.quote(id)+")");
            }
        }
    }
    static void result(JSONObject params) throws Exception {
        Pending request=pending.get(params.getString("requestId"));
        if (request==null) return;
        try {
        if (!request.sid.equals(params.optString("seriesId")) || !request.vid.equals(params.optString("vid"))) throw new IOException("分集结果不匹配");
        String qualityField=params.has("requestedQuality")?"requestedQuality":params.has("quality")?"quality":null;
        if (qualityField==null?!request.requestedQuality.equals("auto"):!request.requestedQuality.equals(params.opt(qualityField))) throw new IOException("分集清晰度结果不匹配");
        if(request.playbackCeiling>0) {
            Object raw=params.opt("playbackCeiling");
            if(!(raw instanceof Number)||((Number)raw).doubleValue()!=request.playbackCeiling)throw new IOException("播放清晰度边界结果不匹配");
        } else if(params.has("playbackCeiling")&&params.optInt("playbackCeiling",-1)!=0)throw new IOException("播放清晰度边界结果不匹配");
        String error=params.isNull("error")?"":params.optString("error","");
        if (!error.isEmpty()) {
            String code=params.optString("errorCode","");
            request.future.completeExceptionally(new AppLookupException(error.length()>300?"App片源暂未取得":error,LOOKUP_TRANSIENT_CODES.contains(code)||code.equals("QUALITY_UNAVAILABLE")||RESPONSE_RETRY_CODE.equals(code)||code.equals("APP_INVALID_RESPONSE")?code:""));
        }
        else {
            JSONObject info=params.getJSONObject("playInfo");
            if (info.has("requestedQuality")&&!request.requestedQuality.equals(info.opt("requestedQuality"))) throw new IOException("片源清晰度结果不匹配");
            if(request.playbackCeiling>0) {
                Object raw=info.opt("playbackCeiling");
                if(!(raw instanceof Number)||((Number)raw).doubleValue()!=request.playbackCeiling)throw new IOException("播放片源边界结果不匹配");
                JSONArray variants=info.optJSONArray("variants");
                if(variants!=null)for(int i=0;i<variants.length();i++) {
                    JSONObject candidate=variants.getJSONObject(i);
                    if(candidate.has("playbackCeiling")&&candidate.optInt("playbackCeiling",-1)!=request.playbackCeiling)throw new IOException("备用片源边界结果不匹配");
                }
            }
            request.future.complete(new JSONObject(info.toString()).put("requestedQuality",request.requestedQuality));
        }
        } catch (Exception invalid) {
            // A malformed callback is a terminal contract error, not a future
            // timeout that may later masquerade as a recoverable network failure.
            request.future.completeExceptionally(invalid);
            throw invalid;
        }
    }
    /** Called only for private cache .part files; plaintext is published atomically by the cache. */
    static void download(String url,File destination,long maxBytes) throws Exception {
        download(url,destination,maxBytes,"auto");
    }
    static void download(String url,File destination,long maxBytes,String quality) throws Exception {
        String requestedQuality=MediaQuality.normalize(quality);
        Plan plan=find(url,requestedQuality);
        if (plan==null) {
            if (!requestedQuality.equals("auto")) throw new IOException("指定清晰度的片源准备信息已过期，请重新取源");
            if (AppTransport.domain(new URI(url).getHost(),"qznovelvod.com","fqnovelvod.com","douyinvod.com")) throw new IOException("App片源准备信息已过期，请重新取源");
            NetworkGuard.download(url,destination,maxBytes); return;
        }
        download(plan,destination,maxBytes);
    }
    /** Captured playback plans cannot be replaced by another job's URL/quality registration. */
    static void download(Plan plan,File destination,long maxBytes) throws Exception {
        if(plan==null)throw new IllegalArgumentException("播放片源准备信息无效");
        String requestedQuality=MediaQuality.normalize(plan.requestedQuality);
        Exception last=null;
        int transfers=0, refreshes=0;
        boolean forbiddenRetryUsed=false, forbiddenFailureSeen=false;
        Plan current=plan;
        for (int round=0;round<3;round++) {
            List<Plan> candidates=new ArrayList<>();candidates.add(current);candidates.addAll(current.variants);
            boolean anyRefreshableTransfer=false, dnsTransferFailure=false;
            for(Plan candidate:candidates) {
                String requestReferer=candidate.referer;
                while(transfers<MAX_MEDIA_TRANSFERS) {
                    if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException("播放缓存任务已取消");
                    boolean transferred=false;
                    try {
                        ++transfers;
                        NetworkGuard.download(candidate.url,destination,maxBytes,candidate.userAgent,requestReferer);
                        transferred=true;
                        if(candidate.contentKey!=null) {
                            byte[] key=new byte[16];for(int i=0;i<16;i++)key[i]=(byte)Integer.parseInt(candidate.contentKey.substring(i*2,i*2+2),16);
                            try { CencDecryptor.decryptInPlace(destination,key); } finally { Arrays.fill(key,(byte)0); }
                        }
                        return;
                    } catch(Exception error) {
                        if(destination.exists() && !destination.delete()) throw new IOException("未完成的视频清理未成功");
                        if(cancelled(error)) throw error;
                        boolean refreshable=!transferred&&refreshableTransfer(error);
                        anyRefreshableTransfer|=refreshable;
                        dnsTransferFailure|=refreshable&&hasDnsCause(error);
                        last=error;
                        boolean forbidden=refreshable&&!candidate.source.equals("web")&&"片源响应 HTTP 403".equals(error.getMessage());
                        forbiddenFailureSeen|=forbidden;
                        // Upstream compatibility: retry the exact signed URL once per job
                        // with the opposite Referer choice, inside the existing transfer cap.
                        if(forbidden&&!forbiddenRetryUsed&&transfers<MAX_MEDIA_TRANSFERS) {
                            forbiddenRetryUsed=true;
                            requestReferer=requestReferer.isEmpty()?"https://novelquickapp.com/":"";
                            recoveryPause();continue;
                        }
                    }
                    break;
                }
                if(transfers>=MAX_MEDIA_TRANSFERS)break;
            }
            // A failure belonging to one candidate must not veto another
            // candidate's recoverable 403. Fatal-only sets still never refresh.
            int refreshLimit=forbiddenFailureSeen?2:1;
            if(refreshes>=refreshLimit || transfers>=MAX_MEDIA_TRANSFERS || plan.seriesId==null || !anyRefreshableTransfer) break;
            if(current.source.equals("web"))markWebFailure(plan.seriesId,plan.vid);
            // The platform also negatively caches CDN DNS answers for two seconds.
            recoveryPause(dnsTransferFailure?2100:500L*(refreshes+1));
            // Refresh only this job's in-memory plan. The original URL remains its cache identity.
            ++refreshes;
            current=preparePlan(bindSeries(resolveApp(plan.seriesId,plan.vid,plan.requestedQuality,false,plan.playbackCeiling),plan.seriesId),plan.vid,false);
        }
        String addressChange=refreshes==0?"":current.url.equals(plan.url)?"，主地址未变化":"，主地址已变化";
        throw new IOException("App片源准备未成功："+(last==null?"请重试":last.getMessage())+
            "（"+requestedQuality+"，已重新取源"+refreshes+"次，媒体尝试"+transfers+"次"+addressChange+"）",last);
    }
    private static boolean hasDnsCause(Exception error) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=error;cause!=null&&seen.add(cause);cause=cause.getCause())
            if(cause instanceof java.net.UnknownHostException)return true;
        return false;
    }
    private static boolean refreshableTransfer(Exception error) {
        // CDN DNS errors are not SocketExceptions. They still qualify for the
        // existing single fresh-model round, preserving explicit 1080p quality.
        // TLS/certificate errors never gain retryability through a nested cause.
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        boolean dnsFailure=false;
        for(Throwable cause=error;cause!=null&&seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof javax.net.ssl.SSLException || cause instanceof java.security.cert.CertificateException)return false;
            if(cause instanceof java.net.UnknownHostException)dnsFailure=true;
        }
        if(dnsFailure)return true;
        if (error instanceof java.net.SocketTimeoutException || error instanceof TimeoutException || error instanceof EOFException || error instanceof java.net.SocketException || error instanceof NetworkGuard.MediaResponseException || error instanceof NetworkGuard.MediaRedirectException) return true;
        String message=error.getMessage();
        return "播放缓存下载超时".equals(message) || message!=null&&message.matches("片源响应 HTTP (?:403|404|408|410|5[0-9]{2})");
    }
    /** Playback may reconnect a transient empty model, but never malformed metadata or business denial. */
    static boolean canRecoverPlayback(Exception error) {
        if(error==null||cancelled(error))return false;
        boolean recoverable=false;Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=error;cause!=null&&seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof javax.net.ssl.SSLException||cause instanceof java.security.cert.CertificateException||cause instanceof JSONException||cause instanceof IllegalArgumentException||cause instanceof java.net.URISyntaxException||cause instanceof SecurityException||cause instanceof CancellationException)return false;
            if(cause instanceof AppLookupException) {
                String code=((AppLookupException)cause).code;
                if(RESPONSE_RETRY_CODE.equals(code)||code.equals("QUALITY_UNAVAILABLE")||LOOKUP_TRANSIENT_CODES.contains(code))recoverable=true;
                else return false;
            } else if(cause instanceof QualityUnavailableException)recoverable=true;
            else if(cause instanceof Exception&&refreshableTransfer((Exception)cause))recoverable=true;
        }
        return recoverable;
    }
    /** Only the playback coordinator may choose a lower quality; downloads remain strict. */
    static boolean canLowerQuality(Exception error) {
        if (error==null || cancelled(error)) return false;
        boolean recoverable=false;
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for (Throwable cause=error;cause!=null&&seen.add(cause);cause=cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLException || cause instanceof java.security.cert.CertificateException ||
                cause instanceof JSONException || cause instanceof IllegalArgumentException || cause instanceof java.net.URISyntaxException ||
                cause instanceof SecurityException || cause instanceof CancellationException) return false;
            if (cause instanceof QualityUnavailableException) recoverable=true;
            else if (cause instanceof AppLookupException) {
                String code=((AppLookupException)cause).code;
                if(RESPONSE_RETRY_CODE.equals(code)||code.equals("APP_INVALID_RESPONSE"))return false;
                if (code.equals("QUALITY_UNAVAILABLE") || LOOKUP_TRANSIENT_CODES.contains(code)) recoverable=true;
                else return false;
            } else if (cause instanceof Exception && refreshableTransfer((Exception)cause)) recoverable=true;
        }
        return recoverable;
    }
}
