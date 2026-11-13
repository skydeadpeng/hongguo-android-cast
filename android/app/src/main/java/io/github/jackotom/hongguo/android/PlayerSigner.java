package io.github.jackotom.hongguo.android;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Playback-only 551 Argus/Ladon signing, the Java twin of src/native/playback-signature.js
 * (protocol provenance and golden vectors live there; both must stay byte-identical).
 * The server rejects the old timestamp-placeholder ladon/argus with empty 200s.
 * Public protocol constants only; no account credentials.
 */
final class PlayerSigner {
    private static final long LICENSE_ID = 1611921764L;
    private static final byte[] SIGN_KEY = hex("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304");
    private static final byte[] AES_KEY = hex("d31e3718288a1027baab59f146a09a9c");
    private static final byte[] AES_IV = hex("ea180a0336ed352fcd24e4d50018ae54");
    private static final byte[] MAGIC8 = hex("f2f7fcfff2f7fcff");
    private static final byte[] TAIL9 = hex("eafb2cfe8568519154");
    private static final SecureRandom RANDOM = new SecureRandom();

    private PlayerSigner() { }

    static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    /** Protobuf writer over length-prefixed fields; varints truncate to the low 32 bits. */
    static final class ProtoBuf {
        private final ByteBuffer buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        private byte[] bytes() {
            byte[] out = new byte[buffer.position()];
            buffer.flip(); buffer.get(out); return out;
        }
        private ProtoBuf put(byte[] value) { if (buffer.remaining() < value.length) { buffer.flip(); ByteBuffer grown = ByteBuffer.allocate(Math.max(buffer.capacity() * 2, buffer.capacity() + value.length)).order(ByteOrder.LITTLE_ENDIAN); grown.put(buffer); buffer.clear(); } buffer.put(value); return this; }
        private void varint(long value) {
            long n = value & 0xFFFFFFFFL;
            while ((n & ~0x7FL) != 0) { put(new byte[] { (byte) (n | 0x80) }); n >>>= 7; }
            put(new byte[] { (byte) n });
        }
        ProtoBuf putVarint(int tag, long value) { varint(((long) tag << 3) & 0xFFFFFFFFL); varint(value); return this; }
        ProtoBuf putVarintRaw(int tag) { varint(((long) tag << 3) & 0xFFFFFFFFL); return this; }
        ProtoBuf putBytes(int tag, byte[] value) { varint(((long) tag << 3 | 2) & 0xFFFFFFFFL); varint(value.length & 0xFFFFFFFFL); return put(value); }
        ProtoBuf putUtf8(int tag, String value) { return putBytes(tag, value.getBytes(StandardCharsets.UTF_8)); }
        ProtoBuf putProtobuf(int tag, ProtoBuf value) { return putBytes(tag, value.bytes()); }
        byte[] toBuf() { return bytes(); }
    }

    static long[] simonKeyExpansion(long[] key) {
        long[] expanded = new long[72];
        for (int i = 0; i < 4; i++) expanded[i] = key[i];
        for (int i = 4; i < 72; i++) {
            long tmp = Long.rotateRight(expanded[i - 1], 3) ^ expanded[i - 3];
            tmp ^= Long.rotateRight(tmp, 1);
            long bit = (0x3dc94c3a046d678bL >>> ((i - 4) % 62)) & 1L;
            expanded[i] = ~expanded[i - 4] ^ tmp ^ bit ^ 3L;
        }
        return expanded;
    }
    static long[] simonEncrypt(long left, long right, long[] key) {
        long x = left, y = right;
        for (long roundKey : simonKeyExpansion(key)) {
            long previous = y;
            y = x ^ (Long.rotateLeft(y, 1) & Long.rotateLeft(y, 8)) ^ Long.rotateLeft(y, 2) ^ roundKey;
            x = previous;
        }
        return new long[] { x, y };
    }

