package io.github.jackotom.hongguo.android;

import org.json.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

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
            group("explicit playback strategy is pinned across CDN refresh and never enters URL lookup or JSON", () -> {
                NetworkGuard.reset();
                PlaybackSources.NativeLookup unrelated = (sid, vid, quality, ceiling, timeoutMs) -> { throw new AssertionError("legacy lookup cannot replace captured playback strategy"); };
                nativeAttach(unrelated);
                host.callback = script -> { throw new AssertionError("explicit playback cannot call the WebView"); };
                int[] calls = {0};
                PlaybackSources.NativeLookup owner = (sid, vid, quality, ceiling, timeoutMs) -> {
                    check(SID.equals(sid) && VID.equals(vid) && quality.equals("720p") && ceiling == 1079, "every attempt preserves playback identity, quality and ceiling");
                    return model(calls[0]++ == 0 ? "expired" : "renewed", quality).put("playbackCeiling", ceiling);
                };
                PlaybackSources.Plan plan = PlaybackSources.resolvePlayback(SID, VID, "720p", 1079, true, false, owner);
                check(calls[0] == 1 && plan.url.endsWith("expired.mp4"), "explicit strategy wins for original playback lookup");
                PlaybackSources.Plan indexed = PlaybackSources.find(plan.url, "720p");
                check(indexed != null && indexed != plan && indexed.json().toString().equals(plan.json().toString()), "plan strategy is private and not placed in the global URL index or serialized");
                NetworkGuard.outcomes.put(plan.url, "eof");
                File target = Files.createTempDirectory("hongguo-native-strategy").resolve("transfer.mp4").toFile();
                try {
                    PlaybackSources.download(plan, target, 1024);
                    check(calls[0] == 2 && NetworkGuard.attempted.equals(Arrays.asList(plan.url, model("renewed", "720p").getString("url"))), "CDN failure refreshes via captured strategy exactly once");
                    check(target.length() > 0, "fresh CDN bytes reach the destination");
                } finally {
                    Files.deleteIfExists(target.toPath()); Files.deleteIfExists(target.getParentFile().toPath());
                }
            });
            group("explicit strategy is captured even when original public page has a valid direct URL", () -> {
                NetworkGuard.reset();
                String original = "https://cdn.qznovelvod.com/web-first.mp4";
                EpisodeResolver.result = original;
                int[] calls = {0};
                PlaybackSources.Plan plan = PlaybackSources.resolvePlayback(SID, VID, "auto", 0, false, false, (sid, vid, quality, ceiling, timeoutMs) -> {
                    calls[0]++;
                    return model("web-refresh", quality);
                });
                check(original.equals(plan.url) && plan.source.equals("web") && calls[0] == 0, "valid public source still wins normal Auto lookup");
                NetworkGuard.outcomes.put(original, "eof");
                File target = Files.createTempDirectory("hongguo-web-strategy").resolve("transfer.mp4").toFile();
                try {
                    PlaybackSources.download(plan, target, 1024);
                    check(calls[0] == 1 && NetworkGuard.attempted.equals(Arrays.asList(original, model("web-refresh", "auto").getString("url"))), "public CDN failure uses captured strategy instead of legacy owner");
                } finally {
                    Files.deleteIfExists(target.toPath()); Files.deleteIfExists(target.getParentFile().toPath());
                }
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
