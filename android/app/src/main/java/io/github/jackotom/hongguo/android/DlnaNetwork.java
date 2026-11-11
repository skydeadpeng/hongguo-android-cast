package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** DLNA-only HTTP. Never resolves DNS or contacts a non-RFC1918 literal IPv4. */
public final class DlnaNetwork {
    static final int MAX_BODY = 512 * 1024, MAX_HEADERS = 16384;
    private DlnaNetwork() { }
    public static boolean isPrivateIpv4(String host) {
        if (host == null || !host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        String[] parts = host.split("\\."); int[] n = new int[4];
        for (int i = 0; i < 4; i++) { n[i] = Integer.parseInt(parts[i]); if (n[i] > 255) return false; }
        return n[0] == 10 || n[0] == 172 && n[1] >= 16 && n[1] <= 31 || n[0] == 192 && n[1] == 168;
    }
    public static String localAddress() throws IOException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && isPrivateIpv4(address.getHostAddress())) return address.getHostAddress();
            }
        }
        throw new IOException("未找到局域网 IPv4；此版投屏仅支持 10.x、172.16–31.x、192.168.x 网络");
    }
    static URI endpoint(URI uri, String peer) throws IOException {
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                !isPrivateIpv4(uri.getHost()) || !uri.getHost().equals(peer) || uri.getUserInfo() != null ||
                uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535 || uri.toASCIIString().length() > 4096)
            throw new IOException("投屏地址必须是同一已发现设备的局域网 IPv4 HTTP 地址");
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.startsWith("/")) throw new IOException("投屏地址路径必须为绝对路径");
        return uri.normalize();
    }
    static URI mediaUri(String value) throws IOException {
        try {
            URI uri = new URI(value);
            return endpoint(uri, uri.getHost());
        } catch (URISyntaxException | NullPointerException invalid) { throw new IOException("投屏媒体地址无效", invalid); }
    }
    interface Transport {
        Response exchange(URI uri, String method, Map<String, String> headers, byte[] body, int timeoutMs) throws IOException;
    }
    static final Transport HTTP = DlnaNetwork::request;
    static final class Response {
        final int status; final Map<String, String> headers; final byte[] body;
        Response(int status, Map<String, String> headers, byte[] body) { this.status = status; this.headers = headers; this.body = body; }
    }
    static Response request(URI input, String method, Map<String, String> headers, byte[] body, int timeoutMs) throws IOException {
        URI uri = endpoint(input, input == null ? null : input.getHost());
        if (!method.equals("GET") && !method.equals("POST")) throw new IOException("投屏 HTTP 方法无效");
        byte[] payload = body == null ? new byte[0] : body;
        if (payload.length > MAX_BODY) throw new IOException("投屏请求过大");
        int timeout = Math.max(100, Math.min(5000, timeoutMs)); long deadline = System.nanoTime() + timeout * 1000000L;
        int port = uri.getPort() < 0 ? 80 : uri.getPort();
        String target = requestTarget(uri);
        StringBuilder request = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\nHost: ").append(uri.getHost()).append(':').append(port)
            .append("\r\nConnection: close\r\nAccept-Encoding: identity\r\nContent-Length: ").append(payload.length).append("\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!header.getKey().matches("[A-Za-z0-9-]{1,80}") || !header.getValue().matches("[\\x20-\\x7e]{0,4096}") ||
                    Arrays.asList("host", "connection", "content-length", "transfer-encoding").contains(header.getKey().toLowerCase(Locale.ROOT)))
                throw new IOException("投屏请求头无效");
            request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        request.append("\r\n");
        if (request.length() > MAX_HEADERS) throw new IOException("投屏请求头过大");
        // InetAddress.getByAddress, not getByName: no DNS/rebinding or proxy boundary.
        String[] parts = uri.getHost().split("\\."); byte[] address = new byte[4];
        for (int i = 0; i < 4; i++) address[i] = (byte) Integer.parseInt(parts[i]);
        try (Socket socket = new Socket()) {
            // SO_TIMEOUT applies only to reads. Close at the total deadline also
            // bounds a peer which stops consuming a POST or stalls connect/write.
            Timer watchdog = new Timer("HongguoDlnaDeadline", true);
            watchdog.schedule(new TimerTask() { @Override public void run() { try { socket.close(); } catch (IOException ignored) { } } }, timeout);
            try {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(address), port), timeout);
            socket.setSoTimeout(remaining(deadline));
            OutputStream out = socket.getOutputStream(); out.write(request.toString().getBytes(StandardCharsets.US_ASCII)); out.write(payload); out.flush();
            InputStream source = new FilterInputStream(socket.getInputStream()) {
                @Override public int read() throws IOException { socket.setSoTimeout(remaining(deadline)); return super.read(); }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException { socket.setSoTimeout(remaining(deadline)); return in.read(bytes, offset, length); }
            };
            return readResponse(source, MAX_BODY);
            } finally { watchdog.cancel(); }
        }
    }
    /** HTTP origin-form always starts with '/'; never serialize a platform-relative authority path. */
    static String requestTarget(URI uri) throws IOException {
        URI ascii = URI.create(uri.toASCIIString());
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.startsWith("/")) throw new IOException("投屏路径必须为绝对路径");
        String target = ascii.getRawPath(); if (target == null || target.isEmpty()) target = "/";
        if (!target.startsWith("/")) throw new IOException("投屏路径必须为绝对路径");
        if (ascii.getRawQuery() != null) target += "?" + ascii.getRawQuery();
        if (!target.matches("[\\x21-\\x7e]+")) throw new IOException("投屏路径无效");
        return target;
    }
    private static int remaining(long deadline) throws SocketTimeoutException {
        long ms = (deadline - System.nanoTime()) / 1000000L;
        if (ms <= 0) throw new SocketTimeoutException("投屏设备响应超时");
        return (int) Math.min(5000, ms);
    }
    static Response readResponse(InputStream input, int maxBody) throws IOException {
        int[] budget = { MAX_HEADERS }; String statusLine = line(input, budget);
        if (!statusLine.matches("HTTP/1\\.[01] [0-9]{3}(?: [\\x20-\\x7e]*)?")) throw new IOException("投屏 HTTP 响应无效");
        int status = Integer.parseInt(statusLine.substring(9, 12)); Map<String, String> headers = new LinkedHashMap<>();
        for (int count = 0; ; count++) {
            String row = line(input, budget); if (row.isEmpty()) break;
            if (count >= 64 || row.charAt(0) == ' ' || row.charAt(0) == '\t') throw new IOException("投屏响应头无效");
            int colon = row.indexOf(':'); if (colon < 1) throw new IOException("投屏响应头无效");
            String key = row.substring(0, colon).toLowerCase(Locale.ROOT), value = row.substring(colon + 1).trim();
            if (!key.matches("[!#$%&'*+.^_`|~0-9a-z-]{1,80}") || !value.matches("[\\x20-\\x7e]*") || headers.containsKey(key)) throw new IOException("投屏重复或无效响应头");
            headers.put(key, value);
        }
        if (status >= 300 && status < 400) throw new IOException("投屏设备重定向已拒绝（HTTP " + status + "）");
        String encoding = headers.get("content-encoding"); if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("投屏设备压缩响应不受支持");
        String transfer = headers.get("transfer-encoding"), size = headers.get("content-length");
        if (transfer != null && size != null) throw new IOException("投屏响应长度冲突");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (transfer != null) {
            if (!transfer.equalsIgnoreCase("chunked")) throw new IOException("投屏传输编码不受支持");
            for (int chunks = 0; ; chunks++) {
                if (chunks > 65536) throw new IOException("投屏分块过多");
                String chunkLine = line(input, budget); String raw = chunkLine.split(";", 2)[0];
                if (!raw.matches("[0-9a-fA-F]{1,8}")) throw new IOException("投屏分块长度无效");
                long chunk = Long.parseLong(raw, 16); if (chunk > maxBody - body.size()) throw new IOException("投屏响应过大");
                if (chunk == 0) { while (!line(input, budget).isEmpty()) { /* bounded trailers */ } break; }
                copyExact(input, body, (int) chunk); if (input.read() != '\r' || input.read() != '\n') throw new IOException("投屏分块未完整结束");
            }
        } else if (size != null) {
            if (!size.matches("[0-9]{1,10}")) throw new IOException("投屏响应长度无效");
            long length = Long.parseLong(size); if (length > maxBody) throw new IOException("投屏响应过大");
            copyExact(input, body, (int) length);
        } else {
            byte[] buffer = new byte[4096]; int length;
            while ((length = input.read(buffer)) != -1) { if (length > maxBody - body.size()) throw new IOException("投屏响应过大"); body.write(buffer, 0, length); }
        }
        return new Response(status, Collections.unmodifiableMap(headers), body.toByteArray());
    }
    private static void copyExact(InputStream input, OutputStream out, int size) throws IOException {
        byte[] buffer = new byte[4096];
        while (size > 0) { int n = input.read(buffer, 0, Math.min(size, buffer.length)); if (n < 0) throw new EOFException("投屏响应提前结束"); if (n == 0) continue; out.write(buffer, 0, n); size -= n; }
    }
    static String line(InputStream input, int[] budget) throws IOException {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        for (;;) {
            if (--budget[0] < 0) throw new IOException("投屏响应头过大"); int next = input.read();
            if (next < 0) throw new EOFException("投屏响应头提前结束");
            if (next == '\r') { if (--budget[0] < 0 || input.read() != '\n') throw new IOException("投屏响应换行无效"); return value.toString("US-ASCII"); }
            if (next < 32 || next > 126) throw new IOException("投屏响应头字符无效"); value.write(next);
        }
    }
}
