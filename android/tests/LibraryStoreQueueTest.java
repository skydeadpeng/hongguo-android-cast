package io.github.jackotom.hongguo.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Pure queue/episode tests. Generated Android stubs only satisfy unused persistence signatures. */
public final class LibraryStoreQueueTest {
    private static int checks;
    private static JSONObject episode(String vid, int index) throws Exception {
        return new JSONObject().put("vid", vid).put("index", index).put("webAvailable", false).put("availability", "unknown");
    }
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    private static void rejected(JSONObject value) throws Exception {
        try { LibraryStore.episode(value); throw new AssertionError("accepted invalid episode " + value); }
        catch (JSONException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        JSONObject selected = episode("2", 2);
        JSONObject unknown = LibraryStore.episode(selected);
        check("unknown".equals(unknown.getString("availability")), "unknown availability preserved");
        check(!unknown.getBoolean("webAvailable"), "unknown is not an availability lock");
        JSONObject available = LibraryStore.episode(episode("1", 1).put("availability", "available"));
        check("available".equals(available.getString("availability")) && available.getBoolean("webAvailable"), "available preserved");
        JSONObject legacy = episode("3", 3); legacy.remove("availability"); legacy.put("webAvailable", true);
        check("available".equals(LibraryStore.episode(legacy).getString("availability")), "legacy available compatible");

        JSONArray queue = LibraryStore.episodes(new JSONArray().put(episode("3", 3)).put(episode("1", 1))
                .put(episode("2", 8)).put(episode("9", 2)).put(episode("4", 4)).put(episode("3", 5)), selected);
        check(queue.length() == 4, "vid and index deduplicated");
        for (int i = 0; i < 4; i++) check(queue.getJSONObject(i).getInt("index") == i + 1, "ascending order " + i);
        check("2".equals(queue.getJSONObject(1).getString("vid")), "current episode takes priority");
        check(queue.getJSONObject(1).length() == 4, "queue contains compact fields only");
        check(LibraryStore.episodes(null, selected).length() == 1, "legacy play falls back to current");
        check(LibraryStore.episodes(new JSONArray(), selected).length() == 1, "empty queue contains current");

        for (Object value : new Object[]{"2", 0, -1, 1.2, 10001, JSONObject.NULL}) rejected(episode("1", 1).put("index", value));
        for (Object value : new Object[]{1, "bad", "1".repeat(31), JSONObject.NULL}) rejected(episode("1", 1).put("vid", value));

        JSONArray thousand = new JSONArray();
        for (int i = 1; i <= 1000; i++) thousand.put(episode(Integer.toString(i), i));
        check(LibraryStore.episodes(thousand, selected).length() == 1000, "1000 valid episodes accepted");
        try { LibraryStore.episodes(thousand, episode("9000", 9000)); throw new AssertionError("1001 merged episodes accepted"); }
        catch (JSONException expected) { checks++; }
        thousand.put(episode("1001", 1001));
        try { LibraryStore.episodes(thousand, selected); throw new AssertionError("1001 input episodes accepted"); }
        catch (JSONException expected) { checks++; }
        try { LibraryStore.episodes(new JSONArray().put("bad"), selected); throw new AssertionError("nonobject episode accepted"); }
        catch (JSONException expected) { checks++; }
        System.out.println("PASS LibraryStoreQueueTest checks=" + checks + " networkRequests=0");
    }
}
