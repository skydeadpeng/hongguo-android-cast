package io.github.jackotom.hongguo.android;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

/** SdkAssets against a scripted CDN over a synthetic APK: resumption after a cut or a stall, the checks
 *  that come before a byte is kept, what each failure leaves behind, and the cache. */
public final class SdkAssetsTest {
    static int checks;
    static void check(boolean ok, String text) { checks++; if (!ok) throw new AssertionError(text); }
    static void equal(Object actual, Object expected, String text) { check(java.util.Objects.equals(actual, expected), text + " actual=" + actual); }
    interface Run { void call() throws Exception; }
    static void fails(Class<? extends Throwable> kind, String message, Run run) throws Exception {
        try { run.call(); }
        catch (Throwable failure) {
            check(kind.isInstance(failure) && message.equals(failure.getMessage()), "expected " + kind.getSimpleName() + "(" + message + ") actual=" + failure);
            return;
        }
        throw new AssertionError("expected " + kind.getSimpleName() + "(" + message + "), nothing was thrown");
    }

    interface Answer { SdkAssets.Reply answer(String url, long start, long end) throws Exception; }

    /** The scripted CDN: each request takes the next answer, or the APK's own bytes once the script is spent. */
    static final class Cdn implements SdkAssets.Fetcher {
        final Fixture fixture;
        final Deque<Answer> script = new ArrayDeque<>();
        final List<long[]> asked = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        Cdn(Fixture fixture) { this.fixture = fixture; }
        @Override public SdkAssets.Reply fetch(String url, long start, long end, long deadlineNanos) throws Exception {
            asked.add(new long[]{start, end});
            urls.add(url);
            return script.isEmpty() ? range(fixture.apk, start, end) : script.pollFirst().answer(url, start, end);
        }
        /** A correct 206 over `data`, for exactly the slice this request asked for. */
        SdkAssets.Reply range(byte[] data, long start, long end) {
            return new SdkAssets.Reply(206, null, "bytes " + start + "-" + end + "/" + data.length, null, new ByteArrayInputStream(data, (int) start, (int) (end - start + 1)));
        }
        long start(int index) { return asked.get(index)[0]; }
        long end(int index) { return asked.get(index)[1]; }
    }

    /** One synthetic APK with the two packed ranges laid out the way the real one carries them. */
    static final class Fixture {
        static final long IMAGE_START = 500;
        static final int OFFSET = 3_000;
        final byte[] apk = new byte[200_000], image = new byte[6_000], plain = new byte[20_000], config, imagePacked, configPacked;
        final long configStart;
        final String[][] parts;
        Fixture() throws Exception {
            Random random = new Random(20261124);
            for (int index = 0; index < apk.length; index++) apk[index] = (byte) (index * 37 + 11);
            random.nextBytes(image);
            random.nextBytes(plain);
            imagePacked = deflate(image);
            configPacked = deflate(plain);
            configStart = IMAGE_START + imagePacked.length + 100;
            System.arraycopy(imagePacked, 0, apk, (int) IMAGE_START, imagePacked.length);
            System.arraycopy(configPacked, 0, apk, (int) configStart, configPacked.length);
            config = Arrays.copyOfRange(plain, OFFSET, OFFSET + SdkMaterials.CONFIG_SIZE);
            parts = new String[][] {
                {SdkAssets.IMAGE, Long.toString(IMAGE_START), Integer.toString(imagePacked.length), sha(imagePacked), Integer.toString(image.length), sha(image)},
                {SdkAssets.CONFIG, Long.toString(configStart), Integer.toString(configPacked.length), sha(configPacked), Integer.toString(plain.length), sha(config)}};
        }
        /** Puts the verified image in the cache so that only the configuration range reaches the CDN. */
        void seed(File directory) throws IOException { Files.write(new File(directory, SdkAssets.IMAGE).toPath(), image); }
        /** The APK with one bit flipped inside the configuration range. */
        byte[] damaged() { byte[] bad = apk.clone(); bad[(int) configStart + 500] ^= 1; return bad; }
        byte[][] ensure(File directory, Cdn cdn, long deadlineNanos) throws Exception {
            return SdkAssets.ensure(directory, parts, "https://lf9-apk.ugapk.cn/package/apk/synthetic.apk", apk.length, OFFSET, cdn, deadlineNanos);
        }
        File partial(File directory) { return new File(directory, SdkAssets.CONFIG + ".part"); }
        File cached(File directory) { return new File(directory, SdkAssets.CONFIG); }
    }

