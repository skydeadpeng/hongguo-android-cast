package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.json.*;

/** Fixed-domain App JSON transport. Never forwards a signed POST to a redirect destination. */
final class AppTransport {
    private static final Set<String> HEADERS = new HashSet<>(Arrays.asList("user-agent", "accept", "content-type", "x-xs-from-web", "x-ss-req-ticket", "x-tt-request-tag", "sdk-version", "passport-sdk-version", "x-vc-bdturing-sdk-version", "x-ss-stub", "x-gorgon", "x-khronos", "x-ladon", "x-argus", "x-helios", "x-medusa", "x-tt-dt", "cookie", "log-encode-type", "x-ss-dp", "x-neptune", "lc"));
    // Also bounds legacy NetworkGuard implementations/test doubles that expose only publicAddresses(URI).
    private static final ExecutorService SEARCH_DNS = new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<Runnable>(), runnable -> {Thread thread=new Thread(runnable,"hongguo-app-dns");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    /** One daemon timer closes stalled TCP/TLS/write/read operations on deadline or owner cancellation. */
    private static final ScheduledThreadPoolExecutor SOCKET_TIMEOUT = socketTimer();
    private static ScheduledThreadPoolExecutor socketTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> { Thread thread = new Thread(runnable, "hongguo-app-timeout"); thread.setDaemon(true); return thread; });
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

