package io.github.jackotom.hongguo.android;

/** Pure batch-opening gate; does not grant permission, resolve sources, or verify a full episode. */
final class PlaybackProbeSourcePolicy {
    private PlaybackProbeSourcePolicy() { }

    static final class AppEvidence {
        boolean batch, protectedIdentity, cached, manual, remote, guest;
        int matchingPlans, playbackCeiling, planWidth, planHeight, decodedWidth, decodedHeight;
        String source, requestedQuality;
    }

    static boolean selectedIdentity(String series, String vid, String selectedSeries, String selectedVid) {
        return selectedSeries != null && selectedVid != null
            && selectedSeries.equals(series) && selectedVid.equals(vid);
    }

    static boolean acceptsApp(AppEvidence e) {
        return e != null && e.batch && e.matchingPlans == 1 && "app".equals(e.source)
            && !e.guest && "auto".equals(e.requestedQuality) && e.playbackCeiling == 0
            && e.protectedIdentity && e.cached && !e.manual && !e.remote
            && e.planWidth > 0 && e.planHeight > 0
            && e.planWidth == e.decodedWidth && e.planHeight == e.decodedHeight;
    }

    static boolean sameRegisteredPlan(Object first, Object current) {
        return first != null && first == current;
    }
}
