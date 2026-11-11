package io.github.jackotom.hongguo.android;

import java.io.InterruptedIOException;

/** Bounded playback-only descent. Manual download preferences never enter this coordinator. */
final class AdaptiveQuality {
    interface Attempt<T> { T prepare(String quality) throws Exception; }
    interface Eligible { boolean test(Exception error); }
    interface Alive { boolean test(); }
    interface Progress { void changed(String quality, int stage, Exception previous); }
    interface Discard<T> { void release(T value); }
    static final class Result<T> {
        final String quality; final T value; final int stages;
        Result(String quality, T value, int stages) { this.quality = quality; this.value = value; this.stages = stages; }
    }
    static String next(String quality) {
        switch (MediaQuality.normalize(quality)) {
            case "auto":
            case "1080p": return "720p";
            case "720p": return "480p";
            case "480p": return "360p";
            default: return null;
        }
    }
    /** Internal playback caps are below the previous nominal grade, never auto/highest. */
    static int ceiling(String quality) {
        switch (MediaQuality.normalize(quality)) {
            case "720p": return 1079;
            case "480p": return 719;
            case "360p": return 479;
            default: return 0;
        }
    }
    /** Skip a nominal grade that would not be below a known decoded short edge. */
    static String next(String quality, int observedShortEdge) {
        String candidate = next(quality);
        while (candidate != null && observedShortEdge > 0
                && Integer.parseInt(candidate.substring(0, candidate.length() - 1)) >= observedShortEdge)
            candidate = next(candidate);
        return candidate;
    }
    private static void check(Alive alive) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || !alive.test()) throw new InterruptedIOException("播放准备已取消");
    }
    static <T> Result<T> prepare(String initial, Attempt<T> attempt, Eligible eligible, Alive alive, Progress progress, Discard<T> discard) throws Exception {
        String quality = MediaQuality.normalize(initial); Exception previous = null;
        for (int stage = 1; stage <= 4; stage++) {
            check(alive); progress.changed(quality, stage, previous); check(alive);
            T value;
            try { value = attempt.prepare(quality); }
            catch (Exception error) {
                check(alive);
                String lower = next(quality);
                if (lower == null || !eligible.test(error)) throw error;
                previous = error; quality = lower; continue;
            }
            try { check(alive); }
            catch (Exception cancelled) { discard.release(value); throw cancelled; }
            return new Result<>(quality, value, stage);
        }
        throw new IllegalStateException("清晰度准备阶段超出上限");
    }
}
