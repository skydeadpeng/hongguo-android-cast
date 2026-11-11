package io.github.jackotom.hongguo.android;

import java.util.Arrays;
import java.io.File;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Uses the actual coordinator and the existing deterministic, network-free receiver fixture. */
final class DlnaAutoplayCompatibilityTest {
    static int checks, groups, failed;
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    interface Scenario { void run() throws Exception; }
    static void group(String name, Scenario test) {
        groups++;
        try { test.run(); System.out.println("PASS " + name); }
        catch (AssertionError failure) { failed++; System.out.println("FAIL " + name + ": " + failure.getMessage()); }
        catch (Exception failure) { failed++; System.out.println("FAIL " + name + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
    }
    static DlnaSessionTest.Fixture started() { DlnaSessionTest.Fixture f = new DlnaSessionTest.Fixture(); f.start(); return f; }
    static void tail(DlnaSessionTest.Fixture f) { f.control.position = 99000; f.control.duration = 100000; f.control.state = "PLAYING"; f.clock.tick(); }
    static void clearTerminal(DlnaSessionTest.Fixture f, String state) { f.control.state = state; f.control.position = 0; f.control.duration = 0; f.control.uri = ""; }
    static void twoTicks(DlnaSessionTest.Fixture f) { f.clock.tick(); f.clock.tick(); }
    static void nextPrepared(DlnaSessionTest.Fixture f, String label) { check(f.clock.preparations.size() == 2 && f.session.snapshot().preparing && f.session.snapshot().targetCurrent == 1, label); }
    static void stays(DlnaSessionTest.Fixture f, String label) { check(f.clock.preparations.size() == 1 && !f.session.snapshot().preparing && !"ENDED".equals(f.session.snapshot().state), label); }
    static void normalSameUri() {
        DlnaSessionTest.Fixture f = started(); tail(f); f.control.state = "STOPPED"; f.clock.tick();
        nextPrepared(f, "standard same URI near-end PLAYING then STOPPED still advances immediately");
        f.clock.tick(); check(f.clock.preparations.size() == 2, "repeated STOPPED queues no duplicate next"); f.clock.prepare(1, false);
        check(f.session.snapshot().current == 1 && f.source.confirmed.equals(Arrays.asList(0, 1)), "next independently confirms exactly once"); f.session.stop();
    }
    static void terminalFirstReportsTail() {
        DlnaSessionTest.Fixture f = started(); f.control.state = "STOPPED"; f.control.position = 100000; f.control.duration = 100000; twoTicks(f);
        nextPrepared(f, "first tail position in same-URI terminal sample can complete observed playing episode"); f.session.stop();
    }
    static void transitioningRetainsTail() {
        DlnaSessionTest.Fixture f = started(); tail(f); f.control.state = "TRANSITIONING"; f.control.position = 0; f.control.duration = 0; f.clock.tick();
        stays(f, "TRANSITIONING alone never completes episode"); f.control.state = "STOPPED"; twoTicks(f);
        nextPrepared(f, "recent tail survives TRANSITIONING before STOPPED"); f.session.stop();
    }
    static void stoppedClearsTiming() {
        DlnaSessionTest.Fixture f = started(); tail(f); f.control.state = "STOPPED"; f.control.position = 0; f.control.duration = 0; twoTicks(f);
        nextPrepared(f, "near-end evidence survives terminal position and duration reset"); f.session.stop();
    }
    static void stoppedClearsEverything() {
        DlnaSessionTest.Fixture f = started(); tail(f); clearTerminal(f, "STOPPED"); f.clock.tick();
        check(f.clock.preparations.size() == 1, "empty-URI terminal needs a second terminal observation"); f.clock.tick();
        nextPrepared(f, "second consistent empty-URI STOPPED completes recent near-end episode");
        f.clock.tick(); check(f.clock.preparations.size() == 2, "empty-URI repeated terminal still queues only once"); f.session.stop();
    }
    static void noMediaSameUri() {
        DlnaSessionTest.Fixture f = started(); tail(f); f.control.state = "NO_MEDIA_PRESENT"; f.control.position = 0; f.control.duration = 0; f.clock.tick();
        check(f.clock.preparations.size() == 1, "NO_MEDIA_PRESENT with original URI requires two terminal observations"); f.clock.tick();
        nextPrepared(f, "confirmed NO_MEDIA_PRESENT after recent tail advances"); f.session.stop();
    }
    static void noMediaClearsEverything() {
        DlnaSessionTest.Fixture f = started(); tail(f); clearTerminal(f, "NO_MEDIA_PRESENT"); f.clock.tick();
        check(f.clock.preparations.size() == 1, "NO_MEDIA_PRESENT with empty URI never advances on first terminal"); f.clock.tick();
        nextPrepared(f, "repeated cleared NO_MEDIA_PRESENT after recent tail advances"); f.session.stop();
    }
    static void transitionedEmptyTerminal() {
        DlnaSessionTest.Fixture f = started(); tail(f); f.control.state = "TRANSITIONING"; f.control.position = 0; f.control.duration = 0; f.clock.tick();
        clearTerminal(f, "STOPPED"); f.clock.tick(); check(f.clock.preparations.size() == 1, "transition then cleared terminal still requires confirmation"); f.clock.tick();
        nextPrepared(f, "transition then repeated cleared terminal keeps recent valid tail"); f.session.stop();
    }
    static void autoplayOffAndLastEpisode() {
        DlnaSessionTest.Fixture off = started(); off.session.setAutoplay(false); tail(off); clearTerminal(off, "STOPPED"); twoTicks(off);
        check(off.clock.preparations.size() == 1 && "ENDED".equals(off.session.snapshot().state), "autoplay off records completion without next preparation"); off.session.stop();
        DlnaSessionTest.Fixture last = new DlnaSessionTest.Fixture(); last.session.start(3, 0); last.clock.prepare(0, false); tail(last); clearTerminal(last, "NO_MEDIA_PRESENT"); twoTicks(last);
        check(last.clock.preparations.size() == 1 && last.session.snapshot().current == 3 && "ENDED".equals(last.session.snapshot().state), "last episode records completion without wrapping"); last.session.stop();
    }
    static void explicitPauseDoesNotComplete() {
        DlnaSessionTest.Fixture f = new DlnaSessionTest.Fixture();
        DlnaSession.Controller pauseReportsStopped = new DlnaSession.Controller() {
            public void setMedia(String uri, String title) throws Exception { f.control.setMedia(uri, title); }
            public void play() { f.control.play(); }
            public void pause() { f.control.pause(); f.control.state = "STOPPED"; f.control.position = 100000; }
            public void stop() throws Exception { f.control.stop(); }
            public void seek(long position) { f.control.seek(position); }
            public DlnaSession.Status status() throws Exception { return f.control.status(); }
        };
        DlnaSession session = new DlnaSession(f.clock, pauseReportsStopped, f.source, f.server, f.snapshots::add, "123456", "测试剧", "合成电视", 4, "1080p", true, true);
        session.start(0, 0); f.clock.prepare(0, false); tail(f); session.pause(); twoTicks(f);
        check(f.clock.preparations.size() == 1 && !session.snapshot().preparing && !"ENDED".equals(session.snapshot().state), "pause command whose immediate receiver feedback is near-end STOPPED never advances"); session.stop();
    }
    static void pausedReceiverDoesNotComplete() {
        DlnaSessionTest.Fixture f = started(); tail(f); f.control.state = "PAUSED_PLAYBACK"; f.clock.tick(); f.control.state = "STOPPED"; f.control.position = 0; f.control.duration = 0; twoTicks(f);
        stays(f, "receiver PAUSED_PLAYBACK invalidates natural-end evidence"); f.session.stop();
    }
    static void unrelatedStatesDoNotComplete() {
        for (String state : Arrays.asList("UNKNOWN", "BUFFERING", "PAUSED_RECORDING", "RECORDING")) {
            DlnaSessionTest.Fixture f = started(); tail(f); f.control.state = state; f.clock.tick(); f.control.state = "STOPPED"; f.control.position = 0; f.control.duration = 0; twoTicks(f);
            stays(f, state + " invalidates tail evidence instead of masquerading as end"); f.session.stop();
        }
    }
    static void earlyStopsDoNotComplete() {
        for (String state : Arrays.asList("STOPPED", "NO_MEDIA_PRESENT")) {
            DlnaSessionTest.Fixture f = started(); f.control.position = 1000; f.clock.tick(); clearTerminal(f, state); twoTicks(f);
            stays(f, "early " + state + " with cleared URI does not advance"); f.session.stop();
        }
        DlnaSessionTest.Fixture zero = new DlnaSessionTest.Fixture(); zero.control.duration = 0; zero.start(); zero.control.position = 99000; zero.clock.tick(); zero.control.state = "STOPPED"; twoTicks(zero);
        stays(zero, "unknown duration never advances"); zero.session.stop();
    }
    static void networkFailureInvalidatesEvidence() {
        DlnaSessionTest.Fixture f = started(); tail(f); f.control.failStatus = true; f.clock.tick(); f.control.failStatus = false; f.control.state = "STOPPED"; f.control.position = 0; f.control.duration = 0; twoTicks(f);
        stays(f, "transport error between tail and terminal never triggers automatic next"); f.session.stop();
    }
    static void foreignUriInvalidatesEvidence() {
        DlnaSessionTest.Fixture f = started(); tail(f); String own = f.control.uri; f.control.uri = "http://192.168.1.3/foreign.mp4"; f.control.state = "STOPPED"; f.clock.tick();
        check(f.clock.preparations.size() == 1, "different nonempty URI never completes our episode"); f.control.uri = own; f.control.position = 0; f.control.duration = 0; twoTicks(f);
        stays(f, "foreign URI invalidates old tail even if own URI returns stopped"); f.session.stop();
    }
    static void staleEvidenceDoesNotComplete() {
        DlnaSessionTest.Fixture f = started(); tail(f); f.clock.now += 16000; f.control.state = "STOPPED"; f.control.position = 0; f.control.duration = 0; twoTicks(f);
        stays(f, "tail evidence older than fifteen seconds does not complete"); f.session.stop();
    }
    static void recentEvidenceWithinWindowCompletes() {
        DlnaSessionTest.Fixture f = started(); tail(f); f.clock.now += 10000; clearTerminal(f, "STOPPED"); twoTicks(f);
        nextPrepared(f, "recent evidence within fifteen-second window remains usable"); f.session.stop();
    }
    static void manualSelectionLatestWins() {
        DlnaSessionTest.Fixture f = started(); tail(f); f.clock.queueCommands = true; f.session.select(2); clearTerminal(f, "STOPPED"); twoTicks(f);
        check(f.clock.preparations.size() == 1 && f.clock.commands.size() == 1, "terminal polls never overwrite queued newer manual selection"); f.clock.flush(); f.clock.queueCommands = false;
        check(f.session.snapshot().targetCurrent == 2 && f.clock.preparations.size() == 2, "latest manual target alone prepares"); f.clock.prepare(1, false);
        check(f.session.snapshot().current == 2, "manual selected URI adopts and old terminal evidence is discarded"); f.session.stop();
    }
    static void failedNextNeverSkips() {
        DlnaSessionTest.Fixture f = started(); tail(f); f.source.fail = 1; clearTerminal(f, "STOPPED"); twoTicks(f); nextPrepared(f, "natural end schedules immediate next episode"); f.clock.prepare(1, false);
        check(!f.session.snapshot().preparing && f.session.snapshot().current == 0 && f.session.snapshot().targetCurrent == 1 && !f.session.snapshot().error.isEmpty(), "next source failure remains retryable on same episode");
        clearTerminal(f, "STOPPED"); for (int i = 0; i < 5; i++) f.clock.tick();
        check(f.clock.preparations.size() == 2 && f.session.snapshot().targetCurrent == 1, "failed next neither loops preparation nor skips to another episode"); f.session.stop();
    }
    static void nextPreparationIgnoresOldStatusFailures() {
        DlnaSessionTest.Fixture f = started(); tail(f); f.control.state = "STOPPED"; f.clock.tick();
        nextPrepared(f, "natural completion starts next source preparation"); int priorPolls = f.control.statuses;
        f.control.failStatus = true; for (int i = 0; i < 5; i++) f.clock.tick();
        check(f.control.statuses == priorPolls && f.session.snapshot().preparing && "PREPARING".equals(f.session.snapshot().state), "old receiver status failures never poll or disconnect a generation still preparing its source");
        check(f.clock.preparations.size() == 2 && f.source.confirmed.equals(Arrays.asList(0)), "preparation delay does not duplicate target or prune current cache");
        f.control.failStatus = false; f.clock.prepare(1, false);
        check(f.session.snapshot().current == 1 && f.session.snapshot().playing && !f.session.snapshot().preparing && f.source.confirmed.equals(Arrays.asList(0, 1)), "new prepared URI still confirms and adopts after old receiver was unavailable"); f.session.stop();
    }
    static void compatibilityCacheHandoff() throws Exception {
        File root = Files.createTempDirectory("hongguo-dlna-autoplay-cache-").toFile();
        File directory = new File(root, "playback"); check(directory.mkdirs(), "isolated cache fixture directory exists");
        File manual = new File(directory, "manual-user-download.mp4"); byte[] sentinel = "manual-download-must-remain".getBytes(StandardCharsets.UTF_8); Files.write(manual.toPath(), sentinel);
        DlnaIntegrationTest.Clock clock = new DlnaIntegrationTest.Clock();
        DlnaIntegrationTest.Receiver receiver = new DlnaIntegrationTest.Receiver();
        DlnaIntegrationTest.PreparedCache source = new DlnaIntegrationTest.PreparedCache(directory);
        DlnaIntegrationTest.HttpServer server = new DlnaIntegrationTest.HttpServer();
        DlnaSession session = new DlnaSession(clock, receiver, source, server, ignored -> {}, DlnaIntegrationTest.SID, "Synthetic series", "Loopback fake TV", 4, "1080p", true, true);
        List<Socket> reads = new ArrayList<>();
        try {
            session.start(0, 0); clock.drain(); clock.prepareNext(); receiver.duration = 100000; DlnaIntegrationTest.confirm(clock, receiver, session, 0);
            String oldUrl = receiver.requested; Socket firstRead = DlnaIntegrationTest.held(oldUrl); reads.add(firstRead);
            DlnaIntegrationTest.await(() -> source.leases[0].get() == 2, "actual publication and old HTTP reader independently pin cache");
            for (int next = 1; next < 4; next++) {
                receiver.nearEnd(); clock.tick(); receiver.state = "TRANSITIONING"; receiver.position = 0; receiver.duration = 0; clock.tick();
                check(!session.snapshot().preparing, "transition alone never prepares next episode " + next);
                receiver.state = next % 2 == 0 ? "NO_MEDIA_PRESENT" : "STOPPED"; receiver.uri = ""; clock.tick();
                check(!session.snapshot().preparing, "first cleared terminal waits for independent confirmation"); clock.tick();
                check(session.snapshot().preparing && session.snapshot().targetCurrent == next && session.snapshot().current == next - 1, "second cleared terminal prepares exactly the immediate next episode");
                for (int i = 0; i < 3; i++) clock.tick();
                check(clock.preparations.size() == 1 && source.downloads == next, "repeated terminal polls while preparing neither duplicate nor skip downloads");
                clock.prepareNext(); String nextUrl = receiver.requested;
                check(DlnaIntegrationTest.status(oldUrl) == 206 && session.snapshot().current == next - 1, "old URL remains readable until replacement URI confirms");
                receiver.duration = 100000; DlnaIntegrationTest.confirm(clock, receiver, session, next);
                check(DlnaIntegrationTest.status(oldUrl) == 404, "new confirmed URI retires previous endpoint"); DlnaIntegrationTest.verifyRange(nextUrl, source.files[next]); oldUrl = nextUrl;
                if (next == 2) {
                    check(source.files[0].exists() && source.leases[0].get() == 1, "out-of-window old cache survives while actual HTTP read remains pinned"); firstRead.close();
                    DlnaIntegrationTest.await(() -> source.leases[0].get() == 0, "closed old reader releases final cache pin");
                    DlnaIntegrationTest.await(() -> !source.files[0].exists(), "reader release prunes old cache only after confirmed handoff");
                }
            }
            check(source.prepared.equals(Arrays.asList(0, 1, 2, 3)) && source.confirmed.equals(Arrays.asList(0, 1, 2, 3)) && source.downloads == 4, "four compatibility completions prepare and confirm each episode once");
            check(source.cache.status().files == 2 && source.cache.status().bytes <= DlnaIntegrationTest.BUDGET && !source.files[1].exists() && source.files[2].exists() && source.files[3].exists(), "actual rolling cache retains current and previous after older readers release");
            receiver.nearEnd(); clock.tick(); receiver.state = "NO_MEDIA_PRESENT"; receiver.uri = ""; receiver.position = 0; receiver.duration = 0; clock.tick(); clock.tick();
            for (int i = 0; i < 3; i++) clock.tick();
            check("ENDED".equals(session.snapshot().state) && clock.preparations.isEmpty() && source.downloads == 4, "last episode repeated cleared terminal neither wraps nor prepares a phantom next");
            session.stop(); clock.drain(); check(source.totalPins() == 0, "session close balances every actual cache publication and HTTP lease");
            check(source.cache.clear().removed == 2 && Arrays.equals(Files.readAllBytes(manual.toPath()), sentinel), "only app cache clears after stop and manual sentinel remains intact");
        } finally {
            session.close(); server.close(); for (Socket socket : reads) try { socket.close(); } catch (Exception ignored) {}
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) { paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} }); }
        }
    }
    static void contradictoryTerminalEvidenceDoesNotComplete() {
        for (int shape = 0; shape < 3; shape++) {
            DlnaSessionTest.Fixture f = started();
            if (shape != 1) tail(f);
            else { f.control.state = "STOPPED"; f.control.position = 100000; f.clock.tick(); check(f.clock.preparations.size() == 1, "first final-only tail awaits confirmation"); }
            f.control.state = "STOPPED"; f.control.position = shape == 2 ? 0 : 10000; f.control.duration = shape == 2 ? 300000 : 100000;
            twoTicks(f); stays(f, "contradictory positive terminal progress/duration invalidates prior tail shape=" + shape); f.session.stop();
        }
    }
    static void failedSelectionRecoversCurrentAutoplay() {
        DlnaSessionTest.Fixture f = started(); f.control.position = 40000; f.clock.tick(); f.source.fail = 2; f.session.select(2); f.clock.prepare(1, false);
        check(f.session.snapshot().current == 0 && f.session.snapshot().targetCurrent == 2 && !f.session.snapshot().error.isEmpty(), "failed manual selection preserves current URI and explicit failed target");
        f.control.state = "PLAYING"; f.clock.tick(); tail(f); f.control.state = "STOPPED"; f.clock.tick();
        check(f.clock.preparations.size() == 3 && f.session.snapshot().preparing && f.session.snapshot().targetCurrent == 1, "reconfirmed old current URI resumes natural next after failed manual selection");
        f.clock.prepare(2, false); check(f.session.snapshot().current == 1 && f.source.confirmed.equals(Arrays.asList(0, 1)), "recovered autoplay confirms next in sequence without adopting failed target"); f.session.stop();
    }
    static void failedCandidateRestoreRecoversAutoplay() {
        DlnaSessionTest.Fixture f = started(); f.control.position = 40000; f.clock.tick(); f.control.confirm = false; f.session.select(2); f.clock.prepare(1, false);
        for (int i = 0; i < 11; i++) f.clock.tick(); // install already made the first confirmation poll.
        check(!f.session.snapshot().preparing && f.session.snapshot().current == 0 && !f.session.snapshot().error.isEmpty(), "candidate confirmation timeout restores prior current URI without adopting candidate");
        f.control.confirm = true; f.control.state = "PLAYING"; f.clock.tick(); tail(f); f.control.state = "STOPPED"; f.clock.tick();
        check(f.clock.preparations.size() == 3 && f.session.snapshot().targetCurrent == 1 && f.session.snapshot().preparing, "confirmed restored current URI regains autoplay epoch after candidate failure"); f.clock.prepare(2, false);
        check(f.session.snapshot().current == 1 && f.server.live.size() == 1, "restored queue adopts immediate next and retires exposed failed candidates"); f.session.stop();
    }
    static void failedRecoveryNeverOverridesNewerQueuedSelection() {
        DlnaSessionTest.Fixture f = started(); f.source.fail = 2; f.session.select(2); f.clock.prepare(1, false);
        f.clock.queueCommands = true; f.session.select(3); tail(f); clearTerminal(f, "STOPPED"); twoTicks(f);
        check(f.clock.commands.size() == 1 && f.clock.preparations.size() == 2, "recoverable old URI does not re-arm over a queued newer manual generation");
        f.clock.flush(); f.clock.queueCommands = false; check(f.session.snapshot().targetCurrent == 3 && f.clock.preparations.size() == 3, "newer manual target alone prepares after recovery opportunity"); f.clock.prepare(2, false);
        check(f.session.snapshot().current == 3 && f.source.confirmed.equals(Arrays.asList(0, 3)), "latest queued target wins without intervening automatic episode"); f.session.stop();
        DlnaSessionTest.Fixture late = started(); late.source.fail = 2; late.session.select(2); late.clock.queueCommands = true; late.clock.prepare(1, false); late.session.select(3);
        check(late.clock.commands.size() == 2, "failed preparation delivery and newer user request share deterministic control queue"); late.clock.flush(); late.clock.queueCommands = false; late.clock.prepare(2, false);
        check(late.session.snapshot().current == 3 && late.session.snapshot().error.isEmpty(), "stale failed-preparation handler never restores or overwrites newer target"); late.session.stop();
    }
    static void endedListenerManualSelectionWins() {
        for (int mode = 0; mode < 3; mode++) {
            final int deliveryMode = mode;
            DlnaSessionTest.Fixture f = new DlnaSessionTest.Fixture();
            DlnaSession[] owner = new DlnaSession[1]; int[] selections = {0};
            owner[0] = new DlnaSession(f.clock, f.control, f.source, f.server, snapshot -> {
                f.snapshots.add(snapshot);
                if ("ENDED".equals(snapshot.state) && selections[0]++ == 0) {
                    if (deliveryMode == 2) f.clock.queueCommands = true;
                    owner[0].select(2);
                    if (deliveryMode == 1) f.clock.prepare(1, false);
                }
            }, "123456", "测试剧", "合成电视", 4, "1080p", true, true);
            DlnaSession session = owner[0]; session.start(0, 0); f.clock.prepare(0, false); tail(f); f.control.state = "STOPPED"; f.clock.tick();
            check(selections[0] == 1, "natural end delivered exactly one listener selection opportunity mode=" + mode);
            if (mode == 2) {
                check(f.clock.commands.size() == 1 && f.clock.preparations.size() == 1, "manual selection queued inside ENDED listener is not followed by stale automatic selection");
                f.clock.flush(); f.clock.queueCommands = false;
            }
            if (mode != 1) {
                check(session.snapshot().targetCurrent == 2 && session.snapshot().preparing && f.clock.preparations.size() == 2, "manual target chosen reentrantly during ENDED delivery alone prepares mode=" + mode);
                f.clock.prepare(1, false);
            }
            check(session.snapshot().current == 2 && session.snapshot().targetCurrent == 2 && !session.snapshot().preparing && f.clock.preparations.size() == 2, "manual episode survives stale natural-end continuation even after synchronous adoption mode=" + mode);
            check(f.source.confirmed.equals(Arrays.asList(0, 2)) && f.control.sets == 2, "listener-selected URI alone adopts with no automatic intermediate or later episode mode=" + mode);
            session.stop();
        }
    }
    public static void main(String[] args) {
        group("same-uri-standard-end", DlnaAutoplayCompatibilityTest::normalSameUri);
        group("terminal-first-tail-position", DlnaAutoplayCompatibilityTest::terminalFirstReportsTail);
        group("transitioning-retains-recent-tail", DlnaAutoplayCompatibilityTest::transitioningRetainsTail);
        group("stopped-resets-timing", DlnaAutoplayCompatibilityTest::stoppedClearsTiming);
        group("stopped-resets-uri-and-timing", DlnaAutoplayCompatibilityTest::stoppedClearsEverything);
        group("no-media-same-uri-confirmed-twice", DlnaAutoplayCompatibilityTest::noMediaSameUri);
        group("no-media-cleared-uri-confirmed-twice", DlnaAutoplayCompatibilityTest::noMediaClearsEverything);
        group("transitioning-then-empty-terminal", DlnaAutoplayCompatibilityTest::transitionedEmptyTerminal);
        group("autoplay-off-and-last-episode", DlnaAutoplayCompatibilityTest::autoplayOffAndLastEpisode);
        group("explicit-pause-does-not-complete", DlnaAutoplayCompatibilityTest::explicitPauseDoesNotComplete);
        group("receiver-pause-invalidates-evidence", DlnaAutoplayCompatibilityTest::pausedReceiverDoesNotComplete);
        group("unrelated-states-invalidate-evidence", DlnaAutoplayCompatibilityTest::unrelatedStatesDoNotComplete);
        group("early-stop-and-unknown-duration", DlnaAutoplayCompatibilityTest::earlyStopsDoNotComplete);
        group("network-error-invalidates-evidence", DlnaAutoplayCompatibilityTest::networkFailureInvalidatesEvidence);
        group("foreign-uri-invalidates-evidence", DlnaAutoplayCompatibilityTest::foreignUriInvalidatesEvidence);
        group("stale-tail-evidence-expires", DlnaAutoplayCompatibilityTest::staleEvidenceDoesNotComplete);
        group("recent-tail-evidence-remains-valid", DlnaAutoplayCompatibilityTest::recentEvidenceWithinWindowCompletes);
        group("queued-manual-selection-latest-wins", DlnaAutoplayCompatibilityTest::manualSelectionLatestWins);
        group("failed-next-never-skips", DlnaAutoplayCompatibilityTest::failedNextNeverSkips);
        group("next-preparation-ignores-old-status-failures", DlnaAutoplayCompatibilityTest::nextPreparationIgnoresOldStatusFailures);
        group("actual-cache-and-http-compatible-handoff", DlnaAutoplayCompatibilityTest::compatibilityCacheHandoff);
        group("contradictory-terminal-progress-invalidates-tail", DlnaAutoplayCompatibilityTest::contradictoryTerminalEvidenceDoesNotComplete);
        group("failed-selection-reconfirmed-current-autoplay", DlnaAutoplayCompatibilityTest::failedSelectionRecoversCurrentAutoplay);
        group("failed-candidate-restored-current-autoplay", DlnaAutoplayCompatibilityTest::failedCandidateRestoreRecoversAutoplay);
        group("failed-recovery-newer-manual-generation-wins", DlnaAutoplayCompatibilityTest::failedRecoveryNeverOverridesNewerQueuedSelection);
        group("ended-listener-manual-selection-beats-autoplay", DlnaAutoplayCompatibilityTest::endedListenerManualSelectionWins);
        System.out.println("DLNA_AUTOPLAY_COMPATIBILITY_RESULT groups=" + groups + " failed=" + failed + " checks=" + checks + " actualProductionSession=true fakeReceiver=true actualCacheAndHttpHandoff=true lanNetworkRequests=0 loopbackOnly=true deviceTests=not_run");
        if (failed > 0) System.exit(1);
    }
}
