package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Production verifier with deterministic Android API doubles, not a decoder test.
 * The synthetic box fixtures below exercise boundaries only, never playback QA.
 */
public final class PublicPlaybackVerifierTest {
    static File work, source;
    static int passed;
    static boolean active;
    static String boxMode;
    static final double EXPECTED = 139.034;

    interface Action { void run() throws Exception; }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void reset() throws Exception {
        active = true; boxMode = "";
        MediaExtractor.mode = ""; MediaExtractor.hook = "";
        MediaExtractor.released = false; MediaExtractor.opened = false;
        MediaExtractor.actual = null; MediaExtractor.width = 360; MediaExtractor.height = 640;
        MediaExtractor.formattedDuration = (long) (EXPECTED * 1_000_000);
        packets(EXPECTED, EXPECTED);
        writeFixture();
    }
    static void packets(double video, double audio) {
        List<Packet> rows = new ArrayList<>();
        if (video > 0) {
            for (int i = 0; i < (int) Math.round(video * 30); i++)
                rows.add(new Packet(0, Math.round(i * 1_000_000.0 / 30), 16, i == 0 ? 1 : 0, 0));
        }
        if (audio > 0) {
            // Real AAC commonly has negative timestamps for encoder priming.
            for (long t = -46_440; t < audio * 1_000_000; t += 23_220)
                rows.add(new Packet(video > 0 ? 1 : 0, t, 16, 1, 0));
        }
        rows.sort(Comparator.comparingLong(p -> p.time));
        // Reorder video PTS into a B-frame-like decode order, preserving the first sync.
        List<Integer> positions = new ArrayList<>();
        if (video > 0) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).track == 0) positions.add(i);
            for (int i = 1; i + 1 < positions.size(); i += 2)
                Collections.swap(rows, positions.get(i), positions.get(i + 1));
        }
        MediaExtractor.packets = rows;
        MediaExtractor.mimes = video > 0 ? (audio > 0
                ? new String[]{"video/avc", "audio/mp4a-latm"} : new String[]{"video/avc"})
                : new String[]{"audio/mp4a-latm"};
    }
    static byte[] bytes(int... values) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        for (int value : values) data.writeInt(value);
        return out.toByteArray();
    }
    static byte[] join(byte[]... values) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] value : values) out.write(value);
        return out.toByteArray();
    }
    static byte[] box(String name, byte[]... values) throws Exception {
        byte[] payload = join(values);
        return join(bytes(payload.length + 8), name.getBytes("US-ASCII"), payload);
    }
    static byte[] track(int index) throws Exception {
        boolean video = MediaExtractor.mimes[index].startsWith("video/");
        int count = 0;
        for (Packet p : MediaExtractor.packets) if (p.track == index) count++;
        byte[] stsz = box("stsz", bytes(0, 16,
                boxMode.equals("table-count-limit") ? 250_001 : count));
        byte[] codec = box(video ? "avcC" : "esds", new byte[]{1, 2, 3, 4});
        if (boxMode.equals("sinf")) codec = join(codec, box("sinf", box("schm", bytes(0))));
        String entry = video ? "avc1" : "mp4a";
        if (boxMode.equals("encrypted-entry")) entry = video ? "encv" : "enca";
        byte[] stsd = box("stsd", bytes(0, 1), box(entry, new byte[video ? 78 : 28], codec));
        byte[] hdlr = box("hdlr", bytes(0, 0), (video ? "vide" : "soun").getBytes("US-ASCII"));
        return box("trak", box("mdia", hdlr, box("minf", box("stbl", stsd, stsz))));
    }
    static void writeFixture() throws Exception {
        List<byte[]> tracks = new ArrayList<>();
        for (int i = 0; i < MediaExtractor.mimes.length; i++) tracks.add(track(i));
        byte[] moov = box("moov", tracks.toArray(new byte[0][]));
        if (boxMode.equals("pssh")) moov = box("moov", join(tracks.toArray(new byte[0][])), box("pssh", bytes(0)));
        if (boxMode.equals("fragmented")) moov = box("moov", join(tracks.toArray(new byte[0][])), box("mvex", bytes(0)));
        byte[] data = box("mdat", new byte[Math.max(32, MediaExtractor.packets.size() * 16)]);
        if (boxMode.equals("no-mdat")) data = new byte[0];
        byte[] ftyp = box("ftyp", "mp42".getBytes("US-ASCII"), bytes(0), "isom".getBytes("US-ASCII"));
        if (boxMode.equals("not-mp4")) ftyp = box("ftyp", "qt  ".getBytes("US-ASCII"), bytes(0));
        Files.write(source.toPath(), join(ftyp, moov, data));
    }
    static void verify() throws Exception {
        PublicPlaybackVerifier.verify(source, EXPECTED, 360, 640,
                () -> active, System.nanoTime() + 60_000_000_000L);
    }
    static void rejects(String name, Action action) throws Exception {
        byte[] before = source.length() < 1_000_000
                ? MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.toPath())) : null;
        long length = source.length(), modified = source.lastModified();
        try { action.run(); throw new AssertionError("Accepted: " + name); }
        catch (IOException expected) {
            check(source.exists() && source.length() == length && source.lastModified() == modified,
                    "Verifier changed or deleted caller's input: " + name);
            if (before != null) check(Arrays.equals(before,
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.toPath()))),
                    "Verifier changed bytes: " + name);
            if (MediaExtractor.opened) check(MediaExtractor.released, "Extractor leaked: " + name);
            passed++;
        }
    }
    static void mode(String value) throws Exception {
        reset(); MediaExtractor.mode = value; rejects(value, PublicPlaybackVerifierTest::verify);
    }
    static void container(String value) throws Exception {
        reset(); boxMode = value; writeFixture(); rejects(value, PublicPlaybackVerifierTest::verify);
    }
    public static void main(String[] args) throws Exception {
        work = new File(args[0]); source = new File(work, "public.part");
        reset();
        byte[] before = Files.readAllBytes(source.toPath());
        verify();
        check(MediaExtractor.released, "Extractor not released on success");
        check(Arrays.equals(before, Files.readAllBytes(source.toPath())), "Success changed bytes");
        passed++;
        reset(); packets(10, 0); writeFixture(); rejects("139s declared, 10s video", PublicPlaybackVerifierTest::verify);
        reset(); packets(10, EXPECTED); writeFixture(); rejects("long audio must not fill short video", PublicPlaybackVerifierTest::verify);
        reset(); packets(0, EXPECTED); writeFixture(); rejects("audio-only full duration", PublicPlaybackVerifierTest::verify);
        reset(); packets(10, 0); writeFixture(); MediaExtractor.formattedDuration = 139_034_000;
        rejects("forged duration metadata", PublicPlaybackVerifierTest::verify);
        reset(); MediaExtractor.formattedDuration = 1; verify(); passed++; // observed samples win
        reset();
        for (Packet p : MediaExtractor.packets) if (p.track == 0 && p.time > 5_000_000 && p.time < 130_000_000)
            p.time = 5_000_000;
        rejects("large video hole or duplicate PTS", PublicPlaybackVerifierTest::verify);
        for (String m : new String[]{"encrypted-video", "encrypted-audio", "crypto-info", "drm",
                "crypto-format", "partial", "oversize", "negative-video", "negative-audio",
                "huge-time", "zero-size", "read-eof", "read-throws", "early-eof", "advance-eof",
                "extra-sample", "no-sync", "bad-width", "bad-height", "missing-csd", "huge-csd",
                "bad-input-size", "huge-input-size", "unsupported", "unknown-track", "empty"}) mode(m);
        for (String m : new String[]{"pssh", "sinf", "encrypted-entry", "fragmented", "not-mp4",
                "no-mdat", "table-count-limit"}) container(m);
        reset();
        try (RandomAccessFile out = new RandomAccessFile(source, "rw")) { out.setLength(source.length() - 1); }
        rejects("physical EOF", PublicPlaybackVerifierTest::verify);
        reset();
        Files.write(source.toPath(), join(Files.readAllBytes(source.toPath()), new byte[]{1, 2, 3}));
        rejects("trailing truncated box", PublicPlaybackVerifierTest::verify);
        reset();
        Files.write(source.toPath(), join(Files.readAllBytes(source.toPath()), bytes(1), "free".getBytes("US-ASCII"), new byte[]{1}));
        rejects("truncated extended box", PublicPlaybackVerifierTest::verify);
        reset();
        Files.write(source.toPath(), join(Files.readAllBytes(source.toPath()), bytes(1), "free".getBytes("US-ASCII"), bytes(-1, -1)));
        rejects("overflow extended box", PublicPlaybackVerifierTest::verify);
        reset();
        try (RandomAccessFile out = new RandomAccessFile(source, "rw")) { out.setLength(PublicPlaybackVerifier.MAX_FILE_BYTES + 1); }
        rejects("96MiB cap", PublicPlaybackVerifierTest::verify);
        reset();
        ByteArrayOutputStream many = new ByteArrayOutputStream(); many.write(Files.readAllBytes(source.toPath()));
        for (int i = 0; i < 20_001; i++) many.write(box("free"));
        Files.write(source.toPath(), many.toByteArray());
        rejects("box count cap", PublicPlaybackVerifierTest::verify);
        reset(); byte[] nested = box("free");
        for (int i = 0; i < 14; i++) nested = box("dinf", nested);
        Files.write(source.toPath(), join(Files.readAllBytes(source.toPath()), nested));
        rejects("box depth cap", PublicPlaybackVerifierTest::verify);
        for (double seconds : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 21601}) {
            reset(); rejects("invalid expected duration", () -> PublicPlaybackVerifier.verify(source, seconds, 360, 640));
        }
        // Declared dimensions are advisory since 0.1.18; only invalid ones are rejected up front.
        for (int[] dims : new int[][]{{0,640},{360,-1},{8193,640},{8192,8192}}) {
            reset(); rejects("invalid declared dimensions", () -> PublicPlaybackVerifier.verify(source, EXPECTED, dims[0], dims[1]));
        }
        reset(); active = false; rejects("cancel before open", PublicPlaybackVerifierTest::verify);
        reset(); Thread.currentThread().interrupt();
        try { rejects("thread interrupted", PublicPlaybackVerifierTest::verify); }
        finally { Thread.interrupted(); }
        for (String stage : new String[]{"open", "format", "read", "advance", "release"}) {
            reset(); MediaExtractor.hook = stage; rejects("cancel during " + stage, PublicPlaybackVerifierTest::verify);
        }
        reset(); rejects("deadline", () -> PublicPlaybackVerifier.verify(source, EXPECTED, 360, 640,
                () -> true, System.nanoTime() - 1));
        reset(); final int[] calls = {0};
        rejects("cancel inside box/sample table scan", () -> PublicPlaybackVerifier.verify(source, EXPECTED, 360, 640,
                () -> ++calls[0] < 50, System.nanoTime() + 60_000_000_000L));
        reset(); packets(10, 0); writeFixture();
        PublicPlaybackVerifier.verify(source, 10, 360, 640); passed++;
        if (args.length == 3) replayActual(new File(args[1]), new File(args[2]));
        System.out.println("Public playback verifier host cases passed: " + passed
                + " (Android API doubles; no device/decoder claim)");
    }

    static void replayActual(File actual, File metadata) throws Exception {
        reset();
        MediaExtractor.actual = actual;
        MediaExtractor.packets = new ArrayList<>();
        List<String> lines = Files.readAllLines(metadata.toPath());
        String[] first = lines.remove(0).split(" ");
        double seconds = Double.parseDouble(first[0]);
        MediaExtractor.width = Integer.parseInt(first[1]); MediaExtractor.height = Integer.parseInt(first[2]);
        for (String line : lines) {
            String[] parts = line.split(" ");
            MediaExtractor.packets.add(new Packet(Integer.parseInt(parts[0]), Long.parseLong(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Long.parseLong(parts[4])));
        }
        long length = actual.length(), modified = actual.lastModified();
        PublicPlaybackVerifier.verify(actual, seconds, MediaExtractor.width, MediaExtractor.height);
        check(actual.length() == length && actual.lastModified() == modified, "Actual fixture changed");
        check(MediaExtractor.released, "Actual replay leaked extractor double");
        passed++;
        System.out.println("Actual public MP4: production box parser + ffprobe packet replay passed; "
                + MediaExtractor.packets.size() + " packets; Android MediaExtractor NOT run.");
        MediaExtractor.actual = null;
    }
}

