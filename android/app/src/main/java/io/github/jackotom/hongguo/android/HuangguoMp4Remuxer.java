package io.github.jackotom.hongguo.android;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Bounded, local-only AVC/AAC TS remux. Never decrypts DRM or transcodes. */
public final class HuangguoMp4Remuxer {
    private static final int MAX_SAMPLE_BYTES = 8 * 1024 * 1024;
    private static final long MAX_SAMPLES = 1_000_000L;
    private static final double MAX_SECONDS = 24 * 60 * 60;
    // Reserve space for sample tables before writing, including stop()'s final moov.
    private static final long CONTAINER_RESERVE = 1024 * 1024;
    private static final long SAMPLE_TABLE_RESERVE = 64;

    private HuangguoMp4Remuxer() {}

    private static final class Track {
        final int input;
        final MediaFormat format;
        final boolean video;
        int output;
        long first = -1, last = -1, samples, bytes;
        Track(int input, MediaFormat format, boolean video) {
            this.input = input;
            this.format = format;
            this.video = video;
        }
        void observe(int size, long time, int flags) throws IOException {
            if (size <= 0 || size > MAX_SAMPLE_BYTES) throw failure("无效或过大的媒体样本");
            if ((flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                throw failure("不支持加密媒体样本");
            if ((flags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0)
                throw failure("不支持不完整媒体帧");
            if (time < 0 || time > Long.MAX_VALUE - 100_000_000_000L
                    || (samples > 0 && last - time > 10_000_000L))
                throw failure("媒体时间戳无效或不连续");
            if (samples == 0) {
                if (video && (flags & MediaExtractor.SAMPLE_FLAG_SYNC) == 0)
                    throw failure("视频缺少起始关键帧");
                first = time;
            }
            // AVC B frames may have nonmonotonic PTS in decode order. Keep
            // extractor order and exact PTS, but reject large backward resets.
            first = Math.min(first, time);
            last = Math.max(last, time);
            samples++;
            bytes += size;
        }
    }

    /**
     * source must be a complete, decrypted local MPEG-TS concatenation. destination
     * is a caller-owned private temporary file, removed on any unsuccessful return.
     * Native extractor/muxer calls are synchronous; cancellation is checked before
     * and after calls, but cannot interrupt a call inside Android's media service.
     */
    public static void remux(File source, File destination, long maxBytes,
                             double playlistSeconds, BooleanSupplier alive,
                             long deadlineNanos) throws Exception {
        if (source == null || destination == null || alive == null)
            throw new IllegalArgumentException("Missing remux argument");
        // Never clean up the source even if the caller accidentally aliases it.
        if (source.getCanonicalFile().equals(destination.getCanonicalFile()))
            throw new IllegalArgumentException("Input and output must differ");
        boolean success = false;
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        try {
            check(alive, deadlineNanos);
            if (!Double.isFinite(playlistSeconds) || playlistSeconds <= 0
                    || playlistSeconds > MAX_SECONDS || maxBytes <= CONTAINER_RESERVE)
                throw failure("媒体时长或大小限制无效");
            if (!source.isFile() || source.length() <= 0 || source.length() > maxBytes)
                throw failure("本地媒体文件无效或过大");
            final long sourceLength = source.length();
            final long sourceModified = source.lastModified();
            ByteBuffer buffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES);
            extractor = open(source);
            List<Track> tracks = tracks(extractor);
            scan(extractor, tracks, buffer, null, destination, maxBytes,
                    playlistSeconds, 0, alive, deadlineNanos);
            long origin = Long.MAX_VALUE;
            for (Track track : tracks) {
                validateDuration(track, playlistSeconds);
                origin = Math.min(origin, track.first);
            }
            // A fresh extractor starts at the exact beginning, unlike seekTo(0),
            // which may select a later video sync sample or discard leading audio.
            extractor.release();
            extractor = null;
            check(alive, deadlineNanos);
            if (source.length() != sourceLength || source.lastModified() != sourceModified)
                throw failure("本地媒体文件已变更");
            extractor = open(source);
            List<Track> writing = tracks(extractor);
            if (writing.size() != tracks.size()) throw failure("媒体轨道已变更");
            muxer = new MediaMuxer(destination.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for (int i = 0; i < writing.size(); i++) {
                Track track = writing.get(i);
                Track expected = tracks.get(i);
                if (track.input != expected.input || track.video != expected.video)
                    throw failure("媒体轨道已变更");
                track.output = muxer.addTrack(track.format);
            }
            muxer.start();
            scan(extractor, writing, buffer, muxer, destination, maxBytes,
                    playlistSeconds, origin, alive, deadlineNanos);
            for (int i = 0; i < writing.size(); i++) {
                Track track = writing.get(i), expected = tracks.get(i);
                validateDuration(track, playlistSeconds);
                if (track.samples != expected.samples || track.first != expected.first
                        || track.last != expected.last || track.bytes != expected.bytes)
                    throw failure("媒体样本已变更");
            }
            check(alive, deadlineNanos);
            muxer.stop();
            check(alive, deadlineNanos);
            muxer.release();
            muxer = null;
            extractor.release();
            extractor = null;
            if (source.length() != sourceLength || source.lastModified() != sourceModified)
                throw failure("本地媒体文件已变更");
            checkSize(destination, maxBytes);
            if (destination.length() < 32) throw failure("MP4 输出为空");
            check(alive, deadlineNanos);
            success = true;
        } finally {
            // release() can itself throw. Neither resource may prevent the other
            // from being released, or prevent deletion of an incomplete output.
            try {
                if (muxer != null) muxer.release();
            } finally {
                try {
                    if (extractor != null) extractor.release();
                } finally {
                    if (!success && destination.exists() && !destination.delete())
                        throw failure("无法清理未完成的 MP4 文件");
                }
            }
        }
    }

    private static MediaExtractor open(File source) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        boolean ready = false;
        try {
            try (FileInputStream input = new FileInputStream(source)) {
                extractor.setDataSource(input.getFD(), 0, source.length());
                if (extractor.getPsshInfo() != null && !extractor.getPsshInfo().isEmpty())
                    throw failure("不支持 DRM 媒体");
            }
            ready = true;
            return extractor;
        } finally {
            if (!ready) extractor.release();
        }
    }

    private static List<Track> tracks(MediaExtractor extractor) throws IOException {
        int count = extractor.getTrackCount();
        if (count < 1 || count > 16) throw failure("媒体轨道数量无效");
        List<Track> tracks = new ArrayList<>();
        boolean videoSeen = false, audioSeen = false;
        for (int i = 0; i < count; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            boolean video = "video/avc".equals(mime);
            boolean audio = "audio/mp4a-latm".equals(mime);
            if (!video && !audio) {
                if (mime == null || mime.startsWith("video/") || mime.startsWith("audio/"))
                    throw failure("投屏仅支持 H.264 视频和 AAC 音频");
                continue; // TS metadata/subtitles are not audio/video tracks.
            }
            if ((video && videoSeen) || (audio && audioSeen))
                throw failure("不支持多个同类媒体轨道");
            if (!format.containsKey("csd-0") || format.getByteBuffer("csd-0") == null
                    || !format.getByteBuffer("csd-0").hasRemaining())
                throw failure("缺少媒体编码参数");
            for (String key : new String[]{"csd-0", "csd-1", "csd-2"}) {
                if (format.containsKey(key)) {
                    ByteBuffer data = format.getByteBuffer(key);
                    if (data != null && data.remaining() > 64 * 1024)
                        throw failure("媒体编码参数过大");
                }
            }
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                int size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                if (size < 0 || size > MAX_SAMPLE_BYTES) throw failure("媒体样本大小超限");
            }
            tracks.add(new Track(i, format, video));
            extractor.selectTrack(i);
            videoSeen |= video;
            audioSeen |= audio;
        }
        if (!videoSeen) throw failure("缺少 H.264 视频轨道");
        return tracks;
    }

