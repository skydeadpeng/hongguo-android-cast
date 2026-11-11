package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Host-only deterministic media API doubles. Real Android codec tests remain required. */
public class HuangguoMp4RemuxerTest {
    static File source, destination;
    static int passed;
    static void reset() throws Exception {
        MediaExtractor.mode = "ok";
        MediaMuxer.times.clear();
        MediaMuxer.flags.clear();
        MediaMuxer.failStop = false;
        MediaMuxer.oversize = false;
        MediaMuxer.live = true;
        try (FileOutputStream out = new FileOutputStream(destination)) { out.write(1); }
    }
    static void run(double seconds, long bytes) throws Exception {
        HuangguoMp4Remuxer.remux(source, destination, bytes, seconds,
                () -> MediaMuxer.live, System.nanoTime() + 60_000_000_000L);
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void rejects(String mode) throws Exception {
        reset(); MediaExtractor.mode = mode;
        try { run(10, 4_000_000); throw new AssertionError(mode); }
        catch (IOException expected) { check(!destination.exists()); passed++; }
    }
    public static void main(String[] args) throws Exception {
        source = new File(args[0], "input.ts"); destination = new File(args[0], "output.part");
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(new byte[188]); }
        reset(); run(10, 4_000_000);
        check(destination.length() >= 32);
        // Video PTS goes backward for a B frame. Audio starts 0.5s earlier.
        check(MediaMuxer.times.equals(Arrays.asList(500000L, 0L, 3500000L,
                2500000L, 9000000L, 9500000L)));
        check(MediaMuxer.flags.get(0) == 1 && MediaMuxer.flags.get(2) == 0); passed++;
        for (String mode : new String[]{"encrypted", "partial", "drm", "truncated",
                "huge", "negative", "audio-only", "hevc", "no-sync", "empty"}) rejects(mode);
        reset(); MediaMuxer.failStop = true;
        try { run(10, 4_000_000); throw new AssertionError(); }
        catch (IllegalStateException expected) { check(!destination.exists()); passed++; }
        reset(); MediaMuxer.oversize = true;
        try { run(10, 4_000_000); throw new AssertionError(); }
        catch (IOException expected) { check(!destination.exists()); passed++; }
        reset(); MediaExtractor.mode = "cancel-write";
        try { run(10, 4_000_000); throw new AssertionError(); }
        catch (InterruptedIOException expected) { check(!destination.exists()); passed++; }
        reset();
        try { run(Double.NaN, 4_000_000); throw new AssertionError(); }
        catch (IOException expected) { check(!destination.exists()); passed++; }
        reset();
        try { HuangguoMp4Remuxer.remux(source, destination, 4_000_000, 10,
                    () -> true, System.nanoTime() - 1); throw new AssertionError(); }
        catch (InterruptedIOException expected) { check(!destination.exists()); passed++; }
        reset();
        try { run(10, 1_048_577); throw new AssertionError(); }
        catch (IOException expected) { check(!destination.exists()); passed++; }
        System.out.println("Huangguo remux host boundary cases passed: " + passed);
    }
}
class MediaFormat {
    static final String KEY_MIME="mime", KEY_MAX_INPUT_SIZE="max-input-size";
    final String mime;
    MediaFormat(String mime) { this.mime=mime; }
    String getString(String key) { return mime; }
    boolean containsKey(String key) { return key.equals("csd-0"); }
    ByteBuffer getByteBuffer(String key) { return ByteBuffer.wrap(new byte[]{1,2}); }
    int getInteger(String key) { return 100; }
}
class MediaExtractor {
    static final int SAMPLE_FLAG_SYNC=1, SAMPLE_FLAG_ENCRYPTED=2, SAMPLE_FLAG_PARTIAL_FRAME=4;
    static String mode="ok";
    int position;
    final int[] tracks={0,1,0,0,1,0};
    final long[] timestamps={1500000,1000000,4500000,3500000,10000000,10500000};
    void setDataSource(FileDescriptor fd,long start,long length) {}
    Map<Object,Object> getPsshInfo() { return mode.equals("drm")?Collections.singletonMap("x","x"):null; }
    int getTrackCount() { return mode.equals("audio-only")?1:2; }
    MediaFormat getTrackFormat(int i) {
        return new MediaFormat(i==1 || mode.equals("audio-only")?"audio/mp4a-latm":
                mode.equals("hevc")?"video/hevc":"video/avc");
    }
    void selectTrack(int i) {}
    int getSampleTrackIndex() { return mode.equals("empty")?-1:tracks[position]; }
    int getSampleFlags() { return mode.equals("encrypted")?2:mode.equals("partial")?4:
            position==0&&!mode.equals("no-sync")?1:0; }
    int readSampleData(ByteBuffer b,int offset) { return mode.equals("huge")?9*1024*1024:100; }
    long getSampleTime() { return mode.equals("negative")?-1:
            mode.equals("truncated")?timestamps[position]/10:timestamps[position]; }
    boolean advance() { return ++position<timestamps.length; }
    void release() {}
}
class MediaCodec {
    static final int BUFFER_FLAG_KEY_FRAME=1;
    static class BufferInfo {
        long time; int flags;
        void set(int offset,int size,long time,int flags) { this.time=time;this.flags=flags; }
    }
}
class MediaMuxer {
    static final List<Long> times=new ArrayList<>();
    static final List<Integer> flags=new ArrayList<>();
    static boolean failStop, oversize, live=true;
    static class OutputFormat { static final int MUXER_OUTPUT_MPEG_4=0; }
    final File file;
    MediaMuxer(String path,int format) { file=new File(path); }
    int addTrack(MediaFormat format) { return 0; }
    void start() {}
    void writeSampleData(int track,ByteBuffer data,MediaCodec.BufferInfo info) {
        times.add(info.time); flags.add(info.flags);
        if (MediaExtractor.mode.equals("cancel-write")) live=false;
        try (RandomAccessFile out=new RandomAccessFile(file,"rw")) { out.setLength(oversize?5_000_000:128); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
    void stop() { if (failStop) throw new IllegalStateException("stop"); }
    void release() {}
}
