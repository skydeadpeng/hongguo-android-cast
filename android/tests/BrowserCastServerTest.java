package io.github.jackotom.hongguo.android;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual production server + real bounded local sockets; no LAN discovery or remote media. */
public final class BrowserCastServerTest {
    private static int checks;
    private static final InetAddress LOOP;
    static { try { LOOP = InetAddress.getByName("127.0.0.1"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    interface Task { void run() throws Exception; }
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    static void rejected(Task task, String label) throws Exception { boolean rejected = false; try { task.run(); } catch (Exception expected) { rejected = true; } check(rejected, label); }
    static void until(Task assertion, long ms) throws Exception { long end = System.nanoTime() + ms * 1000000; while (true) { try { assertion.run(); return; } catch (AssertionError e) { if (System.nanoTime() >= end) throw e; Thread.sleep(10); } } }
    static Map<String, byte[]> assets() { Map<String, byte[]> out = new HashMap<>(); out.put("/", "<!doctype html><title>接收</title>".getBytes(StandardCharsets.UTF_8)); out.put("/receiver.js", "'use strict';".getBytes(StandardCharsets.UTF_8)); out.put("/receiver.css", "body{color:white}".getBytes(StandardCharsets.UTF_8)); return out; }
    static final class Handler implements BrowserCastServer.Handler {
        AtomicInteger paired = new AtomicInteger(), events = new AtomicInteger(), commands = new AtomicInteger(), states = new AtomicInteger();
        String name;
        public JSONObject state() { states.incrementAndGet(); try { return new JSONObject().put("active", true).put("state", "PLAYING"); } catch (Exception e) { throw new RuntimeException(e); } }
        public void event(JSONObject event) { events.incrementAndGet(); }
        public void command(JSONObject command) { commands.incrementAndGet(); }
        public void paired(String value) { paired.incrementAndGet(); name = value; }
    }
    static BrowserCastServer server(Handler h) throws Exception { return new BrowserCastServer(LOOP, assets(), h, true); }
    static BrowserCastServer server(Handler h, long read, long idle, long life, long ttl, long lockout) throws Exception { return new BrowserCastServer(LOOP, assets(), h, true, new BrowserCastServer.Timeouts(read, idle, life, ttl, lockout)); }
    static Socket socket(URI url) throws Exception { Socket socket = new Socket(); socket.setSoTimeout(5000); socket.setReceiveBufferSize(4096); socket.connect(new InetSocketAddress(LOOP, url.getPort()), 1000); return socket; }
    static void send(Socket socket, String text) throws Exception { socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush(); }
    static final class Reply {
        final int status; final byte[] body; final Map<String, String> headers = new HashMap<>();
        Reply(byte[] all) { String text = new String(all, StandardCharsets.ISO_8859_1); int split = text.indexOf("\r\n\r\n"); if (split < 0) throw new AssertionError("Missing HTTP response"); String[] lines = text.substring(0, split).split("\r\n"); status = Integer.parseInt(lines[0].split(" ")[1]); for (int i = 1; i < lines.length; i++) { int colon = lines[i].indexOf(':'); headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim()); } body = Arrays.copyOfRange(all, split + 4, all.length); }
        JSONObject json() throws Exception { return new JSONObject(new String(body, StandardCharsets.UTF_8)); }
    }
    static Reply readReply(Socket socket, boolean head) throws Exception {
        String raw = header(socket); int length = 0;
        for (String line : raw.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(raw.getBytes(StandardCharsets.US_ASCII));
        if (!head) { byte[] body = socket.getInputStream().readNBytes(length); if (body.length != length) throw new AssertionError("Truncated response"); bytes.write(body); }
        if (head && socket.getInputStream().read() != -1) throw new AssertionError("HEAD response illegally included a body");
        return new Reply(bytes.toByteArray());
    }
    static String origin(URI uri) { return "http://" + uri.getHost() + ":" + uri.getPort(); }
    static Reply request(URI uri, String method, String path, String extra, String body) throws Exception {
        try (Socket socket = socket(uri)) { send(socket, method + " " + path + " HTTP/1.1\r\nHost: " + uri.getHost() + ":" + uri.getPort() + "\r\n" + extra + (body == null ? "" : "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n") + "\r\n" + (body == null ? "" : body)); return readReply(socket, method.equals("HEAD")); }
    }
    static Reply get(URI uri, String path, String token) throws Exception { return request(uri, "GET", path, token == null ? "" : "X-Cast-Token: " + token + "\r\n", null); }
    static Reply post(URI uri, String path, String token, String body) throws Exception { return request(uri, "POST", path, "Origin: " + origin(uri) + "\r\nContent-Type: application/json\r\n" + (token == null ? "" : "X-Cast-Token: " + token + "\r\n"), body); }
    static String pair(BrowserCastServer server, URI uri) throws Exception { Reply response = post(uri, "/pair", null, "{\"code\":\"" + server.pairCode() + "\",\"name\":\"Mac 接收端\"}"); check(response.status == 200, "correct code pairs"); return response.json().getString("token"); }
    static String header(Socket socket) throws Exception { InputStream input = socket.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] end = {'\r','\n','\r','\n'}; int n = 0; while (n < 4) { int b = input.read(); if (b < 0) throw new AssertionError("Missing response header"); out.write(b); n = b == end[n] ? n + 1 : b == '\r' ? 1 : 0; } return out.toString("US-ASCII"); }
    static final class Pins { AtomicInteger count = new AtomicInteger(), acquire = new AtomicInteger(), release = new AtomicInteger(); void retain() { acquire.incrementAndGet(); count.incrementAndGet(); } void release() { release.incrementAndGet(); if (count.decrementAndGet() < 0) throw new AssertionError("Lease underflow"); } }
    static DlnaSession.Prepared prepared(File file, Pins pins) { return new DlnaSession.Prepared(file, "Test", "1080p", 1, pins::retain, pins::release); }
    static Object field(Object value, String name) throws Exception { java.lang.reflect.Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value); }
    static void setField(Object value, String name, Object next) throws Exception { java.lang.reflect.Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); f.set(value, next); }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("hongguo-browser-server-").toFile(), file = new File(dir, "ready.mp4"); byte[] bytes = new byte[1000]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251); Files.write(file.toPath(), bytes);
        try {
            for (String ip : new String[]{"10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.9.1"}) check(BrowserCastServer.privateIpv4(InetAddress.getByName(ip)), "private IPv4 accepted " + ip);
            for (String ip : new String[]{"127.0.0.1", "0.0.0.0", "172.15.0.1", "172.32.0.1", "169.254.0.1", "224.0.0.1", "8.8.8.8", "::1"}) check(!BrowserCastServer.privateIpv4(InetAddress.getByName(ip)), "non-private rejected " + ip);
            rejected(() -> new BrowserCastServer(LOOP, assets(), new Handler()), "production constructor rejects loopback");
            rejected(() -> new BrowserCastServer(InetAddress.getByName("0.0.0.0"), assets(), new Handler(), true), "test flag never allows wildcard");
            rejected(() -> new BrowserCastServer(LOOP, new HashMap<>(), new Handler(), true), "fixed resources mandatory");
            Handler h = new Handler();
            try (BrowserCastServer server = server(h)) {
                URI uri = URI.create(server.landingUrl());
                check(server.pairCode().matches("[0-9]{6}") && !server.isPaired(), "six-digit pairing begins unpaired");
                for (String path : new String[]{"/", "/receiver.js", "/receiver.css"}) { Reply r = get(uri, path, null); check(r.status == 200 && Arrays.equals(r.body, assets().get(path)), "fixed asset exact bytes " + path); check(r.headers.get("cache-control").equals("no-store") && r.headers.get("referrer-policy").equals("no-referrer") && r.headers.get("content-security-policy").contains("frame-ancestors 'none'") && !r.headers.containsKey("access-control-allow-origin"), "security headers/no CORS " + path); }
                Reply head = request(uri, "HEAD", "/", "", null); check(head.status == 200 && head.body.length == 0 && Integer.parseInt(head.headers.get("content-length")) == assets().get("/").length, "asset HEAD exact declared length");
                for (String path : new String[]{"/../ready.mp4", "/%2e%2e/ready.mp4", "/receiver.js?x=1", "/secret", "//receiver.js"}) check(get(uri, path, null).status == 404, "no file/proxy/query lookup " + path);
                check(get(uri, "/state", null).status == 401 && h.states.get() == 0, "state protected before pairing");
                Reply badHead = request(uri, "HEAD", "/missing", "", null); check(badHead.status == 404 && badHead.body.length == 0, "HEAD errors have no body");
                check(request(uri, "GET", "/", "Origin: null\r\n", null).status == 403, "null Origin denied");
                check(request(uri, "GET", "/", "Origin: http://attacker.invalid\r\n", null).status == 403, "foreign Origin denied on assets");
                try (Socket sock = socket(uri)) { send(sock, "GET / HTTP/1.1\r\nHost: attacker.invalid\r\n\r\n"); check(new Reply(sock.getInputStream().readAllBytes()).status == 403, "DNS rebinding Host denied"); }
                try (Socket sock = socket(uri)) { send(sock, "GET / HTTP/1.1\r\n\r\n"); check(new Reply(sock.getInputStream().readAllBytes()).status == 403, "missing Host denied"); }
                String good = "{\"code\":\"" + server.pairCode() + "\"}";
                check(request(uri, "POST", "/pair", "Content-Type: application/json\r\n", good).status == 403, "mutation requires browser Origin");
                check(request(uri, "POST", "/pair", "Origin: " + origin(uri) + "\r\nContent-Type: text/plain\r\n", good).status == 415, "simple cross-site body rejected");
                check(request(uri, "POST", "/pair", "Origin: " + origin(uri) + "\r\nContent-Type: application/json\r\n", null).status == 411, "POST bounded content-length required");
                String token = pair(server, uri);
                check(token.matches("[0-9a-f]{64}") && server.isPaired() && h.paired.get() == 1 && h.name.equals("Mac 接收端"), "256-bit token and one pairing callback");
                check(pair(server, uri).equals(token) && h.paired.get() == 1, "same peer/code reconnect returns existing token");
                check(get(uri, "/state", token).status == 200 && h.states.get() == 1, "paired token reaches state handler");
                check(get(uri, "/state", token.substring(1)).status == 401, "invalid token denied");
                check(get(uri, "/state?token=" + token, null).status == 404, "query never authenticates");
                check(post(uri, "/event", token, "{\"type\":\"ended\"}").status == 200 && h.events.get() == 1, "authenticated event delivered");
                check(post(uri, "/command", token, "{\"type\":\"next\"}").status == 200, "authenticated command acknowledged");
                until(() -> check(h.commands.get() == 1, "acknowledged command dispatched"), 1000);
                check(post(uri, "/command", "bad", "{}").status == 401 && h.commands.get() == 1, "invalid token never calls command");
                check(post(uri, "/state", token, "{}").status == 405, "state read-only method");
                check(get(uri, "/command", token).status == 405, "command rejects GET");
                check(post(uri, "/event", token, "[]").status == 400, "array body denied");
                check(post(uri, "/event", token, "{bad}").status == 400, "invalid JSON denied");
                check(post(uri, "/event", token, "{}{}").status == 400, "trailing JSON values denied");
                check(post(uri, "/event", token, "{\"x\":" + "[".repeat(35) + "0" + "]".repeat(35) + "}").status == 400, "deep JSON denied before recursive parser");
                check(post(uri, "/event", token, "{\"x\":\"" + "a".repeat(8200) + "\"}").status == 413, "body above 8192 denied");
                check(request(uri, "GET", "/", "Transfer-Encoding: chunked\r\n", null).status == 400, "chunking denied");
                check(request(uri, "GET", "/", "Content-Length: 1\r\n", "x").status == 400, "duplicate/body GET denied");
                check(request(uri, "GET", "/", "X-A: 1\r\nX-A: 2\r\n", null).status == 400, "duplicate headers denied");
                check(request(uri, "GET", "/", "X-Bad : 1\r\n", null).status == 400, "whitespace header name denied");
                check(request(uri, "GET", "/", "Expect: 100-continue\r\n", null).status == 400, "unsupported expectation denied");
                StringBuilder many = new StringBuilder(); for (int i = 0; i < 42; i++) many.append("X-").append(i).append(": v\r\n"); check(request(uri, "GET", "/", many.toString(), null).status == 431, "header count bounded");
                try (Socket sock = socket(uri)) { boolean denied = false; try { send(sock, "GET / HTTP/1.1\r\nHost: " + uri.getAuthority() + "\r\nX-Long: " + "x".repeat(9000) + "\r\n\r\n"); denied = new Reply(sock.getInputStream().readAllBytes()).status == 431; } catch (java.io.IOException expected) { denied = true; } check(denied, "headers over 8192 closed or 431"); }
                check(!server.diagnostics().contains(token) && !server.diagnostics().contains(server.pairCode()), "diagnostics never include credentials");
                InetAddress oldPeer = (InetAddress) field(server, "pairedPeer"); setField(server, "pairedPeer", InetAddress.getByName("127.0.0.2"));
                check(get(uri, "/state", token).status == 401, "valid token from other peer rejected (peer fixture)");
                check(post(uri, "/pair", null, good).status == 409, "second peer cannot replace receiver (peer fixture)");
                setField(server, "pairedPeer", oldPeer);

                Pins pins = new Pins(); DlnaSession.Publication pub = server.publish(prepared(file, pins)); URI media = URI.create(pub.url);
                check(media.getPath().matches("/media/[a-f0-9]{64}\\.mp4") && pins.count.get() == 1, "independent 256-bit media capability pins file");
                Reply full = get(uri, media.getPath(), null); check(full.status == 200 && Arrays.equals(full.body, bytes), "media capability works without control token for relay");
                check(full.headers.get("content-type").equals("video/mp4") && full.headers.get("accept-ranges").equals("bytes") && full.headers.get("content-length").equals("1000"), "media response length MIME ranges");
                setField(server, "pairedPeer", InetAddress.getByName("127.0.0.2"));
                check(get(uri, media.getPath(), null).status == 200, "media capability independent of paired browser IP"); setField(server, "pairedPeer", oldPeer);
                Reply mediaHead = request(uri, "HEAD", media.getPath(), "", null); check(mediaHead.status == 200 && mediaHead.body.length == 0 && mediaHead.headers.get("content-length").equals("1000"), "media HEAD no payload");
                String[] ranges = {"bytes=0-9", "bytes=990-", "bytes=-10", "bytes=-9999", "bytes=995-9999", "bytes=0-0"}; int[][] slices = {{0, 10}, {990, 1000}, {990, 1000}, {0, 1000}, {995, 1000}, {0, 1}};
                for (int i = 0; i < ranges.length; i++) { Reply r = request(uri, "GET", media.getPath(), "Range: " + ranges[i] + "\r\n", null); check(r.status == 206 && Arrays.equals(r.body, Arrays.copyOfRange(bytes, slices[i][0], slices[i][1])) && r.headers.get("content-range").equals("bytes " + slices[i][0] + "-" + (slices[i][1] - 1) + "/1000"), "range exact " + ranges[i]); }
                Reply rh = request(uri, "HEAD", media.getPath(), "Range: bytes=10-19\r\n", null); check(rh.status == 206 && rh.body.length == 0 && rh.headers.get("content-length").equals("10"), "ranged HEAD");
                for (String range : new String[]{"bytes=1000-", "bytes=20-10", "bytes=-0", "bytes=0-1,3-4", "bytes=9223372036854775808-", "items=0-1", "bytes=abc-"}) { Reply r = request(uri, "GET", media.getPath(), "Range: " + range + "\r\n", null); check(r.status == 416 && r.headers.get("content-range").equals("bytes */1000"), "invalid range rejected " + range); }
                check(post(uri, media.getPath(), token, "{}").status == 405, "media POST denied");
                check(get(uri, media.getPath() + "?file=elsewhere", null).status == 404, "media query cannot rewrite mapping");
                server.revoke(pub); server.revoke(pub); until(() -> check(pins.count.get() == 0, "revoke releases publication and completed request leases"), 1000);
                check(get(uri, media.getPath(), null).status == 404, "revoked capability rejects new reads");
                rejected(() -> server.publish(prepared(new File(dir, "missing.mp4"), pins)), "unregistered missing media rejected");
                File link = new File(dir, "link.mp4"); Files.createSymbolicLink(link.toPath(), file.toPath()); rejected(() -> server.publish(prepared(link, pins)), "symbolic-link file rejected");
                File tooBig = new File(dir, "oversize.mp4"); try (RandomAccessFile sparse = new RandomAccessFile(tooBig, "rw")) { sparse.setLength(96L * 1024 * 1024 + 1); } rejected(() -> server.publish(prepared(tooBig, pins)), "media above cache size bound rejected");
                File text = new File(dir, "note.txt"); Files.writeString(text.toPath(), "not media"); rejected(() -> server.publish(prepared(text, pins)), "non-MP4 file rejected");
                List<DlnaSession.Publication> pubs = new ArrayList<>(); for (int i = 0; i < 8; i++) pubs.add(server.publish(prepared(file, pins)));
                rejected(() -> server.publish(prepared(file, pins)), "publication count bounded at eight"); for (DlnaSession.Publication p : pubs) server.revoke(p); check(pins.count.get() == 0, "publication-bound failure never leaks leases");
                DlnaSession.Publication changed = server.publish(prepared(file, pins)); Files.write(file.toPath(), new byte[]{1}); check(get(uri, URI.create(changed.url).getPath(), null).status == 410, "changed file length rejected"); server.revoke(changed); Files.write(file.toPath(), bytes);
            }
            Handler locked = new Handler();
            try (BrowserCastServer server = server(locked, 1000, 1000, 5000, 5000, 600)) {
                URI uri = URI.create(server.landingUrl()); String bad = server.pairCode().equals("000000") ? "111111" : "000000";
                for (int i = 0; i < 5; i++) check(post(uri, "/pair", null, "{\"code\":\"" + bad + "\"}").status == 403, "failed pair attempt " + i);
                check(post(uri, "/pair", null, "{\"code\":\"" + server.pairCode() + "\"}").status == 429 && locked.paired.get() == 0, "even correct code rejected after five failures");
                Thread.sleep(700); pair(server, uri); check(locked.paired.get() == 1, "global lockout expires on monotonic clock");
            }
            try (BrowserCastServer server = server(new Handler(), 1000, 1000, 5000, 100, 1000)) { URI uri = URI.create(server.landingUrl()); Thread.sleep(150); check(post(uri, "/pair", null, "{\"code\":\"" + server.pairCode() + "\"}").status == 410, "expired pairing code denied"); }
            try (BrowserCastServer server = server(new Handler(), 200, 200, 1000, 5000, 1000)) {
                URI uri = URI.create(server.landingUrl());
                try (Socket slow = socket(uri)) { send(slow, "GET / HTTP/1.1\r\n"); Thread.sleep(350); check(slow.getInputStream().read() == -1, "absolute header deadline closes slow client"); }
                try (Socket slow = socket(uri)) { send(slow, "POST /pair HTTP/1.1\r\nHost: " + uri.getAuthority() + "\r\nOrigin: " + origin(uri) + "\r\nContent-Type: application/json\r\nContent-Length: 20\r\n\r\n{"); Thread.sleep(350); check(slow.getInputStream().read() == -1, "absolute body deadline closes partial JSON"); }
                List<Socket> sockets = new ArrayList<>(); for (int i = 0; i < 16; i++) { Socket s = socket(uri); sockets.add(s); send(s, "G"); }
                ThreadPoolExecutor pool = (ThreadPoolExecutor) field(server, "workers"); check(pool.getMaximumPoolSize() == 4 && pool.getQueue().remainingCapacity() + pool.getQueue().size() == 8, "workers and queue bounded 4/8 under actual saturation");
                Thread.sleep(400); for (Socket s : sockets) s.close();
                check(get(uri, "/", null).status == 200, "server recovers after exhausted slow-client queue");
            }
            File big = new File(dir, "big.mp4"); try (RandomAccessFile sparse = new RandomAccessFile(big, "rw")) { sparse.setLength(12L * 1024 * 1024); }
            Pins leases = new Pins();
            try (BrowserCastServer server = server(new Handler(), 1000, 5000, 15000, 5000, 1000)) {
                URI uri = URI.create(server.landingUrl()); DlnaSession.Publication p = server.publish(prepared(big, leases)); String path = URI.create(p.url).getPath();
                try (Socket reading = socket(uri)) {
                    send(reading, "GET " + path + " HTTP/1.1\r\nHost: " + uri.getAuthority() + "\r\n\r\n"); check(header(reading).contains(" 200 "), "inflight media response started");
                    server.revoke(p); check(leases.count.get() == 1, "revoked in-flight read keeps exactly one lease"); check(get(uri, path, null).status == 404, "new request denied during old inflight read");
                    check(reading.getInputStream().readAllBytes().length == big.length(), "old inflight read completes after revoke");
                }
                until(() -> check(leases.count.get() == 0, "inflight completion releases final lease"), 1000);
            }
            try (BrowserCastServer server = server(new Handler(), 1000, 200, 5000, 5000, 1000)) {
                URI uri = URI.create(server.landingUrl()); DlnaSession.Publication p = server.publish(prepared(big, leases));
                try (Socket blocked = socket(uri)) { send(blocked, "GET " + URI.create(p.url).getPath() + " HTTP/1.1\r\nHost: " + uri.getAuthority() + "\r\n\r\n"); header(blocked); server.revoke(p); until(() -> check(leases.count.get() == 0, "write-stalled socket watchdog releases request lease"), 3000); }
            }
            java.util.concurrent.atomic.AtomicReference<BrowserCastServer> synchronousStop = new java.util.concurrent.atomic.AtomicReference<>();
            AtomicInteger stoppedCommands = new AtomicInteger();
            BrowserCastServer stopping = new BrowserCastServer(LOOP, assets(), new BrowserCastServer.Handler() {
                public JSONObject state() { return new JSONObject(); }
                public void event(JSONObject value) { }
                public void paired(String name) { }
                public void command(JSONObject value) { stoppedCommands.incrementAndGet(); synchronousStop.get().close(); }
            }, true);
            synchronousStop.set(stopping);
            try (BrowserCastServer receiver = stopping) {
                URI stoppingUrl = URI.create(receiver.landingUrl()); String stoppingToken = pair(receiver, stoppingUrl);
                Reply stopReply = post(stoppingUrl, "/command", stoppingToken, "{\"type\":\"stop\"}");
                check(stopReply.status == 200 && stopReply.json().getBoolean("ok"), "stop ACK body arrives before synchronous callback closes connection");
                until(() -> check(stoppedCommands.get() == 1 && !receiver.isPaired(), "acknowledged stop dispatched exactly once and closes service"), 1000);
            }
            BrowserCastServer stop = server(new Handler()); DlnaSession.Publication p = stop.publish(prepared(big, leases)); URI uri = URI.create(stop.landingUrl());
            try (Socket active = socket(uri)) { send(active, "GET " + URI.create(p.url).getPath() + " HTTP/1.1\r\nHost: " + uri.getAuthority() + "\r\n\r\n"); header(active); stop.close(); stop.close(); check(leases.count.get() == 0, "idempotent close releases publication + active readers"); rejected(() -> stop.publish(prepared(file, leases)), "closed server rejects publications"); check(!stop.isPaired(), "closed server no active pairing"); }
            check(leases.acquire.get() == leases.release.get(), "every real media lease balanced across cancellation and stop");
            System.out.println("PASS BrowserCastServer checks=" + checks + " actualProduction=true realLocalSockets=true auth=paired-private-peer media=separate-capability range=true boundedWorkers=4 boundedQueue=8 dlnaUntouched=true");
        } finally { try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(dir.toPath())) { paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} }); } }
    }
}
