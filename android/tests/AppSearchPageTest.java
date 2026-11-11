package io.github.jackotom.hongguo.android;

import org.json.*;

/** Cursor-search envelope fixtures; parser only, no network or registration. */
public final class AppSearchPageTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static JSONObject video(String id, Object idType, String title, String cover) throws Exception {
        JSONObject video = new JSONObject().put("title", title).put("cover", cover);
        if (idType == String.class) video.put("series_id", id); else video.put("series_id", Long.parseLong(id));
        return video;
    }
    static JSONObject cell(Object... videos) throws Exception {
        JSONArray list = new JSONArray();
        for (Object video : videos) list.put(video);
        return new JSONObject().put("cell_name", "单元").put("book_id", "5").put("video_data", list);
    }
    static JSONObject plainCell(Object... videos) throws Exception {
        JSONArray list = new JSONArray();
        for (Object video : videos) list.put(video);
        return new JSONObject().put("cell_name", "单元").put("video_data", list);
    }
    static JSONObject envelope(JSONArray data, Object hasMore, Object nextOffset) throws Exception {
        return new JSONObject().put("code", 0).put("search_tabs", new JSONArray().put(new JSONObject()
            .put("tab_type", 11).put("has_more", hasMore).put("next_offset", nextOffset)
            .put("search_id", "sid-1").put("passback", "pb-1").put("data", data)));
    }
    interface Throwing { void run() throws Exception; }
    static void rejects(Throwing task, String code, String label) {
        try { task.run(); throw new AssertionError("accepted " + label); }
        catch (AssertionError expected) { throw expected; }
        catch (AppSearchPage.SearchPageException expected) { check(code.equals(expected.code), label + " code " + expected.code); }
        catch (Exception unexpected) { throw new AssertionError(label + " unexpected " + unexpected); }
    }
    public static void main(String[] unused) throws Exception {
        // string IDs accepted, numeric precision-unsafe IDs dropped, canonical title only when bound
        JSONObject good = video("9001234567890123456", String.class, "别名标题", "https://p3.byteimg.com/a.webp");
        good.put("video_detail", new JSONObject().put("series_id_str", "9001234567890123456").put("series_title", "正式标题第1季")
            .put("series_intro", "简介").put("episode_cnt", 24).put("episode_right_text", "更新至24集")
            .put("category_schema", "[{\"name\":\"都市\"},{\"name\":\"都市\"}]"));
        JSONObject numeric = video("9001234567890123456", Long.class, "数值ID剧", "https://p3.byteimg.com/b.webp");
        JSONObject parsed = AppSearchPage.parse(envelope(new JSONArray().put(plainCell(good, numeric)), true, 20), 0, new java.util.HashSet<>());
        check(parsed.getBoolean("hasMore"), "hasMore");
        check(parsed.getJSONObject("cursor").getInt("offset") == 20 && parsed.getJSONObject("cursor").getString("searchId").equals("sid-1"), "cursor fields");
        JSONArray items = parsed.getJSONArray("items");
        check(items.length() == 1, "numeric id dropped");
        JSONObject row = items.getJSONObject(0);
        check(row.getString("seriesId").equals("9001234567890123456"), "series id");
        check(row.getString("title").equals("正式标题第1季"), "canonical title binds by series_id_str");
        check(row.getString("coverUrl").startsWith("https://p3.byteimg.com/"), "cover domain");
        check(row.getInt("episodeCount") == 24 && row.getString("episodeLabel").equals("更新至24集"), "episode fields");
        check(row.getJSONArray("tags").length() == 1 && row.getJSONArray("tags").getString(0).equals("都市"), "tags");
        // cover from a foreign domain is dropped to empty
        JSONObject foreignCover = video("123", String.class, "外域封面", "https://evil.example.com/x.webp");
        JSONObject foreign = AppSearchPage.parse(envelope(new JSONArray().put(cell(foreignCover)), false, 0), 0, new java.util.HashSet<>());
        check(foreign.getJSONArray("items").getJSONObject(0).getString("coverUrl").isEmpty(), "foreign cover dropped");
        // lynx_data nested cells expand
        JSONObject nested = cell(video("777", String.class, "嵌套剧", ""));
        JSONObject outer = new JSONObject().put("cell_name", "外层").put("lynx_data",
            new JSONObject().put("cell_data", new JSONArray().put(nested)).toString());
        JSONObject expanded = AppSearchPage.parse(envelope(new JSONArray().put(outer), false, 0), 0, new java.util.HashSet<>());
        check(expanded.getJSONArray("items").getJSONObject(0).getString("seriesId").equals("777"), "lynx_data cell expansion");
        // seen dedupe across pages
        JSONObject seenParsed = AppSearchPage.parse(envelope(new JSONArray().put(cell(good)), false, 0), 20, new java.util.HashSet<>(java.util.Collections.singleton("9001234567890123456")));
        check(seenParsed.getJSONArray("items").length() == 0 && !seenParsed.getBoolean("hasMore"), "seen ids never repeat");
        // cursor must advance
        rejects(() -> AppSearchPage.parse(envelope(new JSONArray().put(cell(good)), true, 0), 0, new java.util.HashSet<>()), "SEARCH_CURSOR_STALLED", "non-advancing cursor");
        rejects(() -> AppSearchPage.parse(envelope(new JSONArray().put(cell(good)), true, "abc"), 0, new java.util.HashSet<>()), "SEARCH_CURSOR_STALLED", "invalid next_offset");
        // over 40 items per page rejected
        JSONArray many = new JSONArray();
        for (int i = 0; i < 41; i++) many.put(cell(video(String.valueOf(1000 + i), String.class, "剧" + i, "")));
        rejects(() -> AppSearchPage.parse(envelope(many, false, 0), 0, new java.util.HashSet<>()), "SEARCH_PAGE_OVERFLOW", "page over 40");
        // has_more without new items stalls
        rejects(() -> AppSearchPage.parse(envelope(new JSONArray(), true, 30), 0, new java.util.HashSet<>()), "SEARCH_CURSOR_STALLED", "has_more without items");
        // tab_type must be 11
        JSONObject wrongTab = envelope(new JSONArray().put(cell(good)), false, 0);
        wrongTab.getJSONArray("search_tabs").getJSONObject(0).put("tab_type", 12);
        rejects(() -> AppSearchPage.parse(wrongTab, 0, new java.util.HashSet<>()), "SEARCH_INVALID", "missing drama tab");
        // business code must be zero
        JSONObject rejected = envelope(new JSONArray().put(cell(good)), false, 0);
        rejected.put("code", 1);
        rejects(() -> AppSearchPage.parse(rejected, 0, new java.util.HashSet<>()), "SEARCH_INVALID", "business rejection");
        System.out.println("APP_SEARCH_PAGE_OK checks=" + checks + " networkRequests=0");
    }
}
