package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.json.*;

/**
 * Complete numeric episode list from the signed App series detail (jackotom 8.5, issue #217).
 * Since 2026-11-27 the official episode page lists only the leading episodes, under obfuscated
 * "v1_" tokens, and answers the numeric route with 404; numeric episode ids therefore come from
 * video_detail/v1, signed like the playback ladder on the same registered identity.
 * Ported from upstream app-source.js fetchAppEpisodeList.
 */
final class AppEpisodeList {
    static final int MAX_EPISODES = 2000;
    /** Registration aside, the list request gets this budget; the transport caps one exchange at 7s. */
    static final long MAX_NANOS = TimeUnit.SECONDS.toNanos(9);
    static final class ListError extends IOException {
        final String code;
        ListError(String message, String code) { super(message); this.code = code; }
    }
    private AppEpisodeList() { }
    private static ListError error(String code, String message) { return new ListError(message, code); }

    static final class Request {
        final String url; final Map<String, String> headers; final byte[] body;
        Request(String url, Map<String, String> headers, byte[] body) { this.url = url; this.headers = headers; this.body = body; }
    }

    /** The 7.4.5.32 profile upstream signs video_detail/v1 with; field 21 = 312 like its preload request. */
    static Request request(String did, String iid, String cdid, String seriesId, long now) throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        String[][] fields = {{"aid", "8662"}, {"app_name", "novelread"}, {"version_code", "74532"}, {"version_name", "7.4.5.32"},
            {"update_version_code", "74532"}, {"manifest_version_code", "74532"}, {"os", "android"}, {"ssmix", "a"}, {"channel", "update_64"},
            {"device_platform", "android"}, {"device_type", "25053RT47C"}, {"device_brand", "Redmi"}, {"os_version", "16"}, {"os_api", "36"},
            {"ac", "wifi"}, {"language", "zh"}, {"host_abi", "arm64-v8a"}, {"iid", iid}, {"cdid", cdid}, {"device_id", did}};
        for (String[] row : fields) query.put(row[0], row[1]);
        JSONObject payload = new JSONObject().put("series_id", seriesId).put("biz_param", new JSONObject()
            .put("caller_scene", "single_col").put("detail_page_version", 0).put("from_video_id", "")
            .put("need_all_video_definition", false).put("need_mp4_align", false).put("source", 4).put("video_id_type", 1));
        byte[] body = gzip(payload.toString().getBytes(StandardCharsets.UTF_8));
        PlaybackSignerV5.Request signed = PlaybackSignerV5.signPlayback(PlaybackSignerV5.VIDEO_DETAIL_API, query, body, now, PlaybackSignerV5.DETAIL_FIELD21);
        Map<String, String> headers = new LinkedHashMap<>(signed.headers);
        headers.put("User-Agent", "okhttp/4.12.0"); headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json; charset=utf-8"); headers.put("Content-Encoding", "gzip");
        return new Request(signed.url, headers, signed.body);
    }

    /** {device_id, install_id, cdid} of the playback ladder; the detail endpoint grades identities too. */
    interface Identity { String[] get() throws Exception; }
    /** One signed exchange; production runs the model transport, whose node rotates per attempt. */
    interface Transport { OfficialApiClient.Response send(OfficialApiClient.Request request) throws Exception; }
    private static volatile Identity identity = RegisteredPlaybackModel::cachedBootstrap;
    private static volatile Transport transport = AppEpisodeList::modelTransport;
    private static final long RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(1500), FIRST_ATTEMPT_NANOS = TimeUnit.MILLISECONDS.toNanos(4500);
    static OfficialApiClient.Response modelTransport(OfficialApiClient.Request request) throws Exception {
        try (RegisteredPlaybackModel.ModelHttpTransport transport = new RegisteredPlaybackModel.ModelHttpTransport()) { return transport.execute(request); }
    }
    /** Test seams; production keeps the registered identity and the model transport. */
    static void seams(Identity identitySource, Transport wire) { identity = identitySource; transport = wire; }

    /** {seriesId,title,coverUrl,description,episodeCount,episodeLabel,episodes:[{vid,index,locked}],source:"app"} */
    static JSONObject load(String seriesId, long deadlineNanos) throws Exception {
        if (seriesId == null || !seriesId.matches("[0-9]{1,30}")) throw error("INVALID_INPUT", "剧集编号必须是 1 至 30 位数字字符串");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("分集列表请求已取消");
        if (deadlineNanos <= System.nanoTime()) throw error("ETIMEDOUT", "分集列表读取超时，请重试");
        // Registration keeps its own transport timeouts; only the list request spends the 9s budget.
        String[] registered;
        try { registered = identity.get(); }
        catch (Exception failure) {
            if (Thread.currentThread().isInterrupted() || failure instanceof InterruptedIOException && !(failure instanceof SocketTimeoutException)) throw failure;
            throw error("DEVICE_REGISTRATION_FAILED", "设备登记未完成，请稍后重试");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("分集列表请求已取消");
        if (registered.length < 2 || !OfficialApiClient.validID(registered[0]) || !OfficialApiClient.validID(registered[1]))
            throw error("DEVICE_REGISTRATION_FAILED", "设备登记未返回有效身份");
        long deadline = Math.min(deadlineNanos, System.nanoTime() + MAX_NANOS);
        if (deadline <= System.nanoTime()) throw error("ETIMEDOUT", "分集列表读取超时，请重试");
        String cdid = registered.length > 2 && registered[2] != null && !registered[2].isEmpty() ? registered[2] : PlaybackSignerV5.newCdid();
        Request request = request(registered[0], registered[1], cdid, seriesId, System.currentTimeMillis());
        // A healthy node answers in well under a second: a stalled first node must not spend the
        // whole budget and leave the retry on another node no time.
        OfficialApiClient.Request first = new OfficialApiClient.Request(request.url, "POST", request.headers, request.body,
            Math.min(deadline, System.nanoTime() + FIRST_ATTEMPT_NANOS));
        OfficialApiClient.Request retry = new OfficialApiClient.Request(request.url, "POST", request.headers, request.body, deadline);
        for (int attempt = 0; ; attempt++) {
            try { return adopt(transport.send(attempt == 0 ? first : retry), seriesId, deadline); }
            catch (ListError | JSONException refused) { throw refused; }
            catch (InterruptedIOException cancelled) {
                // A stalled node times out as SocketTimeoutException; anything else is cancellation.
                if (!(cancelled instanceof SocketTimeoutException)) throw cancelled;
                if (attempt > 0 || deadline - System.nanoTime() < RETRY_NANOS) throw error("ETIMEDOUT", "分集列表读取超时，请重试");
            } catch (java.io.IOException unreachable) {
                // One CDN node may drop the handshake or reset the connection; the next attempt
                // lands on another node. The request never reached the service, so nothing is spent.
                if (attempt > 0 || deadline - System.nanoTime() < RETRY_NANOS) throw error("ERR_NETWORK", "分集服务连接未完成，请稍后重试");
            }
        }
    }

    /** Wire answer to the projected list; empty bodies and refusals stay typed for the caller. */
    static JSONObject adopt(OfficialApiClient.Response response, String seriesId, long deadline) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("分集列表请求已取消");
        if (response == null || response.body.length > RegisteredPlaybackModel.MAX_BYTES) throw error("INVALID_RESPONSE", "分集接口响应超过大小限制");
        if (response.status != 200) throw error("HTTP_ERROR", "分集服务返回 HTTP " + response.status);
        byte[] data = response.body;
        String encoding = response.header("Content-Encoding");
        if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("gzip") && !encoding.equalsIgnoreCase("identity"))
            throw error("INVALID_RESPONSE", "分集接口响应编码无效");
        // The transport asks for gzip itself, so OkHttp hands the compressed bytes over untouched.
        if (encoding.equalsIgnoreCase("gzip") && data.length > 0) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) { data = read(in, deadline); }
        }
        if (new String(data, StandardCharsets.UTF_8).trim().isEmpty()) {
            if (!response.header("Retry-After").trim().isEmpty()) throw error("APP_SERVICE_BACKOFF", "分集服务繁忙，请稍后重试");
            throw error("EMPTY_RESPONSE", "分集接口未返回数据，请稍后重试");
        }
        JSONObject envelope;
        try { envelope = OfficialApiClient.parse(data); }
        catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (Exception invalid) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("分集列表请求已取消");
            throw error("INVALID_RESPONSE", "分集接口响应格式无效");
        }
        return project(envelope, seriesId);
    }

    /** Pure projection of the detail reply; large integer identities stay decimal strings. */
    static JSONObject project(JSONObject envelope, String seriesId) throws Exception {
        if (envelope == null) throw error("INVALID_RESPONSE", "分集接口响应格式无效");
        if (!zero(envelope.opt("code")) || envelope.has("error_code") && !zero(envelope.opt("error_code")))
            throw error("BUSINESS_REJECTED", "分集接口暂未提供这部剧的分集，请稍后重试");
        JSONObject data = envelope.optJSONObject("data"), video = data == null ? null : data.optJSONObject("video_data");
        if (video == null || !seriesId.equals(identifier(video.opt("series_id"))))
            throw error("INVALID_RESPONSE", "分集接口返回的剧集与请求不一致");
        JSONArray rows = video.optJSONArray("video_list");
        if (rows == null || rows.length() == 0 || rows.length() > MAX_EPISODES) throw error("INVALID_RESPONSE", "分集接口未返回有效分集");
        List<JSONObject> episodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            String vid = row == null ? "" : identifier(row.opt("vid"));
            // A per-row series id is optional; a foreign one is a mismatched reply.
            if (!vid.matches("[0-9]{1,30}") || row.has("series_id") && !row.isNull("series_id") && !seriesId.equals(identifier(row.opt("series_id"))))
                throw error("INVALID_RESPONSE", "分集接口返回的分集数据异常");
            if (!seen.add(vid)) throw error("INVALID_RESPONSE", "分集接口返回了重复分集");
            long index = integer(row.opt("vid_index"));
            episodes.add(new JSONObject().put("vid", vid).put("index", index > 0 ? index : i + 1).put("locked", Boolean.TRUE.equals(row.opt("disable_play"))));
        }
        // Official episode order; rows without a usable index keep their reply position.
        episodes.sort(Comparator.<JSONObject>comparingLong(episode -> episode.optLong("index", 0)));
        if (integer(video.opt("episode_cnt")) > episodes.size()) throw error("INVALID_RESPONSE", "分集接口返回的分集列表不完整");
        JSONArray list = new JSONArray();
        for (JSONObject episode : episodes) list.put(episode);
        String title = text(video.opt("series_title"), 200);
        return new JSONObject().put("seriesId", seriesId).put("title", title.isEmpty() ? "未命名短剧" : title)
            .put("coverUrl", EpisodeResolver.publicHttpsUrl(text(video.opt("series_cover"), 4096)))
            .put("description", text(video.opt("series_intro"), 8000)).put("episodeCount", episodes.size())
            .put("episodeLabel", text(video.opt("episode_right_text"), 80)).put("episodes", list).put("source", "app");
    }

    private static boolean zero(Object value) {
        return value instanceof Number && Double.isFinite(((Number) value).doubleValue()) && ((Number) value).doubleValue() == 0 || value instanceof String && value.equals("0");
    }
    /** PassportJson keeps integers beyond 2^53 as exact decimal strings and smaller ones as Long. */
    private static String identifier(Object value) {
        if (value instanceof String) return ((String) value).trim();
        return value instanceof Number ? Long.toString(((Number) value).longValue()) : "";
    }
    private static long integer(Object value) {
        long number = 0;
        if (value instanceof Number) number = ((Number) value).longValue();
        else if (value instanceof String && ((String) value).matches("[0-9]{1,18}")) number = Long.parseLong((String) value);
        return number > 0 && number <= Integer.MAX_VALUE ? number : 0;
    }
    private static String text(Object value, int max) {
        if (!(value instanceof String)) return "";
        String text = ((String) value).trim();
        return text.length() <= max && !text.matches("(?s).*[\\x00-\\x1f\\x7f].*") ? text : "";
    }
    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(plain); }
        return out.toByteArray();
    }
    private static byte[] read(InputStream input, long deadline) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        for (;;) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("分集列表请求已取消");
            if (System.nanoTime() >= deadline) throw new SocketTimeoutException("分集列表读取超时");
            count = input.read(buffer); if (count < 0) break;
            if (count > 0) {
                if (output.size() + count > RegisteredPlaybackModel.MAX_BYTES) throw error("INVALID_RESPONSE", "分集接口响应超过大小限制");
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }
}
