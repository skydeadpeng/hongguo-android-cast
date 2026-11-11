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
        System.out.println("PASS EpisodeResolverTransportTest checks=" + checks + " networkRequests=0 stubbedGets=" + NetworkGuard.totalRequests);
    }
}
