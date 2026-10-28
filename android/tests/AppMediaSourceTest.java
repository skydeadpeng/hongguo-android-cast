package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.*;

/** Selection, variants and error codes over fixture video_models; no network or device registration. */
public final class AppMediaSourceTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static String url(String path) { return "https://v.douyinvod.com/" + path + ".mp4?sig=1"; }
    static String encoded(String path) { return Base64.getEncoder().encodeToString(url(path).getBytes(StandardCharsets.UTF_8)); }
    static final String KEY_A = "lbAT91q-EfJfvxDvaZYS6myWK95hlC3ZYpcyxki8NMFLvVyqRL1cEUs7KioFLGs";
    static final String KEY_B = "r7Apy1KyK8p6hDTJeYIyyHiOBfJ5iAPxUb4e8FC8HPdXsUCmWLFAEUs7KioFLGs";
    static JSONObject row(String mainUrl, String codec, String quality, int w, int h, String spade) throws Exception {
        JSONObject row = new JSONObject().put("main_url", mainUrl).put("codec_type", codec).put("quality_desc", quality)
            .put("vwidth", w).put("vheight", h);
        if (spade != null) row.put("spade_a", spade);
        return row;
    }
    static JSONObject model(JSONObject... rows) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject row : rows) list.put(row);
        return new JSONObject().put("vid", "880006").put("video_model", new JSONObject().put("video_list", list));
    }
    static JSONObject resolve(JSONObject model, String quality, Integer ceiling) throws Exception {
        return new AppMediaSource(vid -> model).resolve("880006", quality, ceiling);
    }
    static void rejected(JSONObject model, String quality, Integer ceiling, String code) throws Exception {
        try { resolve(model, quality, ceiling); throw new AssertionError("accepted " + code); }
        catch (AppMediaSource.AppSourceException expected) { check(code.equals(expected.code), "code " + code + " got " + expected.code); }
    }
    public static void main(String[] unused) throws Exception {
        // auto: codecRank asc then pixels desc → 1080p h264 first, 480p second, hevc third; unsupported codec dropped.
        JSONObject auto = resolve(model(
            row(encoded("hevc720"), "bytevc1", "720p", 1280, 720, KEY_B),
            row(encoded("h264-480"), "h264", "480p", 854, 480, null),
            row(encoded("h264-1080"), "h264", "1080p", 1920, 1080, KEY_A),
            row(encoded("av1"), "av01", "1080p", 1920, 1080, null)), "auto", null);
        check(auto.getString("url").equals(url("h264-1080")), "auto primary is h264 1080p");
        check(auto.getString("contentKey").equals("0123456789abcdef0123456789abcdef"), "primary key");
        check(auto.getString("userAgent").equals(DanmakuSigner.USER_AGENT) && auto.getString("referer").isEmpty(), "registered headers");
        JSONArray autoVariants = auto.getJSONArray("variants");
        check(autoVariants.length() == 2, "variants exclude rejected codec");
        check(autoVariants.getJSONObject(0).getString("url").equals(url("h264-480")) && autoVariants.getJSONObject(0).isNull("contentKey"), "no spade → null key");
        check(autoVariants.getJSONObject(1).getString("url").equals(url("hevc720")), "hevc ranked after both h264 rows");
        check(auto.getString("quality").equals("1080p") && auto.getString("requestedQuality").equals("auto"), "quality labels");
        // bounded: ceiling 719 keeps only confirmed ≤719 short edge, sorted ascending
        JSONObject bounded = resolve(model(
            row(encoded("h264-1080"), "h264", "1080p", 1920, 1080, KEY_A),
            row(encoded("h264-480"), "h264", "480p", 854, 480, null),
            row(encoded("hevc-720"), "hevc", "720p", 1280, 720, KEY_B)), "auto", 719);
        check(bounded.getString("url").equals(url("h264-480")), "bounded keeps lowest within ceiling");
        check(bounded.getInt("playbackCeiling") == 719, "ceiling echoed");
        // fixed 720p: only confirmed ≥720 rows, min size ascending
        JSONObject fixed = resolve(model(
            row(encoded("h264-1080"), "h264", "1080p", 1920, 1080, KEY_A),
            row(encoded("h264-720"), "h264", "720p", 1280, 720, KEY_B)), "720p", null);
        check(fixed.getString("url").equals(url("h264-720")), "fixed 720p picks exact size");
        rejected(model(row(encoded("h264-480"), "h264", "480p", 854, 480, null)), "720p", null, "QUALITY_UNAVAILABLE");
        // dedupe by url:contentKey; backup_url_list participates
        JSONObject multi = row(encoded("dup"), "h264", "1080p", 1920, 1080, KEY_A);
        multi.put("backup_url", encoded("dup")).put("backup_url_list", new JSONArray().put(encoded("backup1")).put(encoded("backup1")));
        JSONObject variants = resolve(model(multi), "auto", null);
        check(variants.getJSONArray("variants").length() == 1 && variants.getJSONArray("variants").getJSONObject(0).getString("url").equals(url("backup1")), "dedupe url:contentKey");
        // unsupported media domain is skipped; nothing left → INVALID_RESPONSE
        JSONObject foreign = new JSONObject().put("main_url", Base64.getEncoder().encodeToString("https://evil.example.com/x.mp4".getBytes(StandardCharsets.UTF_8)))
            .put("codec_type", "h264").put("quality_desc", "1080p").put("vwidth", 1920).put("vheight", 1080);
        rejected(model(foreign), "auto", null, "INVALID_RESPONSE");
        // provider failure mapping (plain transport IOException → APP_INVALID_RESPONSE)
        try { new AppMediaSource(vid -> { throw new IOException("x"); }).resolve("880006", "auto", null); throw new AssertionError("no error"); }
        catch (AppMediaSource.AppSourceException expected) { check("APP_INVALID_RESPONSE".equals(expected.code), "provider failure mapped, got " + expected.code); }
        // invalid input
        try { new AppMediaSource(vid -> new JSONObject()).resolve("abc", "auto", null); throw new AssertionError("no error"); }
        catch (AppMediaSource.AppSourceException expected) { check("INVALID_INPUT".equals(expected.code), "invalid vid code"); }
        System.out.println("APP_MEDIA_SOURCE_OK checks=" + checks + " networkRequests=0");
    }
}
