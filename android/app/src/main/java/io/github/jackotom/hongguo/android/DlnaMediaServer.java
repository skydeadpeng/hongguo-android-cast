package io.github.jackotom.hongguo.android;

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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

/** One selected receiver, local registered MP4s only, bounded HTTP/1.x byte serving. */
final class DlnaMediaServer implements AutoCloseable {
    private static final int HEADER_BYTES = 8192, HEADER_COUNT = 40, PUBLICATIONS = 8;
    private static final int WORKERS = 4, QUEUED = 8, BUFFER_BYTES = 32768;
    private static final String FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final InetAddress tv;
    private final String origin;
    private final ServerSocket listener;
    private final Thread acceptor;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService watchdog;
    private final Timeouts timeouts;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, Publication> publications = new HashMap<>();
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final Object diagnosticsLock = new Object();
    private long selectedConnections, rejectedPeers, mediaGets, mediaHeads, mediaBodyBytes;
    private int lastHttpStatus;
    private ConnectionError lastConnectionError = ConnectionError.NONE;

    // Finite categories only: receiver-controlled request/error text never enters diagnostics.
    private enum ConnectionError {
        NONE, ACCEPT_IO, CONNECTION_SETUP_IO, WORKER_BUSY, HEADER_TIMEOUT,
        HEADER_READ_IO, RESPONSE_WRITE_IO, LOCAL_MEDIA_IO, MEDIA_WRITE_IO,
        WRITE_IDLE_TIMEOUT, CONNECTION_LIFETIME, INTERNAL_ERROR
    }

    /** Cumulative server-session observations, not a per-episode or receiver playback acknowledgement. */
    String diagnostics() {
        synchronized (diagnosticsLock) {
            String progress = selectedConnections == 0 ? "所选电视尚未连接取流"
                    : mediaGets == 0 && mediaHeads == 0 ? "尚无匹配的媒体GET/HEAD请求"
                    : mediaGets == 0 ? "已探测媒体，尚无媒体GET请求"
                    : mediaBodyBytes == 0 ? "已收到媒体GET，尚未写出正文" : "已写出媒体正文";
            String value = "取流诊断（本次会话累计，非当前集）：" + progress + "\nTV TCP=" + selectedConnections
                    + "，拒绝其他对端=" + rejectedPeers + "，媒体GET=" + mediaGets + "，媒体HEAD=" + mediaHeads
                    + "\n正文写出=" + mediaBodyBytes + "B，最近HTTP=" + (lastHttpStatus == 0 ? "无" : lastHttpStatus)
                    + "，最近供流错误=" + lastConnectionError.name() + "\n本机=" + origin.substring("http://".length())
                    + "；正文写出不代表播放成功或接收确认。";
            return value.length() <= 512 ? value : value.substring(0, 512);
        }
    }

    private static long addBounded(long value, long delta) { return delta > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + delta; }
    private void connection(boolean selected) {
        synchronized (diagnosticsLock) {
            if (selected) selectedConnections = addBounded(selectedConnections, 1);
            else rejectedPeers = addBounded(rejectedPeers, 1);
        }
    }
    private void mediaRequest(boolean head) {
        synchronized (diagnosticsLock) {
            if (head) mediaHeads = addBounded(mediaHeads, 1); else mediaGets = addBounded(mediaGets, 1);
        }
    }
    private void bodyWritten(int bytes) { synchronized (diagnosticsLock) { mediaBodyBytes = addBounded(mediaBodyBytes, bytes); } }
    private void response(int status) { synchronized (diagnosticsLock) { lastHttpStatus = status; } }
    private void connectionError(ConnectionError error) { synchronized (diagnosticsLock) { lastConnectionError = error; } }

    interface AddressPolicy { void validate(InetAddress bind, InetAddress tv) throws IOException; }
    static final class Timeouts {
        final long headerMillis, writeIdleMillis, lifetimeMillis;
        Timeouts(long headerMillis, long writeIdleMillis, long lifetimeMillis) {
            if (headerMillis < 50 || writeIdleMillis < 50 || lifetimeMillis < headerMillis) throw new IllegalArgumentException("Invalid timeouts");
            this.headerMillis = headerMillis; this.writeIdleMillis = writeIdleMillis; this.lifetimeMillis = lifetimeMillis;
        }
    }
    private static final Timeouts DEFAULT_TIMEOUTS = new Timeouts(4000, 20000, 30 * 60 * 1000);
    private static final AddressPolicy LAN_ONLY = (bind, tv) -> {
        if (!privateIpv4(bind) || !privateIpv4(tv)) throw new IOException("投屏仅支持所选局域网 IPv4 设备");
        NetworkInterface local = NetworkInterface.getByInetAddress(bind);
        if (local == null || !local.isUp() || local.isLoopback()) throw new IOException("投屏本机局域网地址无效");
    };

