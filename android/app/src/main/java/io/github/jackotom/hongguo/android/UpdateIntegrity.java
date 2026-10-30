package io.github.jackotom.hongguo.android;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Streaming primitives shared by network download and re-verification; no whole-APK buffering. */
final class UpdateIntegrity {
    interface Guard { void check() throws IOException; }
    interface Progress { void accept(long bytes); }
    static long copy(InputStream input, OutputStream output, long expected, Guard guard, Progress progress) throws IOException {
        if (expected <= 0 || expected > UpdatePolicy.MAX_APK_BYTES) throw new IOException("更新包大小无效");
        byte[] buffer = new byte[32768]; long total = 0;
        for (;;) {
            guard.check(); int n = input.read(buffer); guard.check(); if (n < 0) break;
            if (n == 0) continue;
            total += n; if (total > expected) throw new IOException("更新包超过声明大小");
            output.write(buffer, 0, n); progress.accept(total);
        }
        if (total != expected) throw new IOException("更新包未下载完整");
        return total;
    }
    static String hash(File file, Guard guard) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            for (;;) { guard.check(); int n = input.read(buffer); guard.check(); if (n < 0) break; if (n != 0) digest.update(buffer, 0, n); }
        }
        StringBuilder result = new StringBuilder(64); for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255)); return result.toString();
    }
}
