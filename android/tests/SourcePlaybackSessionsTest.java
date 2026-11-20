package io.github.jackotom.hongguo.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class SourcePlaybackSessionsTest {
    private static int count;
    private static void check(boolean value, String label) {
        count++;
        if (!value) throw new AssertionError(label);
    }
    private interface Step { void run() throws Exception; }
    private static void rejected(String label, Step step) throws Exception {
        try { step.run(); }
        catch (IOException expected) { check(true, label); return; }
        check(false, label);
    }
    private static JSONObject media(String suffix) throws Exception {
        return new JSONObject().put("url", "https://media.example.com/program." + suffix + "?sig=fixture");
    }
    private static void playlist(String text) throws IOException {
        SourcePlaybackSessions.checkPlaylist(text.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        String id = SourcePlaybackSessions.open("guipian", "第1集", media("m3u8"));
        check(!id.contains("sig") && SourcePlaybackSessions.isPending(id), "opaque pending token");
        check(SourcePlaybackSessions.take("other", () -> {}) == null, "unknown token");
        SourcePlaybackSessions.Session hls = SourcePlaybackSessions.take(id, releases::incrementAndGet);
        check(hls != null && hls.hls && hls.referer.equals("https://guipianwu.com/"), "HLS plan");
        check(hls.active() && !SourcePlaybackSessions.isPending(id) && SourcePlaybackSessions.take(id, () -> {}) == null, "single claim");
        check(hls.begin(cancelled::incrementAndGet), "register network call");
        SourcePlaybackSessions.closeSource("hanxiaoquan");
        check(hls.active(), "another source cannot close current session");
        SourcePlaybackSessions.closeSource("guipian");
        check(!hls.active() && releases.get() == 1 && cancelled.get() == 1, "disable revokes player and its live network");
        check(!hls.begin(cancelled::incrementAndGet), "no late network request");

        String replaced = SourcePlaybackSessions.open("guipian", "替换", media("m3u8"));
        String mp4id = SourcePlaybackSessions.open("hanxiaoquan", "第2集", media("mp4"));
        check(!SourcePlaybackSessions.isPending(replaced), "new opening revokes pending token");
        SourcePlaybackSessions.Session mp4 = SourcePlaybackSessions.take(mp4id, releases::incrementAndGet);
        check(mp4 != null && !mp4.hls && "video/mp4".equals(mp4.mimeType), "MP4 plan");
        check(mp4.begin(cancelled::incrementAndGet), "second network call");
        mp4.cancelRequests();
        check(!mp4.active() && releases.get() == 1 && cancelled.get() == 2, "failure stops network while retaining error screen");
        SourcePlaybackSessions.close(mp4id);
        check(releases.get() == 2 && cancelled.get() == 2, "closing error view does not double cancel");

        rejected("source allowlist", () -> SourcePlaybackSessions.open("other", "第2集", media("mp4")));
        rejected("unknown format", () -> SourcePlaybackSessions.open("guipian", "第2集", media("txt")));
        rejected("wrong format", () -> SourcePlaybackSessions.open("guipian", "第2集", media("m3u8").put("format", "mp4")));
        rejected("credentials", () -> SourcePlaybackSessions.open("guipian", "第2集", media("mp4").put("credentials", new JSONObject())));
        rejected("protocol", () -> SourcePlaybackSessions.open("guipian", "第2集", media("mp4").put("url", "http://media.example.com/video.mp4")));
        rejected("nonvideo header", () -> SourcePlaybackSessions.open("guipian", "第2集", media("mp4").put("userAgent", "Example\r\nX: value")));
        rejected("cross-source referer", () -> SourcePlaybackSessions.open("guipian", "第2集", media("mp4").put("referer", "https://www.jennyhow.com/")));
        rejected("referer query", () -> SourcePlaybackSessions.open("guipian", "第2集", media("mp4").put("referer", "https://guipianwu.com/?sig=fixture")));

        JSONObject manifest = new JSONObject().put("id", "huangguo-video").put("api", 1).put("runtime", "js")
            .put("hosts", new JSONArray().put("fixture.example.com")).put("mediaAuthPaths", new JSONArray().put("/api/hls_key/"));
        JSONObject credentials = new JSONObject().put("origin", "https://media.example.com").put("cookie", "session=fixture")
            .put("expiresMs", System.currentTimeMillis() + 60000);
        JSONObject authorized = media("m3u8").put("credentials", credentials).put("referer", "https://fixture.example.com/video/1");
        AtomicInteger grants = new AtomicInteger();
        String authorizedId = SourcePlaybackSessions.open("huangguo-video", "测试", authorized, manifest, (url, referer, deadline) -> {
            grants.incrementAndGet(); return "fixture-token";
        });
        SourcePlaybackSessions.Session session = SourcePlaybackSessions.take(authorizedId, () -> {});
        check(session != null && session.cookieFor(new URI("https://media.example.com/segment.ts")).equals("session=fixture"), "signed cookie sent to exact authorized origin");
        check(session.cookieFor(new URI("https://cdn.example.com/segment.ts")).isEmpty(), "signed cookie not forwarded across origin");
        check(session.authorizeKey(new URI("https://fixture.example.com/api/hls_key/1"), 1).equals("fixture-token"), "declared key endpoint receives normal grant");
        check(session.authorizeKey(new URI("https://fixture.example.com/api/hls_key/../elsewhere"), 1).isEmpty(), "normalization cannot escape grant scope");
        check(session.authorizeKey(new URI("https://other.example.com/api/hls_key/1"), 1).isEmpty() && grants.get()==1, "grant not forwarded to a different host");
        SourcePlaybackSessions.close(authorizedId);
        rejected("no key grant after close", () -> session.authorizeKey(new URI("https://fixture.example.com/api/hls_key/1"), 1));
        credentials.put("expiresMs", 1);
        rejected("expired scoped cookie", () -> SourcePlaybackSessions.open("huangguo-video", "测试", authorized, manifest));
        rejected("manifest mismatch", () -> SourcePlaybackSessions.open("a-different-source", "测试", media("mp4"), manifest));

        URI base = new URI("https://media.example.com/path/index.m3u8?sig=fixture");
        check(SourcePlaybackSessions.redirect(base, "../next.m3u8").equals(new URI("https://media.example.com/next.m3u8")), "relative redirects");
        check(SourcePlaybackSessions.redirect(base, "?sig=updated").equals(new URI("https://media.example.com/path/index.m3u8?sig=updated")), "query-only redirects");
        rejected("private redirect", () -> SourcePlaybackSessions.redirect(base, "https://127.0.0.1/movie.mp4"));
        rejected("protected relay redirect", () -> SourcePlaybackSessions.redirect(base, "https://sub.api.zxcbug.com/fixture"));
        rejected("fragment redirect", () -> SourcePlaybackSessions.redirect(base, "#unrecognized"));

        String vod = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:5.0,\nseg.ts\n#EXT-X-ENDLIST\n";
        playlist(vod);
        check(true, "ordinary complete VOD");
        playlist(vod.replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin?f=1,2\",KEYFORMAT=\"identity\"\n#EXTINF"));
        check(true, "ordinary AES-128 key declaration");
        playlist("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nsmall/index.m3u8");
        check(true, "master playlist");
        rejected("HTML as playlist", () -> playlist("<html>Not media</html>"));
        rejected("live loop", () -> playlist(vod.replace("#EXT-X-ENDLIST", "")));
        rejected("unsupported method", () -> playlist(vod.replace("#EXTINF", "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key.bin\"\n#EXTINF")));
        rejected("unsupported format", () -> playlist(vod.replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128,KEYFORMAT=\"other\",URI=\"key.bin\"\n#EXTINF")));
        rejected("incomplete attributes", () -> playlist(vod.replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128,URI=\"unclosed\n#EXTINF")));
        rejected("steering", () -> playlist(vod.replace("#EXTINF", "#EXT-X-CONTENT-STEERING:SERVER-URI=\"steer.json\"\n#EXTINF")));

        System.out.println("PASS SourcePlaybackSessionsTest checks=" + count + " networkRequests=0");
    }
}
