package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

/** Built-in software decoder layer: libVLC decodes in software what the device's hardware
 *  cannot (H.265/ByteVC2 on Android 6.0 phones and TVs). Owns a full-screen overlay with a
 *  minimal control bar; the surrounding PlayerActivity keeps queue and progress duty. */
public final class VlcSoftLayer {
    private LibVLC engine;
    private MediaPlayer player;
    private Activity activity;
    private SoftDecoder.Host host;
    private FrameLayout overlay;
    private SurfaceView surface;
    private SeekBar seek;
    private TextView status;
    private Button toggle;
    private long length = -1;
    private boolean seeking, ended, started;
    private long lastReport;
    private Runnable watchdog;
    private android.os.Handler watchdogHandler;

    /** Builds the overlay and starts playback. Returns false (and cleans up) when libVLC cannot start. */
    public boolean start(SoftDecoder.Host host, Activity activity, Uri uri, String userAgent, String referer, long startAtMs, String title) {
        this.host = host;
        this.activity = activity;
        // Software-decoding 1080P on a weak CPU can take very long to show the first frame; if
        // nothing starts within 30s, fail so the player lowers the quality and retries.
        watchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        watchdog = () -> { if (!started && !ended) { ended = true; host.onSoftFailed("内置软解起播等待超时，请稍后重试"); } };
        watchdogHandler.postDelayed(watchdog, 30000);
        try {
            ArrayList<String> args = new ArrayList<>();
            args.add("--no-video-title-show");
            args.add("--audio-time-stretch");
            if (userAgent != null && !userAgent.isEmpty()) args.add("--http-user-agent=" + userAgent);
            if (referer != null && !referer.isEmpty()) args.add("--http-referrer=" + referer);
            engine = new LibVLC(activity.getApplicationContext(), args);
            player = new MediaPlayer(engine);
        } catch (Throwable error) { releaseEngine(); return false; }
        buildOverlay(title);
        try {
            Media media = new Media(engine, uri);
            media.setHWDecoderEnabled(false, false); // software decode by design: hardware HEVC is what's missing here
            player.setMedia(media);
            media.release();
            player.setEventListener(event -> {
                if (ended) return;
                switch (event.type) {
                    case MediaPlayer.Event.EndReached:
                        ended = true;
                        host.onSoftProgress(length > 0 ? length : player.getTime(), length);
                        host.onSoftEnded();
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        ended = true;
                        host.onSoftFailed("内置软解播放该分集未成功");
                        break;
                    case MediaPlayer.Event.LengthChanged:
                        length = event.getLengthChanged(); refreshTime();
                        break;
                    case MediaPlayer.Event.TimeChanged:
                        if (!seeking) { seek.setMax((int) Math.max(0, length)); seek.setProgress((int) Math.max(0, player.getTime())); refreshTime(); }
                        if (length > 0 && System.currentTimeMillis() - lastReport >= 5000) { lastReport = System.currentTimeMillis(); host.onSoftProgress(player.getTime(), length); }
                        break;
                    case MediaPlayer.Event.Playing:
                    case MediaPlayer.Event.Paused:
                        if (!started && event.type == MediaPlayer.Event.Playing) { started = true; watchdogHandler.removeCallbacks(watchdog); }
                        refreshToggle();
                        break;
                    default: break;
                }
            });
            player.getVLCVout().setVideoView(surface);
            player.getVLCVout().attachViews();
            player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
            player.play();
            if (startAtMs > 0) player.setTime(startAtMs);
            return true;
        } catch (Throwable error) {
            host.onSoftFailed("内置软解启动未成功");
            releaseEngine();
            return false;
        }
    }

