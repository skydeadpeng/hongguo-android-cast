package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/**
 * 黄果漫剧（huangguoai.com 官方站）只读客户端：分类、搜索、详情与取流。
 * 全站分集直发完整签名 m3u8（AES-128），无 VIP 门禁；取流校验实际清单时长
 * 防止源站只下发预览片段。剧集在本机以 99 前缀数字编码与红果 ID 共存。
 */
final class HuangguoApi {
    /** 官方主域 + 自建 Worker 代理 + 社区镜像：部分运营商网络对主域 RST，失败按序切换并粘住可用域。
     *  粘住 Worker 代理域时，封面与播放流同步改走代理（/img、/m3u8），不依赖内容 CDN 直连。 */
    private static final String[] ORIGINS = {
        "https://huangguoai.com", "https://huoguo.skydeadpeng.xyz",
        "https://l5f9m.rxzfszht.cc", "https://blolhh.rxzfszht.cc", "https://mxz58.rxzfszht.cc"
    };
    /** 每域快速失败：丢包型阻断不等满长超时，尽快切到可达线路。 */
    private static final long PER_ORIGIN_MS = 6_000;
    private static volatile int originIndex = 0;
    private static volatile android.content.Context appContext;
    /** 首次桥调用注入应用上下文；上次成功的线路持久保存，冷启动直接使用，不再重复试错。 */
    static void attach(android.content.Context context) {
        if (appContext != null || context == null) return;
        appContext = context.getApplicationContext();
        try {
            int saved = appContext.getSharedPreferences("huangguo", android.content.Context.MODE_PRIVATE).getInt("origin", 0);
            if (saved >= 0 && saved < ORIGINS.length) originIndex = saved;
        } catch (Exception ignored) { }
    }
    private static void stickOrigin(int index) {
        originIndex = index;
        try {
            if (appContext != null)
                appContext.getSharedPreferences("huangguo", android.content.Context.MODE_PRIVATE).edit().putInt("origin", index).apply();
        } catch (Exception ignored) { }
    }
    private static final Pattern CATEGORY = Pattern.compile("ai-manju|ai-duanju");

    private HuangguoApi() { }

    /** 黄果剧进入本机体系的编码：99 + 八位剧号，长度固定 10，避免与红果真实编号冲突。 */
    static String encodeSeries(long id) { return String.format(Locale.ROOT, "99%08d", id); }
    static String encodeEpisode(long id, int ep) { return encodeSeries(id) + String.format(Locale.ROOT, "%04d", ep); }
    static long decodeSeries(String seriesId) throws JSONException {
        if (seriesId == null || !seriesId.matches("99[0-9]{8}")) throw new JSONException("黄果剧集编号无效");
        return Long.parseLong(seriesId.substring(2));
    }
    static int decodeEpisodeIndex(String vid) throws JSONException {
        if (vid == null || !vid.matches("99[0-9]{12}")) throw new JSONException("黄果分集编号无效");
        return Integer.parseInt(vid.substring(10));
    }

    private static JSONObject get(String path) throws Exception {
        // 域名全部固定；路径由本类内部构成，绝无用户可控主机。连接失败按序换域并粘住成功域。
        Exception last = null;
        for (int attempt = 0; attempt < ORIGINS.length; attempt++) {
            String origin = ORIGINS[(originIndex + attempt) % ORIGINS.length];
            try {
                JSONObject data = getFrom(origin, path);
                stickOrigin((originIndex + attempt) % ORIGINS.length);
                return data;
            } catch (IOException error) { last = error; }
        }
        throw last != null ? last : new IOException("黄果来源暂不可用，请稍后重试");
    }
    private static JSONObject getFrom(String origin, String path) throws Exception {
        NetworkGuard.Reply reply = NetworkGuard.publicGet(origin + path, System.nanoTime() + PER_ORIGIN_MS * 1_000_000L);
        if (reply.status != 200) throw new IOException("黄果来源暂不可用，请稍后重试");
        JSONObject body;
        try { body = new JSONObject(reply.body); } catch (JSONException ignored) { throw new IOException("黄果返回数据无效"); }
        if (body.optInt("status", 0) != 1 || !(body.opt("data") instanceof JSONObject)) {
            String message = body.optString("msg", "");
            throw new IOException(message.isEmpty() ? "黄果来源暂不可用，请稍后重试" : message);
        }
        return body.getJSONObject("data");
    }

