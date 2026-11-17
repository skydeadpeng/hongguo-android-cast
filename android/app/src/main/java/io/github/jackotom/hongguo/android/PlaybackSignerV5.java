package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Playback v5 (Android 04.10.03) Medusa/Helios/Khronos signing for the
 * /novel/player/multi_video_model/player/v1 endpoint.
 * Adapted from N3urda/hongguoTV commits 14771e0 + 18c37c2
 * (kotlin-tv/core/src/main/kotlin/com/hongguotv/core/{Signer,AesV3,V5Constants}.kt, GPL-3.0-only),
 * which upstream attributes to drpys 22261adfa31435e3b3ef5a730b8a2a75bcaf1715.
 * 2026-11-16: the former 551-signed model requests earn empty HTTP 200 bodies; only the
 * v5 three-header form (x-khronos/x-helios/x-medusa) is accepted. The JS twin with golden
 * vectors is src/native/playback-signature-v5.js; both must stay byte-identical.
 * Public protocol constants only; no account credentials.
 */
final class PlaybackSignerV5 {
    private static final byte[] MAGIC = hex("2d4b4fca49750d433fb5ae2c226dcc56");
    private static final String LICENSE = "646637794";
    private static final byte[] SIGN_KEY = hex("b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d");
    private static final byte[] SBOX = hex(
        "53290fe9e51f316f90f74a7e034d367784ab49237cd0135cdc632c322fd3be0e2624a417ecd9151a7ff64f60730a87f9445ec6ada2f865d22b696c4188f24c0d" +
        "f37b75510b939f6bc2fe6883e68bc8c722181ba0bdb08c9871b50c9a16b46eea8eed20ba4b8a14e0fcf1468dbbef05a93e5a9d7dcf06898635c1fd99f5de287a" +
        "80c594daaab19b6279fbd75070cb52eb2555c456e39e7839853874e2f0013ac0b64742d8ca10e4b79c955de8b843ce4ed464a6a72ed508091c92e7b3eedd1e1d" +
        "d12afac321618fa3d65f9167c982f43d66a53c6a1997585b346dbf9672ff37cdbc073b2dae11453304df00815476b2a13fccdb30b957a8acafe1274002485912" +
        "38922563e36406d37a24100b7902a028044e218453c2a399199c9d13dc40c55ba67e502273850ebdc1a2e2188b8f2d9e3cb909b8c46a2a9f4a6dd93007eafa65" +
        "b7b3a881aa908cc74f0ad76bbbb58617c37012ca05a469fd44e62eecf1e4272ce860c0ae5eadf99b20d8f0234174006f3f110d7657d01645d4eff8f71471cbfe" +
        "1cb2f52648727bf3c9de55153e031b4d61757db63dbf7782eeafe9018a8891362fb14759ac32bab03b349aeb5a377c5156be4c8dfbe13a684bda39cf836697c8" +
        "94545fe0f2491acea9fc6252f4ff1e0fe72bd2df6e67a7f635e5bcc6d1a5dd951d89960887a18eb40c785cdb1f80ed42299333ab98cdd67f316cd54346585dcc" +
        "d759fb722789b08807cc0fda293f7e5e1819bb0353517020b31d3d00374952bea2c421e862040a9ec892a05b266b7a0d0cc316f483b81af39715657ffe32ad8d" +
        "e0c13077646035236ee5363e5f900e9a7cf850a81cd9960b6df73317226f99eaa6fff15cec34acb98246639b918ab2851466a7cdfc4ad1b52c2ae95a58d3734e" +
        "47ddd5b7560193f644ef9c408b7dee39b4d6aabf4de761e3252d4c7ba3843187745d55e6a5db05d42b45caed43c0c5d0b64bfa9d94a4a9d8f2f0e1a108dcfd28" +
        "8fd2bc1fc7bd675412138169cb09249875ba3b68cfb11180afae104fc9789571c6dfce02413886796ae21e3c488cc21b9f423a066c2eabf98e2fdeeb5776e4f5" +
        "d09a515ba58cbbab3e37b09fd4f6d9244df80512e84cd7956be301cf64f1429d58577cc241f3a2c655144f81aaf95a4e108da894b38970561ae9002619fbbac9" +
        "1fa922ed202bfc62cb08e0de2a7f3631435f8b47c8a3c563b4db4071ce74c46da6fa696a9e282d1e825d663b93bfd56c48dce1884bb10fccea063929f746443f" +
        "d830a4232e53da21a1f2d1bdfebc3a02c10a1b8ad31d04fd616590d272ac737e98eb33af60528716bee78ec0ee8699031177277d9cff32542c67caf5f4459168" +
        "1335ae0e178f3409a7803db83892dde5b9b7f0c750e24aef077badd678a09b850b84ec1597e4963cb5e675df1c0d6fb618496ecd76c35c798325590c2fb27a5e");
    private static final int[] HASH_TABLE = {0xebb64faf,0x7aadcc2,0xcf3187bf,0xe01138ff,0x6d0bfcff,0x5a30a3be,0xb41ad638,0x34180eb8,0xf233eb6f,0xb1a584cc,0xccc30dc7,0x47d1db51,0xd55653de,0x70a84fa1,0x57473c12,0xf76f0288,0x2c077f0a,0xda0dcad0,0xfbb86f6c,0xfdc4cf00,0x688a020d,0xe676c6a6,0x8cd6338b,0x1a3c8d0e,0xcce8b06b,0x6ad0ed0b,0xa0522717,0xdc71ac83,0x2285db71,0xd5b4dda6,0x736f8650,0x6560306c,0x617ce2a6,0xe423417e,0xa40e143,0x544e4032,0x88dffb2a,0x716c1ae0,0x4c467a88,0x5b23bb3,0xe1d0b866,0xbaa3dcb8,0xae3374d3,0xc3381a50,0x1702f75b,0xfe6da368,0xf0b4cf48,0x4e0ffbb8,0x72aad10d,0x26c53a3d,0xf2bce0f6,0xb4557581,0x4a257fdd,0x8c3182a2,0xab0b3b86,0x3d5dfb14,0x4f103634,0xd37b52d7,0x444eff16,0xeb0a33d1,0x6ca86f6e,0x284ba7,0x8387cfa,0x5fb37586};
    static final String PLAYER_V1_API = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1";
    static final String PLAYER_V1_UA = "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** The official medusa plaintext embeds a per-process nonce and start time; one pair per cold start. */
    private static final String PROCESS_NONCE = base64Url(random(18));
    private static final long PROCESS_STARTED = System.currentTimeMillis();

