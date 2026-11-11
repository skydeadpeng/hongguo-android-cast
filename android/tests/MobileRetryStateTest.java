package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/** Actual Activity methods; each resolver/main-loop boundary advances explicitly, without clocks/network. */
public final class MobileRetryStateTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static AdaptiveActivityHarness fresh() throws Exception {
        PlaybackSources.reset(); return new AdaptiveActivityHarness();
    }
    private static void failEveryStage(AdaptiveActivityHarness player) {
        for (String quality : new String[]{"1080p", "720p", "auto"})
            player.cache.failures.put(quality, new IOException("片源响应 HTTP 403"));
    }
    private static void waiting(AdaptiveActivityHarness player, int selected, String boundary) {
        check(player.errors == 0 && player.dialog == null, boundary + ": no premature terminal/error dialog");
        check(!player.completed && player.current == selected, boundary + ": no ended flag or auto episode advance");
        check(!player.status.text.contains("已播完"), boundary + ": no misleading episode/series-ended status");
    }
    private static void nextCallback(AdaptiveActivityHarness player) {
        check(!player.main.callbacks.isEmpty(), "expected queued real Activity main callback");
        player.main.callbacks.removeFirst().run();
    }
    public static void main(String[] arguments) throws Exception {
        AdaptiveActivityHarness exhausted = fresh(); failEveryStage(exhausted); exhausted.autoplay = true;
        exhausted.loadAt(1, null, 37000, true);
        waiting(exhausted, 1, "loadAt submitted, resolver not run");
        check(exhausted.resolver.pending.size() == 1 && exhausted.cache.fetches.isEmpty(), "preparation really waits in resolver queue");
        check(exhausted.status.text.equals("正在连接片源") && exhausted.resumeAt == 37000, "request keeps connecting status and desired resume position");
        exhausted.resolver.flush();
        waiting(exhausted, 1, "all resolver attempts finished, main loop not delivered");
        check(exhausted.cache.fetches.equals(Arrays.asList("v2:1080p", "v2:720p", "v2:auto")), "actual coordinator exhausted exactly three qualities of same episode");
        check(exhausted.main.callbacks.size() == 4, "three progress callbacks precede sole terminal callback");
        String[] stages = {"1080p", "720p", "自动"};
        for (int stage = 0; stage < stages.length; stage++) {
            nextCallback(exhausted);
            waiting(exhausted, 1, "main progress " + stages[stage]);
            check(exhausted.status.text.contains(stages[stage]), "actual stageProgress identifies " + stages[stage]);
            check(stage == 0 || exhausted.status.text.contains("自动降低至"), "lowering remains preparation, not ended");
        }
        check(exhausted.main.callbacks.size() == 1, "only final exhausted outcome remains after all progress");
        nextCallback(exhausted);
        check(exhausted.errors == 1 && exhausted.dialog != null, "real playbackError runs once only after last stage exhausted");
        check(exhausted.current == 1 && !exhausted.completed, "final failure never skips selected episode despite autoplay");
        check(exhausted.status.text.equals("片源响应 HTTP 403"), "final error preserves actual cause rather than claiming ended");
        check(exhausted.pendingMedia.isEmpty() && exhausted.cache.protectedKeys.isEmpty(), "exhaustion releases every temporary stage reservation");
        exhausted.main.flush(); exhausted.resolver.flush(); exhausted.cacheWorker.flush(); exhausted.main.flush();
        check(exhausted.errors == 1 && exhausted.current == 1, "drained queues do not repeat terminal outcome or auto-skip");

        AdaptiveActivityHarness pendingReplacement = fresh();
        pendingReplacement.loadAt(0, null, 12000); Worker.Job oldPending = pendingReplacement.resolver.pending.peekFirst();
        pendingReplacement.loadAt(1, null, 25000, true);
        check(oldPending.isCancelled(), "new load cancels queued previous Future through real loadAtInternal");
        waiting(pendingReplacement, 1, "new generation replaces pending resolver");
        pendingReplacement.resolver.flush();
        check(PlaybackSources.calls.equals(Collections.singletonList("resolve:v2:1080p")), "cancelled queued old episode never reaches transport boundary");
        pendingReplacement.main.flush(); pendingReplacement.video.complete(1080, 1920);
        check(pendingReplacement.errors == 0 && pendingReplacement.current == 1 && pendingReplacement.video.position == 25000,
            "only replacement generation publishes and prepares its requested episode/position");

        AdaptiveActivityHarness lateFailure = fresh(); failEveryStage(lateFailure);
        lateFailure.loadAt(0, null, 0); lateFailure.resolver.flush();
        int staleCallbacks = lateFailure.main.callbacks.size();
        check(staleCallbacks == 4, "old generation has three progress plus terminal already queued");
        lateFailure.cache.failures.clear(); lateFailure.loadAt(2, null, 61000);
        for (int i = 0; i < staleCallbacks; i++) {
            nextCallback(lateFailure);
            waiting(lateFailure, 2, "stale queued progress/error callback " + i);
            check(lateFailure.status.text.equals("正在连接片源"), "old stage cannot replace new generation loading status");
        }
        lateFailure.resolver.flush(); lateFailure.main.flush(); lateFailure.video.complete(1080, 1920);
        check(lateFailure.errors == 0 && lateFailure.current == 2 && lateFailure.video.position == 61000,
            "already-queued old terminal callback cannot terminate successful new episode");

        AdaptiveActivityHarness cancelled = fresh(); failEveryStage(cancelled);
        cancelled.loadAt(0, null, 0); cancelled.resolver.flush();
        cancelled.destroyed = true; cancelled.generation++; cancelled.resolution.cancel(true); cancelled.discardPendingMedia();
        cancelled.main.flush();
        check(cancelled.errors == 0 && cancelled.dialog == null && cancelled.video.uri == null,
            "destroy/cancellation generation blocks late progress, media publication and terminal dialog");
        check(cancelled.pendingMedia.isEmpty() && cancelled.cache.protectedKeys.isEmpty(), "cancelled request leaves no reservation");

        AdaptiveActivityHarness success = fresh(); success.cache.failures.put("1080p", new IOException("片源响应 HTTP 403"));
        success.requestedSpeed = 1.5f; success.loadAt(1, null, 47000, true);
        waiting(success, 1, "success chain before resolver"); success.resolver.flush();
        waiting(success, 1, "720 success resolved, UI still queued");
        check(success.cache.fetches.equals(Arrays.asList("v2:1080p", "v2:720p")) && success.main.callbacks.size() == 3,
            "successful 720 stops stage search and queues two progress plus publish");
        nextCallback(success); waiting(success, 1, "success 1080 progress");
        nextCallback(success); waiting(success, 1, "success 720 progress");
        nextCallback(success); waiting(success, 1, "published 720 URI, decoder not prepared yet");
        check(!success.prepared && success.video.uri != null && success.actualQuality.equals("720p"),
            "source publication waits for decoder and reports actual lower quality without error");
        success.video.complete(720, 1280); success.main.flush();
        check(success.prepared && success.errors == 0 && success.dialog == null && !success.completed && success.current == 1,
            "decoder prepare succeeds on same episode without terminal/end state");
        check(success.video.position == 47000 && success.resumeAt == 47000 && success.userPaused && !success.video.playing,
            "successful retry preserves resume point and explicit pause");
        check(success.requestedSpeed == 1.5f && success.actualSpeed == 1.5f && success.quality.equals("1080p") && success.actualQuality.equals("720p"),
            "successful lower source preserves preferred quality and confirmed speed");
        check(PlayerOptions.saved.isEmpty() && success.status.text.contains("720p") && success.status.text.contains("720×1280"),
            "fallback is actual stage, not persistent preference rewrite or fabricated resolution");
        check(success.pendingMedia.isEmpty() && success.cache.protectedKeys.size() == 1 && success.cache.protectedKeys.containsKey("v2:720p"),
            "only adopted 720 cache key remains protected");
        System.out.println("MOBILE_RETRY_STATE_OK checks=" + checks + " activityMethods=real terminalHandler=real coordinator=real resolverAndMain=stepwise boundary=UI_media_cache_transport_doubles networkRequests=0 deviceTests=not_run emulator=not_used");
    }
}
