package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.SSLHandshakeException;

/** Compiles the actual transport with DNS/socket test doubles. No actual DNS,
 * sockets, TLS, HTTP, account registration, or media requests are performed. */
public final class OfficialTransportTimeoutTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static IOException execute(String mode, long budgetMs) throws Exception {
        NetworkGuard.mode = mode;
        OfficialTransport transport = new OfficialTransport();
        OfficialApiClient.Request request = new OfficialApiClient.Request(
            "https://log.snssdk.com/service/2/device_register/", "POST", Collections.emptyMap(),
            new byte[0], System.nanoTime() + budgetMs * 1000000L);
        try { transport.execute(request); throw new AssertionError("unexpected success"); }
        catch (IOException error) { return error; }
        finally { transport.close(); }
    }
    public static void main(String[] args) throws Exception {
        Socket.setSocketImplFactory(FakeSocketImpl::new);
        check(execute("immediate_timeout", 1000) instanceof SocketTimeoutException, "direct timeout retained");
        check(execute("late_socket", 25) instanceof SocketTimeoutException,
            "socket failure observed after deadline must remain a timeout");
        check(execute("connect_timeout", 1000) instanceof SocketTimeoutException,
            "last address connect timeout must not be erased by an IOException wrapper");
        check(FakeSocketImpl.connects == 1, "exactly one synthetic connect attempt");
        for (String mode : new String[]{"early_socket", "late_io", "late_tls", "unknown_host", "late_unknown_host"}) {
            IOException error = execute(mode, mode.startsWith("late_") ? 25 : 1000);
            check(!(error instanceof SocketTimeoutException), mode + " is not promoted to retryable timeout");
            check(error.getCause() == null && !error.getMessage().contains("synthetic-private"),
                "transport failure remains sanitized");
        }
        int prior = NetworkGuard.lookups;
        check(execute("immediate_timeout", -1) instanceof SocketTimeoutException, "expired entry is typed timeout");
        check(NetworkGuard.lookups == prior, "pre-expired request starts no DNS");
        Thread.currentThread().interrupt();
        try {
            IOException canceled = execute("immediate_timeout", -1);
            check(canceled instanceof InterruptedIOException && !(canceled instanceof SocketTimeoutException),
                "explicit cancellation wins over simultaneous deadline");
            check(Thread.currentThread().isInterrupted() && NetworkGuard.lookups == prior,
                "pre-cancel preserves interrupt and starts no DNS");
        } finally { Thread.interrupted(); }
        try {
            IOException canceled = execute("late_cancel", 25);
            check(canceled instanceof InterruptedIOException && !(canceled instanceof SocketTimeoutException),
                "in-flight cancellation wins over late socket failure");
            check(Thread.currentThread().isInterrupted(), "in-flight interrupt retained");
        } finally { Thread.interrupted(); }
        OfficialTransport closed = new OfficialTransport(); closed.close(); prior = NetworkGuard.lookups;
        try {
            closed.execute(new OfficialApiClient.Request("https://log.snssdk.com/service/2/device_register/",
                "POST", Collections.emptyMap(), new byte[0], System.nanoTime() - 1));
            throw new AssertionError("closed request started");
        } catch (InterruptedIOException expected) {
            check(!(expected instanceof SocketTimeoutException), "close is cancellation, not deadline");
            check(NetworkGuard.lookups == prior, "closed operation starts no DNS");
        }
        check(FakeSocketImpl.connects == 1, "all other cases finish before connect");
        System.out.println("OFFICIAL_TRANSPORT_TIMEOUT_OK checks=" + checks + " actualTransport=true networkRequests=0");
    }
}
// Test-only collaborators. Unexpected HTTP/TLS work is a hard failure.
class OfficialApiClient {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    interface Transport extends AutoCloseable { Response execute(Request r) throws Exception; default void close() {} }
    static class Request {
        final String url, method; final Map<String,String> headers; final byte[] body; final long deadlineNanos;
        Request(String u, String m, Map<String,String> h, byte[] b, long d) { url=u;method=m;headers=h;body=b;deadlineNanos=d; }
    }
    static class Response { Response(int c, Map<String,List<String>> h, byte[] b) {} }
}
class NetworkGuard {
    static String mode; static int lookups;
    static InetAddress[] publicAddresses(URI uri, long deadline) throws IOException {
        lookups++;
        if (mode.startsWith("late_")) {
            while (System.nanoTime() < deadline + 10000000L) {
                try { Thread.sleep(1); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            }
        }
        switch (mode) {
            case "immediate_timeout": throw new SocketTimeoutException("synthetic-private timeout");
            case "early_socket": case "late_socket": throw new SocketException("synthetic-private socket closed");
            case "late_cancel": Thread.currentThread().interrupt(); throw new SocketException("synthetic-private cancel");
            case "late_io": throw new IOException("synthetic-private protocol failure");
            case "late_tls": throw new SSLHandshakeException("synthetic-private certificate failure");
            case "unknown_host": case "late_unknown_host": throw new UnknownHostException("synthetic-private host");
            case "connect_timeout": return new InetAddress[]{InetAddress.getByAddress(new byte[]{8,8,8,8})};
            default: throw new AssertionError("unconfigured DNS double");
        }
    }
    static void streamBody(InputStream i, OutputStream o, Map<String,String> h, int m, long d) {
        throw new AssertionError("no HTTP permitted in this test");
    }
}
class AppTransport {
    static SocketGuard guardSocket(Socket s, long d) { return new SocketGuard(); }
    static class SocketGuard implements AutoCloseable { public void close() {} }
}
class FakeSocketImpl extends SocketImpl {
    static int connects;
    protected void create(boolean stream) {}
    protected void connect(SocketAddress address, int timeout) throws IOException {
        connects++; throw new SocketTimeoutException("synthetic-private connect timeout");
    }
    protected void connect(String h, int p) { throw new AssertionError("no network"); }
    protected void connect(InetAddress a, int p) { throw new AssertionError("no network"); }
    protected void bind(InetAddress h, int p) { throw new AssertionError("no bind"); }
    protected void listen(int b) { throw new AssertionError("no listen"); }
    protected void accept(SocketImpl s) { throw new AssertionError("no accept"); }
    protected InputStream getInputStream() { throw new AssertionError("no TLS/HTTP"); }
    protected OutputStream getOutputStream() { throw new AssertionError("no TLS/HTTP"); }
    protected int available() { return 0; }
    protected void close() {}
    protected void sendUrgentData(int d) { throw new AssertionError("no network"); }
    public void setOption(int k, Object v) {}
    public Object getOption(int k) { return null; }
}
