package io.github.jackotom.hongguo.android;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real temporary files and production policy; reflection makes unsupported baseline explicit. */
public final class CastCacheWindowTest {
    private static int checks, groups;
    private static Constructor<PlaybackCachePolicy> constructor;
    private static Method open, budget;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void reject(Checked action, String label) throws Exception {
        try { action.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("accepted: " + label);
    }
    private static void write(File file, int bytes) throws Exception { Files.write(file.toPath(), new byte[bytes]); }
    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }
    private static PlaybackCachePolicy policy(File directory) throws Exception {
        return constructor.newInstance(directory, 256L, 96L, 768L,
            (PlaybackCachePolicy.Downloader) (url, file, cap) -> { check(cap == 96, "single-file cap remains 96"); write(file, Integer.parseInt(url)); });
    }
    private static Object window(PlaybackCachePolicy policy, String sid, int index) throws Exception { return invoke(open, policy, sid, index); }
    private static void move(Object lease, int index) throws Exception { invoke(lease.getClass().getDeclaredMethod("moveTo", int.class), lease, index); }
    private static void close(Object lease) throws Exception { invoke(lease.getClass().getDeclaredMethod("close"), lease); }
    private static long budget(PlaybackCachePolicy policy) throws Exception { return (Long) invoke(budget, policy); }
    private static File fetch(PlaybackCachePolicy policy, String sid, int index, int bytes) throws Exception { return policy.fetch(sid, "v-" + index, index, Integer.toString(bytes)); }
    private static boolean exists(PlaybackCachePolicy policy, String sid, int index) throws Exception { return policy.find(sid, "v-" + index, index) != null; }
    private static long parts(File dir) { File[] files = dir.listFiles(); return files == null ? 0 : Arrays.stream(files).filter(f -> f.getName().endsWith(".part")).count(); }
    private static Thread job(Checked action, AtomicReference<Throwable> failure) { return new Thread(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } }); }
    private static void joined(Thread thread) throws Exception { thread.join(4000); check(!thread.isAlive(), "thread completes without deadlock"); }
    private static void waiting(Thread thread) throws Exception {
        for (int i = 0; i < 400; i++) { if (thread.getState() == Thread.State.WAITING) { checks++; return; } Thread.sleep(5); }
        throw new AssertionError("foreground never joined real cache flight");
    }
    private static void remove(File file) throws Exception {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) for (File child : Objects.requireNonNull(file.listFiles())) remove(child);
        Files.deleteIfExists(file.toPath());
    }
    public static void main(String[] args) throws Exception {
        try {
            constructor = PlaybackCachePolicy.class.getDeclaredConstructor(File.class, long.class, long.class, long.class, PlaybackCachePolicy.Downloader.class);
            open = PlaybackCachePolicy.class.getDeclaredMethod("openCastWindow", String.class, int.class);
            budget = PlaybackCachePolicy.class.getDeclaredMethod("budgetBytes");
        } catch (NoSuchMethodException baseline) {
            System.err.println("FAIL CastCacheWindowTest unsupported=cast-window-lease baselineHasFiveEpisodeRetention=false");
            System.exit(1); return;
        }
        File root = Files.createTempDirectory("hongguo-cast-five-cache-").toFile();
        try {
            PlaybackCachePolicy normal = policy(new File(root, "normal"));
            check(budget(normal) == 256, "idle budget remains 256");
            for (int i = 0; i < 10; i++) fetch(normal, "s", i, 10);
            normal.prune("s", 4, 1);
            for (int i = 0; i < 10; i++) check(exists(normal, "s", i) == (i >= 3 && i <= 5), "default one-ahead prune unchanged " + i);
            groups++;

            PlaybackCachePolicy active = policy(new File(root, "active"));
            Object lease = window(active, "s", 3);
            check(budget(active) == 768, "cast lease activates finite 768 budget");
            Map<Integer, File> files = new HashMap<>();
            for (int i = 2; i <= 8; i++) files.put(i, fetch(active, "s", i, 96));
            check(active.status().files == 7 && active.status().bytes == 672, "previous+current+five next full-size files fit");
            active.prune("s", 3, 1);
            active.prune("s", 100, 1); // A stale Activity/current-index callback may coexist with casting.
            for (int i = 2; i <= 8; i++) check(files.get(i).isFile(), "lease overrides old prune and retains " + i);
            File outside = fetch(active, "other", 0, 96); outside.setLastModified(1);
            File nextOutside = fetch(active, "other", 1, 96);
            check(!outside.exists() && nextOutside.exists() && active.status().bytes == 768, "budget evicts outside lease instead of second/fifth next");
            for (int i = 2; i <= 8; i++) active.protect("s", "v-" + i, i);
            active.protect("other", "v-1", 1);
            reject(() -> fetch(active, "overflow", 0, 96), "finite budget occupied by lease and reader");
            check(active.status().bytes == 768, "budget remains bounded on rejection");
            active.release("other", "v-1", 1);
            for (int i = 2; i <= 8; i++) active.release("s", "v-" + i, i);
            groups++;

            move(lease, 4);
            check(!files.get(2).exists(), "advancing drops watched history outside previous-one");
            for (int i = 3; i <= 8; i++) check(files.get(i).exists(), "sliding keeps useful next episodes " + i);
            files.put(9, fetch(active, "s", 9, 96));
            check(files.get(9).exists(), "sliding fills new fifth next");
            active.protect("s", "v-3", 3);
            File manual = new File(root, "manual-download.mp4"), unowned = new File(files.get(3).getParent(), "manual-kept.mp4");
            write(manual, 13); write(unowned, 17);
            move(lease, 20);
            check(files.get(3).exists(), "jump never removes old episode while receiver still reads");
            for (int i = 4; i <= 9; i++) check(!files.get(i).exists(), "jump evicts stale prepared episode " + i);
            for (int i = 19; i <= 25; i++) fetch(active, "s", i, 96);
            check(active.status().bytes == 768, "new seven-episode window plus held reader remains bounded");
            active.release("s", "v-3", 3); active.prune("s", 20, 1);
            check(!files.get(3).exists(), "released old receiver file is cleaned on next prune");
            close(lease); close(lease); move(lease, 50);
            check(budget(active) == 256 && active.status().bytes <= 256, "close restores default and trims; duplicate close/move do not reopen");
            check(manual.length() == 13 && unowned.length() == 17, "window/jump/close never touches manual files");
            groups++;

            PlaybackCachePolicy readers = policy(new File(root, "readers"));
            Object readerLease = window(readers, "s", 2);
            List<File> held = new ArrayList<>();
            for (int i = 1; i <= 7; i++) { held.add(fetch(readers, "s", i, 96)); readers.protect("s", "v-" + i, i); }
            close(readerLease);
            check(budget(readers) == 256 && readers.status().bytes == 672, "close defers physical shrink while readers hold bytes");
            for (File file : held) check(file.exists(), "held reader survives close");
            for (int i = 1; i <= 7; i++) readers.release("s", "v-" + i, i);
            check(readers.status().bytes <= 256, "last readers release triggers deferred budget shrink");
            reject(() -> window(readers, "s", -1), "negative window index");
            groups++;

            PlaybackCachePolicy nested = policy(new File(root, "nested"));
            Object left = window(nested, "s", 2), right = window(nested, "s", 10);
            for (int i = 1; i <= 15; i++) fetch(nested, "s", i, 10);
            nested.prune("s", 2, 1);
            check(!exists(nested, "s", 8), "nested windows do not retain gap");
            for (int i : new int[]{1, 2, 7, 9, 10, 15}) check(exists(nested, "s", i), "nested leases retain union " + i);
            close(left);
            check(budget(nested) == 768 && !exists(nested, "s", 2) && exists(nested, "s", 15), "one lease cannot revoke another lease");
            close(right); check(budget(nested) == 256, "last nested lease restores default");
            groups++;

            PlaybackCachePolicy sizing = policy(new File(root, "sizing")); Object sizingLease = window(sizing, "s", 0);
            reject(() -> fetch(sizing, "s", 5, 97), "cast mode cannot exceed unchanged single-file cap");
            check(sizing.status().files == 0 && parts(new File(root, "sizing")) == 0, "oversized transfer leaves no partial/final file");
            fetch(sizing, "s", 5, 96); check(exists(sizing, "s", 5), "exact cap accepted for fifth next");
            sizing.clear(); check(sizing.status().files == 0, "explicit clear still clears unheld window files");
            close(sizingLease); groups++;

            PlaybackCachePolicy keepHistory = policy(new File(root, "cleanup-disabled"));
            File history = fetch(keepHistory, "s", 0, 20);
            Object noCleanup = invoke(PlaybackCachePolicy.class.getDeclaredMethod("openCastWindow", String.class, int.class, boolean.class), keepHistory, "s", 20, false);
            check(history.exists(), "cleanup=false does not prune history when opening");
            File prepared = fetch(keepHistory, "s", 25, 20);
            move(noCleanup, 40);
            check(history.exists() && prepared.exists(), "cleanup=false does not prune on jump");
            close(noCleanup);
            check(budget(keepHistory) == 256 && history.exists() && prepared.exists(), "cleanup=false close keeps historical files already inside default budget");
            Object noCleanupLarge = invoke(PlaybackCachePolicy.class.getDeclaredMethod("openCastWindow", String.class, int.class, boolean.class), keepHistory, "s", 40, false);
            for (int i = 40; i <= 45; i++) fetch(keepHistory, "s", i, 96);
            check(keepHistory.status().bytes > 256 && keepHistory.status().bytes <= 768, "cleanup=false still respects finite active budget");
            close(noCleanupLarge);
            check(keepHistory.status().bytes <= 256, "cleanup=false close shrinks only by default LRU capacity");
            groups++;

            PlaybackCachePolicy quality = policy(new File(root, "quality-window")); Object qualityWindow = window(quality, "s", 1);
            List<File> oldQuality = new ArrayList<>();
            for (int i = 0; i <= 6; i++) { File f = quality.fetch("s", "v-" + i + "|quality=1080p", i, "96"); f.setLastModified(i + 1); oldQuality.add(f); }
            quality.protect("s", "v-1|quality=1080p", 1); // The receiver still reads this original file.
            for (int i = 2; i <= 6; i++) {
                File replacement = quality.fetch("s", "v-" + i + "|quality=720p", i, "96");
                quality.protect("s", "v-" + i + "|quality=720p", i); // New ready items own leases.
                check(replacement.isFile() && quality.status().bytes <= 768, "changed quality replaces released old variants under hard budget " + i);
            }
            check(oldQuality.get(1).exists(), "old currently-read quality survives new quality fill");
            check(oldQuality.stream().filter(File::exists).count() < 7, "released old quality is not immortal merely because index lies in window");
            close(qualityWindow);
            quality.release("s", "v-1|quality=1080p", 1);
            for (int i = 2; i <= 6; i++) quality.release("s", "v-" + i + "|quality=720p", i);
            check(quality.status().bytes <= 256, "quality owners release restores default capacity");
            groups++;

            for (int scenario = 0; scenario < 3; scenario++) {
                File dir = new File(root, "pending-" + scenario); PlaybackCachePolicy pending = policy(dir); Object pendingLease = window(pending, "s", 3);
                CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread job = job(() -> pending.fetch("s", "v-8", 8, "url", (u, f, cap) -> { write(f, 20); entered.countDown(); if (!finish.await(3, TimeUnit.SECONDS)) throw new IOException("fixture timeout"); }), failure);
                job.start(); check(entered.await(3, TimeUnit.SECONDS), "real fifth-next part starts");
                if (scenario == 0) pending.prune("s", 3, 1);
                else if (scenario == 1) move(pendingLease, 20);
                else close(pendingLease);
                finish.countDown(); joined(job);
                check((failure.get() == null) == (scenario == 0), "pending old prune survives; jump/close cancels " + scenario);
                check(exists(pending, "s", 8) == (scenario == 0) && parts(dir) == 0, "completion cannot resurrect obsolete prefetch " + scenario);
                close(pendingLease);
            }
            groups++;

            for (int scenario = 0; scenario < 3; scenario++) {
                final int kind = scenario; File dir = new File(root, "takeover-" + scenario); PlaybackCachePolicy handoff = policy(dir);
                Object handoffLease = window(handoff, "s", 2); CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
                AtomicInteger oldCalls = new AtomicInteger(), newCalls = new AtomicInteger(); AtomicReference<Throwable> oldError = new AtomicReference<>(), newError = new AtomicReference<>(); AtomicReference<File> result = new AtomicReference<>();
                Thread oldOwner = job(() -> handoff.fetch("s", "v-2", 2, "old", (u, f, cap) -> {
                    oldCalls.incrementAndGet(); write(f, 3); entered.countDown(); if (!finish.await(3, TimeUnit.SECONDS)) throw new IOException("fixture timeout");
                    if (kind == 0) throw new InterruptedIOException("old Activity prefetch cancelled");
                    if (kind == 1) throw new IOException("old generation cancelled", new InterruptedException("old"));
                    throw new IOException("old HTTP failure");
                }), oldError);
                oldOwner.start(); check(entered.await(3, TimeUnit.SECONDS), "old generation flight entered"); handoff.protect("s", "v-2", 2);
                Thread foreground = job(() -> result.set(handoff.fetchForeground("s", "v-2", 2, "fresh", (u, f, cap) -> { newCalls.incrementAndGet(); check(u.equals("fresh"), "new foreground supplies fresh URL"); write(f, 21); })), newError);
                foreground.start(); waiting(foreground); finish.countDown(); joined(oldOwner); joined(foreground);
                check(oldError.get() != null && newError.get() == null, "only old generation owns old cancellation " + scenario);
                check(oldCalls.get() == 1 && newCalls.get() == 1 && result.get().length() == 21, "new foreground takes over once " + scenario);
                handoff.release("s", "v-2", 2); close(handoffLease);
            }
            groups++;

            PlaybackCachePolicy interrupted = policy(new File(root, "interrupted-waiter")); CountDownLatch start = new CountDownLatch(1), end = new CountDownLatch(1);
            AtomicReference<Throwable> oldError = new AtomicReference<>(), frontError = new AtomicReference<>(); AtomicInteger calls = new AtomicInteger(); AtomicBoolean preserved = new AtomicBoolean();
            Thread owner = job(() -> interrupted.fetch("s", "v-2", 2, "old", (u, f, cap) -> { calls.incrementAndGet(); start.countDown(); if (!end.await(3, TimeUnit.SECONDS)) throw new IOException("fixture timeout"); write(f, 2); }), oldError);
            owner.start(); check(start.await(3, TimeUnit.SECONDS), "interrupted-waiter owner starts");
            Thread front = new Thread(() -> { try { interrupted.fetchForeground("s", "v-2", 2, "new", (u, f, cap) -> { calls.incrementAndGet(); write(f, 4); }); } catch (Exception error) { frontError.set(error); preserved.set(Thread.currentThread().isInterrupted()); } });
            front.start(); waiting(front); front.interrupt(); joined(front); end.countDown(); joined(owner);
            check(frontError.get() != null && preserved.get() && calls.get() == 1 && oldError.get() == null, "current foreground interruption remains terminal without takeover");
            groups++;
            System.out.println("PASS CastCacheWindowTest groups=" + groups + " checks=" + checks + " ahead=5 previous=1 budgets=256/768 maxFile=96 realTemporaryFiles=true networkRequests=0 deviceOperations=0");
        } finally { remove(root); }
    }
}
