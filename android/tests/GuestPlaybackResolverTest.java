package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.json.*;

/** Offline contracts against production EpisodeResolver and, in a separate JVM, NetworkGuard. */
public final class GuestPlaybackResolverTest {
    private static final String SID = "123456", VID = "789012";
    private static final String ENDPOINT = "https://hongguoduanju.com/api/video/protected/fallback-play-info";
    private static final String MEDIA = "https://v3.fqnovelvod.com/video.mp4?token=a%2Fb%20c&range=0-9&x=1";
    private static final String CLEAN_MEDIA = "https://v3.fqnovelvod.com/video.mp4?token=a%2Fb%20c&x=1";
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean pass, String label) { checks++; if (!pass) throw new AssertionError(label); }
    private static void fails(Class<? extends Throwable> type, String message, Action action, String label) throws Exception {
        try { action.run(); throw new AssertionError("accepted " + label); }
        catch (Exception failure) {
            check(type.isInstance(failure), label + " failure type: " + failure);
            if (message != null) check(failure.getMessage() != null && failure.getMessage().contains(message), label + " failure message: " + failure);
        }
    }
    private static JSONObject pageData() throws Exception {
        return new JSONObject().put("isSuccess", true).put("protectedPlayback", true).put("series_id", SID).put("vid", VID)
            .put("seriesDetail", new JSONObject().put("series_id", SID).put("vid_list", new JSONArray().put("111111").put(VID)))
            .put("publicVideoSeo", new JSONObject().put("available", true).put("seriesId", SID).put("itemId", VID)
                .put("durationSeconds", 123.5).put("width", 1080).put("height", 1920)
                .put("contentUrl", "https://hongguoduanju.com/video/public/" + VID + "/seo-preview.mp4"))
            .put("req", new JSONObject().put("pcLoginRequired", false).put("pcAuthenticatedPlayback", false).put("encryptedBrowserAllowed", false));
    }
    private static String html(JSONObject data) throws Exception {
        JSONObject router = new JSONObject().put("loaderData", new JSONObject().put("player_(series_id)/(vid)/page", data));
        // Deliberately tempting SEO media: protected pages must never fall back to this URL.
        return "<script>window._ROUTER_DATA=" + router + "</script><script type=\"application/ld+json\">" +
            new JSONObject().put("@type", "VideoObject").put("url", "https://hongguoduanju.com/player/" + SID + "/" + VID)
                .put("contentUrl", "https://hongguoduanju.com/video/public/" + VID + "/seo-preview.mp4") + "</script>";
    }
    private static JSONObject success() throws Exception {
        return new JSONObject().put("base_resp", new JSONObject().put("StatusCode", 0)).put("main_url", MEDIA)
            .put("url_expire", System.currentTimeMillis() / 1000L + 3600);
    }
    private static NetworkGuard.Reply getReply(JSONObject page) throws Exception {
        return new NetworkGuard.Reply(html(page), 200, "https://hongguoduanju.com/player/" + SID + "/" + VID);
    }
    private static NetworkGuard.Reply postReply(int status, Object body) {
        return new NetworkGuard.Reply(body == null ? null : body.toString(), status, ENDPOINT);
    }
    private static JSONObject resolve(JSONObject page, Object post) throws Exception {
        FixtureTransport.reset(new Object[]{getReply(page)}, post);
        return EpisodeResolver.resolveInfo(SID, VID);
    }
    private static void rejectPage(JSONObject page, String label) throws Exception {
        NetworkGuard.Reply reply = getReply(page);
        FixtureTransport.reset(new Object[]{reply, reply}, postReply(200, success()));
        fails(IOException.class, null, () -> EpisodeResolver.resolveInfo(SID, VID), label);
        check(FixtureTransport.posts.get() == 0, label + " never POSTs");
        check(FixtureTransport.gets.get() == 1, label + " terminal page denial does not try another route");
    }
    private static void rejectPost(Object post, String label) throws Exception {
        FixtureTransport.reset(new Object[]{getReply(pageData())}, post);
        Class<? extends IOException> failureType = post instanceof InterruptedIOException && !(post instanceof SocketTimeoutException) ? InterruptedIOException.class : EpisodeResolver.GuestFailure.class;
        fails(failureType, null, () -> EpisodeResolver.resolveInfo(SID, VID), label);
        check(FixtureTransport.gets.get() == 1 && FixtureTransport.posts.get() == 1, label + " one GET and one POST, no retry/SEO fallback");
    }
    private static void resolverTests() throws Exception {
        JSONObject info = resolve(pageData(), postReply(200, success()));
        check(CLEAN_MEDIA.equals(info.getString("url")), "POST media used; signed query bytes preserved and Range removed");
        check(SID.equals(info.getString("seriesId")) && VID.equals(info.getString("vid")), "result retains requested series and episode");
        check(info.getBoolean("guestPlayback") && "web".equals(info.getString("source")), "guest provenance");
        check(info.getDouble("expectedDurationSeconds") == 123.5 && info.getInt("width") == 1080 && info.getInt("height") == 1920, "verification metadata from matching SEO");
        check("auto".equals(info.getString("requestedQuality")) && info.getLong("urlExpiresAt") > System.currentTimeMillis()/1000L, "quality and expiry metadata");
        check(FixtureTransport.gets.get() == 1 && FixtureTransport.posts.get() == 1 && VID.equals(FixtureTransport.postedId), "single POST binds exact VID");
        check(FixtureTransport.postThread == Thread.currentThread(), "POST executes only after page selection on caller thread");
        check(FixtureTransport.postDeadline > System.nanoTime() && FixtureTransport.postDeadline <= System.nanoTime() + 25_000_000_000L, "POST receives shared monotonic deadline");

        String canonical = html(pageData()).replace("player_(series_id)/(vid)/page", "player_(series_id)/page");
        FixtureTransport.reset(new Object[]{new NetworkGuard.Reply(canonical, 200, "https://hongguoduanju.com/player/_/" + VID)}, postReply(200, success()));
        check(CLEAN_MEDIA.equals(EpisodeResolver.resolveInfo(SID, VID).getString("url")), "canonical loader accepted with exact identities");
        FixtureTransport.reset(new Object[]{new NetworkGuard.Reply("", 404, ""), getReply(pageData())}, postReply(200, success()));
        EpisodeResolver.resolveInfo(SID, VID);
        check(FixtureTransport.gets.get() == 2 && FixtureTransport.posts.get() == 1, "404 fallback GET then single guest POST");
        check(FixtureTransport.requested.equals(Arrays.asList("https://hongguoduanju.com/player/" + SID + "/" + VID, "https://hongguoduanju.com/player/_/" + VID)), "GET routes and order stay exact");

        for (int status : new int[]{301, 302, 307, 308, 401, 403, 404, 429, 503}) {
            NetworkGuard.Reply denied = new NetworkGuard.Reply(html(pageData()), status, "https://hongguoduanju.com/player/" + SID + "/" + VID);
            FixtureTransport.reset(new Object[]{denied, denied}, postReply(200, success()));
            fails(EpisodeResolver.ResolverException.class, null, () -> EpisodeResolver.resolveInfo(SID, VID), "non-success GET " + status);
            check(FixtureTransport.gets.get() == 2 && FixtureTransport.posts.get() == 0, "GET HTTP refusal cannot authorize POST");
        }
        for (String key : new String[]{"series_id", "vid"}) {
            JSONObject p = pageData(); p.put(key, "999999"); rejectPage(p, "page identity " + key);
            p = pageData(); p.put(key, Long.parseLong(key.equals("vid") ? VID : SID)); rejectPage(p, "numeric page identity " + key);
        }
        for (String[] location : new String[][]{{"seriesDetail", "series_id"}, {"publicVideoSeo", "seriesId"}, {"publicVideoSeo", "itemId"}}) {
            JSONObject p = pageData(); p.getJSONObject(location[0]).put(location[1], "999999"); rejectPage(p, "nested identity " + Arrays.toString(location));
            p = pageData(); p.getJSONObject(location[0]).remove(location[1]); rejectPage(p, "missing nested identity " + Arrays.toString(location));
        }
        for (Object list : new Object[]{new JSONArray().put("999999"), new JSONArray().put(Long.parseLong(VID)), new JSONArray(), JSONObject.NULL, VID}) {
            JSONObject p = pageData(); p.getJSONObject("seriesDetail").put("vid_list", list); rejectPage(p, "VID list membership " + list);
        }
        for (Object value : new Object[]{false, "true", 1, JSONObject.NULL}) {
            JSONObject p = pageData(); p.put("isSuccess", value); rejectPage(p, "isSuccess is explicit true: " + value);
            p = pageData(); p.getJSONObject("publicVideoSeo").put("available", value); rejectPage(p, "SEO available is explicit true: " + value);
        }
        for (String key : new String[]{"pcLoginRequired", "pcAuthenticatedPlayback"}) {
            for (Object value : new Object[]{true, "false", 0, JSONObject.NULL}) {
                JSONObject p = pageData(); p.getJSONObject("req").put(key, value); rejectPage(p, key + " gate " + value);
            }
            JSONObject p = pageData(); p.getJSONObject("req").remove(key); rejectPage(p, "missing " + key);
        }
        // Browser permission is a capability selector, not guest authorization.
        // The native public-MP4 path has no encrypted browser player implementation.
        for (Object value : new Object[]{"true", "false", 0, 1, JSONObject.NULL}) {
            JSONObject invalid = pageData(); invalid.getJSONObject("req").put("encryptedBrowserAllowed", value);
            rejectPage(invalid, "browser capability metadata must be boolean " + value);
        }
        JSONObject absentCapability = pageData(); absentCapability.getJSONObject("req").remove("encryptedBrowserAllowed");
        rejectPage(absentCapability, "unknown browser capability is not guessed");
        final JSONObject nativePage = pageData(); nativePage.getJSONObject("req").put("encryptedBrowserAllowed", true);
        JSONObject nativeInfo = resolve(nativePage, postReply(200, success()));
        check(nativeInfo.getBoolean("guestPlayback") && CLEAN_MEDIA.equals(nativeInfo.getString("url")), "native unsupported browser crypto selects official public fallback even when browser flag true");
        check(FixtureTransport.gets.get() == 1 && FixtureTransport.posts.get() == 1, "native capability selection adds no retry or alternate request");
        for (String auth : new String[]{"pcLoginRequired", "pcAuthenticatedPlayback"}) {
            JSONObject denied = pageData(); denied.getJSONObject("req").put("encryptedBrowserAllowed", true).put(auth, true);
            rejectPage(denied, "true browser capability cannot override " + auth);
        }
        for (int status : new int[]{302, 401, 403}) {
            fails(EpisodeResolver.GuestFailure.class, null, () -> resolve(nativePage, postReply(status, success())), "native public route preserves HTTP refusal " + status);
            check(FixtureTransport.posts.get() == 1, "refused native public route makes one POST");
        }
        for (String protection : new String[]{"encrypt", "encrypted", "is_trial_video", "is_preview", "login_required"}) {
            fails(EpisodeResolver.GuestFailure.class, null, () -> resolve(nativePage, postReply(200, success().put(protection, true))), "browser capability cannot override response protection " + protection);
            check(FixtureTransport.posts.get() == 1, "protected native public response cannot retry");
        }
        JSONObject p = pageData(); p.getJSONObject("req").put("pcAuthenticatedPlayback", true); rejectPage(p, "authenticated player never guest");
        for (String flag : new String[]{"pcLoginRequired", "pcAuthenticatedPlayback"}) {
            p = pageData(); p.put("protectedPlayback", false); p.getJSONObject("req").put(flag, true);
            rejectPage(p, "login/auth gate rejects legacy SEO fallback too: " + flag);
        }
        p = pageData(); p.remove("req"); rejectPage(p, "missing request gates");
        p = pageData(); p.getJSONObject("req").remove("pcAuthenticatedPlayback");
        rejectPage(p, "authenticated gate must explicitly be false");

        for (Object value : new Object[]{0, -1, 0.99, 7200.1, "123.5", true, JSONObject.NULL}) {
            p = pageData(); p.getJSONObject("publicVideoSeo").put("durationSeconds", value); rejectPage(p, "duration rejected " + value);
        }
        for (double value : new double[]{1, 7200}) {
            p = pageData(); p.getJSONObject("publicVideoSeo").put("durationSeconds", value);
            check(resolve(p, postReply(200, success())).getDouble("expectedDurationSeconds") == value, "duration inclusive bound " + value);
        }
        for (String field : new String[]{"width", "height"}) {
            for (Object value : new Object[]{0, -1, 16385, 1080.5, "1080", true, JSONObject.NULL}) {
                p = pageData(); p.getJSONObject("publicVideoSeo").put(field, value); rejectPage(p, field + " rejected " + value);
            }
            for (int value : new int[]{1, 16384}) {
                p = pageData(); p.getJSONObject("publicVideoSeo").put(field, value);
                check(resolve(p, postReply(200, success())).getInt(field) == value, field + " inclusive bound " + value);
            }
        }
        for (Object value : new Object[]{"0", false, true, 1, -1, 0.5, JSONObject.NULL}) {
            JSONObject body = success(); body.getJSONObject("base_resp").put("StatusCode", value); rejectPost(postReply(200, body), "strict numeric zero status " + value);
        }
        for (String id : new String[]{"item_id", "vid", "series_id", "seriesId"}) {
            String expected = id.equals("item_id") || id.equals("vid") ? VID : SID;
            for (Object wrong : new Object[]{"999999", Long.parseLong(expected), JSONObject.NULL})
                rejectPost(postReply(200, success().put(id, wrong)), "conflicting response identity " + id);
            check(resolve(pageData(), postReply(200, success().put(id, expected))).getBoolean("guestPlayback"), "matching optional response identity " + id);
        }
        for (String code : new String[]{"code", "Code", "error_code"}) {
            for (Object wrong : new Object[]{1, -1, 0.5, "0", false, JSONObject.NULL})
                rejectPost(postReply(200, success().put(code, wrong)), "conflicting business status " + code);
            check(resolve(pageData(), postReply(200, success().put(code, 0))).getBoolean("guestPlayback"), "explicit numeric zero business status " + code);
        }
        for (String field : new String[]{"base_resp", "main_url", "url_expire"}) {
            JSONObject body = success(); body.remove(field); rejectPost(postReply(200, body), "missing " + field);
        }
        JSONObject noCode = success(); noCode.getJSONObject("base_resp").remove("StatusCode"); rejectPost(postReply(200, noCode), "missing StatusCode");
        for (int status : new int[]{201, 204, 301, 302, 303, 307, 308, 400, 401, 403, 404, 429, 500, 503})
            rejectPost(postReply(status, success()), "POST HTTP " + status);
        rejectPost(new IOException("官网游客播放信息不接受跳转"), "transport rejects redirect without another request");
        rejectPost(new SocketTimeoutException("POST timeout"), "POST timeout is never hedged or retried");
        for (String invalid : new String[]{"", "[]", "null", "{\"base_resp\":{\"StatusCode\":0},}", "{base_resp:{StatusCode:0}}", success().toString() + "junk", " ".repeat(65537)})
            rejectPost(postReply(200, invalid), "malformed or oversized JSON");
        rejectPost(postReply(200, null), "null body");
        long now = System.currentTimeMillis()/1000L;
        for (Object expiry : new Object[]{0, now - 1, now, 4102444801L, now + 100.25, "4102444800", true, JSONObject.NULL}) {
            JSONObject body = success(); body.put("url_expire", expiry); rejectPost(postReply(200, body), "expiry rejected " + expiry);
        }
        JSONObject boundary = success().put("url_expire", 4102444800L);
        check(resolve(pageData(), postReply(200, boundary)).getLong("urlExpiresAt") == 4102444800L, "expiry inclusive upper bound");
        for (String url : new String[]{"http://v3.fqnovelvod.com/v.mp4", "https://127.0.0.1/v.mp4", "https://[::1]/v.mp4", "https://v3.fqnovelvod.com:444/v.mp4", "https://user:pass@v3.fqnovelvod.com/v.mp4", "https://v3.fqnovelvod.com.evil.org/v.mp4", "https://notfqnovelvod.com/v.mp4", "https://cdn.example.net/v.mp4", "https://hongguoduanju.com/video/public/" + VID + "/seo-preview.mp4", "https://v3.fqnovelvod.com/v\\x.mp4", "https://v3.fqnovelvod.com/v\nx.mp4", "https://v3.fqnovelvod.com/v\u00a0x.mp4", "//v3.fqnovelvod.com/v.mp4", ""}) {
            rejectPost(postReply(200, success().put("main_url", url)), "unsafe or unrelated CDN URL");
        }
        for (String host : new String[]{"qznovelvod.com", "v3.qznovelvod.com", "fqnovelvod.com", "v3.fqnovelvod.com", "douyinvod.com", "v3.douyinvod.com"}) {
            String url = "https://" + host + "/video.mp4";
            check(url.equals(resolve(pageData(), postReply(200, success().put("main_url", url))).getString("url")), "official CDN host " + host);
        }
        for (String flag : new String[]{"is_trial_video", "is_preview", "encrypt", "encrypted", "login_required"}) {
            for (Object value : new Object[]{true, 1, 0, "false", JSONObject.NULL})
                rejectPost(postReply(200, success().put(flag, value)), "explicit protection flag " + flag + "=" + value);
            check(resolve(pageData(), postReply(200, success().put(flag, false))).getBoolean("guestPlayback"), "explicit false flag " + flag);
        }
        // SEO contentUrl may be missing, invalid, encrypted, or unrelated; only the POST URL is authoritative.
        for (Object seoUrl : new Object[]{JSONObject.NULL, "https://127.0.0.1/secret", "javascript:alert(1)", "https://cdn.example.net/wrong-episode.mp4"}) {
            p = pageData(); p.getJSONObject("publicVideoSeo").put("contentUrl", seoUrl);
            check(CLEAN_MEDIA.equals(resolve(p, postReply(200, success())).getString("url")), "SEO media is not consumed");
        }
        final String protectedHtml = html(pageData());
        FixtureTransport.reset(new Object[0], null);
        fails(EpisodeResolver.ResolverException.class, "授权", () -> EpisodeResolver.parse(protectedHtml, SID, VID), "legacy parse still rejects protected page");
        check(FixtureTransport.gets.get() == 0 && FixtureTransport.posts.get() == 0, "legacy parser remains pure");
        p = pageData(); p.put("protectedPlayback", false);
        info = resolve(p, new AssertionError("legacy page must not POST"));
        check(!info.has("guestPlayback") && info.getString("url").endsWith("/seo-preview.mp4"), "existing unprotected parser unchanged");
        check(FixtureTransport.posts.get() == 0, "unprotected page performs zero POST");
        strictJsonContracts();
        cancellationAndHedgeTests();
        System.out.println("PASS GuestPlaybackResolverTest resolver checks=" + checks + " realNetworkRequests=0 stubbedGets=" + FixtureTransport.totalGets.get() + " stubbedPosts=" + FixtureTransport.totalPosts.get());
    }

    private static void strictJsonContracts() throws Exception {
        String valid = success().toString();
        for (String token : new String[]{"1e-400", "-1e-400", "1e-324", "1e309", "-1e309", "0." + "0".repeat(1023)}) {
            String body = valid.replace("\"StatusCode\":0", "\"StatusCode\":" + token);
            rejectPost(postReply(200, body), "StatusCode rejects lossy or oversized numeric token");
            for (String key : new String[]{"code", "Code", "error_code", "unrelated_metadata"}) {
                body = valid.substring(0, valid.length() - 1) + ",\"" + key + "\":" + token + "}";
                rejectPost(postReply(200, body), "strict JSON numeric guard applies globally: " + key);
            }
            String rawPage = html(pageData()).replace("\"durationSeconds\":123.5", "\"durationSeconds\":" + token);
            rejectRawPage(rawPage, "page JSON rejects lossy or oversized numeric token");
        }
        for (String zero : new String[]{"0", "0e0", "-0", "0.0", "-0e0", "0." + "0".repeat(1022)}) {
            String body = valid.replace("\"StatusCode\":0", "\"StatusCode\":" + zero);
            body = body.substring(0, body.length() - 1) + ",\"code\":" + zero + ",\"Code\":" + zero + ",\"error_code\":" + zero + "}";
            check(resolve(pageData(), postReply(200, body)).getBoolean("guestPlayback"), "valid exact numeric zero accepted, length=" + zero.length());
        }
        String tiny = valid.substring(0, valid.length() - 1) + ",\"unrelated_metadata\":5e-324}";
        check(resolve(pageData(), postReply(200, tiny)).getBoolean("guestPlayback"), "representable nonzero subnormal is not mistaken for underflow");
        for (String fields : new String[]{
                "\"is_trial_video\":true,\"is_trial_video\":false",
                "\"is_trial_video\":false,\"is_trial_video\":false",
                "\"is_trial_video\":true,\"\\u0069s_trial_video\":false",
                "\"encrypted\":true,\"encrypted\":false",
                "\"login_required\":true,\"login_required\":false",
                "\"unused\":{\"key\":1,\"key\":2}"}) {
            String body = valid.substring(0, valid.length() - 1) + "," + fields + "}";
            rejectPost(postReply(200, body), "duplicate response key rejected before protection can be shadowed");
        }
        for (String keys : new String[]{"\"StatusCode\":1,\"StatusCode\":0", "\"StatusCode\":1,\"Status\\u0043ode\":0"}) {
            rejectPost(postReply(200, valid.replace("\"StatusCode\":0", keys)), "duplicate nested status key cannot mask denial");
        }
        String distinctObjects = valid.substring(0, valid.length() - 1) + ",\"one\":{\"key\":1},\"two\":{\"key\":2}}";
        check(resolve(pageData(), postReply(200, distinctObjects)).getBoolean("guestPlayback"), "same key in distinct objects remains valid");
        for (String replacement : new String[]{
                "\"pcLoginRequired\":true,\"pcLoginRequired\":false",
                "\"pcLoginRequired\":true,\"pc\\u004coginRequired\":false"}) {
            rejectRawPage(html(pageData()).replace("\"pcLoginRequired\":false", replacement), "duplicate page gate cannot mask login requirement");
        }
    }
    private static void rejectRawPage(String body, String label) throws Exception {
        NetworkGuard.Reply reply = new NetworkGuard.Reply(body, 200, "https://hongguoduanju.com/player/" + SID + "/" + VID);
        FixtureTransport.reset(new Object[]{reply, reply}, postReply(200, success()));
        fails(EpisodeResolver.ResolverException.class, null, () -> EpisodeResolver.resolveInfo(SID, VID), label);
        check(FixtureTransport.gets.get() == 2 && FixtureTransport.posts.get() == 0, label + " invalid JSON never reaches POST");
    }

    private static void cancellationAndHedgeTests() throws Exception {
        for (String[] ids : new String[][]{{"../1", VID}, {SID, "2x"}, {SID, ""}, {null, VID}}) {
            FixtureTransport.reset(new Object[0], null);
            fails(EpisodeResolver.ResolverException.class, null, () -> EpisodeResolver.resolveInfo(ids[0], ids[1]), "invalid IDs before transport");
            check(FixtureTransport.gets.get() == 0 && FixtureTransport.posts.get() == 0, "invalid IDs zero GET/POST");
        }
        FixtureTransport.reset(new Object[]{getReply(pageData())}, postReply(200, success()));
        Thread.currentThread().interrupt();
        try { fails(InterruptedIOException.class, null, () -> EpisodeResolver.resolveInfo(SID, VID), "already cancelled"); }
        finally { Thread.interrupted(); }
        check(FixtureTransport.gets.get() == 0 && FixtureTransport.posts.get() == 0, "cancelled before GET has no side effects");
        FixtureTransport.reset(new Object[]{getReply(pageData())}, postReply(200, success()));
        FixtureTransport.afterGet = () -> Thread.currentThread().interrupt();
        try { fails(InterruptedIOException.class, null, () -> EpisodeResolver.resolveInfo(SID, VID), "cancelled after GET"); }
        finally { Thread.interrupted(); }
        check(FixtureTransport.gets.get() == 1 && FixtureTransport.posts.get() == 0, "cancelled selected page never POSTs");
        FixtureTransport.reset(new Object[]{getReply(pageData())}, postReply(200, success()));
        FixtureTransport.afterPost = () -> Thread.currentThread().interrupt();
        try { fails(InterruptedIOException.class, null, () -> EpisodeResolver.resolveInfo(SID, VID), "cancelled during POST"); }
        finally { Thread.interrupted(); }
        check(FixtureTransport.posts.get() == 1, "cancelled POST no retry or usable result");
        rejectPost(new InterruptedIOException("cancelled POST"), "POST interrupted transport");
        for (boolean twoSuccesses : new boolean[]{true, false}) {
            Object second = twoSuccesses ? getReply(pageData()) : new SocketTimeoutException("hedged edge still hung");
            FixtureTransport.reset(new Object[]{new SocketTimeoutException("edge 1"), new SocketTimeoutException("edge 2"), getReply(pageData()), second}, postReply(200, success()));
            FixtureTransport.hedgeBarrier = new CountDownLatch(2);
            JSONObject result = EpisodeResolver.resolveInfo(SID, VID);
            check(result.getBoolean("guestPlayback"), "hedged guest selection succeeds");
            check(FixtureTransport.gets.get() == 4 && FixtureTransport.posts.get() == 1, "two hedged GETs produce exactly one POST, twoSuccesses=" + twoSuccesses);
            check(Collections.frequency(FixtureTransport.requested, "https://hongguoduanju.com/player/" + SID + "/" + VID) == 2 &&
                Collections.frequency(FixtureTransport.requested, "https://hongguoduanju.com/player/_/" + VID) == 2, "each GET route retried exactly once");
            check(FixtureTransport.postThread == Thread.currentThread(), "hedge workers never issue POST");
        }
        FixtureTransport.reset(new Object[]{new SocketTimeoutException("edge 1"), new SocketTimeoutException("edge 2"), getReply(pageData()), getReply(pageData())}, postReply(200, success()));
        FixtureTransport.hedgeBarrier = new CountDownLatch(2);
        FixtureTransport.holdHedge = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread owner = new Thread(() -> { try { EpisodeResolver.resolveInfo(SID, VID); failure.set(new AssertionError("cancelled hedge returned media")); } catch (Throwable e) { failure.set(e); } }, "guest-test-cancel-owner");
        owner.start();
        try {
            check(FixtureTransport.hedgeBarrier.await(3, TimeUnit.SECONDS), "both hedge GETs reached cancellation barrier");
            owner.interrupt(); owner.join(3000);
            check(!owner.isAlive() && failure.get() instanceof InterruptedIOException, "in-flight hedge cancellation terminates resolution");
            check(FixtureTransport.posts.get() == 0, "cancelled hedges never POST");
        } finally { owner.interrupt(); FixtureTransport.holdHedge.countDown(); owner.join(3000); }
    }

    /** A strict queue: unexpected GETs, POST retries, or SEO fetches fail instead of using a network. */
    public static final class FixtureTransport {
        static volatile Object[] replies = new Object[0]; static volatile Object post;
        static final AtomicInteger gets = new AtomicInteger(), posts = new AtomicInteger();
        static final AtomicInteger totalGets = new AtomicInteger(), totalPosts = new AtomicInteger();
        static final List<String> requested = new CopyOnWriteArrayList<>();
        static volatile String postedId; static volatile long postDeadline; static volatile Thread postThread;
        static volatile Runnable afterGet, afterPost;
        static volatile CountDownLatch hedgeBarrier, holdHedge;
        static void reset(Object[] next, Object nextPost) {
            replies = next; post = nextPost; gets.set(0); posts.set(0); requested.clear();
            postedId = null; postDeadline = 0; postThread = null; afterGet = afterPost = null; hedgeBarrier = holdHedge = null;
        }
        static NetworkGuard.Reply outcome(Object next) throws Exception {
            if (next instanceof Error) throw (Error) next;
            if (next instanceof Exception) throw (Exception) next;
            if (!(next instanceof NetworkGuard.Reply)) throw new AssertionError("missing transport fixture");
            return (NetworkGuard.Reply) next;
        }
        static NetworkGuard.Reply get(String url, int budget, long deadline) throws Exception {
            int at = gets.getAndIncrement(); totalGets.incrementAndGet(); requested.add(url);
            Object[] batch = replies;
            if (at >= batch.length) throw new AssertionError("unexpected extra GET " + url);
            Object next = batch[at];
            if (budget < 1 || budget > 6000 || deadline < System.currentTimeMillis()) throw new AssertionError("invalid GET budget");
            CountDownLatch barrier = hedgeBarrier, hold = holdHedge;
            if (at >= 2 && barrier != null) {
                barrier.countDown();
                if (!barrier.await(3, TimeUnit.SECONDS)) throw new AssertionError("hedge did not start both GETs");
                if (hold != null) hold.await();
            }
            if (afterGet != null) afterGet.run();
            return outcome(next);
        }
        static NetworkGuard.Reply post(String id, long deadline) throws Exception {
            totalPosts.incrementAndGet();
            if (posts.incrementAndGet() != 1) throw new AssertionError("unexpected repeated guest POST");
            postedId = id; postDeadline = deadline; postThread = Thread.currentThread();
            if (afterPost != null) afterPost.run();
            return outcome(post);
        }
    }

    /* This second mode uses the REAL NetworkGuard. The JVM hosts file and DoH seam
       prevent DNS traffic; SocketImpl and SSLContext substitute only the byte transport.
       No real socket, TLS session, HTTP server, or upstream service is opened. */
    private static void networkGuardTests() throws Exception {
        check(System.getProperty("jdk.net.hosts.file") != null, "offline hosts file configured");
        Socket.setSocketImplFactory(OfflineSocket::new);
        SSLContext context = new SSLContext(new OfflineTls(), new Provider("GuestOffline", "1", "offline only") {}, "TLS") {};
        context.init(null, null, null); SSLContext.setDefault(context);
        NetworkGuard.dohProbe = host -> {
            if (!"hongguoduanju.com".equals(host)) throw new AssertionError("unexpected DNS host " + host);
            try { return new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte)184, (byte)216, 34})}; }
            catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
        };
        for (String bad : new String[]{null, "", "../1", "1\"}", "12a", "1".repeat(31), "１２"}) {
            Wire.reset(200, "{}");
            fails(IOException.class, "编号", () -> NetworkGuard.guestPlaybackPost(bad, System.nanoTime() + 2_000_000_000L), "transport ID validation");
            check(Wire.connects.get() == 0, "invalid ID rejected before connect");
        }
        Wire.reset(200, "{}");
        fails(SocketTimeoutException.class, null, () -> NetworkGuard.guestPlaybackPost(VID, System.nanoTime() - 1), "expired transport deadline");
        check(Wire.connects.get() == 0, "expired deadline zero connects");
        Thread.currentThread().interrupt();
        try { fails(InterruptedIOException.class, null, () -> NetworkGuard.guestPlaybackPost(VID, System.nanoTime() + 2_000_000_000L), "cancelled transport"); }
        finally { Thread.interrupted(); }
        check(Wire.connects.get() == 0, "cancelled transport zero connects");
        Wire.reset(200, "{\"ok\":true}");
        NetworkGuard.Reply result = NetworkGuard.guestPlaybackPost(VID, System.nanoTime() + 2_000_000_000L);
        check(result.status == 200 && "{\"ok\":true}".equals(result.body) && ENDPOINT.equals(result.url), "real transport parses response and preserves fixed URL");
        assertWire();
        for (int status : new int[]{400, 401, 403, 404, 429, 500, 503}) {
            Wire.reset(status, "denied");
            result = NetworkGuard.guestPlaybackPost(VID, System.nanoTime() + 2_000_000_000L);
            check(result.status == status && "".equals(result.body), "real HTTP rejection retained without parsing body " + status);
            assertWire();
        }
        for (int status : new int[]{401, 403, 429, 503}) {
            Wire.reset(status, ""); Wire.extraHeaders = "Content-Encoding: gzip\r\n"; Wire.contentLength = "not-a-length";
            result = NetworkGuard.guestPlaybackPost(VID, System.nanoTime() + 2_000_000_000L);
            check(result.status == status && result.body.isEmpty(), "HTTP refusal remains terminal with unusable body framing " + status);
            assertWire();
        }
        Wire.reset(200, "{}"); Wire.extraHeaders = "Content-Encoding: gzip\r\n";
        fails(IOException.class, "压缩", () -> NetworkGuard.guestPlaybackPost(VID, System.nanoTime() + 2_000_000_000L), "successful HTTP response still validates body encoding");
        assertWire();
        for (int status : new int[]{301, 302, 303, 307, 308}) {
            for (String location : new String[]{"/api/video/protected/fallback-play-info", "https://other.fqnovelvod.com/redirect", "http://127.0.0.1/secret"}) {
                Wire.reset(status, "redirect"); Wire.location = location;
                result = NetworkGuard.guestPlaybackPost(VID, System.nanoTime() + 2_000_000_000L);
                check(result.status == status && "".equals(result.body) && ENDPOINT.equals(result.url), "real redirect returned as terminal HTTP rejection " + status);
                assertWire();
            }
        }
        NetworkGuard.dohProbe = null;
        System.out.println("PASS GuestPlaybackResolverTest real NetworkGuard checks=" + checks + " realNetworkRequests=0 fakeConnections=" + Wire.totalConnects.get());
    }
    private static void assertWire() {
        check(Wire.connects.get() == 1 && Wire.requests.size() == 1, "one POST connection, no retry/redirect");
        String request = Wire.requests.get(0), lower = request.toLowerCase(Locale.ROOT);
        check(request.startsWith("POST /api/video/protected/fallback-play-info HTTP/1.1\r\nHost: hongguoduanju.com\r\n"), "fixed POST origin and endpoint");
        check(request.endsWith("\r\n\r\n{\"item_id\":\"" + VID + "\"}"), "body has exact public episode only");
        check(lower.contains("\r\ncontent-type: application/json\r\n") && lower.contains("\r\ncontent-length: 20\r\n"), "JSON content type and byte length");
        check(!lower.contains("\r\ncookie:") && !lower.contains("\r\nauthorization:") && !lower.contains("\r\nproxy-authorization:"), "guest request has no credentials");
        check("HTTPS".equals(Wire.endpointIdentification), "production keeps TLS hostname verification");
    }
    private static final class Wire {
        static final AtomicInteger connects = new AtomicInteger(), totalConnects = new AtomicInteger();
        static final List<String> requests = new CopyOnWriteArrayList<>();
        static volatile int status; static volatile String body, location, endpointIdentification, extraHeaders, contentLength;
        static void reset(int nextStatus, String nextBody) { connects.set(0); requests.clear(); status = nextStatus; body = nextBody; location = null; endpointIdentification = null; extraHeaders = ""; contentLength = null; }
        static byte[] response() {
            return ("HTTP/1.1 " + status + " Test\r\nContent-Length: " + (contentLength == null ? body.getBytes(StandardCharsets.UTF_8).length : contentLength) + "\r\n" + extraHeaders +
                (location == null ? "" : "Location: " + location + "\r\n") + "\r\n" + body).getBytes(StandardCharsets.UTF_8);
        }
    }
    private static final class OfflineSocket extends SocketImpl {
        protected void create(boolean stream) { if (!stream) throw new AssertionError("unexpected UDP socket"); }
        protected void connect(SocketAddress address, int timeout) {
            InetSocketAddress target = (InetSocketAddress) address;
            if (!"93.184.216.34".equals(target.getAddress().getHostAddress()) || target.getPort() != 443) throw new AssertionError("unexpected socket target " + target);
            Wire.connects.incrementAndGet(); Wire.totalConnects.incrementAndGet();
        }
        protected void connect(String host, int port) { throw new AssertionError("unvalidated host connect"); }
        protected void connect(InetAddress address, int port) { connect(new InetSocketAddress(address, port), 1); }
        protected void bind(InetAddress host, int port) { throw new AssertionError("unexpected bind"); }
        protected void listen(int backlog) { throw new AssertionError("unexpected listen"); }
        protected void accept(SocketImpl socket) { throw new AssertionError("unexpected accept"); }
        protected InputStream getInputStream() { throw new AssertionError("raw socket read"); }
        protected OutputStream getOutputStream() { throw new AssertionError("raw socket write"); }
        protected int available() { return 0; }
        protected void close() { }
        protected void sendUrgentData(int data) { throw new AssertionError("unexpected urgent data"); }
        public Object getOption(int id) { return 0; }
        public void setOption(int id, Object value) { }
    }
    private static final class OfflineTls extends SSLContextSpi {
        protected void engineInit(KeyManager[] keys, TrustManager[] trust, SecureRandom random) { }
        protected SSLSocketFactory engineGetSocketFactory() { return new OfflineTlsFactory(); }
        protected SSLServerSocketFactory engineGetServerSocketFactory() { throw new AssertionError("unexpected server TLS"); }
        protected SSLEngine engineCreateSSLEngine() { throw new AssertionError("unexpected engine"); }
        protected SSLEngine engineCreateSSLEngine(String host, int port) { throw new AssertionError("unexpected engine"); }
        protected SSLSessionContext engineGetServerSessionContext() { return null; }
        protected SSLSessionContext engineGetClientSessionContext() { return null; }
    }
    private static final class OfflineTlsFactory extends SSLSocketFactory {
        public String[] getDefaultCipherSuites() { return new String[0]; }
        public String[] getSupportedCipherSuites() { return new String[0]; }
        public Socket createSocket(Socket raw, String host, int port, boolean close) {
            if (!"hongguoduanju.com".equals(host) || port != 443 || !close) throw new AssertionError("unexpected TLS target");
            return new OfflineTlsSocket();
        }
        public Socket createSocket(String host, int port) { throw new AssertionError("unexpected direct TLS"); }
        public Socket createSocket(String host, int port, InetAddress local, int localPort) { throw new AssertionError("unexpected direct TLS"); }
        public Socket createSocket(InetAddress host, int port) { throw new AssertionError("unexpected direct TLS"); }
        public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) { throw new AssertionError("unexpected direct TLS"); }
    }
    private static final class OfflineTlsSocket extends SSLSocket {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final InputStream input = new ByteArrayInputStream(Wire.response()); boolean closed;
        public InputStream getInputStream() { return input; }
        public OutputStream getOutputStream() { return sent; }
        public void setSoTimeout(int timeout) { }
        public synchronized void close() { if (!closed) { closed = true; Wire.requests.add(sent.toString(StandardCharsets.US_ASCII)); } }
        public SSLParameters getSSLParameters() { return new SSLParameters(); }
        public void setSSLParameters(SSLParameters params) { Wire.endpointIdentification = params.getEndpointIdentificationAlgorithm(); }
        public void startHandshake() { if (!"HTTPS".equals(Wire.endpointIdentification)) throw new AssertionError("hostname verification not enabled"); }
        public String[] getSupportedCipherSuites() { return new String[0]; }
        public String[] getEnabledCipherSuites() { return new String[0]; }
        public void setEnabledCipherSuites(String[] suites) { }
        public String[] getSupportedProtocols() { return new String[0]; }
        public String[] getEnabledProtocols() { return new String[0]; }
        public void setEnabledProtocols(String[] protocols) { }
        public SSLSession getSession() { return null; }
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        public void setUseClientMode(boolean mode) { }
        public boolean getUseClientMode() { return true; }
        public void setNeedClientAuth(boolean need) { }
        public boolean getNeedClientAuth() { return false; }
        public void setWantClientAuth(boolean want) { }
        public boolean getWantClientAuth() { return false; }
        public void setEnableSessionCreation(boolean create) { }
        public boolean getEnableSessionCreation() { return false; }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--network-guard".equals(args[0])) networkGuardTests();
        else if (args.length == 0) resolverTests();
        else throw new IllegalArgumentException("usage: GuestPlaybackResolverTest [--network-guard]");
    }
}