    static boolean domain(String host, String... allowed) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String suffix : allowed) if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        return false;
    }
    static JSONObject request(JSONObject params) throws Exception {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("App请求已取消");
        String method = params.optString("method", "GET").toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("POST")) throw new IOException("App请求方法无效");
        String body = params.optString("body", "");
        if (body.getBytes(StandardCharsets.UTF_8).length > 128 * 1024) throw new IOException("App请求过长");
        Map<String, String> headers = new LinkedHashMap<>(); JSONObject input = params.optJSONObject("headers");
        if (input != null) for (Iterator<String> keys = input.keys(); keys.hasNext();) {
            String name = keys.next(), lower = name.toLowerCase(Locale.ROOT), value = input.optString(name);
            if (!HEADERS.contains(lower) || value.length() > 16_384 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IOException("App请求头无效");
            headers.put(lower, value);
        }
        String url=params.getString("url");
        try { return execute(url, method, headers, body.getBytes(StandardCharsets.UTF_8), false, false, sourceDeadline(params)); }
        catch(Exception error) {
            String code=transientCode(error);
            if(code==null)throw error;
            // Classification only: the source owner may create ONE new signed lookup.
            // Never replay an arbitrary POST or reuse an old signature in this transport.
            return new JSONObject().put("status",0).put("body","").put("url",url).put("headers",new JSONObject()).put("errorCode",code);
        }
    }
    /** JS caller budget is an upper bound, never a way to extend the native timeout. */
    static long sourceDeadline(JSONObject params) throws IOException {
        long budget = params.has("timeoutMs") ? Math.min(12_000, positiveInteger(params.opt("timeoutMs"))) : 12_000;
        if (params.has("deadlineMs")) budget = Math.min(budget, positiveInteger(params.opt("deadlineMs")) - System.currentTimeMillis());
        if (budget <= 0) throw new SocketTimeoutException("App请求超时");
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
    }
    private static long positiveInteger(Object value) throws IOException {
        if (!(value instanceof Number)) throw new IOException("App请求时限无效");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number <= 0 || number > 9_007_199_254_740_991d || number != Math.rint(number)) throw new IOException("App请求时限无效");
        return ((Number) value).longValue();
    }
    static String transientCode(Exception error) {
        if(Thread.currentThread().isInterrupted())return null;
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=error;cause!=null&&seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof InterruptedException || cause instanceof InterruptedIOException && !(cause instanceof SocketTimeoutException) || cause instanceof SSLException)return null;
            if(cause instanceof UnknownHostException)return "ENOTFOUND";
            if(cause instanceof SocketTimeoutException)return "ETIMEDOUT";
            if(cause instanceof ConnectException)return "ECONNREFUSED";
            if(cause instanceof SocketException || cause instanceof EOFException)return "ECONNRESET";
        }
        return null;
    }
    static String media(String value, String ua, String referer) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>(); headers.put("user-agent", ua); headers.put("referer", referer);
        JSONObject reply = execute(value, "HEAD", headers, "", true);
        int status = reply.getInt("status");
        // Signed media CDNs may reject HEAD although the same GET is valid.
        // Probe one byte with the real GET method instead of treating HEAD as access denial.
        if (status == 403 || status == 404 || status == 405 || status == 501) {
            headers.put("range", "bytes=0-0");
            reply = execute(value, "GET", headers, "", true);
            status = reply.getInt("status");
        }
        if (status < 200 || status >= 400) throw new IOException("App片源响应 HTTP " + status);
        return reply.getString("url");
    }
    /** Internal binary registration/search only; never exposed as a general bridge body format. */
    static JSONObject searchRequest(String value, String method, Map<String, String> headers, byte[] body, long deadline) throws Exception {
        if (body == null || body.length > 128 * 1024 || method.equals("GET") && body.length != 0) throw new IOException("搜索请求过长或格式无效");
        remaining(deadline,12000);
        for (Map.Entry<String,String> entry : headers.entrySet()) {
            String name=entry.getKey().toLowerCase(Locale.ROOT), text=entry.getValue();
            if(!HEADERS.contains(name)||text==null||text.length()>16384||text.indexOf('\r')>=0||text.indexOf('\n')>=0)throw new IOException("搜索请求头无效");
        }
        return execute(value, method, headers, body, false, true, deadline);
    }
    /** The upstream registration endpoint, host-locked; binary body, text reply. */
    static String upstreamRegisterPost(String url, Map<String, String> headers, byte[] body, long deadline) throws Exception {
        URI uri = NetworkGuard.validate(url, false);
        if (!"log5-applog-lf.fqnovel.com".equals(uri.getHost()) || !"/service/2/device_register/".equals(uri.getRawPath())) throw new IOException("上游登记地址无效");
        JSONObject reply = execute(url, "POST", headers, body, false, false, deadline);
        return reply.optString("body", "");
    }
    /** Signed account call on the 72232 host; returns the raw body text for the caller to parse. */
    static String accountCall(String url, String method, Map<String, String> headers, String body, long deadline) throws Exception {
        URI uri = NetworkGuard.validate(url, false);
        if (!"api5-normal-sinfonlinea.fqnovel.com".equals(uri.getHost()) || uri.getRawPath() == null || !uri.getRawPath().matches("/reading/[A-Za-z0-9/_-]{1,240}") || uri.getRawPath().contains("//")) throw new IOException("账号云端地址无效");
        JSONObject reply = execute(url, method, headers, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), false, false, deadline);
        return reply.optString("body", "");
    }
    /** Signed account GET on the 72232 host; returns the raw body text for the caller to parse. */
    static String accountTextRequest(String url, Map<String, String> headers, long deadline) throws Exception {
        remaining(deadline, 12000);
        for (Map.Entry<String,String> entry : headers.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT), text = entry.getValue();
            if (!HEADERS.contains(name) || text == null || text.length() > 16384 || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) throw new IOException("账号请求头无效");
        }
        JSONObject reply = execute(url, "GET", headers, new byte[0], false, false, deadline);
        return reply.optString("body", "");
    }
    static boolean searchEndpoint(URI uri, String method) {
        return method.equals("POST") && "log.snssdk.com".equals(uri.getHost()) && "/service/2/device_register/".equals(uri.getRawPath())
            || method.equals("GET") && "api5-normal-sinfonlineb.fqnovel.com".equals(uri.getHost()) && "/reading/bookapi/search/tab/v".equals(uri.getRawPath());
    }
    private static JSONObject execute(String value, String method, Map<String, String> requestHeaders, String body, boolean media) throws Exception {
        return execute(value, method, requestHeaders, body.getBytes(StandardCharsets.UTF_8), media, false, System.nanoTime() + 20_000_000_000L);
    }
    private static JSONObject execute(String value, String method, Map<String, String> requestHeaders, byte[] payload, boolean media, boolean search, long deadline) throws Exception {
        URI uri = NetworkGuard.validate(value, false);
        if (search && !searchEndpoint(uri, method)) throw new IOException("搜索服务地址无效");
        for (int hop = 0; hop <= 3; hop++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("App请求已取消");
            if (!domain(uri.getHost(), media ? new String[]{"qznovelvod.com", "fqnovelvod.com", "douyinvod.com"} : new String[]{"fqnovel.com", "snssdk.com"})) throw new IOException("App服务地址无效");
            InetAddress[] addresses = searchAddresses(uri,deadline); Socket raw = null; IOException last = null;
            for (int index = 0; index < Math.min(addresses.length, 8); index++) {
                remaining(deadline, 7000);
                Socket candidate = new Socket(); boolean connected = false;
                try (SocketGuard ignored = guardSocket(candidate, deadline)) {
                    candidate.connect(new InetSocketAddress(addresses[index], 443), remaining(deadline, 7000));
                    remaining(deadline, 7000); raw = candidate; connected = true; break;
                } catch (IOException error) {
                    remaining(deadline, 7000);
                    if (error instanceof InterruptedIOException && !(error instanceof SocketTimeoutException)) throw error;
                    last = error;
                } finally { if (!connected) candidate.close(); }
            }
            if (raw == null) throw new IOException("App服务连接未成功", last);
            try (SocketGuard ignored = guardSocket(raw, deadline)) {
                raw.setSoTimeout(remaining(deadline, 12_000));
                try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, uri.getHost(), 443, true)) {
                    SSLParameters ssl = socket.getSSLParameters(); ssl.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(ssl); socket.startHandshake();
                    String path = uri.getRawPath(); if (path == null || path.isEmpty()) path = "/"; if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                    StringBuilder request = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + uri.getHost() + "\r\nAccept-Encoding: identity\r\nConnection: close\r\n");
                    for (Map.Entry<String, String> header : requestHeaders.entrySet()) request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
                    if (method.equals("POST")) request.append("Content-Length: ").append(payload.length).append("\r\n");
                    request.append("\r\n"); OutputStream output = socket.getOutputStream(); output.write(request.toString().getBytes(StandardCharsets.UTF_8)); if (method.equals("POST")) output.write(payload); output.flush();
                    InputStream input = new SearchDeadlineInput(new BufferedInputStream(socket.getInputStream()), socket, deadline);
                    String line = line(input, 8192);
                    if (!line.matches("HTTP/1\\.[01] [0-9]{3}.*")) throw new IOException("App响应无效");
                    int status = Integer.parseInt(line.substring(9, 12)); Map<String, String> headers = new HashMap<>(); int size = line.length();
                    for (;;) { String row = line(input, 16_384); size += row.length(); if (size > 65_536) throw new IOException("App响应头过长"); if (row.isEmpty()) break;
                        int colon = row.indexOf(':'); if (colon <= 0) throw new IOException("App响应头无效"); String name = row.substring(0, colon).toLowerCase(Locale.ROOT);
                        if (headers.containsKey(name) && Arrays.asList("content-length", "transfer-encoding", "content-encoding", "location").contains(name)) throw new IOException("App响应头重复");
                        headers.put(name, row.substring(colon + 1).trim());
                    }
                    if (Arrays.asList(301,302,303,307,308).contains(status)) {
                        if (search) throw new IOException("搜索服务发生跳转，请重试");
                        if (method.equals("POST")) throw new IOException("App签名请求发生跳转，已停止转发");
                        if (hop == 3 || !headers.containsKey("location")) throw new IOException("App服务跳转无效");
                        uri = NetworkGuard.validate(uri.resolve(headers.get("location")).toString(), false); continue;
                    }
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    if (media && method.equals("GET")) {
                        // Close immediately after a byte even if a CDN ignores Range; never fetch the whole video here.
                        if ((status == 200 || status == 206) && input.read() < 0) throw new EOFException("App片源未返回媒体字节");
                    } else if (!method.equals("HEAD")) NetworkGuard.streamBody(input, bytes, headers, 2 * 1024 * 1024, deadline);
                    remaining(deadline, Integer.MAX_VALUE);
                    return new JSONObject().put("status", status).put("url", uri.toASCIIString()).put("body", bytes.toString("UTF-8")).put("headers", new JSONObject());
                }
            } catch (IOException error) { remaining(deadline, Integer.MAX_VALUE); throw error; }
            finally { raw.close(); }
        }
        throw new IOException("App服务跳转过多");
    }
    private static InetAddress[] searchAddresses(URI uri,long deadline)throws Exception {
        remaining(deadline,Integer.MAX_VALUE);
        Future<InetAddress[]> future;
        try{future=SEARCH_DNS.submit(() -> NetworkGuard.publicAddresses(uri));}
        catch(RejectedExecutionException error){throw new IOException("App域名解析繁忙，请重试");}
        try{InetAddress[] result=future.get(remaining(deadline,Integer.MAX_VALUE),TimeUnit.MILLISECONDS);remaining(deadline,Integer.MAX_VALUE);return result;}
        catch(TimeoutException error){throw new SocketTimeoutException("App域名解析超时");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("App请求已取消");}
        catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException("App域名解析失败");}
        finally{future.cancel(true);}
    }
    private static int remaining(long deadline,int maximum) throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("App请求已取消");
        long nanos=deadline-System.nanoTime();
        if(nanos<=0)throw new SocketTimeoutException("App请求超时");
        return (int)Math.max(1,Math.min(TimeUnit.NANOSECONDS.toMillis(nanos),maximum));
    }
    private static final class SearchDeadlineInput extends FilterInputStream {
        private final Socket socket; private final long deadline;
        SearchDeadlineInput(InputStream input,Socket socket,long deadline){super(input);this.socket=socket;this.deadline=deadline;}
        private void check()throws IOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("App请求已取消");socket.setSoTimeout(remaining(deadline,12000));}
        @Override public int read()throws IOException{check();return in.read();}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{check();return in.read(bytes,offset,length);}
    }
    private static String line(InputStream input, int max) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() <= max) { int value = input.read(); if (value < 0) throw new EOFException("App响应中断"); if (value == '\n') return bytes.toString("US-ASCII").replaceFirst("\\r$", ""); bytes.write(value); }
        throw new IOException("App响应行过长");
    }
}
