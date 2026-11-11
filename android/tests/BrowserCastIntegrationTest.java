package io.github.jackotom.hongguo.android;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Production protocol/session/prefetch/cache, real scheduler + HTTP sockets. Only media download/history are local fixtures. */
final class BrowserCastIntegrationTest {
    static int checks;
    static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; }
    interface Condition { boolean get() throws Exception; }
    static void await(Condition condition, String label) throws Exception { await(condition, label, 6000); }
    static void await(Condition condition, String label, long timeout) throws Exception {
        long end = System.nanoTime() + timeout * 1000000;
        do { if (condition.get()) { checks++; return; } Thread.sleep(10); } while (System.nanoTime() < end);
        throw new AssertionError("Timed out: " + label);
    }
    static final class RuntimeScheduler implements DlnaSession.Scheduler {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final ThreadFactory factory = action -> { Thread thread = new Thread(action, "browser-cast-integration"); thread.setDaemon(true); return thread; };
        final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(factory);
        final ThreadPoolExecutor preparer = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), factory);
        Runnable guarded(Runnable action) { return () -> { try { action.run(); } catch (Throwable error) { failure.compareAndSet(null, error); throw error; } }; }
        public boolean execute(Runnable action) { try { control.execute(guarded(action)); return true; } catch (RejectedExecutionException closed) { return false; } }
        public DlnaSession.Task prepare(Runnable action) { java.util.concurrent.Future<?> future = preparer.submit(guarded(action)); return () -> future.cancel(true); }
        public DlnaSession.Task repeat(Runnable action, long delay) { java.util.concurrent.Future<?> future = control.scheduleWithFixedDelay(guarded(action), delay, delay, TimeUnit.MILLISECONDS); return () -> future.cancel(false); }
        public long nowMs() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
        public void close() { preparer.shutdownNow(); control.shutdown(); }
    }
    static final class CacheSource implements DlnaSession.Source {
        static final String SID = "local-browser-integration";
        final PlaybackCachePolicy cache;
        final PlaybackCachePolicy.CastWindow window;
        final DlnaPrefetch prefetch;
        final File input;
        final CopyOnWriteArrayList<Integer> confirmed = new CopyOnWriteArrayList<>();
        final ConcurrentHashMap<Integer, File> files = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, AtomicInteger> refs = new ConcurrentHashMap<>();
        final AtomicInteger loads = new AtomicInteger(), downloads = new AtomicInteger(), retains = new AtomicInteger(), releases = new AtomicInteger(), progress = new AtomicInteger();
        volatile boolean closed; volatile int currentIndex = 1;
        CacheSource(File directory, File input, int count) {
            this.input = input;
            cache = new PlaybackCachePolicy(directory, 32L * 1024 * 1024, 16L * 1024 * 1024, 160L * 1024 * 1024, (url, output, max) -> {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("fixture cancelled");
                if (input.length() > max) throw new IOException("fixture too large");
                Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING); downloads.incrementAndGet();
            });
            window = cache.openCastWindow(SID, 1, true); prefetch = new DlnaPrefetch(count, this::load);
        }
        String vid(int position) { return "synthetic-" + position; }
        void retain(int position) { cache.protect(SID, vid(position), position + 1); refs.computeIfAbsent(position, key -> new AtomicInteger()).incrementAndGet(); retains.incrementAndGet(); }
        void release(int position) {
            cache.release(SID, vid(position), position + 1); int n = refs.get(position).decrementAndGet(); releases.incrementAndGet();
            if (n < 0) throw new AssertionError("cache lease underflow");
            try { cache.prune(SID, currentIndex, 1); } catch (IOException error) { throw new IllegalStateException(error); }
        }
        DlnaSession.Prepared load(int position, String quality, boolean foreground, BooleanSupplier alive) throws Exception {
            if (!alive.getAsBoolean()) throw new InterruptedIOException("fixture cancelled"); loads.incrementAndGet(); retain(position); boolean handedOff = false;
            try {
                File file = cache.find(SID, vid(position), position + 1);
                if (file == null) file = cache.fetch(SID, vid(position), position + 1, "fixture:" + position);
                if (!alive.getAsBoolean()) throw new InterruptedIOException("fixture cancelled"); files.put(position, file);
                DlnaSession.Prepared media = new DlnaSession.Prepared(file, "本地合成视频 · 第 " + (position + 1) + " 集", quality, position + 1, () -> retain(position), () -> release(position)); handedOff = true; return media;
            } finally { if (!handedOff) release(position); }
        }
        public void selecting(int position) { window.moveTo(position + 1); prefetch.selecting(position); }
        public DlnaSession.Prepared prepare(int position, String quality, BooleanSupplier alive) throws Exception { return prefetch.prepare(position, quality, alive); }
        public void confirmed(int position, long ms, boolean cleanup) throws Exception { currentIndex = position + 1; confirmed.add(position); if (cleanup) cache.prune(SID, currentIndex, 1); }
        public void playbackConfirmed(int position, String quality, boolean autoplay) { window.moveTo(position + 1); prefetch.confirmed(position, quality, autoplay); }
        public void autoplayChanged(boolean value) { prefetch.autoplay(value); }
        public void progress(int position, long ms) { progress.incrementAndGet(); }
        public void close() { if (closed) return; closed = true; prefetch.close(); window.close(); }
        int live(int position) { AtomicInteger value = refs.get(position); return value == null ? 0 : value.get(); }
        int live() { return refs.values().stream().mapToInt(AtomicInteger::get).sum(); }
    }
    static final class Fixture implements AutoCloseable {
        final RuntimeScheduler scheduler = new RuntimeScheduler();
        final CacheSource source;
        final BrowserCastServer server;
        final BrowserCastSession session;
        final URI url;
        String token;
        long seq;
        Fixture(File directory, File input, File assetDirectory, int count) throws Exception {
            source = new CacheSource(new File(directory, "cache"), input, count);
            AtomicReference<BrowserCastSession> holder = new AtomicReference<>();
            Map<String, byte[]> assets = new HashMap<>();
            assets.put("/", Files.readAllBytes(new File(assetDirectory, "index.html").toPath()));
            assets.put("/receiver.js", Files.readAllBytes(new File(assetDirectory, "receiver.js").toPath()));
            assets.put("/receiver.css", Files.readAllBytes(new File(assetDirectory, "receiver.css").toPath()));
            server = new BrowserCastServer(InetAddress.getByName("127.0.0.1"), assets, new BrowserCastServer.Handler() {
                public JSONObject state() { return holder.get().receiverState(); }
                public void event(JSONObject event) { holder.get().receiverEvent(event); }
                public void command(JSONObject command) { holder.get().receiverCommand(command); }
                public void paired(String name) { holder.get().receiverPaired(name); }
            }, true);
            session = new BrowserCastSession(scheduler, source, server, snapshot -> {}, CacheSource.SID, "本地合成视频", count, "1080p", true, true);
            holder.set(session); url = URI.create(server.landingUrl()); session.start(0, 0);
        }
        void pair() throws Exception {
            Reply r = post("/pair", null, new JSONObject().put("code", server.pairCode()).put("name", "本机浏览器夹具"));
            check(r.status == 200, "pairing HTTP accepted"); token = r.json().getString("token");
        }
        JSONObject state() throws Exception { Reply r = get("/state"); if (r.status != 200) throw new AssertionError("state HTTP " + r.status); return r.json(); }
        JSONObject waitMedia(int position) throws Exception {
            AtomicReference<JSONObject> found = new AtomicReference<>();
            await(() -> { JSONObject s = state(), media = s.optJSONObject("media"); if (media != null && media.optInt("queuePosition", -1) == position) { found.set(s); return true; } return false; }, "media " + position);
            return found.get();
        }
        JSONObject event(JSONObject state, String type, long position, long duration) throws Exception {
            return new JSONObject().put("sessionId", state.getString("sessionId")).put("mediaId", state.getJSONObject("media").getString("id"))
                    .put("commandId", state.getJSONObject("intent").getLong("commandId")).put("eventSeq", ++seq).put("type", type).put("positionMs", position).put("durationMs", duration);
        }
        void event(String type, long position, long duration) throws Exception { sendEvent(event(state(), type, position, duration)); }
        void sendEvent(JSONObject event) throws Exception { check(post("/event", token, event).status == 200, "event HTTP accepted " + event.getString("type")); }
        void command(String type, Object value) throws Exception {
            JSONObject s = state(); JSONObject body = new JSONObject().put("sessionId", s.getString("sessionId")).put("mediaId", s.getJSONObject("media").getString("id")).put("type", type).put("value", value);
            check(post("/command", token, body).status == 200, "command HTTP accepted " + type);
            if (type.equals("play") || type.equals("pause") || type.equals("seek")) {
                long previous = s.getJSONObject("intent").getLong("commandId");
                await(() -> state().getJSONObject("intent").getLong("commandId") > previous, "remote command published " + type);
            } else if (type.equals("autoplay")) await(() -> state().getBoolean("autoplay") == (Boolean) value, "remote autoplay published");
        }
        Reply get(String path) throws Exception { return request(url, "GET", path, token == null ? "" : "X-Cast-Token: " + token + "\r\n", null); }
        Reply post(String path, String token, JSONObject value) throws Exception { return request(url, "POST", path, "Origin: http://" + url.getAuthority() + "\r\nContent-Type: application/json\r\n" + (token == null ? "" : "X-Cast-Token: " + token + "\r\n"), value.toString()); }
        public void close() {
            session.close(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!(source.closed && source.live() == 0 && scheduler.control.isTerminated())) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Fixture shutdown did not release all resources");
                try { Thread.sleep(10); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
        }
    }
    static final class Reply {
        final int status; final Map<String, String> headers = new HashMap<>(); final byte[] body;
        Reply(String header, byte[] body) {
            String[] lines = header.split("\r\n"); status = Integer.parseInt(lines[0].split(" ")[1]); this.body = body;
            for (int i = 1; i < lines.length; i++) { int colon = lines[i].indexOf(':'); if (colon > 0) headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim()); }
        }
        JSONObject json() throws Exception { return new JSONObject(new String(body, StandardCharsets.UTF_8)); }
    }
    static Socket socket(URI url) throws Exception { Socket socket = new Socket(); socket.setSoTimeout(5000); socket.setReceiveBufferSize(4096); socket.connect(new InetSocketAddress("127.0.0.1", url.getPort()), 1000); return socket; }
    static String header(Socket socket) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); InputStream input = socket.getInputStream(); byte[] end = {'\r', '\n', '\r', '\n'}; int n = 0;
        while (n < 4) { int b = input.read(); if (b < 0) throw new IOException("fixture early HTTP close"); out.write(b); n = b == end[n] ? n + 1 : b == '\r' ? 1 : 0; }
        return out.toString("US-ASCII");
    }
    static void send(Socket socket, String request) throws Exception { socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush(); }
    static Reply request(URI url, String method, String path, String extra, String body) throws Exception {
        try (Socket socket = socket(url)) {
            send(socket, method + " " + path + " HTTP/1.1\r\nHost: " + url.getAuthority() + "\r\n" + extra + (body == null ? "" : "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n") + "\r\n" + (body == null ? "" : body));
            String h = header(socket); int length = 0; for (String line : h.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            byte[] bytes = method.equals("HEAD") ? new byte[0] : socket.getInputStream().readNBytes(length); if (!method.equals("HEAD") && bytes.length != length) throw new IOException("fixture truncated response"); return new Reply(h, bytes);
        }
    }
    static void tests(File directory, File assets) throws Exception {
        File input = new File(directory, "episode.mp4");
        try (RandomAccessFile output = new RandomAccessFile(input, "rw")) { output.setLength(12L * 1024 * 1024); output.write(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0}); }
        try (Fixture f = new Fixture(directory, input, assets, 8)) {
            await(() -> f.session.snapshot().state.equals("WAITING_RECEIVER"), "initial awaiting pairing");
            check(f.source.loads.get() == 0, "unpaired receiver does not download");
            check(f.get("/state").status == 401, "unpaired state protected on actual socket");
            check(f.get("/").status == 200 && f.get("/receiver.js").status == 200, "actual packaged receiver assets served");
            f.pair(); JSONObject first = f.waitMedia(0); String firstPath = URI.create(first.getJSONObject("media").getString("url")).getPath();
            check(first.getString("state").equals("WAITING_RECEIVER_PLAY") && f.source.confirmed.isEmpty(), "publishing never pretends playback");
            Thread.sleep(100); check(f.source.loads.get() == 1 && f.source.prefetch.preparedCount() == 0, "five-cache waits for real playing event");
            Reply range = request(f.url, "GET", firstPath, "Range: bytes=0-15\r\n", null);
            check(range.status == 206 && range.body[4] == 'f' && range.headers.get("content-range").equals("bytes 0-15/" + input.length()), "actual media Range through registered publication");
            f.event("playing", 0, 10000); await(() -> f.session.snapshot().playing && f.source.confirmed.contains(0), "first actual playing adopted");
            await(() -> f.source.prefetch.preparedCount() == 5, "actual DlnaPrefetch cached five following episodes");
            check(f.source.downloads.get() == 6 && f.source.cache.status().files == 6, "actual cache has current plus five distinct files");
            File firstFile = f.source.files.get(0);
            try (Socket reader = socket(f.url)) {
                send(reader, "GET " + firstPath + " HTTP/1.1\r\nHost: " + f.url.getAuthority() + "\r\n\r\n"); check(header(reader).contains(" 200 "), "old media stream starts before next selection");
                await(() -> f.source.live(0) == 2, "publication and old reader independently pinned");
                f.event("timeupdate", 9500, 10000); JSONObject ended = f.event(f.state(), "ended", 10000, 10000); f.sendEvent(ended);
                JSONObject second = f.waitMedia(1); String secondPath = URI.create(second.getJSONObject("media").getString("url")).getPath();
                check(!second.getJSONObject("media").getString("id").equals(first.getJSONObject("media").getString("id")), "natural ended changes media generation");
                check(request(f.url, "HEAD", firstPath, "", null).status == 200 && f.source.confirmed.size() == 1, "old URL retained until replacement really plays");
                f.sendEvent(ended); Thread.sleep(50); check(f.session.snapshot().targetCurrent == 1, "duplicate stale ended does not skip another episode");
                f.event("playing", 0, 10000); await(() -> f.session.snapshot().current == 1, "second episode confirmed");
                check(f.get(firstPath).status == 404 && f.source.live(0) == 1, "old capability revoked but active reader lease survives");
                check(request(f.url, "HEAD", secondPath, "", null).status == 200, "replacement media remains available");
                f.command("select", 3); f.waitMedia(3); f.event("playing", 0, 10000); await(() -> f.session.snapshot().current == 3, "explicit selection to fourth episode");
                check(firstFile.isFile() && f.source.live(0) == 1, "actual cache preserves old in-flight media outside retention window");
                check(reader.getInputStream().readAllBytes().length == input.length(), "old stream finishes after both selection and revoke");
                await(() -> f.source.live(0) == 0 && !firstFile.exists(), "actual cache removes old episode after last reader releases");
            }
            f.event("timeupdate", 9500, 10000); JSONObject stale = f.event(f.state(), "ended", 10000, 10000);
            f.command("pause", false); await(() -> !f.state().getJSONObject("intent").getBoolean("play"), "manual pause delivered");
            f.sendEvent(stale); f.event("ended", 10000, 10000); Thread.sleep(100);
            check(f.session.snapshot().targetCurrent == 3 && f.session.snapshot().state.equals("PAUSED"), "manual pause rejects both stale and fresh tail EOF");
            f.command("play", true); f.event("playing", 0, 10000); f.event("timeupdate", 9000, 10000); f.event("waiting", 9500, 10000); f.event("ended", 10000, 10000);
            await(() -> f.session.snapshot().state.equals("BUFFERING"), "buffering is not ended"); check(f.session.snapshot().targetCurrent == 3, "buffering never advances queue");
            f.command("autoplay", false); await(() -> !f.session.snapshot().autoplay, "disable autoplay over HTTP");
            f.event("playing", 9500, 10000); f.event("timeupdate", 9800, 10000); f.event("ended", 10000, 10000);
            await(() -> f.session.snapshot().state.equals("ENDED"), "autoplay off ends current episode"); check(f.session.snapshot().targetCurrent == 3, "autoplay off no next media");
            f.command("autoplay", true); f.command("play", true); f.event("playing", 0, 10000); f.event("timeupdate", 9000, 10000);
            JSONObject late = f.event(f.state(), "ended", 10000, 10000);
            await(() -> f.session.snapshot().state.equals("DISCONNECTED"), "real 15-second heartbeat timeout", 18000);
            f.sendEvent(late); Thread.sleep(50); check(f.session.snapshot().targetCurrent == 3 && f.session.snapshot().state.equals("DISCONNECTED"), "late ended never recovers disconnected receiver or advances");
            f.state(); check(f.session.snapshot().state.equals("DISCONNECTED"), "polling alone does not pretend resumed playback");
            f.event("playing", 0, 10000); await(() -> f.session.snapshot().playing, "actual playing restores disconnected receiver");
            f.command("next", null); JSONObject next = f.waitMedia(4); f.event("playing", 0, 10000); await(() -> f.session.snapshot().current == 4, "manual next works after recovery");
            String lastPath = URI.create(next.getJSONObject("media").getString("url")).getPath(); check(request(f.url, "HEAD", lastPath, "", null).status == 200, "final active media accessible");
            JSONObject current = f.state(); JSONObject stop = new JSONObject().put("sessionId", current.getString("sessionId")).put("mediaId", current.getJSONObject("media").getString("id")).put("type", "stop");
            try { Reply reply = f.post("/command", f.token, stop); check(reply.status == 200, "stop acknowledgement if response precedes shutdown"); } catch (IOException stoppingSocket) { checks++; }
            await(() -> !f.session.snapshot().active && f.source.closed, "HTTP stop closes active session and source");
            await(() -> f.source.live() == 0 && f.source.retains.get() == f.source.releases.get(), "all prefetch/publication/reader/owner leases balanced");
            boolean unavailable = false; try { f.get(lastPath); } catch (IOException expected) { unavailable = true; } check(unavailable, "stopped receiver server no longer exposes media");
            check(f.scheduler.failure.get() == null, "no asynchronous scheduler exception");
        }
        System.out.println("PASS BrowserCastIntegration checks=" + checks + " server=actual session=actual prefetch=actual cache=actual scheduler=realThreads clock=realTime transport=realLocalHTTP fiveAhead=true oldReaderSurvives=true oldCacheRemoved=true pauseAndDisconnectDoNotAdvance=true stopRevokes=true remoteRequests=0 appleDeviceTests=not_run");
    }
    static void serve(File directory, File assets) throws Exception {
        File input = new File(directory, "episode.mp4"); if (!input.isFile()) throw new IOException("Expected local episode.mp4 fixture");
        File work = Files.createTempDirectory(directory.toPath(), "browser-production-").toFile();
        try (Fixture fixture = new Fixture(work, input, assets, 3)) {
            // Explicit test-only local stdout handoff. Never used by Android production logging.
            System.out.println(new JSONObject().put("url", fixture.server.landingUrl()).put("code", fixture.server.pairCode()).toString()); System.out.flush();
            while (System.in.read() != -1) { /* Parent process owns fixture lifetime. */ }
        } finally { deleteTree(work); }
    }
    static void deleteTree(File dir) throws Exception { if (!dir.exists()) return; try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(dir.toPath())) { for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); } }
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--serve")) { serve(new File(args[1]), new File(args[2])); return; }
        if (args.length != 1) throw new IllegalArgumentException("Expected assets directory, or --serve fixtureDirectory assetsDirectory");
        File directory = Files.createTempDirectory("hongguo-browser-integration-").toFile(); try { tests(directory, new File(args[0])); } finally { deleteTree(directory); }
    }
}
