package io.github.jackotom.hongguo.android;

import org.json.*;

/** The native-UI lookup hook owns resolution without a WebView page; offline stubs only. */
public final class PlaybackSourcesNativeLookupTest {
    static int passed, failed, checks;
    static final String SID = "880001", VID = "880006";
    interface Checked { void run() throws Exception; }
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static void group(String name, Checked task) {
        try { task.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable error) { failed++; System.out.println("FAIL " + name + ": " + error.getClass().getSimpleName() + " " + error.getMessage()); }
        finally { Thread.interrupted(); }
    }
    static JSONObject model(String name, String quality) throws Exception {
        JSONObject row = new JSONObject().put("url", "https://cdn.qznovelvod.com/native-" + name + ".mp4")
            .put("vid", VID).put("seriesId", SID).put("source", "app").put("requestedQuality", quality)
            .put("userAgent", DanmakuSigner.USER_AGENT).put("referer", "");
        if (!quality.equals("auto")) {
            int size = Integer.parseInt(quality.substring(0, quality.length() - 1));
            row.put("width", size).put("height", size * 2);
        }
        return row;
    }
    public static void main(String[] unused) throws Exception {
        MainActivity host = new MainActivity();
        PlaybackSources.attach(host);
        try {
            group("native lookup replaces the WebView page path", () -> {
                host.callback = script -> { throw new AssertionError("native path must not touch the WebView"); };
                nativeAttach((sid, vid, quality, ceiling, timeoutMs) -> {
                    check(SID.equals(sid) && VID.equals(vid) && quality.equals("1080p"), "lookup identity");
                    check(ceiling == 0, "unbounded request has zero ceiling");
                    check(timeoutMs > 0 && timeoutMs <= 28000, "bounded timeout");
                    return model("primary", quality);
                });
                PlaybackSources.Plan plan = PlaybackSources.resolve(SID, VID, "1080p");
                check(plan.url.endsWith("native-primary.mp4") && plan.requestedQuality.equals("1080p") && plan.width == 1080, "native plan fields");
                check(plan.source.equals("app"), "app source plan");
            });
            group("bounded ceiling reaches the lookup and validates the echo", () -> {
                nativeAttach((sid, vid, quality, ceiling, timeoutMs) -> {
                    check(ceiling == 1079, "ceiling passthrough, got " + ceiling);
                    JSONObject row = model("bounded", quality).put("playbackCeiling", ceiling);
                    int size = Integer.parseInt(quality.substring(0, quality.length() - 1));
                    row.put("width", size).put("height", size * 2);
                    return row;
                });
                PlaybackSources.Plan plan = PlaybackSources.resolvePlayback(SID, VID, "720p", 1079, true, false);
                check(plan.url.endsWith("bounded.mp4") && plan.playbackCeiling == 1079, "bounded plan echo");
            });
            group("quality-unavailable from the lookup stays quality-recoverable", () -> {
                nativeAttach((sid, vid, quality, ceiling, timeoutMs) -> { throw new AppMediaSource.AppSourceException("本集暂无尺寸可确认且满足 720p 的片源", "QUALITY_UNAVAILABLE"); });
                Exception failure = null;
                try { PlaybackSources.resolve(SID, VID, "720p"); } catch (Exception error) { failure = error; }
                check(failure != null && PlaybackSources.canLowerQuality(failure), "QUALITY_UNAVAILABLE can lower");
            });
            group("invalid response is terminal for quality lowering", () -> {
                nativeAttach((sid, vid, quality, ceiling, timeoutMs) -> { throw new AppMediaSource.AppSourceException("App片源模型响应格式无效", "APP_INVALID_RESPONSE"); });
                Exception failure = null;
                try { PlaybackSources.resolve(SID, VID, "720p"); } catch (Exception error) { failure = error; }
                check(failure != null && !PlaybackSources.canLowerQuality(failure) && !PlaybackSources.canRecoverPlayback(failure), "APP_INVALID_RESPONSE is terminal: no auto retry or lowering");
            });
            group("detach restores the WebView page path", () -> {
                PlaybackSources.NativeLookup detached = (sid, vid, quality, ceiling, timeoutMs) -> { throw new AssertionError("detached lookup must not run"); };
                nativeAttach(detached);
                PlaybackSources.detachNative(detached);
                int[] calls = { 0 };
                host.callback = script -> {
                    try {
                        calls[0]++;
                        JSONArray arguments = new JSONArray("[" + script.substring(script.lastIndexOf('(') + 1, script.length() - 1) + "]");
                        JSONObject envelope = new JSONObject().put("requestId", arguments.getString(0)).put("seriesId", SID).put("vid", VID)
                            .put("requestedQuality", "1080p").put("playInfo", model("page", "1080p"));
                        PlaybackSources.result(envelope);
                    } catch (Exception error) { throw new RuntimeException(error); }
                };
                PlaybackSources.Plan plan = PlaybackSources.resolve(SID, VID, "1080p");
                check(plan.url.endsWith("page.mp4") && calls[0] == 1, "page path resumes after detach");
            });
        } finally {
            nativeTeardown();
            PlaybackSources.detach(host);
        }
        System.out.println("NATIVE_LOOKUP_" + (failed == 0 ? "OK" : "FAILED") + " groupsPassed=" + passed + " groupsFailed=" + failed + " checks=" + checks + " networkRequests=0");
        if (failed != 0) throw new AssertionError("native lookup gate failed");
    }
    private static PlaybackSources.NativeLookup active;
    private static void nativeAttach(PlaybackSources.NativeLookup lookup) { active = lookup; PlaybackSources.attachNative(lookup); }
    private static void nativeTeardown() {
        if (active != null) PlaybackSources.detachNative(active);
        active = null;
    }
}
