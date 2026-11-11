package io.github.jackotom.hongguo.android;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** Real executors with a deliberately uninterruptible resolver stand-in; no network or media. */
public final class AnnouncementRequestsTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void await(CountDownLatch latch, String message) throws Exception { check(latch.await(2, TimeUnit.SECONDS), message); }
    private static ThreadPoolExecutor worker() {
        return new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    }
    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1); timer.setRemoveOnCancelPolicy(true); return timer;
    }
    private static void released(ThreadPoolExecutor worker) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (worker.getActiveCount() != 0 && System.nanoTime() < until) Thread.sleep(2);
        check(worker.getActiveCount() == 0, "released worker finishes");
    }
    public static void main(String[] args) throws Exception {
        check(AnnouncementClient.fetch(() -> true).length() == 0, "cancelled fetch makes no network request");
        successAndCoalescing();
        blockedResolver();
        rejectedScheduling();
        System.out.println("ANNOUNCEMENT_REQUESTS_OK checks=" + checks + " networkRequests=0");
    }
    private static void successAndCoalescing() throws Exception {
        ThreadPoolExecutor worker = worker(); ScheduledThreadPoolExecutor timer = timer();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), replies = new CountDownLatch(3);
        AtomicInteger fetches = new AtomicInteger(), successful = new AtomicInteger();
        JSONObject value = new JSONObject().put("normal", "normal notice").put("emergency", "urgent notice");
        AnnouncementRequests requests = new AnnouncementRequests(worker, timer, cancelled -> {
            fetches.incrementAndGet(); entered.countDown(); release.await(); return value;
        }, 1000);
        try {
            requests.request(result -> { if (result == value) successful.incrementAndGet(); replies.countDown(); });
            await(entered, "fetch starts");
            requests.request(result -> { if (result == value) successful.incrementAndGet(); replies.countDown(); throw new IllegalStateException("callback fixture"); });
            requests.request(result -> { if (result == value) successful.incrementAndGet(); replies.countDown(); });
            check(fetches.get() == 1, "normal/emergency/manual callers share the same fetch");
            release.countDown(); await(replies, "all coalesced callbacks delivered despite a throwing callback");
            check(successful.get() == 3, "both channel texts retained for every caller");
            released(worker);
            CountDownLatch retry = new CountDownLatch(1); requests.request(result -> retry.countDown()); await(retry, "fresh request after success completes");
            check(fetches.get() == 2, "no stale result cache");
            released(worker); check(timer.getQueue().isEmpty(), "successful requests remove deadline tasks");
        } finally { release.countDown(); worker.shutdownNow(); timer.shutdownNow(); }

        worker = worker(); timer = timer();
        try {
            AnnouncementRequests failures = new AnnouncementRequests(worker, timer, cancelled -> { throw new IllegalStateException("fetch fixture"); }, 1000);
            AtomicInteger nulls = new AtomicInteger(); CountDownLatch failed = new CountDownLatch(1);
            failures.request(result -> { if (result == null) nulls.incrementAndGet(); failed.countDown(); });
            await(failed, "fetch exception returns unavailable"); check(nulls.get() == 1, "failure delivered exactly once");
            released(worker); check(timer.getQueue().isEmpty(), "failed fetch removes deadline task");
        } finally { worker.shutdownNow(); timer.shutdownNow(); }
    }
    private static void blockedResolver() throws Exception {
        ThreadPoolExecutor worker = worker(); ScheduledThreadPoolExecutor timer = timer();
        ExecutorService playback = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), deadlines = new CountDownLatch(16);
        AtomicInteger fetches = new AtomicInteger(), replies = new AtomicInteger(), nulls = new AtomicInteger();
        AtomicBoolean sawCancellation = new AtomicBoolean();
        AnnouncementRequests requests = new AnnouncementRequests(worker, timer, cancelled -> {
            if (fetches.incrementAndGet() > 1) return new JSONObject();
            entered.countDown();
            // Like native getaddrinfo, this stand-in intentionally ignores interrupts.
            while (release.getCount() != 0) { try { release.await(); } catch (InterruptedException ignored) { } }
            sawCancellation.set(cancelled.getAsBoolean()); return new JSONObject();
        }, 150);
        try {
            requests.request(result -> { replies.incrementAndGet(); if (result == null) nulls.incrementAndGet(); deadlines.countDown(); });
            await(entered, "uninterruptible lookup starts");
            for (int i = 1; i < 16; i++) requests.request(result -> { replies.incrementAndGet(); if (result == null) nulls.incrementAndGet(); deadlines.countDown(); });
            CountDownLatch overCapacity = new CountDownLatch(1);
            requests.request(result -> { if (result == null) nulls.incrementAndGet(); overCapacity.countDown(); });
            await(overCapacity, "listener cap fails fast");
            check(playback.submit(() -> "getInfo").get(1, TimeUnit.SECONDS).equals("getInfo"), "control remains runnable during hung announcement");
            check(playback.submit(() -> "play").get(1, TimeUnit.SECONDS).equals("play"), "playback remains runnable during hung announcement");
            await(deadlines, "all coalesced callers receive deadline response");
            check(replies.get() == 16 && nulls.get() == 17, "timeout returns unavailable once to each caller");
            check(worker.getActiveCount() == 1 && worker.getQueue().isEmpty(), "physical blocked lookup still occupies only shared lane");
            for (int i = 0; i < 100; i++) {
                AtomicInteger immediate = new AtomicInteger();
                requests.request(result -> { if (result == null) immediate.incrementAndGet(); });
                check(immediate.get() == 1, "later/page-reload request rejects promptly without releasing stuck worker");
            }
            check(fetches.get() == 1 && worker.getLargestPoolSize() == 1, "retries cannot spawn replacement resolver workers");
            check(timer.getQueue().isEmpty(), "rejection deadlines removed, no timer backlog");
            release.countDown(); released(worker);
            check(sawCancellation.get(), "late fetch sees cancelled state even when interrupt was swallowed");
            check(replies.get() == 16, "late success cannot reply after timeout");
            CountDownLatch recovered = new CountDownLatch(1); AtomicBoolean hasResult = new AtomicBoolean();
            requests.request(result -> { hasResult.set(result != null); recovered.countDown(); });
            await(recovered, "lane recovers after native lookup releases");
            check(hasResult.get() && fetches.get() == 2, "fresh request works after recovery");
        } finally { release.countDown(); worker.shutdownNow(); timer.shutdownNow(); playback.shutdownNow(); }
    }
    private static void rejectedScheduling() throws Exception {
        ScheduledThreadPoolExecutor timer = timer(); timer.shutdownNow(); AtomicInteger network = new AtomicInteger(), replies = new AtomicInteger();
        AnnouncementRequests requests = new AnnouncementRequests(task -> network.incrementAndGet(), timer, cancelled -> new JSONObject(), 1000);
        requests.request(result -> { if (result == null) replies.incrementAndGet(); });
        requests.request(result -> { if (result == null) replies.incrementAndGet(); });
        check(network.get() == 0 && replies.get() == 2, "timer rejection leaves no active request and never runs network inline");
    }
}
