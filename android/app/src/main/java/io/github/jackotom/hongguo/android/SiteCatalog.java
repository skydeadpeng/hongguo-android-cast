package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Official public-site catalog/detail/search parsing and bounded fetching for the native UI. */
final class SiteCatalog {
    static final String ORIGIN = "https://hongguoduanju.com";
    static final String[] CATEGORIES = { "real-drama", "comic-drama", "ai-drama" };
    /** Since 2026-11-27 the episode page addresses its accessible episodes by an obfuscated token. */
    static final String WEB_TOKEN = "v[0-9]+_[A-Za-z0-9_-]{8,128}";
    static final String WEB_ID = "(?:[0-9]{1,30}|" + WEB_TOKEN + ")";
    private static final int MAX_EPISODES = 2000;
    /** Whole detail budget: up to two page routes, then the App list inside its own 9s. */
    private static final long DETAIL_NANOS = TimeUnit.SECONDS.toNanos(16), APP_DETAIL_NANOS = TimeUnit.SECONDS.toNanos(9);
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

    /** A parsed official episode page: numeric ids, or since 2026-11-27 its leading obfuscated tokens. */
    static final class WebPage {
        /** Series metadata in the library projection; null only for an App-only detail. */
        JSONObject item;
        /** Numeric episodes in page order; empty on a token page. */
        final JSONArray episodes = new JSONArray();
        /** Ids the page uses for its accessible episodes, in episode order: tokens, or numeric ids. */
        final List<String> webIds = new ArrayList<>();
        int episodeCount;
        /** False when the page only previews the accessible episodes of a longer series. */
        boolean complete = true;
    }
    static WebPage parseDetailPage(String html, String seriesId) throws Exception {
        if (seriesId == null || !seriesId.matches("[0-9]{1,30}")) throw new IOException("剧集编号必须是 1 至 30 位数字字符串");
        JSONObject loaders = EpisodeResolver.extractRouterData(html).optJSONObject("loaderData");
        JSONObject data = loaders == null ? null : first(
            loaders.optJSONObject("player_(series_id)/page"), loaders.optJSONObject("player_(series_id)/(vid)/page"), loaders.optJSONObject("detail_page"));
        JSONObject detail = data == null ? null : data.optJSONObject("seriesDetail");
        if (data == null || Boolean.FALSE.equals(data.opt("isSuccess")) || detail == null
            || !seriesId.equals(text(detail.opt("series_id"), 30)) || detail.has("series_id") && !seriesId.equals(String.valueOf(detail.get("series_id"))))
            throw new IOException("剧集详情与请求编号不一致");
        JSONObject item = series(detail, false);
        JSONArray ids = detail.optJSONArray("vid_list");
        if (item == null || ids == null) throw new IOException("来源页面缺少剧集详情或分集列表");
        WebPage page = new WebPage();
        page.item = item;
        JSONObject info = detail.optJSONObject("series_episode_info");
        page.episodeCount = Math.max(count(detail.opt("episode_cnt")), info == null ? 0 : count(info.opt("episode_cnt")));
        int accessible = count(detail.opt("accessible_episode_cnt"));
        if (ids.length() > 0 && String.valueOf(ids.opt(0)).matches(WEB_TOKEN)) {
            // Token page: only the leading episodes, by position; the full numeric list needs the App detail.
            int limit = accessible > 0 ? Math.min(accessible, ids.length()) : ids.length();
            for (int i = 0; i < ids.length() && page.webIds.size() < limit; i++) {
                String token = text(ids.opt(i), 64);
                if (token.matches(WEB_TOKEN) && !page.webIds.contains(token)) page.webIds.add(token);
            }
            page.complete = false;
            return page;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            String vid = text(ids.opt(i), 30);
            if (!vid.matches("[0-9]{1,30}") || !seen.add(vid)) continue;
            boolean available = seen.size() <= accessible;
            page.episodes.put(new JSONObject().put("vid", vid).put("index", seen.size()).put("webAvailable", available).put("availability", available ? "available" : "unknown"));
            if (available) page.webIds.add(vid);
        }
        if (page.episodes.length() == 0) throw new IOException("来源未返回有效分集");
        // A full page lists every episode and only marks its accessible prefix; one that lists
        // nothing beyond that prefix is a preview of a longer series.
        page.complete = !(page.episodeCount > page.episodes.length() && page.episodes.length() <= accessible);
        return page;
    }
    static JSONObject parseDetail(String html, String seriesId) throws Exception {
        WebPage page = parseDetailPage(html, seriesId);
        if (page.episodes.length() == 0) throw new IOException("来源未返回有效分集");
        return detail(page);
    }
    private static JSONObject detail(WebPage page) throws Exception {
        int available = 0;
        for (int i = 0; i < page.episodes.length(); i++) if (page.episodes.getJSONObject(i).getBoolean("webAvailable")) available++;
        return page.item.put("episodes", page.episodes).put("accessibleEpisodeCount", available);
    }

    /** Supplies the complete numeric episode list when the page only previews its accessible episodes. */
    interface EpisodeCompleter { JSONObject complete(String seriesId, long deadlineNanos) throws Exception; }
    private static volatile EpisodeCompleter completer;
    /** Wired once by the application; tests install their own or clear it. */
    static void completer(EpisodeCompleter value) { completer = value; }
    private static boolean cancelled(Exception error) {
        return Thread.currentThread().isInterrupted() || error instanceof java.io.InterruptedIOException && !(error instanceof java.net.SocketTimeoutException);
    }

