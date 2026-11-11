package io.github.jackotom.hongguo.android;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Bounded non-fragmented ISO CENC AES-CTR. Caller exclusively owns an unpublished temporary file. */
final class CencDecryptor {
    private static final int MAX_SAMPLES = 250_000, MAX_BOXES = 100_000, MAX_SUBSAMPLES = 1_000_000;
    private static final long MAX_FILE = 96L * 1024 * 1024;
    private final RandomAccessFile file;
    private final long length;
    private int boxes, subsamples;
    private final List<Box> mdats = new ArrayList<>(), erase = new ArrayList<>();
    private final List<Sample> allSamples = new ArrayList<>(), encrypted = new ArrayList<>();
    private final List<long[]> auxRanges = new ArrayList<>();
    private final List<Description> descriptions = new ArrayList<>();

    private static final class Box {
        final long start, end, body; final String type;
        Box(long start, long end, long body, String type) { this.start = start; this.end = end; this.body = body; this.type = type; }
    }
    private static final class Description {
        final Box entry; final String codec; final int ivSize; final byte[] constantIv;
        Description(Box entry, String codec, int ivSize, byte[] constantIv) { this.entry = entry; this.codec = codec; this.ivSize = ivSize; this.constantIv = constantIv; }
    }
    private static final class Sample {
        final long offset; final int size; byte[] iv; int[] clear, cipher;
        Sample(long offset, int size) { this.offset = offset; this.size = size; }
    }
    private static IOException invalid(String reason) { return new IOException("CENC_INVALID: " + reason); }
    private static IOException unsupported(String reason) { return new IOException("CENC_UNSUPPORTED: " + reason); }
    private CencDecryptor(RandomAccessFile file) throws IOException { this.file = file; length = file.length(); }
    static void decryptInPlace(File input, byte[] key) throws Exception {
        if (input == null || key == null || key.length != 16) throw invalid("AES-128 key or temporary file");
        byte[] privateKey = key.clone();
        try (RandomAccessFile file = new RandomAccessFile(input, "rw")) {
            CencDecryptor decoder = new CencDecryptor(file);
            decoder.plan(); // No file writes until every box, sample and auxiliary record is validated.
            decoder.decrypt(privateKey);
            file.getFD().sync();
        } finally { Arrays.fill(privateKey, (byte) 0); }
    }
    private void bounds(long position, long size, long end) throws IOException {
        if (position < 0 || size < 0 || position > end || size > end - position || end > length) throw invalid("box/sample bounds");
    }
    private long u32(long position, long end) throws IOException { bounds(position, 4, end); file.seek(position); return file.readInt() & 0xffffffffL; }
    private long u64(long position, long end) throws IOException { bounds(position, 8, end); file.seek(position); long n = file.readLong(); if (n < 0) throw invalid("64-bit offset overflow"); return n; }
    private int u16(long position, long end) throws IOException { bounds(position, 2, end); file.seek(position); return file.readUnsignedShort(); }
    private int u8(long position, long end) throws IOException { bounds(position, 1, end); file.seek(position); return file.readUnsignedByte(); }
    private String type(long position, long end) throws IOException { byte[] b = bytes(position, 4, end); for (byte v : b) if ((v & 255) < 32 || (v & 255) > 126) throw invalid("box type"); return new String(b, StandardCharsets.US_ASCII); }
    private byte[] bytes(long position, int count, long end) throws IOException { bounds(position, count, end); byte[] result = new byte[count]; file.seek(position); file.readFully(result); return result; }
    private int count(long n) throws IOException { if (n > MAX_SAMPLES) throw invalid("sample/count limit"); return (int) n; }
    private void versionZero(Box box) throws IOException { if (u32(box.body, box.end) != 0) throw unsupported(box.type + " version/flags"); }
    private List<Box> children(long start, long end) throws IOException {
        List<Box> result = new ArrayList<>();
        while (start < end) {
            interrupted();
            if (++boxes > MAX_BOXES) throw invalid("box count limit");
            long size = u32(start, end), header = 8; String type = type(start + 4, end);
            if (size == 1) { size = u64(start + 8, end); header = 16; }
            else if (size == 0) size = end - start;
            if (size < header) throw invalid("box size");
            bounds(start, size, end);
            result.add(new Box(start, start + size, start + header, type)); start += size;
        }
        return result;
    }
    private Box one(List<Box> boxes, String type, boolean required) throws IOException {
        Box result = null;
        for (Box box : boxes) if (box.type.equals(type)) { if (result != null) throw invalid("duplicate " + type); result = box; }
        if (result == null && required) throw invalid("missing " + type);
        return result;
    }
    private List<Box> nested(Box parent) throws IOException { return children(parent.body, parent.end); }
    private void plan() throws Exception {
        if (length < 24 || length > MAX_FILE) throw invalid("file size limit");
        List<Box> top = children(0, length);
        if (one(top, "moof", false) != null) throw unsupported("fragmented MP4");
        Box moov = one(top, "moov", true);
        for (Box box : top) { if (box.type.equals("mdat")) mdats.add(box); if (box.type.equals("pssh")) erase.add(box); }
        if (mdats.isEmpty()) throw invalid("missing media data");
        List<Box> movie = nested(moov);
        if (one(movie, "mvex", false) != null) throw unsupported("fragment defaults");
        for (Box box : movie) {
            if (box.type.equals("pssh")) erase.add(box);
            if (!box.type.equals("trak")) continue;
            Box mdia = one(nested(box), "mdia", true);
            Box minf = one(nested(mdia), "minf", true);
            Box stbl = one(nested(minf), "stbl", true);
            track(stbl);
        }
        if (encrypted.isEmpty()) throw invalid("no encrypted samples");
        allSamples.sort(Comparator.comparingLong(sample -> sample.offset));
        long previousEnd = -1;
        for (Sample sample : allSamples) if (sample.size > 0) {
            if (sample.offset < previousEnd) throw invalid("overlapping media samples");
            previousEnd = sample.offset + sample.size;
        }
        auxRanges.sort(Comparator.comparingLong(range -> range[0]));
        previousEnd = -1;
        for (long[] range : auxRanges) {
            if (range[0] < previousEnd) throw invalid("overlapping auxiliary records");
            previousEnd = range[1];
        }
        int sampleIndex = 0;
        for (long[] range : auxRanges) {
            while (sampleIndex < allSamples.size() && (allSamples.get(sampleIndex).size == 0 || allSamples.get(sampleIndex).offset + allSamples.get(sampleIndex).size <= range[0])) sampleIndex++;
            if (sampleIndex < allSamples.size() && allSamples.get(sampleIndex).size > 0 && allSamples.get(sampleIndex).offset < range[1]) throw invalid("auxiliary/sample overlap");
        }
    }
    private void track(Box stbl) throws Exception {
        List<Box> table = nested(stbl);
        Box stsd = one(table, "stsd", true); versionZero(stsd);
        int entries = count(u32(stsd.body + 4, stsd.end));
        List<Box> sampleEntries = children(stsd.body + 8, stsd.end);
        if (sampleEntries.size() != entries || entries == 0) throw invalid("sample description count");
        Description description = null;
        for (Box entry : sampleEntries) if (entry.type.equals("encv") || entry.type.equals("enca")) {
            if (entries != 1) throw unsupported("multiple sample descriptions");
            description = description(entry); descriptions.add(description);
        }
        Box sizes = one(table, "stsz", false), mapping = one(table, "stsc", false);
        Box chunks = one(table, "stco", false), wideChunks = one(table, "co64", false);
        if (description == null && (sizes == null || mapping == null || chunks == null && wideChunks == null)) return;
        if (sizes == null || mapping == null || chunks == null && wideChunks == null || chunks != null && wideChunks != null) throw invalid("sample table missing/ambiguous");
        List<Sample> samples = samples(sizes, mapping, chunks != null ? chunks : wideChunks, entries);
        if (allSamples.size() > MAX_SAMPLES - samples.size()) throw invalid("total sample limit");
        allSamples.addAll(samples);
        if (description == null) return;
        for (Box box : table) {
            if (box.type.equals("sgpd") || box.type.equals("sbgp")) if (type(box.body + 4, box.end).equals("seig")) throw unsupported("sample encryption groups/key rotation");
        }
        Box senc = one(table, "senc", false), saiz = one(table, "saiz", false), saio = one(table, "saio", false);
        if (senc != null) {
            long full = u32(senc.body, senc.end);
            if ((full & ~2L) != 0) throw unsupported("senc version/override flags");
            if (u32(senc.body + 4, senc.end) != samples.size()) throw invalid("senc sample count");
            long at = senc.body + 8;
            for (Sample sample : samples) at = encryption(sample, description, at, senc.end, (full & 2) != 0, false);
            if (at != senc.end) throw invalid("senc record length");
            erase.add(senc);
        } else if (saiz != null && saio != null) auxiliary(samples, description, saiz, saio, mapping, chunks != null ? chunks : wideChunks);
        else throw invalid("missing sample encryption data");
        if (saiz != null) erase.add(saiz);
        if (saio != null) erase.add(saio);
        encrypted.addAll(samples);
    }
    private Description description(Box entry) throws Exception {
        int base = entry.type.equals("encv") ? 78 : 28;
        bounds(entry.body, base, entry.end);
        if (entry.type.equals("enca") && u16(entry.body + 8, entry.end) != 0) throw unsupported("QuickTime audio sample version");
        List<Box> extra = children(entry.body + base, entry.end);
        Box sinf = one(extra, "sinf", true); List<Box> info = nested(sinf);
        Box frma = one(info, "frma", true), schm = one(info, "schm", true), schi = one(info, "schi", true);
        if (frma.end - frma.body != 4) throw invalid("original codec length");
        String codec = type(frma.body, frma.end);
        if (codec.equals("encv") || codec.equals("enca") || codec.equals("free")) throw invalid("original codec");
        versionZero(schm);
        if (!type(schm.body + 4, schm.end).equals("cenc")) throw unsupported("encryption scheme");
        bounds(schm.body, 12, schm.end);
        Box tenc = one(nested(schi), "tenc", true);
        long full = u32(tenc.body, tenc.end); int version = (int) (full >>> 24);
        if (version > 1 || (full & 0xffffff) != 0) throw unsupported("tenc version/flags");
        if (u8(tenc.body + 4, tenc.end) != 0 || u8(tenc.body + 5, tenc.end) != 0) throw unsupported("encryption pattern/reserved bits");
        if (u8(tenc.body + 6, tenc.end) != 1) throw unsupported("mixed unprotected samples");
        int ivSize = u8(tenc.body + 7, tenc.end); bounds(tenc.body + 8, 16, tenc.end);
        byte[] constant = null; long end = tenc.body + 24;
        if (ivSize == 0) {
            int constantSize = u8(end++, tenc.end);
            if (constantSize != 8 && constantSize != 16) throw invalid("constant IV size");
            constant = bytes(end, constantSize, tenc.end); end += constantSize;
        } else if (ivSize != 8 && ivSize != 16) throw invalid("per-sample IV size");
        if (end != tenc.end) throw invalid("tenc length");
        erase.add(sinf);
        return new Description(entry, codec, ivSize, constant);
    }
    private List<Sample> samples(Box stsz, Box stsc, Box chunkBox, int descriptions) throws IOException {
        versionZero(stsz); versionZero(stsc); versionZero(chunkBox);
        int fixed = countSize(u32(stsz.body + 4, stsz.end)); int count = count(u32(stsz.body + 8, stsz.end));
        if (count == 0 || stsz.end - stsz.body != 12L + (fixed == 0 ? count * 4L : 0)) throw invalid("sample size table length");
        int[] sizes = new int[count];
        for (int i = 0; i < count; i++) sizes[i] = fixed == 0 ? countSize(u32(stsz.body + 12 + 4L * i, stsz.end)) : fixed;
        int chunks = count(u32(chunkBox.body + 4, chunkBox.end)), width = chunkBox.type.equals("co64") ? 8 : 4;
        if (chunks == 0 || chunkBox.end - chunkBox.body != 8L + (long) chunks * width) throw invalid("chunk offset table length");
        int mappings = count(u32(stsc.body + 4, stsc.end));
        if (mappings == 0 || mappings > chunks || stsc.end - stsc.body != 8L + mappings * 12L) throw invalid("sample/chunk map length");
        int[] first = new int[mappings], perChunk = new int[mappings];
        for (int i = 0; i < mappings; i++) {
            long at = stsc.body + 8 + 12L * i;
            first[i] = count(u32(at, stsc.end)); perChunk[i] = count(u32(at + 4, stsc.end));
            long desc = u32(at + 8, stsc.end);
            if (first[i] < 1 || first[i] > chunks || i == 0 && first[i] != 1 || i > 0 && first[i] <= first[i - 1] || perChunk[i] < 1 || desc < 1 || desc > descriptions) throw invalid("sample/chunk mapping");
        }
        List<Sample> result = new ArrayList<>(count); int map = 0, sample = 0;
        for (int chunk = 1; chunk <= chunks; chunk++) {
            while (map + 1 < mappings && first[map + 1] <= chunk) map++;
            long at = chunkBox.body + 8 + (long) (chunk - 1) * width;
            long offset = width == 8 ? u64(at, chunkBox.end) : u32(at, chunkBox.end);
            for (int j = 0; j < perChunk[map]; j++) {
                if (sample >= count) throw invalid("too many mapped samples");
                int size = sizes[sample++]; bounds(offset, size, length);
                if (!insideMdat(offset, size)) throw invalid("sample outside mdat");
                result.add(new Sample(offset, size)); offset += size;
            }
        }
        if (sample != count) throw invalid("incomplete sample/chunk mapping");
        return result;
    }
    private boolean insideMdat(long offset, int size) {
        // Top-level boxes are ordered; avoid samples * mdat-count work on untrusted containers.
        int low = 0, high = mdats.size() - 1, found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (mdats.get(middle).body <= offset) { found = middle; low = middle + 1; } else high = middle - 1;
        }
        if (found < 0) return false;
        Box mdat = mdats.get(found);
        return offset <= mdat.end && size <= mdat.end - offset;
    }
    private int countSize(long size) throws IOException { if (size > MAX_FILE) throw invalid("sample size limit"); return (int) size; }
    private long auxiliaryHeader(Box box, boolean offsets) throws IOException {
        long full = u32(box.body, box.end); int version = (int) (full >>> 24), flags = (int) full & 0xffffff;
        if ((flags & ~1) != 0 || version > (offsets ? 1 : 0)) throw unsupported(box.type + " version/flags");
        long at = box.body + 4;
        if ((flags & 1) != 0) {
            if (!type(at, box.end).equals("cenc") || u32(at + 4, box.end) != 0) throw unsupported("auxiliary scheme");
            at += 8;
        }
        return at;
    }
    private void auxiliary(List<Sample> samples, Description description, Box saiz, Box saio, Box stsc, Box chunks) throws IOException {
        long at = auxiliaryHeader(saiz, false); int fixed = u8(at++, saiz.end);
        if (u32(at, saiz.end) != samples.size()) throw invalid("saiz sample count"); at += 4;
        if (saiz.end - at != (fixed == 0 ? samples.size() : 0)) throw invalid("saiz sizes length");
        int[] sizes = new int[samples.size()];
        for (int i = 0; i < sizes.length; i++) sizes[i] = fixed == 0 ? u8(at++, saiz.end) : fixed;
        at = auxiliaryHeader(saio, true); int offsets = count(u32(at, saio.end)); at += 4;
        int width = u8(saio.body, saio.end) == 0 ? 4 : 8;
        if (saio.end - at != (long) offsets * width || offsets == 0) throw invalid("saio offsets length");
        int chunkCount = count(u32(chunks.body + 4, chunks.end));
        if (offsets != 1 && offsets != chunkCount) throw unsupported("auxiliary offsets not contiguous/per-chunk");
        int sample = 0, map = 0, maps = count(u32(stsc.body + 4, stsc.end));
        for (int i = 0; i < offsets; i++) {
            long offset = width == 8 ? u64(at, saio.end) : u32(at, saio.end); at += width;
            int n = samples.size();
            if (offsets != 1) {
                while (map + 1 < maps && u32(stsc.body + 8 + 12L * (map + 1), stsc.end) <= i + 1) map++;
                n = count(u32(stsc.body + 12 + 12L * map, stsc.end));
            }
            for (int j = 0; j < n; j++) {
                if (sample >= samples.size()) throw invalid("auxiliary chunk sample count");
                int size = sizes[sample]; bounds(offset, size, length);
                long end = offset + size;
                encryption(samples.get(sample++), description, offset, end, size > description.ivSize, true);
                if (size > 0) auxRanges.add(new long[]{offset, end}); offset = end;
            }
        }
        if (sample != samples.size()) throw invalid("auxiliary samples incomplete");
    }
    private long encryption(Sample sample, Description description, long at, long end, boolean hasSubsamples, boolean exact) throws IOException {
        sample.iv = new byte[16];
        byte[] iv = description.ivSize == 0 ? description.constantIv : bytes(at, description.ivSize, end);
        System.arraycopy(iv, 0, sample.iv, 0, iv.length); at += description.ivSize;
        if (hasSubsamples) {
            int count = u16(at, end); at += 2;
            if (subsamples > MAX_SUBSAMPLES - count) throw invalid("subsample count limit"); subsamples += count;
            if (count > 0) {
                sample.clear = new int[count]; sample.cipher = new int[count]; long sum = 0;
                for (int i = 0; i < count; i++) {
                    sample.clear[i] = u16(at, end); sample.cipher[i] = countSize(u32(at + 2, end)); at += 6;
                    sum += (long) sample.clear[i] + sample.cipher[i];
                    if (sum > sample.size) throw invalid("subsamples exceed sample");
                }
                if (sum != sample.size) throw invalid("subsamples do not cover sample");
            }
        }
        if (exact && at != end) throw invalid("auxiliary record size");
        return at;
    }
    private static void interrupted() throws IOException { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("CENC_CANCELLED"); }
    private void decrypt(byte[] key) throws Exception {
        byte[] buffer = new byte[32 * 1024], output = new byte[32 * 1024 + 16];
        SecretKeySpec aes = new SecretKeySpec(key, "AES");
        for (Sample sample : encrypted) {
            interrupted();
            Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding"); ctr.init(Cipher.DECRYPT_MODE, aes, new IvParameterSpec(sample.iv));
            long at = sample.offset;
            if (sample.clear == null) crypt(ctr, at, sample.size, buffer, output);
            else for (int i = 0; i < sample.clear.length; i++) { at += sample.clear[i]; crypt(ctr, at, sample.cipher[i], buffer, output); at += sample.cipher[i]; }
            if (ctr.doFinal().length != 0) throw invalid("unexpected AES-CTR buffering");
        }
        // These patches keep all box sizes/chunk offsets and the file length exactly unchanged.
        for (Description description : descriptions) { file.seek(description.entry.start + 4); file.write(description.codec.getBytes(StandardCharsets.US_ASCII)); }
        Arrays.fill(buffer, (byte) 0);
        for (Box box : erase) {
            interrupted(); file.seek(box.start + 4); file.write("free".getBytes(StandardCharsets.US_ASCII));
            long at = box.body;
            while (at < box.end) { int n = (int) Math.min(buffer.length, box.end - at); file.seek(at); file.write(buffer, 0, n); at += n; }
        }
        if (file.length() != length) throw invalid("file length changed");
    }
    private void crypt(Cipher ctr, long offset, int length, byte[] buffer, byte[] output) throws Exception {
        int remaining = length;
        while (remaining > 0) {
            interrupted(); int n = Math.min(buffer.length, remaining);
            file.seek(offset); file.readFully(buffer, 0, n);
            int written = ctr.update(buffer, 0, n, output, 0);
            if (written != n) throw invalid("unsupported AES-CTR provider buffering");
            file.seek(offset); file.write(output, 0, written); offset += n; remaining -= n;
        }
    }
}
