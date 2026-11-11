package io.github.jackotom.hongguo.android;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Process-wide announcement lane. A stuck OS resolver must never occupy playback workers. */
final class AnnouncementRequests {
    interface Fetcher { JSONObject fetch(BooleanSupplier cancelled) throws Exception; }
    private static final int MAX_LISTENERS = 16;
    private static final AnnouncementRequests SHARED = new AnnouncementRequests(
        new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
            runnable -> daemon(runnable, "HongguoAnnouncements")),
        timer(), AnnouncementClient::fetch, 12_000);

    static void fetch(Consumer<JSONObject> callback) { SHARED.request(callback); }
    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
    }
    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
            runnable -> daemon(runnable, "HongguoAnnouncementDeadline"));
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    private final Executor executor;
    private final ScheduledExecutorService timer;
    private final Fetcher fetcher;
    private final long timeoutMs;
    private Flight active;
    AnnouncementRequests(Executor executor, ScheduledExecutorService timer, Fetcher fetcher, long timeoutMs) {
        this.executor = executor; this.timer = timer; this.fetcher = fetcher; this.timeoutMs = timeoutMs;
    }
    private final class Flight {
        final List<Consumer<JSONObject>> callbacks = new ArrayList<>();
        volatile boolean finished;
        ScheduledFuture<?> timeout;
        final FutureTask<Void> task = new FutureTask<>(() -> {
            JSONObject result = null;
            try { result = fetcher.fetch(() -> finished); } catch (Exception ignored) { }
            finally { finish(this, result, false); }
            return null;
        });
    }

    /** Coalesce the normal/emergency checks; no network queue and no per-page resolver pool. */
    void request(Consumer<JSONObject> callback) {
        Flight flight = null;
        boolean rejected = false;
        synchronized (this) {
            if (active != null) {
                if (active.callbacks.size() < MAX_LISTENERS) { active.callbacks.add(callback); return; }
                rejected = true;
            } else {
                flight = new Flight(); active = flight; flight.callbacks.add(callback);
                final Flight scheduled = flight;
                try {
                    flight.timeout = timer.schedule(() -> finish(scheduled, null, true), timeoutMs, TimeUnit.MILLISECONDS);
                    executor.execute(flight.task);
                } catch (RejectedExecutionException busy) { rejected = true; }
            }
        }
        if (rejected) {
            if (flight != null) finish(flight, null, true);
            else deliver(callback, null);
        }
    }
    private void finish(Flight flight, JSONObject result, boolean cancel) {
        List<Consumer<JSONObject>> callbacks;
        synchronized (this) {
            if (flight.finished) return;
            flight.finished = true;
            if (active == flight) active = null;
            if (flight.timeout != null) flight.timeout.cancel(false);
            callbacks = new ArrayList<>(flight.callbacks); flight.callbacks.clear();
        }
        // Interrupt is best effort: Android's native DNS may ignore it. The single shared
        // worker stays occupied until DNS returns; later requests then fail fast, not queue.
        if (cancel) flight.task.cancel(true);
        for (Consumer<JSONObject> callback : callbacks) deliver(callback, result);
    }
    private static void deliver(Consumer<JSONObject> callback, JSONObject result) {
        try { callback.accept(result); } catch (RuntimeException ignored) { }
    }
}