    static JSONObject loadDetail(String seriesId) throws Exception {
        if (seriesId == null || !seriesId.matches("[0-9]{1,30}")) throw new IOException("剧集编号必须是 1 至 30 位数字字符串");
        long deadline = System.nanoTime() + DETAIL_NANOS;
        Exception failure = null;
        for (String path : new String[]{ "/player/" + seriesId, "/detail?series_id=" + seriesId }) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("分集加载已取消");
            try {
                NetworkGuard.Reply reply = NetworkGuard.get(ORIGIN + path);
                requireOk(reply);
                WebPage page = parseDetailPage(reply.body, seriesId);
                // Both routes serve the same series data: a full page is final, a preview needs the App list.
                return page.complete ? detail(page) : complete(seriesId, page, deadline);
            } catch (Exception error) { if (cancelled(error)) throw error; failure = error; }
        }
        // No page route answered; the App list alone still yields a usable detail.
        try { return complete(seriesId, null, deadline); }
        catch (Exception appError) { if (failure != null && failure != appError) appError.addSuppressed(failure); throw appError; }
    }
    /** Completes a preview page with the App list; without a page the App reply stands alone. */
    static JSONObject complete(String seriesId, WebPage page, long deadlineNanos) throws Exception {
        EpisodeCompleter source = completer;
        if (source == null) throw new IOException("分集列表来源未就绪，请重启应用后重试");
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("分集加载已取消");
        JSONObject app;
        try { app = source.complete(seriesId, Math.min(deadlineNanos, System.nanoTime() + APP_DETAIL_NANOS)); }
        catch (Exception error) {
            if (cancelled(error)) throw error;
            // A page that still lists numeric episodes remains usable on its own.
            if (page == null || page.episodes.length() == 0) throw error;
            return detail(page);
        }
        return merge(seriesId, page, app);
    }
    /** The WebView page parsed the official page itself; only the accessible ids it found are handed over. */
    static JSONObject completeFromWebIds(String seriesId, List<String> webIds, long deadlineNanos) throws Exception {
        if (seriesId == null || !seriesId.matches("[0-9]{1,30}") || webIds == null || webIds.size() > MAX_EPISODES) throw new IOException("剧集或分集请求信息无效");
        for (String id : webIds) if (id == null || !id.matches(WEB_ID)) throw new IOException("分集请求信息无效");
        WebPage page = new WebPage();
        page.webIds.addAll(new LinkedHashSet<>(webIds)); page.complete = false;
        return complete(seriesId, page, deadlineNanos);
    }
    /** The App episode order binds each accessible web id to its numeric episode. */
    static JSONObject merge(String seriesId, WebPage page, JSONObject app) throws Exception {
        if (app == null || !seriesId.equals(app.optString("seriesId"))) throw new IOException("分集接口返回的剧集与请求不一致");
        JSONArray rows = app.optJSONArray("episodes");
        if (rows == null || rows.length() == 0 || rows.length() > MAX_EPISODES) throw new IOException("分集接口未返回有效分集");
        List<String> webIds = page == null ? Collections.<String>emptyList() : page.webIds;
        // Official episode number orders the rows whatever order the reply arrived in.
        Integer[] order = new Integer[rows.length()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            JSONObject x = rows.optJSONObject(a), y = rows.optJSONObject(b);
            long ix = x == null ? a + 1 : x.optLong("index", a + 1), iy = y == null ? b + 1 : y.optLong("index", b + 1);
            return ix != iy ? Long.compare(ix, iy) : Integer.compare(a, b);
        });
        Set<String> seen = new HashSet<>();
        JSONArray episodes = new JSONArray();
        List<String> vids = new ArrayList<>();
        for (int position = 0; position < order.length; position++) {
            JSONObject row = rows.optJSONObject(order[position]);
            String vid = row == null ? "" : row.optString("vid", "");
            if (!vid.matches("[0-9]{1,30}") || !seen.add(vid)) throw new IOException("分集接口返回的分集数据异常");
            boolean available = position < webIds.size();
            episodes.put(new JSONObject().put("vid", vid).put("index", position + 1).put("webAvailable", available).put("availability", available ? "available" : "unknown"));
            vids.add(vid);
        }
        String title = app.optString("title", "").trim();
        JSONObject item = page != null && page.item != null ? page.item
            : new JSONObject().put("seriesId", seriesId).put("title", title.isEmpty() ? "未命名短剧" : title)
                .put("coverUrl", app.optString("coverUrl", "")).put("description", app.optString("description", ""))
                .put("episodeLabel", app.optString("episodeLabel", "")).put("tags", new JSONArray());
        EpisodeResolver.rememberWebIds(seriesId, vids, webIds);
        return item.put("episodeCount", episodes.length()).put("episodes", episodes).put("accessibleEpisodeCount", Math.min(webIds.size(), episodes.length()));
    }
    private static JSONObject first(Object... values) { for (Object value : values) if (value instanceof JSONObject) return (JSONObject) value; return null; }
    // ---- bounded fetching through the shared page transport ----
    static JSONObject loadCatalog(String category, int page) throws Exception {
        NetworkGuard.Reply reply = NetworkGuard.get(ORIGIN + "/category/" + category + (page > 1 ? "?page=" + page : ""));
        requireOk(reply);
        return parseCatalog(reply.body, category, page);
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
