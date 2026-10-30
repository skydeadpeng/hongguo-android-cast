package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Registered-model media decoding: spade_a content keys and base64 media URLs. Mirrors src/native/app-source.js. */
final class SpadeDecoder {
    static final class DecodeException extends IOException {
        DecodeException(String message) { super(message); }
    }
    private SpadeDecoder() { }
    private static byte[] base64(String text) throws DecodeException {
        StringBuilder padded = new StringBuilder(text);
        while (padded.length() % 4 != 0) padded.append('=');
        try { return Base64.getDecoder().decode(padded.toString()); }
        catch (IllegalArgumentException invalid) { throw new DecodeException("App 单集片源编码无效"); }
    }
    static byte[] registeredContentKey(String value) throws IOException {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > 2048 || !text.matches("[A-Za-z0-9+/_-]+={0,2}")) throw new DecodeException("spade_a encoding invalid");
        byte[] raw;
        try { raw = base64(text.replace('-', '+').replace('_', '/')); }
        catch (DecodeException invalid) { throw new DecodeException("spade_a encoding invalid"); }
        if (raw.length < 3 || raw.length > 1024) throw new DecodeException("spade_a length invalid");
        int tagLength = ((raw[0] ^ raw[1] ^ raw[2]) & 255) - 48;
        int length = raw.length - tagLength - 1;
        if (tagLength < 1 || length < 33 || length >= raw.length) throw new DecodeException("spade_a structure invalid");
        int seed = raw[raw.length - tagLength - 2] ^ raw[raw.length - tagLength - 1];
        StringBuilder tag = new StringBuilder();
        for (int i = raw.length - tagLength; i < raw.length; i++) tag.append((char) ((raw[i] ^ seed) & 255));
        String tagText = tag.toString();
        if (tagText.equals("app_v2") || tagText.equals("web_v2")) throw new DecodeException("spade_a format unsupported");
        // decoded[i] = ((previous[i&1] ^ raw[i+1]) - 21 - bitcount(i)) & 255; key = decoded[1..32].
        byte[] decoded = new byte[length];
        int[] previous = { 250, 85 };
        for (int i = 0; i < length; i++) {
            int current = raw[i + 1] & 255;
            decoded[i] = (byte) (((previous[i & 1] ^ current) - 21 - Integer.bitCount(i)) & 255);
            previous[i & 1] = current;
        }
        byte[] keyBytes = Arrays.copyOfRange(decoded, 1, 33);
        for (byte b : keyBytes) if ((b & 255) > 127) throw new DecodeException("App 视频密钥格式无效");
        String hex = new String(keyBytes, StandardCharsets.US_ASCII);
        if (!hex.matches("(?i)[0-9a-f]{32}")) throw new DecodeException("App 视频密钥格式无效");
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return key;
    }
    static String registeredMediaUrl(String value) throws IOException {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("https://")) return text;
        if (text.isEmpty() || text.length() > 32768 || !text.matches("[A-Za-z0-9+/]+={0,2}") || text.length() % 4 == 1)
            throw new DecodeException("App 单集片源编码无效");
        String decoded = new String(base64(text), StandardCharsets.UTF_8);
        if (!decoded.startsWith("https://")) throw new DecodeException("App 单集片源地址无效");
        return decoded;
    }
}
