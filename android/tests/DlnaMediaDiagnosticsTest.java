package io.github.jackotom.hongguo.android;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Production HTTP server, synthetic files, and loopback-only receiver fixtures. */
public final class DlnaMediaDiagnosticsTest {
    private static int checks;
    private static final String PRIVATE_TITLE = "private-title-do-not-disclose";
    private static final String PRIVATE_FILE = "private-file-do-not-disclose.mp4";
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void await(BooleanSupplier condition, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        check(condition.getAsBoolean(), label);
    }
    private static long metric(String text, String label) {
        Matcher match = Pattern.compile(Pattern.quote(label) + "([0-9]+)").matcher(text);
        if (!match.find()) throw new AssertionError("Missing finite diagnostic metric: " + label);
        return Long.parseLong(match.group(1));
    }
    private static String snapshot(DlnaMediaServer server, URI... urls) {
        String value = server.diagnostics();
        check(value.length() <= 512, "diagnostic snapshot has hard bounded size");
        String[] lines = value.split("\n", -1);
        check(lines.length == 4, "diagnostic snapshot uses four bounded UI lines");
        for (String line : lines) check(line.length() <= 240, "diagnostic line stays inside UI sanitizer limit");
        check(!value.contains("http://") && !value.contains("https://"), "origin uses only validated local address, not a redacted URI");
        check(value.contains("本次会话累计，非当前集"), "counts explicitly cumulative, not current episode");
        check(value.contains("不代表播放成功或接收确认"), "write observations never claim receiver playback success");
        check(!value.contains(PRIVATE_TITLE) && !value.contains(PRIVATE_FILE) && !value.contains(".mp4"), "registered title and filename remain private");
        check(!value.contains("/media/") && !value.contains("HTTP/1.1") && !value.contains("Range:") && !value.contains("Host:"), "no bearer path or raw request in diagnostics");
        check(!Pattern.compile("[a-f0-9]{64}").matcher(value).find(), "no bearer token in diagnostics");
        for (URI url : urls) {
            check(!value.contains(url.getRawPath()) && !value.contains(url.toString()), "full publication URL not exposed");
            check(value.contains("本机=127.0.0.1:" + url.getPort()), "only local address and dynamic port exposed");
        }
        return value;
    }
    private static void counts(String value, long tcp, long denied, long gets, long heads, long bytes) {
        check(metric(value, "TV TCP=") == tcp, "selected TCP count exact");
        check(metric(value, "拒绝其他对端=") == denied, "foreign peer rejection count exact");
        check(metric(value, "媒体GET=") == gets, "validated matched GET count exact");
        check(metric(value, "媒体HEAD=") == heads, "validated matched HEAD count exact");
        check(metric(value, "正文写出=") == bytes, "body writes count excludes headers and HEAD");
    }
    private static final class Traffic {
        int connections;
        DlnaMediaServerTest.Reply send(URI url, String method, String headers) throws Exception {
            connections++;
            return DlnaMediaServerTest.request(url, method, headers);
        }
    }
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("hongguo-dlna-diagnostics-").toFile();
        File file = new File(directory, PRIVATE_FILE);
        byte[] content = new byte[1000]; for (int i = 0; i < content.length; i++) content[i] = (byte) (i % 251);
        Files.write(file.toPath(), content);
        try {
            DlnaMediaServerTest.Pins pins = new DlnaMediaServerTest.Pins();
            try (DlnaMediaServer server = DlnaMediaServerTest.server(1500, 3000, 10000)) {
                URI url = URI.create(server.publish(file, PRIVATE_TITLE, pins::retain, pins::release).url);
                String fresh = snapshot(server, url);
                counts(fresh, 0, 0, 0, 0, 0);
                check(fresh.contains("最近HTTP=无") && fresh.contains("最近供流错误=NONE") && fresh.contains("尚未连接取流"), "no requests report zero and explicit no-fetch hint");
                for (int i = 0; i < 50; i++) server.diagnostics();
                check(pins.live.get() == 1 && pins.acquired.get() == 1, "observing diagnostics does not change publication/read leases");
                Traffic traffic = new Traffic();
                try (Socket ignored = DlnaMediaServerTest.socket(url)) { traffic.connections++; }
                await(() -> metric(server.diagnostics(), "TV TCP=") == 1, "selected TCP counted even before HTTP request");
                counts(snapshot(server, url), 1, 0, 0, 0, 0);
                check(server.diagnostics().contains("尚无匹配的媒体GET/HEAD请求"), "TCP without valid media request is distinguished");
                check(traffic.send(url, "HEAD", "").status == 200, "real HEAD response unchanged");
                counts(snapshot(server, url), traffic.connections, 0, 0, 1, 0);
                check(server.diagnostics().contains("尚无媒体GET请求"), "HEAD-only receiver is not reported as media body consumption");
                check(traffic.send(url, "GET", "Range: bytes=-10\r\n").body.length == 10, "real ranged GET transfers synthetic bytes");
                check(traffic.send(url, "HEAD", "Range: bytes=10-19\r\n").status == 206, "ranged HEAD response unchanged");
                counts(snapshot(server, url), traffic.connections, 0, 1, 2, 10);
                check(server.diagnostics().contains("最近HTTP=206"), "latest successful response records 206");
                check(traffic.send(url, "GET", "Range: bytes=0-1,3-4\r\n").status == 416, "multi-range remains rejected");
                check(server.diagnostics().contains("最近HTTP=416"), "rejected range status visible");
                check(traffic.send(url, "POST", "").status == 405, "unsupported method unchanged");
                check(server.diagnostics().contains("最近HTTP=405"), "method rejection status visible");
                URI origin = URI.create("http://127.0.0.1:" + url.getPort());
                check(traffic.send(origin.resolve("/../" + PRIVATE_FILE), "GET", "").status == 404, "traversal not registered media");
                check(traffic.send(origin.resolve("/media/" + "0".repeat(64) + ".mp4"), "GET", "").status == 404, "unknown bearer not registered media");
                check(server.diagnostics().contains("最近HTTP=404"), "404 response observable without storing path");
                check(traffic.send(url, "GET", "X-Private: " + PRIVATE_TITLE + "\r\nX-Private: twice\r\n").status == 400, "malformed duplicate header remains rejected");
                check(traffic.send(url, "GET", "Transfer-Encoding: chunked\r\n").status == 400, "request body attack remains rejected");
                String invalid = snapshot(server, url);
                counts(invalid, traffic.connections, 0, 1, 2, 10);
                check(invalid.contains("最近HTTP=400"), "malformed request status recorded but not a media GET");

                File next = new File(directory, "next-private.mp4"); Files.write(next.toPath(), new byte[73]);
                DlnaMediaServer.Publication second = server.publish(next, PRIVATE_TITLE, pins::retain, pins::release);
                URI nextUrl = URI.create(second.url);
                check(traffic.send(nextUrl, "GET", "").body.length == 73, "new publication fetch unchanged");
                server.revoke(second);
                check(traffic.send(nextUrl, "GET", "").status == 404, "revoked publication stays unavailable");
                check(traffic.send(url, "GET", "Range: bytes=0-4\r\n").body.length == 5, "another current publication still serves");
                counts(snapshot(server, url, nextUrl), traffic.connections, 0, 3, 2, 88);

                int parallelGets = 8, parallelHeads = 4;
                CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(parallelGets + parallelHeads);
                AtomicReference<Throwable> failure = new AtomicReference<>(); List<Thread> threads = new ArrayList<>();
                for (int i = 0; i < parallelGets + parallelHeads; i++) {
                    final boolean head = i >= parallelGets;
                    Thread thread = new Thread(() -> {
                        try {
                            start.await();
                            DlnaMediaServerTest.Reply reply = DlnaMediaServerTest.request(url, head ? "HEAD" : "GET", "Range: bytes=0-6\r\n");
                            if (reply.status != 206 || reply.body.length != (head ? 0 : 7)) throw new AssertionError("Concurrent HTTP bytes unchanged");
                            String value = server.diagnostics();
                            if (value.length() > 512 || metric(value, "媒体GET=") < 3 || metric(value, "正文写出=") < 88) throw new AssertionError("Concurrent diagnostic snapshot invalid");
                        } catch (Throwable error) { failure.compareAndSet(null, error); }
                        finally { done.countDown(); }
                    }, "dlna-diagnostic-fixture");
                    thread.setDaemon(true); threads.add(thread); thread.start();
                }
                start.countDown();
                check(done.await(8, TimeUnit.SECONDS), "all concurrent real loopback requests complete");
                for (Thread thread : threads) thread.join(500);
                if (failure.get() != null) throw new AssertionError("Concurrent loopback fixture", failure.get());
                traffic.connections += parallelGets + parallelHeads;
                counts(snapshot(server, url), traffic.connections, 0, 11, 6, 144);
                check(server.diagnostics().contains("最近HTTP=206"), "concurrent latest status stays finite");
            }
            check(pins.live.get() == 0 && pins.acquired.get() == pins.released.get(), "all diagnostic fixture leases balanced");

            // A connection from any source except the immutable selected peer is not media access.
            try (DlnaMediaServer foreign = new DlnaMediaServer(DlnaMediaServerTest.LOOP, InetAddress.getByName("127.0.0.2"), (bind, tv) -> {
                if (!bind.isLoopbackAddress() || !tv.isLoopbackAddress()) throw new java.io.IOException("Fixture loopback only");
            })) {
                URI url = URI.create(foreign.publish(file, PRIVATE_TITLE, () -> {}, () -> {}).url);
                try (Socket socket = DlnaMediaServerTest.socket(url)) {
                    try { DlnaMediaServerTest.send(socket, "GET " + url.getRawPath() + " HTTP/1.1\r\nHost: fixture\r\n\r\n"); socket.getInputStream().read(); }
                    catch (java.io.IOException expected) { /* immutable wrong peer closes without a response */ }
                }
                await(() -> metric(foreign.diagnostics(), "拒绝其他对端=") == 1, "wrong peer counted once");
                counts(snapshot(foreign, url), 0, 1, 0, 0, 0);
                check(foreign.diagnostics().contains("最近HTTP=无") && foreign.diagnostics().contains("尚未连接取流"), "foreign source cannot fake selected-TV media activity");
            }

            // File failures retain their category only; private local path/exception text stays absent.
            try (DlnaMediaServer changed = DlnaMediaServerTest.server(1500, 3000, 10000)) {
                File missing = new File(directory, "missing-private.mp4"); Files.write(missing.toPath(), content);
                URI url = URI.create(changed.publish(missing, PRIVATE_TITLE, () -> {}, () -> {}).url);
                Files.write(missing.toPath(), new byte[999]);
                check(DlnaMediaServerTest.request(url, "GET", "").status == 410, "changed size returns same 410");
                counts(snapshot(changed, url), 1, 0, 0, 0, 0);
                Files.delete(missing.toPath());
                try (Socket socket = DlnaMediaServerTest.socket(url)) {
                    DlnaMediaServerTest.send(socket, "GET " + url.getRawPath() + " HTTP/1.1\r\nHost: fixture\r\n\r\n");
                    check(socket.getInputStream().readAllBytes().length == 0, "missing file still closes without invented HTTP response");
                }
                await(() -> changed.diagnostics().contains("最近供流错误=LOCAL_MEDIA_IO"), "local media failure reduced to finite category");
                counts(snapshot(changed, url), 2, 0, 0, 0, 0);
                check(changed.diagnostics().contains("最近HTTP=410") && !changed.diagnostics().contains("missing-private"), "last actually written HTTP status retained, local file stays private");
            }

            try (DlnaMediaServer slow = DlnaMediaServerTest.server(150, 250, 3000)) {
                URI url = URI.create(slow.publish(file, PRIVATE_TITLE, () -> {}, () -> {}).url);
                try (Socket socket = DlnaMediaServerTest.socket(url)) {
                    DlnaMediaServerTest.send(socket, "GET ");
                    check(socket.getInputStream().read() < 0, "slow request connection expires as before");
                }
                await(() -> slow.diagnostics().contains("最近供流错误=HEADER_TIMEOUT"), "header deadline category visible");
                counts(snapshot(slow, url), 1, 0, 0, 0, 0);
            }

            File large = new File(directory, "large-private.mp4");
            try (RandomAccessFile out = new RandomAccessFile(large, "rw")) { out.setLength(64L * 1024 * 1024); }
            DlnaMediaServerTest.Pins abortedPins = new DlnaMediaServerTest.Pins();
            try (DlnaMediaServer aborted = DlnaMediaServerTest.server(1500, 3000, 10000)) {
                URI url = URI.create(aborted.publish(large, PRIVATE_TITLE, abortedPins::retain, abortedPins::release).url);
                try (Socket socket = DlnaMediaServerTest.socket(url)) {
                    DlnaMediaServerTest.send(socket, "GET " + url.getRawPath() + " HTTP/1.1\r\nHost: fixture\r\n\r\n");
                    check(DlnaMediaServerTest.readHeader(socket).contains("200 OK"), "receiver abort fixture receives response header");
                    await(() -> metric(aborted.diagnostics(), "正文写出=") > 0, "successful writes counted before receiver closes");
                    socket.setSoLinger(true, 0);
                }
                await(() -> aborted.diagnostics().contains("最近供流错误=MEDIA_WRITE_IO"), "receiver disconnect records media-write category");
                await(() -> abortedPins.live.get() == 1, "receiver abort releases only its read lease");
                String value = snapshot(aborted, url);
                check(metric(value, "TV TCP=") == 1 && metric(value, "媒体GET=") == 1 && metric(value, "媒体HEAD=") == 0, "aborted matched GET still counted once");
                check(metric(value, "正文写出=") > 0 && metric(value, "正文写出=") < large.length(), "only successful body writes, not declared Content-Length, are counted");
            }
            check(abortedPins.live.get() == 0 && abortedPins.acquired.get() == abortedPins.released.get(), "abort plus server close balances every lease");

            DlnaMediaServerTest.Pins stalledPins = new DlnaMediaServerTest.Pins();
            DlnaMediaServer stalled = DlnaMediaServerTest.server(1000, 200, 4000);
            try {
                URI url = URI.create(stalled.publish(large, PRIVATE_TITLE, stalledPins::retain, stalledPins::release).url);
                try (Socket socket = DlnaMediaServerTest.socket(url)) {
                    DlnaMediaServerTest.send(socket, "GET " + url.getRawPath() + " HTTP/1.1\r\nHost: fixture\r\n\r\n");
                    check(DlnaMediaServerTest.readHeader(socket).contains("200 OK"), "stalled receiver gets original 200");
                    await(() -> stalledPins.live.get() == 1, "write-idle timeout still releases stream lease");
                    check(stalled.diagnostics().contains("最近供流错误=WRITE_IDLE_TIMEOUT"), "watchdog closure not overwritten by generic socket error");
                    String value = snapshot(stalled, url);
                    check(metric(value, "媒体GET=") == 1 && metric(value, "正文写出=") > 0 && metric(value, "正文写出=") < large.length(), "stalled partial body remains observed rather than claimed complete");
                }
                String before = stalled.diagnostics(); stalled.close();
                check(stalled.diagnostics().equals(before), "close retains cumulative diagnostics without inventing network error");
            } finally { stalled.close(); }
            check(stalledPins.live.get() == 0 && stalledPins.acquired.get() == stalledPins.released.get(), "diagnostic deadline changes do not change lease balance");

            DlnaMediaServer stopped = DlnaMediaServerTest.server(1500, 3000, 10000);
            try {
                URI url = URI.create(stopped.publish(file, PRIVATE_TITLE, () -> {}, () -> {}).url);
                try (Socket socket = DlnaMediaServerTest.socket(url)) {
                    await(() -> metric(stopped.diagnostics(), "TV TCP=") == 1, "pending connection accepted before server close");
                    stopped.close();
                    check(socket.getInputStream().read() < 0, "manual close still cancels pending connection");
                    check(stopped.diagnostics().contains("最近供流错误=NONE"), "normal session shutdown is not mislabeled a connection failure");
                }
            } finally { stopped.close(); }
            check(file.exists() && large.exists(), "observability never deletes registered manual files");
            System.out.println("DLNA_MEDIA_DIAGNOSTICS_OK checks=" + checks + " cumulativeSession=true actualServerSocket=true bodyWritesNotPlayback=true redacted=true loopbackOnly=true externalRequests=0 deviceTests=not_run");
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> entries = Files.walk(directory.toPath())) {
                entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
            }
        }
    }
}
