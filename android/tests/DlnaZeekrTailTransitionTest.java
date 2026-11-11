package io.github.jackotom.hongguo.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Replays the reported 128-second Zeekr tail using the real coordinator and fake transports. */
public final class DlnaZeekrTailTransitionTest {
    private static final long END = 128000;
    private static int checks, passed, failed;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private static void group(String name, Checked test) {
        try { test.run(); passed++; System.out.println("PASS ZeekrTail " + name); }
        catch (Throwable error) { failed++; System.out.println("FAIL ZeekrTail " + name + ": " + error.getMessage()); }
    }
    private static final class Receiver implements DlnaSession.Controller {
        final DlnaSessionTest.Control control = new DlnaSessionTest.Control();
        long initialPosition;
        Runnable onStatus;
        public void setMedia(String url, String title) throws Exception { control.setMedia(url, title); }
        public void play() { control.play(); if (control.plays == 1) control.position = initialPosition; }
        public void pause() { control.pause(); }
        public void stop() throws Exception { control.stop(); }
        public void seek(long value) { control.seek(value); }
        public DlnaSession.Status status() throws Exception {
            DlnaSession.Status result = control.status();
            Runnable hook = onStatus; onStatus = null;
            if (hook != null) hook.run();
            return result;
        }
    }
    private static final class F implements AutoCloseable {
        final DlnaSessionTest.Clock clock = new DlnaSessionTest.Clock();
        final DlnaSessionTest.Source source = new DlnaSessionTest.Source();
        final DlnaSessionTest.Server server = new DlnaSessionTest.Server();
        final Receiver receiver = new Receiver();
        final List<DlnaSession.Snapshot> snapshots = new ArrayList<>();
        final DlnaSession session;
        F() { this(END, "Zeekr-8507_投屏", 0); }
        F(long duration) { this(duration, "Zeekr-8507_投屏", 0); }
        F(long duration, String device, long initialPosition) {
            receiver.control.duration = duration; receiver.initialPosition = initialPosition;
            session = new DlnaSession(clock, receiver, source, server, snapshots::add,
                "123456", "合成128秒剧集", device, 40, "1080p", true, true);
            session.start(31, 0); clock.prepare(0, false);
            check(session.snapshot().current == 31 && !session.snapshot().preparing,
                "actual matching PLAYING adopts zero-based31 before replay");
        }
        void sample(String state, long position, long duration) { after(1000, state, position, duration); }
        void after(long delay, String state, long position, long duration) {
            receiver.control.state = state; receiver.control.position = position; receiver.control.duration = duration;
            clock.now += delay - 1000; clock.tick();
        }
        void arm() { arm(END); }
        void arm(long duration) { sample("PLAYING", 126000, duration); sample("PLAYING", 127000, duration); }
        void tail(long position) { sample("PAUSED_PLAYBACK", position, END); }
        void exact() { tail(END); }
        void three() { exact(); exact(); exact(); }
        void noNext(String label) { check(clock.preparations.size() == 1 && !session.snapshot().preparing, label); }
        void next(String label) {
            check(clock.preparations.size() == 2 && session.snapshot().preparing
                && session.snapshot().current == 31 && session.snapshot().targetCurrent == 32, label);
        }
        public void close() {
            session.close();
            for (DlnaSessionTest.Media media : source.media)
                check(media.references == 0, "closing session balances publication and prepared media leases");
        }
    }
    private static void reportedTail() {
        try (F f = new F()) {
            f.arm(); f.tail(127000); f.noNext("PAUSE127000/128000 waits rather than auto-ending");
            f.exact(); f.noNext("first exact sample after tail cannot count the earlier near-end pause");
            f.exact(); f.noNext("second exact sample still awaits third and elapsed2s");
            f.exact(); f.next("observed advance then tail settling128000/128000 queues immediate32");
            for (int i = 0; i < 8; i++) f.exact();
            check(f.clock.preparations.size() == 2 && f.source.confirmed.equals(Arrays.asList(31)),
                "fixed EOF schedules once and does not adopt next until receiver confirms");
            f.clock.prepare(1, false);
            check(f.session.snapshot().current == 32 && f.source.confirmed.equals(Arrays.asList(31, 32)),
                "next matching URI plus PLAYING adopts32 without changing quality");
        }
    }
    private static void unknownTail() {
        try (F f = new F(0)) { f.arm(0); f.tail(127000); f.three(); f.next("late positive duration can accompany the bounded one-second tail"); }
    }
    private static void fractionalTail() {
        for (long position : new long[]{127001, 127500, 127999}) try (F f = new F()) {
            f.arm(); f.tail(position); f.noNext("fractional tail does not itself finish");
            f.three(); f.next("subsecond tail is compatible after three exact terminal samples");
        }
    }
    private static void directExactUnchanged() {
        try (F f = new F()) { f.arm(); f.three(); f.next("existing directly exact EOF behavior remains supported"); }
    }
    private static void fixedTailNeverEnds() {
        try (F f = new F()) { f.arm(); for (int i = 0; i < 80; i++) f.tail(127000); f.noNext("fixed127000 never counts as complete even across timeouts"); }
    }
    private static void gapAndMiddleRejected() {
        for (long position : new long[]{0, 64000, 126999}) try (F f = new F()) {
            f.arm(); f.tail(position); f.three(); f.noNext("tail gap greater than1000ms or middle pause invalidates proof");
        }
    }
    private static void backwardsTailRejected() {
        try (F f = new F()) {
            f.arm(); f.sample("PLAYING", 127800, END); f.tail(127000); f.three();
            f.noNext("first tail position behind real playing progress rejects proof");
        }
        try (F f = new F()) {
            f.arm(); f.tail(127800); f.tail(127000); f.three();
            f.noNext("tail position cannot move backward then reuse old progress");
        }
    }
    private static void overrunRejected() {
        try (F f = new F()) { f.arm(); f.tail(END + 1); f.three(); f.noNext("position beyond positive duration invalidates tail proof"); }
    }
    private static void slowTailStillRequiresExactSpan() {
        try (F f = new F()) {
            f.arm(); f.tail(127000);
            f.after(12000, "PAUSED_PLAYBACK", 127999, END);
            for (int i = 0; i < 4; i++) f.after(0, "PAUSED_PLAYBACK", END, END);
            f.noNext("long time waiting near end does not replace minimum2s across exact samples");
            f.exact(); f.noNext("only1s since first exact remains insufficient");
            f.exact(); f.next("tail wait preserves evidence after fresh entry and exact samples span2s");
        }
    }
    private static void tailEntryFreshness() {
        try (F f = new F()) {
            f.arm(); f.after(15000, "PAUSED_PLAYBACK", 127000, END); f.three();
            f.next("tail entry at exactly15s after real advance remains fresh");
        }
        try (F f = new F()) {
            f.arm(); f.after(15001, "PAUSED_PLAYBACK", 127000, END); f.three();
            f.noNext("tail entry older than15s cannot renew stale playing evidence");
        }
    }
    private static void totalTailBoundary() {
        try (F f = new F()) {
            f.arm(); f.tail(127000); f.after(28000, "PAUSED_PLAYBACK", END, END); f.exact(); f.exact();
            f.next("third exact at30s after first tail is inside total bounded confirmation window");
        }
    }
    private static void totalTailExpires() {
        try (F f = new F()) {
            f.arm(); f.tail(127000); f.after(28001, "PAUSED_PLAYBACK", END, END); f.exact(); f.exact(); f.three();
            f.noNext("tail plus exact confirmation over30s expires and fixed EOF cannot rearm");
        }
        try (F f = new F()) {
            f.arm(); f.tail(127000); for (int i = 0; i < 31; i++) f.tail(127000); f.three();
            f.noNext("successive nonexact tail samples cannot indefinitely renew the30s window");
        }
    }
    private static void tailAfterExactRejected() {
        try (F f = new F()) {
            f.arm(); f.exact(); f.tail(127999); f.three();
            f.noNext("position regression from exact EOF invalidates candidate rather than restarting tail wait");
        }
    }
    private static void tailDurationConflicts() {
        for (long duration : new long[]{0, END - 1000, END + 1000}) try (F f = new F()) {
            f.arm(); f.tail(127000); f.sample("PAUSED_PLAYBACK", 127000, duration); f.three();
            f.noNext("tail duration must remain the same positive value");
        }
    }
    private static void unknownTailDurationLocks() {
        try (F f = new F(0)) {
            f.arm(0); f.tail(127000); f.sample("PAUSED_PLAYBACK", 128000, END + 1000); f.three();
            f.noNext("first tail positive duration locks metadata even when earlier PLAYING duration was unknown");
        }
    }
    private static void tailKnownDurationSurvivesPlayingZero() {
        try (F f = new F(0)) {
            f.arm(0); f.tail(127000); f.sample("PLAYING", 127500, 0);
            for (int i = 0; i < 3; i++) f.sample("PAUSED_PLAYBACK", 129000, 129000);
            f.noNext("positive tail duration128000 survives resumed PLAYING zero metadata and rejects contradictory129000 EOF");
        }
        try (F f = new F(0)) {
            f.arm(0); f.tail(127000); f.sample("PLAYING", 127500, 0); f.three();
            f.next("same128000 duration after fresh resumed PLAYING progress can still confirm natural EOF");
        }
    }
    private static void backwardsClock() {
        try (F f = new F()) {
            f.arm(); f.tail(127000); f.after(-1000, "PAUSED_PLAYBACK", END, END); f.three();
            f.noNext("clock moving before first tail invalidates pending confirmation");
        }
    }
    private static void unknownProgressAtEof() {
        try (F f = new F(0)) {
            f.arm(0); f.sample("PLAYING", END, 0); f.three();
            f.next("unknown duration126000→127000→128000 retains demonstrated advance strictly before newly reported EOF");
        }
    }
    private static void lateKnownPlayingEof() {
        try (F f = new F(0)) {
            f.arm(0); f.sample("PLAYING", END, END); f.three();
            f.next("duration first becoming known on PLAYING EOF preserves earlier pre-end advance");
        }
    }
    private static void unknownEofCannotRenewStalePreEnd() {
        try (F f = new F(0)) {
            f.arm(0); f.after(16000, "PLAYING", END, 0); f.three();
            f.noNext("late unknown-duration EOF point cannot refresh stale earlier strictly pre-end advance");
        }
    }
    private static void unknownClockBetweenProofPoints() {
        try (F f = new F(0)) {
            f.sample("PLAYING", 125000, 0); f.sample("PLAYING", 126000, 0); f.sample("PLAYING", END, 0);
            f.after(-500, "PAUSED_PLAYBACK", END, END);
            f.three(); f.noNext("first unknown-duration EOF pause timestamp between previous and latest forward samples rejects clock reversal");
        }
    }
    private static void unknownFixedEofRejected() {
        try (F f = new F(0, "Zeekr-8507_投屏", END)) {
            for (int i = 0; i < 5; i++) f.sample("PLAYING", END, 0);
            f.three(); f.noNext("fixed unknown-duration PLAYING EOF has no real forward proof");
        }
    }
    private static void unknownSingleJumpRejected() {
        try (F f = new F(0, "Zeekr-8507_投屏", 127000)) {
            f.sample("PLAYING", END, 0); f.three();
            f.noNext("one adopted point then jump directly to unknown EOF supplies no earlier pre-end advance");
        }
    }
    private static void unknownOverrunRejected() {
        try (F f = new F(0)) {
            f.arm(0); f.sample("PLAYING", END + 1000, 0); f.three();
            f.noNext("late duration below unknown-duration maximum cannot be treated as EOF");
        }
    }
    private static void unknownBackwardRejected() {
        try (F f = new F(0)) {
            f.arm(0); f.sample("PLAYING", END, 0); f.sample("PLAYING", 10000, 0); f.three();
            f.noNext("unknown-duration backward jump discards saved pre-end progress");
        }
    }
    private static void phonePauseAndSeek() {
        try (F f = new F()) {
            f.arm(); f.tail(127000); f.session.pause(); f.three();
            f.noNext("explicit phone pause while in tail prevents automatic continuation");
        }
        try (F f = new F(0)) {
            f.arm(0); f.session.seek(END); for (int i = 0; i < 4; i++) f.sample("PLAYING", END, 0);
            f.three(); f.noNext("seek to unknown EOF resets old proof and fixed postseek EOF does not rearm");
        }
        try (F f = new F()) {
            f.arm(); f.tail(127000); f.session.seek(END); f.three();
            f.noNext("seek after tail invalidates old candidate");
        }
    }
    private static void foreignUri() {
        for (String foreign : Arrays.asList("", "http://192.168.1.9/other.mp4")) try (F f = new F()) {
            f.arm(); f.tail(127000); String own = f.receiver.control.uri;
            f.receiver.control.uri = foreign; f.exact(); f.receiver.control.uri = own; f.three();
            f.noNext("foreign or missing URI invalidates pending tail");
        }
    }
    private static void statusFailure() {
        try (F f = new F()) {
            f.arm(); f.tail(127000); f.receiver.control.failStatus = true; f.clock.tick();
            f.receiver.control.failStatus = false; f.three();
            f.noNext("single status failure clears pending tail even before disconnect threshold");
        }
    }
    private static void otherStates() {
        for (String state : Arrays.asList("TRANSITIONING", "BUFFERING", "UNKNOWN", "STOPPED", "NO_MEDIA_PRESENT")) try (F f = new F()) {
            f.arm(); f.tail(127000); f.sample(state, END, END); f.three();
            f.noNext("unrelated state between tail and exact EOF cannot finish: " + state);
        }
    }
    private static void ordinaryTv() {
        for (String device : Arrays.asList("FF 75S365C-33CE", "普通电视", "Zeekr", "OtherZeekr-8507_投屏")) try (F f = new F(END, device, 0)) {
            f.arm(); f.tail(127000); f.three(); f.noNext("one-second tail compatibility remains scoped to exact Zeekr profile");
        }
    }
    private static void disabledAndSelection() {
        try (F f = new F()) { f.arm(); f.tail(127000); f.session.setAutoplay(false); f.three(); f.noNext("autoplay-off invalidates pending tail"); }
        try (F f = new F()) {
            f.arm(); f.tail(127000); f.session.select(35); f.three();
            check(f.clock.preparations.size() == 2 && f.session.snapshot().targetCurrent == 35,
                "manual selection35 supersedes automatic32 while preparation owns new epoch");
        }
    }
    private static void intentDuringExactPoll() {
        try (F f = new F()) {
            f.arm(); f.tail(127000); f.exact(); f.exact(); f.clock.queueCommands = true;
            f.receiver.onStatus = f.session::pause; f.exact(); f.clock.flush(); f.clock.queueCommands = false;
            f.noNext("phone pause arriving during blocked third status wins over pending automatic next");
        }
    }
    private static void failureDoesNotSkip() {
        try (F f = new F()) {
            f.arm(); f.tail(127000); f.source.fail = 32; f.three(); f.next("confirmed EOF prepares immediate32 only");
            f.clock.prepare(1, false);
            check(!f.session.snapshot().preparing && f.session.snapshot().current == 31 && f.session.snapshot().targetCurrent == 32
                && !f.session.snapshot().error.isEmpty(), "failed immediate source keeps31 current and retryable32 target");
            for (int i = 0; i < 6; i++) f.exact();
            check(f.clock.preparations.size() == 2, "failed source does not skip episode or start auto-retry storm");
        }
    }
    private static void freshPlaybackRearms() {
        try (F f = new F()) {
            f.arm(); f.tail(64000); f.three(); f.noNext("invalid middle pause first discards proof");
            f.sample("PLAYING", 126000, END); f.sample("PLAYING", 127000, END); f.tail(127000); f.three();
            f.next("fresh independently observed progress after rejection may rearm valid tail");
        }
    }
    private static String diagnostics(F f) throws Exception {
        return (String) DlnaSession.class.getMethod("diagnostics").invoke(f.session);
    }
    private static void rejectionDiagnosticsReadOnly() throws Exception {
        try (F f = new F()) {
            f.sample("PLAYING", 125000, END); f.sample("PLAYING", 126000, END); f.tail(126000);
            String expected = "片尾首拒：PAUSE_BEFORE_TAIL raw=126000/128000 tracked=126000 prior=125000 samples=0";
            check(diagnostics(f).contains(expected), "diagnostic captures first rejecting raw sample and tracked/prior positions before reset");
            for (int i = 0; i < 6; i++) f.exact();
            String saved = diagnostics(f);
            check(saved.contains(expected), "six later fixed128000/128000 samples do not overwrite original rejection evidence");
            String trace = saved.substring(saved.indexOf("片尾采样"));
            check(!trace.contains("126000") && trace.split("PAUSE=:128000/128000", -1).length == 7,
                "rolling trace replaces six samples while separate first-rejection diagnostic retains cause");
            int statuses = f.receiver.control.statuses, sets = f.receiver.control.sets, plays = f.receiver.control.plays;
            int prepares = f.clock.preparations.size(), publications = f.server.live.size();
            DlnaSession.Snapshot snapshot = f.session.snapshot();
            for (int i = 0; i < 20; i++) check(diagnostics(f).equals(saved), "read-only diagnostic returns stable immutable text");
            check(f.receiver.control.statuses == statuses && f.receiver.control.sets == sets && f.receiver.control.plays == plays
                && f.clock.preparations.size() == prepares && f.server.live.size() == publications && f.session.snapshot() == snapshot,
                "diagnostic reads cause no receiver I/O, scheduling, supply changes or snapshot mutations");
            check(!saved.contains("http") && !saved.contains("192.168.") && !saved.contains("token") && !saved.contains("secret"),
                "diagnostic output exposes neither serving URLs nor receiver addresses or tokens");
            f.noNext("reading first rejection does not relax the earlier invalidation");
            f.sample("PLAYING", 126000, END); f.sample("PLAYING", 127000, END);
            check(diagnostics(f).contains("片尾首拒：无"), "new observed forward playback clears stale first-rejection display");
        }
    }
    public static void main(String[] args) {
        group("reported-current31-tail127000-to128000-queues32-once", DlnaZeekrTailTransitionTest::reportedTail);
        group("unknown-duration-then-tail", DlnaZeekrTailTransitionTest::unknownTail);
        group("fractional-tail-settles-exact", DlnaZeekrTailTransitionTest::fractionalTail);
        group("direct-exact-EOF-unchanged", DlnaZeekrTailTransitionTest::directExactUnchanged);
        group("fixed127000-never-ends", DlnaZeekrTailTransitionTest::fixedTailNeverEnds);
        group("gap1001-or-middle-pause-rejects", DlnaZeekrTailTransitionTest::gapAndMiddleRejected);
        group("first-or-later-tail-backwards-rejects", DlnaZeekrTailTransitionTest::backwardsTailRejected);
        group("tail-overrun-rejects", DlnaZeekrTailTransitionTest::overrunRejected);
        group("long-tail-never-counts-as-exact-span", DlnaZeekrTailTransitionTest::slowTailStillRequiresExactSpan);
        group("tail-first-entry15s-freshness-boundary", DlnaZeekrTailTransitionTest::tailEntryFreshness);
        group("tail-plus-exact30s-boundary", DlnaZeekrTailTransitionTest::totalTailBoundary);
        group("tail-plus-exact-over30s-expires", DlnaZeekrTailTransitionTest::totalTailExpires);
        group("nonexact-after-exact-cannot-rearm", DlnaZeekrTailTransitionTest::tailAfterExactRejected);
        group("tail-duration-conflict-rejects", DlnaZeekrTailTransitionTest::tailDurationConflicts);
        group("first-unknown-tail-positive-duration-locks", DlnaZeekrTailTransitionTest::unknownTailDurationLocks);
        group("tail-positive-duration-survives-resumed-PLAYING-zero", DlnaZeekrTailTransitionTest::tailKnownDurationSurvivesPlayingZero);
        group("tail-clock-backwards-rejects", DlnaZeekrTailTransitionTest::backwardsClock);
        group("unknown126000-127000-128000-preserves-pre-end-proof", DlnaZeekrTailTransitionTest::unknownProgressAtEof);
        group("known-duration-first-arrives-on-PLAYING-EOF", DlnaZeekrTailTransitionTest::lateKnownPlayingEof);
        group("unknown-EOF-point-cannot-refresh-stale-pre-end-proof", DlnaZeekrTailTransitionTest::unknownEofCannotRenewStalePreEnd);
        group("unknown-clock-between-prior-and-latest-proof-rejects", DlnaZeekrTailTransitionTest::unknownClockBetweenProofPoints);
        group("unknown-fixed-EOF-is-not-progress", DlnaZeekrTailTransitionTest::unknownFixedEofRejected);
        group("unknown-one-point-to-EOF-is-not-pre-end-progress", DlnaZeekrTailTransitionTest::unknownSingleJumpRejected);
        group("unknown-overrun-does-not-end", DlnaZeekrTailTransitionTest::unknownOverrunRejected);
        group("unknown-backward-position-resets", DlnaZeekrTailTransitionTest::unknownBackwardRejected);
        group("phone-pause-and-seek-protections", DlnaZeekrTailTransitionTest::phonePauseAndSeek);
        group("foreign-or-empty-URI-clears-tail", DlnaZeekrTailTransitionTest::foreignUri);
        group("single-status-failure-clears-tail", DlnaZeekrTailTransitionTest::statusFailure);
        group("other-states-clear-tail", DlnaZeekrTailTransitionTest::otherStates);
        group("ordinary-TV-profile-unchanged", DlnaZeekrTailTransitionTest::ordinaryTv);
        group("autoplay-off-and-new-selection-win", DlnaZeekrTailTransitionTest::disabledAndSelection);
        group("intent-during-third-exact-poll-wins", DlnaZeekrTailTransitionTest::intentDuringExactPoll);
        group("next-source-failure-does-not-skip", DlnaZeekrTailTransitionTest::failureDoesNotSkip);
        group("new-real-playing-progress-can-rearm", DlnaZeekrTailTransitionTest::freshPlaybackRearms);
        group("first-rejection-outlives-six-sample-trace-read-only", DlnaZeekrTailTransitionTest::rejectionDiagnosticsReadOnly);
        System.out.println("DLNA_ZEEKR_TAIL_" + (failed == 0 ? "OK" : "FAILED")
            + " groupsPassed=" + passed + " groupsFailed=" + failed + " checks=" + checks
            + " actualSession=true zeroBasedCurrent=31 durationMs=128000 nextPosition=32"
            + " networkRequests=0 deviceTests=not_run");
        if (failed != 0) System.exit(1);
    }
}