    static final class Request {
        final String url; final Map<String, String> headers; final byte[] body;
        Request(String url, Map<String, String> headers, byte[] body) { this.url = url; this.headers = headers; this.body = body; }
    }

    private static byte[] random(int count) { byte[] out = new byte[count]; RANDOM.nextBytes(out); return out; }
    private static String base64Url(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static String base64(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static int rand() { return RANDOM.nextInt(); }
    private static long unsigned(int value) { return value & 0xffffffffL; }

    static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    static String hexText(byte[] value) {
        StringBuilder out = new StringBuilder();
        for (byte item : value) out.append(String.format("%02x", item));
        return out.toString();
    }
    private static byte[] le32(long value) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array(); }
    private static byte[] le64(long value) { return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array(); }
    private static byte[] be32(long value) { return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) value).array(); }
    private static byte[] md5(byte[] value) {
        try { return MessageDigest.getInstance("MD5").digest(value); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    // ===== protobuf =====
    private static byte[] varint(long value) {
        long n = value; ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((n & ~0x7fL) != 0) { out.write((int) ((n & 0x7f) | 0x80)); n >>>= 7; }
        out.write((int) n); return out.toByteArray();
    }
    private static long zigzag(long value) { return value < 0 ? -value * 2 - 1 : value * 2; }
    private static byte[] field(int tag, byte[] payload) {
        return concat(varint(((long) tag << 3) | 2), varint(payload.length), payload);
    }
    private static byte[] sint(int tag, long value) { return concat(varint((long) tag << 3), varint(zigzag(value))); }
    private static byte[] str(int tag, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length == 0 ? new byte[0] : field(tag, bytes);
    }
    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    // ===== digest helpers =====
    private static int getIv(int initial, byte[] data) {
        int value = initial;
        for (int i = 0; i < data.length; i++) {
            // Kotlin Byte.u()/JS Buffer use unsigned octets; Java byte sign extension
            // changes both the branch selector and the signed request ticket.
            int octet = data[i] & 255;
            value = (i & 1) == 0 ? (value >>> 4) ^ value ^ (value << 6) ^ octet
                : ~((value >>> 7) ^ value ^ (octet | (value << 12)));
        }
        return value;
    }
    private static int iv(byte[] query, byte[] body, long ts) {
        return getIv(getIv(getIv(0x20230928, query), body), le32(ts));
    }
    /** v5 keeps one hash branch: signPlayback adjusts _rticket until the digest class lands on 12. */
    static int branchClass(String query, byte[] body, long ts) {
        byte[] bodyMd5 = body != null ? md5(body) : new byte[16];
        return iv(DanmakuSigner.sm3(query.getBytes(StandardCharsets.UTF_8)), bodyMd5, ts) & 15;
    }
    private static int sumMd5(byte[] data) {
        int check = 0x20220420;
        for (int i = 0; i < 12; i++) {
            int temp = (i & 1) == 0 ? (check >>> 3) ^ check : (check >>> 5) ^ check;
            int octet = data[i] & 255;
            check = (i & 1) == 0 ? octet ^ (check << 7) : octet | (check << 11);
            if ((i & 1) != 0) check = ~check;
            check = check ^ temp;
        }
        return (check | 4) ^ 0x1000000;
    }
    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[Math.min(a.length, b.length)];
        for (int i = 0; i < out.length; i++) out[i] = (byte) (a[i] ^ b[i]);
        return out;
    }
    /** v5 digest: fixed seed 0x9d4a97c2, 96 rounds with state selection (1,3,4,0,6,5,2,0,7,7), checksum bit 5. */
    static byte[] hashF13(byte[] querySm3, byte[] bodyMd5, long ts) {
        final int ivV1 = 0x9d4a97c2;
        int count = ivV1 + (int) ts;
        int[] tt = new int[HASH_TABLE.length];
        for (int i = 0; i < tt.length; i++) tt[i] = Integer.rotateRight(HASH_TABLE[i], (count + 1) & 255);
        int shift = (count + 2) & 7;
        byte[] pad = new byte[4];
        int[] seedBytes = {0xfa, 0x45, 0x61, 0xd7};
        for (int i = 0; i < 4; i++) pad[i] = (byte) (((seedBytes[i] | (seedBytes[i] << 8)) >>> shift) & 255);
        byte[] data = concat(querySm3, bodyMd5, le32(ts), pad, hex("00000000000001a0"));
        int[] di = new int[data.length / 4 + 112];
        for (int i = 0; i < data.length / 4; i++) di[i] = ByteBuffer.wrap(data, i * 4, 4).getInt();
        for (int i = 0; i < 112; i++) {
            int x = di[i + 1], y = di[i + 14];
            di[data.length / 4 + i] = di[i] + di[i + 9]
                + (Integer.rotateLeft(x, 14) ^ Integer.rotateLeft(x, 25) ^ (x >>> 3))
                + (Integer.rotateLeft(y, 13) ^ Integer.rotateLeft(y, 15) ^ (y >>> 10));
        }
        int[] initialSource = {0x7aba4fc8, 0x67166507, 0x6403fa00, 0x340f512f, 984304912, (int) 3005047866L, (int) 2874125293L, (int) 2152413264L};
        int[] initial = new int[8];
        for (int i = 0; i < 8; i++) initial[i] = Integer.rotateRight(initialSource[i], count);
        final int[] chosen = {96, 1, 3, 4, 0, 6, 5, 2, 0, 7, 7};
        int[] d = initial.clone();
        for (int i = 0; i < chosen[0]; i++) {
            int base = ivV1 + i;
            int n1 = ((d[chosen[3]] ^ d[chosen[4]]) & d[chosen[1]]) ^ d[chosen[3]];
            int n2 = Integer.rotateLeft(d[chosen[1]], 26) ^ Integer.rotateLeft(d[chosen[1]], 21) ^ Integer.rotateLeft(d[chosen[1]], 7);
            int n4 = di[base & 127] + n1 + n2 + tt[base & 63] + d[chosen[5]];
            int n5 = Integer.rotateLeft(d[chosen[2]], 30) ^ Integer.rotateLeft(d[chosen[2]], 19) ^ Integer.rotateLeft(d[chosen[2]], 10);
            int n6 = (d[chosen[2]] & d[chosen[6]]) | ((d[chosen[2]] | d[chosen[6]]) & d[chosen[7]]);
            int old = d[chosen[9]];
            System.arraycopy(d, 0, d, 1, 7);
            d[0] = d[7];
            d[chosen[10]] = n5 + n6 + n4;
            d[chosen[8]] = old + n4;
        }
        ByteBuffer ret = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < 8; i++) ret.putInt(d[i] + initial[i]);
        byte[] folded = xor(ret.array(), 0, 16, ret.array(), 16, 16);
        return concat(folded, le32(sumMd5(folded) | 0x20));
    }
    private static byte[] xor(byte[] a, int aOff, int aLen, byte[] b, int bOff, int bLen) {
        byte[] out = new byte[aLen];
        for (int i = 0; i < aLen; i++) out[i] = (byte) (a[aOff + i] ^ b[bOff + i]);
        return out;
    }

    // ===== carrier transform =====
    private static byte[] xmxor(byte[] data, byte[] key) {
        int[] encoded = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            int at = (i * 4) & 28;
            int d0 = key[at] & 255, d1 = key[at + 1] & 255;
            int v = data[i] & 255;
            int d2 = (((v << 4) | (v >>> 4)) & 255) + d0;
            d2 = ~d2 ^ d1;
            d2 = (((d2 & 255) << 3) | ((d2 & 255) >>> 5)) & 255;
            d2 = (d2 + d1) & 255;
            d2 = (d2 ^ d0) & 255;
            encoded[data.length - i - 1] = (~d2) & 255;
        }
        int last = encoded[encoded.length - 1] ^ encoded[encoded.length - 2];
        int first = encoded[0];
        encoded[0] = (~last + first) & 255;
        encoded[1] = ((encoded[0] ^ encoded[encoded.length - 1] ^ 254) + encoded[1]) & 255;
        encoded[2] = (encoded[2] + ((last - first) ^ (((encoded[1] << 3) | (encoded[1] >>> 5)) & 255) ^ 2)) & 255;
        for (int i = 0; i < encoded.length - 4; i++) {
            int temp = (((encoded[i + 2] << 3) | (encoded[i + 2] >>> 5)) & 255) ^ encoded[i + 1] ^ (i + 3);
            encoded[i + 3] = (~temp + encoded[i + 3]) & 255;
        }
        encoded[encoded.length - 1] ^= encoded[encoded.length - 2];
        int sum = 0;
        for (int i = 0; i < encoded.length - 1; i++) sum += encoded[i + 1];
        encoded[0] = ((encoded[0] ^ encoded[1]) + sum) & 255;
        byte[] out = new byte[encoded.length];
        for (int i = 0; i < encoded.length; i++) out[i] = (byte) encoded[i];
        return out;
    }
    private static byte[] keyHash(byte[] signKey, int nonce) {
        byte[] hash = DanmakuSigner.sm3(concat(signKey, le32(unsigned(nonce)), signKey));
        int high = (nonce >>> 16) & 255;
        int seed = ~(((high << 11) | (nonce >>> 24)) ^ (high >>> 5) ^ high);
        return hash;
    }
    private static int seedOf(int nonce) {
        int high = (nonce >>> 16) & 255;
        return ~(((high << 11) | (nonce >>> 24)) ^ (high >>> 5) ^ high);
    }

    // ===== AesV3 v5 =====
    static final class AesV3V5 {
        private final int word;
        private final byte[] box;
        private final int[] con, order;
        private final int[][] keys;
        AesV3V5(byte[] key, long ts) {
            word = (int) (ts & 3);
            box = new byte[256];
            System.arraycopy(SBOX, word * 256, box, 0, 256);
            con = new int[][]{{2, 0, 3, 1}, {1, 3, 2, 0}, {3, 2, 1, 0}, {2, 3, 1, 0}}[word];
            order = new int[][]{
                {0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11},
                {0, 9, 14, 11, 4, 13, 2, 3, 8, 1, 6, 15, 12, 5, 10, 7},
                {0, 9, 14, 3, 4, 13, 2, 15, 8, 1, 6, 11, 12, 5, 10, 7},
                {0, 9, 14, 15, 4, 13, 2, 3, 8, 1, 6, 11, 12, 5, 10, 7},
            }[word];
            int init = new int[]{0xbbb5de72, 0xd4638d5b, 0x92a5f73b, 0x89f8a1c9}[word];
            byte[] initial = new byte[16];
            for (int i = 0; i < 4; i++) ByteBuffer.wrap(initial, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(init);
            byte[] mk = xor(key, 0, 16, initial, 0, 16);
            // The JS/Kotlin twin keeps the expanded schedule as a byte buffer: every
            // stored word is one byte (0-255), never a packed 32-bit integer.
            byte[] expanded = new byte[12 * 4];
            System.arraycopy(mk, 0, expanded, 0, 16);
            int rounds = 8;
            for (int i = 4; i < 12; i++) {
                int at = 4 * (i - 1);
                int a = expanded[at] & 255, b = expanded[at + 1] & 255, c = expanded[at + 2] & 255, d = expanded[at + 3] & 255;
                if ((i & 3) == 0) {
                    int t = ((init >>> (rounds & 24)) ^ (box[b] & 255)) & 255;
                    b = box[c] & 255; c = box[d] & 255; d = box[a] & 255; a = t;
                }
                rounds += 2;
                expanded[at + 4] = (byte) (a ^ (expanded[at - 12] & 255));
                expanded[at + 5] = (byte) (b ^ (expanded[at - 11] & 255));
                expanded[at + 6] = (byte) (c ^ (expanded[at - 10] & 255));
                expanded[at + 7] = (byte) (d ^ (expanded[at - 9] & 255));
            }
            keys = new int[12][];
            for (int i = 0; i < 12; i++) keys[i] = new int[]{expanded[i * 4] & 255, expanded[i * 4 + 1] & 255, expanded[i * 4 + 2] & 255, expanded[i * 4 + 3] & 255};
        }
        private byte[] block(byte[] value) {
            int[][] rows = new int[4][4];
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) rows[i][j] = value[i * 4 + j] & 255;
            addCon(rows, 0);
            for (int round = 1; round <= 2; round++) {
                for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) rows[i][j] = box[rows[i][j]] & 255;
                int[][] before = new int[4][];
                for (int i = 0; i < 4; i++) before[i] = rows[i].clone();
                for (int i = 0; i < 4; i++) rows[i] = before[con[i]].clone();
                int[] flat = new int[16];
                for (int i = 0; i < 4; i++) System.arraycopy(rows[i], 0, flat, i * 4, 4);
                for (int i = 0; i < 16; i++) rows[i / 4][i % 4] = flat[order[i]];
                if (round == 1) {
                    for (int i = 0; i < 4; i++) { int[] old = rows[i].clone(); for (int j = 0; j < 4; j++) rows[i][j] = old[con[j]]; }
                    for (int i = 0; i < 4; i++) {
                        int t = rows[0][i] ^ rows[1][i] ^ rows[2][i] ^ rows[3][i];
                        int u = rows[0][i];
                        rows[0][i] ^= t ^ xt(rows[0][i] ^ rows[1][i]);
                        rows[1][i] ^= t ^ xt(rows[1][i] ^ rows[2][i]);
                        rows[2][i] ^= t ^ xt(rows[2][i] ^ rows[3][i]);
                        rows[3][i] ^= t ^ xt(rows[3][i] ^ u);
                    }
                }
                addCon(rows, round * 4);
            }
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) rows[i][j] ^= keys[4 + i][j];
            byte[] out = new byte[16];
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) out[i * 4 + j] = (byte) rows[i][j];
            return out;
        }
        private void addCon(int[][] rows, int offset) {
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) rows[i][j] ^= keys[offset + i][con[j]];
        }
        private static int xt(int x) { return ((x << 1) ^ ((x & 128) != 0 ? 0x1b : 0)) & 255; }
        byte[] encrypt(byte[] source, byte[] iv) {
            // wire=[6,5,3,4,2,7,1,0], compact=[1,7,3,6,2,0,4,5]
            int[] wire = {6, 5, 3, 4, 2, 7, 1, 0}, compact = {1, 7, 3, 6, 2, 0, 4, 5};
            byte[] plaintext = new byte[32];
            for (int i = 0; i < 31; i++) {
                int value = 0;
                for (int j = 0; j < 8; j++) value |= ((source[i * 8 + j] & 255) >>> wire[j] & 1) << compact[j];
                plaintext[i] = (byte) value;
            }
            plaintext[31] = 1;
            byte[] first = block(xor(plaintext, 0, 16, iv, 0, 16));
            byte[] second = block(xor(plaintext, 16, 16, first, 0, 16));
            byte[] key = concat(first, second);
            byte[] out = source.clone();
            for (int i = 0; i < 31; i++) {
                for (int j = 0; j < 8; j++) {
                    int at = i * 8 + j;
                    out[at] = (byte) ((out[at] & 255 & ~(1 << wire[j])) | ((key[i] >> compact[j] & 1) << wire[j]));
                }
            }
            return concat(new byte[]{key[key.length - 1]}, out);
        }
    }

    // ===== headers =====
    static String helios(long ts) {
        int random = rand();
        byte[] digest = md5(concat(le32(unsigned(random)), "8662".getBytes(StandardCharsets.UTF_8)));
        byte[] ascii = hexText(digest).getBytes(StandardCharsets.US_ASCII);
        long[] words = new long[4];
        for (int i = 0; i < 4; i++) words[i] = ByteBuffer.wrap(ascii, i * 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        // JS twin semantics: a queue seeded with words[2..3]; push(x8) then shift() the
        // front, so iteration i consumes the value pushed two iterations earlier.
        long[] window = new long[4];
        window[0] = words[2]; window[1] = words[3];
        long[] table = new long[35];
        table[0] = words[0];
        long b0 = words[0], b8 = words[1];
        int writeAt = 2;
        for (int i = 0; i < 34; i++) {
            long x8 = Long.rotateRight(b8, 8) + b0;
            x8 = x8 ^ (long) i;
            window[writeAt] = x8;
            x8 = x8 ^ Long.rotateRight(b0, 61);
            table[i + 1] = x8;
            b0 = x8;
            b8 = window[(writeAt + 2) & 3];
            writeAt = (writeAt + 1) & 3;
        }
        byte[] text = (ts + "-" + LICENSE + "-8662").getBytes(StandardCharsets.UTF_8);
        int padding = 16 - text.length % 16;
        byte[] data = new byte[text.length + padding];
        System.arraycopy(text, 0, data, 0, text.length);
        for (int i = text.length; i < data.length; i++) data[i] = (byte) padding;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(le32(unsigned(random)), 0, 4);
        for (int at = 0; at < data.length; at += 16) {
            long a = ByteBuffer.wrap(data, at, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            long b = ByteBuffer.wrap(data, at + 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            for (int i = 0; i < 34; i++) {
                b = table[i] ^ (a + Long.rotateRight(b, 8));
                a = b ^ Long.rotateRight(a, 61);
            }
            output.write(le64(a), 0, 8);
            output.write(le64(b), 0, 8);
        }
        return base64(output.toByteArray());
    }

    /** who carries the same registered identity the query string carries; the server cross-checks both. */
    static String medusa(String url, byte[] body, long ts, Map<String, String> who) {
        String query = url.substring(url.indexOf('?') + 1);
        byte[] bodyMd5 = body != null ? md5(body) : new byte[16];
        byte[] querySm3 = DanmakuSigner.sm3(query.getBytes(StandardCharsets.UTF_8));
        byte[] f13 = hashF13(querySm3, bodyMd5, ts);
        byte[] flags = new byte[8];
        byte[] deviceInfo = concat(
            sint(1, 1), sint(2, 2), str(3, "8662"), str(4, who.get("device_id")), str(5, PROCESS_NONCE),
            str(6, "!noperm!"), str(12, "Asia/Shanghai,8"), str(13, "zh_CN"), str(22, who.get("os_version")),
            sint(25, PROCESS_STARTED), str(30, who.get("device_type")), str(31, "HONOR"), str(32, who.get("device_type")), str(34, "HONOR"));
        byte[] env = concat(str(6, "v04.10.03.04-bugfix"), str(8, who.get("channel")), field(12, deviceInfo), str(14, who.get("version_name")));
        byte[] message = concat(
            field(1, MAGIC), sint(2, 5), sint(3, unsigned(rand())), str(4, "8662"), str(5, who.get("device_id")),
            str(6, LICENSE), str(7, who.get("version_name")), str(8, "v04.10.03-ml-android"), sint(9, 0x040a0300),
            field(10, flags), sint(12, ts), field(13, f13), field(14, copyOf(querySm3, 6)),
            field(15, concat(sint(1, 1), sint(2, 1), sint(3, 1), sint(5, 1))),
            sint(17, ts), str(20, "none"), sint(21, 377), field(23, env),
            str(24, "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":11,\"vpn\":0,\"kd\":0,\"dyn\":\"\",\"do\":1,\"tk\":true}"));
        int nonce = rand();
        byte[] hash = DanmakuSigner.sm3(concat(SIGN_KEY, le32(unsigned(nonce)), SIGN_KEY));
        byte[] seed = le32(unsigned(seedOf(nonce)));
        byte[] transformed = concat(flags, xmxor(message, hash));
        reverse(transformed);
        for (int i = 0; i < transformed.length; i++) transformed[i] = (byte) (transformed[i] ^ seed[(~i) & 3]);
        int check = 0x18000001 | ((querySm3[0] & 63) << 14) | ((f13[0] & 63) << 8);
        byte[] packed = concat(new byte[]{(byte) 0xca}, le32(unsigned(rand())), le32(unsigned(check)), transformed,
            new byte[]{(byte) ((nonce >>> 16) & 255), (byte) ((nonce >>> 24) & 255)});
        byte[] encrypted = new AesV3V5(md5(copyOf(SIGN_KEY, 16)), ts).encrypt(packed, md5(copyOf(SIGN_KEY, 16, 16)));
        byte[] prefix = concat(le32(5), MAGIC);
        byte[] tsLe = le32(ts);
        for (int i = 0; i < prefix.length; i++) prefix[i] = (byte) (prefix[i] ^ tsLe[i & 3]);
        return base64(concat(prefix, new byte[]{(byte) nonce, (byte) (nonce >>> 8), 0, 1}, encrypted));
    }
    private static byte[] copyOf(byte[] source, int length) { byte[] out = new byte[length]; System.arraycopy(source, 0, out, 0, length); return out; }
    private static byte[] copyOf(byte[] source, int offset, int length) { byte[] out = new byte[length]; System.arraycopy(source, offset, out, 0, length); return out; }
    private static void reverse(byte[] data) { for (int i = 0, j = data.length - 1; i < j; i++, j--) { byte t = data[i]; data[i] = data[j]; data[j] = t; } }

    /** Java URLEncoder semantics with '+'→%20 and %7E→'~' — must match the signing oracle byte-for-byte. */
    static String encodeValue(String value) {
        StringBuilder out = new StringBuilder();
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            int c = item & 255;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '*' || c == '_') out.append((char) c);
            else if (c == ' ') out.append("%20");
            else if (c == '~') out.append('~');
            else out.append('%').append(String.format("%02X", c));
        }
        return out.toString();
    }
    static String encode(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(encodeValue(entry.getKey())).append('=').append(encodeValue(entry.getValue()));
        }
        return out.toString();
    }

    /**
     * v5 playback signing. Adjusts only _rticket (≤1024 tries) until the digest lands
     * on class 12; emits only x-khronos/x-helios/x-medusa (+Content-Type/x-ss-stub for bodies).
     */
    static Request signPlayback(String base, Map<String, String> values, byte[] body, long now) {
        long ts = now / 1000;
        Map<String, String> query = new LinkedHashMap<>(values);
        for (int offset = 0; offset < 1024; offset++) {
            query.put("_rticket", Long.toString(now + offset));
            String queryString = encode(query);
            if (branchClass(queryString, body, ts) == 12) {
                String url = base + "?" + queryString;
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("x-khronos", Long.toString(ts));
                headers.put("x-helios", helios(ts));
                headers.put("x-medusa", medusa(url, body, ts, values));
                if (body != null) {
                    headers.put("Content-Type", "application/json; charset=utf-8");
                    headers.put("x-ss-stub", hexText(md5(body)).toUpperCase());
                }
                return new Request(url, headers, body);
            }
        }
        throw new IllegalStateException("无法生成 v5 播放签名");
    }

    /** Placeholder kept for documentation parity; identity cdid stays a local UUID like the Kotlin twin. */
    static String newCdid() { return UUID.randomUUID().toString(); }
}
