package io.github.jackotom.hongguo.android;

import java.util.function.Consumer;

/** No-network checks for conservative App-source opening acceptance, never live playback. */
public final class PlaybackProbeSourcePolicyChecks {
    private static int checks;

    public static void main(String[] args) {
        need(PlaybackProbeSourcePolicy.acceptsApp(valid()));
        reject(e -> e.batch = false); // Legacy stays guest-only.
        reject(e -> e.matchingPlans = 0);
        reject(e -> e.matchingPlans = 2);
        reject(e -> e.source = "api");
        reject(e -> e.source = "web");
        reject(e -> e.source = null);
        reject(e -> e.guest = true);
        reject(e -> e.requestedQuality = "720p");
        reject(e -> e.requestedQuality = null);
        reject(e -> e.playbackCeiling = 1079);
        reject(e -> e.playbackCeiling = -1);
        reject(e -> e.protectedIdentity = false);
        reject(e -> e.cached = false);
        reject(e -> e.manual = true);
        reject(e -> e.remote = true);
        reject(e -> e.planWidth = 0);
        reject(e -> e.planHeight = -1);
        reject(e -> e.decodedWidth = 719);
        reject(e -> e.decodedHeight = 1279);
        need(!PlaybackProbeSourcePolicy.acceptsApp(null));
        need(PlaybackProbeSourcePolicy.selectedIdentity("series", "vid", "series", "vid"));
        need(!PlaybackProbeSourcePolicy.selectedIdentity("other", "vid", "series", "vid"));
        need(!PlaybackProbeSourcePolicy.selectedIdentity("series", "other", "series", "vid"));
        need(!PlaybackProbeSourcePolicy.selectedIdentity(null, "vid", "series", "vid"));
        need(!PlaybackProbeSourcePolicy.selectedIdentity("series", "vid", null, "vid"));
        Object first = new Object();
        need(PlaybackProbeSourcePolicy.sameRegisteredPlan(first, first));
        need(!PlaybackProbeSourcePolicy.sameRegisteredPlan(first, new Object()));
        need(!PlaybackProbeSourcePolicy.sameRegisteredPlan(first, null));
        need(!PlaybackProbeSourcePolicy.sameRegisteredPlan(null, null));
        System.out.println("PLAYBACK_PROBE_SOURCE_CHECKS_PASSED=" + checks);
    }

    private static PlaybackProbeSourcePolicy.AppEvidence valid() {
        PlaybackProbeSourcePolicy.AppEvidence e = new PlaybackProbeSourcePolicy.AppEvidence();
        e.batch = e.protectedIdentity = e.cached = true;
        e.matchingPlans = 1; e.source = "app"; e.requestedQuality = "auto";
        e.planWidth = e.decodedWidth = 720; e.planHeight = e.decodedHeight = 1280;
        return e;
    }

    private static void reject(Consumer<PlaybackProbeSourcePolicy.AppEvidence> change) {
        PlaybackProbeSourcePolicy.AppEvidence evidence = valid(); change.accept(evidence);
        need(!PlaybackProbeSourcePolicy.acceptsApp(evidence));
    }

    private static void need(boolean value) {
        checks++;
        if (!value) throw new AssertionError("probe_source_check_failed_" + checks);
    }
}
