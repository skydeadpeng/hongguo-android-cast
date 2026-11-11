package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real temporary files and threads, no Android SDK or network, never a user's cache. */
public final class PlaybackCachePolicyTest {
    private static int checks;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void fails(Checked action, String label) throws Exception {
        try { action.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("must fail: " + label);
    }
    private static void write(File file, int size) throws Exception { try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[size]); } }
    private static PlaybackCachePolicy cache(File root, String name, long budget, long max) {
        return new PlaybackCachePolicy(new File(root, name), budget, max, (url, file, limit) -> write(file, Integer.parseInt(url)));
    }
    private static long parts(File directory) { File[] list = directory.listFiles(); return list == null ? 0 : Arrays.stream(list).filter(f -> f.getName().endsWith(".part")).count(); }
    private static void removeTree(File file) throws Exception {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) for (File child : Objects.requireNonNull(file.listFiles())) removeTree(child);
        Files.deleteIfExists(file.toPath());
    }
    private static Map<String, String> headers(String... values) {
        Map<String, String> result = new HashMap<>(); for (int i = 0; i < values.length; i += 2) result.put(values[i], values[i + 1]); return result;
    }
    private static byte[] stream(byte[] input, long cap, Map<String, String> headers) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long bytes = NetworkGuard.streamBody(new ByteArrayInputStream(input), output, headers, cap, System.nanoTime() + 5_000_000_000L);
        check(bytes == output.size(), "stream length matches output"); return output.toByteArray();
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("hongguo-playback-cache-test-").toFile();
        try {
            PlaybackCachePolicy policy = cache(root, "window", 1024, 128);
            for (int i = 0; i < 8; i++) policy.fetch("series", "video-" + i, i, "10");
            check(policy.status().files == 8 && policy.status().bytes == 80, "files counted");
            policy.protect("series", "video-0", 0); policy.protect("series", "video-0", 0);
            policy.prune("series", 4, 1);
            check(policy.status().files == 4, "previous/current/next plus protected retained");
            for (int i : new int[]{0, 3, 4, 5}) check(policy.find("series", "video-" + i, i) != null, "keep episode " + i);
            for (int i : new int[]{1, 2, 6, 7}) check(policy.find("series", "video-" + i, i) == null, "prune episode " + i);
            policy.release("series", "video-0", 0); policy.prune("series", 4, 1);
            check(policy.find("series", "video-0", 0) != null, "one reference still protects current file");
            policy.release("series", "video-0", 0); policy.prune("series", 4, 1);
            check(policy.find("series", "video-0", 0) == null, "last release permits removal");
            policy.protect("series", "video-4", 4);
            PlaybackCachePolicy.Stats cleared = policy.clear();
            check(cleared.removed == 2 && cleared.bytes == 20, "clear reports removed bytes only");
            check(policy.status().files == 1 && policy.find("series", "video-4", 4) != null, "clear retains in-use playback");
            policy.release("series", "video-4", 4); policy.clear();
            check(policy.status().files == 0, "clear removes released playback");

            PlaybackCachePolicy lru = cache(root, "lru", 120, 40);
            File oldest = lru.fetch("old-series", "old", 0, "40"); oldest.setLastModified(1);
            File middle = lru.fetch("new-series", "current", 0, "40"); middle.setLastModified(2);
            lru.protect("new-series", "current", 0);
            File recent = lru.fetch("third-series", "recent", 0, "40"); recent.setLastModified(3);
            lru.fetch("fourth-series", "new", 0, "40");
            check(!oldest.exists() && middle.exists() && recent.exists(), "cross-series LRU, current protected");
            check(lru.status().bytes <= 120, "budget remains bounded without per-series prune");
            lru.protect("third-series", "recent", 0); lru.protect("fourth-series", "new", 0);
            fails(() -> lru.fetch("fifth-series", "full", 0, "40"), "protected files occupy full budget");
            check(middle.exists() && recent.exists(), "budget rejection never deletes current files");

            PlaybackCachePolicy sizing = cache(root, "sizing", 100, 40);
            fails(() -> sizing.fetch("s", "oversized", 0, "41"), "oversized download");
            fails(() -> sizing.fetch("s", "empty", 1, "0"), "empty download");
            check(sizing.status().files == 0 && parts(new File(root, "sizing")) == 0, "size failures leave no final or partial files");
            PlaybackCachePolicy broken = new PlaybackCachePolicy(new File(root, "broken"), 100, 40, (url, file, cap) -> { write(file, 5); throw new IOException("simulated transfer failure"); });
            fails(() -> broken.fetch("s", "v", 0, "unused"), "download failure");
            check(broken.status().files == 0 && parts(new File(root, "broken")) == 0, "failed partial transfer cleaned");

            File seed = sizing.fetch("s", "old", 3, "10");
            File stale = new File(seed.getParentFile(), seed.getName() + ".part"); write(stale, 4);
            PlaybackCachePolicy reopened = cache(root, "sizing", 100, 40); reopened.status();
            check(!stale.exists() && seed.exists(), "stale part removed after reopen, completed file preserved");
            File outside = new File(root, "manual-download.mp4"); write(outside, 6);
            File unrelated = new File(seed.getParentFile(), "manual-DownloadManager.mp4"); write(unrelated, 7);
            File symlink = new File(seed.getParentFile(), seed.getName().replace("_3_", "_4_"));
            Files.createSymbolicLink(symlink.toPath(), outside.toPath());
            check(reopened.find("s", "old", 4) == null, "symbolic link never returned");
            reopened.clear();
            check(outside.length() == 6 && unrelated.length() == 7 && Files.isSymbolicLink(symlink.toPath()), "clear leaves manual and outside files untouched");
            fails(() -> NetworkGuard.download("file:///no-network", outside, 40), "download rejects invalid URL without network");
            check(outside.length() == 6, "validation failure never deletes untouched destination");
            File hashed = reopened.fetch("../../", "../../manual-download", 0, "4");
            check(hashed.getCanonicalFile().getParentFile().equals(seed.getParentFile().getCanonicalFile()), "IDs cannot escape cache directory");
            fails(() -> reopened.fetch("s", "bad", -1, "4"), "negative index");
            File linkedDirectory = new File(root, "linked-cache"); Files.createSymbolicLink(linkedDirectory.toPath(), outside.getParentFile().toPath());
            fails(() -> new PlaybackCachePolicy(linkedDirectory, 100, 40, (u, f, n) -> write(f, 1)).status(), "symlink cache directory");

            CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger(); AtomicReference<Throwable> threadError = new AtomicReference<>();
            PlaybackCachePolicy concurrent = new PlaybackCachePolicy(new File(root, "concurrent"), 100, 40, (url, file, cap) -> { calls.incrementAndGet(); entered.countDown(); if (!unblock.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout"); write(file, 12); });
            AtomicReference<File> a = new AtomicReference<>(), b = new AtomicReference<>();
            Thread one = new Thread(() -> { try { a.set(concurrent.fetch("s", "same", 0, "test")); } catch (Throwable t) { threadError.set(t); } });
            Thread two = new Thread(() -> { try { b.set(concurrent.fetch("s", "same", 0, "test")); } catch (Throwable t) { threadError.set(t); } });
            one.start(); check(entered.await(3, TimeUnit.SECONDS), "download starts"); two.start(); unblock.countDown(); one.join(5000); two.join(5000);
            check(!one.isAlive() && !two.isAlive() && threadError.get() == null, "same-key threads finish without errors");
            check(calls.get() == 1 && a.get().equals(b.get()), "same-key fetch merges concurrent downloads");
            check(parts(new File(root, "concurrent")) == 0 && concurrent.status().bytes == 12, "atomic commit leaves single final file");

            for (boolean clear : new boolean[]{false, true}) {
                CountDownLatch begin = new CountDownLatch(1), finish = new CountDownLatch(1);
                AtomicReference<Exception> outcome = new AtomicReference<>();
                PlaybackCachePolicy pending = new PlaybackCachePolicy(new File(root, "cancel-" + clear), 100, 40, (url, file, cap) -> { write(file, 5); begin.countDown(); if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout"); });
                Thread job = new Thread(() -> { try { pending.fetch("s", "old", 0, "test"); } catch (Exception error) { outcome.set(error); } });
                job.start(); check(begin.await(3, TimeUnit.SECONDS), "partial download starts");
                if (clear) pending.clear(); else pending.prune("s", 5, 1);
                finish.countDown(); job.join(5000);
                check(!job.isAlive() && outcome.get() != null, "cleared/out-of-window in-flight fetch cancelled " + clear);
                check(pending.status().files == 0 && parts(new File(root, "cancel-" + clear)) == 0, "cancelled fetch cannot resurrect cleared file " + clear);
            }
            CountDownLatch protectedBegin = new CountDownLatch(1), protectedFinish = new CountDownLatch(1);
            AtomicReference<Throwable> protectedError = new AtomicReference<>();
            PlaybackCachePolicy protectedFlight = new PlaybackCachePolicy(new File(root, "protected-flight"), 100, 40, (url, file, cap) -> { protectedBegin.countDown(); if (!protectedFinish.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout"); write(file, 5); });
            protectedFlight.protect("s", "current", 0);
            Thread inUse = new Thread(() -> { try { protectedFlight.fetch("s", "current", 0, "test"); } catch (Throwable error) { protectedError.set(error); } });
            inUse.start(); check(protectedBegin.await(3, TimeUnit.SECONDS), "protected download starts"); protectedFlight.clear(); protectedFlight.prune("s", 9, 1); protectedFinish.countDown(); inUse.join(5000);
            check(!inUse.isAlive() && protectedError.get() == null && protectedFlight.status().files == 1, "clear/prune cannot cancel protected current file");

            byte[] binary = new byte[]{0, (byte) 255, (byte) 128, 42};
            check(Arrays.equals(binary, stream(binary, 10, headers("content-length", "4"))), "binary bytes preserved");
            check(Arrays.equals(ascii("hello"), stream(ascii("2\r\nhe\r\n3;extension=x\r\nllo\r\n0\r\nX-Test: yes\r\n\r\n"), 10, headers("transfer-encoding", "chunked"))), "chunked bytes and trailer decoded");
            check(Arrays.equals(binary, stream(binary, 4, headers())), "EOF framing at exact cap");
            final long largeLength = 12L * 1024 * 1024;
            InputStream generated = new InputStream() {
                private long remaining = largeLength;
                @Override public int read() { return remaining-- > 0 ? 123 : -1; }
                @Override public int read(byte[] data, int offset, int length) {
                    if (remaining == 0) return -1;
                    check(length <= 32768, "binary copy reads bounded chunks");
                    int count = (int) Math.min(length, remaining); remaining -= count; return count;
                }
            };
            OutputStream discard = new OutputStream() { @Override public void write(int value) {} @Override public void write(byte[] data, int offset, int length) {} };
            check(NetworkGuard.streamBody(generated, discard, headers("content-length", Long.toString(largeLength)), 96L * 1024 * 1024, System.nanoTime() + 5_000_000_000L) == largeLength, "12 MiB video streams beyond unchanged text limit without full buffering");
            check(NetworkGuard.LIMIT == 8 * 1024 * 1024, "original text limit unchanged");
            for (Map<String, String> h : Arrays.asList(headers("content-length", "-1"), headers("content-length", "9999999999999999999"), headers("content-length", "11"), headers("content-length", "4", "transfer-encoding", "chunked"), headers("transfer-encoding", "gzip"), headers("content-encoding", "gzip"))) fails(() -> stream(binary, 10, h), "invalid framing/encoding");
            fails(() -> stream(binary, 10, headers("content-length", "5")), "truncated content length");
            fails(() -> stream(binary, 3, headers()), "unbounded body exceeds cap");
            for (String chunked : new String[]{"b\r\n", "-1\r\n", "+1\r\na\r\n0\r\n\r\n", "ffffffffffffffff\r\n", "2\r\na", "1\r\naX\r\n0\r\n\r\n", "0\r\nbad-trailer\r\n\r\n"}) fails(() -> stream(ascii(chunked), 10, headers("transfer-encoding", "chunked")), "malformed chunked body");
            fails(() -> NetworkGuard.streamBody(new ByteArrayInputStream(binary), new ByteArrayOutputStream(), headers(), 10, System.nanoTime() - 1), "deadline enforced");
            Thread.currentThread().interrupt();
            try { fails(() -> stream(binary, 10, headers()), "interruption enforced"); } finally { Thread.interrupted(); }
            System.out.println("PASS PlaybackCachePolicyTest checks=" + checks + " networkRequests=0 temporaryFilesOnly=true");
        } finally { removeTree(root); }
    }
}
