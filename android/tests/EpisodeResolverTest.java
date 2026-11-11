package io.github.jackotom.hongguo.android;

import org.json.JSONArray;
import org.json.JSONObject;

/** Generated fixtures only: parser and public-URL checks never access a network. */
public final class EpisodeResolverTest {
    private static final String SID = "1234567890123456789", VID = "2345678901234567890";
    private static final String KEY = "player_(series_id)/(vid)/page", ORIGIN = "https://hongguoduanju.com";
    private static int checks;
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void same(String actual, String expected, String label) { check(expected.equals(actual), label + " actual=" + actual); }
    private static JSONObject data() throws Exception {
        return new JSONObject().put("isSuccess", true).put("series_id", SID).put("vid", VID)
                .put("seriesDetail", new JSONObject().put("series_id", SID))
                .put("publicVideoSeo", new JSONObject().put("available", true).put("seriesId", SID).put("itemId", VID));
    }
    private static JSONObject video() throws Exception {
        return new JSONObject().put("@type", "VideoObject").put("url", ORIGIN + "/player/" + SID + "/" + VID)
                .put("contentUrl", "https://media.example.net/v.mp4?Range=0-100&token=a%20b&x=1");
    }
    private static String page(JSONObject data, Object objects, String key) throws Exception {
        String router = new JSONObject().put("loaderData", new JSONObject().put(key, data == null ? JSONObject.NULL : data)).toString();
        return "<script>window._ROUTER_DATA = " + router + ";globalThis.__executed=true;</script>" +
                (objects == null ? "" : "<script type=\"application/ld+json\">" + objects + "</script>");
    }
    private static String page(JSONObject data, Object objects) throws Exception { return page(data, objects, KEY); }
    private static String parsed(JSONObject data, Object objects) throws Exception { return EpisodeResolver.parse(page(data, objects), SID, VID); }
    private static void rejected(String html, String sid, String vid, String code, String label) throws Exception {
        try { EpisodeResolver.parse(html, sid, vid); throw new AssertionError("accepted " + label); }
        catch (EpisodeResolver.ResolverException expected) {
            same(expected.code, code, label); check(expected.getMessage().matches(".*[\\u4e00-\\u9fff].*"), label + " Chinese message");
        }
    }
    private static void rejected(JSONObject data, Object objects, String code, String label) throws Exception { rejected(page(data, objects), SID, VID, code, label); }
    private static JSONObject canonicalMedia() throws Exception {
        return video().put("url", ORIGIN + "/player/" + SID).put("@id", ORIGIN + "/player/" + SID + "#video")
                .put("contentUrl", ORIGIN + "/video/public/" + VID + "/play.mp4?token=a%2Fb&Range=0-100&x=1");
    }
    private static JSONObject laterPlayer() throws Exception {
        JSONObject model = data();
        model.getJSONObject("seriesDetail").put("vid_list", new JSONArray().put("111111").put(VID)).put("accessible_episode_cnt", 1);
        return model;
    }
    private static void canonicalMediaChecks() throws Exception {
        String media = ORIGIN + "/video/public/" + VID + "/play.mp4?token=a%2Fb&x=1";
        for (String key : new String[]{KEY, "player_(series_id)/page"})
            same(EpisodeResolver.parse(page(laterPlayer(), canonicalMedia(), key), SID, VID), media, "canonical later identity ignores preview gate");
        for (Object list : new Object[]{new JSONArray().put(VID).put("111111"), new JSONArray().put("111111").put(VID), new JSONArray(), JSONObject.NULL}) {
            JSONObject model = data(); model.getJSONObject("seriesDetail").put("vid_list", list);
            same(parsed(model, canonicalMedia()), media, "canonical first/later/missing list with exact media identity");
        }
        for (Object id : new Object[]{JSONObject.NULL, ORIGIN + "/player/" + SID + "#video", ORIGIN + "/player/" + SID + "/" + VID + "#video"}) {
            same(parsed(laterPlayer(), canonicalMedia().put("@id", id).put("contentUrl", ORIGIN + ":443/video/public/" + VID + "/play.mp4?token=a%2fb+Z&amp;%52ange=0&amp;x=%2520#media")),
                    ORIGIN + "/video/public/" + VID + "/play.mp4?token=a%2fb+Z&x=%2520", "canonical @id fragments and signed query");
        }
        for (String key : new String[]{"series_id", "vid"}) rejected(data().put(key, "111111"), canonicalMedia(), "IDENTITY_MISMATCH", "canonical wrong router " + key);
        rejected(data().put("seriesDetail", new JSONObject().put("series_id", "111111")), canonicalMedia(), "IDENTITY_MISMATCH", "canonical wrong detail SID");
        for (String key : new String[]{"seriesId", "itemId"}) {
            JSONObject model = data(); model.getJSONObject("publicVideoSeo").put(key, "111111");
            rejected(model, canonicalMedia(), "IDENTITY_MISMATCH", "canonical wrong SEO " + key);
        }
        for (Object available : new Object[]{false, JSONObject.NULL, 1, "true"}) {
            JSONObject model = data(); model.getJSONObject("publicVideoSeo").put("available", available);
            rejected(model, canonicalMedia(), "EPISODE_UNAVAILABLE", "canonical unavailable");
        }
        for (Object content : new Object[]{ORIGIN + "/video/public/111111/play.mp4", ORIGIN + "/video/public/" + VID + "0/play.mp4",
                ORIGIN + "/video/public/" + VID, ORIGIN + "/video/public/" + VID + "%2Fplay.mp4", ORIGIN + "/video/public/" + VID + "/../111111/play.mp4",
                ORIGIN + "/other/" + VID + "/play.mp4", "https://cdn.example.net/video/public/" + VID + "/play.mp4",
                "http://hongguoduanju.com/video/public/" + VID + "/play.mp4", "https://user:pass@hongguoduanju.com/video/public/" + VID + "/play.mp4",
                ORIGIN + ":8443/video/public/" + VID + "/play.mp4", ORIGIN + "/video/public/" + VID + "/a b",
                ORIGIN + "/video/public/" + VID + "/a\\b", ORIGIN + "/video/public/" + VID + "/a?%zz=1", JSONObject.NULL, 123}) {
            rejected(laterPlayer(), canonicalMedia().put("contentUrl", content), "NO_PLAYABLE_URL", "canonical invalid/nonmatching media " + content);
        }
        for (String url : new String[]{ORIGIN + "/player/111111", ORIGIN + "/player/" + SID + "?vid=111111", "/player/" + SID,
                "http://hongguoduanju.com/player/" + SID, "https://user:pass@hongguoduanju.com/player/" + SID})
            rejected(laterPlayer(), canonicalMedia().put("url", url), "NO_PLAYABLE_URL", "canonical wrong player " + url);
        for (String id : new String[]{ORIGIN + "/player/111111#video", ORIGIN + "/player/" + SID + "/111111#video", ORIGIN + "/player/" + SID + "?vid=111111#video"})
            rejected(laterPlayer(), canonicalMedia().put("@id", id), "NO_PLAYABLE_URL", "canonical wrong @id " + id);
        JSONObject first = data(); first.getJSONObject("seriesDetail").put("vid_list", new JSONArray().put(VID).put("111111"));
        for (String url : new String[]{ORIGIN + "/player/" + SID, ORIGIN + "/player/" + SID + "/" + VID})
            rejected(first, canonicalMedia().put("url", url).put("contentUrl", ORIGIN + "/video/public/111111/play.mp4"), "NO_PLAYABLE_URL", "wrong media VID cannot borrow first-episode compatibility");
        same(parsed(first, video().put("url", ORIGIN + "/player/" + SID)), "https://media.example.net/v.mp4?token=a%20b&x=1", "legacy first CDN unchanged");
        JSONObject recommended = canonicalMedia().put("contentUrl", ORIGIN + "/video/public/111111/play.mp4");
        rejected(laterPlayer(), recommended, "NO_PLAYABLE_URL", "same series recommended media is not current");
        same(parsed(laterPlayer(), new JSONObject().put("@graph", new JSONArray().put(recommended).put(canonicalMedia()))), media, "skip recommended canonical and find exact current media");
    }
    public static void main(String[] args) throws Exception {
        same(parsed(data(), video()), "https://media.example.net/v.mp4?token=a%20b&x=1", "signed query kept Range removed");
        same(parsed(data(), video().put("contentUrl", "HTTPS://Media.Example.Net:443/a?%52aNgE=0&amp;token=a%2Fb+Z&amp;X=%2520#part")),
                "https://media.example.net/a?token=a%2Fb+Z&X=%2520", "encoded Range, entity, case, fragment");
        same(parsed(data(), video().put("contentUrl", "https://cdn.example.net")), "https://cdn.example.net/", "empty path normalized");
        same(parsed(data(), video().put("contentUrl", "https://cdn.example.net/a?range=1")), "https://cdn.example.net/a", "only Range query removed");
        same(parsed(data(), video().put("contentUrl", "https://cdn.example.net/signed//video.mp4?token=a%2Fb")), "https://cdn.example.net/signed//video.mp4?token=a%2Fb", "signed double-slash path unchanged");
        same(parsed(data(), video().put("contentUrl", "https://cdn.example.net/a/%2E/b/%2e%2e/c")), "https://cdn.example.net/a/c", "WHATWG encoded dot path normalization");
        same(parsed(data(), video().put("contentUrl", "https://cdn.example.net/a?x=1&range=1&x=2&token=a%2fb")), "https://cdn.example.net/a?x=1&x=2&token=a%2fb", "repeated params retained");
        same(EpisodeResolver.parse(page(data(), video(), "player_(series_id)/page"), SID, VID), "https://media.example.net/v.mp4?token=a%20b&x=1", "canonical loader accepted");
        JSONObject fourth = data().put("vid", "1004").put("publicVideoSeo", new JSONObject().put("available", true).put("seriesId", SID).put("itemId", "1004"));
        fourth.getJSONObject("seriesDetail").put("accessible_episode_cnt", 3).put("vid_list", new JSONArray().put("1001").put("1002").put("1003").put("1004"));
        same(EpisodeResolver.parse(page(fourth, video().put("url", ORIGIN + "/player/" + SID + "/1004")), SID, "1004"), "https://media.example.net/v.mp4?token=a%20b&x=1", "fourth resolved without preview gate");
        for (String key : new String[]{"series_id", "vid"}) rejected(data().put(key, "333333"), video(), "IDENTITY_MISMATCH", "wrong " + key);
        rejected(null, video(), "IDENTITY_MISMATCH", "null model");
        rejected(data().put("isSuccess", false), video(), "IDENTITY_MISMATCH", "business false");
        rejected(data().put("series_id", 123), video(), "IDENTITY_MISMATCH", "numeric series id");
        rejected(data().put("vid", Long.parseLong(VID)), video(), "IDENTITY_MISMATCH", "numeric vid");
        rejected(data().put("seriesDetail", new JSONObject().put("series_id", "333333")), video(), "IDENTITY_MISMATCH", "detail mismatch");
        rejected(data().put("seriesDetail", JSONObject.NULL), video(), "IDENTITY_MISMATCH", "missing detail");
        rejected(data().put("publicVideoSeo", JSONObject.NULL), video(), "IDENTITY_MISMATCH", "missing seo");
        for (String key : new String[]{"seriesId", "itemId"}) {
            JSONObject model = data(); model.getJSONObject("publicVideoSeo").put(key, "333333");
            rejected(model, video(), "IDENTITY_MISMATCH", "wrong seo " + key);
        }
        for (Object available : new Object[]{false, JSONObject.NULL, "true", 1}) {
            JSONObject model = data(); model.getJSONObject("publicVideoSeo").put("available", available);
            rejected(model, video(), "EPISODE_UNAVAILABLE", "not boolean available " + available);
        }
        JSONObject missingAvailable = data(); missingAvailable.getJSONObject("publicVideoSeo").remove("available");
        rejected(missingAvailable, video(), "EPISODE_UNAVAILABLE", "missing available");
        for (Object url : new Object[]{ORIGIN + "/player/333333/" + VID, "https://foreign.example.net/player/" + SID + "/" + VID,
                "/player/" + SID + "/" + VID, "https://a:b@hongguoduanju.com/player/" + SID + "/" + VID, JSONObject.NULL}) {
            rejected(data(), video().put("url", url), "NO_PLAYABLE_URL", "foreign video identity " + url);
        }
        rejected(data(), video().put("@id", ORIGIN + "/player/" + SID + "/333333"), "NO_PLAYABLE_URL", "wrong @id");
        rejected(data(), video().put("@type", "Article"), "NO_PLAYABLE_URL", "not video");
        rejected(data(), video().put("@type", new JSONArray().put("VideoObject")), "NO_PLAYABLE_URL", "type array not accepted by JS parser");
        JSONObject first = data(); first.getJSONObject("seriesDetail").put("vid_list", new JSONArray().put(VID).put("333333"));
        JSONObject canonical = video().put("url", ORIGIN + "/player/" + SID).put("@id", ORIGIN + "/player/" + SID + "#video");
        same(parsed(first, canonical), "https://media.example.net/v.mp4?token=a%20b&x=1", "independent canonical first evidence");
        for (Object list : new Object[]{JSONObject.NULL, new JSONArray(), new JSONArray().put("333333").put(VID), new JSONArray().put(Long.parseLong(VID))}) {
            JSONObject model = data(); model.getJSONObject("seriesDetail").put("vid_list", list);
            rejected(model, canonical, "NO_PLAYABLE_URL", "canonical evidence missing or wrong " + list);
        }
        rejected(first, video().put("url", ORIGIN + "/player/" + SID + "?vid=333333"), "NO_PLAYABLE_URL", "canonical query disallowed");
        rejected(first, canonical.put("@id", ORIGIN + "/player/333333#video"), "NO_PLAYABLE_URL", "canonical foreign @id");
        canonicalMediaChecks();
        same(parsed(data(), new JSONArray().put(JSONObject.NULL).put(new JSONObject().put("@graph", new JSONArray().put(new JSONObject().put("@type", "Article")).put(new JSONArray().put(video()))))), "https://media.example.net/v.mp4?token=a%20b&x=1", "LD array graph recursion");
        rejected(data(), new JSONObject().put("recommendation", video()), "NO_PLAYABLE_URL", "do not crawl arbitrary recommendation object");
        same(EpisodeResolver.parse(page(data(), null) + "<SCRIPT TYPE='application/ld+json'>" + video() + "</SCRIPT>", SID, VID), "https://media.example.net/v.mp4?token=a%20b&x=1", "case insensitive script and single quote type");
        rejected(page(data(), null) + "<script type=\"text/javascript\">" + video() + "</script><script type=\"application/ld+json\">bad</script>", SID, VID, "NO_PLAYABLE_URL", "skip invalid non-LD JSON");
        for (Object media : new Object[]{JSONObject.NULL, 1, "http://cdn.example.net/a", "file:///x", "javascript:alert(1)", "https://localhost/a", "https://a.local/a", "https://a.internal/a", "https://a.lan/a", "https://a.home/a", "https://a.test/a", "https://a.invalid/a", "https://a.example/a", "https://a.onion/a", "https://127.0.0.1/a", "https://0x7f000001/a", "https://[::1]/a", "https://8.8.8.8/a", "https://0x8.0x8.0x8.0x8/a", "https://user:pass@cdn.example.net/a", "https://cdn.example.net:1234/a", "https://-bad.example.net/a", "https://cdn.example.net/a b", "https://cdn.example.net/a\\b", "https://cdn.example.net/a\u00a0b", "https://cdn.example.net/a\uFEFFb", "https://cdn.example.net/a?%zz=1", "https://cdn.example.net/a?%C3=1", "https://cdn.example.net/" + "x".repeat(16_384), "not-a-url"}) {
            rejected(data(), video().put("contentUrl", media), "INVALID_MEDIA_URL", "bad media " + media.toString().substring(0, Math.min(70, media.toString().length())));
        }
        for (int cp : new int[]{0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680,
                0x2000, 0x2003, 0x2007, 0x2009, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF, '\\'}) {
            String media = "https://cdn.example.net/video" + new String(Character.toChars(cp)) + ".mp4?token=a%20b";
            rejected(data(), video().put("contentUrl", media), "INVALID_MEDIA_URL", "Unicode whitespace/backslash U+" + Integer.toHexString(cp));
        }
        for (String input : new String[]{null, "", " ", "x".repeat(8 * 1024 * 1024 + 1), "<html/>", "_ROUTER_DATA={\"x\":{", "_ROUTER_DATA={\"x\":\"unfinished", "_ROUTER_DATA={x:1}", "_ROUTER_DATA={\"x\":undefined}", "_ROUTER_DATA={\"x\":01}", "_ROUTER_DATA={\"x\":1,}", "_ROUTER_DATA={\"x\":'bad'}", "_ROUTER_DATA={/*a*/\"x\":1}"}) {
            rejected(input, SID, VID, "SOURCE_INVALID", "invalid router");
        }
        for (String invalid : new String[]{null, "", "a", "1".repeat(31), "../1"}) {
            rejected(page(data(), video()), invalid, VID, "INVALID_INPUT", "invalid sid");
            rejected(page(data(), video()), SID, invalid, "INVALID_INPUT", "invalid vid");
        }
        JSONObject escaped = data().put("title", "a } { \\ \" 嵌套").put("extra", new JSONArray().put(new JSONObject().put("quote", "}")));
        same(parsed(escaped, video()), "https://media.example.net/v.mp4?token=a%20b&x=1", "balanced braces escaped quotes no eval");
        JSONObject decoy = new JSONObject().put("loaderData", new JSONObject().put("recommendation", new JSONObject().put("vid_list", new JSONArray().put(VID))).put(KEY, data()));
        same(EpisodeResolver.parse("<script>_ROUTER_DATA=" + decoy + "</script><script type=\"application/ld+json\">" + video() + "</script>", SID, VID), "https://media.example.net/v.mp4?token=a%20b&x=1", "no regex recommendation vid list selection");
        System.out.println("PASS EpisodeResolverTest checks=" + checks + " networkRequests=0");
    }
}
