package io.github.jackotom.hongguo.android;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/** Bounded API-1 host for reviewed APK assets. Never evaluates remote JavaScript. */
final class SourceModuleRuntime {
    static final Set<String> IDS = Collections.unmodifiableSet(new LinkedHashSet<>(Collections.singletonList("hongguo")));
    private static final Set<String> ORDINARY = new HashSet<>(Collections.singletonList("hongguo"));
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList("categories","catalogPage","search","detail","resolveMedia"));
    private static final Set<String> REQUEST_HEADERS = new HashSet<>(Arrays.asList("user-agent","accept","accept-language","content-type",
        "referer","origin","authorization","sec-fetch-dest","sec-fetch-mode","sec-fetch-site","x-requested-with"));
    private static final Set<String> RESPONSE_HEADERS = new HashSet<>(Arrays.asList("retry-after","content-type","date","location"));
    private static final String ROOT = "source-modules/";
    private static final int MAX_PAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 4 * 1024 * 1024;
    private static final int MAX_CODE_BYTES = 1024 * 1024;
    private static final int MAX_NETWORK_REQUESTS = 14;
    private static final long CALL_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(25);

    static final class Failure extends IOException {
        final String code;
        final int status;
        Failure(String code, String message) { this(code, message, 0); }
        Failure(String code, String message, int status) { super(message); this.code = code; this.status = status; }
    }

    // A dedicated escape from script, including a module's catch blocks. An
    // upstream authorization rejection never becomes a discovery/retry trigger.
    private static final class Stopped extends Error {
        final String code;
        final int status;
        Stopped(String code) { this(code,0); }
        Stopped(String code,int status) { super(null, null, false, false); this.code = code; this.status=status; }
    }

    static final class Job {
        final long deadline;
        final AtomicReference<Call> inFlight = new AtomicReference<>();
        volatile boolean cancelled;
        int instructions, requests;
        boolean exclusive;
        RequestOptions request;
        Job(long deadline) { this.deadline = deadline; }
        void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new Stopped("ABORT_ERR");
            if (System.nanoTime() - deadline >= 0) throw new Stopped("SOURCE_TIMEOUT");
        }
        void cancel() {
            cancelled = true;
            Call call = inFlight.get();
            if (call != null) call.cancel();
        }
    }

    static final class RequestOptions {
        String method="GET", body=null;
        int maxBytes=MAX_PAGE_BYTES, timeoutMs=15000;
        boolean redirects, allowStatus;
        final Map<String,String> headers=new LinkedHashMap<>();
        final Set<Integer> status=new HashSet<>();
    }

    static final class Page {
        final int status;
        final String body, location;
        final Map<String,List<String>> headers;
        Page(int status, String body, String location) { this(status,body,location,Collections.emptyMap()); }
        Page(int status, String body, String location,Map<String,List<String>> headers) {
            this.status = status; this.body = body; this.location = location; this.headers=headers;
        }
    }

    /** Fixture injection at one checked HTTP hop; production pins public IPs. */
    interface Wire { Page load(URI address, String agent, String referer, Job job) throws Exception; }

    private static final class CacheEntry {
        final String value;
        final long until;
        final int size;
        CacheEntry(String value,long until,int size) { this.value=value; this.until=until; this.size=size; }
    }

    private final String sourceId, code;
    private final JSONObject manifest;
    private final Set<String> metadataHosts;
    private final Wire wire;
    private final LinkedHashMap<String,CacheEntry> cache=new LinkedHashMap<>(16,0.75f,true);
    private int cacheBytes;
    private String preferred="";
    private final AtomicReference<Job> active = new AtomicReference<>();

    SourceModuleRuntime(Context context, String sourceId) throws Exception {
        this(sourceId, readBundle(context, sourceId), SourceModuleRuntime::loadPage);
    }

    private SourceModuleRuntime(String sourceId, String[] bundle, Wire wire) throws Exception {
        this(sourceId, bundle[0], bundle[1], wire);
    }

    // Package-local injection allows offline transport and interpreter fixtures.
    SourceModuleRuntime(String sourceId, String manifestJson,String code,Wire wire) throws Exception {
        if (!IDS.contains(sourceId)) throw new Failure("SOURCE_UNKNOWN", "来源未收录");
        this.sourceId=sourceId; this.code=code; this.wire=wire;
        manifest=new JSONObject(manifestJson);
        validateManifest(sourceId,manifest);
        JSONArray hosts=manifest.getJSONArray("hosts");
        metadataHosts=new LinkedHashSet<>();
        for (int i=0; i<hosts.length();i++) metadataHosts.add(hosts.getString(i).toLowerCase(Locale.ROOT));
        // Mirror-host DNS on these domains may be poisoned; module requests resolve DoH-first.
        NetworkGuard.registerDohFirstHosts(metadataHosts);
    }

    private static void validateManifest(String id,JSONObject value) throws Exception {
        if (!IDS.contains(id) || !id.equals(value.optString("id")) || value.optInt("api")!=1 ||
                !"js".equals(value.optString("runtime")) || !(value.opt("adult") instanceof Boolean) ||
                value.getBoolean("adult")==ORDINARY.contains(id))
            throw new Failure("SOURCE_INVALID","来源清单无效");
        JSONArray hosts=value.getJSONArray("hosts");
        if (hosts.length()==0 || hosts.length()>32) throw new Failure("SOURCE_INVALID","来源域名清单无效");
        Set<String> seen=new HashSet<>();
        for (int i=0;i<hosts.length();i++) {
            String host=hosts.getString(i).toLowerCase(Locale.ROOT);
            if (!host.matches("(?:\\*\\.)?[a-z0-9.-]+") || !seen.add(host)) throw new Failure("SOURCE_INVALID","来源域名清单无效");
            validateMedia("https://"+(host.startsWith("*.")?host.substring(2):host)+"/");
        }
    }

    JSONObject manifest() throws org.json.JSONException { return new JSONObject(manifest.toString()); }

    void cancel() {
        Job current = active.get();
        if (current != null) current.cancel();
    }

    Object invoke(String method, JSONArray args, long deadlineNanos) throws Exception {
        if (!METHODS.contains(method) || args == null || args.toString().length() > 2 * 1024 * 1024)
            throw new Failure("SOURCE_INVALID", "来源调用无效");
        long now = System.nanoTime();
        long deadline = deadlineNanos - now > CALL_LIMIT_NANOS ? now + CALL_LIMIT_NANOS : deadlineNanos;
        Job job = new Job(deadline);
        if (!active.compareAndSet(null, job)) throw new Failure("SOURCE_BUSY", "来源正在处理请求");
        try {
            job.check();
            Object result = new SourceContext(job).call(cx -> execute(cx, method, args, job));
            job.check();
            if (Undefined.isUndefined(result)) return JSONObject.NULL;
            if (!(result instanceof CharSequence) || ((CharSequence) result).length() > MAX_OUTPUT_CHARS)
                throw new Failure("SOURCE_INVALID", "来源结果无效或过大");
            return new JSONTokener(result.toString()).nextValue();
        } catch (Stopped stopped) {
            if (stopped.status>0) throw new Failure(stopped.code,"HTTP "+stopped.status,stopped.status);
            throw new Failure(stopped.code, stopped.code.equals("ABORT_ERR") ? "来源操作已取消" :
                stopped.code.equals("SOURCE_ACCESS") ? "SOURCE_ACCESS" : "来源操作超时");
        } catch (JavaScriptException js) {
            if (js.getValue() instanceof Scriptable) {
                Scriptable error = (Scriptable) js.getValue();
                String code = property(error, "sourceCode", "");
                if (Boolean.TRUE.equals(ScriptableObject.getProperty(error,"loginRequired"))) throw new Failure("LOGIN_REQUIRED","来源需要先登录");
                Object value = ScriptableObject.getProperty(error, "status");
                int status = value instanceof Number ? ((Number) value).intValue() : 0;
                if (status >= 400 && status <= 599 && code.equals("HTTP_" + status)) throw new Failure(code, "HTTP " + status, status);
                if (code.equals("SOURCE_ACCESS")) throw new Failure(code, "SOURCE_ACCESS");
                if (code.equals("SOURCE_ADDRESS")) throw new Failure(code, "来源地址无效");
                if (code.equals("SOURCE_SIZE")) throw new Failure(code, "来源网页内容过大");
                if (code.equals("SOURCE_TIMEOUT")) throw new Failure(code, "来源请求超时");
                if (code.equals("SOURCE_NETWORK")) throw new Failure(code, "来源网络请求失败");
                String message = property(error, "message", "");
                // Known module validation/lookup errors are static, local text.
                if (message.matches("(?:鬼片网|韩小圈)[\\u4e00-\\u9fff A-Za-z0-9，。！？（）]{1,80}")) throw new Failure("SOURCE_ERROR", message);
            }
            throw new Failure("SOURCE_ERROR", "来源未能处理该内容");
        } catch (RhinoException error) {
            throw new Failure("SOURCE_ERROR", "来源执行失败");
        } finally {
            active.compareAndSet(job, null);
        }
    }

    private final class SourceContext extends ContextFactory {
        private final Job job;
        SourceContext(Job job) { this.job = job; }
        @Override protected org.mozilla.javascript.Context makeContext() {
            org.mozilla.javascript.Context cx = super.makeContext();
            cx.setLanguageVersion(org.mozilla.javascript.Context.VERSION_ECMASCRIPT);
            cx.setInterpretedMode(true);
            cx.setMaximumInterpreterStackDepth(192);
            cx.setClassShutter(name -> false);
            cx.setInstructionObserverThreshold(4096);
            return cx;
        }
        @Override protected void observeInstructionCount(org.mozilla.javascript.Context cx, int instructions) {
            job.check();
            if ((job.instructions += instructions) > 20_000_000) throw new Stopped("SOURCE_TIMEOUT");
        }
    }

    private Object execute(org.mozilla.javascript.Context cx, String method, JSONArray args, Job job) {
        ScriptableObject scope = cx.initSafeStandardObjects();
        Scriptable functionPrototype = ScriptableObject.getFunctionPrototype(scope);
        ScriptableObject.deleteProperty(scope, "eval");
        ScriptableObject.deleteProperty(scope, "Function");
        ScriptableObject.deleteProperty(functionPrototype, "constructor");
        ScriptableObject module = (ScriptableObject) cx.newObject(scope);
        module.defineProperty("exports", cx.newObject(scope), ScriptableObject.PERMANENT);
        scope.defineProperty("module", module, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        ScriptableObject host = (ScriptableObject) cx.newObject(scope);
        host.defineProperty("source", sourceId, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        hostFunction(host, scope, "dramaId", (c, s, input) -> sourceId + ":" + asId(input, 0));
        hostFunction(host, scope, "chapterId", (c, s, input) -> sourceId + ":" + asId(input, 0) + ":" + asId(input, 1));
        hostFunction(host, scope, "parseUrl", (c, s, input) -> parseUrl(c, s, param(input, 0)));
        hostFunction(host, scope, "resolveUrl", (c,s,i) -> resolve(new URI(param(i,0)),param(i,1)).toString());
        hostFunction(host, scope, "fetchText", (c,s,i) -> fetch(c,s,argument(i,0),job,true));
        hostFunction(host, scope, "fetch", (c,s,i) -> fetch(c,s,argument(i,0),job,false));
        hostFunction(host, scope, "fetchPlaylist", (c,s,i) -> parseJson(c,s,playlist(argument(i,0),job).toString()));
        hostFunction(host, scope, "prepareWebMedia", (c,s,i) -> prepareMedia(c,s,argument(i,0)));
        hostFunction(host, scope, "finishMedia", (c,s,i) -> prepareMedia(c,s,argument(i,0)));
        hostFunction(host, scope, "preferredSite", (c,s,i) -> preferred);
        hostFunction(host, scope, "reserve", (c,s,i) -> { validateMedia(property(argument(i,0),"url","")); return 1; });
        hostFunction(host, scope, "retries", (c,s,i) -> 1);
        hostFunction(host, scope, "maxPages", (c,s,i) -> 100);
        hostFunction(host, scope, "sleep", (c,s,i) -> {
            int left=SourceRuntimeSupport.intValue(first(i,0),10000);
            while(left>0) { job.check(); int span=Math.min(left,100); try { Thread.sleep(span); } catch(InterruptedException stop) { throw new Stopped("ABORT_ERR"); } left-=span; }
            return null;
        });
        hostFunction(host, scope, "exclusive", (c,s,i) -> {
            if (job.exclusive || !(first(i,0) instanceof Function)) throw new IOException("来源操作无法重入");
            job.exclusive=true;
            try { return ((Function)i[0]).call(c,s,s,new Object[0]); } finally { job.exclusive=false; }
        });
        hostFunction(host, scope, "cacheGet", (c,s,i) -> cacheGet(param(i,0)));
        hostFunction(host, scope, "cacheSet", (c,s,i) -> { cacheSet(param(i,0),first(i,1),first(i,2)); return null; });
        hostFunction(host, scope, "catalogContext", (c,s,i) -> parseJson(c,s,"{\"more\":false,\"background\":false}"));
        hostFunction(host, scope, "isKnown", (c,s,i) -> false);
        hostFunction(host, scope, "reportProgress", (c,s,i) -> null);
        hostFunction(host, scope, "log", (c,s,i) -> null); // Never emit source responses, tokens or credentials.
        hostFunction(host, scope, "parseHtml", (c,s,i) -> parseJson(c,s,SourceRuntimeSupport.htmlTree(param(i,0),job).toString()));
        hostFunction(host, scope, "preserveIntegers", (c,s,i) -> SourceRuntimeSupport.preserveIntegers(param(i,0)));
        hostFunction(host, scope, "utf8Encode", (c,s,i) -> SourceRuntimeSupport.typed(c,s,SourceRuntimeSupport.bytes(param(i,0))));
        hostFunction(host, scope, "utf8Decode", (c,s,i) -> new String(bytes(i,0),StandardCharsets.UTF_8));
        hostFunction(host, scope, "hexEncode", (c,s,i) -> SourceRuntimeSupport.hex(bytes(i,0)));
        hostFunction(host, scope, "hexDecode", (c,s,i) -> SourceRuntimeSupport.typed(c,s,SourceRuntimeSupport.unhex(param(i,0))));
        hostFunction(host, scope, "base64Encode", (c,s,i) -> SourceRuntimeSupport.base64(bytes(i,0)));
        hostFunction(host, scope, "base64Decode", (c,s,i) -> SourceRuntimeSupport.typed(c,s,SourceRuntimeSupport.unbase64(param(i,0))));
        hostFunction(host, scope, "md5", (c,s,i) -> SourceRuntimeSupport.typed(c,s,SourceRuntimeSupport.digest("MD5",bytes(i,0))));
        hostFunction(host, scope, "sha256", (c,s,i) -> SourceRuntimeSupport.typed(c,s,SourceRuntimeSupport.digest("SHA-256",bytes(i,0))));
        hostFunction(host, scope, "randomBytes", (c,s,i) -> SourceRuntimeSupport.typed(c,s,SourceRuntimeSupport.random(SourceRuntimeSupport.intValue(first(i,0),1024))));
        hostFunction(host, scope, "aesCbcEncrypt", (c,s,i) -> SourceRuntimeSupport.typed(c,s,SourceRuntimeSupport.cbc(true,bytes(i,0),bytes(i,1),bytes(i,2))));
        hostFunction(host, scope, "aesCbcDecrypt", (c,s,i) -> SourceRuntimeSupport.typed(c,s,SourceRuntimeSupport.cbc(false,bytes(i,0),bytes(i,1),bytes(i,2))));
        hostFunction(host, scope, "aesGcmSeal", (c,s,i) -> SourceRuntimeSupport.typed(c,s,SourceRuntimeSupport.gcm(true,bytes(i,0),bytes(i,1),bytes(i,2),bytes(i,3))));
        hostFunction(host, scope, "aesGcmOpen", (c,s,i) -> SourceRuntimeSupport.typed(c,s,SourceRuntimeSupport.gcm(false,bytes(i,0),bytes(i,1),bytes(i,2),bytes(i,3))));
        hostFunction(host, scope, "ecdhP256KeyPair", (c,s,i) -> {
            byte[][] pair=SourceRuntimeSupport.ecdhPair();
            Scriptable result=c.newObject(s);
            result.put("privateKey",result,SourceRuntimeSupport.typed(c,s,pair[0]));
            result.put("publicKey",result,SourceRuntimeSupport.typed(c,s,pair[1]));
            return result;
        });
        hostFunction(host, scope, "ecdhP256", (c,s,i) -> SourceRuntimeSupport.typed(c,s,SourceRuntimeSupport.ecdh(bytes(i,0),bytes(i,1))));
        hostFunction(host, scope, "hkdfSha256", (c,s,i) -> SourceRuntimeSupport.typed(c,s,SourceRuntimeSupport.hkdf(bytes(i,0),bytes(i,1),bytes(i,2),SourceRuntimeSupport.intValue(first(i,3),1024))));
        host.sealObject();
        scope.defineProperty("host", host, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        cx.evaluateString(scope, "'use strict';\n" + code, sourceId + "/runtime.js", 1, null);
        Object exports = module.get("exports", module);
        if (!(exports instanceof Scriptable)) throw new IllegalStateException("来源没有导出");
        Object fn = ScriptableObject.getProperty((Scriptable) exports, method);
        if (!(fn instanceof Function)) throw new IllegalStateException("来源接口缺失");
        Scriptable json = (Scriptable) ScriptableObject.getProperty(scope, "JSON");
        Function parse = (Function) ScriptableObject.getProperty(json, "parse");
        Scriptable jsonArgs = (Scriptable) parse.call(cx, scope, json, new Object[]{args.toString()});
        Object[] values = new Object[args.length()];
        for (int i = 0; i < values.length; i++) values[i] = jsonArgs.get(i, jsonArgs);
        Object value = ((Function) fn).call(cx, scope, (Scriptable) exports, values);
        return NativeJSON.stringify(cx, scope, value, null, null);
    }

    interface HostAction {
        Object invoke(org.mozilla.javascript.Context cx, Scriptable scope, Object[] args) throws Exception;
    }

    private static void hostFunction(ScriptableObject host, Scriptable scope, String name, HostAction action) {
        BaseFunction fn = new BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            @Override public Object call(org.mozilla.javascript.Context cx, Scriptable current, Scriptable self, Object[] args) {
                try { return action.invoke(cx, scope, args); }
                catch (JavaScriptException moduleError) { throw moduleError; }
                catch (Failure failure) {
                    Scriptable error = cx.newObject(scope, "Error", new Object[]{failure.getMessage()});
                    error.put("sourceCode", error, failure.code);
                    error.put("status", error, failure.status);
                    throw new JavaScriptException(error, "", 0);
                } catch (Exception malformed) {
                    throw new JavaScriptException(cx.newObject(scope, "Error", new Object[]{"来源参数无效"}), "", 0);
                }
            }
        };
        host.defineProperty(name, fn, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
    }

    private static Object first(Object[] args,int index) { return index<args.length?args[index]:Undefined.instance; }
    private static byte[] bytes(Object[] args,int index) throws Exception { return SourceRuntimeSupport.bytes(first(args,index)); }

    private static String asId(Object[] args,int index) throws Failure {
        Object value=first(args,index);
        if (value instanceof CharSequence || value instanceof Number) return org.mozilla.javascript.Context.toString(value);
        throw new Failure("SOURCE_INVALID","来源编号无效");
    }

    private static Object parseJson(org.mozilla.javascript.Context cx,Scriptable scope,String value) {
        Scriptable json=(Scriptable)ScriptableObject.getProperty(scope,"JSON");
        Function parse=(Function)ScriptableObject.getProperty(json,"parse");
        return parse.call(cx,scope,json,new Object[]{value});
    }

    private Object cacheGet(String key) {
        CacheEntry value=cache.get(key);
        if (value==null) return null;
        if (value.until>0 && value.until<=System.currentTimeMillis()) {
            cache.remove(key); cacheBytes-=value.size; return null;
        }
        return value.value;
    }

    private void cacheSet(String key,Object raw,Object ttl) throws Failure {
        int size=raw instanceof CharSequence?raw.toString().getBytes(StandardCharsets.UTF_8).length:0;
        if (key.isEmpty() || key.length()>1024 || size>1024*1024 || (raw!=null && !(raw instanceof CharSequence) && !Undefined.isUndefined(raw)))
            throw new Failure("SOURCE_INVALID","来源缓存数据无效");
        CacheEntry old=cache.remove(key);
        if (old!=null) cacheBytes-=old.size;
        if (raw==null || Undefined.isUndefined(raw)) return;
        long time=ttl instanceof Number?Math.max(0,Math.min(86400000L,((Number)ttl).longValue())):0;
        cache.put(key,new CacheEntry(raw.toString(),time>0?System.currentTimeMillis()+time:0,size));
        cacheBytes+=size;
        while (cache.size()>128 || cacheBytes>4*1024*1024) {
            Iterator<CacheEntry> oldest=cache.values().iterator();
            cacheBytes-=oldest.next().size;
            oldest.remove();
        }
    }

    private static URI resolve(URI base,String raw) throws Exception {
        if (raw.length()>8192 || raw.indexOf('\\')>=0) throw new Failure("SOURCE_ADDRESS","来源地址无效");
        if (raw.startsWith("?") || raw.startsWith("#")) {
            String original=base.toString();
            int separator=original.indexOf('#');
            if (separator>=0) original=original.substring(0,separator);
            if (raw.startsWith("?")) {
                separator=original.indexOf('?');
                if (separator>=0) original=original.substring(0,separator);
            }
            return new URI(original+raw);
        }
        return base.resolve(new URI(raw));
    }

    private static Scriptable parseUrl(org.mozilla.javascript.Context cx, Scriptable scope, String raw) throws Exception {
        if (raw.length() > 8192) throw new Failure("SOURCE_ADDRESS", "来源地址无效");
        URI url = new URI(raw);
        Scriptable out = cx.newObject(scope);
        out.put("scheme", out, url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT));
        String hostname=url.getHost()==null?"":url.getHost().toLowerCase(Locale.ROOT);
        String port=url.getPort()>0?Integer.toString(url.getPort()):"";
        out.put("hostname", out, hostname);
        out.put("host", out, hostname+(port.isEmpty()?"":":"+port));
        out.put("port",out,port);
        String path = url.getRawPath();
        out.put("path", out, path == null || path.isEmpty() ? "/" : path);
        out.put("query",out,url.getRawQuery()==null?"":url.getRawQuery());
        out.put("fragment",out,url.getRawFragment()==null?"":url.getRawFragment());
        out.put("userInfo", out, url.getRawUserInfo() != null);
        out.put("href", out, raw);
        return out;
    }

    private static Scriptable prepareMedia(org.mozilla.javascript.Context cx, Scriptable scope, Scriptable input) throws Failure {
        String raw = property(input, "url", "");
        URI uri = validateMedia(raw);
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        String format=property(input,"format","");
        if (!"hls".equals(format) && !"mp4".equals(format)) format=path.endsWith(".m3u8")?"hls":"mp4";
        // Retain only the structured result fields consumed by the native player;
        // it independently checks transport authorization on every resource.
        Scriptable output = cx.newObject(scope);
        for (String key:new String[]{"referer","userAgent","credentials","variants","playlist","durationMs","quality","width","height","mediaAuth"})
            if (input.has(key,input)) output.put(key,output,input.get(key,input));
        output.put("url", output, raw);
        output.put("format", output, format);
        return output;
    }

    static URI validateMedia(String raw) throws Failure {
        try {
            URI uri = NetworkGuard.validate(raw, false);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith(".") || host.equals("api.zxcbug.com") || host.endsWith(".api.zxcbug.com")) throw new IOException();
            return uri;
        } catch (Exception error) { throw new Failure("SOURCE_ADDRESS", "来源地址无效"); }
    }

    private boolean metadataHost(String host) {
        String name=host.toLowerCase(Locale.ROOT);
        if (metadataHosts.contains(name)) return true;
        for (String pattern:metadataHosts)
            if (pattern.startsWith("*.") && name.endsWith(pattern.substring(1))) return true;
        return false;
    }

    private URI checkedSite(String raw) throws Failure {
        URI url = validateMedia(raw);
        if (!metadataHost(url.getHost())) throw new Failure("SOURCE_ADDRESS", "来源域名未收录");
        String query=url.getRawQuery();
        if (query!=null && query.toLowerCase(Locale.ROOT).contains("_idc_pass")) throw new Stopped("SOURCE_ACCESS");
        return url;
    }

    private String allowedReferrer(String value,boolean originOnly) throws Failure {
        if (value.isEmpty()) return "";
        URI uri=validateMedia(value);
        // Some sites deliberately use a distinct frontend origin. It may be
        // identified to the server, but never added to the API destination set.
        if (!metadataHost(uri.getHost())) return "";
        if (originOnly || uri.getRawQuery()!=null) return SourceRuntimeSupport.origin(uri)+(originOnly?"":"/");
        return value;
    }

    private RequestOptions options(Scriptable input,boolean text) throws Exception {
        RequestOptions opts=new RequestOptions();
        Object rawBody=ScriptableObject.getProperty(input,"body");
        if (rawBody!=Scriptable.NOT_FOUND && !Undefined.isUndefined(rawBody) && rawBody!=null && !(rawBody instanceof CharSequence))
            throw new Failure("SOURCE_INVALID","来源请求正文无效");
        String body=property(input,"body","");
        opts.method=property(input,"method",body.isEmpty()?"GET":"POST").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("GET","HEAD","POST").contains(opts.method) || (!body.isEmpty() && !"POST".equals(opts.method)))
            throw new Failure("SOURCE_INVALID","来源请求方法无效");
        if (body.getBytes(StandardCharsets.UTF_8).length>1024*1024) throw new Failure("SOURCE_SIZE","来源请求正文过大");
        if ("POST".equals(opts.method)) opts.body=body;
        opts.redirects=text||Boolean.TRUE.equals(ScriptableObject.getProperty(input,"followRedirects"));
        opts.allowStatus=Boolean.TRUE.equals(ScriptableObject.getProperty(input,"anyStatus"));
        Object limit=ScriptableObject.getProperty(input,"limit"), timeout=ScriptableObject.getProperty(input,"timeoutMs");
        if (limit instanceof Number) opts.maxBytes=Math.max(1024,Math.min(MAX_PAGE_BYTES,((Number)limit).intValue()));
        if (timeout instanceof Number) opts.timeoutMs=Math.max(1000,Math.min(30000,((Number)timeout).intValue()));
        Object statuses=ScriptableObject.getProperty(input,"acceptStatus");
        if (statuses instanceof Scriptable) {
            int c=SourceRuntimeSupport.intValue(ScriptableObject.getProperty((Scriptable)statuses,"length"),16);
            for (int i=0;i<c;i++) opts.status.add(SourceRuntimeSupport.intValue(ScriptableObject.getProperty((Scriptable)statuses,i),599));
        }
        opts.headers.put("user-agent",NetworkGuard.UA);
        opts.headers.put("accept",text?"text/html,application/xml,text/xml;q=0.9,*/*;q=0.8":"application/json, */*");
        if (opts.body!=null) opts.headers.put("content-type","application/json");
        Object headers=ScriptableObject.getProperty(input,"headers");
        if (headers instanceof Scriptable) {
            Scriptable map=(Scriptable)headers;
            Object[] ids=map.getIds();
            if(ids.length>24) throw new Failure("SOURCE_INVALID","来源请求头过多");
            for (Object key:ids) if (key instanceof String) {
                String name=((String)key).toLowerCase(Locale.ROOT);
                if ("cookie".equals(name)) throw new Stopped("SOURCE_ACCESS");
                if (REQUEST_HEADERS.contains(name)) addHeader(opts,name,property(map,(String)key,""));
            }
        }
        String user=property(input,"userAgent","");
        String referer=property(input,"referer","");
        if (!user.isEmpty()) addHeader(opts,"user-agent",user);
        if (!referer.isEmpty()) addHeader(opts,"referer",referer);
        // Direct Cookie input is never accepted; raw HTML challenges stay blocked.
        Object rawCookie=ScriptableObject.getProperty(input,"cookie");
        if (rawCookie!=Scriptable.NOT_FOUND && rawCookie!=null && !Undefined.isUndefined(rawCookie))
            throw new Stopped("SOURCE_ACCESS");
        return opts;
    }

    private void addHeader(RequestOptions opts,String key,String value) throws Exception {
        if (value.isEmpty()) return;
        if (value.length()>8192 || !value.matches("[ -~]+")) throw new Failure("SOURCE_INVALID","来源请求头无效");
        if ("authorization".equals(key)) throw new Stopped("SOURCE_ACCESS");
        if ("referer".equals(key)||"origin".equals(key)) value=allowedReferrer(value,"origin".equals(key));
        if (!value.isEmpty()) opts.headers.put(key,value);
    }

    private static final class Result {
        final URI address;
        final Page page;
        Result(URI address,Page page) { this.address=address; this.page=page; }
    }

    private Scriptable fetch(org.mozilla.javascript.Context cx, Scriptable scope, Scriptable input,Job job,boolean text) throws Exception {
        List<String> candidates=new ArrayList<>();
        candidates.add(property(input,"url",""));
        Object more=ScriptableObject.getProperty(input,"mirrors");
        if (text && more instanceof Scriptable) {
            int count=Math.min(5,SourceRuntimeSupport.intValue(ScriptableObject.getProperty((Scriptable)more,"length"),32));
            for(int i=0;i<count;i++) {
                Object url=ScriptableObject.getProperty((Scriptable)more,i);
                if (url instanceof CharSequence && !candidates.contains(url.toString())) candidates.add(url.toString());
            }
        }
        Failure last=null;
        for(String url:candidates) {
            try {
                URI target=checkedSite(url);
                Result result=fetchChecked(target,options(input,text),job,false);
                JSONObject json=new JSONObject().put("body",result.page.body).put("url",result.address.toString());
                if (!text) json.put("status",result.page.status).put("headers",jsonHeaders(result.page.headers))
                    .put("blocked",false).put("oversized",false);
                return (Scriptable)parseJson(cx,scope,json.toString());
            } catch(Failure failure) {
                last=failure;
                if (!(failure.code.equals("SOURCE_NETWORK")||failure.code.equals("SOURCE_TIMEOUT"))) break;
            }
        }
        throw last==null?new Failure("SOURCE_NETWORK","来源请求失败"):last;
    }

    private JSONObject jsonHeaders(Map<String,List<String>> values) throws Exception {
        JSONObject result=new JSONObject();
        for (Map.Entry<String,List<String>> entry:values.entrySet()) {
            String key=entry.getKey().toLowerCase(Locale.ROOT);
            if (!RESPONSE_HEADERS.contains(key)) continue;
            StringBuilder title=new StringBuilder();
            for(int i=0;i<key.length();i++) title.append(i==0||key.charAt(i-1)=='-'?Character.toUpperCase(key.charAt(i)):key.charAt(i));
            JSONArray rows=new JSONArray();
            for(String value:entry.getValue()) if(value.length()<=32768 && rows.length()<12) rows.put(value);
            result.put(title.toString(),rows);
        }
        return result;
    }

    private Result fetchChecked(URI address,RequestOptions opts,Job job,boolean media) throws Failure {
        for (int hop=0;hop<=3;hop++) {
            job.check();
            if (++job.requests>MAX_NETWORK_REQUESTS) throw new Stopped("SOURCE_TIMEOUT");
            Page page;
            job.request=opts;
            try {
                page=wire.load(address,opts.headers.getOrDefault("user-agent",NetworkGuard.UA),opts.headers.getOrDefault("referer",""),job);
            } catch(Failure failure) { throw failure; }
            catch(InterruptedIOException error) {
                job.check();
                throw new Failure("SOURCE_TIMEOUT","来源请求超时");
            } catch(Exception error) {
                job.check();
                throw new Failure("SOURCE_NETWORK","来源网络请求失败");
            } finally {
                job.request=null;
            }
            job.check();
            if (opts.redirects && Arrays.asList(301,302,303,307,308).contains(page.status)) {
                if(page.location==null || hop==3) throw new Failure("SOURCE_ADDRESS","来源跳转无效");
                URI target;
                try { target=resolve(address,page.location); }
                catch(Exception error) { throw new Failure("SOURCE_ADDRESS","来源跳转无效"); }
                target=media?validateMedia(target.toString()):checkedSite(target.toString());
                if (!SourceRuntimeSupport.origin(address).equals(SourceRuntimeSupport.origin(target))) {
                    // Disallow cross-origin submissions. Strip all authorization
                    // and site context before following any cross-origin GET.
                    if ("POST".equals(opts.method)) throw new Stopped("SOURCE_ACCESS");
                    opts.headers.remove("authorization"); opts.headers.remove("cookie"); opts.headers.remove("origin"); opts.headers.remove("referer");
                }
                if(page.status==303 || ((page.status==301||page.status==302)&&"POST".equals(opts.method))) {
                    opts.method="GET"; opts.body=null; opts.headers.remove("content-type");
                }
                address=target; continue;
            }
            if (page.status==429 || page.status==401 || page.status==403)
                throw new Stopped("HTTP_"+page.status,page.status);
            if ((page.status<200||page.status>=300) && !opts.allowStatus && !opts.status.contains(page.status))
                throw new Failure("HTTP_"+page.status,"HTTP "+page.status,page.status);
            if (!media && page.status>=200 && page.status<300) preferred=SourceRuntimeSupport.origin(address);
            return new Result(address,page);
        }
        throw new Failure("SOURCE_ADDRESS","来源跳转无效");
    }

    private JSONObject playlist(Scriptable input,Job job) throws Exception {
        URI address=validateMedia(property(input,"url",""));
        RequestOptions request=new RequestOptions();
        request.maxBytes=2*1024*1024;
        request.redirects=true;
        request.headers.put("accept","application/vnd.apple.mpegurl, application/x-mpegurl, */*");
        request.headers.put("user-agent",NetworkGuard.UA);
        addHeader(request,"user-agent",property(input,"userAgent",""));
        addHeader(request,"referer",property(input,"referer",""));
        Object raw=ScriptableObject.getProperty(input,"credentials");
        if (raw instanceof Scriptable) throw new Stopped("SOURCE_ACCESS");
        for (int depth=0;depth<4;depth++) {
            Result fetched=fetchChecked(address,request,job,true);
            String body=fetched.page.body.replaceFirst("^\\ufeff","").trim();
            if (!body.matches("(?s)^#EXTM3U(?:\\r?\\n.*)?$"))
                throw new Failure("SOURCE_INVALID","来源未返回有效 HLS 清单");
            boolean master=false;
            double duration=0;
            String next=null;
            for (String line:body.split("\\r?\\n")) {
                String value=line.trim();
                if (value.startsWith("#EXT-X-STREAM-INF:")) master=true;
                if (master&&!value.isEmpty()&&!value.startsWith("#")) { next=value; break; }
                if (value.startsWith("#EXTINF:")) {
                    String time=value.substring(8).split(",",2)[0].trim();
                    if (time.matches("[0-9]{1,8}(?:\\.[0-9]{1,9})?")) duration+=Double.parseDouble(time);
                }
            }
            if (!master) return new JSONObject().put("playlist",body).put("url",fetched.address.toString())
                .put("durationMs",Math.round(duration*1000));
            if (next==null) throw new Failure("SOURCE_INVALID","来源 HLS 子清单缺失");
            address=validateMedia(resolve(fetched.address,next).toString());
            if (!SourceRuntimeSupport.origin(address).equals(SourceRuntimeSupport.origin(fetched.address))) {
                request.headers.remove("cookie"); request.headers.remove("referer");
            }
        }
        throw new Failure("SOURCE_INVALID","来源 HLS 清单层级过深");
    }

    private static Page loadPage(URI uri,String agent,String referer,Job job) throws Exception {
        job.check();
        RequestOptions options=job.request;
        long end=Math.min(job.deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(options.timeoutMs));
        // Use already checked public addresses for this exact TLS destination.
        java.net.InetAddress[] addresses=NetworkGuard.sourceAddresses(uri,end);
        job.check();
        long remaining=end-System.nanoTime();
        if (remaining<=0) throw new InterruptedIOException();
        OkHttpClient client=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)),TimeUnit.MILLISECONDS)
            .dns(host->{
                if(!uri.getHost().equalsIgnoreCase(host)) throw new UnknownHostException("来源网络地址变更");
                return Arrays.asList(addresses);
            }).build();
        RequestBody body=options.body==null?null:RequestBody.create(options.body, (okhttp3.MediaType)null);
        Request.Builder request=new Request.Builder().url(uri.toASCIIString()).method(options.method,body);
        for(Map.Entry<String,String> header:options.headers.entrySet()) request.header(header.getKey(),header.getValue());
        Call call=client.newCall(request.build());
        job.inFlight.set(call);
        try {
            job.check();
            try(Response response=call.execute()) {
                int status=response.code();
                ResponseBody responseBody=response.body();
                String text="";
                boolean accessError=status==401||status==403||status==429;
                // Preserve terminal access status even with a huge challenge response.
                if (responseBody!=null && !accessError && !(status>=300&&status<400)) {
                    if(responseBody.contentLength()>options.maxBytes) throw new Failure("SOURCE_SIZE","来源网页内容过大");
                    text=readText(responseBody.byteStream(),options.maxBytes,job);
                }
                return new Page(status,text,response.header("Location"),response.headers().toMultimap());
            }
        } finally {
            job.inFlight.compareAndSet(call,null);
            client.connectionPool().evictAll();
        }
    }

    private static String readText(InputStream input, int maxBytes, Job job) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0, count;
            while ((count = stream.read(buffer)) != -1) {
                if (job != null) job.check();
                if ((total += count) > maxBytes) throw new Failure("SOURCE_SIZE", "来源网页内容过大");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAsset(Context context, String filename, int maxBytes) throws IOException {
        try (InputStream stream = context.getAssets().open(ROOT + filename); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count, total = 0;
            while ((count = stream.read(buffer)) != -1) {
                if ((total += count) > maxBytes) throw new Failure("SOURCE_SIZE", "捆绑来源文件过大");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static Map<String,JSONObject> readIndex(Context context) throws Exception {
        JSONObject index=new JSONObject(new String(readAsset(context,"index.json",32*1024),StandardCharsets.UTF_8));
        if(index.optInt("format")!=1) throw new Failure("SOURCE_INVALID","捆绑来源索引无效");
        JSONArray rows=index.getJSONArray("modules");
        if(rows.length()!=IDS.size()) throw new Failure("SOURCE_INVALID","捆绑来源索引无效");
        Map<String,JSONObject> result=new LinkedHashMap<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);
            String id=row.getString("id");
            if(!IDS.contains(id)||result.put(id,row.getJSONObject("sha256"))!=null)
                throw new Failure("SOURCE_INVALID","捆绑来源索引无效");
        }
        return result;
    }
    private static String asset(Context context,String id,String name,JSONObject hashes) throws Exception {
        byte[] data=readAsset(context,id+"/"+name,MAX_CODE_BYTES);
        String digest=SourceRuntimeSupport.hex(MessageDigest.getInstance("SHA-256").digest(data));
        if(!digest.equals(hashes.optString(name))) throw new Failure("SOURCE_INVALID","捆绑来源摘要不匹配");
        return new String(data,StandardCharsets.UTF_8);
    }
    static JSONArray listManifests(Context context) throws Exception {
        JSONArray result=new JSONArray();
        for(Map.Entry<String,JSONObject> row:readIndex(context).entrySet()) {
            JSONObject value=new JSONObject(asset(context,row.getKey(),"manifest.json",row.getValue()));
            validateManifest(row.getKey(),value);
            result.put(value);
        }
        return result;
    }
    private static String[] readBundle(Context context,String sourceId) throws Exception {
        if (!IDS.contains(sourceId)) throw new Failure("SOURCE_UNKNOWN","来源未收录");
        JSONObject hashes=readIndex(context).get(sourceId);
        String manifest=asset(context,sourceId,"manifest.json",hashes);
        String runtime=asset(context,sourceId,"runtime.js",hashes);
        asset(context,sourceId,"main.js",hashes);
        return new String[]{manifest,runtime};
    }

    private static String param(Object[] args, int i) throws Failure {
        if (i >= args.length || !(args[i] instanceof CharSequence)) throw new Failure("SOURCE_INVALID", "来源参数无效");
        return args[i].toString();
    }

    private static Scriptable argument(Object[] args, int i) throws Failure {
        if (i >= args.length || !(args[i] instanceof Scriptable)) throw new Failure("SOURCE_INVALID", "来源参数无效");
        return (Scriptable) args[i];
    }

    private static String property(Scriptable input, String key, String fallback) {
        Object value = ScriptableObject.getProperty(input, key);
        return value instanceof CharSequence ? value.toString() : fallback;
    }
}
