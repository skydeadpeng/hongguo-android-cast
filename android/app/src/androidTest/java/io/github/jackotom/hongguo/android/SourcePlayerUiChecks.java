package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

/** Actual debug Activity, with offline transport of real, previously downloaded footage. */
@UnstableApi
final class SourcePlayerUiChecks {
    private static final String FIXTURE_HOST = "media-fixture.invalid";
    private static final String TITLE = "红果真实下载样片";
    private static final long WAIT_MS = 18_000;
    private final Instrumentation test;
    private final Context target;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private File output;
    private String stage = "environment";

    private SourcePlayerUiChecks(Instrumentation test) { this.test = test; target = test.getTargetContext(); }

    static void run(Instrumentation test) {
        int result = Activity.RESULT_CANCELED;
        Bundle report = new Bundle();
        SourcePlayerUiChecks suite = new SourcePlayerUiChecks(test);
        try {
            suite.verify();
            report.putString("stream", "\nANDROID_SOURCE_PLAYER_UI_PASS actualActivity=true mp4=true hls=true realHongguo=true firstFrame=true pauseResume=true dpadSeek=true fullscreen=true safeFailure=true back=true muteBeforeLaunch=true externalNetwork=0\n");
            result = Activity.RESULT_OK;
        } catch (Throwable error) {
            // Never copy exception messages that might contain a media address to instrumentation output.
            report.putString("stream", "\nANDROID_SOURCE_PLAYER_UI_FAIL stage=" + suite.stage
                + " kind=" + error.getClass().getSimpleName()
                + (error instanceof CheckFailure ? " check=" + error.getMessage() : "") + "\n");
        }
        test.finish(result, report);
    }

