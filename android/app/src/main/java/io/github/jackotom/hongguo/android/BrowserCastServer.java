package io.github.jackotom.hongguo.android;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** An independent, paired browser receiver. Never changes the DLNA server's selected-peer policy. */
final class BrowserCastServer implements DlnaSession.Server, AutoCloseable {
    interface Handler {
        JSONObject state();
        void event(JSONObject event);
        void command(JSONObject command);
        void paired(String name);
    }
    private static final int HEADER_BYTES = 8192, BODY_BYTES = 8192, HEADER_COUNT = 40;
    private static final int WORKERS = 4, QUEUED = 8, PUBLICATIONS = 8, BUFFER_BYTES = 32768;
    private static final long MAX_MEDIA_BYTES = 96L * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SECURITY_HEADERS = "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n"
            + "Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; media-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'\r\n";
    private final Handler handler;
    private final Map<String, byte[]> assets = new HashMap<>();
    private final Map<String, Media> publications = new HashMap<>();
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ServerSocket listener;
    private final Thread acceptor;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService watchdog;
    private final boolean allowLoopback;
    private final Timeouts timeouts;
    private final String origin, host, code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
    private final long created = System.nanoTime();
    private final Object pairing = new Object(), diagnosticsLock = new Object();
    private InetAddress pairedPeer;
    private String token;
    private long failuresWindow = created;
    private int pairingFailures;
    private long accepted, rejected, mediaGets, mediaHeads, mediaBytes;
    private int lastStatus;
    private Failure lastFailure = Failure.NONE;
    private enum Failure { NONE, ACCEPT_IO, BUSY, READ_TIMEOUT, READ_IO, WRITE_IO, MEDIA_IO, WRITE_TIMEOUT, LIFETIME, INTERNAL }

    static final class Timeouts {
        final long readMs, writeIdleMs, lifetimeMs, pairTtlMs, lockoutMs;
        Timeouts(long read, long write, long life, long pairTtl, long lockout) {
            if (read < 50 || write < 50 || life < read || pairTtl < 50 || lockout < 50) throw new IllegalArgumentException("Invalid timeouts");
            readMs = read; writeIdleMs = write; lifetimeMs = life; pairTtlMs = pairTtl; lockoutMs = lockout;
        }
    }
    private static final Timeouts DEFAULT_TIMEOUTS = new Timeouts(4000, 20000, 30 * 60 * 1000, 10 * 60 * 1000, 60 * 1000);

