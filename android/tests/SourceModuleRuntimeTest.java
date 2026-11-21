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
        for (String source : new String[]{"guipian", "hanxiaoquan"}) {
            List<String> requests = new ArrayList<>();
            boolean ghost = source.equals("guipian");
            String detailPage = ghost
                ? "<h1 class=\"media-title\">离线影视</h1><li class=\"swiper-slide ewave-tab\" data-target=\"#ewave-playlist-1\">线路</li><div id=\"ewave-playlist-1\"><li><a title='第1集' href='/play/1234-1-1.html'></a></li></section>"
                : "<h1 class=\"page-title\">离线影视</h1><a title=\"第1集\" href=\"/play/1234-1-1.html\"></a>";
            SourceModuleRuntime host = runtime(base, source, (uri, agent, ref, job) -> {
                require(!agent.isEmpty() && !ref.isEmpty(), "ordinary origin headers");
                String path = uri.getPath();
                requests.add(path);
                if (path.startsWith("/nv/") || path.startsWith("/hanxiaoquan/"))
                    return new SourceModuleRuntime.Page(200, detailPage, null);
                if (path.startsWith("/play/"))
                    return new SourceModuleRuntime.Page(200, "var now=\"https://media.example.test/a.m3u8\";", null);
                if (path.equals("/xml/rss.xml"))
                    return new SourceModuleRuntime.Page(200, "<item><title><![CDATA[怪谈]]></title><link>/nv/1234.html</link></item>", null);
                if (path.equals("/search.php"))
                    return new SourceModuleRuntime.Page(200, "<a href=\"/hanxiaoquan/1234.html\" title=\"怪谈\" class=\"module-item-pic\">", null);
                return new SourceModuleRuntime.Page(200, "<html><body></body></html>", null);
            });
            JSONArray categories = (JSONArray) host.invoke("categories", args(), deadline());
            require(categories.length() > 0 && requests.isEmpty(), source + " categories offline");
            JSONObject catalog = (JSONObject) host.invoke("catalogPage", args(1, categories.getJSONObject(0).getString("id")), deadline());
            require(catalog.getJSONArray("items").length() == 0, source + " catalog");
            JSONObject search = (JSONObject) host.invoke("search", args(1, "怪谈"), deadline());
            require(search.getJSONArray("items").length() == 1, source + " search");
            JSONObject detail = (JSONObject) host.invoke("detail", args("1234"), deadline());
            JSONObject drama = detail.getJSONObject("drama");
            JSONArray chapters = detail.getJSONArray("chapters");
            require(drama.getString("title").equals("离线影视") && chapters.length() == 1, source + " detail");
            JSONObject task = new JSONObject().put("dramaId", drama.getString("id")).put("chapter", chapters.getJSONObject(0)).put("index", 1).put("total", 1);
            JSONObject media = (JSONObject) host.invoke("resolveMedia", args(task), deadline());
            require(media.getString("format").equals("hls") && media.getString("url").equals("https://media.example.test/a.m3u8"), source + " media");
            require(requests.size() == 4, source + " no CDN requests in catalog host");
            expect("SOURCE_INVALID", () -> host.invoke("constructor", args(), deadline()));
        }

        int staticCategories=0, terminalCategories=0;
        for (String id:SourceModuleRuntime.IDS) {
            int[] calls={0};
            SourceModuleRuntime host=runtime(base,id,(uri,agent,ref,job)->{
                calls[0]++;
                return new SourceModuleRuntime.Page(403,"",null);
            });
            try {
                Object categories=host.invoke("categories",args(),deadline());
                require(categories instanceof JSONArray && ((JSONArray)categories).length()>0,id+" local categories");
                require(calls[0]==0,id+" no calls before local categories");
                staticCategories++;
            } catch(SourceModuleRuntime.Failure denied) {
                require(denied.code.equals("HTTP_403") && calls[0]==1,id+" terminal categories access: "+denied.code+" calls="+calls[0]);
                terminalCategories++;
            }
        }
        require(staticCategories+terminalCategories==15,"all signed modules executed categories");

        int fulfilledCategories=0;
        for (String id:new String[]{"beabox","dsd","heitao","huangguo-video","huangju","missav"}) {
            SourceModuleRuntime host=runtime(base,id,(uri,agent,ref,job)->{
                String body;
                switch(id) {
                    case "beabox":
                        body=new JSONObject().put("code",200).put("data",new JSONObject().put("home_tags","离线")).toString();
                        break;
                    case "dsd":
                    case "heitao":
                        body="<a href='/index.php/vod/type/id/2.html'>离线</a>";
                        break;
                    case "huangguo-video":
                        body="<a href='/videos?category=2'>离线</a>";
                        break;
                    case "huangju":
                        if ("/auth/guest".equals(uri.getPath())) {
                            require(job.request.method.equals("POST"),"normal guest API method");
                            body=new JSONObject().put("token","test-fixture-token").toString();
                        } else {
                            require("Bearer test-fixture-token".equals(job.request.headers.get("authorization")),"issued guest token scope");
                            body=new JSONArray().put(new JSONObject().put("slug","offline").put("name","离线")).toString();
                        }
                        break;
                    default:
                        body="<a href='/cn/genres/offline'>离线</a>";
                }
                return new SourceModuleRuntime.Page(200,body,null);
            });
            Object result;
            try { result=host.invoke("categories",args(),deadline()); }
            catch(SourceModuleRuntime.Failure error) { throw new AssertionError(id+" fixture failed: "+error.code,error); }
            require(result instanceof JSONArray && result.toString().contains("离线"),id+" representative success categories");
            fulfilledCategories++;
        }

        AtomicReference<Integer> requests=new AtomicReference<>(0);
        SourceModuleRuntime contracts=new SourceModuleRuntime("sorani",assets(base,"sorani","manifest.json"),"""
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
                const reply=host.fetch({method:'POST',url:'https://api.sorani.cc/offline-fixture',body:'{}',headers:{Authorization:'Bearer fictitious',Origin:'https://www.sorani.net'}});
                const list=host.fetchPlaylist({url:'https://media.example.test/root/master.m3u8'});
                host.cacheSet("demo","cache",1000);
                const state=host.exclusive(()=>{ host.saveState("session","fixture-secret"); return host.loadState("session"); });
                return [tree.includes('article')&&tree.includes('application/json'), data.id==="9007199254740993"&&data.literal===data.id,
                    host.utf8Decode(opened)==='secret-envelope!'&&host.utf8Decode(cbc)==='secret-envelope!',agree,
                    host.hkdfSha256(k,iv,bytes,42).length===42, reply.status===200&&reply.headers['Content-Type'][0]==='application/json',
                    list.durationMs===2500&&list.url.indexOf('/root/leaf.m3u8')>=0,host.cacheGet("demo")==="cache",state==="fixture-secret"];
            }};
            """,(uri,agent,ref,job)->{
                requests.set(requests.get()+1);
                if (uri.getPath().equals("/offline-fixture")) {
                    require(job.request.method.equals("POST") && "{}".equals(job.request.body),"serialized JSON POST");
                    require("Bearer fictitious".equals(job.request.headers.get("authorization")),"authorized metadata scope");
                    require("https://www.sorani.net".equals(job.request.headers.get("origin")),"declared frontend origin");
                    return new SourceModuleRuntime.Page(200,"{}",null,java.util.Collections.singletonMap("content-type",java.util.Collections.singletonList("application/json")));
                }
                require(!job.request.headers.containsKey("authorization"),"metadata authorization never sent to media");
                return new SourceModuleRuntime.Page(200,uri.getPath().endsWith("master.m3u8")
                    ? "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nleaf.m3u8"
                    : "#EXTM3U\n#EXTINF:2.5,\na.ts\n#EXT-X-ENDLIST",null);
            });
        JSONArray covered=(JSONArray)contracts.invoke("categories",args(),deadline());
        for(int i=0;i<covered.length();i++) require(covered.getBoolean(i),"API-1 contract "+i);
        require(covered.length()==9&&requests.get()==3,"API-1 bridge contracts bounded requests");

        int[] count = {0};
        SourceModuleRuntime denied = runtime(base, "hanxiaoquan", (uri, agent, ref, job) -> {
            count[0]++;
            return new SourceModuleRuntime.Page(403, "", null);
        });
        expect("HTTP_403", () -> denied.invoke("search", args(1, "甲"), deadline()));
        require(count[0] == 1, "no retries on upstream denial");
        SourceModuleRuntime challenge = runtime(base, "hanxiaoquan", (uri, agent, ref, job) -> {
            count[0]++;
            return new SourceModuleRuntime.Page(200, "<script>document.cookie=\"idc_search_browser=00000000\"</script>", null);
        });
        expect("SOURCE_ACCESS", () -> challenge.invoke("search", args(1, "乙"), deadline()));
        require(count[0] == 2, "browser check stopped before network");
        SourceModuleRuntime redirect = runtime(base, "guipian", (uri, agent, ref, job) -> {
            count[0]++;
            return new SourceModuleRuntime.Page(302, "", "https://example.test/other");
        });
        expect("SOURCE_ADDRESS", () -> redirect.invoke("detail", args("1234"), deadline()));
        require(count[0] == 3, "unlisted redirect stopped before network");
        // heitao: the site serves its player page only with the synthetic
        // age-confirmation cookie; resolveMedia must send exactly that value.
        AtomicReference<String> sentCookie = new AtomicReference<>("");
        SourceModuleRuntime heitaoPlayback = runtime(base, "heitao", (uri, agent, ref, job) -> {
            sentCookie.set(job.request.headers.getOrDefault("cookie", ""));
            if (uri.getPath().equals("/index.php/vod/player/id/1152/nid/1/sid/1.html")) {
                require("heitao_age_confirmed=1".equals(sentCookie.get()), "heitao age cookie sent");
                require("https://heitaodj.com/index.php/vod/play/id/1152/sid/1/nid/1.html".equals(job.request.headers.get("referer")), "player referer is the play page");
                return new SourceModuleRuntime.Page(200, "<script>var player_aaaa = {\"flag\":\"play\",\"encrypt\":0,\"trysee\":0,\"url\":\"https://media.example.test/ht.m3u8\"};</script>", null);
            }
            return new SourceModuleRuntime.Page(200, "", null);
        });
        JSONObject heitaoTask = new JSONObject()
            .put("dramaId", "heitao:1152")
            .put("chapter", new JSONObject().put("id", "heitao:1152:1:1").put("pageUrl", ""))
            .put("index", 1).put("total", 1);
        JSONObject heitaoMedia = (JSONObject) heitaoPlayback.invoke("resolveMedia", args(heitaoTask), deadline());
        require("hls".equals(heitaoMedia.getString("format")) && "https://media.example.test/ht.m3u8".equals(heitaoMedia.getString("url")), "heitao media resolved with age cookie");
        // Only the allowlisted exact value on its own source may pass; any
        // other cookie, or the same cookie on another source, stays blocked.
        SourceModuleRuntime foreignCookie = new SourceModuleRuntime("guipian", assets(base, "guipian", "manifest.json"),
            "module.exports={categories(){ host.fetchText({url:'https://guipianwu.com/index.html', cookie:'heitao_age_confirmed=1'}); return []; }};",
            (u, a, r, j) -> { throw new AssertionError("unexpected network"); });
        expect("SOURCE_ACCESS", () -> foreignCookie.invoke("categories", args(), deadline()));
        SourceModuleRuntime wrongCookie = new SourceModuleRuntime("heitao", assets(base, "heitao", "manifest.json"),
            "module.exports={categories(){ host.fetchText({url:'https://heitaodj.com/index.html', cookie:'heitao_age_confirmed=2'}); return []; }};",
            (u, a, r, j) -> { throw new AssertionError("unexpected network"); });
        expect("SOURCE_ACCESS", () -> wrongCookie.invoke("categories", args(), deadline()));
        SourceModuleRuntime noInterop = new SourceModuleRuntime("guipian", assets(base, "guipian", "manifest.json"),
            "module.exports={categories(){return [typeof Packages,typeof java,typeof eval,typeof Function,typeof (function(){}).constructor]}};",
            (u, a, r, j) -> { throw new AssertionError("unexpected network"); });
        JSONArray vue = (JSONArray) noInterop.invoke("categories", args(), deadline());
        for (int i = 0; i < vue.length(); i++) require("undefined".equals(vue.getString(i)), "no Java or eval interop");

        SourceModuleRuntime loop = new SourceModuleRuntime("guipian", assets(base, "guipian", "manifest.json"),
            "module.exports={categories(){try {for (;;) {}} catch (err) {} return ['escaped'];}};",
            (u, a, r, j) -> { throw new AssertionError("unexpected network"); });
        expect("SOURCE_TIMEOUT", () -> loop.invoke("categories", args(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(80)));
        expect("SOURCE_TIMEOUT", () -> noInterop.invoke("categories", args(), System.nanoTime() - 1));

        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        SourceModuleRuntime cancel = runtime(base, "guipian", (uri, agent, ref, job) -> {
            started.countDown();
            if (!release.await(1, TimeUnit.SECONDS)) throw new AssertionError("cancel fixture not released");
            return new SourceModuleRuntime.Page(200, "", null);
        });
        Thread worker = new Thread(() -> {
            try { cancel.invoke("detail", args("1234"), deadline()); }
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
        require(((JSONArray) cancel.invoke("categories", args(), deadline())).length() > 0, "fresh invocation works after cancel");
        System.out.println("ANDROID_SOURCE_RUNTIME_PASS modules=15 metadata=offline outbound=0 denial=terminal cancellation=verified staticCategories=" + staticCategories + " terminalCategories=" + terminalCategories + " fixtureCategories=" + fulfilledCategories + " hostContracts=9");
    }
}