    private void verify() throws Exception {
        need(BuildConfig.DEBUG && (target.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && (Build.MODEL.contains("sdk") || "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)), "isolated_debug_avd");
        ConnectivityManager connectivity = (ConnectivityManager) target.getSystemService(Context.CONNECTIVITY_SERVICE);
        need(connectivity != null && connectivity.getActiveNetwork() == null, "no_active_network");
        File clips = new File(target.getFilesDir(), "source-pilot-footage");
        need(new File(clips, "clip.mp4").isFile() && new File(clips, "clip.m3u8").isFile(), "prior_real_footage_present");
        output = new File(target.getFilesDir(), "source-player-ui-qa");
        need(output.isDirectory() || output.mkdirs(), "evidence_directory");

        stage = "mute";
        audio = (AudioManager) target.getSystemService(Context.AUDIO_SERVICE);
        need(audio != null, "audio_service");
        UiAutomation automation = test.getUiAutomation();
        automation.adoptShellPermissionIdentity(android.Manifest.permission.MODIFY_AUDIO_SETTINGS);
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
        } finally { automation.dropShellPermissionIdentity(); }
        requireMuted(null);
        test.setInTouchMode(false); // exercise the same focus/center path as a remote.

        AtomicInteger requests = new AtomicInteger();
        OkHttpClient fixture = SourcePilotMediaChecks.offlineFixtureClient(clips, requests);
        verifyPlayback(fixture, "clip.mp4", true);
        need(requests.get() > 0, "mp4_fixture_intercepted");
        int beforeHls = requests.get();
        verifyPlayback(fixture, "clip.m3u8", false);
        need(requests.get() >= beforeHls + 2, "hls_manifest_and_media_intercepted");

        stage = "controlled_http_failure";
        AtomicInteger deniedRequests = new AtomicInteger();
        OkHttpClient denied = new OkHttpClient.Builder()
            .dns(host -> { throw new AssertionError("must stay offline"); })
            .addInterceptor(chain -> {
                okhttp3.Request request = chain.request();
                if (!request.url().isHttps() || !FIXTURE_HOST.equals(request.url().host())
                        || !"/unavailable.mp4".equals(request.url().encodedPath())) throw new IOException("unexpected fixture request");
                deniedRequests.incrementAndGet();
                return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503)
                    .message("fixture unavailable").body(ResponseBody.create(new byte[0], null)).build();
            }).build();
        String token = session("unavailable.mp4");
        SourcePlaybackActivity activity = null;
        try {
            activity = launch(token, denied);
            SourcePlaybackActivity a = activity;
            await(() -> {
                TextView status = (TextView) value(a, "status");
                return status.isShown() && status.getText().toString().contains("503")
                    && control(a, "返回选集") != null;
            }, "readable_503_and_return");
            requireMuted(activity);
            SourcePlaybackSessions.Session deniedSession = (SourcePlaybackSessions.Session) onMain(() -> value(a, "session"));
            need(deniedSession != null && !deniedSession.active() && deniedRequests.get() == 1, "failure_terminal_one_request");
            need(onMain(() -> player(a) == null || !player(a).isPlaying()), "failure_no_playing");
            screenshot("source-player-error.png", activity);
            press(activity, "返回选集", "safe_return");
            await(a::isDestroyed, "safe_return_destroyed");
            need(deniedRequests.get() == 1, "safe_return_no_retry");
        } finally { close(activity, token); }
    }

    private void verifyPlayback(OkHttpClient fixture, String name, boolean controls) throws Exception {
        stage = controls ? "mp4_open" : "hls_open";
        String token = session(name);
        SourcePlaybackActivity activity = null;
        try {
            activity = launch(token, fixture);
            SourcePlaybackActivity a = activity;
            await(() -> player(a) != null && player(a).getPlaybackState() == Player.STATE_READY
                && player(a).getDuration() >= 8_000 && player(a).getVideoSize().height > 0, "video_ready_known_duration");
            requireMuted(activity);
            await(() -> player(a).getCurrentPosition() >= 1_200, "actual_play_progress");
            checkDecodedSurface(activity);
            SourcePlaybackSessions.Session playing = (SourcePlaybackSessions.Session) onMain(() -> value(a, "session"));
            need(playing != null && playing.active(), "claimed_session_active");

            if (controls) {
                stage = "pause";
                press(activity, "暂停", "pause");
                await(() -> !player(a).isPlaying() && !player(a).getPlayWhenReady()
                    && control(a, "播放") != null, "dpad_pauses_seen");
                long paused = onMain(() -> player(a).getCurrentPosition());
                SystemClock.sleep(450);
                need(Math.abs(onMain(() -> player(a).getCurrentPosition()) - paused) <= 150, "pause_clock_holds");
                requireMuted(activity);
                need(onMain(() -> control(a, "打开声音") != null), "mute_labeled");
                await(() -> {
                    TextView timing = (TextView) value(a, "timing");
                    String clock = timing.getText().toString();
                    return timing.isShown() && clock.matches("00:[01][0-9] / 00:1[0-3]")
                        && !clock.startsWith("00:00");
                }, "nonzero_elapsed_and_finite_duration");
                screenshot("source-player-controls.png", activity);

                stage = "seek";
                onMain(() -> {
                    SeekBar bar = (SeekBar) value(a, "timeBar");
                    need(player(a).isCurrentMediaItemSeekable() && bar.isShown() && bar.isEnabled()
                        && bar.isFocusable() && bar.requestFocus(), "seekbar_focusable_for_vod");
                    return null;
                });
                test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
                await(() -> player(a).getCurrentPosition() >= paused + 500, "dpad_seek_moves_position");
                need(onMain(() -> !player(a).getPlayWhenReady()), "seek_does_not_resume");

                stage = "fullscreen";
                press(activity, "全屏", "fullscreen");
                await(() -> control(a, "退出全屏") != null && systemBars(a, false), "fullscreen_bars_hidden");
                need(playing.active(), "fullscreen_keeps_session");
                screenshot("source-player-fullscreen.png", activity);
                press(activity, "退出全屏", "fullscreen_exit");
                await(() -> control(a, "全屏") != null && systemBars(a, true), "fullscreen_exit_bars_visible");

                stage = "resume";
                long seeked = onMain(() -> player(a).getCurrentPosition());
                press(activity, "播放", "play");
                await(() -> player(a).getCurrentPosition() >= seeked + 350
                    || player(a).getPlaybackState() == Player.STATE_ENDED, "resume_continues_from_seek");
                requireMuted(activity);
            }
            stage = controls ? "mp4_back" : "hls_back";
            press(activity, "返回", "back");
            await(a::isDestroyed, "back_finishes_actual_activity");
            need(!playing.active(), "exit_cancels_session");
        } finally { close(activity, token); }
    }

    private static String session(String path) throws Exception {
        return SourcePlaybackSessions.open("guipian", TITLE, new JSONObject().put("url", "https://" + FIXTURE_HOST + "/" + path));
    }

    private SourcePlaybackActivity launch(String token, OkHttpClient client) throws Exception {
        SourcePlaybackActivity.setFixtureClient(client);
        Instrumentation.ActivityMonitor monitor = test.addMonitor(SourcePlaybackActivity.class.getName(), null, false);
        try {
            Intent intent = new Intent(target, SourcePlaybackActivity.class)
                .putExtra(SourcePlaybackSessions.EXTRA_SESSION, token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            onMain(() -> { target.startActivity(intent); return null; });
            Activity observed = monitor.waitForActivityWithTimeout(WAIT_MS);
            need(observed instanceof SourcePlaybackActivity, "source_activity_launched");
            return (SourcePlaybackActivity) observed;
        } finally { test.removeMonitor(monitor); }
    }

    private void close(SourcePlaybackActivity activity, String token) throws Exception {
        SourcePlaybackActivity.setFixtureClient(null);
        SourcePlaybackSessions.close(token);
        if (activity != null) onMain(() -> {
            if (!activity.isDestroyed() && !activity.isFinishing()) activity.finish();
            return null;
        });
    }

    private void requireMuted(SourcePlaybackActivity activity) throws Exception {
        need(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                && audio.isStreamMute(AudioManager.STREAM_MUSIC), "system_music_muted");
        if (activity != null) need(onMain(() -> player(activity) == null || player(activity).getVolume() == 0f), "native_player_muted");
    }

    private void checkDecodedSurface(SourcePlaybackActivity activity) throws Exception {
        Bitmap pixels = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888);
        AtomicInteger result = new AtomicInteger(PixelCopy.ERROR_UNKNOWN);
        CountDownLatch copied = new CountDownLatch(1);
        try {
            onMain(() -> {
                View surface = ((PlayerView) value(activity, "playerView")).getVideoSurfaceView();
                need(surface instanceof SurfaceView && surface.isShown(), "decoded_surface_visible");
                PixelCopy.request((SurfaceView) surface, pixels, code -> { result.set(code); copied.countDown(); }, main);
                return null;
            });
            need(copied.await(4, TimeUnit.SECONDS) && result.get() == PixelCopy.SUCCESS, "decoded_surface_copy");
            int[] values = new int[112 * 112];
            pixels.getPixels(values, 0, 112, 0, 0, 112, 112);
            int min = 255, max = 0;
            for (int p : values) {
                int luma = (Color.red(p) * 77 + Color.green(p) * 150 + Color.blue(p) * 29) >> 8;
                min = Math.min(min, luma);
                max = Math.max(max, luma);
            }
            need(max - min >= 24, "decoded_frame_not_blank");
        } finally { if (copied.getCount() == 0) pixels.recycle(); }
    }

    private static boolean systemBars(SourcePlaybackActivity activity, boolean visible) {
        if (android.os.Build.VERSION.SDK_INT < 30) return true;
        android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
        if (insets == null) return false;
        return insets.isVisible(android.view.WindowInsets.Type.statusBars()) == visible
            && insets.isVisible(android.view.WindowInsets.Type.navigationBars()) == visible;
    }

    private void screenshot(String name, SourcePlaybackActivity activity) throws Exception {
        await(activity::hasWindowFocus, "screenshot_window_focused");
        SystemClock.sleep(450); // Wait for the real compositor transition, not just the updated label.
        requireMuted(activity);
        Bitmap picture = test.getUiAutomation().takeScreenshot();
        need(picture != null, "screenshot_available");
        try (FileOutputStream file = new FileOutputStream(new File(output, name))) {
            need(picture.compress(Bitmap.CompressFormat.PNG, 100, file), "screenshot_written");
        } finally { picture.recycle(); }
    }

    private void press(SourcePlaybackActivity activity, String label, String id) throws Exception {
        await(() -> activity.hasWindowFocus() && control(activity, label) != null && control(activity, label).getHeight() > 0, "visible_control_" + id);
        onMain(() -> {
            View action = control(activity, label);
            int targetPx = Math.round(activity.getResources().getDisplayMetrics().density * 44);
            need(action != null && action.isFocusable() && action.getHeight() >= targetPx, "accessible_control_" + id);
            need(action.requestFocus(), "remote_focus_" + id);
            return null;
        });
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    private static View control(SourcePlaybackActivity activity, String label) {
        return controlIn(activity.getWindow().getDecorView(), label);
    }

    private static View controlIn(View v, String label) {
        if (!v.isShown() || !v.isEnabled()) return null;
        if (v.isClickable() && ((!"返回选集".equals(label) && label.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription()))
                || (v instanceof TextView && label.contentEquals(((TextView) v).getText())))) return v;
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = controlIn(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Object value(SourcePlaybackActivity a, String field) throws Exception {
        Field f = SourcePlaybackActivity.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(a);
    }

    private static Player player(SourcePlaybackActivity a) throws Exception { return (Player) value(a, "player"); }

    private void await(Callable<Boolean> predicate, String label) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + WAIT_MS;
        do {
            if (onMain(predicate)) return;
            SystemClock.sleep(70);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new CheckFailure(label);
    }

    private <T> T onMain(Callable<T> callable) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> fault = new AtomicReference<>();
        test.runOnMainSync(() -> {
            try { value.set(callable.call()); }
            catch (Throwable error) { fault.set(error); }
        });
        if (fault.get() instanceof Exception) throw (Exception) fault.get();
        if (fault.get() instanceof Error) throw (Error) fault.get();
        return value.get();
    }

    private static void need(boolean good, String label) { if (!good) throw new CheckFailure(label); }

    private static final class CheckFailure extends AssertionError {
        CheckFailure(String label) { super(label); }
    }
}