    BrowserCastServer(InetAddress bind, Map<String, byte[]> assets, Handler handler) throws IOException {
        this(bind, assets, handler, false);
    }
    // Explicit local-socket test seams only. Production uses the three-argument constructor.
    BrowserCastServer(InetAddress bind, Map<String, byte[]> assets, Handler handler, boolean allowLoopback) throws IOException {
        this(bind, assets, handler, allowLoopback, DEFAULT_TIMEOUTS);
    }
    BrowserCastServer(InetAddress bind, Map<String, byte[]> assets, Handler handler, boolean allowLoopback, Timeouts timeouts) throws IOException {
        if (!(bind instanceof Inet4Address) || handler == null || assets == null || timeouts == null
                || !(privateIpv4(bind) || allowLoopback && bind.isLoopbackAddress())) throw new IOException("网页投屏需要本机局域网 IPv4 地址");
        NetworkInterface local = NetworkInterface.getByInetAddress(bind);
        if (local == null || !local.isUp() || !allowLoopback && local.isLoopback()) throw new IOException("网页投屏本机地址无效");
        for (String path : new String[]{"/", "/receiver.js", "/receiver.css"}) {
            byte[] bytes = assets.get(path);
            if (bytes == null || bytes.length == 0 || bytes.length > 512 * 1024) throw new IOException("网页接收资源缺失或过大");
            this.assets.put(path, bytes.clone());
        }
        this.handler = handler; this.allowLoopback = allowLoopback; this.timeouts = timeouts;
        listener = new ServerSocket();
        try { listener.setReuseAddress(false); listener.bind(new InetSocketAddress(bind, 0), QUEUED); }
        catch (IOException error) { listener.close(); throw error; }
        host = bind.getHostAddress() + ":" + listener.getLocalPort(); origin = "http://" + host;
        ThreadFactory factory = action -> { Thread thread = new Thread(action, "hongguo-browser-cast"); thread.setDaemon(true); return thread; };
        workers = new ThreadPoolExecutor(WORKERS, WORKERS, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUED), factory);
        workers.allowCoreThreadTimeOut(true);
        watchdog = Executors.newSingleThreadScheduledExecutor(factory);
        watchdog.scheduleWithFixedDelay(this::expireClients, 50, 50, TimeUnit.MILLISECONDS);
        acceptor = factory.newThread(this::accept); acceptor.start();
    }

    String landingUrl() { return origin + "/"; }
    String pairCode() { return code; }
    boolean isPaired() { synchronized (pairing) { return !closed.get() && pairedPeer != null; } }
    String diagnostics() {
        synchronized (diagnosticsLock) {
            return "网页接收：已配对=" + isPaired() + "；TCP=" + accepted + "；拒绝对端=" + rejected
                    + "\n媒体 GET=" + mediaGets + "；HEAD=" + mediaHeads + "；正文写出=" + mediaBytes + "B"
                    + "\n最近 HTTP=" + (lastStatus == 0 ? "无" : lastStatus) + "；供流状态=" + lastFailure.name()
                    + "；写出字节不代表接收端已播放。";
        }
    }
    static boolean privateIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] bytes = address.getAddress(); int first = bytes[0] & 255, second = bytes[1] & 255;
        return first == 10 || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168;
    }
    private boolean allowedPeer(InetAddress address) { return privateIpv4(address) || allowLoopback && address instanceof Inet4Address && address.isLoopbackAddress(); }
    private static String randomToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); char[] hex = new char[64], alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { hex[i * 2] = alphabet[(bytes[i] & 255) >>> 4]; hex[i * 2 + 1] = alphabet[bytes[i] & 15]; }
        return new String(hex);
    }
    private static boolean sameSecret(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
    private static long add(long value, long increment) { return increment > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + increment; }
    private void failure(Failure value) { synchronized (diagnosticsLock) { lastFailure = value; } }

    private final class Media {
        final String path; final File file; final long size; final Runnable retain, release;
        boolean revoked;
        Media(String path, File file, long size, Runnable retain, Runnable release) { this.path = path; this.file = file; this.size = size; this.retain = retain; this.release = release; }
        synchronized boolean acquire() {
            if (revoked) return false;
            try { retain.run(); return true; } catch (RuntimeException error) { quietly(release); return false; }
        }
        synchronized void revoke() { if (!revoked) { revoked = true; quietly(release); } }
    }
    @Override public DlnaSession.Publication publish(DlnaSession.Prepared prepared) throws IOException {
        if (prepared == null || prepared.file == null || prepared.retain == null || prepared.release == null || Files.isSymbolicLink(prepared.file.toPath())) throw new IOException("网页投屏本地媒体无效");
        File file = prepared.file.getCanonicalFile(); long size = file.length();
        if (!file.isFile() || !file.canRead() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".mp4") || size <= 0 || size > MAX_MEDIA_BYTES) throw new IOException("网页投屏媒体尚未准备完成");
        synchronized (publications) {
            if (closed.get() || publications.size() >= PUBLICATIONS) throw new IOException("网页投屏服务已停止或片源请求较多");
            try { prepared.retain.run(); } catch (RuntimeException error) { quietly(prepared.release); throw new IOException("网页投屏文件保护未成功", error); }
            String path = "/media/" + randomToken() + ".mp4";
            Media media = new Media(path, file, size, prepared.retain, prepared.release); publications.put(path, media);
            return new DlnaSession.Publication(origin + path, media);
        }
    }
    @Override public void revoke(DlnaSession.Publication publication) {
        if (publication == null || !(publication.handle instanceof BrowserCastServer.Media)) return;
        Media media = (Media) publication.handle;
        synchronized (publications) { if (!publications.remove(media.path, media)) return; }
        media.revoke();
    }

    private void accept() {
        while (!closed.get()) {
            Socket socket = null;
            try {
                socket = listener.accept();
                if (closed.get()) { socket.close(); continue; }
                if (!allowedPeer(socket.getInetAddress())) { synchronized (diagnosticsLock) { rejected = add(rejected, 1); } socket.close(); continue; }
                synchronized (diagnosticsLock) { accepted = add(accepted, 1); }
                socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, timeouts.readMs)); socket.setTcpNoDelay(true); socket.setSendBufferSize(BUFFER_BYTES);
                Client client = new Client(socket); clients.add(client);
                if (closed.get()) { client.close(); continue; }
                try { workers.execute(() -> handle(client)); } catch (RejectedExecutionException error) { client.close(Failure.BUSY); }
            } catch (IOException error) { closeQuietly(socket); if (!closed.get()) failure(Failure.ACCEPT_IO); }
        }
    }
    private final class Client implements Closeable {
        final Socket socket; final long started = System.nanoTime(); volatile long progress = started; volatile boolean responding;
        boolean done, head; Media leased; FileChannel file;
        Client(Socket socket) { this.socket = socket; }
        synchronized boolean acquire(Media media) { if (done || !media.acquire()) return false; leased = media; return true; }
        synchronized boolean attach(FileChannel value) { if (done) { closeQuietly(value); return false; } file = value; return true; }
        public void close() { close(null); }
        void close(Failure problem) {
            Media old; FileChannel channel;
            synchronized (this) { if (done) return; done = true; old = leased; leased = null; channel = file; file = null; }
            if (problem != null && !closed.get()) failure(problem);
            closeQuietly(socket); closeQuietly(channel); if (old != null) quietly(old.release); clients.remove(this);
        }
    }
    private void expireClients() {
        long now = System.nanoTime();
        for (Client client : clients) {
            long age = TimeUnit.NANOSECONDS.toMillis(now - client.started), idle = TimeUnit.NANOSECONDS.toMillis(now - client.progress);
            if (age >= timeouts.lifetimeMs) client.close(Failure.LIFETIME);
            else if (!client.responding && age >= timeouts.readMs) client.close(Failure.READ_TIMEOUT);
            else if (client.responding && idle >= timeouts.writeIdleMs) client.close(Failure.WRITE_TIMEOUT);
        }
    }
    private static final class Request {
        final String method, path; final Map<String, String> headers; final byte[] body;
        Request(String method, String path, Map<String, String> headers, byte[] body) { this.method = method; this.path = path; this.headers = headers; this.body = body; }
    }
    private static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { this.status = status; }
    }
    private Request readRequest(Client client) throws IOException {
        InputStream input = client.socket.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int matched = 0; byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < 4) {
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - client.started) >= timeouts.readMs) throw new SocketTimeoutException("Request deadline");
            int value = input.read(); if (value < 0) return null;
            if (bytes.size() >= HEADER_BYTES) throw new HttpFailure(431);
            if (value > 126 || value < 32 && value != '\r' && value != '\n' && value != '\t') throw new HttpFailure(400);
            bytes.write(value); matched = value == end[matched] ? matched + 1 : value == '\r' ? 1 : 0;
        }
        String[] lines = bytes.toString("US-ASCII").split("\r\n", -1);
        if (lines.length > HEADER_COUNT + 3 || lines[0].length() > 2048) throw new HttpFailure(431);
        String[] start = lines[0].split(" ", -1);
        if (start.length != 3 || !start[0].matches("[A-Z]+") || !start[1].startsWith("/") || !(start[2].equals("HTTP/1.1") || start[2].equals("HTTP/1.0"))) throw new HttpFailure(400);
        client.head = start[0].equals("HEAD");
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length - 2; i++) {
            int colon = lines[i].indexOf(':'); if (colon <= 0) throw new HttpFailure(400);
            String name = lines[i].substring(0, colon).toLowerCase(Locale.ROOT), value = lines[i].substring(colon + 1).trim();
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+") || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || headers.put(name, value) != null) throw new HttpFailure(400);
        }
        if (!host.equals(headers.get("host"))) throw new HttpFailure(403);
        String requestOrigin = headers.get("origin");
        if (requestOrigin != null && !origin.equals(requestOrigin)) throw new HttpFailure(403);
        if (headers.containsKey("transfer-encoding") || headers.containsKey("expect")) throw new HttpFailure(400);
        int length = 0; String rawLength = headers.get("content-length");
        if (rawLength != null) {
            if (!rawLength.matches("[0-9]{1,9}")) throw new HttpFailure(400);
            long parsed = Long.parseLong(rawLength); if (parsed > BODY_BYTES) throw new HttpFailure(413); length = (int) parsed;
        }
        if (start[0].equals("POST")) {
            if (requestOrigin == null) throw new HttpFailure(403);
            if (rawLength == null) throw new HttpFailure(411);
            String contentType = headers.get("content-type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;\\s*charset=utf-8)?")) throw new HttpFailure(415);
        } else if (length != 0) throw new HttpFailure(400);
        byte[] body = new byte[length]; int position = 0;
        while (position < length) {
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - client.started) >= timeouts.readMs) throw new SocketTimeoutException("Body deadline");
            int read = input.read(body, position, length - position); if (read < 0) throw new HttpFailure(400); position += read;
        }
        return new Request(start[0], start[1], headers, body);
    }
    private static JSONObject object(byte[] bytes) throws HttpFailure {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().trim();
            if (!text.startsWith("{") || !text.endsWith("}")) throw new HttpFailure(400);
            int depth = 0; boolean string = false, escape = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (string) { if (escape) escape = false; else if (c == '\\') escape = true; else if (c == '"') string = false; }
                else if (c == '"') string = true;
                else if (c == '{' || c == '[') { if (++depth > 32) throw new HttpFailure(400); }
                else if ((c == '}' || c == ']') && --depth < 0) throw new HttpFailure(400);
            }
            if (depth != 0 || string) throw new HttpFailure(400);
            JSONTokener parser = new JSONTokener(text);
            Object value = parser.nextValue();
            if (!(value instanceof JSONObject) || parser.nextClean() != 0) throw new HttpFailure(400);
            return (JSONObject) value;
        } catch (JSONException | CharacterCodingException error) { throw new HttpFailure(400); }
    }
    private void handle(Client client) {
        try {
            Request request = readRequest(client); if (request == null) return;
            if (request.path.startsWith("/media/")) { serveMedia(client, request); return; }
            byte[] asset = assets.get(request.path);
            if (asset != null) {
                if (!request.method.equals("GET") && !request.method.equals("HEAD")) throw new HttpFailure(405);
                String type = request.path.equals("/") ? "text/html; charset=utf-8" : request.path.endsWith(".js") ? "application/javascript; charset=utf-8" : "text/css; charset=utf-8";
                send(client, 200, type, asset, request.method.equals("HEAD"), ""); return;
            }
            if (request.path.equals("/pair")) {
                if (!request.method.equals("POST")) throw new HttpFailure(405);
                pair(client, object(request.body)); return;
            }
            if (!request.path.equals("/state") && !request.path.equals("/event") && !request.path.equals("/command")) throw new HttpFailure(404);
            synchronized (pairing) {
                if (pairedPeer == null || !pairedPeer.equals(client.socket.getInetAddress()) || !sameSecret(token, request.headers.get("x-cast-token"))) throw new HttpFailure(401);
            }
            if (request.path.equals("/state")) {
                if (!request.method.equals("GET")) throw new HttpFailure(405);
                JSONObject state = handler.state(); if (state == null) throw new HttpFailure(503);
                byte[] body = state.toString().getBytes(StandardCharsets.UTF_8); if (body.length > 65536) throw new HttpFailure(500);
                send(client, 200, "application/json; charset=utf-8", body, false, "");
            } else {
                if (!request.method.equals("POST")) throw new HttpFailure(405);
                JSONObject body = object(request.body);
                if (request.path.equals("/event")) {
                    handler.event(body);
                    json(client, 200, "{\"ok\":true}");
                } else {
                    // A stop callback may synchronously close this server and every client.
                    // Flush the receipt first; it confirms receipt, never playback or completion.
                    json(client, 200, "{\"ok\":true}");
                    handler.command(body);
                }
            }
        } catch (HttpFailure error) { try { json(client, error.status, "{\"error\":\"" + error.status + "\"}"); } catch (IOException ignored) { failure(Failure.WRITE_IO); } }
        catch (SocketTimeoutException error) { if (!closed.get()) failure(Failure.READ_TIMEOUT); }
        catch (IOException error) { if (!closed.get()) failure(client.responding ? Failure.WRITE_IO : Failure.READ_IO); }
        catch (RuntimeException error) { if (!closed.get()) { failure(Failure.INTERNAL); try { if (!client.responding) json(client, 500, "{\"error\":\"500\"}"); } catch (IOException ignored) {} } }
        finally { client.close(); }
    }
    private void pair(Client client, JSONObject body) throws IOException {
        String provided = body.optString("code", ""), name = body.optString("name", "浏览器"); boolean newlyPaired = false; String value;
        synchronized (pairing) {
            long now = System.nanoTime();
            if (TimeUnit.NANOSECONDS.toMillis(now - failuresWindow) >= timeouts.lockoutMs) { failuresWindow = now; pairingFailures = 0; }
            if (pairingFailures >= 5) throw new HttpFailure(429);
            if (TimeUnit.NANOSECONDS.toMillis(now - created) >= timeouts.pairTtlMs) throw new HttpFailure(410);
            if (!provided.matches("[0-9]{6}") || !sameSecret(code, provided)) { pairingFailures++; throw new HttpFailure(403); }
            if (pairedPeer != null && !pairedPeer.equals(client.socket.getInetAddress())) throw new HttpFailure(409);
            if (pairedPeer == null) { pairedPeer = client.socket.getInetAddress(); token = randomToken(); newlyPaired = true; }
            value = token;
        }
        if (newlyPaired) {
            String sanitized = name.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
            if (sanitized.length() > 40) sanitized = sanitized.substring(0, 40);
            handler.paired(sanitized.isEmpty() ? "浏览器" : sanitized);
        }
        json(client, 200, "{\"token\":\"" + value + "\"}");
    }
    private void serveMedia(Client client, Request request) throws IOException {
        if (!request.method.equals("GET") && !request.method.equals("HEAD")) throw new HttpFailure(405);
        if (!request.path.matches("/media/[a-f0-9]{64}\\.mp4")) throw new HttpFailure(404);
        Media media; synchronized (publications) { media = publications.get(request.path); }
        if (media == null || !client.acquire(media)) throw new HttpFailure(404);
        FileChannel file;
        try { file = FileChannel.open(media.file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS); }
        catch (IOException error) { failure(Failure.MEDIA_IO); throw new HttpFailure(410); }
        if (!client.attach(file)) return;
        if (file.size() != media.size) throw new HttpFailure(410);
        Range range;
        try { range = Range.parse(request.headers.get("range"), media.size); }
        catch (IllegalArgumentException error) { send(client, 416, "text/plain", new byte[0], true, "Content-Range: bytes */" + media.size + "\r\n"); return; }
        synchronized (diagnosticsLock) { if (request.method.equals("HEAD")) mediaHeads = add(mediaHeads, 1); else mediaGets = add(mediaGets, 1); }
        String extra = "Accept-Ranges: bytes\r\n" + (range.partial ? "Content-Range: bytes " + range.start + "-" + range.end + "/" + media.size + "\r\n" : "");
        header(client, range.partial ? 206 : 200, "video/mp4", range.length(), extra);
        if (request.method.equals("HEAD")) return;
        file.position(range.start); long remaining = range.length(); ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES); OutputStream output = client.socket.getOutputStream();
        while (remaining > 0 && !closed.get()) {
            buffer.clear(); buffer.limit((int) Math.min(buffer.capacity(), remaining)); int read = file.read(buffer);
            if (read < 0) { failure(Failure.MEDIA_IO); throw new IOException("Media changed"); }
            if (read == 0) continue;
            output.write(buffer.array(), 0, read); client.progress = System.nanoTime(); remaining -= read;
            synchronized (diagnosticsLock) { mediaBytes = add(mediaBytes, read); }
        }
        output.flush();
    }
    private static final class Range {
        final long start, end; final boolean partial;
        Range(long start, long end, boolean partial) { this.start = start; this.end = end; this.partial = partial; }
        long length() { return end - start + 1; }
        static Range parse(String value, long size) {
            if (value == null) return new Range(0, size - 1, false);
            if (!value.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) throw new IllegalArgumentException();
            String[] parts = value.substring(6).split("-", -1);
            try {
                if (parts[0].isEmpty()) { long suffix = Long.parseLong(parts[1]); if (suffix <= 0) throw new IllegalArgumentException(); return new Range(Math.max(0, size - suffix), size - 1, true); }
                long start = Long.parseLong(parts[0]), end = parts[1].isEmpty() ? size - 1 : Long.parseLong(parts[1]);
                if (start >= size || start > end) throw new IllegalArgumentException(); return new Range(start, Math.min(end, size - 1), true);
            } catch (NumberFormatException error) { throw new IllegalArgumentException(error); }
        }
    }
    private void json(Client client, int status, String body) throws IOException { send(client, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), client.head, ""); }
    private void send(Client client, int status, String type, byte[] body, boolean head, String extra) throws IOException {
        header(client, status, type, body.length, extra);
        if (!head) { client.socket.getOutputStream().write(body); client.progress = System.nanoTime(); }
        client.socket.getOutputStream().flush();
    }
    private void header(Client client, int status, String type, long length, String extra) throws IOException {
        client.responding = true; client.progress = System.nanoTime();
        String reason = status == 200 ? "OK" : status == 206 ? "Partial Content" : "Request Rejected";
        String value = "HTTP/1.1 " + status + " " + reason + "\r\nConnection: close\r\nContent-Type: " + type + "\r\nContent-Length: " + length + "\r\n" + SECURITY_HEADERS + extra + "\r\n";
        client.socket.getOutputStream().write(value.getBytes(StandardCharsets.US_ASCII)); client.socket.getOutputStream().flush();
        synchronized (diagnosticsLock) { lastStatus = status; }
    }
    private static void quietly(Runnable action) { try { action.run(); } catch (RuntimeException ignored) {} }
    private static void closeQuietly(Closeable value) { if (value != null) try { value.close(); } catch (IOException ignored) {} }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeQuietly(listener); watchdog.shutdownNow();
        ArrayList<Media> old; synchronized (publications) { old = new ArrayList<>(publications.values()); publications.clear(); }
        for (Media media : old) media.revoke();
        for (Client client : new ArrayList<>(clients)) client.close();
        workers.shutdownNow();
        synchronized (pairing) { token = null; pairedPeer = null; }
        try { workers.awaitTermination(1500, TimeUnit.MILLISECONDS); acceptor.join(1000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
