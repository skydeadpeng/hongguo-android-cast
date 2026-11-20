package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import org.json.JSONObject;

/** Exercises the production MediaSource/DataSource with previously downloaded real footage.
 *  The application interceptor serves local bytes only; no DNS/socket may be opened. */
@UnstableApi
final class SourcePilotMediaChecks {
    static void run(Instrumentation test) {
        Bundle result = new Bundle();
        int exit = 1;
        try {
            if (!android.os.Build.MODEL.contains("sdk")) throw new AssertionError("Use an isolated test emulator");
            File directory = new File(test.getTargetContext().getFilesDir(), "source-pilot-footage");
            for (String media : new String[]{"clip.mp4", "clip.m3u8"}) verify(test, directory, media);
            result.putString("stream", "\nANDROID_SOURCE_MEDIA_PASS mp4=true hls=true realHongguo=true firstFrame=true progressMs>=1500 muted=true externalNetwork=0\n");
            exit = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("stream", "\nANDROID_SOURCE_MEDIA_FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
        }
        test.finish(exit, result);
    }

    private static void verify(Instrumentation test, File directory, String name) throws Exception {
        final String prefix = "https://media-fixture.invalid/";
        String token = SourcePlaybackSessions.open("guipian", "红果真实下载素材", new JSONObject().put("url", prefix + name));
        SourcePlaybackSessions.Session session = SourcePlaybackSessions.take(token, () -> {});
        if (session == null) throw new AssertionError("session unavailable");
        CountDownLatch first = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> fault = new AtomicReference<>();
        OkHttpClient testClient = offlineFixtureClient(directory, requests);
        final ExoPlayer[] player = {null};
        final SurfaceTexture[] texture = {null};
        final Surface[] surface = {null};
        long[] position = {0};
        try {
            test.runOnMainSync(() -> {
                texture[0] = new SurfaceTexture(false);
                surface[0] = new Surface(texture[0]);
                player[0] = new ExoPlayer.Builder(test.getTargetContext()).build();
                player[0].setVolume(0f);
                player[0].setVideoSurface(surface[0]);
                player[0].addListener(new Player.Listener() {
                    @Override public void onRenderedFirstFrame() { first.countDown(); }
                    @Override public void onPlayerError(PlaybackException error) {
                        fault.set(error.errorCode + ":" + error.getErrorCodeName()); first.countDown();
                    }
                });
                player[0].setMediaSource(SourcePlaybackActivity.buildMediaSource(session, testClient));
                player[0].setPlayWhenReady(true);
                player[0].prepare();
            });
            if (!first.await(15, TimeUnit.SECONDS) || fault.get() != null) throw new AssertionError(name + " no decoded frame " + fault.get());
            for (int i = 0; i < 40 && position[0] < 1500 && fault.get() == null; i++) {
                SystemClock.sleep(100);
                test.runOnMainSync(() -> {
                    if (player[0].getVolume() != 0f) fault.set("not muted");
                    position[0] = player[0].getCurrentPosition();
                });
            }
            if (fault.get() != null || position[0] < 1500 || requests.get() < (name.endsWith("m3u8") ? 2 : 1))
                throw new AssertionError(name + " stalled or invalid fixture transport " + position[0] + " " + fault.get());
        } finally {
            SourcePlaybackSessions.close(token);
            test.runOnMainSync(() -> {
                if (player[0] != null) player[0].release();
                if (surface[0] != null) surface[0].release();
                if (texture[0] != null) texture[0].release();
            });
        }
    }

    static OkHttpClient offlineFixtureClient(File directory, AtomicInteger requests) {
        return new OkHttpClient.Builder().dns(host -> { throw new AssertionError("must stay offline"); })
            .addInterceptor(chain -> {
                Request request = chain.request();
                if (!request.url().isHttps() || !request.url().host().equals("media-fixture.invalid")) throw new IOException("not a fixture request");
                String path = request.url().encodedPath();
                if (!path.matches("/clip(?:\\.(?:mp4|m3u8)|[0-9]{3}\\.ts)")) throw new IOException("invalid fixture path");
                File file = new File(directory, path.substring(1));
                byte[] data = read(file);
                String contentType = path.endsWith(".mp4") ? "video/mp4" : path.endsWith(".m3u8") ? "application/x-mpegURL" : "video/mp2t";
                int start = 0, end = data.length - 1, code = 200;
                String range = request.header("Range");
                if (range != null) {
                    java.util.regex.Matcher match = java.util.regex.Pattern.compile("bytes=([0-9]+)-([0-9]*)").matcher(range);
                    if (!match.matches()) throw new IOException("invalid fixture range");
                    start = Integer.parseInt(match.group(1));
                    if (!match.group(2).isEmpty()) end = Integer.parseInt(match.group(2));
                    if (end >= data.length || end < start) throw new IOException("fixture range out of bounds");
                    code = 206;
                }
                requests.incrementAndGet();
                Response.Builder response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fixture").header("Content-Type", contentType)
                    .body(ResponseBody.create(Arrays.copyOfRange(data, start, end + 1), MediaType.get(contentType)));
                if (code == 206) response.header("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
                return response.build();
            }).build();
    }

    private static byte[] read(File file) throws IOException {
        if (!file.isFile() || file.length() < 1 || file.length() > 16 * 1024 * 1024) throw new IOException("fixture missing");
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }
}