    private static String text(JSONObject input, String key, int limit) {
        String value = input.optString(key, "").replaceAll("<[^>]*>", "").trim();
        return value.length() > limit ? value.substring(0, limit) : value;
    }
    private static boolean viaProxy() { return ORIGINS[originIndex].contains("skydeadpeng.xyz"); }
    private static String encode(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); } catch (java.io.UnsupportedEncodingException ignored) { return value; }
    }
    private static String wrapCover(String url) {
        return viaProxy() && url.startsWith("https://") ? ORIGINS[originIndex] + "/img?url=" + encode(url) : url;
    }
    private static String wrapStream(String url) {
        return viaProxy() ? ORIGINS[originIndex] + "/m3u8?url=" + encode(url) : url;
    }

    private static JSONObject item(JSONObject row) throws JSONException {
        long id = row.optLong("id", 0);
        String title = text(row, "title", 200);
        if (id <= 0 || title.isEmpty()) return null;
        JSONArray tags = new JSONArray();
        JSONArray raw = row.optJSONArray("tags");
        if (raw != null) for (int i = 0; i < raw.length() && tags.length() < 4; i++) {
            Object tag = raw.opt(i);
            String label = tag instanceof JSONObject ? text((JSONObject) tag, "name", 80) : (tag instanceof String ? ((String) tag).trim() : "");
            if (!label.isEmpty() && label.length() <= 80) tags.put(label);
        }
        return new JSONObject().put("seriesId", encodeSeries(id)).put("title", title)
            .put("coverUrl", wrapCover(row.optString("cover", ""))).put("episodeCount", Math.max(0, row.optInt("episode_count")))
            .put("episodeLabel", "").put("tags", tags);
    }

    private static JSONObject pageData(JSONObject data, int page) throws IOException, JSONException {
        JSONArray rows = data.optJSONArray("items");
        if (rows == null) throw new IOException("黄果返回数据无效");
        JSONArray items = new JSONArray();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length() && items.length() < 100; i++) {
            JSONObject clean = rows.optJSONObject(i) == null ? null : item(rows.getJSONObject(i));
            if (clean == null || !seen.add(clean.getString("seriesId"))) continue;
            items.put(clean);
        }
        JSONObject pagination = data.optJSONObject("pagination");
        int pages = pagination == null ? 0 : pagination.optInt("pages", 0);
        return new JSONObject().put("items", items).put("page", page)
            .put("hasMore", pages > page && items.length() > 0);
    }

    static JSONObject feed(String category, int page) throws Exception {
        if (!CATEGORY.matcher(category).matches()) throw new JSONException("黄果分类无效");
        if (page < 1 || page > 100000) throw new JSONException("黄果页码无效");
        return pageData(get("/api/videos/category/" + category + "?page=" + page + "&size=30&sort=latest"), page)
            .put("category", category);
    }

    static JSONObject search(String keyword, int page) throws Exception {
        String query = keyword == null ? "" : keyword.replaceAll("<[^>]*>", "").trim();
        if (query.isEmpty()) throw new JSONException("请输入搜索关键词");
        if (query.length() > 100) throw new JSONException("搜索词过长，请精简后重试");
        if (page < 1 || page > 1000) throw new JSONException("黄果页码无效");
        for (char c : query.toCharArray()) if (c < 0x20 || c == 0x7f) throw new JSONException("搜索词包含无效字符");
        String encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20");
        return pageData(get("/api/search?q=" + encoded + "&page=" + page + "&size=30"), page);
    }

    static JSONObject detail(String seriesId) throws Exception {
        long id = decodeSeries(seriesId);
        JSONObject data = get("/api/videos/" + id);
        if (data.optLong("id", 0) != id) throw new IOException("黄果返回的剧集与请求不一致");
        JSONArray rows = data.optJSONArray("episodes");
        if (rows == null) throw new IOException("黄果分集列表无效");
        JSONArray episodes = new JSONArray();
        for (int i = 0; i < rows.length() && episodes.length() < 1000; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            int ep = row.optInt("ep_num", 0);
            if (ep < 1 || ep > 9999) continue;
            episodes.put(new JSONObject().put("vid", encodeEpisode(id, ep)).put("index", ep)
                .put("label", "第" + ep + "集").put("locked", row.optInt("status", 1) != 1));
        }
        if (episodes.length() == 0) throw new IOException("黄果分集列表为空");
        return new JSONObject().put("seriesId", seriesId).put("title", text(data, "title", 200))
            .put("coverUrl", wrapCover(data.optString("cover", ""))).put("description", text(data, "description", 8000))
            .put("episodeCount", episodes.length()).put("episodes", episodes).put("source", "huangguo");
    }

    /**
     * 取单集播放地址。官方对个别内容只下发预览片段：清单实际时长不足声明八成
     * 即拒绝下发，避免把预览当正片播放。
     */
    static String episodeUrl(String seriesId, int ep) throws Exception {
        long id = decodeSeries(seriesId);
        if (ep < 1 || ep > 9999) throw new JSONException("黄果分集编号无效");
        JSONObject data = get("/api/videos/" + id + "/play?ep=" + ep);
        String url = data.optString("video_url", "");
        if (url.isEmpty() || !url.startsWith("https://")) throw new IOException("黄果未提供有效播放地址");
        // 走 Worker 代理时播放流与时长校验同样经代理，不依赖内容 CDN 直连。
        String played = wrapStream(url);
        double declared = data.optDouble("duration", 0);
        if (declared > 0 && url.toLowerCase(Locale.ROOT).contains(".m3u8")) {
            double actual = playlistSeconds(played);
            if (actual > 0 && actual < declared * 0.8)
                throw new IOException("该集只下发了预览片段（约 " + (int) actual + " 秒），暂无法完整播放");
        }
        return played;
    }

    private static double playlistSeconds(String url) {
        try {
            NetworkGuard.Reply reply = NetworkGuard.publicGet(url, System.nanoTime() + 12_000_000_000L);
            if (reply.status != 200 || !reply.body.startsWith("#EXTM3U") || reply.body.contains("#EXT-X-STREAM-INF")) return 0;
            Matcher inf = Pattern.compile("#EXTINF:([0-9.]+)").matcher(reply.body);
            double total = 0; int segments = 0;
            while (inf.find() && segments++ < 20000) total += Double.parseDouble(inf.group(1));
            return total;
        } catch (Exception ignored) { return 0; }
    }
}
