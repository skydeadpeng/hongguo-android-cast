package io.github.jackotom.hongguo.android;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** JVM-only argument/schema/acceptance-boundary checks. No device, file, or network activity. */
public final class PlaybackProbeConfigChecks {
    private static int checks;
    private static final String[] EXPECTED_VIDS = {
        "7703412149342719001", "7703412063502093337", "7703412110843186201",
        "7703412039816842265", "7703412145559440409", "7703412101368269849",
        "7703412128635423769", "7703412191394810905", "7703412032455855129",
        "7703412076667997209"
    };

    public static void main(String[] args) {
        PlaybackProbeConfig original = PlaybackProbeConfig.parse(Collections.emptyMap());
        need(!original.batch && original.schema == 1 && original.episodeIndex == 1);
        need(original.vid.equals(EXPECTED_VIDS[0]));
        need(original.advanceMs == 30_000 && original.budgetMs == 240_000 && original.continuousBudgetMs == 45_000);
        need(original.outputRelativePath.equals("playback-qa"));
        need(original.scope.equals("production_player_component_not_browse_ui"));
        need(original.continuedStage.equals("continued_30s") && original.finalScreenshot.equals("after-30s.png"));
        need(original.passReason.equals("rendered_and_advanced_30s"));
        need(!original.intervalSatisfied(29_999, 40_000));
        need(!original.intervalSatisfied(40_000, 29_999));
        need(!original.intervalSatisfied(5_000, 5_000));
        need(original.intervalSatisfied(30_000, 30_000));
        need(original.observationWindowSatisfied(30_000, 30_000, 1, 1, 0));
        need(!PlaybackProbeConfig.parse(Collections.singletonMap("batchTenEpisodes", "false")).batch);
        String seriesHash = "d9a7e0417b6ec0af956fe4199efdbaa4a77f3ecfef75a27032bcef62c9288178";
        String vidHash = String.join("", Collections.nCopies(64, "a"));
        for (int index = 1; index <= 10; index++) {
            PlaybackProbeConfig config = PlaybackProbeConfig.parse(batchArgs(index));
            need(config.batch && config.schema == 2 && config.episodeIndex == index);
            need(config.vid.equals(EXPECTED_VIDS[index - 1]));
            need(config.scope.equals("production_player_component_ten_episode_opening"));
            need(config.acceptanceMode.equals("ten_episode_opening_5s"));
            need(config.testScope.equals("ten_episode_opening_5s_production_player_component"));
            need(config.advanceMs == 5_000 && config.budgetMs == 180_000 && config.continuousBudgetMs == 15_000);
            need(config.outputRelativePath.equals("playback-qa/episode-" + (index < 10 ? "0" : "") + index));
            need(config.continuedStage.equals("continued_5s") && config.finalScreenshot.equals("after-5s.png"));
            need(config.passReason.equals("rendered_and_advanced_5s"));
            need(!config.intervalSatisfied(4_999, 6_000));
            need(!config.intervalSatisfied(6_000, 4_999));
            need(!config.intervalSatisfied(-1, Long.MAX_VALUE));
            need(config.intervalSatisfied(5_000, 5_000));
            need(config.enoughObservations(10, 4, 2));
            need(!config.enoughObservations(9, 4, 2));
            need(!config.enoughObservations(10, 3, 2));
            need(!config.enoughObservations(10, 4, 1));
            need(!config.observationWindowSatisfied(5_000, 5_000, 9, 4, 2));
            need(!config.observationWindowSatisfied(5_000, 5_000, 10, 3, 2));
            need(!config.observationWindowSatisfied(5_000, 5_000, 10, 4, 1));
            need(!config.observationWindowSatisfied(4_999, 5_000, 10, 4, 2));
            need(config.observationWindowSatisfied(5_500, 5_500, 10, 4, 2));
            String target = seriesHash + "_" + index + "_" + vidHash + ".mp4";
            need(config.targetCacheEntry(target));
            need(config.targetCacheEntry(target + ".part"));
            need(!config.targetCacheEntry(target + ".bak"));
            need(!config.targetCacheEntry("../" + target));
            need(!config.targetCacheEntry(vidHash + "_" + index + "_" + vidHash + ".mp4"));
            need(!config.targetCacheEntry(seriesHash + "_" + (index + 1) + "_" + vidHash + ".mp4"));
            need(!config.targetCacheEntry(null));
        }
        for (String value : new String[] {"", "0", "11", "-1", "01", "1.0", "1 ", " 1", "../1", "2147483648"})
            rejects(with(batchArgs(1), "episodeIndex", value), "invalid_episode_index");
        rejects(with(batchArgs(1), "episodeVid", EXPECTED_VIDS[1]), "episode_vid_mismatch");
        rejects(with(batchArgs(1), "episodeVid", "https://example.invalid/source"), "episode_vid_mismatch");
        Map<String, Object> missingVid = batchArgs(1); missingVid.remove("episodeVid");
        rejects(missingVid, "episode_vid_mismatch");
        Map<String, Object> missingIndex = batchArgs(1); missingIndex.remove("episodeIndex");
        rejects(missingIndex, "invalid_episode_index");
        rejects(Collections.singletonMap("episodeIndex", "1"), "episode_arguments_require_batch_opt_in");
        rejects(with(batchArgs(1), "batchTenEpisodes", "false"), "episode_arguments_require_batch_opt_in");
        for (String value : new String[] {"", "True", "1", "yes"})
            rejects(with(batchArgs(1), "batchTenEpisodes", value), "invalid_batch_opt_in");
        for (String key : new String[] {"url", "playInfo", "seriesId", "duration", "advanceMs", "output", "quality", "speed"})
            rejects(with(batchArgs(1), key, "unexpected"), "unsupported_probe_argument");
        rejects(with(batchArgs(1), "episodeIndex", Integer.valueOf(1)), "non_string_probe_argument");
        rejects(with(batchArgs(1), "batchTenEpisodes", Boolean.TRUE), "non_string_probe_argument");
        rejects(with(batchArgs(1), "episodeVid", null), "non_string_probe_argument");
        Map<String, Object> explicit = batchArgs(1);
        explicit.put("isolatedQaAvd", "true"); explicit.put("qaCommit", String.join("", Collections.nCopies(40, "a")));
        need(PlaybackProbeConfig.parse(explicit).batch);
        System.out.println("PLAYBACK_PROBE_CONFIG_CHECKS_PASSED=" + checks);
    }

    private static Map<String, Object> batchArgs(int index) {
        Map<String, Object> args = new HashMap<>();
        args.put("batchTenEpisodes", "true"); args.put("episodeIndex", Integer.toString(index));
        args.put("episodeVid", EXPECTED_VIDS[index - 1]);
        return args;
    }

    private static Map<String, Object> with(Map<String, Object> args, String key, Object value) {
        args.put(key, value); return args;
    }

    private static void rejects(Map<String, ?> args, String reason) {
        try { PlaybackProbeConfig.parse(args); throw new AssertionError("unsafe_probe_arguments_accepted"); }
        catch (IllegalArgumentException expected) { need(reason.equals(expected.getMessage())); }
    }

    private static void need(boolean value) {
        checks++;
        if (!value) throw new AssertionError("probe_config_check_failed_" + checks);
    }
}
