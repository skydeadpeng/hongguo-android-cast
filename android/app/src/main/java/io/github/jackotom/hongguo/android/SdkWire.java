package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Wire primitives of the SDK playback route: protobuf, RC4, CRC-16/CCITT, the XTEA chain and zlib frames. */
final class SdkWire {
    private SdkWire() { }

    /** Append-only protobuf writer with the route's three field kinds; empty bytes are omitted. */
    static final class Proto {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Proto number(int field, long value) { varint(out, (long) field << 3); varint(out, value); return this; }
        Proto bytes(int field, byte[] value) {
            if (value == null || value.length == 0) return this;
            varint(out, ((long) field << 3) | 2); varint(out, value.length); out.write(value, 0, value.length); return this;
        }
        Proto text(int field, String value) { return bytes(field, value.getBytes(StandardCharsets.UTF_8)); }
        Proto floats(int field, float value) {
            varint(out, ((long) field << 3) | 5);
            int bits = Float.floatToRawIntBits(value);
            for (int shift = 0; shift < 32; shift += 8) out.write(bits >>> shift);
            return this;
        }
        Proto join(byte[] block) { out.write(block, 0, block.length); return this; }
        byte[] toByteArray() { return out.toByteArray(); }
    }

    /** One decoded protobuf row: encoding 0 keeps its varint in `value`, the others keep the bytes. */
    static final class Row {
        final int field, encoding; final long value; final byte[] bytes;
        Row(int field, int encoding, long value, byte[] bytes) { this.field = field; this.encoding = encoding; this.value = value; this.bytes = bytes; }
    }

