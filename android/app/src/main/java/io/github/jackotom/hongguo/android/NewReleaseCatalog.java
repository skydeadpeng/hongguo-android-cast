package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The App near-seven-day new-release directory (real dramas and AI dramas, online-time order).
 * The web search index lags behind the App by hours to a day, so a same-day title is found
 * here when the normal channels return nothing relevant. Verified live: the registered
 * anonymous identity of the account client serves this endpoint without any new signer.
 */
final class NewReleaseCatalog {
    static final String CATEGORY_API = "/reading/distribution/category/landpage/v/";
    static final int PAGE_LIMIT = 50;
    static final int MAX_RESULTS = 200;
    static final String[][] GENRES = {{"default", "short_play"}, {"ai_series", "ai_series"}};

    private NewReleaseCatalog() { }

    /** One genre page body; the cursor chain rides offset + session_id. */
    static JSONObject pageBody(String reqScene, String genre, int offset, String sessionId) throws JSONException {
        if (reqScene == null || genre == null || !reqScene.matches("[a-z_]{1,30}") || !genre.matches("[a-z_]{1,30}")
                || offset < 0 || sessionId == null || sessionId.length() > 128) throw new IllegalArgumentException("上新目录筛选无效");
        return new JSONObject()
                .put("filter_ids", "")
                .put("req_scene", reqScene)
                .put("offset", offset)
                .put("need_selector_panel", false)
                .put("limit", PAGE_LIMIT)
                .put("select_items", new JSONObject()
                        .put("genre", new JSONArray().put(genre))
                        .put("sort", new JSONArray().put("online_time"))
                        .put("category_dim_theme", new JSONArray())
                        .put("category_dim_role", new JSONArray())
                        .put("category_dim_epoch", new JSONArray())
                        .put("gender", new JSONArray())
                        .put("online_time", new JSONArray().put("days_7"))
                        .put("creation_status", new JSONArray()))
                .put("session_id", sessionId)
                .put("req_type", offset > 0 ? "only_content" : "default")
                .put("client_req_type", 3);
    }

    /** One request: the POSTed JSON body is bounded; the reply must be a valid envelope. */
    static JSONObject request(OfficialApiClient client, String reqScene, String genre, int offset, String sessionId) throws Exception {
        if (client == null) throw new IOException("红果上新目录未就绪");
        return client.cloud(CATEGORY_API, "POST", null, pageBody(reqScene, genre, offset, sessionId), false);
    }

    /**
     * Reads both genres up to {@code pages} pages each and returns the merged, deduped
     * new-release rows. One failing genre keeps the other's results.
     */
    static JSONArray fetch(OfficialApiClient client, int pages, long deadlineNanos) throws Exception {
        if (pages < 1 || pages > 2) throw new IOException("上新目录页数无效");
        LinkedHashMap<String, JSONObject> seen = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        Set<String> releasedToday = new HashSet<>();
        for (String[] genre : GENRES) {
            int offset = 0;
            String sessionId = "";
            for (int page = 1; page <= pages; page++) {
                if (System.nanoTime() >= deadlineNanos) break;
                JSONObject reply = request(client, genre[0], genre[1], offset, sessionId);
                if (reply.optInt("code", -1) != 0) break;
                JSONObject data = reply.optJSONObject("data");
                JSONArray rows = data == null ? null : data.optJSONArray("video_data");
                if (rows == null) break;
                for (int index = 0; index < rows.length(); index++) {
                    JSONObject item = row(rows.optJSONObject(index));
                    if (item == null) continue;
                    String id = item.getString("series_id");
                    if (item.optBoolean("released_today")) releasedToday.add(id);
                    JSONObject existing = seen.get(id);
                    if (existing == null) { seen.put(id, item); order.add(id); }
                }
                if (data.optBoolean("has_more") != true) break;
                int next = data.optInt("next_offset", -1);
                if (next <= offset) break;
                offset = next;
                sessionId = data.optString("session_id", "");
                if (seen.size() >= MAX_RESULTS) break;
            }
        }
        JSONArray out = new JSONArray();
        for (String id : order) {
            JSONObject item = seen.get(id);
            if (releasedToday.contains(id)) item.put("released_today", true);
            out.put(item);
            if (out.length() >= MAX_RESULTS) break;
        }
        return out;
    }

    /** Compact row projection; unknown shapes are skipped, never guessed. */
    static JSONObject row(JSONObject raw) {
        if (raw == null) return null;
        String id = String.valueOf(raw.optString("series_id", ""));
        if (!id.matches("\\d{1,30}") || id.matches("0+")) return null;
        String title = clean(raw.optString("title", raw.optString("series_title", raw.optString("series_name", ""))));
        if (title.isEmpty() || title.length() > 200) return null;
        JSONObject tagInfo = raw.optJSONObject("tag_info");
        List<String> labels = new ArrayList<>();
        addLabel(labels, tagInfo == null ? "" : tagInfo.optString("text", ""));
        JSONArray subtitles = raw.optJSONArray("sub_title_list");
        for (int index = 0; subtitles != null && index < subtitles.length(); index++) {
            JSONObject entry = subtitles.optJSONObject(index);
            addLabel(labels, entry == null ? "" : entry.optString("content", ""));
        }
        boolean today = labels.remove("今日上新");
        labels.remove("新剧");
        JSONArray tags = new JSONArray();
        for (String label : labels) { tags.put(label); if (tags.length() >= 4) break; }
        JSONObject item = new JSONObject();
        try {
            item.put("series_id", id).put("title", title).put("cover", cover(raw.optString("cover", raw.optString("series_cover", ""))))
                    .put("episode_count", Math.min(raw.optInt("episode_cnt", 0), 10000))
                    .put("description", clean(raw.optString("video_desc", raw.optString("series_intro", ""))))
                    .put("tags", tags);
            if (today) item.put("released_today", true);
        } catch (JSONException invalid) { return null; }
        return item;
    }

    private static void addLabel(List<String> labels, String value) {
        String text = clean(value);
        if (!text.isEmpty() && !labels.contains(text)) labels.add(text);
    }

    private static String clean(String value) {
        String text = String.valueOf(value == null ? "" : value).replaceAll("<[^>]*>", "").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String cover(String value) {
        if (value == null || value.length() > 2048) return "";
        if (!value.startsWith("https://")) return "";
        return value.indexOf(' ') >= 0 || value.indexOf('\\') >= 0 ? "" : value;
    }
}
