package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Official public-site catalog/detail/search parsing and bounded fetching for the native UI. */
final class SiteCatalog {
    static final String ORIGIN = "https://hongguoduanju.com";
    static final String[] CATEGORIES = { "real-drama", "comic-drama", "ai-drama" };
    private SiteCatalog() { }
    static String categoryLabel(String id) {
        return id.equals("real-drama") ? "真人剧" : id.equals("comic-drama") ? "漫剧" : id.equals("ai-drama") ? "AI剧" : "分类";
    }
    private static String text(Object value, int max) {
        if (!(value instanceof String)) return "";
        String value_ = ((String) value).trim();
        return value_.length() <= max && !value_.matches("(?s).*[\\x00-\\x1f\\x7f].*") ? value_ : "";
    }
    private static int count(Object value) {
        if (!(value instanceof Number)) return 0;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number == Math.rint(number) && number >= 0 && number <= 10000 ? (int) number : 0;
    }
    /** LibraryStore-compatible series projection; the search shape nests under video_data. */
    private static JSONObject series(JSONObject item, boolean search) throws JSONException {
        if (item == null) return null;
        String id = text(item.opt("series_id"), 30);
        String title = text(item.opt(search ? "series_title" : "series_name"), 200);
        if (!id.matches("[0-9]{1,30}") || title.isEmpty()) return null;
        JSONArray rawTags = item.optJSONArray(search ? "category_list" : "tags");
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (rawTags != null) for (int i = 0; i < rawTags.length() && tags.size() < 8; i++) {
            Object raw = rawTags.opt(i);
            String tag = raw instanceof JSONObject ? text(((JSONObject) raw).opt("name"), 80) : text(raw, 80);
            if (!tag.isEmpty()) tags.add(tag);
        }
        return new JSONObject().put("seriesId", id).put("title", title)
            .put("coverUrl", EpisodeResolver.publicHttpsUrl(text(item.opt("series_cover"), 4096)))
            .put("description", text(item.opt("series_intro"), 8000))
            .put("episodeCount", count(item.opt("episode_cnt")))
            .put("episodeLabel", text(item.opt("episode_right_text"), 80))
            .put("tags", new JSONArray(new ArrayList<>(tags)));
    }
    static JSONObject parseCatalog(String html, String category, int page) throws Exception {
        if (!Arrays.asList(CATEGORIES).contains(category) || page < 1) throw new IOException("目录分类或页码无效");
        JSONObject loaders = EpisodeResolver.extractRouterData(html).optJSONObject("loaderData");
        JSONObject data = null;
        if (loaders != null) for (Iterator<String> i = loaders.keys(); i.hasNext(); ) {
            JSONObject candidate = loaders.optJSONObject(i.next());
            JSONObject route = candidate == null ? null : candidate.optJSONObject("categoryRoute");
            if (route != null && category.equals(route.opt("contentType"))) { data = candidate; break; }
        }
        JSONArray recommend = data == null ? null : data.optJSONArray("recommendList");
        if (data == null || Boolean.FALSE.equals(data.opt("isSuccess")) || recommend == null) throw new IOException("来源目录暂不可用");
        JSONArray items = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < recommend.length(); i++) {
            JSONObject row = series(recommend.optJSONObject(i), false);
            if (row != null && seen.add(row.getString("seriesId"))) items.put(row);
        }
        JSONObject pagination = data.optJSONObject("pagination");
        int pageNum = pagination == null ? page : count(pagination.opt("pageNum")) == 0 ? page : count(pagination.opt("pageNum"));
        return new JSONObject().put("items", items).put("page", pageNum)
            .put("totalPages", pagination == null ? 0 : count(pagination.opt("totalPages")))
            .put("total", pagination == null ? items.length() : count(pagination.opt("total")));
    }
    static JSONObject parseSearch(String html) throws Exception {
        JSONObject loaders = EpisodeResolver.extractRouterData(html).optJSONObject("loaderData");
        JSONObject data = loaders == null ? null : loaders.optJSONObject("search_(keyword)/page");
        JSONArray list = data == null ? null : data.optJSONArray("searchList");
        if (data == null || Boolean.FALSE.equals(data.opt("isSuccess")) || list == null) throw new IOException("来源搜索暂不可用");
        JSONArray items = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject video = list.optJSONObject(i) == null ? null : list.optJSONObject(i).optJSONObject("video_data");
            JSONObject row = video == null ? null : series(video, true);
            if (row != null && seen.add(row.getString("seriesId"))) items.put(row);
        }
        return new JSONObject().put("items", items);
    }
    static JSONObject parseDetail(String html, String seriesId) throws Exception {
        if (seriesId == null || !seriesId.matches("[0-9]{1,30}")) throw new IOException("剧集编号必须是 1 至 30 位数字字符串");
        JSONObject loaders = EpisodeResolver.extractRouterData(html).optJSONObject("loaderData");
        JSONObject data = loaders == null ? null : first(
            loaders.optJSONObject("player_(series_id)/page"), loaders.optJSONObject("player_(series_id)/(vid)/page"), loaders.optJSONObject("detail_page"));
        JSONObject detail = data == null ? null : data.optJSONObject("seriesDetail");
        if (data == null || Boolean.FALSE.equals(data.opt("isSuccess")) || detail == null
            || !seriesId.equals(text(detail.opt("series_id"), 30)) || detail.has("series_id") && !seriesId.equals(String.valueOf(detail.get("series_id"))))
            throw new IOException("剧集详情与请求编号不一致");
        JSONObject item = series(detail, false);
        JSONArray vids = detail.optJSONArray("vid_list");
        if (item == null || vids == null) throw new IOException("来源页面缺少剧集详情或分集列表");
        int accessible = count(detail.opt("accessible_episode_cnt"));
        JSONArray episodes = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < vids.length(); i++) {
            String vid = text(vids.opt(i), 30);
            if (!vid.matches("[0-9]{1,30}") || !seen.add(vid)) continue;
            boolean available = seen.size() <= accessible;
            episodes.put(new JSONObject().put("vid", vid).put("index", seen.size()).put("webAvailable", available).put("availability", available ? "available" : "unknown"));
        }
        if (episodes.length() == 0) throw new IOException("来源未返回有效分集");
        int available = 0;
        for (int i = 0; i < episodes.length(); i++) if (episodes.getJSONObject(i).getBoolean("webAvailable")) available++;
        return item.put("episodes", episodes).put("accessibleEpisodeCount", available);
    }
    private static JSONObject first(Object... values) { for (Object value : values) if (value instanceof JSONObject) return (JSONObject) value; return null; }
    // ---- bounded fetching through the shared page transport ----
    static JSONObject loadCatalog(String category, int page) throws Exception {
        NetworkGuard.Reply reply = NetworkGuard.get(ORIGIN + "/category/" + category + (page > 1 ? "?page=" + page : ""));
        requireOk(reply);
        return parseCatalog(reply.body, category, page);
    }
    static JSONObject loadDetail(String seriesId) throws Exception {
        Exception failure = null;
        for (String path : new String[]{ "/player/" + seriesId, "/detail?series_id=" + seriesId }) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("分集加载已取消");
            try {
                NetworkGuard.Reply reply = NetworkGuard.get(ORIGIN + path);
                requireOk(reply);
                return parseDetail(reply.body, seriesId);
            } catch (Exception error) { failure = error; }
        }
        throw failure == null ? new IOException("分集加载未成功，请重试") : failure;
    }
    static JSONObject loadSearchWeb(String keyword) throws Exception {
        String query = keyword == null ? "" : keyword.trim();
        if (query.isEmpty() || query.length() > 100) throw new IOException("请输入 1 至 100 字的搜索词");
        NetworkGuard.Reply reply = NetworkGuard.get(ORIGIN + "/search/" + URLEncoder.encode(query, StandardCharsets.UTF_8.name()));
        requireOk(reply);
        return parseSearch(reply.body);
    }
    private static void requireOk(NetworkGuard.Reply reply) throws IOException {
        if (reply.status < 200 || reply.status >= 300) throw new IOException("来源页面响应 HTTP " + reply.status);
    }
}