    static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) { out.write((int) (value & 0x7f) | 0x80); value >>>= 7; }
        out.write((int) value);
    }

    /** Strict bounded protobuf reader; repeats are kept in wire order. */
    static List<Row> read(byte[] data, int limit) throws IOException {
        if (data == null || data.length > limit) throw new IOException("SDK 数据长度无效");
        List<Row> rows = new ArrayList<>();
        long[] cursor = {0};
        while (cursor[0] < data.length) {
            long tag = integer(data, cursor), number = tag >>> 3;
            int encoding = (int) (tag & 7);
            if (number < 1 || number >= 1L << 29) throw new IOException("SDK 字段号无效");
            long value = 0; byte[] bytes = null;
            if (encoding == 0) value = integer(data, cursor);
            else if (encoding == 1 || encoding == 2 || encoding == 5) {
                long size = encoding == 2 ? integer(data, cursor) : encoding == 1 ? 8 : 4;
                if (size > data.length - cursor[0]) throw new IOException("SDK 字段被截断");
                bytes = new byte[(int) size];
                System.arraycopy(data, (int) cursor[0], bytes, 0, (int) size); cursor[0] += size;
            } else throw new IOException("SDK 字段编码不支持");
            rows.add(new Row((int) number, encoding, value, bytes));
            if (rows.size() > 512) throw new IOException("SDK 字段过多");
        }
        return rows;
    }

    private static long integer(byte[] data, long[] cursor) throws IOException {
        long result = 0;
        for (int part = 0; part < 10; part++) {
            if (cursor[0] >= data.length) throw new IOException("SDK 整数被截断");
            int value = data[(int) cursor[0]++] & 255;
            if (part == 9 && value > 1) throw new IOException("SDK 整数溢出");
            result |= (long) (value & 127) << (7 * part);
            if (value < 128) return result;
        }
        throw new IOException("SDK 整数无效");
    }

    /** Standard RC4 over `data`, in place. */
    static void rc4(byte[] key, byte[] data) {
        if (key.length == 0) throw new IllegalArgumentException("SDK RC4 密钥为空");
        int[] schedule = new int[256];
        for (int index = 0; index < 256; index++) schedule[index] = index;
        for (int index = 0, mix = 0; index < 256; index++) {
            mix = (mix + schedule[index] + (key[index % key.length] & 255)) & 255;
            int swap = schedule[index]; schedule[index] = schedule[mix]; schedule[mix] = swap;
        }
        for (int index = 0, mix = 0, at = 0; at < data.length; at++) {
            index = (index + 1) & 255; mix = (mix + schedule[index]) & 255;
            int swap = schedule[index]; schedule[index] = schedule[mix]; schedule[mix] = swap;
            data[at] ^= (byte) schedule[(schedule[index] + schedule[mix]) & 255];
        }
    }

    /** CRC-16/CCITT-FALSE, what binascii.crc_hqx computes from zero. */
    static int crc16(byte[] data) {
        int crc = 0;
        for (byte value : data) {
            crc ^= (value & 255) << 8;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            crc &= 0xffff;
        }
        return crc;
    }

    /** The route's XTEA variant: a CBC chain whose cycle count follows the 4-byte nonce. */
    static byte[] xtea(byte[] data, byte[] key, byte[] nonce, boolean decrypt) throws IOException {
        if (data.length == 0 || data.length % 8 != 0 || key.length != 16 || nonce.length != 4) throw new IOException("SDK XTEA 参数无效");
        long[] words = new long[4];
        for (int index = 0; index < 4; index++) words[index] = be32(key, index * 4);
        int cycles = 32 + 8 * (int) (le32(nonce, 0) % 5);
        byte[] previous = new byte[8], out = new byte[data.length];
        System.arraycopy(nonce, 0, previous, 0, 4); putLe32(previous, 4, 0x20200427);
        for (int offset = 0; offset < data.length; offset += 8) {
            long[] block = new long[2];
            for (int half = 0; half < 2; half++) block[half] = be32(data, offset + half * 4) ^ (decrypt ? 0 : be32(previous, half * 4));
            for (int round = 0; round < cycles * 2; round++) {
                int step = decrypt ? cycles * 2 - 1 - round : round, target = step & 1;
                long total = ((long) ((step + 1) / 2) * 0x9e3779b9L) & 0xffffffffL;
                long other = block[1 - target];
                long mixed = ((((other << 4) ^ (other >>> 5)) + other) & 0xffffffffL) ^ ((total + words[(int) (total >>> (11 * target)) & 3]) & 0xffffffffL);
                block[target] = (block[target] + (decrypt ? -mixed : mixed)) & 0xffffffffL;
            }
            for (int half = 0; half < 2; half++) putBe32(out, offset + half * 4, block[half] ^ (decrypt ? be32(previous, half * 4) : 0));
            System.arraycopy(decrypt ? data : out, offset, previous, 0, 8);
        }
        return out;
    }

    static byte[] zlibCompress(byte[] plain, int level) throws IOException {
        Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(plain); deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[2048];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count == 0) break;
                out.write(buffer, 0, count);
            }
            if (!deflater.finished()) throw new IOException("SDK 压缩未完成");
            return out.toByteArray();
        } finally { deflater.end(); }
    }

    /** Inflates a zlib or raw-deflate stream that must decode to exactly `expected` bytes with no trailing input. */
    static byte[] inflate(byte[] data, int offset, int length, int expected, boolean raw) throws IOException {
        Inflater inflater = new Inflater(raw);
        try {
            inflater.setInput(data, offset, length);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[2048];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) break;
                if (out.size() + count > expected) throw new IOException("SDK 解压结果超长");
                out.write(buffer, 0, count);
            }
            if (!inflater.finished() || inflater.getRemaining() != 0 || out.size() != expected) throw new IOException("SDK 解压结果不完整");
            return out.toByteArray();
        } catch (DataFormatException invalid) { throw new IOException("SDK 压缩数据无效"); }
        finally { inflater.end(); }
    }

    static byte[] le32(long value) { byte[] out = new byte[4]; putLe32(out, 0, value); return out; }
    static byte[] le16(int value) { byte[] out = new byte[2]; out[0] = (byte) value; out[1] = (byte) (value >>> 8); return out; }
    static byte[] be32(long value) { byte[] out = new byte[4]; putBe32(out, 0, value); return out; }
    static void putLe32(byte[] out, int at, long value) { for (int index = 0; index < 4; index++) out[at + index] = (byte) (value >>> (8 * index)); }
    static void putBe32(byte[] out, int at, long value) { for (int index = 0; index < 4; index++) out[at + index] = (byte) (value >>> (8 * (3 - index))); }
    static void putBe16(byte[] out, int at, int value) { out[at] = (byte) (value >>> 8); out[at + 1] = (byte) value; }
    static long le32(byte[] data, int at) { long value = 0; for (int index = 3; index >= 0; index--) value = value << 8 | data[at + index] & 255; return value; }
    static long be32(byte[] data, int at) { long value = 0; for (int index = 0; index < 4; index++) value = value << 8 | data[at + index] & 255; return value; }
    static byte[] join(byte[]... arrays) { return DanmakuSigner.join(arrays); }
    static byte[] copy(byte[] data, int from, int to) { return java.util.Arrays.copyOfRange(data, from, to); }
}
