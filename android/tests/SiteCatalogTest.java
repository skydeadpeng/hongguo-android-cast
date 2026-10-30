package io.github.jackotom.hongguo.android;

import org.json.*;

/** Inline _ROUTER_DATA fixtures; parser only, no network. */
public final class SiteCatalogTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static String page(JSONObject loaderData) throws Exception {
        return "<script>window._ROUTER_DATA = " + new JSONObject().put("loaderData", loaderData) + ";globalThis.__executed=true;</script>";
    }
    static JSONObject categoryData(String contentType, String... names) throws Exception {
        JSONArray list = new JSONArray();
        for (int i = 0; i < names.length; i++) {
            // A repeated title denotes the same series repeated in the feed.
            int id = i > 0 && names[i].equals(names[i - 1]) ? i - 1 : i;
            list.put(new JSONObject().put("series_id", "10000" + id).put("series_name", names[i])
                .put("series_cover", "https://p.byteimg.com/c" + id + ".webp").put("series_intro", "简介" + id).put("episode_cnt", 12 + id)
                .put("tags", new JSONArray().put("逆袭")).put("episode_right_text", "全12集"));
        }
        return new JSONObject().put("categoryRoute", new JSONObject().put("contentType", contentType))
            .put("isSuccess", true).put("recommendList", list)
            .put("pagination", new JSONObject().put("pageNum", 2).put("totalPages", 5).put("total", 96));
    }
    static JSONObject detailData() throws Exception {
        return new JSONObject().put("isSuccess", true)
            .put("seriesDetail", new JSONObject().put("series_id", "100000").put("series_name", "详情剧").put("series_intro", "好看")
                .put("episode_cnt", 3).put("series_cover", "https://p2.byteimg.com/d.webp")
                .put("vid_list", new JSONArray().put("9001").put("9002").put("9001")).put("accessible_episode_cnt", 1));
    }
    static void rejects(Throwing task, String label) { try { task.run(); throw new AssertionError("accepted " + label); } catch (Exception expected) { check(true, label); } }
    interface Throwing { void run() throws Exception; }
    public static void main(String[] unused) throws Exception {
        JSONObject catalog = SiteCatalog.parseCatalog(page(new JSONObject().put("category_real-drama/page", categoryData("real-drama", "剧一", "剧二", "剧二"))), "real-drama", 2);
        check(catalog.getInt("page") == 2 && catalog.getInt("totalPages") == 5 && catalog.getInt("total") == 96, "pagination");
        JSONArray items = catalog.getJSONArray("items");
        check(items.length() == 2, "dedupe by seriesId");
        check(items.getJSONObject(0).getString("title").equals("剧一") && items.getJSONObject(0).getInt("episodeCount") == 12, "series shape");
        check(items.getJSONObject(0).getString("coverUrl").startsWith("https://p.byteimg.com/"), "cover passes public https check");
        check(items.getJSONObject(0).getString("episodeLabel").equals("全12集"), "episode label");
        check(!items.getJSONObject(0).getString("seriesId").isEmpty(), "series id");
        rejects(() -> SiteCatalog.parseCatalog(page(new JSONObject().put("category_real-drama/page", categoryData("comic-drama", "x"))), "real-drama", 1), "wrong content type");
        rejects(() -> SiteCatalog.parseCatalog(page(new JSONObject()), "real-drama", 1), "missing category route");
        rejects(() -> SiteCatalog.parseCatalog(page(new JSONObject().put("category_real-drama/page", categoryData("real-drama", "x"))), "other-drama", 1), "unknown category");
        // detail: dedupe, accessible gate, identity mismatch
        JSONObject detail = SiteCatalog.parseDetail(page(new JSONObject().put("player_(series_id)/page", detailData())), "100000");
        check(detail.getJSONArray("episodes").length() == 2, "dedupe vid_list");
        check(detail.getJSONArray("episodes").getJSONObject(0).getBoolean("webAvailable") && !detail.getJSONArray("episodes").getJSONObject(1).getBoolean("webAvailable"), "accessible gate");
        check(detail.getInt("accessibleEpisodeCount") == 1, "accessible count");
        check(detail.getJSONArray("episodes").getJSONObject(1).getInt("index") == 2, "episode index");
        check(detail.getString("title").equals("详情剧") && detail.getInt("episodeCount") == 3, "detail series shape");
        rejects(() -> SiteCatalog.parseDetail(page(new JSONObject().put("player_(series_id)/page", detailData())), "100001"), "identity mismatch");
        rejects(() -> SiteCatalog.parseDetail(page(new JSONObject().put("player_(series_id)/page",
            new JSONObject().put("isSuccess", true).put("seriesDetail", new JSONObject().put("series_id", "100000").put("series_name", "无分集").put("vid_list", new JSONArray())))), "100000"), "no episodes");
        // detail alternate route key
        JSONObject alt = SiteCatalog.parseDetail(page(new JSONObject().put("player_(series_id)/(vid)/page", detailData())), "100000");
        check(alt.getJSONArray("episodes").length() == 2, "alternate loader key");
        // web search first screen
        JSONObject searchData = new JSONObject().put("isSuccess", true).put("searchList", new JSONArray().put(new JSONObject()
            .put("video_data", new JSONObject().put("series_id", "777").put("series_title", "搜索剧")
                .put("category_list", new JSONArray().put(new JSONObject().put("name", "甜宠")).put(new JSONObject().put("name", "甜宠")))))).put("total", 1);
        JSONObject search = SiteCatalog.parseSearch(page(new JSONObject().put("search_(keyword)/page", searchData)));
        check(search.getJSONArray("items").getJSONObject(0).getString("seriesId").equals("777"), "search item id");
        check(search.getJSONArray("items").getJSONObject(0).getString("title").equals("搜索剧"), "search item title");
        check(search.getJSONArray("items").getJSONObject(0).getJSONArray("tags").length() == 1, "search tags dedupe");
        rejects(() -> SiteCatalog.parseSearch(page(new JSONObject())), "missing search data");
        System.out.println("SITE_CATALOG_OK checks=" + checks + " networkRequests=0");
    }
}
