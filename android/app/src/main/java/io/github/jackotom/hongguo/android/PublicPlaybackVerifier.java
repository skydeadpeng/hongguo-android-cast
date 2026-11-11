package io.github.jackotom.hongguo.android;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Read-only gate for a complete public, unencrypted MP4 before cache publication.
 * This verifies demuxed samples, not successful hardware decoding or content identity.
 */
final class PublicPlaybackVerifier {
    static final long MAX_FILE_BYTES = 96L * 1024 * 1024;
    private static final int MAX_SAMPLE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SAMPLES = 250_000;
    private static final int MAX_BOXES = 20_000;
    private static final long MAX_SECONDS = 6 * 60 * 60;
    private static final long BUDGET_NANOS = 30_000_000_000L;
    // The partial-frame flag was named in API 26; the bit is safe to reject on API 23.
    private static final int PARTIAL_FRAME = 4;

    interface Cancellation { boolean isActive(); }

    private PublicPlaybackVerifier() {}

    static void verify(File file, double expectedSeconds, int expectedWidth,
                       int expectedHeight) throws Exception {
        verify(file, expectedSeconds, expectedWidth, expectedHeight, () -> true,
                System.nanoTime() + BUDGET_NANOS);
    }

    /** Does not modify, delete, publish, decrypt, or repair file. The caller owns .part cleanup.
     * Local native extractor calls are synchronous: cancellation/deadline checks surround
     * calls, but cannot interrupt an individual Android media-service call in flight.
     */
    static void verify(File file, double expectedSeconds, int expectedWidth,
                       int expectedHeight, Cancellation active, long deadlineNanos) throws Exception {
        if (file == null || active == null) throw new IllegalArgumentException("Missing media argument");
        long start = System.nanoTime();
        Guard guard = new Guard(active, start, deadlineNanos);
        guard.check();
        if (Double.isNaN(expectedSeconds) || Double.isInfinite(expectedSeconds)
                || expectedSeconds <= 0 || expectedSeconds > MAX_SECONDS
                || !validDimensions(expectedWidth, expectedHeight))
            throw failure("官网视频的完整时长或尺寸无效");
        if (!file.isFile() || file.length() < 32 || file.length() > MAX_FILE_BYTES)
            throw failure("官网视频文件为空或超过大小限制");
        long length = file.length(), modified = file.lastModified();
        Container container = new Container(guard, length);
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            container.walk(input, 0, length, 0, null);
        }
        container.validate();
        guard.check();
        MediaExtractor extractor = new MediaExtractor();
        try {
            try (FileInputStream input = new FileInputStream(file)) {
                extractor.setDataSource(input.getFD(), 0, length);
            }
            guard.check();
            Map<?, ?> pssh = extractor.getPsshInfo();
            if (pssh != null && !pssh.isEmpty()) throw failure("官网视频含 DRM 信息");
            int count = extractor.getTrackCount();
            if (count != container.tracks.size() || count < 1 || count > 2)
                throw failure("官网视频轨道结构无效");
            int video = -1;
            boolean[] videoTracks = new boolean[count];
            int[] observed = new int[count];
            for (int i = 0; i < count; i++) {
                guard.check();
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                boolean isVideo = "video/avc".equals(mime) || "video/hevc".equals(mime);
                if (!isVideo && !"audio/mp4a-latm".equals(mime))
                    throw failure("官网视频编码或轨道类型不受支持");
                if (isVideo != container.tracks.get(i).video)
                    throw failure("官网视频轨道标识不匹配");
                if (isVideo) {
                    if (video >= 0) throw failure("官网视频含多个视频轨道");
                    video = i;
                    int width = format.getInteger(MediaFormat.KEY_WIDTH);
                    int height = format.getInteger(MediaFormat.KEY_HEIGHT);
                    if (!validDimensions(width, height) || width != expectedWidth || height != expectedHeight)
                        throw failure("官网视频实际尺寸与声明不符");
                }
                for (String key : new String[]{"crypto-key", "crypto-mode", "crypto-default-iv-size",
                        "crypto-encrypted-byte-block", "crypto-skip-byte-block"}) {
                    if (format.containsKey(key)) throw failure("官网视频含加密轨道");
                }
                if (!format.containsKey("csd-0") || format.getByteBuffer("csd-0") == null
                        || !format.getByteBuffer("csd-0").hasRemaining())
                    throw failure("官网视频缺少编码参数");
                for (String key : new String[]{"csd-0", "csd-1", "csd-2"}) {
                    if (format.containsKey(key)) {
                        ByteBuffer data = format.getByteBuffer(key);
                        if (data != null && data.remaining() > 64 * 1024)
                            throw failure("官网视频编码参数超限");
                    }
                }
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    int size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    if (size < 0 || size > MAX_SAMPLE_BYTES) throw failure("官网视频样本大小超限");
                }
                videoTracks[i] = isVideo;
                extractor.selectTrack(i);
                guard.check();
            }
            if (video < 0) throw failure("官网文件没有真实视频轨道");
            ByteBuffer buffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES);
            long[] times = new long[container.tracks.get(video).samples];
            int samples = 0, videoSamples = 0;
            long payload = 0;
            MediaCodec.CryptoInfo crypto = new MediaCodec.CryptoInfo();
            long limitUs = (long) ((expectedSeconds + 3) * 1_000_000);
            while (true) {
                guard.check();
                int track = extractor.getSampleTrackIndex();
                guard.check();
                if (track < 0) break;
                if (track >= count || ++samples > MAX_SAMPLES
                        || ++observed[track] > container.tracks.get(track).samples)
                    throw failure("官网视频样本数量或轨道无效");
                int flags = extractor.getSampleFlags();
                if ((flags & (MediaExtractor.SAMPLE_FLAG_ENCRYPTED | PARTIAL_FRAME)) != 0
                        || extractor.getSampleCryptoInfo(crypto))
                    throw failure("官网视频含加密或不完整样本");
                guard.check();
                buffer.clear();
                // API 23 has no getSampleSize(). The fixed buffer caps allocation;
                // Android rejects an oversized sample instead of growing this buffer.
                int size = extractor.readSampleData(buffer, 0);
                guard.check();
                if (size <= 0 || size > MAX_SAMPLE_BYTES || (payload += size) > length)
                    throw failure("官网视频样本截断或大小无效");
                long time = extractor.getSampleTime();
                guard.check();
                // AAC encoder priming may precede presentation zero; it must not
                // contribute to the video timeline or permit negative video PTS.
                if (time < (videoTracks[track] ? 0 : -250_000) || time > limitUs)
                    throw failure("官网视频样本时间戳无效");
                if (videoTracks[track]) {
                    if (videoSamples == 0 && (flags & MediaExtractor.SAMPLE_FLAG_SYNC) == 0)
                        throw failure("官网视频缺少起始关键帧");
                    times[videoSamples++] = time;
                }
                boolean more = extractor.advance();
                guard.check();
                if (!more) break;
            }
            for (int i = 0; i < count; i++) {
                if (observed[i] != container.tracks.get(i).samples)
                    throw failure("官网视频未读到全部媒体样本");
            }
            validateTimeline(times, videoSamples, expectedSeconds, guard);
        } finally {
            extractor.release();
        }
        guard.check();
        if (!file.isFile() || file.length() != length || file.lastModified() != modified)
            throw failure("官网视频文件在验证时发生变化");
        guard.check();
    }

    private static boolean validDimensions(int width, int height) {
        return width > 0 && height > 0 && width <= 8192 && height <= 8192
                && (long) width * height <= 33_554_432L;
    }

    private static void validateTimeline(long[] times, int count, double seconds,
                                         Guard guard) throws IOException {
        if (count < 2) throw failure("官网视频样本不足");
        // Decode-order PTS may go backwards for B frames. Sorting only this local
        // validation copy avoids rejecting valid B frames or trusting container duration.
        Arrays.sort(times, 0, count);
        guard.check();
        long tolerance = (long) (Math.max(0.25, Math.min(1.0, seconds * 0.01)) * 1_000_000);
        long expected = (long) (seconds * 1_000_000);
        long span = times[count - 1] - times[0];
        if (times[0] > tolerance || Math.abs(span - expected) > tolerance)
            throw failure("官网视频实际画面时长与整集声明不符");
        for (int i = 1; i < count; i++) {
            guard.check();
            long gap = times[i] - times[i - 1];
            if (gap <= 0 || gap > 1_000_000)
                throw failure("官网视频画面时间轴存在空缺");
        }
    }

    private static final class Guard {
        final Cancellation active;
        final long start, deadline;
        Guard(Cancellation active, long start, long deadline) {
            this.active = active; this.start = start; this.deadline = deadline;
        }
        void check() throws IOException {
            if (Thread.currentThread().isInterrupted() || !active.isActive())
                throw new InterruptedIOException("官网视频验证已取消");
            long now = System.nanoTime();
            if (now - deadline >= 0 || now - start >= BUDGET_NANOS)
                throw new InterruptedIOException("官网视频验证超时");
        }
    }

    private static final class LayoutTrack {
        boolean video, handler;
        int samples = -1;
    }

    /** Small bounded ISO-BMFF structure check, independent of extractor's EOF and
     * advertised duration. Only ordinary non-fragmented MP4 is accepted. The table
     * sample counts must match the samples actually read, including the audio tail.
     */
    private static final class Container {
        final Guard guard;
        final long length;
        final List<LayoutTrack> tracks = new ArrayList<>();
        int boxes, ftyp, moov, mdat;
        long samples, payload;
        Container(Guard guard, long length) { this.guard = guard; this.length = length; }

        void walk(RandomAccessFile in, long start, long end, int depth,
                  LayoutTrack track) throws IOException {
            if (depth > 12) throw failure("MP4 结构层级超限");
            long at = start;
            while (at < end) {
                guard.check();
                if (++boxes > MAX_BOXES || end - at < 8) throw failure("MP4 结构不完整");
                in.seek(at);
                long size = unsigned(in.readInt());
                String type = fourcc(in.readInt());
                int header = 8;
                if (size == 1) {
                    if (end - at < 16) throw failure("MP4 扩展长度不完整");
                    size = in.readLong(); header = 16;
                } else if (size == 0) {
                    size = end - at;
                }
                if (size < header || size > end - at) throw failure("MP4 文件已截断");
                long body = at + header, next = at + size;
                if (encrypted(type)) throw failure("官网 MP4 含 DRM 或加密结构");
                if ("moof".equals(type) || "mvex".equals(type))
                    throw failure("官网视频不是完整的普通 MP4");
                if (depth == 0) {
                    if ("ftyp".equals(type)) {
                        if (++ftyp != 1 || at != 0 || size - header < 8
                                || size - header > 256 || (size - header) % 4 != 0)
                            throw failure("MP4 文件类型无效");
                        boolean mp4 = false;
                        for (long p = body; p < next; p += 4) {
                            in.seek(p); String brand = fourcc(in.readInt());
                            if (p == body + 4) continue; // minor version is not a brand
                            mp4 |= "isom".equals(brand) || "mp41".equals(brand)
                                    || "mp42".equals(brand) || "avc1".equals(brand)
                                    || brand.matches("iso[2-9]");
                        }
                        if (!mp4) throw failure("官网文件不是支持的 MP4");
                    } else if ("moov".equals(type)) {
                        if (++moov != 1) throw failure("MP4 影片结构重复");
                    } else if ("mdat".equals(type)) {
                        if (size <= header) throw failure("MP4 媒体数据为空");
                        mdat++;
                    }
                }
                if ("trak".equals(type)) {
                    if (depth != 1 || tracks.size() >= 2) throw failure("MP4 轨道数量无效");
                    LayoutTrack child = new LayoutTrack();
                    tracks.add(child);
                    walk(in, body, next, depth + 1, child);
                } else if ("moov".equals(type) || "mdia".equals(type)
                        || "minf".equals(type) || "stbl".equals(type)
                        || "edts".equals(type) || "dinf".equals(type)) {
                    walk(in, body, next, depth + 1, track);
                } else if ("hdlr".equals(type) && track != null) {
                    if (track.handler || next - body < 12) throw failure("MP4 轨道标识无效");
                    in.seek(body + 8);
                    String handler = fourcc(in.readInt());
                    if (!"vide".equals(handler) && !"soun".equals(handler))
                        throw failure("MP4 含不支持的轨道");
                    track.video = "vide".equals(handler); track.handler = true;
                } else if ("stsz".equals(type) && track != null) {
                    if (track.samples >= 0 || next - body < 12) throw failure("MP4 样本表无效");
                    in.seek(body + 4);
                    long uniform = unsigned(in.readInt()), count = unsigned(in.readInt());
                    if (count < 1 || count > MAX_SAMPLES || (samples += count) > MAX_SAMPLES
                            || uniform > MAX_SAMPLE_BYTES
                            || next - body != 12 + (uniform == 0 ? count * 4 : 0))
                        throw failure("MP4 样本表大小或数量无效");
                    track.samples = (int) count;
                    for (int i = 0; i < count; i++) {
                        guard.check();
                        long sample = uniform == 0 ? unsigned(in.readInt()) : uniform;
                        if (sample < 1 || sample > MAX_SAMPLE_BYTES || (payload += sample) > length)
                            throw failure("MP4 样本内容大小无效");
                    }
                } else if ("stsd".equals(type) && track != null) {
                    if (next - body < 8) throw failure("MP4 编码表不完整");
                    in.seek(body + 4);
                    if (in.readInt() != 1) throw failure("MP4 含多个编码描述");
                    walk(in, body + 8, next, depth + 1, track);
                } else if ("avc1".equals(type) || "avc3".equals(type)
                        || "hvc1".equals(type) || "hev1".equals(type)) {
                    if (next - body < 78) throw failure("MP4 视频编码描述不完整");
                    walk(in, body + 78, next, depth + 1, track);
                } else if ("mp4a".equals(type)) {
                    if (next - body < 28) throw failure("MP4 音频编码描述不完整");
                    in.seek(body + 8);
                    if (in.readUnsignedShort() != 0) throw failure("MP4 音频描述版本不受支持");
                    walk(in, body + 28, next, depth + 1, track);
                }
                at = next;
            }
            guard.check();
        }

        void validate() throws IOException {
            guard.check();
            if (ftyp != 1 || moov != 1 || mdat < 1 || tracks.isEmpty())
                throw failure("官网 MP4 缺少完整媒体结构");
            int videos = 0;
            for (LayoutTrack track : tracks) {
                if (!track.handler || track.samples < 1) throw failure("MP4 轨道缺少样本表");
                if (track.video) videos++;
            }
            if (videos != 1) throw failure("官网 MP4 缺少唯一真实视频轨道");
        }
    }

    private static boolean encrypted(String type) {
        return "uuid".equals(type) || "pssh".equals(type) || "sinf".equals(type) || "schm".equals(type)
                || "schi".equals(type) || "tenc".equals(type) || "senc".equals(type)
                || "saiz".equals(type) || "saio".equals(type)
                || "encv".equals(type) || "enca".equals(type);
    }
    private static long unsigned(int value) { return value & 0xffffffffL; }
    private static String fourcc(int value) {
        return new String(new char[]{(char) ((value >>> 24) & 255), (char) ((value >>> 16) & 255),
                (char) ((value >>> 8) & 255), (char) (value & 255)});
    }
    private static IOException failure(String message) { return new IOException(message); }
}
