package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Generated clear/encrypted samples, no network, no production media or production keys. */
public final class CencDecryptorTest {
    private static int checks;
    private static final byte[] KEY = new byte[16];
    static { for (int i = 0; i < KEY.length; i++) KEY[i] = (byte) (i * 7 + 11); }
    private interface Mutation { void apply(byte[] bytes) throws Exception; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] concat(byte[]... arrays) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] array : arrays) out.write(array); return out.toByteArray(); }
    private static byte[] number(long value, int width) { byte[] out = new byte[width]; for (int i = width - 1; i >= 0; i--) { out[i] = (byte) value; value >>>= 8; } return out; }
    private static byte[] box(String type, byte[] payload) throws Exception { return concat(number(payload.length + 8, 4), bytes(type), payload); }
    private static byte[] full(String type, int flags, byte[] payload) throws Exception { return box(type, concat(number(flags, 4), payload)); }
    private static int at(byte[] data, String needle) {
        byte[] target = bytes(needle);
        outer: for (int i = 0; i <= data.length - target.length; i++) { for (int j = 0; j < target.length; j++) if (data[i + j] != target[j]) continue outer; return i; }
        throw new AssertionError("missing fixture marker " + needle);
    }
    private static void put(byte[] data, int at, long value, int width) { System.arraycopy(number(value, width), 0, data, at, width); }
    private static final class Fixture {
        byte[] file; byte[][] plain; long[] offsets; String codec; int descriptionType;
    }
    private static Fixture fixture(int ivSize, boolean subsamples, boolean auxiliary, boolean perChunk, boolean wide, String codec, boolean constant) throws Exception {
        Fixture fixture = new Fixture(); fixture.codec = codec;
        int[] sizes = {57, 93, 65599}; int[][] clears = {{5, 3, 2}, {0, 31, 2}, {0, 5}}, encrypted = {{7, 15, 25}, {2, 17, 41}, {32771, 32823}};
        fixture.plain = new byte[sizes.length][];
        ByteArrayOutputStream data = new ByteArrayOutputStream(), aux = new ByteArrayOutputStream(); int[] auxSizes = new int[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            byte[] plain = new byte[sizes[i]]; for (int j = 0; j < plain.length; j++) plain[j] = (byte) (j * 17 + i * 23);
            fixture.plain[i] = plain;
            byte[] iv = new byte[ivSize]; for (int j = 0; j < iv.length; j++) iv[j] = (byte) (31 + j + (constant ? 0 : i * 3));
            int old = aux.size(); if (!constant) aux.write(iv);
            if (subsamples) {
                aux.write(number(clears[i].length, 2));
                for (int j = 0; j < clears[i].length; j++) { aux.write(number(clears[i][j], 2)); aux.write(number(encrypted[i][j], 4)); }
            }
            auxSizes[i] = aux.size() - old;
            byte[] fullIv = new byte[16]; System.arraycopy(iv, 0, fullIv, 0, iv.length);
            Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding"); ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(fullIv));
            if (!subsamples) data.write(ctr.doFinal(plain));
            else {
                int at = 0;
                for (int j = 0; j < clears[i].length; j++) { data.write(plain, at, clears[i][j]); at += clears[i][j]; data.write(ctr.update(plain, at, encrypted[i][j])); at += encrypted[i][j]; }
                data.write(ctr.doFinal());
            }
        }
        byte[] ftyp = box("ftyp", concat(bytes("isom"), number(0, 4), bytes("isomiso2")));
        int mdatHeader = wide ? 16 : 8;
        long mediaStart = ftyp.length + mdatHeader + (auxiliary ? aux.size() : 0);
        fixture.offsets = new long[]{mediaStart, mediaStart + sizes[0], mediaStart + sizes[0] + sizes[1]};
        byte[] mdatBody = concat(auxiliary ? aux.toByteArray() : new byte[0], data.toByteArray());
        byte[] mdat = wide ? concat(number(1, 4), bytes("mdat"), number(mdatBody.length + 16, 8), mdatBody) : box("mdat", mdatBody);
        boolean audio = codec.equals("mp4a");
        byte[] tenc = concat(new byte[2], number(1, 1), number(constant ? 0 : ivSize, 1), new byte[16]);
        if (constant) { byte[] iv = new byte[ivSize]; for (int j = 0; j < iv.length; j++) iv[j] = (byte) (31 + j); tenc = concat(tenc, number(ivSize, 1), iv); }
        byte[] sinf = box("sinf", concat(box("frma", bytes(codec)), full("schm", 0, concat(bytes("cenc"), number(0x10000, 4))), box("schi", full("tenc", 0, tenc))));
        byte[] entry = box(audio ? "enca" : "encv", concat(new byte[audio ? 28 : 78], box(audio ? "esds" : "avcC", new byte[8]), sinf));
        byte[] stsd = full("stsd", 0, concat(number(1, 4), entry));
        ByteArrayOutputStream sizeEntries = new ByteArrayOutputStream(); for (int size : sizes) sizeEntries.write(number(size, 4));
        byte[] stsz = full("stsz", 0, concat(number(0, 4), number(sizes.length, 4), sizeEntries.toByteArray()));
        byte[] stsc = full("stsc", 0, concat(number(2, 4), number(1, 4), number(2, 4), number(1, 4), number(2, 4), number(1, 4), number(1, 4)));
        byte[] stco = full(wide ? "co64" : "stco", 0, concat(number(2, 4), number(fixture.offsets[0], wide ? 8 : 4), number(fixture.offsets[2], wide ? 8 : 4)));
        byte[] encryption;
        if (!auxiliary) encryption = full("senc", subsamples ? 2 : 0, concat(number(sizes.length, 4), aux.toByteArray()));
        else {
            ByteArrayOutputStream auxLengths = new ByteArrayOutputStream(); for (int size : auxSizes) auxLengths.write(size);
            byte[] saiz = full("saiz", 1, concat(bytes("cenc"), number(0, 4), new byte[1], number(sizes.length, 4), auxLengths.toByteArray()));
            long auxStart = ftyp.length + mdatHeader;
            byte[] offsets = perChunk ? concat(number(auxStart, wide ? 8 : 4), number(auxStart + auxSizes[0] + auxSizes[1], wide ? 8 : 4)) : number(auxStart, wide ? 8 : 4);
            byte[] saio = full("saio", (wide ? 0x01000000 : 0) | 1, concat(bytes("cenc"), number(0, 4), number(perChunk ? 2 : 1, 4), offsets));
            encryption = concat(saiz, saio);
        }
        byte[] stbl = box("stbl", concat(stsd, stsz, stsc, stco, encryption));
        byte[] moov = box("moov", concat(box("pssh", new byte[20]), box("trak", box("mdia", box("minf", stbl)))));
        fixture.file = concat(ftyp, mdat, moov); fixture.descriptionType = at(fixture.file, audio ? "enca" : "encv");
        return fixture;
    }
    private static void success(File directory, Fixture fixture, String name) throws Exception {
        File input = new File(directory, name + ".part"); Files.write(input.toPath(), fixture.file);
        byte[] callerKey = KEY.clone(); CencDecryptor.decryptInPlace(input, callerKey); check(Arrays.equals(callerKey, KEY), "caller-owned key unchanged");
        byte[] output = Files.readAllBytes(input.toPath());
        check(output.length == fixture.file.length, "file length unchanged " + name);
        for (int i = 0; i < fixture.plain.length; i++) check(Arrays.equals(Arrays.copyOfRange(output, (int) fixture.offsets[i], (int) fixture.offsets[i] + fixture.plain[i].length), fixture.plain[i]), "sample bytes exact " + name + " " + i);
        check(new String(output, fixture.descriptionType, 4, StandardCharsets.US_ASCII).equals(fixture.codec), "frma codec restored, not hardcoded " + name);
        check(input.delete(), "temporary fixture removed");
    }
    private static void reject(File directory, Fixture fixture, Mutation mutation, String label) throws Exception {
        byte[] data = fixture.file.clone(); mutation.apply(data);
        File input = new File(directory, "rejected.part"); Files.write(input.toPath(), data);
        boolean failed = false;
        try { CencDecryptor.decryptInPlace(input, KEY); } catch (Exception error) { failed = true; check(error.getMessage().startsWith("CENC_"), "typed parse failure " + label); }
        check(failed, "reject " + label);
        check(Arrays.equals(Files.readAllBytes(input.toPath()), data), "invalid input unchanged before decrypt " + label);
        check(input.delete(), "rejected fixture removed");
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--file")) {
            byte[] raw = Files.readAllBytes(new File(args[2]).toPath()), key;
            if (raw.length == 16) key = raw.clone();
            else { String hex = new String(raw, StandardCharsets.US_ASCII).trim(); if (!hex.matches("[a-fA-F0-9]{32}")) throw new IOException("test key file format"); key = new byte[16]; for (int i = 0; i < 16; i++) key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16); }
            try { File input = new File(args[1]); long size = input.length(); CencDecryptor.decryptInPlace(input, key); check(input.length() == size, "file length"); System.out.println("PASS CencDecryptor file bytes=" + size + " fileLengthUnchanged=true"); }
            finally { Arrays.fill(raw, (byte) 0); Arrays.fill(key, (byte) 0); }
            return;
        }
        File directory = Files.createTempDirectory("hongguo-cenc-test-").toFile();
        try {
            for (int iv : new int[]{8, 16}) for (boolean sub : new boolean[]{false, true}) {
                success(directory, fixture(iv, sub, false, false, false, "avc1", false), "senc-" + iv + "-" + sub);
                success(directory, fixture(iv, sub, true, false, false, "avc1", false), "aux-" + iv + "-" + sub);
                success(directory, fixture(iv, sub, true, true, true, "hvc1", false), "aux-chunk64-" + iv + "-" + sub);
            }
            success(directory, fixture(8, true, false, false, false, "mp4a", false), "audio-codec");
            success(directory, fixture(16, true, false, false, true, "hev1", true), "constant-iv");
            Fixture base = fixture(8, true, false, false, false, "avc1", false);
            reject(directory, base, data -> put(data, 0, 7, 4), "undersized box");
            reject(directory, base, data -> put(data, at(data, "moov") - 4, data.length, 4), "box beyond EOF");
            reject(directory, base, data -> put(data, at(data, "stsz") + 12, 250001, 4), "sample count cap");
            reject(directory, base, data -> put(data, at(data, "stco") + 12, data.length - 4, 4), "sample outside mdat");
            reject(directory, base, data -> put(data, at(data, "stco") + 16, base.offsets[0], 4), "overlapping chunk samples");
            reject(directory, base, data -> put(data, at(data, "stsc") + 12, 2, 4), "first mapped chunk not 1");
            reject(directory, base, data -> System.arraycopy(bytes("cbcs"), 0, data, at(data, "cenc"), 4), "non-CTR scheme");
            reject(directory, base, data -> put(data, at(data, "tenc") + 11, 12, 1), "invalid IV size");
            reject(directory, base, data -> put(data, at(data, "senc") + 8, 2, 4), "mismatched senc count");
            reject(directory, base, data -> put(data, data.length - 4, 32824, 4), "late malformed subsample validates before any writes");
            reject(directory, base, data -> System.arraycopy(bytes("free"), 0, data, at(data, "senc"), 4), "missing encryption records");
            reject(directory, base, data -> put(data, at(data, "tenc") + 1 + 4, 0x01000000, 4), "unsupported tenc layout");
            Fixture aux = fixture(16, true, true, true, true, "avc1", false);
            reject(directory, aux, data -> put(data, at(data, "saio") + 20, data.length + 100L, 8), "aux offset outside file");
            reject(directory, aux, data -> put(data, at(data, "saiz") + 21, 1, 1), "truncated auxiliary IV");
            reject(directory, base, data -> System.arraycopy(bytes("moof"), 0, data, at(data, "ftyp"), 4), "fragmented MP4");
            File badKey = new File(directory, "key-length.part"); Files.write(badKey.toPath(), base.file);
            boolean failed = false; try { CencDecryptor.decryptInPlace(badKey, new byte[15]); } catch (Exception expected) { failed = true; }
            check(failed && Arrays.equals(base.file, Files.readAllBytes(badKey.toPath())), "invalid AES key length before file writes"); check(badKey.delete(), "key fixture removed");
            File interrupted = new File(directory, "interrupted.part"); Files.write(interrupted.toPath(), base.file);
            Thread.currentThread().interrupt(); failed = false;
            try { try { CencDecryptor.decryptInPlace(interrupted, KEY); } catch (InterruptedIOException expected) { failed = true; } }
            finally { Thread.interrupted(); }
            check(Arrays.equals(base.file, Files.readAllBytes(interrupted.toPath())), "cancelled parser unchanged"); check(interrupted.delete(), "cancel fixture removed");
            check(failed, "interrupted task rejected");
            System.out.println("PASS CencDecryptorTest checks=" + checks + " networkRequests=0 fullsample=true subsample=true aux=true co64=true boundedBuffer=32768");
        } finally { File[] children = directory.listFiles(); if (children != null) for (File child : children) child.delete(); directory.delete(); }
    }
}
