package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 黄果 AES-128 HLS 兼容下载器：拉清单、逐分片下载解密并拼接为可直接播放的 MPEG-TS。
 *  用于系统播放器解不了加密 HLS 的设备（部分华为/荣耀）：先落盘再本地播放。 */
final class HuangguoHlsCache {
    private static final Pattern KEY_LINE = Pattern.compile("#EXT-X-KEY:METHOD=AES-128,URI=\"([^\"]+)\"(?:,IV=0x([0-9a-fA-F]+))?");
    private static final long MAX_BYTES = 512L * 1024 * 1024;

    private HuangguoHlsCache() { }

    static boolean deviceNeedsCompat() {
        String maker = android.os.Build.MANUFACTURER;
        return maker != null && (maker.equalsIgnoreCase("HUAWEI") || maker.equalsIgnoreCase("HONOR"));
    }

    /** 下载整集为本地 .ts；返回文件。分片与密钥地址均来自清单（绝对地址）。 */
    static File download(String playlistUrl, File destination) throws Exception {
        NetworkGuard.Reply reply = NetworkGuard.publicGet(playlistUrl, System.nanoTime() + 15_000_000_000L);
        if (reply.status != 200 || !reply.body.startsWith("#EXTM3U")) throw new IOException("黄果播放清单不可用");
        if (reply.body.contains("#EXT-X-STREAM-INF")) throw new IOException("黄果多码率清单暂不支持缓存播放");

        Matcher keyLine = KEY_LINE.matcher(reply.body);
        String keyUrl = null; byte[] explicitIv = null;
        if (keyLine.find()) {
            keyUrl = keyLine.group(1);
            if (keyLine.group(2) != null) {
                explicitIv = new byte[16];
                for (int i = 0; i < 16; i++) explicitIv[i] = (byte) Integer.parseInt(keyLine.group(2).substring(i * 2, i * 2 + 2), 16);
            }
        }
        if (keyUrl == null) throw new IOException("黄果清单缺少解密信息");

        List<String> segments = new ArrayList<>();
        URI base = new URI(playlistUrl);
        String baseDir = base.getPath().substring(0, base.getPath().lastIndexOf('/') + 1);
        for (String line : reply.body.split("\n")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            URI segment = value.startsWith("http") ? new URI(value)
                : value.startsWith("/") ? new URI(base.getScheme(), base.getHost(), value, null)
                : new URI(base.getScheme(), base.getHost(), baseDir + value, null);
            segments.add(segment.toASCIIString());
        }
        if (segments.isEmpty()) throw new IOException("黄果清单没有可播放分片");

        byte[] key = fetchKey(keyUrl);
        File partial = new File(destination.getParentFile(), destination.getName() + ".part");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(partial))) {
            long total = 0;
            byte[] iv = explicitIv;
            for (int index = 0; index < segments.size(); index++) {
                byte[] encrypted = fetchBytes(segments.get(index));
                total += encrypted.length;
                if (total > MAX_BYTES) throw new IOException("黄果分集超出缓存大小上限");
                // 未显式给 IV 时按 HLS 规范使用分片序号（大端 128 位）。
                if (iv == null) {
                    iv = new byte[16];
                    long sequence = index;
                    for (int i = 15; i >= 0; i--) { iv[i] = (byte) (sequence & 0xff); sequence >>= 8; }
                }
                out.write(decrypt(encrypted, key, iv));
                iv = null;
            }
        } catch (Exception error) {
            partial.delete();
            throw error;
        }
        if (!partial.renameTo(destination)) { partial.delete(); throw new IOException("黄果缓存写入未完成"); }
        return destination;
    }

    private static byte[] fetchKey(String url) throws Exception {
        // 密钥地址在分片 CDN 上，同样经 NetworkGuard 的公网校验与 DNS 竞速。
        URI uri = new URI(url);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(uri.getHost(), 443), 8000);
        try (OutputStream out = raw.getOutputStream(); InputStream in = raw.getInputStream()) {
            out.write(("GET " + (uri.getRawQuery() != null ? uri.getRawPath() + "?" + uri.getRawQuery() : uri.getRawPath())
                + " HTTP/1.1\r\nHost: " + uri.getHost() + "\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int read; (read = in.read(chunk)) >= 0; ) buffer.write(chunk, 0, read);
            byte[] response = buffer.toByteArray();
            int split = indexOf(response, "\r\n\r\n");
            if (split < 0) throw new IOException("黄果密钥响应无效");
            String head = new String(response, 0, split, StandardCharsets.UTF_8);
            if (!head.contains(" 200 ")) throw new IOException("黄果密钥获取失败");
            byte[] body = Arrays.copyOfRange(response, split + 4, response.length);
            if (body.length != 16) throw new IOException("黄果密钥长度无效");
            return body;
        }
    }

    private static byte[] fetchBytes(String url) throws Exception {
        File temp = File.createTempFile("hgseg", ".ts", null);
        try {
            NetworkGuard.download(url, temp, 64L * 1024 * 1024);
            return java.nio.file.Files.readAllBytes(temp.toPath());
        } finally { temp.delete(); }
    }

    private static byte[] decrypt(byte[] encrypted, byte[] key, byte[] iv) throws Exception {
        if (encrypted.length == 0 || encrypted.length % 16 != 0) return encrypted;
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] plain = cipher.doFinal(encrypted);
        // PKCS7 去填充仅出现在最后一个分片末尾，TS 流容忍多余填充字节。
        return plain;
    }

    private static int indexOf(byte[] data, String marker) {
        byte[] target = marker.getBytes(StandardCharsets.UTF_8);
        outer: for (int i = 0; i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) if (data[i + j] != target[j]) continue outer;
            return i;
        }
        return -1;
    }
}
