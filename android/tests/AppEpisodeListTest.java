package io.github.jackotom.hongguo.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.json.*;

/** Signed series detail: request shape, wire answers and projection; parser only, no network. */
public final class AppEpisodeListTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    interface Throwing { void run() throws Exception; }
    static void rejects(String code, Throwing task, String label) throws Exception {
        try { task.run(); }
        catch (AppEpisodeList.ListError expected) { check(code.equals(expected.code), label + " code=" + expected.code); return; }
        throw new AssertionError("accepted " + label);
    }
    static final String SID = "7706908220253555737";
    static final String[] VIDS = { "7706910230088518718", "7706910123855203390", "7706910114430585918", "7706910130863868990" };
    static OfficialApiClient.Response response(int status, String encoding, byte[] body, String retryAfter) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (!encoding.isEmpty()) headers.put("Content-Encoding", Collections.singletonList(encoding));
        if (!retryAfter.isEmpty()) headers.put("Retry-After", Collections.singletonList(retryAfter));
        return new OfficialApiClient.Response(status, headers, body);
    }
    static byte[] gzip(byte[] plain) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(plain); }
        return out.toByteArray();
    }
    /** The wire shape of 2026-11-27: identities are unquoted integers beyond 2^53; rows are out of order. */
    static String row(int index, String... overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("vid", VIDS[index - 1]); fields.put("vid_index", String.valueOf(index)); fields.put("series_id", SID);
        fields.put("disable_play", index == 4 ? "true" : "false");
        for (String override : overrides) fields.put(override.substring(0, override.indexOf('=')), override.substring(override.indexOf('=') + 1));
        return "{\"vid\":" + scalar(fields.get("vid")) + ",\"vid_index\":" + fields.get("vid_index") + ",\"series_id\":" + scalar(fields.get("series_id"))
            + ",\"title\":\"第" + index + "集\",\"disable_play\":" + fields.get("disable_play") + "}";
    }
    static String scalar(String value) { return value.matches("true|false|[0-9]{1,30}") ? value : JSONObject.quote(value); }
    static String reply(String... rows) {
        StringBuilder list = new StringBuilder();
        for (String row : rows) list.append(list.length() == 0 ? "" : ",").append(row);
        return "{\"BaseResp\":{\"StatusCode\":0,\"StatusMessage\":\"\"},\"code\":0,\"message\":\"success\",\"log_id\":\"x\",\"data\":{\"video_data\":{"
            + "\"series_id\":" + SID + ",\"series_id_str\":\"" + SID + "\",\"series_title\":\"得了一种只能说真话的病\","
            + "\"series_cover\":\"https://p3-reading-sign.fqnovelpic.com/novel-pic/x~tplv-noop.image\",\"series_intro\":\"简介\",\"episode_cnt\":" + rows.length
            + ",\"episode_right_text\":\"全" + rows.length + "集\",\"content_type\":1,\"video_list\":[" + list + "]}}}";
    }
    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static void shape() throws Exception {
        long now = 1_795_742_574_123L, ts = now / 1000;
        String iid = "9007199254741993", did = "9007199254740993", cdid = "00000000-0000-4000-8000-000000000003";
        AppEpisodeList.Request request = AppEpisodeList.request(did, iid, cdid, SID, now);
        URI uri = new URI(request.url);
        check("api5-normal-sinfonlineb.fqnovel.com".equals(uri.getHost()) && "/novel/player/video_detail/v1/".equals(uri.getRawPath())
            && uri.getPort() == -1 && uri.getUserInfo() == null, "signed detail endpoint");
        Map<String, String> query = new LinkedHashMap<>();
        for (String part : uri.getRawQuery().split("&")) { String[] pair = part.split("=", 2); query.put(pair[0], pair[1]); }
        check(query.keySet().equals(new LinkedHashSet<>(Arrays.asList("aid", "app_name", "version_code", "version_name", "update_version_code",
            "manifest_version_code", "os", "ssmix", "channel", "device_platform", "device_type", "device_brand", "os_version", "os_api", "ac",
            "language", "host_abi", "iid", "cdid", "device_id", "_rticket"))), "upstream 7.4.5.32 query fields in order");
        check("8662".equals(query.get("aid")) && "novelread".equals(query.get("app_name")) && "74532".equals(query.get("version_code"))
            && "7.4.5.32".equals(query.get("version_name")) && "74532".equals(query.get("update_version_code")) && "74532".equals(query.get("manifest_version_code"))
            && "android".equals(query.get("os")) && "a".equals(query.get("ssmix")) && "update_64".equals(query.get("channel"))
            && "android".equals(query.get("device_platform")) && "25053RT47C".equals(query.get("device_type")) && "Redmi".equals(query.get("device_brand"))
            && "16".equals(query.get("os_version")) && "36".equals(query.get("os_api")) && "wifi".equals(query.get("ac")) && "zh".equals(query.get("language"))
            && "arm64-v8a".equals(query.get("host_abi")) && iid.equals(query.get("iid")) && cdid.equals(query.get("cdid")) && did.equals(query.get("device_id")),
            "upstream 7.4.5.32 profile values");
        check(!query.containsKey("ts"), "no ts parameter; the digest picks the ticket");
        long ticket = Long.parseLong(query.get("_rticket"));
        check(ts <= ticket / 1000, "ticket search stays within the signed second");
        check(Long.toString(ts).equals(request.headers.get("x-khronos")), "x-khronos is the signed second");
        check(PlaybackSignerV5.branchClass(uri.getRawQuery(), request.body, ts) == 12, "digest class 12");
        check(new ArrayList<>(request.headers.keySet()).equals(Arrays.asList("x-khronos", "x-helios", "x-medusa", "Content-Type", "x-ss-stub",
            "User-Agent", "Accept", "Content-Encoding")), "v5 three headers plus the body headers, nothing else");
        check("okhttp/4.12.0".equals(request.headers.get("User-Agent")) && "application/json".equals(request.headers.get("Accept"))
            && "application/json; charset=utf-8".equals(request.headers.get("Content-Type")) && "gzip".equals(request.headers.get("Content-Encoding")),
            "upstream's plain transport headers");
        String plain;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(request.body))) { plain = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        // Key order is the JSON library's (insertion order on Android); the signature binds the bytes actually sent.
        JSONObject payload = new JSONObject(plain), biz = payload.getJSONObject("biz_param");
        check(payload.length() == 2 && SID.equals(payload.getString("series_id")) && biz.length() == 7, "gzip body carries series_id and biz_param only");
        check("single_col".equals(biz.getString("caller_scene")) && biz.getInt("detail_page_version") == 0 && "".equals(biz.getString("from_video_id"))
            && !biz.getBoolean("need_all_video_definition") && !biz.getBoolean("need_mp4_align") && biz.getInt("source") == 4 && biz.getInt("video_id_type") == 1,
            "upstream's biz_param values");
        check(PlaybackSignerV5.hexText(DanmakuSigner.md5(request.body)).equalsIgnoreCase(request.headers.get("x-ss-stub")), "x-ss-stub binds the gzip bytes");
        check(request.headers.get("x-helios") != null && !request.headers.get("x-helios").isEmpty()
            && request.headers.get("x-medusa") != null && !request.headers.get("x-medusa").isEmpty(), "helios and medusa present");
        // The model transport only lets this one POST through, on the model host.
        long deadline = System.nanoTime() + 10_000_000_000L;
        new RegisteredPlaybackModel.ModelHttpTransport().validate(new OfficialApiClient.Request(request.url, "POST", request.headers, request.body, deadline));
        check(true, "model transport accepts the signed detail POST");
        for (String[] bad : new String[][]{{"GET", request.url}, {"POST", request.url.replace("sinfonlineb", "sinfonlinea")},
            {"POST", request.url.replace("video_detail", "video_model")}}) {
            try {
                new RegisteredPlaybackModel.ModelHttpTransport().validate(new OfficialApiClient.Request(bad[1], bad[0], request.headers, request.body, deadline));
                throw new AssertionError("transport accepted " + bad[0] + " " + bad[1].substring(0, 60));
            } catch (RegisteredPlaybackModel.ModelError expected) { check("INVALID_INPUT".equals(expected.code), "transport rejects other method host path or query"); }
        }
    }
    static void answers() throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        JSONObject plainList = AppEpisodeList.adopt(response(200, "", utf8(reply(row(1), row(2), row(3))), ""), SID, deadline);
        check(plainList.getJSONArray("episodes").length() == 3, "plain answer");
        JSONObject gzipList = AppEpisodeList.adopt(response(200, "gzip", gzip(utf8(reply(row(1), row(2), row(3)))), ""), SID, deadline);
        check(gzipList.getJSONArray("episodes").length() == 3, "gzip answer is decoded");
        check(AppEpisodeList.adopt(response(200, "identity", utf8(reply(row(1))), ""), SID, deadline).getJSONArray("episodes").length() == 1, "identity encoding");
        rejects("EMPTY_RESPONSE", () -> AppEpisodeList.adopt(response(200, "", new byte[0], ""), SID, deadline), "hollow answer");
        rejects("EMPTY_RESPONSE", () -> AppEpisodeList.adopt(response(200, "", utf8("  \n"), ""), SID, deadline), "blank answer");
        rejects("APP_SERVICE_BACKOFF", () -> AppEpisodeList.adopt(response(200, "", new byte[0], "5"), SID, deadline), "backoff");
        rejects("HTTP_ERROR", () -> AppEpisodeList.adopt(response(403, "", utf8(reply(row(1))), ""), SID, deadline), "HTTP refusal");
        rejects("INVALID_RESPONSE", () -> AppEpisodeList.adopt(response(200, "br", utf8(reply(row(1))), ""), SID, deadline), "unsupported encoding");
        rejects("INVALID_RESPONSE", () -> AppEpisodeList.adopt(response(200, "", utf8("<html>"), ""), SID, deadline), "non-JSON answer");
        rejects("INVALID_RESPONSE", () -> AppEpisodeList.adopt(response(200, "", new byte[RegisteredPlaybackModel.MAX_BYTES + 1], ""), SID, deadline), "oversized answer");
        rejects("INVALID_RESPONSE", () -> AppEpisodeList.adopt(null, SID, deadline), "missing answer");
        rejects("BUSINESS_REJECTED", () -> AppEpisodeList.adopt(response(200, "", utf8("{\"code\":1,\"message\":\"no\"}"), ""), SID, deadline), "business refusal");
        rejects("BUSINESS_REJECTED", () -> AppEpisodeList.adopt(response(200, "", utf8("{\"code\":0,\"error_code\":9}"), ""), SID, deadline), "error code refusal");
        rejects("BUSINESS_REJECTED", () -> AppEpisodeList.adopt(response(200, "", utf8("{}"), ""), SID, deadline), "reply without a code is no success");
        rejects("INVALID_RESPONSE", () -> AppEpisodeList.adopt(response(200, "", utf8(reply(row(1)).replaceFirst("\"series_id\":" + SID, "\"series_id\":100001")), ""), SID, deadline),
            "another series");
        rejects("INVALID_INPUT", () -> AppEpisodeList.load("../1", deadline), "input before registration");
        rejects("ETIMEDOUT", () -> AppEpisodeList.load(SID, System.nanoTime() - 1), "expired deadline before registration");
    }
    static void projection() throws Exception {
        JSONObject list = AppEpisodeList.project(OfficialApiClient.parse(utf8(reply(row(3), row(1), row(4), row(2)))), SID);
        JSONArray episodes = list.getJSONArray("episodes");
        check(episodes.length() == 4 && list.getInt("episodeCount") == 4, "every row is kept");
        for (int i = 0; i < 4; i++) {
            JSONObject episode = episodes.getJSONObject(i);
            check(VIDS[i].equals(episode.getString("vid")) && episode.getInt("index") == i + 1 && episode.getBoolean("locked") == (i == 3), "episode " + (i + 1) + " in official order with its lock");
        }
        check(SID.equals(list.getString("seriesId")) && "得了一种只能说真话的病".equals(list.getString("title")) && "简介".equals(list.getString("description"))
            && "全4集".equals(list.getString("episodeLabel")) && "app".equals(list.getString("source"))
            && "https://p3-reading-sign.fqnovelpic.com/novel-pic/x~tplv-noop.image".equals(list.getString("coverUrl")), "series projection");
        // Rows without a usable index keep their reply position; the declared count may not exceed the list.
        JSONObject unordered = OfficialApiClient.parse(utf8(reply("{\"vid\":" + VIDS[1] + "}", "{\"vid\":" + VIDS[0] + ",\"vid_index\":0}")));
        JSONArray kept = AppEpisodeList.project(unordered, SID).getJSONArray("episodes");
        check(VIDS[1].equals(kept.getJSONObject(0).getString("vid")) && VIDS[0].equals(kept.getJSONObject(1).getString("vid")), "reply order without indexes");
        JSONObject larger = OfficialApiClient.parse(utf8(reply(row(1), row(2))));
        larger.getJSONObject("data").getJSONObject("video_data").put("episode_cnt", 71);
        rejects("INVALID_RESPONSE", () -> AppEpisodeList.project(larger, SID), "list shorter than declared");
        rejects("INVALID_RESPONSE", () -> AppEpisodeList.project(OfficialApiClient.parse(utf8(reply(row(1), row(1)))), SID), "duplicate episode");
        rejects("INVALID_RESPONSE", () -> AppEpisodeList.project(OfficialApiClient.parse(utf8(reply(row(1, "vid=v1_5DC8VBWf14_jqPcnhiSPfJ6fiGI")))), SID), "token in the App list");
        rejects("INVALID_RESPONSE", () -> AppEpisodeList.project(OfficialApiClient.parse(utf8(reply(row(1, "series_id=100001")))), SID), "foreign row series");
        JSONObject bare = OfficialApiClient.parse(utf8(reply("{\"vid\":" + VIDS[0] + "}", "{\"vid\":" + VIDS[1] + ",\"vid_index\":1}")));
        JSONArray bareList = AppEpisodeList.project(bare, SID).getJSONArray("episodes");
        check(bareList.length() == 2 && VIDS[0].equals(bareList.getJSONObject(0).getString("vid")) && VIDS[1].equals(bareList.getJSONObject(1).getString("vid")),
            "row index and row series id are optional");
        rejects("INVALID_RESPONSE", () -> AppEpisodeList.project(OfficialApiClient.parse(utf8(reply())), SID), "empty list");
        rejects("INVALID_RESPONSE", () -> AppEpisodeList.project(null, SID), "missing reply");
        JSONObject titled = OfficialApiClient.parse(utf8(reply(row(1)).replace("\"series_title\":\"得了一种只能说真话的病\"", "\"series_title\":\" \"")
            .replace("https://p3-reading-sign.fqnovelpic.com/novel-pic/x~tplv-noop.image", "http://127.0.0.1/cover")));
        JSONObject untitled = AppEpisodeList.project(titled, SID);
        check("未命名短剧".equals(untitled.getString("title")) && untitled.getString("coverUrl").isEmpty(), "blank title and non-public cover");
    }
    /** Scripted identity and wire: what reaches the service, and what is retried. */
    static void loading() throws Exception {
        java.util.Deque<String> script = new ArrayDeque<>();
        java.util.List<Long> deadlines = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();
        AppEpisodeList.Transport wire = request -> {
            sends.incrementAndGet();
            deadlines.add(request.deadlineNanos);
            check("POST".equals(request.method) && "/novel/player/video_detail/v1/".equals(new URI(request.url).getPath())
                && "okhttp/4.12.0".equals(request.headers.get("User-Agent")) && request.headers.containsKey("x-medusa")
                && request.headers.containsKey("x-ss-stub") && request.body.length > 0 && request.body[0] == 0x1f, "one signed gzip detail POST per attempt");
            String step = script.poll();
            if ("ssl".equals(step)) throw new javax.net.ssl.SSLHandshakeException("Remote host terminated the handshake");
            if ("reset".equals(step)) throw new java.io.IOException("Connection reset");
            if ("stall".equals(step)) throw new java.net.SocketTimeoutException("timeout");
            if ("refuse".equals(step)) return response(403, "", utf8(""), "");
            if ("hollow".equals(step)) return response(200, "", new byte[0], "");
            if ("deny".equals(step)) return response(200, "", utf8("{\"code\":1}"), "");
            return response(200, "gzip", gzip(utf8(reply(row(1), row(2)))), "");
        };
        AppEpisodeList.Identity registered = () -> new String[]{ "9007199254740993", "9007199254741993", "" };
        AppEpisodeList.seams(registered, wire);
        long budget = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        long started = System.nanoTime();
        check(AppEpisodeList.load(SID, budget).getJSONArray("episodes").length() == 2 && sends.get() == 1, "one exchange serves the list");
        // The first node gets 4.5s of the 9s budget, so a retry on another node still has room
        // (signing and the scripted registration happen before the first deadline is taken).
        check(deadlines.get(0) - started <= java.util.concurrent.TimeUnit.SECONDS.toNanos(5), "the first attempt is capped at 4.5s");
        for (String step : new String[]{ "ssl", "reset", "stall" }) {
            script.add(step); int before = sends.get(); started = System.nanoTime();
            check(AppEpisodeList.load(SID, budget).getJSONArray("episodes").length() == 2 && sends.get() == before + 2, "a dropped " + step + " is retried once");
            check(deadlines.get(before) - started <= java.util.concurrent.TimeUnit.SECONDS.toNanos(5) && deadlines.get(before + 1) - started > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(4500)
                && deadlines.get(before + 1) - started <= java.util.concurrent.TimeUnit.SECONDS.toNanos(10), "the retry alone may use the rest of the budget");
        }
        script.add("ssl"); script.add("ssl");
        rejects("ERR_NETWORK", () -> AppEpisodeList.load(SID, budget), "two dropped handshakes");
        check(sends.get() == 9, "no third attempt");
        script.add("stall"); script.add("stall");
        rejects("ETIMEDOUT", () -> AppEpisodeList.load(SID, budget), "two stalled nodes");
        script.add("ssl"); script.add("reset");
        rejects("ERR_NETWORK", () -> AppEpisodeList.load(SID, budget), "dropped handshake then reset");
        check(sends.get() == 13, "each failure path stops at two attempts");
        for (String step : new String[]{ "refuse", "hollow", "deny" }) {
            script.add(step); int before = sends.get();
            rejects("refuse".equals(step) ? "HTTP_ERROR" : "hollow".equals(step) ? "EMPTY_RESPONSE" : "BUSINESS_REJECTED", () -> AppEpisodeList.load(SID, budget), step + " is final");
            check(sends.get() == before + 1, "an answered request is never repeated: " + step);
        }
        // The retry only runs while budget remains.
        script.add("ssl");
        check(AppEpisodeList.load(SID, System.nanoTime() + 2_000_000_000L).getJSONArray("episodes").length() == 2, "a retry fits a two second budget");
        script.add("ssl");
        rejects("ERR_NETWORK", () -> AppEpisodeList.load(SID, System.nanoTime() + 1_000_000_000L), "no retry without budget");
        // Registration failures and cancellation never reach the wire.
        int seen = sends.get();
        AppEpisodeList.seams(() -> { throw new java.io.IOException("registration down"); }, wire);
        rejects("DEVICE_REGISTRATION_FAILED", () -> AppEpisodeList.load(SID, budget), "registration failure");
        AppEpisodeList.seams(() -> new String[]{ "0", "1" }, wire);
        rejects("DEVICE_REGISTRATION_FAILED", () -> AppEpisodeList.load(SID, budget), "invalid registered identity");
        AppEpisodeList.seams(registered, wire);
        Thread.currentThread().interrupt();
        try { AppEpisodeList.load(SID, budget); throw new AssertionError("interrupted load accepted"); }
        catch (java.io.InterruptedIOException expected) { check(sends.get() == seen, "an interrupted load sends nothing"); }
        finally { Thread.interrupted(); }
        AppEpisodeList.seams(RegisteredPlaybackModel::cachedBootstrap, AppEpisodeList::modelTransport);
    }
    public static void main(String[] unused) throws Exception {
        shape();
        answers();
        projection();
        loading();
        System.out.println("APP_EPISODE_LIST_OK checks=" + checks + " networkRequests=0");
    }
}
