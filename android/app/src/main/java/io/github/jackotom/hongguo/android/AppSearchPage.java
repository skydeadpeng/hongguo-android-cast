package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Cursor-search envelope projection: string IDs only, never cached device identities. Mirrors src/android/search.mjs. */
final class AppSearchPage {
    static final class SearchPageException extends IOException {
        final String code;
        SearchPageException(String message, String code) { super(message); this.code = code; }
    }
    private static final String[] COVER_DOMAINS = { "byteimg.com", "fqnovelpic.com", "fqnovelstatic.com" };
    private AppSearchPage() { }
    /** @param offset the requested cursor offset; @param seen series ids already shown for this query. */
    static JSONObject parse(JSONObject envelope, int offset, Set<String> seen) throws Exception {
        if (envelope == null || !zero(envelope.opt("code"))) throw new SearchPageException("搜索接口暂未返回有效结果，请重试", "SEARCH_INVALID");
        Object rawTabs = envelope.opt("search_tabs");
        if (!(rawTabs instanceof JSONArray) && envelope.optJSONObject("data") != null) rawTabs = envelope.getJSONObject("data").opt("search_tabs");
        JSONArray tabs = rawTabs instanceof JSONArray ? (JSONArray) rawTabs : null;
        JSONObject tab = null;
        if (tabs != null) for (int i = 0; i < tabs.length(); i++) {
            JSONObject candidate = tabs.optJSONObject(i);
            if (candidate != null && (candidate.opt("tab_type") instanceof Number && ((Number) candidate.opt("tab_type")).intValue() == 11 || "11".equals(candidate.opt("tab_type")))) { tab = candidate; break; }
        }
        JSONArray data = tab == null ? null : tab.optJSONArray("data");
        if (tab == null || data == null || data.length() > 200) throw new SearchPageException("搜索接口缺少短剧结果，请重试", "SEARCH_INVALID");
        boolean hasMore = more(tab.opt("has_more"));
        Integer next = integer(tab.opt("next_offset"));
        if (hasMore && (next == null || next <= offset || next > 10000)) throw new SearchPageException("搜索分页未前进，请重试当前页", "SEARCH_CURSOR_STALLED");
        String searchId = cursor(tab.opt("search_id")), passback = cursor(tab.opt("passback"));
        Set<String> ids = new HashSet<>(seen);
        JSONArray items = new JSONArray();
        for (int i = 0; i < data.length(); i++) {
            JSONObject outer = data.optJSONObject(i);
            if (outer == null) continue;
            List<JSONObject> cells = new ArrayList<>();
            cells.add(outer);
            Object lynx = outer.opt("lynx_data");
            if (lynx instanceof String && ((String) lynx).length() <= 256 * 1024) {
                try {
                    Object nested = new JSONObject((String) lynx).opt("cell_data");
                    if (nested instanceof JSONArray) { JSONArray rows = (JSONArray) nested; for (int n = 0; n < Math.min(rows.length(), 200); n++) if (rows.optJSONObject(n) != null) cells.add(rows.getJSONObject(n)); }
                    else if (nested instanceof JSONObject) cells.add((JSONObject) nested);
                } catch (JSONException ignored) { }
            }
            for (JSONObject cell : cells) {
                JSONArray videos = cell.optJSONArray("video_data");
                if (videos == null) continue;
                for (int v = 0; v < Math.min(videos.length(), 20); v++) {
                    JSONObject row = item(videos.optJSONObject(v), cell);
                    if (row == null || ids.contains(row.getString("seriesId"))) continue;
                    ids.add(row.getString("seriesId"));
                    items.put(row);
                    if (items.length() > 40) throw new SearchPageException("搜索结果超过单页限制", "SEARCH_PAGE_OVERFLOW");
                }
            }
        }
        if (hasMore && items.length() == 0) throw new SearchPageException("搜索分页没有返回新的短剧，请重试当前页", "SEARCH_CURSOR_STALLED");
        return new JSONObject().put("items", items).put("hasMore", hasMore)
            .put("cursor", new JSONObject().put("offset", next == null ? offset : next).put("searchId", searchId).put("passback", passback));
    }
    private static JSONObject item(JSONObject video, JSONObject cell) throws Exception {
        if (video == null) return null;
        // Never stringify a JSON number: the provider's large numeric IDs lose precision.
        String seriesId = "";
        for (Object value : new Object[]{ video.opt("series_id"), cell.opt("book_id"), cell.opt("search_result_id") })
            if (value instanceof String && ((String) value).matches("[0-9]{1,30}")) { seriesId = (String) value; break; }
        if (seriesId.isEmpty()) return null;
        JSONObject detail = video.optJSONObject("video_detail");
        String fullTitle = detail == null ? "" : text(detail.opt("series_title"), 200);
        String canonical = detail != null && seriesId.equals(detail.opt("series_id_str")) ? fullTitle : "";
        String title = !canonical.isEmpty() ? canonical
            : text(firstString(video.opt("title"), highlightTitle(cell), cell.opt("cell_name")), 200);
        if (title.isEmpty()) return null;
        return new JSONObject().put("seriesId", seriesId).put("title", title)
            .put("coverUrl", cover(firstString(video.opt("cover"), video.opt("cover_url"), detail == null ? null : detail.opt("series_cover"))))
            .put("description", text(firstString(detail == null ? null : detail.opt("series_intro"), video.opt("video_desc"), video.opt("sub_title")), 800))
            .put("episodeCount", Math.min(integer(firstValue(video.opt("episode_cnt"), detail == null ? null : detail.opt("episode_cnt"))) == null ? 0 : integer(firstValue(video.opt("episode_cnt"), detail == null ? null : detail.opt("episode_cnt"))), 10000))
            .put("episodeLabel", text(detail == null ? null : detail.opt("episode_right_text"), 80))
            .put("tags", tags(firstString(video.opt("category_schema"), detail == null ? null : detail.opt("category_schema"))));
    }
    private static Object firstValue(Object... values) { for (Object value : values) if (value != null && value != JSONObject.NULL) return value; return null; }
    private static Object firstString(Object... values) { for (Object value : values) if (value instanceof String && !((String) value).isEmpty()) return value; return null; }
    private static Object highlightTitle(JSONObject cell) {
        JSONObject highlight = cell == null ? null : cell.optJSONObject("search_high_light");
        JSONObject title = highlight == null ? null : highlight.optJSONObject("title");
        return title == null ? null : title.opt("text");
    }
    private static JSONArray tags(Object schema) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (schema instanceof String && ((String) schema).length() <= 16384) {
            try {
                JSONArray rows = new JSONArray((String) schema);
                for (int i = 0; i < rows.length() && tags.size() < 8; i++) {
                    Object name = rows.optJSONObject(i) == null ? null : rows.optJSONObject(i).opt("name");
                    String tag = text(name, 40);
                    if (!tag.isEmpty()) tags.add(tag);
                }
            } catch (JSONException ignored) { }
        }
        return new JSONArray(new ArrayList<>(tags));
    }
    private static String cover(Object value) {
        if (!(value instanceof String)) return "";
        String text = (String) value;
        if (text.length() > 2048 || text.matches("(?s).*[\\s\\\\].*")) return "";
        try {
            java.net.URI uri = new java.net.URI(text);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) return "";
            for (String domain : COVER_DOMAINS) if (host.equals(domain) || host.endsWith("." + domain)) return text;
            return "";
        } catch (Exception invalid) { return ""; }
    }
    private static boolean zero(Object value) {
        return value instanceof Number && ((Number) value).doubleValue() == 0 || "0".equals(value);
    }
    private static boolean more(Object value) throws SearchPageException {
        if (Boolean.TRUE.equals(value) || Integer.valueOf(1).equals(value) || "1".equals(value) || "true".equals(value)) return true;
        if (Boolean.FALSE.equals(value) || Integer.valueOf(0).equals(value) || "0".equals(value) || "false".equals(value)) return false;
        throw new SearchPageException("搜索服务未返回有效的分页状态", "SEARCH_INVALID");
    }
    private static Integer integer(Object value) {
        if (value instanceof String && !((String) value).matches("[0-9]{1,8}")) return null;
        if (!(value instanceof String) && !(value instanceof Number)) return null;
        double number;
        try { number = value instanceof String ? Double.parseDouble((String) value) : ((Number) value).doubleValue(); }
        catch (NumberFormatException invalid) { return null; }
        return Double.isFinite(number) && number == Math.rint(number) && number >= 0 && number <= 1e9 ? (int) number : null;
    }
    private static String cursor(Object value) throws SearchPageException {
        if (!(value instanceof String)) return "";
        String text = (String) value;
        if (text.length() > 512 || text.matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw new SearchPageException("搜索服务返回了无效游标", "SEARCH_INVALID");
        return text;
    }
    private static String text(Object value, int max) {
        if (!(value instanceof String)) return "";
        String text = ((String) value).trim();
        return text.length() <= max && !text.matches("(?s).*[\\x00-\\x1f\\x7f].*") ? text : "";
    }
}