    private static void scan(MediaExtractor extractor, List<Track> tracks,
                             ByteBuffer buffer, MediaMuxer muxer, File destination,
                             long maxBytes, double seconds, long origin,
                             BooleanSupplier alive, long deadlineNanos) throws IOException {
        long payload = 0, samples = 0;
        long maximumSpanUs = (long) ((seconds * 1.25 + 2) * 1_000_000);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            check(alive, deadlineNanos);
            int index = extractor.getSampleTrackIndex();
            if (index < 0) break;
            Track track = null;
            for (Track candidate : tracks) if (candidate.input == index) track = candidate;
            if (track == null) throw failure("意外的媒体轨道");
            int flags = extractor.getSampleFlags();
            if ((flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                throw failure("不支持加密媒体样本");
            buffer.clear();
            // API 26 does not have getSampleSize(). Android checks this fixed
            // buffer's capacity; oversize samples fail instead of allocating more.
            int size = extractor.readSampleData(buffer, 0);
            check(alive, deadlineNanos);
            long time = extractor.getSampleTime();
            track.observe(size, time, flags);
            if (track.last - track.first > maximumSpanUs)
                throw failure("媒体实际时长超限");
            if (++samples > MAX_SAMPLES) throw failure("媒体样本数量超限");
            payload += size;
            long overhead = CONTAINER_RESERVE + samples * SAMPLE_TABLE_RESERVE;
            if (overhead > maxBytes || payload > maxBytes - overhead)
                throw failure("MP4 输出大小超限");
            if (muxer != null) {
                if (time < origin || time - origin > maximumSpanUs)
                    throw failure("媒体轨道时间戳不匹配");
                buffer.position(0);
                buffer.limit(size);
                info.set(0, size, time - origin,
                        (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                check(alive, deadlineNanos);
                muxer.writeSampleData(track.output, buffer, info);
                check(alive, deadlineNanos);
                checkSize(destination, maxBytes);
            }
            if (!extractor.advance()) break;
        }
        check(alive, deadlineNanos);
    }

    private static void validateDuration(Track track, double playlistSeconds) throws IOException {
        double span = (track.last - track.first) / 1_000_000.0;
        // Use observed samples, never the untrusted format's advertised duration.
        if (track.samples < 2 || span < playlistSeconds * 0.8
                || span > playlistSeconds * 1.25 + 2)
            throw failure("媒体实际时长与完整播放列表不符");
    }

    private static void checkSize(File destination, long maxBytes) throws IOException {
        if (destination.length() > maxBytes) throw failure("MP4 输出大小超限");
    }

    private static void check(BooleanSupplier alive, long deadlineNanos) throws IOException {
        if (Thread.currentThread().isInterrupted() || !alive.getAsBoolean())
            throw new InterruptedIOException("媒体准备已取消");
        if (System.nanoTime() - deadlineNanos >= 0)
            throw new InterruptedIOException("媒体准备超时");
    }

    private static IOException failure(String message) { return new IOException(message); }
}
