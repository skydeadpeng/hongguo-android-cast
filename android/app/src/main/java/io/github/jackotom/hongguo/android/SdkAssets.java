package io.github.jackotom.hongguo.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Locale;

/** Fetches and verifies the two SDK inputs the route needs: the pinned SDK image and the app
 *  configuration. Both are byte ranges of the official APK and are hash-checked twice, first as the
 *  packed range and again after inflation. Nothing is trusted from the CDN beyond its bytes. */
final class SdkAssets {
    static final String APK = "https://lf9-apk.ugapk.cn/package/apk/novelread/12267_74532/novelread_69321526a_v12267_74532_46e0_1794485793.apk?v=1794485793";
    static final long APK_SIZE = 156400488L;
    static final String IMAGE = "libmetasec_ml.so", CONFIG = "sdk-app-config.encoded";
    static final int CONFIG_OFFSET = 1918408;
    /** name, range start, packed size, packed SHA-256, inflated size, final SHA-256. */
    private static final String[][] PARTS = {
        {IMAGE, "115882007", "1497823", "3839797f0a1e0ef6c604d639d0054a527b99ed1fa6aa9ecd2e80a9d87c8d133f", "4501136",
            "c6831ab265a0cb76dacfa3b7041e9ea0f706490106a74e12f39058671cc14fe3"},
        {CONFIG, "68002997", "3914490", "e7c9cc2356ed194f4971c9f45687425d8dd7a2c445266a5a6f4fc3947417872d", "10367780",
            "584a01183f1e6783326bff49d4f1319f33fcce678afb93ca0dc8b60a56b65855"}};
    private static final String[] CARRIERS = {"ugapk.cn", "jspcdn.cn"};
    /** Ranged requests in a row that may each end in a cut, a stall or a server error before the range is
     *  given up for now. The bytes that arrived stay on disk for the next call. */
    private static final int ATTEMPTS = 4;

    /** One ranged GET. Redirects are handed back instead of followed so the target can be checked first. */
    interface Fetcher { Reply fetch(String url, long start, long end, long deadlineNanos) throws Exception; }

    /** The status line and headers of one ranged GET, with the body still open for the caller to drain. */
    static final class Reply {
        final int status; final String location, contentRange, contentEncoding; final InputStream body;
        Reply(int status, String location, String contentRange, String contentEncoding, InputStream body) {
            this.status = status; this.location = location; this.contentRange = contentRange; this.contentEncoding = contentEncoding; this.body = body;
        }
        void close() { try { if (body != null) body.close(); } catch (IOException ignored) { } }
    }

    private SdkAssets() { }

    /** Returns {image, configuration}: verified bytes from the cache, downloading whatever is missing. */
    static byte[][] ensure(File directory, Fetcher fetcher, long deadlineNanos) throws Exception {
        return ensure(directory, PARTS, APK, APK_SIZE, CONFIG_OFFSET, fetcher, deadlineNanos);
    }

