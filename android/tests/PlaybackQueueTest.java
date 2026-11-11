package io.github.jackotom.hongguo.android;

import org.json.JSONArray;
import org.json.JSONObject;

/** Executes the production queue normalization without creating an Android Context. */
public final class PlaybackQueueTest {
    private static int checks;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void fails(Checked action, String label) throws Exception {
        try { action.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("must fail: " + label);
    }
    private static JSONObject episode(String vid, int index, String availability) throws Exception {
        return new JSONObject().put("vid", vid).put("index", index).put("availability", availability);
    }
    public static void main(String[] args) throws Exception {
        JSONObject fourth = episode("100004", 4, "unknown");
        JSONArray input = new JSONArray().put(episode("100005", 5, "unknown")).put(episode("100002", 2, "available"))
            .put(episode("100001", 1, "available")).put(fourth).put(episode("100003", 3, "available"));
        JSONArray queue = LibraryStore.episodes(input, fourth);
        check(queue.length() == 5, "five real directory rows preserved");
        for (int i = 0; i < queue.length(); i++) check(queue.getJSONObject(i).getInt("index") == i + 1, "queue sorted by episode number");
        check(queue.getJSONObject(3).getString("availability").equals("unknown") && !queue.getJSONObject(3).getBoolean("webAvailable"), "unknown fourth episode allowed through native queue");
        JSONArray missing = LibraryStore.episodes(new JSONArray().put(episode("100001", 1, "available")), fourth);
        check(missing.length() == 2 && missing.getJSONObject(1).getString("vid").equals("100004"), "selected episode included when input omitted it");
        JSONArray duplicates = LibraryStore.episodes(new JSONArray().put(episode("100004", 99, "available")).put(episode("999999", 4, "available")).put(episode("100001", 1, "available")).put(episode("100001", 1, "available")), fourth);
        check(duplicates.length() == 2 && duplicates.getJSONObject(1).getInt("index") == 4, "selected identity wins duplicate vid and index");
        check(LibraryStore.episodes(null, fourth).length() == 1, "legacy single-episode request supported");
        JSONObject normalized = LibraryStore.episode(new JSONObject().put("vid", "1").put("index", 1).put("url", "file:///not-forwarded").put("availability", "unknown"));
        check(normalized.length() == 4 && !normalized.has("url"), "queue is compact metadata, not arbitrary media addresses");
        check(LibraryStore.episode(new JSONObject().put("vid", "2").put("index", 2).put("webAvailable", true)).getString("availability").equals("available"), "legacy available hint normalized");
        for (Object id : new Object[]{123, "", "../../file", "1".repeat(31), JSONObject.NULL}) {
            fails(() -> LibraryStore.episode(new JSONObject().put("vid", id).put("index", 1)), "bad video identifier");
        }
        for (Object index : new Object[]{0, -1, 10001, 1.5, "2", JSONObject.NULL}) {
            fails(() -> LibraryStore.episode(new JSONObject().put("vid", "123").put("index", index)), "bad episode number");
        }
        fails(() -> LibraryStore.episodes(new JSONArray().put(JSONObject.NULL), fourth), "null row");
        fails(() -> LibraryStore.episodes(new JSONArray().put("not-object"), fourth), "non-object row");
        JSONArray oversized = new JSONArray();
        for (int i = 0; i < 1001; i++) oversized.put(episode(Integer.toString(200000 + i), i + 1, "unknown"));
        fails(() -> LibraryStore.episodes(oversized, fourth), "queue longer than 1000");
        JSONArray limit = new JSONArray();
        for (int i = 0; i < 1000; i++) limit.put(episode(Integer.toString(200000 + i), i + 1, "unknown"));
        check(LibraryStore.episodes(limit, limit.getJSONObject(0)).length() == 1000, "exact queue capacity supported");
        fails(() -> LibraryStore.episodes(limit, episode("999999", 1001, "unknown")), "selected item cannot overflow bounded queue");
        System.out.println("PASS PlaybackQueueTest checks=" + checks + " networkRequests=0 androidContextCreated=false");
    }
}
