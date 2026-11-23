package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Signing primitives of the SDK playback route: the digest framing, Gorgon, the Medusa envelope and
 * the session exchange codec. Every function is pure over its inputs; randomness is supplied by the caller. */
final class SdkSigner {
    static final String SDK_VERSION = "v04.10.03-ml-android";
    static final long SDK_CODE = 0x040a0300L, LICENSE = 646637794L;
    static final int MAX_PLAIN = 65536;
    private static final long MASK = 0xffffffffL;
    private static final int[] SOURCE_BITS = {6, 5, 3, 4, 2, 7, 1, 0}, PACKED_BITS = {1, 7, 3, 6, 2, 0, 4, 5};
    private static final int[] ROUND_CONSTANTS = {0xbbb5de72, 0xd4638d5b, 0x92a5f73b, 0x89f8a1c9};
    private static final int[][] ROW_ORDERS = {{2, 0, 3, 1}, {1, 3, 2, 0}, {3, 2, 1, 0}, {2, 3, 1, 0}};
    private static final int[][] LAST_COLUMN = {{15, 7, 3, 11}, {11, 3, 15, 7}, {3, 15, 11, 7}, {15, 3, 11, 7}};
    private static final byte[] XTEA_SEED = "646637794".getBytes(StandardCharsets.US_ASCII);
    /** CRC-64 over the license text; the reference folds it byte by byte on every call. */
    private static final long LICENSE_CRC;