    static byte[][] ensure(File directory, String[][] parts, String apk, long apkSize, int configOffset, Fetcher fetcher, long deadlineNanos) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("SDK 素材缓存目录不可用");
        byte[][] out = new byte[parts.length][];
        for (int index = 0; index < parts.length; index++) {
            String[] part = parts[index];
            File file = new File(directory, part[0]);
            byte[] wanted = DanmakuSigner.unhex(part[5]);
            byte[] cached = read(file, stored(part));
            if (cached != null && MessageDigest.isEqual(digest(cached), wanted)) { out[index] = cached; continue; }
            byte[] data = download(directory, part, apk, apkSize, configOffset, fetcher, deadlineNanos);
            store(file, data);
            new File(directory, part[0] + ".part").delete();
            out[index] = data;
        }
        return out;
    }

    /** A reply the CDN must not be trusted on: whatever arrived with it is discarded too. */
    private static final class Rejected extends IOException { Rejected(String message) { super(message); } }

    /** Downloads one packed range. What already arrived, in this call or an earlier one, stays in
     *  `<name>.part` and the next request continues after it, so a cut, a stall or a link too slow for a
     *  single attempt only costs the bytes that did not arrive. */
    private static byte[] download(File directory, String[] part, String apk, long apkSize, int configOffset, Fetcher fetcher, long deadlineNanos) throws Exception {
        long start = Long.parseLong(part[1]), size = Long.parseLong(part[2]), end = start + size - 1;
        byte[] packedDigest = DanmakuSigner.unhex(part[3]), finalDigest = DanmakuSigner.unhex(part[5]);
        int inflated = Integer.parseInt(part[4]);
        File partial = new File(directory, part[0] + ".part");
        long have = partial.length();
        String url = apk;
        java.util.Set<String> visited = new java.util.HashSet<>();
        visited.add(url);
        try {
            if (have > size) throw new Rejected("SDK 素材缓存长度不符");
            for (int attempt = 0; have < size; attempt++) {
                if (attempt >= ATTEMPTS) throw new java.io.InterruptedIOException("SDK 素材下载多次中断");
                if (deadlineNanos - System.nanoTime() <= 0) throw new SocketTimeoutException("SDK 素材下载超时");
                Reply reply;
                try { reply = fetcher.fetch(url, start + have, end, deadlineNanos); }
                catch (IOException interrupted) { continue; }
                boolean routed = false;
                switch (reply.status) {
                    case 301: case 302: case 303: case 307: case 308:
                        // A redirect is CDN routing, never permission to retry a failed read.
                        try {
                            if (attempt >= ATTEMPTS - 1) throw new Rejected("SDK 素材跳转次数过多");
                            url = redirect(url, reply.location, visited);
                            routed = true;
                        } finally { reply.close(); }
                        break;
                    default: break;
                }
                if (routed) continue;
                try {
                    check(reply, start + have, end, apkSize);
                    // Only the transfer can fail from here, and that resumes from the bytes already on disk.
                    append(partial, reply.body, size - have, deadlineNanos);
                } catch (Rejected rejected) { throw rejected; }
                catch (IOException interrupted) { if (deadlineNanos - System.nanoTime() <= 0) throw interrupted; }
                finally { reply.close(); }
                have = partial.length();
            }
            byte[] packed = read(partial, (int) size);
            if (packed == null || !MessageDigest.isEqual(digest(packed), packedDigest)) throw new Rejected("SDK 素材摘要不符");
            byte[] data = SdkWire.inflate(packed, 0, packed.length, inflated, true);
            if (part[0].equals(CONFIG)) data = SdkWire.copy(data, configOffset, configOffset + SdkMaterials.CONFIG_SIZE);
            if (!MessageDigest.isEqual(digest(data), finalDigest)) throw new Rejected("SDK 素材解压后摘要不符");
            return data;
        } catch (Rejected rejected) {
            partial.delete();
            throw rejected;
        }
    }

    /** Size of a verified input as stored: the whole inflated image, or the configuration's slice of its range. */
    private static int stored(String[] part) { return part[0].equals(CONFIG) ? SdkMaterials.CONFIG_SIZE : Integer.parseInt(part[4]); }

    private static void check(Reply reply, long from, long end, long apkSize) throws IOException {
        if (reply.status >= 500) throw new IOException("SDK 素材下载 HTTP " + reply.status);
        if (reply.status != 206) throw new Rejected("SDK 素材下载 HTTP " + reply.status);
        if (!("bytes " + from + "-" + end + "/" + apkSize).equals(reply.contentRange)) throw new Rejected("SDK 素材分段不符");
        String encoding = reply.contentEncoding == null || reply.contentEncoding.isEmpty() ? "identity" : reply.contentEncoding.toLowerCase(Locale.ROOT);
        if (!encoding.equals("identity")) throw new Rejected("SDK 素材传输编码不符");
    }

    /** Appends at most `expected` bytes to the partial file. A cut or a stall throws only after what did
     *  arrive is on disk, so the next request continues right after it. */
    private static void append(File partial, InputStream in, long expected, long deadlineNanos) throws Exception {
        try (FileOutputStream out = new FileOutputStream(partial, true)) {
            byte[] buffer = new byte[8192];
            for (int count; expected > 0; expected -= count) {
                count = in.read(buffer, 0, (int) Math.min(buffer.length, expected));
                if (count < 0) throw new IOException("SDK 素材下载中断");
                if (count > 0) out.write(buffer, 0, count);
                if (System.nanoTime() >= deadlineNanos) throw new SocketTimeoutException("SDK 素材下载超时");
            }
            if (in.read() != -1) throw new Rejected("SDK 素材分段长度不符");
        }
    }

    /** A CDN redirect may only stay on HTTPS, on the two official carrier domains, and may not loop. */
    static String redirect(String current, String location, java.util.Set<String> visited) throws IOException {
        if (location == null || location.isEmpty() || location.length() > 8192) throw new IOException("SDK 素材跳转无效");
        for (int index = 0; index < location.length(); index++) {
            char value = location.charAt(index);
            if (value < 33 || value == 127) throw new IOException("SDK 素材跳转无效");
        }
        URI target;
        try { target = new URI(current).resolve(new URI(location)); }
        catch (Exception invalid) { throw new IOException("SDK 素材跳转无效"); }
        if (!carrier(target) || !visited.add(target.toString())) throw new IOException("SDK 素材跳转目标无效");
        return target.toString();
    }

    /** True for an HTTPS URL on an official carrier domain, on its default or alternate port, without user info or fragment. */
    static boolean carrier(URI target) {
        String host = target.getHost();
        int port = target.getPort();
        boolean known = false;
        if (host != null) for (String domain : CARRIERS) known |= host.equals(domain) || host.endsWith("." + domain);
        return "https".equals(target.getScheme()) && target.getUserInfo() == null && target.getFragment() == null && known
            && (port == -1 || port == 443 || port == 20443);
    }

    private static byte[] digest(byte[] data) throws Exception { return MessageDigest.getInstance("SHA-256").digest(data); }

    /** A file is read only when it has exactly the expected size; its hash is checked by the caller. */
    private static byte[] read(File file, int expected) throws IOException {
        if (!file.isFile() || file.length() != expected) return null;
        byte[] out = new byte[expected];
        try (InputStream in = new java.io.FileInputStream(file)) {
            int at = 0;
            while (at < expected) {
                int count = in.read(out, at, expected - at);
                if (count < 0) break;
                at += count;
            }
            return at == expected ? out : null;
        }
    }

    private static void store(File file, byte[] data) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!temporary.renameTo(file) && !(file.delete() && temporary.renameTo(file))) throw new IOException("SDK 素材缓存写入失败");
    }
}