final class Packet {
    final int track, size, flags;
    long time, offset;
    Packet(int track, long time, int size, int flags, long offset) {
        this.track = track; this.time = time; this.size = size; this.flags = flags; this.offset = offset;
    }
}
class MediaFormat {
    static final String KEY_MIME = "mime", KEY_WIDTH = "width", KEY_HEIGHT = "height", KEY_MAX_INPUT_SIZE = "max-input-size";
    final String mime;
    MediaFormat(String mime) { this.mime = mime; }
    String getString(String key) { return MediaExtractor.mode.equals("unsupported") ? "video/unknown" : mime; }
    boolean containsKey(String key) {
        if (key.equals("crypto-key")) return MediaExtractor.mode.equals("crypto-format");
        if (key.equals("csd-0")) return !MediaExtractor.mode.equals("missing-csd");
        return key.equals(KEY_MAX_INPUT_SIZE);
    }
    int getInteger(String key) {
        // Since 0.1.18 small or metadata-level dimension drift is accepted (declared values
        // are advisory); only genuinely invalid dimensions are rejected.
        if (key.equals(KEY_WIDTH)) return MediaExtractor.mode.equals("bad-width") ? 0 : MediaExtractor.width;
        if (key.equals(KEY_HEIGHT)) return MediaExtractor.mode.equals("bad-height") ? 0 : MediaExtractor.height;
        if (MediaExtractor.mode.equals("bad-input-size")) return -1;
        if (MediaExtractor.mode.equals("huge-input-size")) return 9 * 1024 * 1024;
        return 8192;
    }
    long getLong(String key) { return MediaExtractor.formattedDuration; }
    ByteBuffer getByteBuffer(String key) { return ByteBuffer.allocate(MediaExtractor.mode.equals("huge-csd") ? 65537 : 8); }
}
class MediaCodec { static class CryptoInfo {} }
class MediaExtractor {
    static final int SAMPLE_FLAG_SYNC = 1, SAMPLE_FLAG_ENCRYPTED = 2;
    static List<Packet> packets;
    static String mode = "", hook = "";
    static String[] mimes;
    static File actual;
    static int width = 360, height = 640;
    static long formattedDuration;
    static boolean released, opened;
    int at;
    void event(String name) { if (hook.equals(name)) PublicPlaybackVerifierTest.active = false; }
    void setDataSource(FileDescriptor fd, long start, long length) { opened = true; event("open"); }
    Map<Object,Object> getPsshInfo() { return mode.equals("drm") ? Collections.singletonMap("test", "test") : null; }
    int getTrackCount() { return mimes.length; }
    MediaFormat getTrackFormat(int i) { event("format"); return new MediaFormat(mimes[i]); }
    void selectTrack(int i) {}
    Packet packet() { return packets.get(Math.min(at, packets.size() - 1)); }
    int getSampleTrackIndex() {
        if (mode.equals("empty") || mode.equals("early-eof") && at > 4) return -1;
        if (mode.equals("unknown-track")) return 7;
        return packet().track;
    }
    int getSampleFlags() {
        Packet p = packet();
        if (mode.equals("encrypted-video") && p.track == 0 || mode.equals("encrypted-audio") && p.track == 1) return 2;
        if (mode.equals("partial")) return 4;
        return mode.equals("no-sync") ? 0 : p.flags;
    }
    boolean getSampleCryptoInfo(MediaCodec.CryptoInfo info) { return mode.equals("crypto-info"); }
    int readSampleData(ByteBuffer b, int offset) throws IOException {
        event("read");
        if (mode.equals("oversize")) return 9 * 1024 * 1024;
        if (mode.equals("zero-size")) return 0;
        if (mode.equals("read-eof")) return -1;
        if (mode.equals("read-throws")) throw new IOException("Truncated sample");
        Packet p = packet();
        if (actual != null) {
            byte[] bytes = new byte[p.size];
            try (RandomAccessFile in = new RandomAccessFile(actual, "r")) {
                in.seek(p.offset); in.readFully(bytes);
            }
            b.put(bytes);
        }
        return p.size;
    }
    long getSampleTime() {
        if (mode.equals("negative-video") && packet().track == 0) return -1;
        if (mode.equals("negative-audio") && packet().track == 1) return -250001;
        if (mode.equals("huge-time")) return Long.MAX_VALUE;
        return packet().time;
    }
    boolean advance() {
        event("advance");
        if (mode.equals("advance-eof") && at > 4) return false;
        return ++at < packets.size() || mode.equals("extra-sample");
    }
    void release() { released = true; event("release"); }
}