    static {
        long crc = 0;
        for (byte value : XTEA_SEED) {
            crc ^= value & 255L;
            for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0x95ac9329ac4bc9b5L : 0);
        }
        LICENSE_CRC = crc;
    }

    private SdkSigner() { }

    /** The native digest core: 52 bytes plus a selector and a second, answering 16 bytes. */
    interface Core { byte[] digest(byte[] payload, int selector, int seconds) throws Exception; }

    static final class Session {
        final String token; final int status;
        Session(String token, int status) { this.token = token; this.status = status; }
    }

    static long signed(long value) { return (value << 1) ^ (value >> 63); }

    static int selector(byte[] queryDigest, byte[] bodyDigest, int seconds) {
        long value = 0x20230928L;
        byte[][] parts = {queryDigest, bodyDigest, SdkWire.le32(seconds)};
        for (byte[] part : parts) for (int index = 0; index < part.length; index++) {
            int value0 = part[index] & 255;
            value = index % 2 != 0 ? ~(value ^ (value >>> 7) ^ ((value << 12) | value0)) & MASK
                                   : (value ^ (value >>> 4) ^ (value << 6) ^ value0) & MASK;
        }
        return (int) value;
    }

    static int checksum(byte[] value) {
        long result = 0x20220420L;
        for (int index = 0; index < 12; index++) {
            int item = value[index] & 255;
            result = index % 2 != 0 ? (result ^ (result >>> 5) ^ ~((result << 11) | item)) & MASK
                                    : (result ^ (result >>> 3) ^ (result << 7) ^ item) & MASK;
        }
        return (int) (((result | 4) ^ 0x01000000) & MASK);
    }

    /** The 20-byte body proof a signed request carries: the core's 16 bytes and their checksum. */
    static byte[] proof(byte[] query, byte[] body, int seconds, Core core) throws Exception {
        byte[] queryHash = DanmakuSigner.sm3(query), bodyHash = body.length > 0 ? DanmakuSigner.md5(body) : new byte[16];
        int mode = selector(queryHash, bodyHash, seconds) & 15;
        byte[] prefix = core.digest(SdkWire.join(queryHash, bodyHash, SdkWire.le32(seconds)), mode, seconds);
        if (prefix == null || prefix.length != 16) throw new IOException("SDK 摘要结果无效");
        return SdkWire.join(prefix, SdkWire.le32(checksum(prefix) | 0x20));
    }

    /** V5 Gorgon over the exact encoded query and body; `flags` are the first two bytes of the context prefix. */
    static String gorgon(byte[] query, byte[] body, int seconds, int nonce, byte[] flags) {
        if (flags == null || flags.length != 2 || nonce < 0 || nonce >= 1 << 16) throw new IllegalArgumentException("SDK Gorgon 参数无效");
        byte[] payload = SdkWire.join(Arrays.copyOf(md5(query), 4), body.length > 0 ? Arrays.copyOf(md5(body), 4) : new byte[4],
            new byte[4], DanmakuSigner.unhex("00030a04"), SdkWire.be32(seconds));
        byte[] key = {(byte) 0x4a, flags[0], (byte) 0x16, (byte) (nonce >> 8), (byte) 0x47, (byte) 0x6c, flags[1], (byte) nonce};
        SdkWire.rc4(key, payload);
        int[] mixed = new int[20];
        for (int index = 0; index < 20; index++) mixed[index] = payload[index] & 255;
        for (int index = 0; index < 20; index++) {
            int value = mixed[index], nibbles = ((value & 15) << 4) | (value >>> 4), difference = nibbles ^ mixed[(index + 1) % 20];
            mixed[index] = (~(Integer.reverse(difference) >>> 24 ^ 20)) & 255;
        }
        byte[] out = new byte[26];
        out[0] = (byte) 0x84; out[1] = 0x04; out[2] = (byte) nonce; out[3] = (byte) (nonce >>> 8); out[4] = flags[0]; out[5] = flags[1];
        for (int index = 0; index < 20; index++) out[6 + index] = (byte) mixed[index];
        return DanmakuSigner.hex(out);
    }

    /** The next X-Ladon state; the reference clears the allocation's low nibble before calling in. */
    static long advanceLadon(long previous, long allocation, int seconds) {
        return previous ^ Long.rotateLeft((allocation & ~15L) ^ LICENSE_CRC ^ (seconds & MASK), 4) & 0xffffffffL;
    }

    static byte[] scrambleKey(byte[] seed, int nonce) { return DanmakuSigner.sm3(SdkWire.join(seed, SdkWire.le32(nonce), seed)); }

    static byte[] scrambleMask(int nonce) {
        int high = (nonce >>> 16) & 255;
        return SdkWire.le32(~((((nonce >>> 5) & 0x7f800) | (nonce >>> 24)) ^ (high >>> 5) ^ high));
    }

    static byte[] scramble(byte[] payload, byte[] key) {
        int length = payload.length;
        int[] out = new int[length];
        for (int index = 0; index < length; index++) {
            int a = key[(index % 8) * 4] & 255, b = key[(index % 8) * 4 + 1] & 255;
            int middle = rot((~(rot(payload[index], 4) + a)) ^ b, 3);
            out[length - 1 - index] = ~((middle + b) ^ a) & 255;
        }
        int first = out[0], edge = out[length - 1] ^ out[length - 2];
        out[0] = (out[0] + (~edge & 255)) & 255;
        out[1] = (out[1] + ((out[length - 1] ^ out[0] ^ 0xfe) & 255)) & 255;
        out[2] = (out[2] + (((edge - first) & 255) ^ rot(out[1], 3) ^ 2)) & 255;
        for (int index = 3; index < length - 1; index++) out[index] = (out[index] + (~(out[index - 2] ^ rot(out[index - 1], 3) ^ index) & 255)) & 255;
        out[length - 1] ^= out[length - 2];
        int sum = 0;
        for (int index = 1; index < length; index++) sum += out[index];
        out[0] = ((out[0] ^ out[1]) + sum) & 255;
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) result[index] = (byte) out[index];
        return result;
    }

    private static int rot(int value, int bits) {
        value &= 255;
        return ((value << bits) | (value >>> (8 - bits))) & 255;
    }

    /** The route's own block cipher: two substitution/permutation rounds over altered key scheduling. */
    private static final class Block {
        private final int[] box = new int[256], rows, shift = new int[16];
        private final byte[][] keys = new byte[3][16];

        Block(byte[] key, byte[] substitution, int variant) {
            for (int index = 0; index < 256; index++) box[index] = substitution[index] & 255;
            rows = ROW_ORDERS[variant];
            for (int index = 0; index < 16; index++)
                shift[index] = index % 4 == 3 ? LAST_COLUMN[variant][index / 4] : (index + new int[]{0, 8, 12}[index % 4]) % 16;
            int constant = ROUND_CONSTANTS[variant];
            byte[][] words = new byte[12][4];
            for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) words[row][column] = (byte) (key[row * 4 + column] ^ constant >>> (8 * column));
            for (int index = 4; index < 12; index++) {
                byte[] previous = words[index - 1].clone();
                if (index % 4 == 0) {
                    byte[] rotated = {(byte) box[previous[1] & 255], (byte) box[previous[2] & 255], (byte) box[previous[3] & 255], (byte) box[previous[0] & 255]};
                    rotated[0] ^= (byte) (constant >>> ((2 * index) & 24));
                    previous = rotated;
                }
                for (int column = 0; column < 4; column++) words[index][column] = (byte) (words[index - 4][column] ^ previous[column]);
            }
            for (int round = 0; round < 3; round++) for (int word = 0; word < 4; word++)
                System.arraycopy(words[round * 4 + word], 0, keys[round], word * 4, 4);
        }

        byte[] block(byte[] input) {
            int[] state = addKey(input, keys[0]);
            for (int step = 1; step <= 2; step++) {
                int[] substituted = new int[16];
                for (int index = 0; index < 16; index++) substituted[index] = box[state[index]];
                int[] reordered = new int[16];
                for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) reordered[row * 4 + column] = substituted[rows[row] * 4 + column];
                int[] mixed = new int[16];
                for (int index = 0; index < 16; index++) mixed[index] = reordered[shift[index]];
                if (step == 1) {
                    int[] swapped = new int[16];
                    for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) swapped[row * 4 + column] = mixed[row * 4 + rows[column]];
                    int[] output = new int[16];
                    for (int column = 0; column < 4; column++) {
                        int total = swapped[column] ^ swapped[4 + column] ^ swapped[8 + column] ^ swapped[12 + column];
                        for (int row = 0; row < 4; row++) {
                            int value = swapped[row * 4 + column];
                            output[row * 4 + column] = value ^ total ^ twice(value ^ swapped[((row + 1) % 4) * 4 + column]);
                        }
                    }
                    mixed = output;
                }
                byte[] bytes = new byte[16];
                for (int index = 0; index < 16; index++) bytes[index] = (byte) mixed[index];
                state = addKey(bytes, keys[step]);
            }
            byte[] out = new byte[16];
            for (int index = 0; index < 16; index++) out[index] = (byte) (state[index] ^ keys[1][index]);
            return out;
        }

        private int[] addKey(byte[] state, byte[] key) {
            int[] out = new int[16];
            for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++)
                out[row * 4 + column] = (state[row * 4 + column] & 255) ^ key[row * 4 + rows[column]] & 255;
            return out;
        }

        private static int twice(int value) { return ((value << 1) ^ ((value & 128) != 0 ? 0x11b : 0)) & 255; }
    }

    /** Seals the sampled bits of a payload of at least 248 bytes into the 32-byte V5 wire block. */
    static byte[] compact(byte[] payload, int seconds, byte[] keyMaterial, byte[] tables) throws IOException {
        if (payload.length < 248 || keyMaterial.length != 32 || tables.length != 1024) throw new IOException("SDK 信封参数无效");
        byte[] sampled = new byte[32];
        for (int group = 0; group < 31; group++) {
            int value = 0;
            for (int bit = 0; bit < 8; bit++) value |= ((payload[group * 8 + bit] >>> SOURCE_BITS[bit]) & 1) << PACKED_BITS[bit];
            sampled[group] = (byte) value;
        }
        sampled[31] = 1;
        int variant = seconds & 3;
        Block cipher = new Block(md5(SdkWire.copy(keyMaterial, 0, 16)), SdkWire.copy(tables, variant * 256, variant * 256 + 256), variant);
        byte[] first = cipher.block(xor(SdkWire.copy(sampled, 0, 16), md5(SdkWire.copy(keyMaterial, 16, 32))));
        return SdkWire.join(first, cipher.block(xor(SdkWire.copy(sampled, 16, 32), first)));
    }

    /** X-Medusa of one signed request, as base64. */
    static String envelope(byte[] proto, byte[] queryDigest, byte[] bodyProof, int seconds, int keyNonce, int frameNonce,
                           byte[] seed, byte[] frame, byte[] prefix, byte[] tables) throws IOException {
        if (queryDigest.length != 32 || bodyProof.length != 20 || seed.length != 32 || frame.length != 16 || prefix.length != 8
            || tables.length != 1024 || proto.length < 229 || proto.length > 1024 * 1024) throw new IOException("SDK 信封参数无效");
        byte[] mask = scrambleMask(keyNonce);
        byte[] prefixed = SdkWire.join(prefix, scramble(proto, scrambleKey(seed, keyNonce)));
        byte[] mixed = new byte[prefixed.length];
        for (int index = 0; index < mixed.length; index++) mixed[index] = (byte) (prefixed[prefixed.length - 1 - index] ^ mask[(~index) & 3]);
        long flags = 0x18000001L | ((queryDigest[0] & 63) << 14) | ((bodyProof[0] & 63) << 8);
        byte[] payload = new byte[9 + mixed.length + 2];
        payload[0] = (byte) 0xca;
        SdkWire.putLe32(payload, 1, frameNonce); SdkWire.putLe32(payload, 5, flags);
        System.arraycopy(mixed, 0, payload, 9, mixed.length);
        System.arraycopy(SdkWire.le16(keyNonce >>> 16), 0, payload, payload.length - 2, 2);
        byte[] encrypted = compact(payload, seconds, seed, tables);
        for (int group = 0; group < 31; group++) for (int offset = 0; offset < 8; offset++) {
            int index = group * 8 + offset, bit = (encrypted[group] >>> PACKED_BITS[offset]) & 1;
            payload[index] = (byte) ((payload[index] & ~(1 << SOURCE_BITS[offset])) | bit << SOURCE_BITS[offset]);
        }
        byte[] out = new byte[25 + payload.length];
        SdkWire.putLe32(out, 0, seconds ^ 5);
        for (int index = 0; index < 16; index++) out[4 + index] = (byte) (frame[index] ^ seconds >>> (8 * (index % 4)));
        System.arraycopy(SdkWire.le16(keyNonce & 0xffff), 0, out, 20, 2);
        out[22] = 0; out[23] = 1; out[24] = encrypted[31];
        System.arraycopy(payload, 0, out, 25, payload.length);
        return Base64.getEncoder().encodeToString(out);
    }

    /** Seals the exchange plaintext: zlib, a CRC-framed container, the XTEA chain, then AES-CBC. */
    static byte[] seal(byte[] plaintext, byte[] common, byte[] inner, byte[] nonce, int entropy, int level) throws Exception {
        if (common.length != 32 || inner.length != 16 || nonce.length != 4 || plaintext.length > MAX_PLAIN) throw new IOException("SDK 交换密钥无效");
        byte[] compressed = SdkWire.zlibCompress(plaintext, level);
        compressed[0] = 0x78; compressed[1] = 0x01;
        byte[] container = SdkWire.join(SdkWire.le32(plaintext.length), compressed);
        int extra = Math.floorMod(-container.length, 8), position = 1 + Math.floorMod(extra - 1, 4);
        byte[] frame = new byte[container.length + 8 + extra];
        frame[0] = (byte) ((entropy & 0xf8) | extra);
        System.arraycopy(container, 0, frame, position, container.length);
        SdkWire.putBe16(frame, position >= 3 ? 1 : position + container.length, SdkWire.crc16(container));
        byte[] encrypted = SdkWire.xtea(frame, inner, nonce, false);
        byte[] wrapped = new byte[1 + encrypted.length + 4];
        wrapped[0] = (byte) (encrypted[0] ^ 3);
        System.arraycopy(encrypted, 0, wrapped, 1, encrypted.length);
        System.arraycopy(nonce, 0, wrapped, wrapped.length - 4, 4);
        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(SdkWire.copy(common, 0, 16), "AES"), new IvParameterSpec(SdkWire.copy(common, 16, 32)));
        return aes.doFinal(wrapped);
    }

    /** The exchange request wire: the sealed payload inside its protobuf frame. */
    static byte[] exchangeRequest(byte[] plaintext, long milliseconds, byte[] common, byte[] inner, byte[] nonce, int entropy, int level) throws Exception {
        return new SdkWire.Proto().number(1, 0x20200422L * 2).number(2, 2).number(3, 2)
            .bytes(4, seal(plaintext, common, inner, nonce, entropy, level)).number(5, milliseconds * 2).toByteArray();
    }

    /** Opens a sealed exchange frame; `request` selects the request framing (CRC-checked container). */
    static byte[] open(byte[] ciphertext, byte[] common, byte[] inner, boolean request) throws Exception {
        if (ciphertext.length == 0 || ciphertext.length % 16 != 0 || ciphertext.length > MAX_PLAIN * 2) throw new IOException("SDK 交换响应无效");
        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(SdkWire.copy(common, 0, 16), "AES"), new IvParameterSpec(SdkWire.copy(common, 16, 32)));
        byte[] wrapped = aes.doFinal(ciphertext);
        if (wrapped.length < 13 || (wrapped.length - 5) % 8 != 0 || (wrapped[0] ^ wrapped[1]) != 3) throw new IOException("SDK 交换响应帧无效");
        byte[] frame = SdkWire.xtea(SdkWire.copy(wrapped, 1, wrapped.length - 4), inner, SdkWire.copy(wrapped, wrapped.length - 4, wrapped.length), true);
        int extra = frame[0] & 7, length = frame.length - 8 - extra, position = 1 + Math.floorMod(extra - 1, 4);
        if (length < 4) throw new IOException("SDK 交换响应帧过短");
        byte[] container = SdkWire.copy(frame, position, position + length);
        int crcOffset = position >= 3 ? 1 : position + length;
        if (request && ((frame[crcOffset] & 255) != SdkWire.crc16(container) >>> 8 || (frame[crcOffset + 1] & 255) != (SdkWire.crc16(container) & 255)))
            throw new IOException("SDK 交换请求校验失败");
        long expected = SdkWire.le32(container, 0);
        if (expected > MAX_PLAIN) throw new IOException("SDK 交换响应过大");
        return SdkWire.inflate(container, 4, container.length - 4, (int) expected, false);
    }

    static Session answer(byte[] packet, byte[] common, byte[] inner) throws Exception {
        long header = -1, edition = -1; Long refusal = null; byte[] sealed = null;
        for (SdkWire.Row row : SdkWire.read(packet, MAX_PLAIN * 2)) {
            if (row.field == 1 || row.field == 2) {
                long seen = row.field == 1 ? header : edition;
                if (row.encoding != 0 || seen >= 0 && seen != row.value) throw new IOException("SDK 交换响应头无效");
                if (row.field == 1) header = row.value; else edition = row.value;
            } else if (row.field == 3) {
                if (row.encoding != 0) throw new IOException("SDK 交换状态无效");
                refusal = row.value;
            } else if (row.field == 6) {
                if (row.encoding != 2 || sealed != null) throw new IOException("SDK 交换响应密文无效");
                sealed = row.bytes;
            }
        }
        if (header != 0x20120312L * 2 || edition != 2) throw new IOException("SDK 交换响应头不符");
        if (sealed == null) throw new IOException(refusal == null ? "SDK 交换响应缺少密文" : "SDK 换取令牌被拒绝");
        byte[] token = null; Long status = null;
        for (SdkWire.Row row : SdkWire.read(open(sealed, common, inner, false), 8192)) {
            if (row.field == 1) { if (token != null || row.encoding != 2) throw new IOException("SDK 会话字段无效"); token = row.bytes; }
            else if (row.field == 2) { if (status != null || row.encoding != 0) throw new IOException("SDK 会话字段无效"); status = row.value; }
        }
        if (token == null || status == null) throw new IOException("SDK 会话字段不完整");
        long zigzag = status; int result = (int) ((zigzag >>> 1) ^ -(zigzag & 1));
        String text = new String(token, StandardCharsets.UTF_8);
        if (text.length() == 0 || text.length() > 256 || !text.chars().allMatch(c -> c >= 33 && c <= 127) || result != 200 && result != 202)
            throw new IOException("SDK 会话令牌无效");
        return new Session(text, result);
    }

    private static byte[] md5(byte[] data) {
        try { return DanmakuSigner.md5(data); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int index = 0; index < out.length; index++) out[index] = (byte) (a[index] ^ b[index]);
        return out;
    }
}
