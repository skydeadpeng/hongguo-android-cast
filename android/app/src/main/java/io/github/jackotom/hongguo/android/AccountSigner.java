package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The 72232 hongguoTV account signer: x-gorgon / x-ladon / x-argus / x-helios / x-medusa
 * over a registered device identity, byte-verified against the upstream implementation
 * with golden vectors (android/tests/account-signer-vectors.json). Used only by the
 * website session's read-only cloud lane; never for playback, search or the passport.
 */
final class AccountSigner {
    static final String HOST = "https://api5-normal-sinfonlinea.fqnovel.com";
    static final String USER_AGENT = "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)";
    private static final byte[] SBOX = base64("+n0Ia5xZs0sEXznQOEqRmQBnpiCf9U2Ccybu3xhmgzOAAxn72f6uqqmwUsYL83klTni0NqxdGieeiNu9PGPsSRXBMB/cuFbUbM3KCUPINaPvHvSW0vwOcnuUhNHqRVpiAj/TEoE0K91+5ijypUYTATsh9mE3KSoN7Yyvv51cuyR2D3XkU4nhmI2xmmVwT1RMWKtub4sjxAcRDLrPoKSO2AU9FLLadMPX577Wf95IFj6FkKFVt3dCIsmGUC4X+WQxLJvxbRxEaOPpqJOXyzJX6+Vxaq3AzMfF/WAdoi1Hp+JRaV56zgpBtpWP97mH4DoGEIq1+FvV8LyS/3wvwugbQOwb2r26mJEMsiuDQTRn+wrYdrVGBVlhI3WQhyrjUBVMrLF5667llUcEaPCGPVGLD8qO5LlO8hKCvA7V9+8oJc9bXelqVQLhM76T5/WtnT45JKji+hdX0HoNCDDWuKON/QeaxB5uImSX0h2wv0VmP2zd2yeApxHcpsVS+MC2yFwAc2B7oBkTqsk1SEvTpM2fmfMQREBUfin0Bh+iq6EvPPavhWI2IX9e3yAas7Tm/3KEj2UmlFp36kN4x0rMLBRrxuh0U/zUHM4xcAMYjJY4MonxOl/X+alpt2M3WMI7w3HLnpIBigtNiJu7T21v4P6lSd5WFgntnMEt7oF92XzRLkJbTcGmXepE/UVOG6E/0YnhfS+q26utWcuxzpooyeD2cDlK1/8w9d28VzsRjbLuALbmGlp8+d7EzS6Au7lMpZ+ECMZvQmzwJ+eLOpxR+2chdUExp8ogQyq3v9l68rX4jCwjg0+PYKAEEzcU4wHFY2ZcdIHfWL1okD3SszT0GZMyKdZJrg1L2AeerB4tC0C4crp2EHGo5FYdSP7lwkeR2ocmnR+Ia8CYviUJlzOjhRZef9xuVOn3qcjow3fQgivsAmKKkg4+sA8F8/GWeDiGNhg8JM8KtFPMYWWkx5TVFX5t73kiNRJqjlIGVXtGZFCV4gzt0xcDopuZ6xz8r9Rzafpf9ywev8jh8592gHFIqpStZPuJxmDDMrNN0uBE3V+oscdoIzTJbRJ/t+sVvqnReJOgDJKk10fjisJwqyZBmnmn2BSFj8BvVtCMEbkuPOKdzw7eA11GPs04Qw8zWtkaZWwiO/wwpojqN6K0jY5RnNZA7vn4hPSul+nKCkVnVwQvg1zVxcSCtqORmB9KrJaBbssbCQivGJVJfVTt+hYxOtq4ZvWl8f4QAQZ0zGPffCgl9s6yT4vlvIdpu4YhBwA25wtQWZsc6GJYGWHyvSdeuh3mmUI9DSq13Fsp8C1MU3tqc04/df9LoTUXVXI5INOw/e8C7Hd+5CvbkMEFnnrUUmskEw==");
    private static final int[] TT02 = {0xebb64faf, 0x07aadcc2, 0xcf3187bf, 0xe01138ff, 0x6d0bfcff, 0x5a30a3be, 0xb41ad638, 0x34180eb8, 0xf233eb6f, 0xb1a584cc, 0xccc30dc7, 0x47d1db51, 0xd55653de, 0x70a84fa1, 0x57473c12, 0xf76f0288, 0x2c077f0a, 0xda0dcad0, 0xfbb86f6c, 0xfdc4cf00, 0x688a020d, 0xe676c6a6, 0x8cd6338b, 0x1a3c8d0e, 0xcce8b06b, 0x6ad0ed0b, 0xa0522717, 0xdc71ac83, 0x2285db71, 0xd5b4dda6, 0x736f8650, 0x6560306c, 0x617ce2a6, 0xe423417e, 0x0a40e143, 0x544e4032, 0x88dffb2a, 0x716c1ae0, 0x4c467a88, 0x05b23bb3, 0xe1d0b866, 0xbaa3dcb8, 0xae3374d3, 0xc3381a50, 0x1702f75b, 0xfe6da368, 0xf0b4cf48, 0x4e0ffbb8, 0x72aad10d, 0x26c53a3d, 0xf2bce0f6, 0xb4557581, 0x4a257fdd, 0x8c3182a2, 0xab0b3b86, 0x3d5dfb14, 0x4f103634, 0xd37b52d7, 0x444eff16, 0xeb0a33d1, 0x6ca86f6e, 0x00284ba7, 0x08387cfa, 0x5fb37586};
    private static final int[] BRANCH0_IV = {0xc4a78580, 0xb3c0fd39, 0xc58c5686, 0xc9aa3ba7, 0xf5a7adf2, 0x963c2ed1};
    private static final int[][] BRANCH0_CHOSEN = {{101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3}, {96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4}, {96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5}, {99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6}, {96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4}, {100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5}};

