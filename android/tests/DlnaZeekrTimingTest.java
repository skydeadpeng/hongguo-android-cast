package io.github.jackotom.hongguo.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Actual coordinator, deterministic renderer timing; no device, network or media downloads. */
public final class DlnaZeekrTimingTest {
    private static final long END = 108000;
    private static int checks, passed, failed;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private static void group(String name, Checked test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS ZeekrTiming " + name);
        } catch (Throwable error) {
            failed++;
            System.out.println("FAIL ZeekrTiming " + name + ": " + error.getMessage());
        }
    }
    private static final class Receiver implements DlnaSession.Controller {
        final DlnaSessionTest.Control control = new DlnaSessionTest.Control();
        Runnable onStatus;
        long initialPosition;
        public void setMedia(String url, String title) throws Exception { control.setMedia(url, title); }
        public void play() { control.play(); if (control.plays == 1) control.position = initialPosition; }
        public void pause() { control.pause(); }
        public void stop() throws Exception { control.stop(); }
        public void seek(long value) { control.seek(value); }
        public DlnaSession.Status status() throws Exception {
            DlnaSession.Status result = control.status();
            Runnable hook = onStatus;
            onStatus = null;
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
        F(long initialDuration) { this(initialDuration, "Zeekr-8507_投屏", 0, 0); }
        F(long initialDuration, String device, long initialPosition, long resume) {
            receiver.control.duration = initialDuration;
            receiver.initialPosition = initialPosition;
            session = new DlnaSession(clock, receiver, source, server, snapshots::add,
                "123456", "合成108秒剧集", device, 25, "1080p", true, true);
            session.start(17, resume);
            clock.prepare(0, false);
            check(session.snapshot().current == 17 && !session.snapshot().preparing,
                "matching PLAYING URI adopts zero-based position17 before timing replay");
        }
        void sample(String state, long position, long duration) { sampleAfter(1000, state, position, duration); }
        void sampleAfter(long delay, String state, long position, long duration) {
            receiver.control.state = state;
            receiver.control.position = position;
            receiver.control.duration = duration;
            clock.now += delay - 1000;
            clock.tick();
        }
        void arm(long duration) {
            sample("PLAYING", 104000, duration);
            sample("PLAYING", 106000, duration);
        }
        void paused() { sample("PAUSED_PLAYBACK", END, END); }
        void three() { paused(); paused(); paused(); }
        void noNext(String label) {
            check(clock.preparations.size() == 1 && !session.snapshot().preparing, label);
        }
        void next(String label) {
            check(clock.preparations.size() == 2 && session.snapshot().preparing
                && session.snapshot().current == 17 && session.snapshot().targetCurrent == 18, label);
        }
        public void close() {
            session.close();
            for (DlnaSessionTest.Media media : source.media)
                check(media.references == 0, "all current and prepared publication leases are balanced");
        }
    }
    private static void lateDuration() {
        try (F f = new F(0)) {
            f.arm(0);
            f.paused(); f.noNext("first late-duration exact EOF only starts confirmation");
            f.paused(); f.noNext("second exact EOF does not advance before third sample");
            f.paused(); f.next("unknown-duration real forward progress completes at newly reported108000/108000");
            for (int i = 0; i < 6; i++) f.paused();
            check(f.clock.preparations.size() == 2, "one EOF queues immediate next exactly once");
            check(f.source.confirmed.equals(Arrays.asList(17)), "current stays17 until next publication plays");
            f.clock.prepare(1, false);
            check(f.session.snapshot().current == 18 && f.source.confirmed.equals(Arrays.asList(17, 18)),
                "next confirmed URI alone adopts position18 episode19");
        }
    }
    private static void knownZeroKnown() {
        try (F f = new F(END)) {
            f.sample("PLAYING", 104000, END);
            f.sample("PLAYING", 105000, 0);
            f.sample("PLAYING", 106000, 0);
            f.three();
            f.next("temporary zero duration preserves known108000 duration and actual forward progress");
        }
    }
    private static void durationArrivesWhilePlaying() {
        try (F f = new F(0)) {
            f.arm(0);
            f.sample("PLAYING", 107000, END);
            f.three();
            f.next("first positive duration supplements unknown-duration forward evidence rather than erasing it");
        }
    }
    private static void slowConfirmation() {
        for (long initial : new long[]{0, END}) try (F f = new F(initial)) {
            f.arm(initial);
            f.sampleAfter(14000, "PAUSED_PLAYBACK", END, END);
            f.noNext("fresh first terminal sample starts only candidate");
            f.sampleAfter(2000, "PAUSED_PLAYBACK", END, END);
            f.noNext("second slow sample still requires third confirmation");
            f.sampleAfter(2000, "PAUSED_PLAYBACK", END, END);
            f.next("candidate survives last progress aging past15s while confirmation remains within30s");
        }
    }
    private static void confirmationBoundary() {
        try (F f = new F(0)) {
            f.arm(0);
            f.paused();
            f.sampleAfter(15000, "PAUSED_PLAYBACK", END, END);
            f.sampleAfter(15000, "PAUSED_PLAYBACK", END, END);
            f.next("third unchanged terminal sample exactly30s after first remains in bounded confirmation window");
        }
    }
    private static void unknownForever() {
        try (F f = new F(0)) {
            f.arm(0);
            for (int i = 0; i < 6; i++) f.sample("PAUSED_PLAYBACK", END, 0);
            f.noNext("unknown terminal duration does not establish EOF");
        }
    }
    private static void positiveDurationConflicts() {
        try (F f = new F(END)) {
            f.arm(END);
            f.sample("PLAYING", 107000, END + 1000);
            for (int i = 0; i < 6; i++) f.sample("PAUSED_PLAYBACK", END + 1000, END + 1000);
            f.noNext("conflicting positive PLAYING duration resets earlier progress");
        }
        try (F f = new F(END)) {
            f.arm(0);
            for (int i = 0; i < 6; i++) f.sample("PAUSED_PLAYBACK", END + 1000, END + 1000);
            f.noNext("zero intermediary duration does not forget previously known conflicting positive duration");
        }
    }
    private static void overrunAndFinalBounds() {
        try (F f = new F(END)) {
            f.sample("PLAYING", 104000, 0);
            f.sample("PLAYING", END + 1000, 0);
            f.three(); f.noNext("zero duration still obeys already known positive upper bound");
        }
        for (long last : new long[]{END, END + 1000}) try (F f = new F(0)) {
            f.sample("PLAYING", 104000, 0);
            f.sample("PLAYING", last, 0);
            f.three();
            if(last==END)f.next("actual pre-end progress followed by unknown-duration exact EOF is accepted");
            else f.noNext("unknown-duration PLAYING beyond newly reported end remains rejected");
        }
    }
    private static void staleFirstCandidate() {
        for (long initial : new long[]{0, END}) try (F f = new F(initial)) {
            f.arm(initial);
            f.sampleAfter(16000, "PAUSED_PLAYBACK", END, END);
            f.three(); f.noNext("first terminal candidate after stale16s forward evidence stays paused");
        }
    }
    private static void expiredCandidate() {
        try (F f = new F(0)) {
            f.arm(0); f.paused();
            f.sampleAfter(15000, "PAUSED_PLAYBACK", END, END);
            f.sampleAfter(15001, "PAUSED_PLAYBACK", END, END);
            f.three(); f.noNext("terminal confirmation beyond30s is discarded, not restarted from stale progress");
        }
    }
    private static void candidateDurationChanges() {
        for (long replacement : new long[]{0, END - 1000, END + 1000}) try (F f = new F(0)) {
            f.arm(0); f.paused();
            f.sample("PAUSED_PLAYBACK", replacement, replacement);
            f.three(); f.noNext("candidate duration changes discard forward proof even if old duration returns");
        }
    }
    private static void strictRawFinalTiming() {
        for (long position : new long[]{0, END - 1, END + 1}) try (F f = new F(0)) {
            f.arm(0);
            for (int i = 0; i < 6; i++) f.sample("PAUSED_PLAYBACK", position, END);
            f.noNext("only exact positive terminal position equals duration qualifies");
        }
    }
    private static void fixedAndSinglePoint() {
        for (long initial : new long[]{0, END}) try (F f = new F(initial, "Zeekr-8507_投屏", END, 0)) {
            for (int i = 0; i < 5; i++) f.sample("PLAYING", END, initial);
            f.three(); f.noNext("fixed PLAYING108000 even after adopted108000 does not fabricate forward evidence");
        }
        try (F f = new F(0, "Zeekr-8507_投屏", 106000, 0)) {
            f.three(); f.noNext("adopted single near-end position without observed increase does not autoend");
        }
    }
    private static void backwardClockAndPosition() {
        try (F f = new F(0)) {
            f.arm(0); f.clock.now = 0; f.three();
            f.noNext("terminal timestamp before recorded forward progress invalidates proof");
        }
        try (F f = new F(0)) {
            f.arm(0); f.paused();
            f.sampleAfter(-1000, "PAUSED_PLAYBACK", END, END);
            f.three(); f.noNext("confirmation clock moving before first candidate invalidates proof");
        }
        try (F f = new F(0)) {
            f.arm(0); f.sample("PLAYING", 10000, 0);
            f.three(); f.noNext("backwards media position removes old forward proof");
        }
    }
    private static void sameInstantAndTimeGate() {
        try (F f = new F(0)) {
            f.arm(0);
            for (int i = 0; i < 5; i++) f.sampleAfter(0, "PAUSED_PLAYBACK", END, END);
            f.noNext("repeated same-instant terminal samples do not satisfy elapsed time gate");
            f.paused(); f.noNext("one elapsed second remains insufficient");
            f.paused(); f.next("at least three samples spanning2s can confirm");
        }
    }
    private static void ordinaryReceiver() {
        for (String name : Arrays.asList("普通电视", "Zeekr", "FakeZeekr-8507_投屏", "Zeekr-8507_投屏 extra"))
            try (F f = new F(0, name, 0, 0)) {
                f.arm(0); for (int i = 0; i < 6; i++) f.paused();
                f.noNext("unknown-duration compatibility remains restricted to exact known device profile");
            }
    }
    private static void incompatibleStateAndUri() {
        for (String state : Arrays.asList("TRANSITIONING", "STOPPED", "NO_MEDIA_PRESENT", "BUFFERING", "UNKNOWN", "PAUSED_PLAYBACK"))
            try (F f = new F(0)) {
                f.arm(0); f.paused(); f.sample(state, 1000, END); f.three();
                f.noNext("intervening nonterminal or middle-position state invalidates candidate: " + state);
            }
        for (String foreign : Arrays.asList("", "http://192.168.1.9/unrelated.mp4")) try (F f = new F(0)) {
            f.arm(0); f.paused(); String own = f.receiver.control.uri;
            f.receiver.control.uri = foreign; f.paused(); f.receiver.control.uri = own; f.three();
            f.noNext("empty or foreign current URI cannot resume previous confirmation evidence");
        }
    }
    private static void pollFailure() {
        try (F f = new F(0)) {
            f.arm(0); f.paused();
            f.receiver.control.failStatus = true; f.clock.tick();
            f.receiver.control.failStatus = false; f.three();
            f.noNext("single status exception discards candidate even before disconnected threshold");
        }
    }
    private static void explicitPauseAndSeek() {
        try (F f = new F(0)) {
            f.arm(0); f.session.pause(); f.three();
            f.noNext("explicit phone pause is never interpreted as automatic EOF");
        }
        try (F f = new F(0)) {
            f.arm(0); f.session.seek(END);
            for (int i = 0; i < 5; i++) f.sample("PLAYING", END, 0);
            f.three(); f.noNext("seek-to-end cannot reuse preseek unknown-duration progress");
            f.session.seek(102000); f.sample("PLAYING", 104000, 0); f.sample("PLAYING", 106000, 0);
            f.three(); f.next("fresh real progress after seek can rearm a later natural end");
        }
        try (F f = new F(0, "Zeekr-8507_投屏", 0, 106000)) {
            for (int i = 0; i < 5; i++) f.sample("PLAYING", END, 0);
            f.three(); f.noNext("automatic resume seek also invalidates initial publication position proof");
        }
    }
    private static void disabledAndFailedNext() {
        try (F f = new F(0)) {
            f.arm(0); f.session.setAutoplay(false); f.three();
            f.noNext("disabled autoplay never prepares next episode");
        }
        try (F f = new F(0)) {
            f.arm(0); f.source.fail = 18; f.three(); f.next("EOF targets immediate position18 only");
            f.clock.prepare(1, false);
            check(!f.session.snapshot().preparing && f.session.snapshot().current == 17
                && f.session.snapshot().targetCurrent == 18 && !f.session.snapshot().error.isEmpty(),
                "next-source failure preserves old current and retryable target without skip");
            for (int i = 0; i < 8; i++) f.paused();
            check(f.clock.preparations.size() == 2 && f.source.confirmed.equals(Arrays.asList(17)),
                "source failure never causes auto-retry storm, phantom adoption or skipping");
        }
    }
    private static void intent(F f, String action) {
        switch (action) {
            case "pause": f.session.pause(); break;
            case "seek": f.session.seek(20000); break;
            case "off": f.session.setAutoplay(false); break;
            case "select": f.session.select(21); break;
            case "stop": f.session.stop(); break;
            case "play": f.session.play(); break;
            default: throw new AssertionError(action);
        }
    }
    private static void verifyIntent(F f, String action) {
        if (action.equals("select")) {
            check(f.clock.preparations.size() == 2 && f.session.snapshot().targetCurrent == 21,
                "manual selection alone owns selected21 instead of automatic18");
        } else {
            check(f.clock.preparations.size() == 1, "new user intent cancels automatic continuation: " + action);
            if (action.equals("off")) check(!f.session.snapshot().autoplay, "autoplay-off applies");
            if (action.equals("seek")) check(f.receiver.control.sought.contains(20000L), "requested seek applies");
            if (action.equals("stop")) check(!f.session.snapshot().active, "requested stop closes supply");
        }
    }
    private static void intentDuringStatus() {
        for (String action : Arrays.asList("pause", "seek", "off", "select", "stop", "play")) try (F f = new F(0)) {
            f.arm(0); f.paused(); f.paused();
            f.clock.queueCommands = true;
            f.receiver.onStatus = () -> intent(f, action);
            f.paused(); f.clock.flush(); f.clock.queueCommands = false;
            verifyIntent(f, action);
        }
    }
    private static void intentAfterAutoQueued() {
        for (String action : Arrays.asList("pause", "seek", "off", "select", "stop", "play")) try (F f = new F(0)) {
            f.arm(0); f.paused(); f.paused();
            f.clock.queueCommands = true; f.paused();
            check(f.clock.commands.size() == 1 && f.clock.preparations.size() == 1,
                "confirmed EOF queues continuation without advancing generation prematurely");
            intent(f, action); f.clock.flush(); f.clock.queueCommands = false;
            verifyIntent(f, action);
        }
    }
    private static void readOnlyDiagnostics() throws Exception {
        try (F f = new F(0)) {
            java.lang.reflect.Method method = DlnaSession.class.getMethod("diagnostics");
            for (int i = 0; i < 8; i++) f.sample("PLAYING", 97000 + i * 1000, 0);
            f.paused();
            String value = (String) method.invoke(f.session);
            check(value.contains("profile=ZEEKR") && value.contains("推进距今ms=1000")
                && value.contains("末尾样本=1/3") && value.contains("原因=VERIFYING"),
                "diagnostics reports profile, actual forward age, candidate sample count and reason");
            String trace = value.substring(value.lastIndexOf('：') + 1);
            String[] samples = trace.split(" > ");
            check(samples.length == 6 && samples[0].equals("PLAY=:100000/0@1000")
                && samples[5].equals("PAUSE=:108000/108000@1000"),
                "diagnostics retains exactly the latest6 raw samples with own URI marker and elapsed milliseconds");
            int statuses = f.receiver.control.statuses, plays = f.receiver.control.plays;
            int preparations = f.clock.preparations.size();
            for (int i = 0; i < 10; i++) check(value.equals(method.invoke(f.session)),
                "repeated diagnostics reads return immutable observed snapshot");
            check(f.receiver.control.statuses == statuses && f.receiver.control.plays == plays
                && f.clock.preparations.size() == preparations,
                "diagnostics never polls receiver, plays or initiates any preparation");
            String own = f.receiver.control.uri;
            f.receiver.control.uri = "http://secret.example/private?token=secret";
            f.sample("PLAYING", 105000, 0);
            value = (String) method.invoke(f.session);
            check(value.contains("PLAY!:105000/0@1000") && !value.contains("secret")
                && !value.contains("http:") && !value.contains("token") && !value.contains(own),
                "different URI is represented by a marker without exposing URLs or query tokens");
            f.receiver.control.uri = own;
            f.sample("STOPPED", 1000, END);
            value = (String) method.invoke(f.session);
            check(value.contains("STOP=:1000/108000@1000"),
                "raw STOPPED sampling uses compact STOP label without inferring completion");
            f.noNext("reading trace never bypasses foreign-media or middle-position terminal protections");
        }
    }
    public static void main(String[] args) {
        group("late-duration-at-paused-end-position17-to18", DlnaZeekrTimingTest::lateDuration);
        group("known-duration-zero-intermediates-known-end", DlnaZeekrTimingTest::knownZeroKnown);
        group("first-known-duration-arrives-while-playing", DlnaZeekrTimingTest::durationArrivesWhilePlaying);
        group("slow-terminal-confirmation-crosses-original15s-age", DlnaZeekrTimingTest::slowConfirmation);
        group("confirmation-exact30s-boundary", DlnaZeekrTimingTest::confirmationBoundary);
        group("unknown-duration-to-terminal-does-not-end", DlnaZeekrTimingTest::unknownForever);
        group("positive-duration-conflicts-reset-proof", DlnaZeekrTimingTest::positiveDurationConflicts);
        group("known-bound-and-late-terminal-bound", DlnaZeekrTimingTest::overrunAndFinalBounds);
        group("stale-first-candidate-does-not-arm", DlnaZeekrTimingTest::staleFirstCandidate);
        group("confirmation-over30s-expires", DlnaZeekrTimingTest::expiredCandidate);
        group("confirmation-duration-change-resets-proof", DlnaZeekrTimingTest::candidateDurationChanges);
        group("raw-exact-terminal-timing-required", DlnaZeekrTimingTest::strictRawFinalTiming);
        group("fixed-EOF-or-single-point-is-not-progress", DlnaZeekrTimingTest::fixedAndSinglePoint);
        group("backwards-clock-and-position-reset-proof", DlnaZeekrTimingTest::backwardClockAndPosition);
        group("same-instant-samples-and2s-minimum", DlnaZeekrTimingTest::sameInstantAndTimeGate);
        group("ordinary-TV-profile-unchanged", DlnaZeekrTimingTest::ordinaryReceiver);
        group("intervening-state-or-URI-invalidates-proof", DlnaZeekrTimingTest::incompatibleStateAndUri);
        group("single-status-error-invalidates-proof", DlnaZeekrTimingTest::pollFailure);
        group("explicit-pause-seek-resume-remain-protected", DlnaZeekrTimingTest::explicitPauseAndSeek);
        group("autoplay-off-and-next-source-failure", DlnaZeekrTimingTest::disabledAndFailedNext);
        group("user-intent-during-blocked-status", DlnaZeekrTimingTest::intentDuringStatus);
        group("user-intent-after-automatic-task-queued", DlnaZeekrTimingTest::intentAfterAutoQueued);
        group("diagnostics-raw-last6-samples-read-only-without-URLs", DlnaZeekrTimingTest::readOnlyDiagnostics);
        System.out.println("DLNA_ZEEKR_TIMING_" + (failed == 0 ? "OK" : "FAILED")
            + " groupsPassed=" + passed + " groupsFailed=" + failed + " checks=" + checks
            + " actualSession=true zeroBasedCurrent=17 durationMs=108000 nextPosition=18"
            + " networkRequests=0 deviceTests=not_run");
        if (failed != 0) System.exit(1);
    }
}