    /** Removes the overlay and frees the decoder. Safe to call twice. */
    public void stop() {
        ended = true;
        try { if (watchdogHandler != null && watchdog != null) watchdogHandler.removeCallbacks(watchdog); } catch (Throwable ignored) { }
        try { if (player != null) { player.setEventListener(null); player.stop(); player.getVLCVout().detachViews(); } } catch (Throwable ignored) { }
        releaseEngine();
        if (activity != null) activity.runOnUiThread(() -> { if (overlay != null && overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay); });
        overlay = null;
    }

    private void buildOverlay(String title) {
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        root.setClickable(true); root.setFocusable(true); root.setLongClickable(true);
        root.setOnKeyListener((view, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_UP) return false;
            if (code == KeyEvent.KEYCODE_BACK) { close(); return true; }
            return false;
        });

        surface = new SurfaceView(activity);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        TextView caption = new TextView(activity);
        caption.setText(title == null ? "内置软解播放" : title + " · 内置软解");
        caption.setTextColor(0xfff2f2f2); caption.setTextSize(15); caption.setPadding(dp(16), dp(10), dp(16), dp(6));
        FrameLayout.LayoutParams captionLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        captionLayout.topMargin = dp(24);
        root.addView(caption, captionLayout);

        Button close = button("关闭");
        close.setOnClickListener(view -> close());
        FrameLayout.LayoutParams closeLayout = new FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP | Gravity.END);
        closeLayout.topMargin = dp(20); closeLayout.rightMargin = dp(12);
        root.addView(close, closeLayout);

        FrameLayout bar = new FrameLayout(activity);
        bar.setBackgroundColor(0xaa101413);
        seek = new SeekBar(activity);
        seek.setMax(1000); seek.setFocusable(true);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                if (player != null) player.setTime((long) bar.getProgress());
            }
        });
        bar.addView(seek, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        toggle = button("暂停");
        toggle.setOnClickListener(view -> { if (player != null) player.pause(); });
        bar.addView(toggle, frame(dp(96), dp(40), Gravity.BOTTOM | Gravity.START, dp(12), 0, 0, dp(6)));
        status = new TextView(activity);
        status.setTextColor(0xffb8b8b8); status.setTextSize(13); status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        status.setPadding(0, 0, dp(120), 0);
        bar.addView(status, frame(-1, dp(40), Gravity.BOTTOM, 0, 0, 0, dp(6)));
        FrameLayout.LayoutParams barLayout = new FrameLayout.LayoutParams(-1, dp(92), Gravity.BOTTOM);
        root.addView(bar, barLayout);

        overlay = root;
        ViewGroup parent = (ViewGroup) activity.findViewById(android.R.id.content);
        parent.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
        overlay.requestFocus();
        refreshTime();
    }

    private Button button(String label) {
        Button value = new Button(activity);
        value.setText(label); value.setTextSize(14); value.setAllCaps(false);
        value.setTextColor(0xfff2f2f2); value.setFocusable(true);
        return value;
    }
    private FrameLayout.LayoutParams frame(int width, int height, int gravity, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(width, height, gravity);
        layout.setMargins(left, top, right, bottom);
        return layout;
    }
    private void refreshToggle() {
        if (toggle == null || player == null) return;
        activity.runOnUiThread(() -> toggle.setText(player.isPlaying() ? "暂停" : "继续"));
    }
    private void refreshTime() {
        if (status == null) return;
        long position = player == null ? 0 : Math.max(0, player.getTime());
        activity.runOnUiThread(() -> status.setText(format(position) + " / " + (length > 0 ? format(length) : "--:--")));
    }
    private static String format(long ms) {
        long total = ms / 1000;
        return String.format("%02d:%02d", total / 60, total % 60);
    }
    private int dp(int value) { return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f); }
    private void close() {
        SoftDecoder.Host notified = host;
        stop();
        if (notified != null) notified.onSoftClosed();
    }
    private void releaseEngine() {
        try { if (player != null) { player.release(); player = null; } } catch (Throwable ignored) { }
        try { if (engine != null) { engine.release(); engine = null; } } catch (Throwable ignored) { }
    }
}