    private AccountSigner() { }

    private static byte[] base64(String value) { return java.util.Base64.getDecoder().decode(value); }
    private static byte[] md5(byte[] value) throws Exception { return MessageDigest.getInstance("MD5").digest(value); }
    private static byte[] sm3(byte[] value) { return DanmakuSigner.sm3(value); }
    private static byte[] le32(int value) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
    private static byte[] be32(int value) { return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array(); }

    /** JS encodeURIComponent semantics: unreserved pass, everything else uppercase percent hex. */
    static String pythonUrlEncode(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(encodeComponent(entry.getKey())).append('=').append(encodeComponent(entry.getValue()));
        }
        return out.toString();
    }
    private static String encodeComponent(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : String.valueOf(value).getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 255);
            boolean unreserved = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~' || c == '*';
            if (unreserved) out.append(c);
            else { out.append('%'); out.append("0123456789ABCDEF".charAt((c >> 4) & 15)); out.append("0123456789ABCDEF".charAt(c & 15)); }
        }
        return out.toString();
    }

    private static int getIv(int iv, byte[] data) {
        int value = iv;
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 255;
            value = (i & 1) == 0 ? (value >>> 4) ^ value ^ (value << 6) ^ b : ~((value >>> 7) ^ value ^ (b | (value << 12)));
        }
        return value;
    }
    static int branchOf(String url, byte[] body, int khronos) throws Exception {
        String query = url.contains("?") ? url.substring(url.indexOf('?') + 1) : "";
        byte[] bodyMd5 = body == null ? new byte[16] : md5(body);
        int iv = getIv(getIv(getIv(0x20230928, sm3(query.getBytes(StandardCharsets.UTF_8))), bodyMd5), le32(khronos));
        int low = iv & 15;
        return low - ((low * 171) >> 9) * 3;
    }
    private static int sumMd5(int[] data) {
        int check = 0x20220420;
        for (int i = 0; i < 12; i++) {
            int temp = (i & 1) == 0 ? (check >>> 3) ^ check : (check >>> 5) ^ check;
            check = (i & 1) == 0 ? data[i] ^ (check << 7) : data[i] | (check << 11);
            if ((i & 1) != 0) check = ~check;
            check ^= temp;
        }
        return (check | 4) ^ 0x1000000;
    }
    private static int[] bxor16(int[] left, int[] right) {
        int[] out = new int[16];
        for (int at = 0; at < 16; at++) out[at] = left[at] ^ right[at];
        return out;
    }

    /** Branch-0 f13: the custom SHA-256-like compression over 128 expanded words. */
    private static byte[] hashF13(int iv, byte[] querySm3, byte[] bodyMd5, byte[] tsBytes, int khronos) {
        int low = iv & 15;
        int ivV0 = (low * 171) >> 9;
        int ivV1 = BRANCH0_IV[ivV0];
        int countV1 = (ivV1 + khronos + 1) & 255;
        int countV2 = ivV1 + khronos;
        int[] seed = {0xfa, 0x45, 0x61, 0xd7};
        int[] pad = new int[4];
        for (int at = 0; at < 4; at++) pad[at] = ((seed[at] | (seed[at] << 8)) >> ((countV2 + 2) & 7)) & 255;
        int[] data = new int[16];
        for (int at = 0; at < 32; at++) data[at / 4] |= (querySm3[at] & 255) << (24 - (at % 4) * 8);
        for (int at = 0; at < 16; at++) data[8 + at / 4] |= (bodyMd5[at] & 255) << (24 - (at % 4) * 8);
        for (int at = 0; at < 4; at++) data[12 + at / 4] |= (tsBytes[at] & 255) << (24 - (at % 4) * 8);
        data[13] = (pad[0] << 24) | (pad[1] << 16) | (pad[2] << 8) | pad[3];
        data[14] = 0x00000000;
        data[15] = 0x000001a0;
        int[] di = new int[128];
        System.arraycopy(data, 0, di, 0, 16);
        int di0 = di[0];
        for (int i = 0; i < 112; i++) {
            int di1 = di[i + 1], di14 = di[i + 14];
            int r1 = Integer.rotateLeft(di1, 14) ^ Integer.rotateLeft(di1, 25) ^ (di1 >>> 3);
            int r2 = Integer.rotateLeft(di14, 13) ^ Integer.rotateLeft(di14, 15) ^ (di14 >>> 10);
            di[i + 16] = di0 + di[i + 9] + r1 + r2;
            di0 = di1;
        }
        int[] init = {Integer.rotateRight(0x7aba4fc8, countV2 & 31), Integer.rotateRight(0x67166507, countV2 & 31), Integer.rotateRight(0x6403fa00, countV2 & 31), Integer.rotateRight(0x340f512f, countV2 & 31), Integer.rotateRight(984304912, countV2 & 31), Integer.rotateRight((int) 3005047866L, countV2 & 31), Integer.rotateRight((int) 2874125293L, countV2 & 31), Integer.rotateRight((int) 2152413264L, countV2 & 31)};
        int[] chosen = BRANCH0_CHOSEN[ivV0];
        int[] d = init.clone();
        for (int i = 0; i < chosen[0]; i++) {
            int base = ivV1 + i;
            int n0v = di[base & 127];
            int n1 = ((d[chosen[3]] ^ d[chosen[4]]) & d[chosen[1]]) ^ d[chosen[3]];
            int n2 = Integer.rotateLeft(d[chosen[1]], 26) ^ Integer.rotateLeft(d[chosen[1]], 21) ^ Integer.rotateLeft(d[chosen[1]], 7);
            int n4 = n0v + n1 + n2 + Integer.rotateRight(TT02[base & 63], countV1) + d[chosen[5]];
            int n5 = Integer.rotateLeft(d[chosen[2]], 30) ^ Integer.rotateLeft(d[chosen[2]], 19) ^ Integer.rotateLeft(d[chosen[2]], 10);
            int n6 = (d[chosen[2]] & d[chosen[6]]) | ((d[chosen[2]] | d[chosen[6]]) & d[chosen[7]]);
            int n7 = n5 + n6;
            int old = d[chosen[9]];
            int last = d[7];
            System.arraycopy(d, 0, d, 1, 7);
            d[0] = last;
            d[chosen[10]] = n7 + n4;
            d[chosen[8]] = old + n4;
        }
        int[] ret = new int[32];
        for (int i = 0; i < 8; i++) {
            int value = d[i] + init[i];
            ret[i * 4] = (value >>> 24) & 255; ret[i * 4 + 1] = (value >>> 16) & 255; ret[i * 4 + 2] = (value >>> 8) & 255; ret[i * 4 + 3] = value & 255;
        }
        int[] folded = bxor16(java.util.Arrays.copyOfRange(ret, 0, 16), java.util.Arrays.copyOfRange(ret, 16, 32));
        System.arraycopy(folded, 0, ret, 0, 16);
        byte[] out = new byte[20];
        for (int at = 0; at < 16; at++) out[at] = (byte) ret[at];
        System.arraycopy(le32(sumMd5(java.util.Arrays.copyOfRange(ret, 0, 16))), 0, out, 16, 4);
        return out;
    }

    private static int reverseBits(int value) {
        int result = 0;
        for (int i = 0; i < 8; i++) result = (result << 1) | ((value >> i) & 1);
        return result;
    }
    private static int[] rc4Gorgon(int[] data, int[] key) {
        int[] s = new int[256];
        for (int at = 0; at < 256; at++) s[at] = at;
        int j = 0;
        for (int at = 0; at < 256; at++) { j = (j + s[at] + key[at % key.length]) & 255; s[at] = s[j]; }
        int i = 0; j = 0;
        int[] out = new int[data.length];
        for (int at = 0; at < data.length; at++) {
            i += 1;
            int x = s[i];
            j += x;
            int y = s[j & 255];
            s[i] = y;
            out[at] = data[at] ^ s[(y + y) & 255];
        }
        return out;
    }
    static String xGorgon(String query, byte[] body, int timestamp, int random) throws Exception {
        int[] input = new int[20];
        byte[] queryMd5 = md5(query.getBytes(StandardCharsets.UTF_8));
        for (int at = 0; at < 4; at++) input[at] = queryMd5[at] & 255;
        if (body != null) { byte[] bodyMd5 = md5(body); for (int at = 0; at < 4; at++) input[4 + at] = bodyMd5[at] & 255; }
        int sdk = 67503104;
        input[12] = sdk & 255; input[13] = (sdk >> 8) & 255; input[14] = (sdk >> 16) & 255; input[15] = (sdk >> 24) & 255;
        byte[] be = be32(timestamp);
        for (int at = 0; at < 4; at++) input[16 + at] = be[at] & 255;
        int[] key = {0x4a, 0x40, 0x16, (random >> 8) & 255, 0x47, 0x6c, 0x01, random & 255};
        int[] out = rc4Gorgon(input, key);
        for (int at = 0; at < out.length; at++) {
            int value = ((out[at] >> 4) | (out[at] << 4)) & 255;
            int next = at + 1 < out.length ? out[at + 1] : out[0];
            out[at] = (~(reverseBits((next ^ value) & 255) ^ 20)) & 255;
        }
        StringBuilder hex = new StringBuilder("8404");
        int[] head = {random & 255, (random >> 8) & 255, 0x40, 0x01};
        for (int value : head) hex.append(String.format("%02x", value));
        for (int value : out) hex.append(String.format("%02x", value));
        return hex.toString();
    }
    static String helios(int khronos, int random) {
        byte[] data = new byte[8];
        byte[] randomLe = le32(random);
        System.arraycopy(randomLe, 0, data, 0, 4);
        data[4] = '8'; data[5] = '6'; data[6] = '6'; data[7] = '2';
        byte[] digest = null;
        try { digest = md5(data); } catch (Exception impossible) { }
        // Upstream reads the HEX TEXT of the digest as ASCII bytes, not the raw digest.
        byte[] ascii = DanmakuSigner.hex(digest).getBytes(StandardCharsets.US_ASCII);
        long[] words = new long[4];
        for (int at = 0; at < 4; at++) for (int k = 0; k < 8; k++) words[at] |= (long) (ascii[at * 8 + k] & 255) << (k * 8);
        long[] table = new long[35];
        long[] generated = new long[34];
        table[0] = words[0];
        long b0 = words[0], b8 = words[1];
        java.util.ArrayDeque<Long> fifo = new java.util.ArrayDeque<>();
        fifo.add(words[2]); fifo.add(words[3]);
        for (int i = 0; i < 34; i++) {
            long x8 = Long.rotateRight(b8, 8) + b0;
            x8 ^= i;
            generated[i] = x8;
            x8 ^= Long.rotateRight(b0, 61);
            table[i + 1] = x8;
            b0 = x8;
            b8 = fifo.poll();
            fifo.add(generated[i]);
        }
        byte[] text = (khronos + "-1588093228-8662").getBytes(StandardCharsets.US_ASCII);
        int paddedLength = ((text.length + 1 + 15) / 16) * 16;
        byte[] pad = new byte[paddedLength];
        int fill = 16 - (text.length % 16);
        java.util.Arrays.fill(pad, (byte) fill);
        System.arraycopy(text, 0, pad, 0, text.length);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] head = le32(random);
        output.write(head, 0, 4);
        for (int at = 0; at < pad.length; at += 16) {
            long a = 0, b = 0;
            for (int k = 0; k < 8; k++) { a |= (long) (pad[at + k] & 255) << (k * 8); b |= (long) (pad[at + 8 + k] & 255) << (k * 8); }
            for (int i = 0; i < 34; i++) {
                b = table[i] ^ (a + Long.rotateRight(b, 8));
                a = b ^ Long.rotateRight(a, 61);
            }
            byte[] block = new byte[16];
            for (int k = 0; k < 8; k++) { block[k] = (byte) (a >>> (k * 8)); block[8 + k] = (byte) (b >>> (k * 8)); }
            output.write(block, 0, 16);
        }
        return java.util.Base64.getEncoder().encodeToString(output.toByteArray());
    }

    // ---- proto wire format -----------------------------------------------------------

    private static byte[] varint(long value) {
        long n = value;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (n < 0) { out.write((int) (n & 127) | 128); n >>>= 7; }
        while (n >= 128) { out.write((int) (n & 127) | 128); n >>>= 7; }
        out.write((int) n);
        return out.toByteArray();
    }
    private static byte[] protoField(int tag, String type, Object value) {
        // JS: undefined/null/'' fields are omitted entirely (no tag, no bytes).
        if (value == null) return new byte[0];
        if (value instanceof String && ((String) value).isEmpty()) return new byte[0];
        switch (type) {
            case "bytes": case "string": case "message": {
                byte[] body = value instanceof byte[] ? (byte[]) value : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] tagBytes = varint((long) tag << 3 | 2);
                out.write(tagBytes, 0, tagBytes.length);
                byte[] length = varint(body.length);
                out.write(length, 0, length.length);
                out.write(body, 0, body.length);
                return out.toByteArray();
            }
            case "float": {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] tagBytes = varint((long) tag << 3 | 5);
                out.write(tagBytes, 0, tagBytes.length);
                int floatBits = Float.floatToIntBits(((Number) value).floatValue());
                out.write(floatBits & 255); out.write((floatBits >> 8) & 255); out.write((floatBits >> 16) & 255); out.write((floatBits >> 24) & 255);
                return out.toByteArray();
            }
            default: { // sint
                long n = ((Number) value).longValue();
                long zigzag = n < 0 ? -n * 2 - 1 : n * 2;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] tagBytes = varint((long) tag << 3);
                out.write(tagBytes, 0, tagBytes.length);
                byte[] body = varint(zigzag);
                out.write(body, 0, body.length);
                return out.toByteArray();
            }
        }
    }
    private static byte[] proto(Object[]... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object[] field : fields) {
            byte[] encoded = protoField((int) field[0], (String) field[1], field[2]);
            out.write(encoded, 0, encoded.length);
        }
        return out.toByteArray();
    }
    private static byte[] deviceProto(String deviceId, String processNonce, long processStarted) {
        Object[] fields = new Object[36];
        int at = 0;
        fields[at++] = new Object[]{1, "sint", 1}; fields[at++] = new Object[]{2, "sint", 2};
        fields[at++] = new Object[]{3, "string", "8662"}; fields[at++] = new Object[]{4, "string", deviceId};
        fields[at++] = new Object[]{5, "string", processNonce}; fields[at++] = new Object[]{6, "string", "!noperm!"};
        fields[at++] = new Object[]{7, "sint", -888888}; fields[at++] = new Object[]{8, "sint", -888888};
        fields[at++] = new Object[]{9, "sint", 3}; fields[at++] = new Object[]{10, "sint", -888888};
        fields[at++] = new Object[]{11, "string", "!notset!"}; fields[at++] = new Object[]{12, "string", "Asia/Shanghai,8"};
        fields[at++] = new Object[]{13, "string", "zh_CN"}; fields[at++] = new Object[]{14, "sint", 4};
        for (int tag = 16; tag <= 21; tag++) fields[at++] = new Object[]{tag, "float", 0};
        fields[at++] = new Object[]{22, "string", "16"}; fields[at++] = new Object[]{23, "sint", 41};
        fields[at++] = new Object[]{24, "sint", 36};
        for (int tag = 25; tag <= 28; tag++) fields[at++] = new Object[]{tag, "sint", processStarted};
        fields[at++] = new Object[]{29, "sint", -1};
        String[] names = {"25053RT47C", "Redmi", "25053RT47C", "25053RT47C", "Xiaomi", "Redmi", "Redmi"};
        for (int tag = 30; tag <= 36; tag++) fields[at++] = new Object[]{tag, "string", names[tag - 30]};
        fields[at++] = new Object[]{38, "sint", 31};
        Object[][] real = new Object[at][];
        System.arraycopy(fields, 0, real, 0, at);
        return proto(real);
    }
    private static byte[] xmxor(byte[] data, byte[] key) {
        int[] encoded = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            int at = (i * 4) & 28;
            int d0 = key[at] & 255, d1 = key[at + 1] & 255;
            int d2 = ((((data[i] & 255) << 4) | ((data[i] & 255) >>> 4)) & 255) + d0;
            d2 = ~d2 ^ d1;
            d2 = (((d2 & 255) << 3) | ((d2 & 255) >>> 5)) & 255;
            d2 = (d2 + d1) & 255;
            d2 = (d2 ^ d0) & 255;
            encoded[data.length - i - 1] = ~d2 & 255;
        }
        int last = encoded[encoded.length - 1] ^ encoded[encoded.length - 2];
        int first = encoded[0];
        encoded[0] = (~last + first) & 255;
        encoded[1] = ((encoded[0] ^ encoded[encoded.length - 1] ^ 254) + encoded[1]) & 255;
        encoded[2] = (encoded[2] + ((last - first) ^ (((encoded[1] << 3) | ((encoded[1] & 255) >>> 5)) & 255) ^ 2)) & 255;
        for (int i = 0; i < encoded.length - 4; i++) {
            int temp = (((encoded[i + 2] << 3) | ((encoded[i + 2] & 255) >>> 5)) & 255) ^ encoded[i + 1] ^ (i + 3);
            encoded[i + 3] = (~temp + encoded[i + 3]) & 255;
        }
        encoded[encoded.length - 1] ^= encoded[encoded.length - 2];
        int sum = 0;
        for (int i = 1; i < encoded.length; i++) sum += encoded[i];
        encoded[0] = ((encoded[0] ^ encoded[1]) + sum) & 255;
        byte[] out = new byte[encoded.length];
        for (int at = 0; at < out.length; at++) out[at] = (byte) encoded[at];
        return out;
    }
    private static Object[] keyHash(byte[] signKey, int random) {
        byte[] input = new byte[signKey.length * 2 + 4];
        System.arraycopy(signKey, 0, input, 0, signKey.length);
        byte[] randomLe = le32(random);
        System.arraycopy(randomLe, 0, input, signKey.length, 4);
        System.arraycopy(signKey, 0, input, signKey.length + 4, signKey.length);
        byte[] hash = sm3(input);
        int d1 = (random >>> 16) & 255;
        int d2 = ~(((d1 << 11) | (random >>> 24)) ^ (d1 >>> 5) ^ d1);
        byte[] seed = le32(d2);
        return new Object[]{hash, seed};
    }

    // ---- AesV3 -----------------------------------------------------------------------

    private static final class AesV3 {
        final int wordSize;
        final byte[] box;
        final int[] con;
        final int[] con2;
        final int[] order;
        final int[] keys = new int[48];
        AesV3(byte[] key, int khronos) {
            wordSize = khronos & 3;
            // Upstream uses only the 256-byte slice selected by the khronos word.
            box = java.util.Arrays.copyOfRange(SBOX, wordSize * 256, (wordSize + 1) * 256);
            con = new int[]{1, 0, 2, 3};
            con2 = new int[][]{{1, 0, 2, 3}, {2, 0, 3, 1}, {0, 1, 3, 2}, {1, 0, 2, 3}}[wordSize];
            order = new int[][]{{0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3}, {0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11}, {0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15}, {0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3}}[wordSize];
            int init = new int[]{0xca025ddc, 0x823dc546, 0xc9420583, 0xc298225f}[wordSize];
            // The same 32-bit word is written little-endian into all four words.
            byte[] initial = new byte[16];
            for (int i = 0; i < 4; i++) {
                initial[i * 4] = (byte) init;
                initial[i * 4 + 1] = (byte) (init >>> 8);
                initial[i * 4 + 2] = (byte) (init >>> 16);
                initial[i * 4 + 3] = (byte) (init >>> 24);
            }
            byte[] mk = new byte[16];
            for (int i = 0; i < 16; i++) mk[i] = (byte) (initial[i] ^ key[i]);
            byte[] expanded = new byte[48];
            System.arraycopy(mk, 0, expanded, 0, 16);
            int rounds = 8;
            for (int i = 4; i < 12; i++) {
                int at = 4 * (i - 1);
                int a = expanded[at] & 255, b = expanded[at + 1] & 255, c = expanded[at + 2] & 255, d = expanded[at + 3] & 255;
                if ((i & 3) == 0) {
                    int t = (int) ((((init >>> (rounds & 24)) ^ (box[b] & 255)) & 255));
                    b = box[c] & 255; c = box[d] & 255; d = box[a] & 255; a = t;
                }
                rounds += 2;
                expanded[at + 4] = (byte) (a ^ (expanded[at - 12] & 255));
                expanded[at + 5] = (byte) (b ^ (expanded[at - 11] & 255));
                expanded[at + 6] = (byte) (c ^ (expanded[at - 10] & 255));
                expanded[at + 7] = (byte) (d ^ (expanded[at - 9] & 255));
            }
            for (int i = 0; i < 48; i++) keys[i] = expanded[i] & 255;
        }
        private void add(int[][] rows, int base) { for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) rows[i][j] = (rows[i][j] ^ keys[base + i * 4 + j]) & 255; }
        private void addCon(int[][] rows, int base) { for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) rows[i][j] = (rows[i][j] ^ keys[base + i * 4 + con2[j]]) & 255; }
        private void sub(int[][] rows) {
            // Upstream substitutes first, then permutes the substituted rows.
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) rows[i][j] = box[rows[i][j]];
            int[][] old = new int[4][];
            for (int i = 0; i < 4; i++) old[i] = rows[i].clone();
            for (int i = 0; i < 4; i++) rows[i] = old[con2[i]].clone();
        }
        private void shift(int[][] rows) {
            int[] flat = new int[16];
            for (int i = 0; i < 4; i++) System.arraycopy(rows[i], 0, flat, i * 4, 4);
            for (int i = 0; i < 16; i++) rows[i / 4][i % 4] = flat[order[i]];
        }
        private void shiftCon(int[][] rows) {
            int[][] old = new int[4][];
            for (int i = 0; i < 4; i++) old[i] = rows[i].clone();
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) rows[i][j] = old[i][con2[j]];
        }
        private int[] block(int[] value) {
            int[][] rows = new int[4][4];
            for (int i = 0; i < 16; i++) rows[i / 4][i % 4] = value[i];
            addCon(rows, 0);
            for (int round = 1; round < 3; round++) {
                sub(rows);
                shift(rows);
                if (round == 1) { shiftCon(rows); aesMix(rows); }
                // Each round key is one four-byte group: the flat base is group * 16.
                addCon(rows, round * 16);
            }
            add(rows, 16);
            int[] out = new int[16];
            for (int i = 0; i < 4; i++) System.arraycopy(rows[i], 0, out, i * 4, 4);
            return out;
        }
        private static void aesMix(int[][] rows) {
            for (int i = 0; i < 4; i++) {
                int t = rows[0][i] ^ rows[1][i] ^ rows[2][i] ^ rows[3][i];
                int u = rows[0][i];
                rows[0][i] ^= t ^ xt(rows[0][i] ^ rows[1][i]);
                rows[1][i] ^= t ^ xt(rows[1][i] ^ rows[2][i]);
                rows[2][i] ^= t ^ xt(rows[2][i] ^ rows[3][i]);
                rows[3][i] ^= t ^ xt(rows[3][i] ^ u);
            }
        }
        private static int xt(int x) { return ((x << 1) ^ ((x & 128) != 0 ? 0x1b : 0)) & 255; }
        int[] encrypt(int[] data, int[] iv) {
            int[] plaintext = new int[32];
            for (int i = 0; i < 31; i++) {
                int at = i * 8;
                int n0 = (data[at] >> 4) & 2;
                int n1 = n0 | (data[at + 1] & 64);
                int n2 = n1 | ((data[at + 2] >> 2) & 1);
                int n3 = n2 | ((data[at + 3] << 3) & 128);
                int n4 = n3 | ((data[at + 4] >> 1) & 4);
                int n5 = n4 | ((data[at + 5] << 3) & 16);
                int n6 = n5 | ((data[at + 6] << 5) & 32);
                plaintext[i] = n6 | ((data[at + 7] >> 4) & 8);
            }
            plaintext[31] = 1;
            int[][] blocks = new int[2][16];
            int[] previous = iv.clone();
            for (int at = 0; at < plaintext.length; at += 16) {
                int[] chunk = new int[16];
                for (int i = 0; i < 16; i++) chunk[i] = plaintext[at + i] ^ previous[i];
                blocks[at / 16] = block(chunk);
                previous = blocks[at / 16];
            }
            int[] key = new int[32];
            System.arraycopy(blocks[0], 0, key, 0, 16);
            System.arraycopy(blocks[1], 0, key, 16, 16);
            int[] out = data.clone();
            for (int i = 0; i < 31; i++) {
                int at = i * 8;
                int k = key[i];
                out[at] = (out[at] & 0xdf) | ((k << 4) & 32);
                out[at + 1] = (out[at + 1] & 0xbf) | (k & 64);
                out[at + 2] = (out[at + 2] & 0xfb) | ((k << 2) & 4);
                out[at + 3] = (out[at + 3] & 0xef) | ((k >> 3) & 16);
                out[at + 4] = (out[at + 4] & 0xf7) | ((k + k) & 8);
                out[at + 5] = (out[at + 5] & 0xfd) | ((k >> 3) & 2);
                out[at + 6] = (out[at + 6] & 0xfe) | ((k >> 5) & 1);
                out[at + 7] = (out[at + 7] & 0x7f) | ((k << 4) & 128);
            }
            // Upstream returns only the last key byte in front of the transformed data.
            int[] result = new int[1 + out.length];
            result[0] = key[31];
            System.arraycopy(out, 0, result, 1, out.length);
            return result;
        }
    }

    // ---- medusa + sign ---------------------------------------------------------------

    private static byte[] buildMedusa(String url, byte[] body, int khronos, String deviceId, String versionName, String processNonce, long processStarted, int[] randoms) throws Exception {
        byte[] bodyMd5 = body == null ? new byte[16] : md5(body);
        String query = url.substring(url.indexOf('?') + 1);
        byte[] querySm3 = sm3(query.getBytes(StandardCharsets.UTF_8));
        byte[] ts = le32(khronos);
        byte[] queryBodyTs = hashF13(getIv(getIv(getIv(0x20230928, querySm3), bodyMd5), ts), querySm3, bodyMd5, ts, khronos);
        byte[] nested = proto(new Object[][]{{1, "sint", 111L}, {2, "sint", 10L}, {3, "sint", 694367L}, {5, "sint", 586952199L}});
        int messageRand = randoms[2], envLaunchRaw = randoms[3], envPidRaw = randoms[4], random = randoms[5], xmRand = randoms[6];
        int envLaunch = (int) ((envLaunchRaw & 0xFFFFFFFFL) * 21 / 4294967296L) + 100;
        int envPid = (int) ((envPidRaw & 0xFFFFFFFFL) * 2000 / 4294967296L) + 10001;
        byte[] env = proto(new Object[][]{
            {1, "sint", (long) envLaunch}, {2, "sint", 146331399L}, {3, "sint", 146331396L}, {5, "sint", 7L},
            {6, "string", "v04.06.04.03-bugfix"}, {7, "sint", (long) envPid},
            {12, "message", deviceProto(deviceId, processNonce, processStarted)},
            {13, "message", proto(new Object[][]{{1, "sint", (long) khronos}, {2, "sint", -2L}, {4, "sint", 200L}})},
            {14, "string", versionName}});
        byte[] queryHash = sm3(join(query.getBytes(StandardCharsets.UTF_8), bodyMd5, "none".getBytes(StandardCharsets.UTF_8)));
        byte[] message = proto(new Object[][]{
            {1, "bytes", unhex("f7e85ffad7d7dc3bd62ac87057cf6118")},
            {2, "sint", 3L}, {3, "sint", (long) (messageRand & 0xFFFFFFFFL)},
            {4, "string", "8662"}, {5, "string", deviceId}, {6, "string", "1588093228"},
            {7, "string", versionName}, {8, "string", "v04.06.04-ml-android"},
            {9, "sint", 67503104L}, {10, "bytes", unhex("4001000000000000")},
            {12, "sint", (long) khronos}, {13, "bytes", queryBodyTs},
            {14, "bytes", java.util.Arrays.copyOf(querySm3, 6)},
            {15, "message", nested}, {16, "string", processNonce}, {17, "sint", (long) khronos},
            {19, "bytes", queryHash}, {20, "string", "none"}, {21, "sint", 312L},
            {23, "message", env},
            {24, "string", "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1879194040,\"vpn\":0,\"kd\":0,\"fkd\":3672518972,\"pd\":-1872573247,\"dyn\":\"\",\"do\":0,\"tk\":true}"}});
        Object[] keyHashPair = keyHash(unhex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5"), random);
        byte[] hash = (byte[]) keyHashPair[0], seed = (byte[]) keyHashPair[1];
        byte[] transformed = join(unhex("4001000000000000"), xmxor(message, hash));
        for (int left = 0, right = transformed.length - 1; left < right; left++, right--) {
            byte temp = transformed[left]; transformed[left] = transformed[right]; transformed[right] = temp;
        }
        for (int at = 0; at < transformed.length; at++) transformed[at] ^= seed[(~at) & 3];
        int check = ((querySm3[0] & 63) << 14) | 0x18000001 | ((queryBodyTs[0] & 63) << 8);
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(0x35);
        byte[] xmLe = le32(xmRand);
        packed.write(xmLe, 0, 4);
        byte[] checkLe = le32(check);
        packed.write(checkLe, 0, 4);
        packed.write(transformed, 0, transformed.length);
        packed.write((random >>> 16) & 255);
        packed.write((random >>> 24) & 255);
        int[] packedInts = new int[packed.size()];
        byte[] packedBytes = packed.toByteArray();
        for (int at = 0; at < packedBytes.length; at++) packedInts[at] = packedBytes[at] & 255;
        int[] iv = toInts(unhex("1fe109a4125283f418de9e051a969e12"));
        int[] keyMaterial = toInts(unhex("f1593376766ea98d34f31b057a9d5be4"));
        byte[] keyBytes = new byte[16];
        for (int at = 0; at < 16; at++) keyBytes[at] = (byte) keyMaterial[at];
        int[] encrypted = new AesV3(keyBytes, khronos).encrypt(packedInts, iv);
        byte[] version = unhex("03000000f7e85ffad7d7dc3bd62ac87057cf6118");
        byte[] prefix = new byte[20];
        for (int at = 0; at < 20; at += 4) {
            int versionWord = (version[at] & 255) | ((version[at + 1] & 255) << 8) | ((version[at + 2] & 255) << 16) | ((version[at + 3] & 255) << 24);
            int word = versionWord ^ khronos;
            prefix[at] = (byte) word; prefix[at + 1] = (byte) (word >> 8); prefix[at + 2] = (byte) (word >> 16); prefix[at + 3] = (byte) (word >> 24);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(prefix, 0, 20);
        out.write(random & 255); out.write((random >> 8) & 255); out.write(0); out.write(1);
        for (int value : encrypted) out.write(value);
        return out.toByteArray();
    }
    static Signed request(String apiPath, Map<String, String> values, String deviceId, String iid, String cdid, long now, String processNonce, long processStarted, int[] randoms) throws Exception {
        Map<String, String> device = new LinkedHashMap<>();
        device.put("ac", "wifi"); device.put("channel", "update_64"); device.put("aid", "8662"); device.put("app_name", "novelread");
        device.put("version_code", "72232"); device.put("version_name", "7.2.2.32"); device.put("device_platform", "android"); device.put("os", "android");
        device.put("ssmix", "a"); device.put("device_type", "25053RT47C"); device.put("device_brand", "Redmi"); device.put("language", "zh"); device.put("os_api", "36");
        device.put("os_version", "16"); device.put("manifest_version_code", "72232"); device.put("resolution", "1280*2772"); device.put("dpi", "520");
        device.put("update_version_code", "72232"); device.put("host_abi", "arm64-v8a"); device.put("dragon_device_type", "phone"); device.put("pv_player", "72232");
        device.put("compliance_status", "0"); device.put("need_personal_recommend", "1"); device.put("player_so_load", "1"); device.put("is_android_pad_screen", "0");
        device.put("iid", iid); device.put("device_id", deviceId); device.put("cdid", cdid);
        // Upstream spreads the caller's values FIRST so they lead the signed query.
        Map<String, String> merged = new LinkedHashMap<>(values);
        for (Map.Entry<String, String> entry : device.entrySet()) merged.putIfAbsent(entry.getKey(), entry.getValue());
        int khronos = (int) (now / 1000);
        String url = null, query = null;
        long requestTicket = now;
        for (int n = 0; n < 128; n++) {
            requestTicket = now + n;
            merged.put("ts", String.valueOf(khronos));
            merged.put("_rticket", String.valueOf(requestTicket));
            query = pythonUrlEncode(merged);
            url = HOST + apiPath + "?" + query;
            if (branchOf(url, null, khronos) == 0) break;
            if (n == 127) throw new IllegalStateException("无法生成账号请求签名");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
        headers.put("x-ss-req-ticket", String.valueOf(requestTicket));
        headers.put("x-tt-request-tag", "t=0;n=0");
        headers.put("sdk-version", "2");
        headers.put("passport-sdk-version", "50561");
        headers.put("x-xs-from-web", "0");
        headers.put("x-khronos", String.valueOf(khronos));
        headers.put("x-ladon", java.util.Base64.getEncoder().encodeToString(be32(khronos)));
        headers.put("x-argus", java.util.Base64.getEncoder().encodeToString(le32(khronos)));
        headers.put("x-gorgon", xGorgon(query, null, khronos, randoms[0] >>> 16));
        headers.put("x-helios", helios(khronos, randoms[1]));
        byte[] medusa = buildMedusa(url, null, khronos, deviceId, "7.2.2.32", processNonce, processStarted, randoms);
        headers.put("x-medusa", java.util.Base64.getEncoder().encodeToString(medusa));
        headers.put("x-tt-dt", "");
        return new Signed(url, headers);
    }
    static final class Signed { final String url; final Map<String, String> headers; Signed(String url, Map<String, String> headers) { this.url = url; this.headers = headers; } }

    private static byte[] unhex(String value) { return DanmakuSigner.unhex(value); }
    private static int[] toInts(byte[] bytes) { int[] out = new int[bytes.length]; for (int at = 0; at < out.length; at++) out[at] = bytes[at] & 255; return out; }
    private static byte[] join(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) size += part.length;
        byte[] out = new byte[size];
        int at = 0;
        for (byte[] part : parts) { System.arraycopy(part, 0, out, at, part.length); at += part.length; }
        return out;
    }
}