    DlnaMediaServer(InetAddress bind, InetAddress tv) throws IOException { this(bind, tv, LAN_ONLY, DEFAULT_TIMEOUTS); }
    // Explicit package-private test seam: production callers use the two-address constructor.
    DlnaMediaServer(InetAddress bind, InetAddress tv, AddressPolicy policy) throws IOException { this(bind, tv, policy, DEFAULT_TIMEOUTS); }
    DlnaMediaServer(InetAddress bind, InetAddress tv, AddressPolicy policy, Timeouts timeouts) throws IOException {
        if (bind == null || tv == null || policy == null || timeouts == null || !(bind instanceof Inet4Address) || !(tv instanceof Inet4Address)) throw new IOException("投屏地址无效");
        policy.validate(bind, tv);
        this.tv = tv; this.timeouts = timeouts;
        listener = new ServerSocket();
        try { listener.setReuseAddress(false); listener.bind(new InetSocketAddress(bind, 0), QUEUED); }
        catch (IOException error) { listener.close(); throw error; }
        origin = "http://" + bind.getHostAddress() + ":" + listener.getLocalPort();
        ThreadFactory factory = runnable -> { Thread thread = new Thread(runnable, "hongguo-dlna-media"); thread.setDaemon(true); return thread; };
        workers = new ThreadPoolExecutor(WORKERS, WORKERS, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUED), factory);
        workers.allowCoreThreadTimeOut(true);
        watchdog = Executors.newSingleThreadScheduledExecutor(factory);
        watchdog.scheduleWithFixedDelay(this::expireClients, 100, 100, TimeUnit.MILLISECONDS);
        acceptor = factory.newThread(this::accept); acceptor.start();
    }

    static boolean privateIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] a = address.getAddress(); int x = a[0] & 255, y = a[1] & 255;
        return x == 10 || (x == 172 && y >= 16 && y <= 31) || (x == 192 && y == 168);
    }

    static final class Publication {
        final String url;
        private final DlnaMediaServer owner;
        private final String path;
        private final File file;
        private final long size;
        private final Runnable retain, release;
        private boolean revoked, ownerRetained = true;
        private Publication(DlnaMediaServer owner, String path, File file, long size, Runnable retain, Runnable release) {
            this.owner = owner; this.path = path; this.file = file; this.size = size; this.retain = retain; this.release = release; this.url = owner.origin + path;
        }
        synchronized boolean acquire() {
            if (revoked) return false;
            try { retain.run(); return true; }
            catch (RuntimeException error) { quietly(release); return false; }
        }
        synchronized void releaseRequest() { quietly(release); }
        synchronized void revoke() {
            revoked = true;
            if (ownerRetained) { ownerRetained = false; quietly(release); }
        }
    }

    Publication publish(File input, String title, Runnable retain, Runnable release) throws IOException {
        if (input == null || retain == null || release == null || Files.isSymbolicLink(input.toPath())) throw new IOException("投屏本地文件无效");
        File file = input.getCanonicalFile();
        if (!file.isFile() || !file.canRead() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".mp4") || file.length() <= 0) throw new IOException("投屏文件尚未准备完成");
        byte[] token = new byte[32]; RANDOM.nextBytes(token); StringBuilder hex = new StringBuilder(64);
        for (byte b : token) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        String path = "/media/" + hex + ".mp4";
        synchronized (publications) {
            if (closed.get()) throw new IOException("投屏服务已停止");
            if (publications.size() >= PUBLICATIONS) throw new IOException("投屏片源请求较多");
            try { retain.run(); }
            catch (RuntimeException error) { quietly(release); throw new IOException("投屏文件保护未成功", error); }
            Publication item = new Publication(this, path, file, file.length(), retain, release);
            publications.put(path, item); return item;
        }
    }

    void revoke(Publication item) {
        if (item == null || item.owner != this) return;
        synchronized (publications) { publications.remove(item.path, item); }
        item.revoke();
    }

    private void accept() {
        while (!closed.get()) {
            Socket socket = null;
            try {
                socket = listener.accept();
                if (closed.get()) { socket.close(); continue; }
                if (!tv.equals(socket.getInetAddress())) { connection(false); socket.close(); continue; }
                connection(true);
                socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, timeouts.headerMillis));
                socket.setTcpNoDelay(true); socket.setSendBufferSize(BUFFER_BYTES);
                Client client = new Client(socket); clients.add(client);
                if (closed.get()) { client.close(); continue; }
                try { workers.execute(() -> handle(client)); }
                catch (RejectedExecutionException error) { client.close(ConnectionError.WORKER_BUSY); }
            } catch (IOException error) {
                closeQuietly(socket);
                if (closed.get()) return;
                connectionError(socket == null ? ConnectionError.ACCEPT_IO : ConnectionError.CONNECTION_SETUP_IO);
            }
        }
    }

    private final class Client implements Closeable {
        final Socket socket;
        final long started = System.nanoTime();
        volatile long progress = started;
        volatile boolean streaming;
        private boolean done;
        private Publication leased;
        private FileChannel file;
        Client(Socket socket) { this.socket = socket; }
        synchronized boolean acquire(Publication item) {
            if (done || !item.acquire()) return false;
            leased = item; return true;
        }
        synchronized boolean attach(FileChannel channel) {
            if (done) { closeQuietly(channel); return false; }
            file = channel; return true;
        }
        void error(ConnectionError error) {
            synchronized (this) { if (!done && !closed.get()) connectionError(error); }
        }
        public void close() { close(null); }
        void close(ConnectionError error) {
            Publication previous; FileChannel channel;
            synchronized (this) {
                if (done) return;
                if (error != null && !closed.get()) connectionError(error);
                done = true; previous = leased; leased = null; channel = file; file = null;
            }
            closeQuietly(socket); closeQuietly(channel);
            if (previous != null) previous.releaseRequest();
            clients.remove(this);
        }
    }

    private void expireClients() {
        long now = System.nanoTime();
        for (Client client : clients) {
            long age = TimeUnit.NANOSECONDS.toMillis(now - client.started), idle = TimeUnit.NANOSECONDS.toMillis(now - client.progress);
            if (age >= timeouts.lifetimeMillis) client.close(ConnectionError.CONNECTION_LIFETIME);
            else if (!client.streaming && age >= timeouts.headerMillis) client.close(ConnectionError.HEADER_TIMEOUT);
            else if (client.streaming && idle >= timeouts.writeIdleMillis) client.close(ConnectionError.WRITE_IDLE_TIMEOUT);
        }
    }

    private void handle(Client client) {
        ConnectionError phase = ConnectionError.HEADER_READ_IO;
        try {
            Request request = request(client);
            if (request == null) return;
            phase = ConnectionError.RESPONSE_WRITE_IO;
            OutputStream output = client.socket.getOutputStream();
            if (!request.method.equals("GET") && !request.method.equals("HEAD")) { error(output, 405, "Method Not Allowed", "Allow: GET, HEAD\r\n"); return; }
            if (!request.path.matches("/media/[a-f0-9]{64}\\.mp4")) { error(output, 404, "Not Found", ""); return; }
            Publication item;
            synchronized (publications) { item = publications.get(request.path); }
            if (item == null || !client.acquire(item)) { error(output, 404, "Not Found", ""); return; }
            phase = ConnectionError.LOCAL_MEDIA_IO;
            FileChannel file = FileChannel.open(item.file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            if (!client.attach(file)) return;
            if (file.size() != item.size) { phase = ConnectionError.RESPONSE_WRITE_IO; error(output, 410, "Gone", ""); return; }
            Range range;
            try { range = Range.parse(request.headers.get("range"), item.size); }
            catch (IllegalArgumentException error) { phase = ConnectionError.RESPONSE_WRITE_IO; error(output, 416, "Range Not Satisfiable", "Content-Range: bytes */" + item.size + "\r\n"); return; }
            mediaRequest(request.method.equals("HEAD"));
            client.streaming = true; client.progress = System.nanoTime();
            StringBuilder header = new StringBuilder("HTTP/1.1 ").append(range.partial ? "206 Partial Content" : "200 OK").append("\r\nConnection: close\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ").append(range.length()).append("\r\ntransferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: ").append(FEATURES).append("\r\nExt:\r\n");
            if (range.partial) header.append("Content-Range: bytes ").append(range.start).append('-').append(range.end).append('/').append(item.size).append("\r\n");
            phase = ConnectionError.RESPONSE_WRITE_IO;
            header.append("\r\n"); output.write(header.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)); response(range.partial ? 206 : 200); output.flush();
            if (request.method.equals("HEAD")) return;
            phase = ConnectionError.LOCAL_MEDIA_IO;
            file.position(range.start); long remaining = range.length(); ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
            while (remaining > 0 && !closed.get()) {
                buffer.clear(); buffer.limit((int) Math.min(buffer.capacity(), remaining)); int read = file.read(buffer);
                if (read < 0) throw new IOException("Local media changed");
                if (read == 0) continue;
                phase = ConnectionError.MEDIA_WRITE_IO;
                output.write(buffer.array(), 0, read); bodyWritten(read); remaining -= read; client.progress = System.nanoTime();
                phase = ConnectionError.LOCAL_MEDIA_IO;
            }
            phase = ConnectionError.MEDIA_WRITE_IO;
            output.flush();
        } catch (BadRequest error) {
            try { error(client.socket.getOutputStream(), error.status, error.status == 431 ? "Request Header Fields Too Large" : "Bad Request", ""); }
            catch (IOException ignored) { client.error(ConnectionError.RESPONSE_WRITE_IO); }
        } catch (IOException error) {
            client.error(error instanceof SocketTimeoutException && phase == ConnectionError.HEADER_READ_IO ? ConnectionError.HEADER_TIMEOUT : phase);
            // Cancellation, receiver disconnect, local file mutation, and deadlines close this one connection.
        } catch (RuntimeException error) {
            client.error(ConnectionError.INTERNAL_ERROR);
        } finally { client.close(); }
    }

    private static final class Request {
        final String method, path; final Map<String, String> headers;
        Request(String method, String path, Map<String, String> headers) { this.method = method; this.path = path; this.headers = headers; }
    }
    private static final class BadRequest extends IOException {
        final int status;
        BadRequest(int status) { this.status = status; }
    }
    private Request request(Client client) throws IOException {
        InputStream input = client.socket.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < 4) {
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - client.started) >= timeouts.headerMillis) throw new SocketTimeoutException("Header deadline");
            int value = input.read(); if (value < 0) return null;
            if (bytes.size() >= HEADER_BYTES) throw new BadRequest(431);
            if (value > 126 || (value < 32 && value != '\r' && value != '\n' && value != '\t')) throw new BadRequest(400);
            bytes.write(value); matched = value == end[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
        }
        String[] lines = bytes.toString("US-ASCII").split("\r\n", -1);
        if (lines.length > HEADER_COUNT + 3 || lines[0].length() > 2048) throw new BadRequest(431);
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !first[0].matches("[A-Z]+") || first[1].isEmpty() || !(first[2].equals("HTTP/1.1") || first[2].equals("HTTP/1.0"))) throw new BadRequest(400);
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length - 2; i++) {
            String line = lines[i]; int colon = line.indexOf(':');
            if (colon <= 0 || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) throw new BadRequest(400);
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+") || headers.put(name, value) != null) throw new BadRequest(400);
        }
        if (headers.containsKey("transfer-encoding") || (headers.containsKey("content-length") && !headers.get("content-length").equals("0"))) throw new BadRequest(400);
        return new Request(first[0], first[1], headers);
    }
    private static final class Range {
        final long start, end; final boolean partial;
        Range(long start, long end, boolean partial) { this.start = start; this.end = end; this.partial = partial; }
        long length() { return end - start + 1; }
        static Range parse(String value, long size) {
            if (value == null) return new Range(0, size - 1, false);
            if (!value.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) throw new IllegalArgumentException();
            String[] ends = value.substring(6).split("-", -1);
            try {
                if (ends[0].isEmpty()) { long suffix = Long.parseLong(ends[1]); if (suffix <= 0) throw new IllegalArgumentException(); return new Range(Math.max(0, size - suffix), size - 1, true); }
                long start = Long.parseLong(ends[0]); long finish = ends[1].isEmpty() ? size - 1 : Long.parseLong(ends[1]);
                if (start >= size || start > finish) throw new IllegalArgumentException();
                return new Range(start, Math.min(finish, size - 1), true);
            } catch (NumberFormatException error) { throw new IllegalArgumentException(error); }
        }
    }
    private void error(OutputStream output, int code, String reason, String extra) throws IOException {
        output.write(("HTTP/1.1 " + code + " " + reason + "\r\nConnection: close\r\nContent-Length: 0\r\n" + extra + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII)); response(code); output.flush();
    }
    private static void quietly(Runnable action) { try { action.run(); } catch (RuntimeException ignored) {} }
    private static void closeQuietly(Closeable item) { if (item != null) try { item.close(); } catch (IOException ignored) {} }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeQuietly(listener); watchdog.shutdownNow();
        List<Publication> previous;
        synchronized (publications) { previous = new ArrayList<>(publications.values()); publications.clear(); }
        for (Publication item : previous) item.revoke();
        for (Client client : new ArrayList<>(clients)) client.close();
        workers.shutdownNow();
        try { workers.awaitTermination(1500, TimeUnit.MILLISECONDS); acceptor.join(1000); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