    private static byte[] pad16(byte[] data) {
        int pad = 16 - data.length % 16;
        byte[] out = new byte[data.length + pad];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = data.length; i < out.length; i++) out[i] = (byte) pad;
        return out;
    }
    private static long u64At(ByteBuffer buffer, int at) { return buffer.order(ByteOrder.LITTLE_ENDIAN).getLong(at); }
    private static void u64To(ByteBuffer buffer, int at, long value) { buffer.order(ByteOrder.LITTLE_ENDIAN).putLong(at, value); }

    static String queryParam(String query, String name) {
        for (String part : query.split("&")) {
            int at = part.indexOf('=');
            String key = at < 0 ? part : part.substring(0, at);
            if (decode(key).equals(name)) return decode(at < 0 ? "" : part.substring(at + 1));
        }
        return null;
    }
    private static String decode(String value) {
        // The Charset overload requires Android API 33; the name overload is available on our minSdk.
        try { return java.net.URLDecoder.decode(value.replace("+", "%20"), StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return value; }
    }

    static byte[] bodyHash(String stubHex) {
        if (stubHex == null || stubHex.isEmpty()) return first6(DanmakuSigner.sm3(new byte[16]));
        byte[] bytes = stubHex.matches("(?i)(?:[a-f\\d]{2})+") ? hex(stubHex) : new byte[0];
        return first6(DanmakuSigner.sm3(bytes));
    }
    static byte[] queryHash(String query) {
        return query == null || query.isEmpty() ? first6(DanmakuSigner.sm3(new byte[16]))
            : first6(DanmakuSigner.sm3(query.getBytes(StandardCharsets.UTF_8)));
    }
    private static byte[] first6(byte[] digest) {
        byte[] out = new byte[6];
        System.arraycopy(digest, 0, out, 0, 6);
        return out;
    }

    static String argus(String query, String stubHex, long ts, long aid, long licenseId, String deviceId, String installId, int randField) {
        ProtoBuf nested = new ProtoBuf().putVarint(1, 1).putVarint(2, 1).putVarint(3, 1).putVarint(7, 0xc792ecccL);
        ProtoBuf deviceTag = new ProtoBuf().putUtf8(1, "NX551J").putVarint(2, 8196).putVarint(4, 0x80e0d800L);
        ProtoBuf bean = new ProtoBuf().putVarint(1, 1077940818L).putVarint(2, 2).putVarint(3, (long) randField)
            .putUtf8(4, Long.toString(aid == 0 ? 1967 : aid))
            .putUtf8(5, deviceId)
            .putUtf8(6, Long.toString(licenseId == 0 ? LICENSE_ID : licenseId))
            .putUtf8(7, orEmpty(queryParam(query, "version_name")))
            .putUtf8(8, "v04.04.05-ov-android")
            .putVarint(9, 134744640L).putBytes(10, new byte[8]).putVarint(11, 0).putVarint(12, ts << 1)
            .putBytes(13, bodyHash(stubHex)).putBytes(14, queryHash(query))
            .putProtobuf(15, nested)
            .putUtf8(16, installId).putUtf8(20, "none").putVarint(21, 738)
            .putProtobuf(23, deviceTag).putVarint(25, 2);
        byte[] protobuf = pad16(bean.toBuf());
        long[] key = new long[4];
        for (int i = 0; i < 4; i++) key[i] = u64At(ByteBuffer.wrap(SIGN_KEY), i * 8);
        byte[] framed = new byte[MAGIC8.length + protobuf.length / 16 * 16 + TAIL9.length];
        System.arraycopy(MAGIC8, 0, framed, 0, MAGIC8.length);
        ByteBuffer source = ByteBuffer.wrap(protobuf);
        // Absolute puts index from the array origin: write at `out`, never through a sub-window.
        ByteBuffer framedBuffer = ByteBuffer.wrap(framed);
        for (int at = 0, out = MAGIC8.length; at < protobuf.length; at += 16, out += 16) {
            long[] encrypted = simonEncrypt(u64At(source, at), u64At(source, at + 8), key);
            u64To(framedBuffer, out, encrypted[0]);
            u64To(framedBuffer, out + 8, encrypted[1]);
        }
        System.arraycopy(TAIL9, 0, framed, framed.length - TAIL9.length, TAIL9.length);
        for (int i = 8; i < framed.length; i++) framed[i] ^= MAGIC8[i % 8];
        byte[] reversed = new byte[framed.length + 2];
        for (int i = 0; i < framed.length; i++) reversed[i] = framed[framed.length - 1 - i];
        reversed[framed.length] = 'a'; reversed[framed.length + 1] = 'o';
        byte[] padded = pad16(reversed);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"), new IvParameterSpec(AES_IV));
            byte[] encrypted = cipher.doFinal(padded);
            byte[] out = new byte[2 + encrypted.length];
            out[0] = (byte) 0xf2; out[1] = (byte) 0x81;
            System.arraycopy(encrypted, 0, out, 2, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception failure) { throw new IllegalStateException("App 播放签名不可用", failure); }
    }

    static String ladon(long ts, long licenseId, long aid, byte[] randomBytes) {
        if (randomBytes == null || randomBytes.length != 4) throw new IllegalArgumentException("Four Ladon random bytes required");
        String aidText = Long.toString(aid);
        MessageDigest md5 = md5();
        byte[] digest = md5.digest(concat(randomBytes, aidText.getBytes(StandardCharsets.US_ASCII)));
        StringBuilder hexText = new StringBuilder();
        for (byte b : digest) hexText.append(String.format("%02x", b));
        byte[] ascii = hexText.toString().getBytes(StandardCharsets.US_ASCII);
        long[] words = new long[4];
        ByteBuffer wrapped = ByteBuffer.wrap(ascii);
        for (int i = 0; i < 4; i++) words[i] = u64At(wrapped, i * 8);
        long[] table = new long[35];
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        long b0 = words[0], b8 = words[1];
        table[0] = words[0];
        queue.add(words[2]); queue.add(words[3]);
        for (int i = 0; i < 34; i++) {
            long x8 = Long.rotateRight(b8, 8) + b0;
            x8 ^= i;
            queue.add(x8);
            x8 ^= Long.rotateLeft(b0, 3);
            table[i + 1] = x8; b0 = x8; b8 = queue.removeFirst();
        }
        byte[] input = pad16((ts + "-" + licenseId + "-" + aidText).getBytes(StandardCharsets.US_ASCII));
        byte[] out = new byte[input.length];
        ByteBuffer output = ByteBuffer.wrap(out);
        for (int at = 0; at < input.length; at += 16) {
            long a = u64At(ByteBuffer.wrap(input), at), b = u64At(ByteBuffer.wrap(input), at + 8);
            for (int i = 0; i < 34; i++) { b = table[i] ^ (a + Long.rotateRight(b, 8)); a = b ^ Long.rotateLeft(a, 3); }
            u64To(output, at, a); u64To(output, at + 8, b);
        }
        return Base64.getEncoder().encodeToString(concat(randomBytes, out));
    }

    static Map<String, String> signPlayer(String query, byte[] body, long ts, int randField, byte[] ladonRandom) {
        String aidValue = queryParam(query, "aid");
        long aid = 1967;
        if (aidValue != null && aidValue.matches("[+-]?\\d+"))
            try { aid = Long.parseLong(aidValue); } catch (NumberFormatException ignored) { }
        String stub = body != null && body.length > 0 ? hexText(md5().digest(body)) : "";
        if (randField == 0) { byte[] random = new byte[4]; RANDOM.nextBytes(random); randField = ByteBuffer.wrap(random).order(ByteOrder.LITTLE_ENDIAN).getInt(); }
        if (ladonRandom == null) { ladonRandom = new byte[4]; RANDOM.nextBytes(ladonRandom); }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-SS-Req-Ticket", Long.toString(ts * 1000));
        headers.put("X-Khronos", Long.toString(ts));
        headers.put("X-Ladon", ladon(ts, LICENSE_ID, aid, ladonRandom));
        headers.put("X-Argus", argus(query, stub, ts, aid, LICENSE_ID, orEmpty(queryParam(query, "device_id")), "", randField));
        if (!stub.isEmpty()) headers.put("X-SS-STUB", stub);
        return headers;
    }

    static Map<String, String> signPlayer(String query, byte[] body, long ts) { return signPlayer(query, body, ts, 0, null); }

    private static MessageDigest md5() {
        try { return MessageDigest.getInstance("MD5"); }
        catch (Exception failure) { throw new IllegalStateException("App 播放签名不可用", failure); }
    }
    private static String hexText(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }
    private static String orEmpty(String value) { return value == null ? "" : value; }
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
