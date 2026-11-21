package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.net.InetAddress;

/** Offline Java tests: no network traffic and no Android/JUnit dependencies. */
public final class NetworkGuardTest {
    private static int checks;
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void rejected(String value, boolean official) { boolean failed = false; try { NetworkGuard.validate(value, official); } catch (Exception expected) { failed = true; } check(failed, "must reject " + value); }
    public static void main(String[] args) throws Exception {
        for (String path : new String[]{"category/real-drama", "search?keyword=test", "player/123456/123456"}) check(NetworkGuard.validate("https://hongguoduanju.com/" + path, true) != null, path);
        for (String bad : new String[]{"http://hongguoduanju.com/search", "https://hongguoduanju.com.evil.test/search", "https://evil.test/search", "https://hongguoduanju.com/detail", "https://hongguoduanju.com/category/../search", "https://hongguoduanju.com/category/%2e%2e/search", "https://user:secret@hongguoduanju.com/search", "https://hongguoduanju.com:444/search", "https://hongguoduanju.com/search#script", "https://hongguoduanju.com/search\r\nBad: x"}) rejected(bad, true);
        for (String bad : new String[]{"https://127.0.0.1/movie.mp4", "https://[::1]/movie.mp4", "https://media.local/movie.mp4", "https://media.internal/movie.mp4", "file:///tmp/movie.mp4", "content://downloads/1", "https://name:secret@cdn.example.com/movie.mp4"}) rejected(bad, false);
        check(NetworkGuard.validate("https://cdn.example.com/movie.mp4?token=public", false) != null, "public domain syntax");
        for (String ip : new String[]{"0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.0.1", "172.16.0.1", "192.168.1.1", "192.0.2.1", "198.18.0.1", "198.19.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "::", "::1", "fc00::1", "fe80::1", "2001:db8::1", "2001::1", "2002::1"}) check(!NetworkGuard.publicAddress(InetAddress.getByName(ip)), "private/special " + ip);
        for (String ip : new String[]{"1.1.1.1", "8.8.8.8", "172.32.1.1", "100.128.0.1", "2606:4700:4700::1111"}) check(NetworkGuard.publicAddress(InetAddress.getByName(ip)), "public " + ip);
        // 来源模块 DoH 优先解析（污染域）：命中注册域先走可信 DoH，系统解析只在双端点
        // 失败后兜底；通配模式与未注册域不经过 DoH 探针；DoH 答案同样过公网校验。
        long dnsDeadline = System.nanoTime() + 25_000_000_000L;
        NetworkGuard.dohProbe = null;
        NetworkGuard.registerDohFirstHosts(java.util.List.of("doh-fixture.example", "*.poison.example", "nodot", "doh-fixture.example"));
        InetAddress[] clean = { InetAddress.getByName("104.26.1.244") };
        InetAddress[] privateAnswer = { InetAddress.getByName("10.0.0.1") };
        NetworkGuard.dohProbe = host -> { check("doh-fixture.example".equals(host), "probe receives queried host"); return clean; };
        check(NetworkGuard.sourceAddresses(java.net.URI.create("https://doh-fixture.example/x"), dnsDeadline)[0].equals(clean[0]), "DoH-first answer wins over poisoned system DNS");
        NetworkGuard.dohProbe = host -> { throw new java.io.UncheckedIOException(new java.io.IOException("DoH down")); };
        check(NetworkGuard.sourceAddresses(java.net.URI.create("https://example.com/"), dnsDeadline).length > 0, "DoH endpoint failure falls back to system resolver");
        NetworkGuard.dohProbe = host -> privateAnswer;
        check(NetworkGuard.sourceAddresses(java.net.URI.create("https://example.com/"), dnsDeadline).length > 0, "private DoH answer rejected, system fallback");
        boolean[] probed = {false};
        NetworkGuard.dohProbe = host -> { probed[0] = true; return clean; };
        // 未注册域与通配模式直接走系统解析（此处无需关心解析成败，只验证不碰 DoH 探针）。
        try { NetworkGuard.sourceAddresses(java.net.URI.create("https://example.org/"), dnsDeadline); } catch (IOException ignored) { }
        try { NetworkGuard.sourceAddresses(java.net.URI.create("https://a.poison.example/"), dnsDeadline); } catch (IOException ignored) { }
        check(!probed[0], "unregistered and wildcard-pattern hosts bypass DoH-first");
        NetworkGuard.dohProbe = null;
        System.out.println("PASS NetworkGuardTest checks=" + checks + " networkRequests=dns-fallback");
    }
}
