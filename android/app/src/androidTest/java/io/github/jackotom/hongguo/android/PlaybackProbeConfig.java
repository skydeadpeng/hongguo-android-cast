package io.github.jackotom.hongguo.android;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure QA configuration. No arbitrary source, series, URL, interval, or path is accepted. */
final class PlaybackProbeConfig {
    static final String SERIES = "7703410222160694296";
    private static final String[] VIDS = {
        "7703412149342719001", "7703412063502093337", "7703412110843186201",
        "7703412039816842265", "7703412145559440409", "7703412101368269849",
        "7703412128635423769", "7703412191394810905", "7703412032455855129",
        "7703412076667997209"
    };
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
        "isolatedQaAvd", "qaCommit", "batchTenEpisodes", "episodeIndex", "episodeVid"));

    final boolean batch;
    final int episodeIndex, schema, minimumSurfaceObservations, minimumStateObservations, minimumFrameChanges;
    final String vid, outputRelativePath, scope, testScope, acceptanceMode, continuedStage, finalScreenshot, passReason;
    final long advanceMs, budgetMs, continuousBudgetMs;

    private PlaybackProbeConfig(boolean batch, int index) {
        this.batch = batch;
        episodeIndex = index;
        vid = VIDS[index - 1];
        schema = batch ? 2 : 1;
        advanceMs = batch ? 5_000 : 30_000;
        budgetMs = batch ? 180_000 : 240_000;
        continuousBudgetMs = batch ? 15_000 : 45_000;
        // Legacy already requires first and final surface copies; do not weaken its interval.
        minimumSurfaceObservations = batch ? 4 : 2;
        minimumStateObservations = batch ? 10 : 2;
        minimumFrameChanges = batch ? 2 : 1;
        outputRelativePath = batch
            ? String.format(Locale.ROOT, "playback-qa/episode-%02d", index) : "playback-qa";
        testScope = batch ? "ten_episode_opening_5s_production_player_component"
            : "production_player_component_30s";
        scope = batch ? "production_player_component_ten_episode_opening"
            : "production_player_component_not_browse_ui";
        acceptanceMode = batch ? "ten_episode_opening_5s" : "single_episode_continuous_30s";
        continuedStage = batch ? "continued_5s" : "continued_30s";
        finalScreenshot = batch ? "after-5s.png" : "after-30s.png";
        passReason = batch ? "rendered_and_advanced_5s" : "rendered_and_advanced_30s";
    }

    static PlaybackProbeConfig original() { return new PlaybackProbeConfig(false, 1); }

    static PlaybackProbeConfig parse(Map<String, ?> args) {
        for (Map.Entry<String, ?> entry : args.entrySet()) {
            if (!KEYS.contains(entry.getKey())) throw invalid("unsupported_probe_argument");
            if (!(entry.getValue() instanceof String)) throw invalid("non_string_probe_argument");
        }
        String batch = (String) args.get("batchTenEpisodes");
        if (batch != null && !"true".equals(batch) && !"false".equals(batch))
            throw invalid("invalid_batch_opt_in");
        if (!"true".equals(batch)) {
            if (args.containsKey("episodeIndex") || args.containsKey("episodeVid"))
                throw invalid("episode_arguments_require_batch_opt_in");
            return original();
        }
        String index = (String) args.get("episodeIndex");
        if (index == null || !index.matches("(?:[1-9]|10)"))
            throw invalid("invalid_episode_index");
        PlaybackProbeConfig result = new PlaybackProbeConfig(true, Integer.parseInt(index));
        if (!result.vid.equals(args.get("episodeVid"))) throw invalid("episode_vid_mismatch");
        return result;
    }

    boolean intervalSatisfied(long wallElapsedMs, long mediaAdvanceMs) {
        return wallElapsedMs >= advanceMs && mediaAdvanceMs >= advanceMs;
    }

    boolean enoughObservations(int states, int surfaces, int frameChanges) {
        return states >= minimumStateObservations && surfaces >= minimumSurfaceObservations
            && frameChanges >= minimumFrameChanges;
    }

    boolean observationWindowSatisfied(long wallElapsedMs, long mediaAdvanceMs,
            int states, int surfaces, int frameChanges) {
        return intervalSatisfied(wallElapsedMs, mediaAdvanceMs)
            && (!batch || enoughObservations(states, surfaces, frameChanges));
    }

    /** Match all production qualities/ceilings for this series/index, without touching a cache API. */
    boolean targetCacheEntry(String name) {
        return name != null && name.matches(seriesHash() + "_" + episodeIndex + "_[a-f0-9]{64}\\.mp4(?:\\.part)?");
    }

    private static String seriesHash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(SERIES.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static IllegalArgumentException invalid(String fixedCode) {
        return new IllegalArgumentException(fixedCode);
    }
}
