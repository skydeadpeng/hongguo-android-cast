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
    /** The official page since 2026-11-27 (upstream #217): three leading episodes by token, 71 declared. */
    static final String SID = "7706908220253555737", TOKEN1 = "v1_5DC8VBWf14_jqPcnhiSPfJ6fiGI", TOKEN2 = "v1_2VMvyadqPpt6G-ZZqkmCa18eFHw", TOKEN3 = "v1_q8HExAwWPUBqpvQw_DdZQ9vFYP0";
    static JSONObject tokenData() throws Exception {
        return new JSONObject().put("isSuccess", true).put("protectedPlayback", true).put("series_id", SID).put("vid", TOKEN1)
            .put("seriesDetail", new JSONObject().put("series_id", SID).put("series_name", "得了一种只能说真话的病")
                .put("series_cover", "https://p3-reading-sign.fqnovelpic.com/novel-pic/x").put("series_intro", "简介").put("episode_cnt", 71)
                .put("episode_total_cnt", 71).put("accessible_episode_cnt", 3).put("episode_right_text", "全71集")
                .put("series_episode_info", new JSONObject().put("episode_cnt", 71).put("episode_total_cnt", 71).put("series_status", 1))
                .put("vid_list", new JSONArray().put(TOKEN1).put(TOKEN2).put(TOKEN3)));
    }
    /** The App detail reply as AppEpisodeList projects it; rows arrive in reverse to prove the order is kept. */
    static JSONObject appList(String seriesId, int count) throws Exception {
        JSONArray episodes = new JSONArray();
        for (int i = count; i >= 1; i--) episodes.put(new JSONObject().put("vid", "7706910" + String.format("%012d", (long) i)).put("index", i).put("locked", false));
        return new JSONObject().put("seriesId", seriesId).put("title", "得了一种只能说真话的病").put("coverUrl", "https://p3-reading-sign.fqnovelpic.com/novel-pic/x")
            .put("description", "简介").put("episodeCount", count).put("episodeLabel", "全71集").put("episodes", episodes).put("source", "app");
    }
    static void tokenPages() throws Exception {
        // A token page lists only the leading episodes: a preview, never a complete list.
        SiteCatalog.WebPage preview = SiteCatalog.parseDetailPage(page(new JSONObject().put("player_(series_id)/page", tokenData())), SID);
        check(!preview.complete && preview.episodes.length() == 0, "token page is a preview without numeric episodes");
        check(preview.webIds.size() == 3 && TOKEN2.equals(preview.webIds.get(1)), "accessible tokens in episode order");
        check(preview.episodeCount == 71 && preview.item != null && preview.item.getString("title").equals("得了一种只能说真话的病"), "preview keeps the series metadata");
        rejects(() -> SiteCatalog.parseDetail(page(new JSONObject().put("player_(series_id)/page", tokenData())), SID), "token page read as a complete list");
        SiteCatalog.WebPage route = SiteCatalog.parseDetailPage(page(new JSONObject().put("detail_page", tokenData())), SID);
        check(!route.complete && route.webIds.size() == 3, "detail route serves the same token preview");
        rejects(() -> SiteCatalog.parseDetailPage(page(new JSONObject().put("player_(series_id)/page", tokenData())), "100001"), "token page identity mismatch");
        // A numeric page that lists nothing beyond its accessible prefix is a preview as well.
        JSONObject numeric = new JSONObject().put("isSuccess", true).put("seriesDetail", new JSONObject().put("series_id", "100000").put("series_name", "详情剧")
            .put("episode_cnt", 71).put("vid_list", new JSONArray().put("9001").put("9002").put("9003")).put("accessible_episode_cnt", 3));
        SiteCatalog.WebPage truncated = SiteCatalog.parseDetailPage(page(new JSONObject().put("player_(series_id)/page", numeric)), "100000");
        check(!truncated.complete && truncated.episodes.length() == 3 && truncated.webIds.size() == 3, "numeric page listing only its accessible prefix is a preview");
        check(SiteCatalog.parseDetailPage(page(new JSONObject().put("player_(series_id)/page", detailData())), "100000").complete, "a full numeric page stays complete");

        // Completion: the App order binds each token to its numeric episode; page metadata is kept.
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong budget = new java.util.concurrent.atomic.AtomicLong();
        SiteCatalog.completer((series, deadline) -> { calls.incrementAndGet(); budget.set(deadline); return appList(series, 71); });
        long before = System.nanoTime();
        JSONObject full = SiteCatalog.complete(SID, preview, before + java.util.concurrent.TimeUnit.MINUTES.toNanos(5));
        check(calls.get() == 1 && budget.get() > before && budget.get() <= before + java.util.concurrent.TimeUnit.SECONDS.toNanos(10), "the App list gets its own 9s inside the caller budget");
        JSONArray episodes = full.getJSONArray("episodes");
        check(episodes.length() == 71 && full.getInt("episodeCount") == 71 && full.getInt("accessibleEpisodeCount") == 3, "complete list with the three accessible episodes");
        check(episodes.getJSONObject(0).getBoolean("webAvailable") && episodes.getJSONObject(2).getBoolean("webAvailable") && !episodes.getJSONObject(3).getBoolean("webAvailable"), "tokens bind to the leading episodes only");
        check(("7706910" + "000000000001").equals(episodes.getJSONObject(0).getString("vid")) && ("7706910" + "000000000002").equals(episodes.getJSONObject(1).getString("vid")),
            "App rows are kept in episode order");
        check(("7706910" + "000000000071").equals(episodes.getJSONObject(70).getString("vid")) && episodes.getJSONObject(70).getInt("index") == 71
            && episodes.getJSONObject(0).getInt("index") == 1, "indexes run 1..71");
        check(SID.equals(full.getString("seriesId")) && full.getString("title").equals("得了一种只能说真话的病") && full.getString("description").equals("简介"), "page series metadata is kept");
        check(full.getJSONArray("tags").length() == 0, "tags stay an array");
        // From the WebView page only the ids arrive; without any, no episode is web accessible.
        JSONObject one = SiteCatalog.completeFromWebIds(SID, java.util.Arrays.asList(TOKEN1, TOKEN1, TOKEN2), before + java.util.concurrent.TimeUnit.MINUTES.toNanos(5));
        check(one.getInt("accessibleEpisodeCount") == 2 && one.getJSONArray("episodes").getJSONObject(1).getBoolean("webAvailable")
            && !one.getJSONArray("episodes").getJSONObject(2).getBoolean("webAvailable"), "handed-over ids are deduplicated in order");
        check(one.getString("title").equals("得了一种只能说真话的病") && one.getString("coverUrl").startsWith("https://p3-reading-sign.fqnovelpic.com/"), "without a page the App reply describes the series");
        JSONObject none = SiteCatalog.complete(SID, null, before + java.util.concurrent.TimeUnit.MINUTES.toNanos(5));
        check(none.getInt("accessibleEpisodeCount") == 0 && none.getJSONArray("episodes").length() == 71 && none.getJSONArray("tags").length() == 0, "no web ids means no web episodes");
        // Failures stay typed; a page that still lists numeric episodes remains usable on its own.
        final java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        SiteCatalog.completer((series, deadline) -> { asked.incrementAndGet(); throw new java.io.IOException("分集接口未返回数据，请稍后重试"); });
        rejects(() -> SiteCatalog.complete(SID, preview, before + java.util.concurrent.TimeUnit.MINUTES.toNanos(5)), "App failure on a token page");
        JSONObject kept = SiteCatalog.complete("100000", truncated, before + java.util.concurrent.TimeUnit.MINUTES.toNanos(5));
        check(kept.getJSONArray("episodes").length() == 3 && kept.getString("seriesId").equals("100000"), "a numeric preview stays usable when the App list fails");
        rejects(() -> SiteCatalog.completeFromWebIds(SID, java.util.Arrays.asList("v1_short"), before + java.util.concurrent.TimeUnit.MINUTES.toNanos(5)), "malformed web id");
        rejects(() -> SiteCatalog.completeFromWebIds(SID, java.util.Arrays.asList("<b>"), before + java.util.concurrent.TimeUnit.MINUTES.toNanos(5)), "non-web id");
        rejects(() -> SiteCatalog.completeFromWebIds("../1", java.util.Collections.<String>emptyList(), before + java.util.concurrent.TimeUnit.MINUTES.toNanos(5)), "invalid series id");
        rejects(() -> SiteCatalog.merge(SID, preview, appList("100001", 71)), "App list for another series");
        rejects(() -> SiteCatalog.merge(SID, preview, appList(SID, 0)), "empty App list");
        JSONObject duplicated = appList(SID, 2);
        duplicated.getJSONArray("episodes").getJSONObject(1).put("vid", duplicated.getJSONArray("episodes").getJSONObject(0).getString("vid"));
        rejects(() -> SiteCatalog.merge(SID, preview, duplicated), "duplicate App episode");
        SiteCatalog.completer(null);
        rejects(() -> SiteCatalog.complete(SID, preview, before + java.util.concurrent.TimeUnit.MINUTES.toNanos(5)), "unwired list source");
        // Cancellation never reaches the App list.
        SiteCatalog.completer((series, deadline) -> { asked.incrementAndGet(); return appList(series, 71); });
        int seen = asked.get();
        Thread.currentThread().interrupt();
        try { SiteCatalog.complete(SID, preview, before + java.util.concurrent.TimeUnit.MINUTES.toNanos(5)); throw new AssertionError("interrupted completion accepted"); }
        catch (java.io.InterruptedIOException expected) { check(asked.get() == seen, "an interrupted completion never asks the App list"); }
        finally { Thread.interrupted(); }
    }
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
        tokenPages();
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
