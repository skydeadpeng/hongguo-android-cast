package io.github.jackotom.hongguo.android;

/** Compiled against a temporary test double only; no transport request leaves the test. */
public final class EpisodeResolverTransportTest {
    private static int checks;
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static NetworkGuard.Reply reply(int status, String body) { return new NetworkGuard.Reply(body, status, "https://hongguoduanju.com/player/_/2"); }
    private static String page(String sid, String vid) {
        return "<script>_ROUTER_DATA={\"loaderData\":{\"player_(series_id)/(vid)/page\":{\"series_id\":\"" + sid + "\",\"vid\":\"" + vid +
            "\",\"seriesDetail\":{\"series_id\":\"" + sid + "\"},\"publicVideoSeo\":{\"available\":true,\"seriesId\":\"" + sid + "\",\"itemId\":\"" + vid +
            "\"}}}}</script><script type=\"application/ld+json\">{\"@type\":\"VideoObject\",\"url\":\"https://hongguoduanju.com/player/" + sid + "/" + vid +
            "\",\"contentUrl\":\"https://cdn.example.net/video.mp4?token=a%20b&Range=1\"}</script>";
    }
    private static String canonicalPage(String sid, String vid) {
        return page(sid, vid).replace("https://hongguoduanju.com/player/" + sid + "/" + vid,
                "https://hongguoduanju.com/player/" + sid).replace("https://cdn.example.net/video.mp4?token=a%20b&Range=1",
                "https://hongguoduanju.com/video/public/" + vid + "/video.mp4?token=a%20b&Range=1");
    }
    private static void rejected(String code, String label) throws Exception {
        try { EpisodeResolver.resolve("1", "2"); throw new AssertionError("accepted " + label); }
        catch (EpisodeResolver.ResolverException ex) { check(code.equals(ex.code), label + " exact error code"); }
    }
    public static void main(String[] args) throws Exception {
        NetworkGuard.reset(reply(404, ""), reply(404, ""));
        try { EpisodeResolver.resolve("1", "2"); throw new AssertionError("404 accepted"); }
        catch (EpisodeResolver.ResolverException ex) { check("EPISODE_UNAVAILABLE".equals(ex.code) && ex.getMessage().contains("官网"), "404 explicit unavailable"); }
        check(NetworkGuard.requests() == 2, "two 404 candidates checked before unavailable");
        check("https://hongguoduanju.com/player/1/2".equals(NetworkGuard.requested.get(0)), "exact ordinary episode GET first");
        check("https://hongguoduanju.com/player/_/2".equals(NetworkGuard.requested.get(1)), "exact underscore episode GET second");
        NetworkGuard.reset(reply(503, ""), reply(503, ""));
        try { EpisodeResolver.resolve("1", "2"); throw new AssertionError("503 accepted"); }
        catch (EpisodeResolver.ResolverException ex) { check("SOURCE_HTTP".equals(ex.code) && ex.getMessage().contains("503"), "HTTP failure explicit"); }
        NetworkGuard.reset();
        try { EpisodeResolver.resolve("../1", "2"); throw new AssertionError("input accepted"); }
        catch (EpisodeResolver.ResolverException ex) { check("INVALID_INPUT".equals(ex.code) && NetworkGuard.requests() == 0, "input before transport"); }
        NetworkGuard.reset(reply(200, page("1", "2")));
        check("https://cdn.example.net/video.mp4?token=a%20b".equals(EpisodeResolver.resolve("1", "2")), "200 delegated to same parser");
        check(NetworkGuard.requests() == 1, "successful first candidate stops after one GET");
        NetworkGuard.reset(reply(404, ""), reply(200, page("1", "2")));
        check("https://cdn.example.net/video.mp4?token=a%20b".equals(EpisodeResolver.resolve("1", "2")), "ordinary 404 recovers through actual underscore page");
        check(NetworkGuard.requests() == 2 && NetworkGuard.requested.get(0).endsWith("/player/1/2") && NetworkGuard.requested.get(1).endsWith("/player/_/2"), "recovery keeps Mac candidate order");
        for (String[] wrong : new String[][]{{"9", "2"}, {"1", "9"}, {"_", "2"}}) {
            NetworkGuard.reset(reply(404, ""), reply(200, page(wrong[0], wrong[1])));
            rejected("IDENTITY_MISMATCH", "underscore model never changes expected sid/vid " + wrong[0] + "/" + wrong[1]);
            check(NetworkGuard.requests() == 2, "wrong backup identity only inspected after first candidate");
        }
        NetworkGuard.reset(reply(200, page("1", "9")), reply(200, page("1", "2")));
        check("https://cdn.example.net/video.mp4?token=a%20b".equals(EpisodeResolver.resolve("1", "2")), "first-candidate identity failure still permits a strictly matching backup");
        NetworkGuard.reset(reply(200, page("1", "2").replace("\"available\":true", "\"available\":false")), reply(200, page("1", "2")));
        check("https://cdn.example.net/video.mp4?token=a%20b".equals(EpisodeResolver.resolve("1", "2")), "first-candidate unavailable hint does not suppress backup evidence");
        NetworkGuard.reset(reply(404, ""), reply(200, page("1", "2").replace("https://hongguoduanju.com/player/1/2", "https://hongguoduanju.com/player/_/2")));
        rejected("NO_PLAYABLE_URL", "VideoObject URL must use actual expected sid, never underscore");
        NetworkGuard.reset(reply(200, canonicalPage("1", "2")));
        check("https://hongguoduanju.com/video/public/2/video.mp4?token=a%20b".equals(EpisodeResolver.resolve("1", "2")), "matching new canonical page succeeds directly");
        check(NetworkGuard.requests() == 1, "matching canonical page does not try another route");
        NetworkGuard.reset(reply(404, ""), reply(200, canonicalPage("1", "2")));
        check("https://hongguoduanju.com/video/public/2/video.mp4?token=a%20b".equals(EpisodeResolver.resolve("1", "2")), "matching canonical metadata also works on underscore backup route");
        check(NetworkGuard.requests() == 2, "canonical route fallback remains bounded to two GETs");
        NetworkGuard.reset(reply(200, canonicalPage("1", "2").replace("/video/public/2/", "/video/public/9/")), reply(200, canonicalPage("1", "2")));
        check("https://hongguoduanju.com/video/public/2/video.mp4?token=a%20b".equals(EpisodeResolver.resolve("1", "2")), "wrong media on first route is skipped for correct fallback media");
        NetworkGuard.reset(reply(404, ""), reply(200, canonicalPage("1", "2").replace("/video/public/2/", "/video/public/9/")));
        rejected("NO_PLAYABLE_URL", "new canonical recommendation cannot become playback");
        NetworkGuard.reset();
        Thread.currentThread().interrupt();
        try { EpisodeResolver.resolve("1", "2"); throw new AssertionError("interrupted request accepted"); }
        catch (java.io.InterruptedIOException expected) { check(NetworkGuard.requests() == 0, "cancelled resolution never starts another candidate"); }
        finally { Thread.interrupted(); }
        // Dual transport timeouts trigger one hedged retry of both routes at once; the
        // healthy underscore route wins the race with a fresh connection.
        NetworkGuard.reset(new java.net.SocketTimeoutException("hung edge"), new java.net.SocketTimeoutException("hung edge"),
                reply(200, page("1", "2")), new java.net.SocketTimeoutException("still hung"));
        check("https://cdn.example.net/video.mp4?token=a%20b".equals(EpisodeResolver.resolve("1", "2")), "dual transport timeouts hedge both routes and recover");
        check(NetworkGuard.requests() == 4, "hedged retry adds one GET per route");
        tokenRoutes();
        System.out.println("PASS EpisodeResolverTransportTest checks=" + checks + " networkRequests=0 stubbedGets=" + NetworkGuard.totalRequests);
    }
    private static final String TOKEN = "v1_5DC8VBWf14_jqPcnhiSPfJ6fiGI", TOKEN2 = "v1_2VMvyadqPpt6G-ZZqkmCa18eFHw", TOKEN3 = "v1_q8HExAwWPUBqpvQw_DdZQ9vFYP0";
    /** The official episode page since 2026-11-27: protected guest playback, its episodes named by token. */
    private static String guestPage(String sid, String pageId, String... tokens) {
        StringBuilder list = new StringBuilder();
        for (String token : tokens) list.append(list.length() == 0 ? "" : ",").append('"').append(token).append('"');
        return "<script>_ROUTER_DATA={\"loaderData\":{\"player_(series_id)/(vid)/page\":{\"protectedPlayback\":true,\"isSuccess\":true,\"series_id\":\"" + sid +
            "\",\"vid\":\"" + pageId + "\",\"req\":{\"pcLoginRequired\":false,\"pcAuthenticatedPlayback\":false,\"encryptedBrowserAllowed\":true}," +
            "\"seriesDetail\":{\"series_id\":\"" + sid + "\",\"episode_cnt\":3,\"accessible_episode_cnt\":3,\"vid_list\":[" + list + "]}," +
            "\"publicVideoSeo\":{\"available\":true,\"seriesId\":\"" + sid + "\",\"itemId\":\"" + pageId +
            "\",\"durationSeconds\":123.949,\"width\":360,\"height\":640}}}}</script>";
    }
    private static void tokenRoutes() throws Exception {
        // A list that remembered the page's tokens routes the guest flow by token; the result stays
        // bound to the numeric episode the caller selected.
        String sid = "7706908220253555737", first = "7706910230088518718", second = "7706910123855203390", third = "7706910114430585918";
        java.util.List<String> vids = java.util.Arrays.asList(first, second, third), tokens = java.util.Arrays.asList(TOKEN, TOKEN2, TOKEN3);
        EpisodeResolver.rememberWebIds(sid, vids, tokens);
        String route = "https://hongguoduanju.com/player/" + sid + "/" + TOKEN2;
        NetworkGuard.reset(new NetworkGuard.Reply(guestPage(sid, TOKEN2, TOKEN, TOKEN2, TOKEN3), 200, route));
        NetworkGuard.guestReply = new NetworkGuard.Reply("{\"base_resp\":{\"StatusCode\":0,\"StatusMessage\":\"\"},\"main_url\":\"https://v3-hgweb.qznovelvod.com/a/b/video/tos/cn/x\",\"url_expire\":" +
            (System.currentTimeMillis() / 1000 + 600) + ",\"server_time\":0}", 200, "");
        org.json.JSONObject info = EpisodeResolver.resolveInfo(sid, second);
        check(second.equals(info.getString("vid")) && sid.equals(info.getString("seriesId")) && info.getBoolean("guestPlayback") && "web".equals(info.getString("source")),
            "token page keeps the numeric episode identity");
        check("https://v3-hgweb.qznovelvod.com/a/b/video/tos/cn/x".equals(info.getString("url")) && info.getDouble("expectedDurationSeconds") == 123.949
            && info.getInt("width") == 360 && info.getInt("height") == 640, "token page metadata projects like a numeric guest page");
        check(NetworkGuard.requests() == 1 && NetworkGuard.requested.get(0).equals(route), "a known token is the one canonical route");
        check(TOKEN2.equals(NetworkGuard.guestItem), "the guest POST carries the page token, never the numeric vid");
        // Episode 4 has no token: the web route is simply unknown for it.
        EpisodeResolver.rememberWebIds("7001", java.util.Arrays.asList("4", "5"), java.util.Collections.<String>emptyList());
        NetworkGuard.reset(reply(404, ""), reply(404, ""));
        try { EpisodeResolver.resolve("7001", "4"); throw new AssertionError("accepted"); }
        catch (EpisodeResolver.ResolverException expected) { check(NetworkGuard.requests() == 2, "an episode beyond the page's tokens only tries the numeric routes"); }
        // A numeric web id is the vid itself: no extra route.
        EpisodeResolver.rememberWebIds("7002", java.util.Arrays.asList("2"), java.util.Arrays.asList("2"));
        NetworkGuard.reset(reply(200, page("7002", "2")));
        check("https://cdn.example.net/video.mp4?token=a%20b".equals(EpisodeResolver.resolve("7002", "2")), "a numeric web id keeps the ordinary route");
        check(NetworkGuard.requests() == 1, "a numeric web id adds no GET");
        // A stale token falls through to the numeric routes exactly as before.
        EpisodeResolver.rememberWebIds("7003", java.util.Arrays.asList("2"), java.util.Arrays.asList(TOKEN));
        NetworkGuard.reset(reply(404, ""), reply(404, ""), reply(404, ""));
        try { EpisodeResolver.resolve("7003", "2"); throw new AssertionError("stale token accepted"); }
        catch (EpisodeResolver.ResolverException expected) { check("EPISODE_UNAVAILABLE".equals(expected.code), "a stale token ends as unavailable"); }
        check(NetworkGuard.requests() == 3 && NetworkGuard.requested.get(0).endsWith("/player/7003/" + TOKEN), "the token route is tried once, then the numeric routes");
        // A token page that names another episode is a final guest verdict before any POST.
        EpisodeResolver.rememberWebIds("7004", java.util.Arrays.asList("2", "3"), java.util.Arrays.asList(TOKEN, TOKEN2));
        NetworkGuard.reset(new NetworkGuard.Reply(guestPage("7004", TOKEN, TOKEN, TOKEN2), 200, "https://hongguoduanju.com/player/7004/" + TOKEN2));
        try { EpisodeResolver.resolveInfo("7004", "3"); throw new AssertionError("mismatched token page accepted"); }
        catch (EpisodeResolver.GuestFailure expected) { check(true, "a token page for another episode is final"); }
        check(NetworkGuard.requests() == 1 && NetworkGuard.guestItem == null, "no numeric route and no guest POST after a mismatched token page");
        // A slow token page gets one longer retry before the numeric routes.
        NetworkGuard.reset(new java.net.SocketTimeoutException("slow"), new NetworkGuard.Reply(guestPage(sid, TOKEN, TOKEN, TOKEN2, TOKEN3), 200,
            "https://hongguoduanju.com/player/" + sid + "/" + TOKEN));
        NetworkGuard.guestReply = new NetworkGuard.Reply("{\"base_resp\":{\"StatusCode\":0},\"main_url\":\"https://v3-hgweb.qznovelvod.com/a/b/video/tos/cn/x\",\"url_expire\":" +
            (System.currentTimeMillis() / 1000 + 600) + "}", 200, "");
        check(first.equals(EpisodeResolver.resolveInfo(sid, first).getString("vid")), "a timed-out token route is retried once with a longer budget");
        check(NetworkGuard.requests() == 2 && TOKEN.equals(NetworkGuard.guestItem), "the retry answers without any numeric route");
        // Malformed ids are never remembered.
        EpisodeResolver.rememberWebIds("7005", java.util.Arrays.asList("2"), java.util.Arrays.asList("v1_short"));
        NetworkGuard.reset(reply(404, ""), reply(404, ""));
        try { EpisodeResolver.resolve("7005", "2"); throw new AssertionError("accepted"); }
        catch (EpisodeResolver.ResolverException expected) { check(NetworkGuard.requests() == 2, "a malformed web id is ignored"); }
    }
}