    static volatile long heldUntil;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("hongguo-sdk-assets");
        long open = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        Fixture fixture = new Fixture();
        try {
            int next = 0;

            // 1. A connection cut halfway: what arrived is kept and the next request continues right after it.
            File cut = Files.createDirectory(root.resolve(Integer.toString(next++))).toFile();
            fixture.seed(cut);
            Cdn cutCdn = new Cdn(fixture);
            long arrived = 1_000, last = fixture.configStart + fixture.configPacked.length - 1;
            cutCdn.script.add((url, start, end) -> new SdkAssets.Reply(206, null, "bytes " + start + "-" + end + "/" + fixture.apk.length, null,
                cut(fixture.configPacked, (int) arrived)));
            check(Arrays.equals(fixture.ensure(cut, cutCdn, open)[1], fixture.config), "cut: configuration verified");
            equal(cutCdn.asked.size(), 2, "cut: request count");
            equal(cutCdn.start(0), fixture.configStart, "cut: first request starts the range");
            equal(cutCdn.start(1), fixture.configStart + arrived, "cut: second request resumes after what arrived");
            equal(cutCdn.end(1), last, "cut: second request still ends the range");
            check(!fixture.partial(cut).exists(), "cut: no partial file left");
            check(Arrays.equals(Files.readAllBytes(fixture.cached(cut).toPath()), fixture.config), "cut: cached");
            Cdn quiet = new Cdn(fixture);
            fixture.ensure(cut, quiet, open);
            equal(quiet.asked.size(), 0, "cache: no request");

            // 2. The deadline passes while bytes are still coming: nothing is cached, the progress stays,
            //    and the next call finishes the range from it.
            File stalled = Files.createDirectory(root.resolve(Integer.toString(next++))).toFile();
            fixture.seed(stalled);
            Cdn stalledCdn = new Cdn(fixture);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            heldUntil = deadline;
            stalledCdn.script.add((url, start, end) -> new SdkAssets.Reply(206, null, "bytes " + start + "-" + end + "/" + fixture.apk.length, null, held(fixture.configPacked)));
            fails(SocketTimeoutException.class, "SDK 素材下载超时", () -> fixture.ensure(stalled, stalledCdn, deadline));
            check(!fixture.cached(stalled).exists(), "deadline: nothing cached");
            long kept = fixture.partial(stalled).length();
            check(kept > 0 && kept < fixture.configPacked.length, "deadline: progress kept actual=" + kept);
            Cdn resumed = new Cdn(fixture);
            check(Arrays.equals(fixture.ensure(stalled, resumed, open)[1], fixture.config), "deadline: finished on the next call");
            equal(resumed.start(0), fixture.configStart + kept, "deadline: next call continues after the progress");
            check(!fixture.partial(stalled).exists(), "deadline: partial file removed once complete");

            // 3. Redirects: followed on an official carrier, refused anywhere else.
            File routed = Files.createDirectory(root.resolve(Integer.toString(next++))).toFile();
            fixture.seed(routed);
            Cdn routedCdn = new Cdn(fixture);
            routedCdn.script.add((url, start, end) -> new SdkAssets.Reply(302, "https://edge.jspcdn.cn/package/apk/synthetic.apk", null, null, null));
            fixture.ensure(routed, routedCdn, open);
            equal(routedCdn.urls.get(1), "https://edge.jspcdn.cn/package/apk/synthetic.apk", "redirect: followed to the carrier");
            check(Arrays.equals(Files.readAllBytes(fixture.cached(routed).toPath()), fixture.config), "redirect: cached");
            File away = Files.createDirectory(root.resolve(Integer.toString(next++))).toFile();
            fixture.seed(away);
            Cdn awayCdn = new Cdn(fixture);
            awayCdn.script.add((url, start, end) -> new SdkAssets.Reply(302, "https://example.invalid/package/apk/synthetic.apk", null, null, null));
            fails(IOException.class, "SDK 素材跳转目标无效", () -> fixture.ensure(away, awayCdn, open));
            equal(awayCdn.urls.size(), 1, "redirect: an unknown host is never requested");
            check(!fixture.cached(away).exists(), "redirect: nothing cached");

            // 4. A reply that cannot be trusted discards the progress made with it.
            File wrong = Files.createDirectory(root.resolve(Integer.toString(next++))).toFile();
            fixture.seed(wrong);
            Cdn wrongCdn = new Cdn(fixture);
            wrongCdn.script.add((url, start, end) -> new SdkAssets.Reply(206, null, "bytes " + start + "-" + end + "/" + fixture.apk.length, null,
                cut(fixture.configPacked, 2_000)));
            wrongCdn.script.add((url, start, end) -> new SdkAssets.Reply(206, null, "bytes " + (start - 1) + "-" + end + "/" + fixture.apk.length, null, new ByteArrayInputStream(new byte[0])));
            fails(IOException.class, "SDK 素材分段不符", () -> fixture.ensure(wrong, wrongCdn, open));
            check(!fixture.partial(wrong).exists(), "mismatch: progress discarded");
            check(!fixture.cached(wrong).exists(), "mismatch: nothing cached");
            File corrupt = Files.createDirectory(root.resolve(Integer.toString(next++))).toFile();
            fixture.seed(corrupt);
            Cdn corruptCdn = new Cdn(fixture);
            byte[] damaged = fixture.damaged();
            corruptCdn.script.add((url, start, end) -> corruptCdn.range(damaged, start, end));
            fails(IOException.class, "SDK 素材摘要不符", () -> fixture.ensure(corrupt, corruptCdn, open));
            check(!fixture.partial(corrupt).exists(), "corrupt: progress discarded");
            check(!fixture.cached(corrupt).exists(), "corrupt: nothing cached");
            // The discarded range starts over from its first byte.
            check(Arrays.equals(fixture.ensure(corrupt, new Cdn(fixture), open)[1], fixture.config), "corrupt: downloaded again");

            // 5. A server error is tried again inside the same call; any other status is final.
            File busy = Files.createDirectory(root.resolve(Integer.toString(next++))).toFile();
            fixture.seed(busy);
            Cdn busyCdn = new Cdn(fixture);
            busyCdn.script.add((url, start, end) -> new SdkAssets.Reply(503, null, null, null, null));
            fixture.ensure(busy, busyCdn, open);
            equal(busyCdn.asked.size(), 2, "server error: tried again");
            File ignored = Files.createDirectory(root.resolve(Integer.toString(next++))).toFile();
            fixture.seed(ignored);
            Cdn ignoredCdn = new Cdn(fixture);
            ignoredCdn.script.add((url, start, end) -> new SdkAssets.Reply(200, null, null, null, null));
            fails(IOException.class, "SDK 素材下载 HTTP 200", () -> fixture.ensure(ignored, ignoredCdn, open));
            check(!fixture.cached(ignored).exists(), "ignored range: nothing cached");

            // 6. Requests that keep timing out are given up after a bounded number, progress kept.
            File dead = Files.createDirectory(root.resolve(Integer.toString(next++))).toFile();
            fixture.seed(dead);
            Cdn deadCdn = new Cdn(fixture);
            for (int index = 0; index < 8; index++) deadCdn.script.add((url, start, end) -> { throw new SocketTimeoutException("timeout"); });
            fails(java.io.InterruptedIOException.class, "SDK 素材下载多次中断", () -> fixture.ensure(dead, deadCdn, open));
            equal(deadCdn.asked.size(), 4, "timeout: request count");

            // 7. A damaged cache entry is not trusted: it is fetched again and replaced, by its stored size.
            File spoiled = Files.createDirectory(root.resolve(Integer.toString(next++))).toFile();
            byte[] flipped = fixture.image.clone();
            flipped[flipped.length / 2] ^= 1;
            Files.write(new File(spoiled, SdkAssets.IMAGE).toPath(), flipped);
            Cdn repair = new Cdn(fixture);
            byte[][] repaired = fixture.ensure(spoiled, repair, open);
            equal(repair.asked.size(), 2, "cache: a damaged entry is requested again");
            equal(repair.start(0), Fixture.IMAGE_START, "cache: image range");
            equal(repair.start(1), fixture.configStart, "cache: configuration range");
            check(Arrays.equals(repaired[0], fixture.image) && Arrays.equals(Files.readAllBytes(new File(spoiled, SdkAssets.IMAGE).toPath()), fixture.image), "cache: entry replaced");
            check(Arrays.equals(repaired[1], fixture.config), "cache: configuration verified");
            File both = Files.createDirectory(root.resolve(Integer.toString(next++))).toFile();
            Files.write(new File(both, SdkAssets.IMAGE).toPath(), fixture.image);
            Files.write(new File(both, SdkAssets.CONFIG).toPath(), fixture.config);
            Cdn silent = new Cdn(fixture);
            fixture.ensure(both, silent, open);
            equal(silent.asked.size(), 0, "cache: both inputs answer without the CDN");

            // 8. The shipped table still names both inputs of the pinned APK.
            equal(SdkAssets.carrier(java.net.URI.create(SdkAssets.APK)), true, "table: APK on an official carrier");
            equal(SdkAssets.IMAGE.length() > 0 && SdkAssets.CONFIG.length() > 0 && SdkAssets.APK_SIZE > SdkAssets.CONFIG_OFFSET, true, "table: inputs named");
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (IOException ignored) { } });
        }
        System.out.println("SDK_ASSETS_OK checks=" + checks);
    }

    static InputStream cut(byte[] data, int kept) {
        return new InputStream() {
            int at;
            @Override public int read() throws IOException { byte[] one = new byte[1]; int count = read(one, 0, 1); return count < 0 ? -1 : one[0] & 255; }
            @Override public int read(byte[] out, int offset, int length) throws IOException {
                if (at >= kept) throw new IOException("unexpected end of stream");
                int count = Math.min(length, kept - at);
                System.arraycopy(data, at, out, offset, count);
                at += count;
                return count;
            }
        };
    }

    /** Waits until the fetch deadline has passed before the first byte, like a connection that never starts. */
    static InputStream held(byte[] data) {
        return new InputStream() {
            int at;
            boolean waited;
            @Override public int read() throws IOException { byte[] one = new byte[1]; int count = read(one, 0, 1); return count < 0 ? -1 : one[0] & 255; }
            @Override public int read(byte[] out, int offset, int length) throws IOException {
                if (!waited) {
                    long pause = TimeUnit.NANOSECONDS.toMillis(heldUntil - System.nanoTime()) + 50;
                    try { Thread.sleep(Math.max(1, pause)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                    waited = true;
                }
                if (at >= data.length) return -1;
                int count = Math.min(length, data.length - at);
                System.arraycopy(data, at, out, offset, count);
                at += count;
                return count;
            }
        };
    }

    static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(9, true);
        deflater.setInput(data);
        deflater.finish();
        byte[] buffer = new byte[data.length + 64], out = new byte[buffer.length];
        int size = 0;
        for (int count; (count = deflater.deflate(buffer)) > 0; ) {
            if (size + count > out.length) out = Arrays.copyOf(out, out.length * 2);
            System.arraycopy(buffer, 0, out, size, count);
            size += count;
        }
        deflater.end();
        return Arrays.copyOf(out, size);
    }

    static String sha(byte[] data) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) out.append(String.format("%02x", value & 255));
        return out.toString();
    }
}
