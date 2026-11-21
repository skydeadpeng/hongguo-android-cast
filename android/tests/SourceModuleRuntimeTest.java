package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SourceModuleRuntimeTest {
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(2); }
    private static JSONArray args(Object... values) { return new JSONArray(java.util.Arrays.asList(values)); }
    private static void require(boolean condition, String what) { if (!condition) throw new AssertionError(what); }
    interface Work { void run() throws Exception; }
    private static void expect(String code, Work work) throws Exception {
        try { work.run(); throw new AssertionError("expected " + code); }
        catch (SourceModuleRuntime.Failure failure) { require(failure.code.equals(code), failure.code + " != " + code); }
    }
    private static String assets(Path base, String source, String file) throws IOException {
        return Files.readString(base.resolve(source).resolve(file));
    }
    private static SourceModuleRuntime runtime(Path base, String source, SourceModuleRuntime.Wire wire) throws Exception {
        return new SourceModuleRuntime(source, assets(base, source, "manifest.json"), assets(base, source, "runtime.js"), wire);
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]);
        String manifest = assets(base, "hongguo", "manifest.json");

        // The only registered module either answers categories locally or stops
        // terminally on an upstream denial, with exactly one wire call.
        int[] calls = {0};
        SourceModuleRuntime module = runtime(base, "hongguo", (uri, agent, ref, job) -> {
            calls[0]++;
            return new SourceModuleRuntime.Page(403, "", null);
        });
        try {
            Object categories = module.invoke("categories", args(), deadline());
            require(categories instanceof JSONArray && ((JSONArray) categories).length() > 0, "hongguo local categories");
            require(calls[0] == 0, "hongguo no calls before local categories");
        } catch (SourceModuleRuntime.Failure denied) {
            require(denied.code.equals("HTTP_403") && calls[0] == 1, "hongguo terminal categories: " + denied.code + " calls=" + calls[0]);
        }

        AtomicReference<Integer> requests = new AtomicReference<>(0);
        SourceModuleRuntime contracts = new SourceModuleRuntime("hongguo", manifest, """
            module.exports={categories(){
                const raw=host.parseHtml('<article data-idx="3">fixture<script type="application/json">{}</script></article>');
                const tree=JSON.stringify(raw);
                const data=JSON.parse(host.preserveIntegers('{"id":9007199254740993,"literal":"9007199254740993"}'));
                const k=host.utf8Encode('0123456789abcdef'),iv=new Uint8Array(16),nonce=new Uint8Array(12);
                const bytes=host.utf8Encode('secret-envelope!');
                const sealed=host.aesGcmSeal(k,nonce,bytes,host.utf8Encode("scope"));
                const opened=host.aesGcmOpen(k,nonce,sealed,host.utf8Encode("scope"));
                const cbc=host.aesCbcDecrypt(k,iv,host.aesCbcEncrypt(k,iv,bytes));
                const one=host.ecdhP256KeyPair(),two=host.ecdhP256KeyPair();
                const agree=host.hexEncode(host.ecdhP256(one.privateKey,two.publicKey))===host.hexEncode(host.ecdhP256(two.privateKey,one.publicKey));
                const reply=host.fetch({method:'POST',url:'https://www.hongguoduanju.com/offline-fixture',body:'{}',headers:{Origin:'https://foreign.example.com'}});
                const list=host.fetchPlaylist({url:'https://media.example.test/root/master.m3u8'});
                host.cacheSet("demo","cache",1000);
                const stateGone=(typeof host.saveState==='undefined')&&(typeof host.loadState==='undefined');
                return [tree.includes('article')&&tree.includes('application/json'), data.id==="9007199254740993"&&data.literal===data.id,
                    host.utf8Decode(opened)==='secret-envelope!'&&host.utf8Decode(cbc)==='secret-envelope!',agree,
                    host.hkdfSha256(k,iv,bytes,42).length===42, reply.status===200&&reply.headers['Content-Type'][0]==='application/json',
                    list.durationMs===2500&&list.url.indexOf('/root/leaf.m3u8')>=0,host.cacheGet("demo")==="cache",stateGone];
            }};
            """,(uri,agent,ref,job)->{
                requests.set(requests.get()+1);
                if (uri.getPath().equals("/offline-fixture")) {
                    require(job.request.method.equals("POST") && "{}".equals(job.request.body),"serialized JSON POST");
                    require(!job.request.headers.containsKey("authorization"),"authorization never reaches the wire");
                    require(!job.request.headers.containsKey("origin"),"foreign origin is stripped");
                    return new SourceModuleRuntime.Page(200,"{}",null,java.util.Collections.singletonMap("content-type",java.util.Collections.singletonList("application/json")));
                }
                return new SourceModuleRuntime.Page(200,uri.getPath().endsWith("master.m3u8")
                    ? "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nleaf.m3u8"
                    : "#EXTM3U\n#EXTINF:2.5,\na.ts\n#EXT-X-ENDLIST",null);
            });
        JSONArray covered=(JSONArray)contracts.invoke("categories",args(),deadline());
        for(int i=0;i<covered.length();i++) require(covered.getBoolean(i),"API-1 contract "+i);
        require(covered.length()==9&&requests.get()==3,"API-1 bridge contracts bounded requests");

        // Authorization and direct cookie input are rejected as terminal escapes a module cannot catch.
        SourceModuleRuntime authBlocked = new SourceModuleRuntime("hongguo", manifest,
            "module.exports={categories(){ host.fetch({url:'https://www.hongguoduanju.com/auth-fixture',headers:{Authorization:'Bearer fictitious'}}); return []; }};",
            (u,a,r,j) -> { throw new AssertionError("unexpected network"); });
        expect("SOURCE_ACCESS", () -> authBlocked.invoke("categories", args(), deadline()));
        SourceModuleRuntime cookieBlocked = new SourceModuleRuntime("hongguo", manifest,
            "module.exports={categories(){ host.fetchText({url:'https://www.hongguoduanju.com/cookie-fixture',cookie:'a=1'}); return []; }};",
            (u,a,r,j) -> { throw new AssertionError("unexpected network"); });
        expect("SOURCE_ACCESS", () -> cookieBlocked.invoke("categories", args(), deadline()));

        int[] count = {0};
        SourceModuleRuntime denied = runtime(base, "hongguo", (uri, agent, ref, job) -> {
            count[0]++;
            return new SourceModuleRuntime.Page(403, "", null);
        });
        try { denied.invoke("categories", args(), deadline()); throw new AssertionError("expected terminal denial"); }
        catch (SourceModuleRuntime.Failure expected) { require(count[0] == 1, "no retries on upstream denial, got " + count[0]); }
        SourceModuleRuntime noInterop = new SourceModuleRuntime("hongguo", manifest,
            "module.exports={categories(){return [typeof Packages,typeof java,typeof eval,typeof Function,typeof (function(){}).constructor]}};",
            (u, a, r, j) -> { throw new AssertionError("unexpected network"); });
        JSONArray vue = (JSONArray) noInterop.invoke("categories", args(), deadline());
        for (int i = 0; i < vue.length(); i++) require("undefined".equals(vue.getString(i)), "no Java or eval interop");

        SourceModuleRuntime loop = new SourceModuleRuntime("hongguo", manifest,
            "module.exports={categories(){try {for (;;) {}} catch (err) {} return ['escaped'];}};",
            (u, a, r, j) -> { throw new AssertionError("unexpected network"); });
        expect("SOURCE_TIMEOUT", () -> loop.invoke("categories", args(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(80)));
        expect("SOURCE_TIMEOUT", () -> noInterop.invoke("categories", args(), System.nanoTime() - 1));

        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        SourceModuleRuntime cancel = runtime(base, "hongguo", (uri, agent, ref, job) -> {
            started.countDown();
            if (!release.await(1, TimeUnit.SECONDS)) throw new AssertionError("cancel fixture not released");
            return new SourceModuleRuntime.Page(200, "", null);
        });
        Thread worker = new Thread(() -> {
            try { cancel.invoke("categories", args(), deadline()); }
            catch (Throwable failure) { result.set(failure); }
        });
        worker.start();
        require(started.await(1, TimeUnit.SECONDS), "request entered transport");
        expect("SOURCE_BUSY", () -> cancel.invoke("categories", args(), deadline()));
        cancel.cancel();
        release.countDown();
        worker.join(2000);
        require(!worker.isAlive() && result.get() instanceof SourceModuleRuntime.Failure
            && ((SourceModuleRuntime.Failure) result.get()).code.equals("ABORT_ERR"), "cancelled result discarded");
        Object fresh = cancel.invoke("categories", args(), deadline());
        require(fresh instanceof org.json.JSONObject, "fresh invocation works after cancel");
        System.out.println("ANDROID_SOURCE_RUNTIME_PASS modules=1 outbound=0 denial=terminal cancellation=verified hostContracts=9");
    }
}
