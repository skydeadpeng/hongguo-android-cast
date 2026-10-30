package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Small, bounded HTTPS/1.1 client. DNS-checked addresses are the actual TLS socket targets. */
final class NetworkGuard {
    static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    static final String REFERER = "https://hongguoduanju.com/";
    static final int LIMIT = 8 * 1024 * 1024;
    private static final Set<String> OFFICIAL = new HashSet<>(Arrays.asList("hongguoduanju.com", "www.hongguoduanju.com"));
    private static final Set<String> EXTERNAL = new HashSet<>(Arrays.asList("hongguoduanju.com", "www.hongguoduanju.com", "github.com", "www.github.com"));
    // Uninterruptible platform DNS may retain at most two workers; no queued lookups.
    private static final ExecutorService DNS = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), runnable -> { Thread thread = new Thread(runnable, "hongguo-source-dns"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    /** One daemon timer closes stalled TCP/TLS/write/read operations on deadline or owner cancellation. */
    private static final ScheduledThreadPoolExecutor SOCKET_TIMEOUT = socketTimer();
    private static ScheduledThreadPoolExecutor socketTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> { Thread thread = new Thread(runnable, "hongguo-source-timeout"); thread.setDaemon(true); return thread; });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
    static SocketGuard guardSocket(Socket socket, long deadline) throws IOException {
        remaining(deadline, Integer.MAX_VALUE);
        return new SocketGuard(socket, deadline);
    }
    static final class SocketGuard implements AutoCloseable {
        private final ScheduledFuture<?> task;
        SocketGuard(Socket socket, long deadline) {
            Thread owner = Thread.currentThread();
            task = SOCKET_TIMEOUT.scheduleAtFixedRate(() -> {
                if (owner.isInterrupted() || System.nanoTime() >= deadline) try { socket.close(); } catch (IOException ignored) { }
            }, 0, 10, TimeUnit.MILLISECONDS);
        }
        @Override public void close() { task.cancel(false); }
    }
    static int remaining(long deadline, int maximum) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("网络请求已取消");
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new SocketTimeoutException("网络请求超时");
        return (int) Math.max(1, Math.min(maximum, TimeUnit.NANOSECONDS.toMillis(nanos)));
    }
    static final class Reply {
        final String body, url; final int status;
        Reply(String body, int status, String url) { this.body = body; this.status = status; this.url = url; }
    }
    /** A successful HTTP status carrying a known error-page MIME, not video bytes. */
    static final class MediaResponseException extends IOException {
        MediaResponseException(String message) { super(message); }
    }
    /** Only a rejected media response redirect, never an invalid caller-supplied model. */
    static final class MediaRedirectException extends IOException {
        MediaRedirectException() { super("片源跳转不可用（地址无效或跳转次数超限）"); }
    }
    static URI validate(String value, boolean officialOnly) throws IOException, URISyntaxException {
        if (value == null || value.length() > 4096 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IOException("链接无效");
        URI uri = new URI(value);
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IOException("仅接受标准 HTTPS 链接");
        host = host.toLowerCase(Locale.ROOT);
        if (!host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?") || !host.contains(".") || host.matches("[0-9.]+") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home")) throw new IOException("链接必须指向公网域名");
        if (officialOnly) {
            String path = uri.getPath();
            if (!OFFICIAL.contains(host) || path == null) throw new IOException("仅支持官网分类、搜索及播放页面");
            if (path.contains("..") || path.contains("\\")) throw new IOException("页面路径无效");
            boolean legacy = path.equals("/search") || path.startsWith("/search/") || path.startsWith("/category/") || path.startsWith("/player/");
            boolean detail = path.equals("/detail") && path.equals(uri.getRawPath());
            boolean searchApi = host.equals("hongguoduanju.com") && path.equals("/api/search/result") && path.equals(uri.getRawPath());
            if (detail || searchApi) validateOfficialQuery(uri, searchApi);
            else if (!legacy) throw new IOException("仅支持官网分类、搜索及播放页面");
        }
        return uri;
    }
    /** Only two exact metadata endpoints are added; arbitrary official APIs remain closed. */
    private static void validateOfficialQuery(URI uri, boolean search) throws IOException {
        Map<String, String> fields = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) throw new IOException("官网页面参数无效");
        try {
            for (String pair : raw.split("&", -1)) {
                String[] parts = pair.split("=", 2);
                if (parts.length != 2) throw new IOException("官网页面参数无效");
                String key = URLDecoder.decode(parts[0], "UTF-8"), value = URLDecoder.decode(parts[1], "UTF-8");
                if (fields.put(key, value) != null) throw new IOException("官网页面参数重复");
            }
        } catch (IllegalArgumentException invalid) { throw new IOException("官网页面参数无效"); }
        if (!search) {
            if (fields.size() != 1 || !fields.getOrDefault("series_id", "").matches("[0-9]{1,30}")) throw new IOException("官网剧集编号无效");
            return;
        }
        String keyword = fields.getOrDefault("query", ""), page = fields.getOrDefault("page", "");
        if (fields.size() != 3 || !"8662".equals(fields.get("aid")) || keyword.trim().isEmpty() || keyword.length() > 100 ||
            !page.matches("[1-9][0-9]?") || Integer.parseInt(page) > 50) throw new IOException("官网搜索参数无效");
        for (int i = 0; i < keyword.length(); i++) if (Character.isISOControl(keyword.charAt(i))) throw new IOException("官网搜索参数无效");
    }
    /** Convert the caller's absolute wall-clock expiry once; queue time is part of the budget. */
    static long pageDeadline(long timeoutMs, long deadlineMs) throws IOException {
        if (timeoutMs < 1 || timeoutMs > 25000 || deadlineMs < 0 || deadlineMs > 9007199254740991L) throw new IOException("页面请求等待期限无效");
        long budget = timeoutMs;
        if (deadlineMs > 0) budget = Math.min(budget, deadlineMs - System.currentTimeMillis());
        if (budget <= 0) throw new SocketTimeoutException("页面请求超时");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
        remaining(deadline, Integer.MAX_VALUE);
        return deadline;
    }
    /** Resolve without altering signed paths/queries; rejected targets are never requested. */
    static URI mediaRedirect(URI from, String location) throws MediaRedirectException {
        try {
            if (from == null || location == null || location.isEmpty() || location.length() > 4096)
                throw new IOException("媒体跳转地址无效");
            return validate(from.resolve(location).toString(), false);
        } catch (IOException | URISyntaxException | IllegalArgumentException rejected) {
            // URI parse errors include the signed URL in their message. Retain a typed,
            // finite category rather than leaking that URL through a cause or diagnostic.
            throw new MediaRedirectException();
        }
    }
    static boolean publicAddress(InetAddress address) {
        byte[] b = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && c >= 64 && c <= 127) || (a == 169 && c == 254) || (a == 172 && c >= 16 && c <= 31) || (a == 192 && (c == 168 || c == 0 && (d == 0 || d == 2))) || (a == 198 && (c == 18 || c == 19 || c == 51 && d == 100)) || (a == 203 && c == 0 && d == 113));
        }
        // Globally routable IPv6, excluding Teredo, documentation and 6to4 ranges.
        return b.length == 16 && (b[0] & 224) == 32 && !((b[0] & 255) == 32 && (b[1] & 255) == 1 && ((b[2] == 0 && b[3] == 0) || ((b[2] & 255) == 13 && (b[3] & 255) == 184))) && !((b[0] & 255) == 32 && (b[1] & 255) == 2);
    }
    static InetAddress[] publicAddresses(URI uri) throws IOException {
        return publicAddresses(uri, System.nanoTime() + 25_000_000_000L);
    }
    static InetAddress[] publicAddresses(URI uri, long deadline) throws IOException {
        return resolvePublic(() -> InetAddress.getAllByName(uri.getHost()), deadline);
    }
    /** Deadline includes DNS; cancelling the wait never creates an unbounded resolver backlog. */
    static InetAddress[] resolvePublic(Callable<InetAddress[]> resolver, long deadline) throws IOException {
        remaining(deadline, Integer.MAX_VALUE);
        Future<InetAddress[]> future;
        try { future = DNS.submit(resolver); }
        catch (RejectedExecutionException error) { throw new IOException("域名解析繁忙，请重试"); }
        try {
            InetAddress[] addresses = future.get(remaining(deadline, Integer.MAX_VALUE), TimeUnit.MILLISECONDS);
            remaining(deadline, Integer.MAX_VALUE);
            if (addresses == null || addresses.length == 0) throw new UnknownHostException("域名解析为空");
            for (InetAddress address : addresses) if (address == null || !publicAddress(address)) throw new IOException("域名解析到非公网地址");
            return addresses;
        } catch (TimeoutException error) { throw new SocketTimeoutException("域名解析超时"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException("网络请求已取消"); }
        catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("域名解析失败", cause);
        } finally { future.cancel(true); }
    }
    /** Try the checked CDN addresses, not only the first A/AAAA answer. */
    static Socket connectPublic(InetAddress[] addresses,long deadline) throws IOException {
        return connectPublic(addresses,deadline,Socket::new);
    }
    static Socket connectPublic(InetAddress[] addresses,long deadline,java.util.function.Supplier<Socket> sockets) throws IOException {
        if(addresses==null||addresses.length==0)throw new UnknownHostException("域名解析为空");
        // Validate the complete answer set before opening even the first socket.
        for(InetAddress address:addresses)if(address==null||!publicAddress(address))throw new IOException("域名解析到非公网地址");
        IOException last=null;
        for(int i=0;i<Math.min(addresses.length,8);i++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("播放缓存任务已取消");
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw new SocketTimeoutException("播放缓存下载超时");
            int timeout=(int)Math.max(1,Math.min(10000,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
            Socket candidate=sockets.get();boolean connected=false;
            try (SocketGuard ignored = guardSocket(candidate, deadline)) {
                candidate.connect(new InetSocketAddress(addresses[i],443),timeout);
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("播放缓存任务已取消");
                if(System.nanoTime()>=deadline)throw new SocketTimeoutException("播放缓存下载超时");
                connected=true;return candidate;
            } catch(IOException error) {
                if(Thread.currentThread().isInterrupted()||error instanceof InterruptedIOException&&!(error instanceof SocketTimeoutException))throw error;
                last=error;
            } finally { if(!connected)try{candidate.close();}catch(IOException ignored){ } }
        }
        throw last==null?new IOException("片源服务连接未成功"):last;
    }
    static String external(String value) throws Exception {
        URI uri = validate(value, false);
        if (!EXTERNAL.contains(uri.getHost().toLowerCase(Locale.ROOT))) throw new IOException("仅支持官网或 GitHub 链接");
        publicAddresses(uri);
        return uri.toASCIIString();
    }
    static Reply get(String value) throws Exception { return request(value, true, false); }
    static Reply get(String value, long deadline) throws Exception { return request(value, true, false, deadline); }
    static Reply get(String value, long timeoutMs, long deadlineMs) throws Exception { return get(value, pageDeadline(timeoutMs, deadlineMs)); }
    static String media(String value) throws Exception { return request(value, false, true).url; }
    /** Bounded binary transfer; each redirect resolves public IPs and connects TLS to that checked IP. */
    public static void download(String value, File destination, long maxBytes) throws Exception {
        download(value, destination, maxBytes, UA, REFERER);
    }
    static void download(String value, File destination, long maxBytes, String userAgent, String referer) throws Exception {
        if (userAgent == null || referer == null || userAgent.length() > 2048 || referer.length() > 2048 || userAgent.indexOf('\r') >= 0 || userAgent.indexOf('\n') >= 0 || referer.indexOf('\r') >= 0 || referer.indexOf('\n') >= 0) throw new IOException("片源请求头无效");
        if (destination == null || maxBytes <= 0) throw new IllegalArgumentException("下载参数无效");
        long deadline = System.nanoTime() + 120_000_000_000L;
        boolean complete = false, touchedDestination = false;
        try {
            URI uri = validate(value, false);
            for (int redirects = 0; redirects <= 3; redirects++) {
                InetAddress[] addresses = publicAddresses(uri, deadline);
                if (System.nanoTime() > deadline) throw new IOException("播放缓存下载超时");
                Socket raw = connectPublic(addresses,deadline);
                try (SocketGuard ignored = guardSocket(raw, deadline)) {
                    raw.setSoTimeout(remaining(deadline, 15_000));
                    try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, uri.getHost(), 443, true)) {
                        SSLParameters ssl = socket.getSSLParameters();
                        ssl.setEndpointIdentificationAlgorithm("HTTPS");
                        socket.setSSLParameters(ssl);
                        socket.startHandshake();
                        writeMediaRequest(socket.getOutputStream(),uri,userAgent,referer);
                        InputStream input = new DeadlineInputStream(new BufferedInputStream(socket.getInputStream()), socket, deadline);
                        String statusLine = line(input, 8192);
                        if (!statusLine.matches("HTTP/1\\.[01] [0-9]{3}.*")) throw new IOException("片源响应无效");
                        int status = Integer.parseInt(statusLine.substring(9, 12));
                        Map<String, String> headers = new HashMap<>();
                        int headerSize = statusLine.length();
                        for (;;) {
                            String row = line(input, 8192);
                            headerSize += row.length();
                            if (headerSize > 65536) throw new IOException("响应头过长");
                            if (row.isEmpty()) break;
                            int colon = row.indexOf(':');
                            if (colon <= 0) throw new IOException("片源响应头无效");
                            String name = row.substring(0, colon).toLowerCase(Locale.ROOT);
                            if (headers.containsKey(name) && Arrays.asList("content-length", "transfer-encoding", "content-encoding", "location").contains(name)) throw new IOException("片源响应头重复");
                            headers.put(name, row.substring(colon + 1).trim());
                        }
                        if (Arrays.asList(301, 302, 303, 307, 308).contains(status)) {
                            if (redirects == 3) throw new MediaRedirectException();
                            uri = mediaRedirect(uri, headers.get("location"));
                            continue;
                        }
                        if (status != 200) throw new IOException("片源响应 HTTP " + status);
                        touchedDestination = true;
                        try (FileOutputStream output = new FileOutputStream(destination)) {
                            streamMediaBody(input, output, headers, maxBytes, deadline);
                            output.flush();
                            output.getFD().sync();
                        }
                        complete = true;
                        return;
                    }
                } finally { raw.close(); }
            }
            throw new IOException("片源跳转无效");
        } finally {
            if (touchedDestination && !complete && destination.exists() && !destination.delete()) destination.deleteOnExit();
        }
    }
    /** The same writer handles first requests and validated redirects; empty means no header. */
    static void writeMediaRequest(OutputStream output, URI uri, String userAgent, String referer) throws IOException {
        if (userAgent == null || referer == null || userAgent.length() > 2048 || referer.length() > 2048 || userAgent.indexOf('\r') >= 0 || userAgent.indexOf('\n') >= 0 || referer.indexOf('\r') >= 0 || referer.indexOf('\n') >= 0) throw new IOException("片源请求头无效");
        String path=uri.getRawPath();
        if(path==null||path.isEmpty())path="/";
        if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
        String request="GET "+path+" HTTP/1.1\r\nHost: "+uri.getHost()+"\r\nUser-Agent: "+userAgent+"\r\n"+
            (referer.isEmpty()?"":"Referer: "+referer+"\r\n")+
            "Accept: video/*,application/octet-stream,*/*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }
    private static final class DeadlineInputStream extends FilterInputStream {
        private final long deadline; private final Socket socket;
        DeadlineInputStream(InputStream input, Socket socket, long deadline) { super(input); this.socket = socket; this.deadline = deadline; }
        private void checkDeadline() throws IOException { socket.setSoTimeout(remaining(deadline, 15_000)); }
        @Override public int read() throws IOException { checkDeadline(); return in.read(); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException { checkDeadline(); return in.read(bytes, offset, length); }
    }
    /** Media-only MIME check; AppTransport legitimately uses streamBody for JSON. */
    static long streamMediaBody(InputStream input, OutputStream output, Map<String, String> headers, long maxBytes, long deadline) throws IOException {
        String type = headers.getOrDefault("content-type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (Arrays.asList("text/html", "application/xhtml+xml", "text/plain", "application/json", "application/xml", "text/xml").contains(type) || type.endsWith("+json")) {
            throw new MediaResponseException("片源返回了非视频内容，请重新连接片源");
        }
        return streamBody(input, output, headers, maxBytes, deadline);
    }
    /** Package-visible for offline framing tests and bounded App JSON bodies. */
    static long streamBody(InputStream input, OutputStream output, Map<String, String> headers, long maxBytes, long deadline) throws IOException {
        if (maxBytes <= 0) throw new IOException("播放缓存大小上限无效");
        if (!headers.getOrDefault("content-encoding", "identity").equalsIgnoreCase("identity")) throw new IOException("片源压缩编码不受支持");
        String transfer = headers.get("transfer-encoding");
        String contentLength = headers.get("content-length");
        if (transfer != null && (contentLength != null || !transfer.equalsIgnoreCase("chunked"))) throw new IOException("片源传输长度存在歧义");
        long total = 0;
        if (transfer != null) {
            for (;;) {
                String size = line(input, 128).split(";", 2)[0].trim();
                if (!size.matches("[a-fA-F0-9]{1,16}")) throw new IOException("片源响应分块无效");
                long length;
                try { length = Long.parseLong(size, 16); } catch (NumberFormatException error) { throw new IOException("片源响应分块无效", error); }
                if (length > maxBytes - total) throw new IOException("片源超过单集缓存上限");
                if (length == 0) {
                    int trailerSize = 0;
                    for (;;) {
                        String trailer = line(input, 8192);
                        trailerSize += trailer.length();
                        if (trailerSize > 65536) throw new IOException("片源分块尾部过长");
                        if (trailer.isEmpty()) return total;
                        if (trailer.indexOf(':') <= 0) throw new IOException("片源分块尾部无效");
                    }
                }
                total += copyBinary(input, output, length, maxBytes - total, deadline);
                if (!line(input, 2).isEmpty()) throw new IOException("片源响应分块格式无效");
            }
        }
        long length = -1;
        if (contentLength != null) {
            if (!contentLength.matches("[0-9]{1,19}")) throw new IOException("片源响应长度无效");
            try { length = Long.parseLong(contentLength); } catch (NumberFormatException error) { throw new IOException("片源响应长度无效", error); }
            if (length > maxBytes) throw new IOException("片源超过单集缓存上限");
        }
        return copyBinary(input, output, length, maxBytes, deadline);
    }
    private static long copyBinary(InputStream input, OutputStream output, long length, long maxBytes, long deadline) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long total = 0, remaining = length;
        while (remaining != 0) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("播放缓存任务已取消");
            if (System.nanoTime() > deadline) throw new IOException("播放缓存下载超时");
            int count = input.read(buffer, 0, remaining < 0 ? buffer.length : (int) Math.min(buffer.length, remaining));
            if (count < 0) { if (remaining > 0) throw new EOFException("片源响应不完整"); break; }
            if (count > maxBytes - total) throw new IOException("片源超过单集缓存上限");
            output.write(buffer, 0, count); total += count;
            if (remaining > 0) remaining -= count;
        }
        return total;
    }
    private static Reply request(String value, boolean officialOnly, boolean head) throws Exception {
        return request(value, officialOnly, head, System.nanoTime() + 25_000_000_000L);
    }
    private static Reply request(String value, boolean officialOnly, boolean head, long deadline) throws Exception {
        remaining(deadline, Integer.MAX_VALUE);
        URI uri = validate(value, officialOnly);
        for (int redirects = 0; redirects <= 3; redirects++) {
            InetAddress[] addresses = publicAddresses(uri, deadline);
            Socket raw = connectPublic(addresses, deadline);
            try (SocketGuard ignored = guardSocket(raw, deadline)) {
                raw.setSoTimeout(remaining(deadline, 15_000));
                try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, uri.getHost(), 443, true)) {
                    SSLParameters ssl = socket.getSSLParameters();
                    ssl.setEndpointIdentificationAlgorithm("HTTPS");
                    socket.setSSLParameters(ssl);
                    socket.startHandshake();
                    String path = uri.getRawPath();
                    if (path == null || path.isEmpty()) path = "/";
                    if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                    String request = (head ? "HEAD " : "GET ") + path + " HTTP/1.1\r\nHost: " + uri.getHost() + "\r\nUser-Agent: " + UA + "\r\nReferer: " + REFERER + "\r\nAccept: text/html,*/*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    InputStream input = new DeadlineInputStream(new BufferedInputStream(socket.getInputStream()), socket, deadline);
                    String statusLine = line(input, 8192);
                    if (!statusLine.matches("HTTP/1\\.[01] [0-9]{3}.*")) throw new IOException("来源响应无效");
                    int status = Integer.parseInt(statusLine.substring(9, 12));
                    Map<String, String> headers = new HashMap<>();
                    int headerSize = statusLine.length();
                    for (;;) {
                        String row = line(input, 8192);
                        headerSize += row.length();
                        if (headerSize > 65536) throw new IOException("响应头过长");
                        if (row.isEmpty()) break;
                        int colon = row.indexOf(':');
                        if (colon > 0) headers.put(row.substring(0, colon).toLowerCase(Locale.ROOT), row.substring(colon + 1).trim());
                    }
                    if (Arrays.asList(301, 302, 303, 307, 308).contains(status)) {
                        if (redirects == 3 || !headers.containsKey("location")) throw new IOException("来源跳转次数过多");
                        uri = validate(uri.resolve(headers.get("location")).toString(), officialOnly);
                        continue;
                    }
                    if (head) {
                        if (status < 200 || status >= 400) throw new IOException("片源响应 HTTP " + status);
                        remaining(deadline, Integer.MAX_VALUE);
                        return new Reply("", status, uri.toASCIIString());
                    }
                    String encoding = headers.getOrDefault("content-encoding", "identity");
                    if (!encoding.equalsIgnoreCase("identity")) throw new IOException("来源返回了不支持的压缩编码");
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                        while (true) {
                            String size = line(input, 128).split(";", 2)[0].trim();
                            long length;
                            try { length = Long.parseLong(size, 16); } catch (NumberFormatException ex) { throw new IOException("响应分块无效"); }
                            if (length < 0 || length > LIMIT - body.size()) throw new IOException("响应超过 8 MiB");
                            if (length == 0) break;
                            copy(input, body, length, deadline);
                            if (!line(input, 2).isEmpty()) throw new IOException("响应分块格式无效");
                        }
                    } else {
                        long length = -1;
                        if (headers.containsKey("content-length")) {
                            try { length = Long.parseLong(headers.get("content-length")); } catch (NumberFormatException ex) { throw new IOException("响应长度无效"); }
                            if (length < 0 || length > LIMIT) throw new IOException("响应超过 8 MiB");
                        }
                        copy(input, body, length, deadline);
                    }
                    remaining(deadline, Integer.MAX_VALUE);
                    return new Reply(body.toString(StandardCharsets.UTF_8.name()), status, uri.toASCIIString());
                }
            } finally { raw.close(); }
        }
        throw new IOException("来源跳转无效");
    }
    private static void copy(InputStream input, ByteArrayOutputStream body, long length, long deadline) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining != 0) {
            if (System.nanoTime() > deadline) throw new IOException("网络请求超时");
            int count = input.read(buffer, 0, remaining < 0 ? buffer.length : (int) Math.min(buffer.length, remaining));
            if (count < 0) { if (remaining > 0) throw new EOFException("来源响应不完整"); break; }
            if (body.size() + count > LIMIT) throw new IOException("响应超过 8 MiB");
            body.write(buffer, 0, count);
            if (remaining > 0) remaining -= count;
        }
    }
    private static String line(InputStream input, int max) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() <= max) {
            int c = input.read();
            if (c < 0) throw new EOFException("来源响应中断");
            if (c == '\n') return bytes.toString(StandardCharsets.US_ASCII.name()).replaceFirst("\\r$", "");
            bytes.write(c);
        }
        throw new IOException("响应行过长");
    }
}
