package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.hls.HlsDataSourceFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.ui.PlayerView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONArray;
import org.json.JSONObject;

/** Plays a selected third-party source without inheriting Red Fruit cache, identity or download
 *  rules. Player interaction matches PlayerActivity: dock buttons (previous/pause/next/select,
 *  autoplay, speed), the shared HUD idle policy and the same key map. Episode switching keeps the
 *  activity alive and swaps the single-use session through the browse screen's SourceService lane,
 *  so a play request can never resolve an episode id the app did not issue. */
@UnstableApi
public final class SourcePlaybackActivity extends Activity {
    private static final int CLOCK_STEPS = 1000;
    private static final String FIXTURE_HOST = "media-fixture.invalid";
    private static final AtomicReference<OkHttpClient> FIXTURE_CLIENT = new AtomicReference<>();
    // The episode lane (detail cache with the issued episode ids) lives in the browse screen's
    // service instance. Sharing it keeps switch requests inside that lane; without it the dock
    // switches degrade to disabled buttons instead of refetching anything.
    private static final AtomicReference<SourceService> SHARED_SERVICE = new AtomicReference<>();

    // Device instrumentation can exercise this Activity with app-private bytes
    // on a .invalid host. No bridge or Intent extras supply a transport.
    static void setFixtureClient(OkHttpClient client) {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("Debug instrumentation only");
        FIXTURE_CLIENT.set(client);
    }

    static void share(SourceService service) { SHARED_SERVICE.set(service); }
    static void unshare(SourceService service) { SHARED_SERVICE.compareAndSet(service, null); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SourcePlaybackSessions.Session session;
    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout header, feedback, dock;
    private TextView status, titleView;
    private TextView timing;
    private ProgressBar busy;
    private Button mute, playPause, fullScreen, returnToEpisodes, back;
    private Button previous, next, select, autoplayButton, speedButton;
    private SeekBar timeBar;
    private PlayerHud controlsHud;
    private int hudDialogs;
    private OkHttpClient client;
    private String sourceId = "", seriesId = "";
    private JSONArray episodes;
    private int current = -1;
    private boolean autoplay = true;
    private float actualSpeed = Float.NaN;
    private boolean switching, failed, rendered, scrubbing, immersive;
    private boolean userPaused, completed, buffering, touchingControls, destroyed;
    private int wakeKey;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (player == null || failed || isFinishing()) return;
            updateProgress();
            updateButtons();
            main.postDelayed(this, player.isPlaying() ? 350 : 800);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final String sessionId = getIntent().getStringExtra(SourcePlaybackSessions.EXTRA_SESSION);
        sourceId = getIntent().getStringExtra("sourceId");
        seriesId = getIntent().getStringExtra("seriesId");
        if (sourceId == null) sourceId = "";
        if (seriesId == null) seriesId = "";
        current = getIntent().getIntExtra("currentIndex", -1);
        try { actualSpeed = PlayerOptions.speed(PlayerOptions.read(this).getDouble("speed")); }
        catch (Exception ignored) { actualSpeed = 1f; }
        // An in-place episode switch revokes the previous session by design (the lane opens the
        // replacement before delivering it); only an unexpected revoke closes the player.
        session = SourcePlaybackSessions.take(sessionId, () -> main.post(() -> { if (!switching && !destroyed) finish(); }));
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setStatusBarColor(TvUi.BAR);
        window.setNavigationBarColor(TvUi.BAR);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(TvUi.BAR);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            screen.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets visible = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(visible.left, visible.top, visible.right, visible.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        controlsHud = new PlayerHud(new PlayerHud.Scheduler() {
            @Override public void cancel(Runnable action) { main.removeCallbacks(action); }
            @Override public void later(Runnable action, long delay) { main.postDelayed(action, delay); }
        }, visible -> {
            if (header != null) header.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (dock != null) dock.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (!visible && playerView != null) playerView.requestFocus();
        });

        int edge = BuildConfig.IS_TV ? TvUi.edgeInset(this) : 16;
        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(edge), dp(8), dp(edge), dp(8));
        back = control("返回");
        back.setContentDescription("返回选集");
        back.setOnClickListener(v -> { showControls(); finish(); });
        header.addView(back);
        titleView = text(session == null ? "影视来源" : session.title, BuildConfig.IS_TV ? 22 : 16, TvUi.TEXT);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLayout.leftMargin = titleLayout.rightMargin = dp(12);
        header.addView(titleView, titleLayout);
        mute = control("已静音");
        mute.setContentDescription("打开声音");
        mute.setSelected(true);
        mute.setOnClickListener(v -> {
            if (!hasSession()) return;
            player.setVolume(player.getVolume() == 0f ? 1f : 0f);
            updateAudioControl();
        });
        header.addView(mute);
        screen.addView(header, new LinearLayout.LayoutParams(-1, -2));
        screen.addView(divider());

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        playerView.setFocusable(true);
        stage.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        screen.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        feedback = new LinearLayout(this);
        feedback.setGravity(Gravity.CENTER_HORIZONTAL);
        feedback.setOrientation(LinearLayout.VERTICAL);
        feedback.setPadding(dp(16), dp(12), dp(16), dp(12));
        feedback.setBackground(TvUi.panelShape(this));
        status = text("", BuildConfig.IS_TV ? 20 : 16, TvUi.TEXT);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        busy = new ProgressBar(this);
        busy.setIndeterminateTintList(ColorStateList.valueOf(TvUi.ACCENT));
        feedback.addView(busy, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(-2, -2);
        statusLayout.topMargin = dp(8);
        feedback.addView(status, statusLayout);
        returnToEpisodes = control("返回选集");
        returnToEpisodes.setOnClickListener(v -> { showControls(); finish(); });
        LinearLayout.LayoutParams returnLayout = new LinearLayout.LayoutParams(-2, -2);
        returnLayout.topMargin = dp(12);
        feedback.addView(returnToEpisodes, returnLayout);
        FrameLayout.LayoutParams panelLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        panelLayout.setMargins(dp(edge), 0, dp(edge), 0);
        stage.addView(feedback, panelLayout);

        screen.addView(divider());
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(dp(edge), dp(4), dp(edge), dp(8));
        timing = text("", BuildConfig.IS_TV ? 17 : 13, TvUi.TEXT);
        timing.setFontFeatureSettings("tnum");
        timing.setSingleLine(true);
        dock.addView(timing, new LinearLayout.LayoutParams(-1, -2));
        timeBar = new SeekBar(this);
        timeBar.setMax(CLOCK_STEPS);
        timeBar.setContentDescription("视频进度");
        timeBar.setProgressTintList(ColorStateList.valueOf(TvUi.ACCENT));
        timeBar.setProgressBackgroundTintList(ColorStateList.valueOf(TvUi.BORDER));
        timeBar.setSecondaryProgressTintList(ColorStateList.valueOf(TvUi.MUTED));
        timeBar.setThumbTintList(ColorStateList.valueOf(TvUi.TEXT));
        timeBar.setBackground(TvUi.selector(this));
        if (Build.VERSION.SDK_INT >= 26) timeBar.setDefaultFocusHighlightEnabled(false);
        timeBar.setVisibility(View.GONE);
        timeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { scrubbing = canSeek(); showControls(); }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || !canSeek()) return;
                if (scrubbing) updateClock(positionFor(progress), player.getDuration());
                else seekFromControl(progress);
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (scrubbing) seekFromControl(bar.getProgress());
                scrubbing = false;
                showControls();
            }
        });
        dock.addView(timeBar, new LinearLayout.LayoutParams(-1, dp(BuildConfig.IS_TV ? 40 : 48)));
        LinearLayout.LayoutParams actionLayout = new LinearLayout.LayoutParams(0, dp(50), 1f);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        previous = control("上一集");
        previous.setOnClickListener(v -> { showControls(); move(-1); });
        actions.addView(previous, actionLayout);
        playPause = control("播放");
        playPause.setSelected(true);
        playPause.setEnabled(false);
        playPause.setOnClickListener(v -> { showControls(); togglePlayback(); });
        actions.addView(playPause, actionLayout);
        next = control("下一集");
        next.setOnClickListener(v -> { showControls(); move(1); });
        actions.addView(next, actionLayout);
        select = control("选集");
        select.setOnClickListener(v -> { showControls(); selectEpisode(); });
        actions.addView(select, actionLayout);
        autoplayButton = control("连播 开");
        autoplayButton.setContentDescription("自动连播已开启");
        autoplayButton.setOnClickListener(v -> { showControls(); autoplay = !autoplay; updateButtons(); });
        actions.addView(autoplayButton, actionLayout);
        dock.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout options = new LinearLayout(this);
        options.setGravity(Gravity.CENTER_VERTICAL);
        speedButton = control("倍速");
        speedButton.setOnClickListener(v -> { showControls(); selectSpeed(); });
        options.addView(speedButton, actionLayout);
        fullScreen = control("全屏");
        fullScreen.setOnClickListener(v -> { showControls(); immersive = !immersive; applyWindowMode(); });
        if (!BuildConfig.IS_TV) options.addView(fullScreen, actionLayout);
        dock.addView(options, new LinearLayout.LayoutParams(-1, -2));
        screen.addView(dock, new LinearLayout.LayoutParams(-1, -2));

        setContentView(screen);
        screen.requestApplyInsets();
        immersive = BuildConfig.IS_TV;
        applyWindowMode();
        showFeedback("正在加载来源视频…", true, false);
        updateButtons();
        if (playPause.isEnabled()) playPause.requestFocus(); else back.requestFocus();
        if (session == null || !session.active()) {
            fail("播放请求已过期");
            return;
        }
        try {
            start();
        } catch (RuntimeException error) {
            fail("来源播放器启动失败");
            return;
        }
        requestEpisodeList();
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private Button control(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(TvUi.TEXT);
        button.setTextSize(BuildConfig.IS_TV ? 17 : 14);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(BuildConfig.IS_TV ? 54 : 48));
        button.setMinimumHeight(dp(BuildConfig.IS_TV ? 54 : 48));
        button.setMinWidth(dp(BuildConfig.IS_TV ? 88 : 60));
        button.setMinimumWidth(dp(BuildConfig.IS_TV ? 88 : 60));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(TvUi.focusSurface(8, this));
        if (Build.VERSION.SDK_INT >= 26) button.setDefaultFocusHighlightEnabled(false);
        button.setFocusable(true);
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(TvUi.BORDER);
        divider.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return divider;
    }

    private void applyWindowMode() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(0, lightBars);
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (immersive) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            window.getDecorView().setSystemUiVisibility(immersive
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0);
        }
        if (fullScreen != null) fullScreen.setText(immersive ? "退出全屏" : "全屏");
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullScreen != null) applyWindowMode();
    }

    private boolean hasSession() { return player != null && !failed && session != null && session.active(); }

    private boolean canSeek() {
        return hasSession() && !player.isCurrentMediaItemLive() && player.isCurrentMediaItemSeekable()
            && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && player.getDuration() > 0;
    }

    private long positionFor(int progress) {
        return Math.round(player.getDuration() * (progress / (double) CLOCK_STEPS));
    }

    private void seekFromControl(int progress) {
        if (canSeek()) player.seekTo(positionFor(progress));
    }

    private void seekBy(int delta) {
        if (!canSeek()) return;
        long duration = player.getDuration();
        player.seekTo(Math.max(0, Math.min(Math.max(0, duration - 250), player.getCurrentPosition() + delta)));
        showControls();
    }

    private boolean togglePlayback() {
        if (!hasSession()) return false;
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            if (!canSeek()) return false;
            completed = false;
            player.seekTo(0);
            player.play();
            userPaused = false;
        } else if (player.getPlayWhenReady()) {
            player.pause();
            userPaused = true;
        } else {
            player.play();
            userPaused = false;
        }
        updateButtons();
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) exitPlayer();
            return true;
        }
        boolean navigation = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (navigation) {
            // First navigation press while the HUD is hidden only reveals it, matching PlayerActivity.
            if (!controlsHud.isVisible()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    wakeKey = code;
                    showControls();
                    if (playPause.isEnabled()) playPause.requestFocus();
                    else if (returnToEpisodes.getVisibility() == View.VISIBLE) returnToEpisodes.requestFocus();
                    else back.requestFocus();
                }
                if (event.getAction() == KeyEvent.ACTION_UP && code == wakeKey) wakeKey = 0;
                return true;
            }
            showControls();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (hasSession() && rendered) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && playerView.hasFocus())) {
                togglePlayback();
                showControls();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) { move(1); return true; }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { move(-1); return true; }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (!player.getPlayWhenReady()) { userPaused = false; completed = false; player.play(); updateButtons(); }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (player.getPlayWhenReady()) { userPaused = true; player.pause(); updateButtons(); }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) { seekBy(30000); return true; }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) { seekBy(-10000); return true; }
            if (playerView.hasFocus() && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { seekBy(-10000); return true; }
            if (playerView.hasFocus() && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { seekBy(10000); return true; }
            if (playerView.hasFocus() && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                showControls();
                if (playPause.isEnabled()) playPause.requestFocus();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN: touchingControls = true; showControls(); break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: touchingControls = false; showControls(); break;
        }
        return super.dispatchTouchEvent(event);
    }

    private void exitPlayer() {
        if (controlsHud != null) controlsHud.suspend();
        finish();
    }

    // ---- Player HUD + dock state (PlayerActivity parity) ----

    private void showControls() {
        if (controlsHud != null) controlsHud.interact();
        updateButtons();
    }

    private boolean canHideControls() {
        if (!hasSession() || !rendered || completed || userPaused || buffering || scrubbing || touchingControls
            || hudDialogs > 0 || switching || playerView == null) return false;
        try { return player.isPlaying(); } catch (RuntimeException error) { return false; }
    }

    private void updateButtons() {
        boolean ended = hasSession() && player.getPlaybackState() == Player.STATE_ENDED;
        previous.setEnabled(!failed && !switching && stepEpisode(-1) >= 0);
        next.setEnabled(!failed && !switching && stepEpisode(1) >= 0);
        select.setEnabled(!failed && !switching && episodes != null && episodes.length() > 0);
        speedButton.setEnabled(!failed && !switching && hasSession());
        speedButton.setText(Float.isNaN(actualSpeed) ? (hasSession() ? "倍速 待确认" : "倍速") : "倍速 " + rateLabel(actualSpeed));
        autoplayButton.setText(autoplay ? "连播 开" : "连播 关");
        autoplayButton.setContentDescription(autoplay ? "自动连播已开启" : "自动连播已关闭");
        playPause.setText(ready() && player.isPlaying() ? "暂停" : completed ? "重播" : "播放");
        playPause.setEnabled(hasSession() && (!ended || canSeek()));
        updateAudioControl();
        if (controlsHud != null) controlsHud.playback(canHideControls());
    }

    private boolean ready() { return hasSession() && rendered; }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private static String rateLabel(float value) {
        return String.format(Locale.ROOT, "%s×", value == (int) value ? Integer.toString((int) value) : Float.toString(value));
    }

    private void pinDialog(Dialog dialog) {
        ++hudDialogs;
        showControls();
        dialog.setOnDismissListener(item -> { --hudDialogs; showControls(); });
        dialog.show();
    }

    // ---- Episode switching through the shared SourceService lane ----

    private void requestEpisodeList() {
        SourceService service = SHARED_SERVICE.get();
        if (service == null || sourceId.isEmpty() || seriesId.isEmpty() || current < 0) return;
        JSONObject params = new JSONObject();
        try { params.put("operation", "detail").put("sourceId", sourceId).put("seriesId", seriesId); }
        catch (Exception ignored) { return; }
        service.submit(params, new SourceService.Reply() {
            @Override public void success(Object value, String ignoredSession) {
                if (destroyed || isFinishing()) return;
                if (value instanceof JSONObject) {
                    JSONArray rows = ((JSONObject) value).optJSONArray("episodes");
                    if (rows != null && current < rows.length()) episodes = rows;
                }
                updateButtons();
            }
            @Override public void failure(String code, String message) { /* switching stays disabled */ }
        });
    }

    private int stepEpisode(int delta) {
        if (episodes == null || current < 0) return -1;
        int index = current;
        for (;;) {
            index += delta;
            if (index < 0 || index >= episodes.length()) return -1;
            JSONObject row = episodes.optJSONObject(index);
            if (row != null && row.optBoolean("playable")) return index;
        }
    }

    private void move(int delta) {
        int target = stepEpisode(delta);
        if (target >= 0) switchTo(target);
        else toast(delta > 0 ? "已经是最后一集" : "已经是第一集");
    }

    private void switchTo(int index) {
        if (failed || switching || player == null || index < 0 || index == current) return;
        if (episodes == null || index >= episodes.length()) return;
        JSONObject episode = episodes.optJSONObject(index);
        if (episode == null || !episode.optBoolean("playable")) { toast("此分集未开放播放"); return; }
        SourceService service = SHARED_SERVICE.get();
        if (service == null || sourceId.isEmpty() || seriesId.isEmpty()) { toast("切集通道不可用，请返回选集重试"); return; }
        switching = true;
        completed = false;
        updateButtons();
        showFeedback("正在加载第 " + episode.optInt("index", index + 1) + " 集…", true, false);
        JSONObject params = new JSONObject();
        try {
            params.put("operation", "play").put("sourceId", sourceId).put("seriesId", seriesId)
                .put("episodeId", episode.optString("episodeId"));
        } catch (Exception error) {
            switching = false;
            updateButtons();
            updateChrome();
            return;
        }
        service.submit(params, new SourceService.Reply() {
            @Override public void success(Object value, String sessionId) {
                switching = false;
                if (destroyed || isFinishing()) {
                    if (sessionId != null) SourcePlaybackSessions.close(sessionId);
                    return;
                }
                SourcePlaybackSessions.Session taken = sessionId == null ? null
                    : SourcePlaybackSessions.take(sessionId, () -> main.post(() -> { if (!switching && !destroyed) finish(); }));
                if (taken == null) {
                    toast("切换分集未完成，请返回选集重试");
                    updateButtons();
                    updateChrome();
                    return;
                }
                session = taken;
                current = index;
                completed = false;
                rendered = false;
                userPaused = false;
                buffering = false;
                titleView.setText(session.title);
                player.stop();
                player.setMediaSource(buildMediaSource(taken, client));
                applyRequestedSpeed();
                player.setPlayWhenReady(true);
                player.prepare();
                showFeedback("正在加载来源视频…", true, false);
                updateButtons();
            }
            @Override public void failure(String code, String message) {
                switching = false;
                if (destroyed || isFinishing()) return;
                toast(message == null || message.isEmpty() ? "切换分集未完成" : message);
                updateButtons();
                updateChrome();
            }
        });
    }

    private void selectEpisode() {
        if (episodes == null || episodes.length() == 0 || current < 0 || switching) return;
        String[] labels = new String[episodes.length()];
        for (int i = 0; i < labels.length; i++) {
            JSONObject row = episodes.optJSONObject(i);
            int index = row == null ? i + 1 : row.optInt("index", i + 1);
            labels[i] = "第 " + index + " 集" + (i == current ? " · 当前" : "")
                + (row != null && !row.optBoolean("playable") ? " · 暂不可播" : "");
        }
        pinDialog(new android.app.AlertDialog.Builder(this).setTitle("选集")
            .setSingleChoiceItems(labels, current, (dialog, which) -> { dialog.dismiss(); if (which != current) switchTo(which); })
            .setNegativeButton("关闭", null).create());
    }

    // ---- Speed (shared preference with the Red Fruit player) ----

    private void selectSpeed() {
        if (!hasSession() || player == null) return;
        float[] speeds = PlayerOptions.SPEEDS;
        String[] labels = new String[speeds.length];
        int selected = -1;
        for (int i = 0; i < speeds.length; i++) {
            labels[i] = rateLabel(speeds[i]);
            if (!Float.isNaN(actualSpeed) && Math.abs(actualSpeed - speeds[i]) < .001f) selected = i;
        }
        pinDialog(new android.app.AlertDialog.Builder(this).setTitle("播放倍速")
            .setSingleChoiceItems(labels, selected, (dialog, which) -> { dialog.dismiss(); applySpeed(speeds[which]); })
            .setNegativeButton("关闭", null).create());
    }

    private void applySpeed(float requested) {
        if (!hasSession() || player == null) return;
        boolean playing = player.isPlaying();
        float before = Float.isNaN(actualSpeed) ? 1f : actualSpeed;
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(requested));
        float observed = player.getPlaybackParameters().speed;
        if (!Float.isFinite(observed) || observed <= 0 || Math.abs(observed - requested) > .001f) {
            player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(before));
            if (playing) player.play(); else player.pause();
            toast("倍速设置未成功");
            updateButtons();
            return;
        }
        if (playing) player.play(); else player.pause();
        actualSpeed = observed;
        try { PlayerOptions.update(this, new JSONObject().put("speed", (double) observed)); } catch (Exception ignored) { }
        updateButtons();
    }

    private void applyRequestedSpeed() {
        if (hasSession() && !Float.isNaN(actualSpeed)) {
            player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(actualSpeed));
        }
    }

    // ---- Progress / clock / feedback ----

    private static String clock(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void updateClock(long position, long duration) {
        String value = clock(Math.min(position, duration)) + " / " + clock(duration);
        if (!value.contentEquals(timing.getText())) timing.setText(value);
    }

    private void updateProgress() {
        boolean seekable = canSeek();
        if (!seekable) {
            if (timeBar.hasFocus() && playPause.isEnabled()) playPause.requestFocus();
            timeBar.setVisibility(View.GONE);
            timeBar.setEnabled(false);
            scrubbing = false;
            if (hasSession() && rendered) {
                long duration = player.getDuration();
                if (!player.isCurrentMediaItemLive() && duration > 0) updateClock(player.getCurrentPosition(), duration);
                else timing.setText(player.isCurrentMediaItemLive() ? "直播" : "不可拖动");
            } else timing.setText("");
            return;
        }
        timeBar.setVisibility(View.VISIBLE);
        timeBar.setEnabled(true);
        long duration = player.getDuration();
        timeBar.setKeyProgressIncrement((int) Math.max(1, Math.min(CLOCK_STEPS, Math.round(5000.0 * CLOCK_STEPS / duration))));
        timeBar.setSecondaryProgress((int) Math.min(CLOCK_STEPS, player.getBufferedPosition() * (double) CLOCK_STEPS / duration));
        if (!scrubbing) {
            long position = player.getCurrentPosition();
            timeBar.setProgress((int) Math.min(CLOCK_STEPS, position * (double) CLOCK_STEPS / duration));
            updateClock(position, duration);
        }
    }

    private void showFeedback(String message, boolean loading, boolean offerReturn) {
        if (!message.contentEquals(status.getText())) status.setText(message);
        status.setVisibility(View.VISIBLE);
        feedback.setVisibility(View.VISIBLE);
        busy.setVisibility(loading ? View.VISIBLE : View.GONE);
        returnToEpisodes.setVisibility(offerReturn ? View.VISIBLE : View.GONE);
    }

    private void updateAudioControl() {
        if (!hasSession()) return;
        boolean muted = player.getVolume() == 0f;
        mute.setSelected(muted);
        mute.setText(muted ? "已静音" : "有声音");
        mute.setContentDescription(muted ? "打开声音" : "静音");
    }

    private void updateChrome() {
        if (!hasSession()) return;
        int state = player.getPlaybackState();
        boolean ended = state == Player.STATE_ENDED;
        if (ended) {
            showFeedback(stepEpisode(1) >= 0 ? "本集已播完 · 可点击下一集" : "本剧已播完", false, false);
        } else if (state == Player.STATE_BUFFERING) {
            showFeedback(rendered ? "正在缓冲…" : "正在加载来源视频…", true, false);
        } else if (state == Player.STATE_READY && rendered) {
            status.setVisibility(View.GONE);
            feedback.setVisibility(View.GONE);
        }
        updateProgress();
        updateButtons();
    }

    // ---- Transport ----

    static MediaSource buildMediaSource(SourcePlaybackSessions.Session session, OkHttpClient client) {
        LoadErrorHandlingPolicy policy = new DefaultLoadErrorHandlingPolicy(0) {
            @Override public long getRetryDelayMsFor(LoadErrorInfo error) { return C.TIME_UNSET; }
            @Override public FallbackSelection getFallbackSelectionFor(FallbackOptions options, LoadErrorInfo error) { return null; }
        };
        MediaItem item = new MediaItem.Builder().setUri(Uri.parse(session.url.toASCIIString())).setMimeType(session.mimeType).build();
        return session.hls
            ? new HlsMediaSource.Factory((HlsDataSourceFactory) type -> new SourceDataSource(session, client, type))
                .setLoadErrorHandlingPolicy(policy).setDrmSessionManagerProvider(ignored -> DrmSessionManager.DRM_UNSUPPORTED)
                .createMediaSource(item)
            : new ProgressiveMediaSource.Factory(() -> new SourceDataSource(session, client, C.DATA_TYPE_MEDIA))
                .setLoadErrorHandlingPolicy(policy).setDrmSessionManagerProvider(ignored -> DrmSessionManager.DRM_UNSUPPORTED)
                .createMediaSource(item);
    }

    private void start() {
        OkHttpClient fixture = BuildConfig.DEBUG && FIXTURE_HOST.equals(session.url.getHost()) ? FIXTURE_CLIENT.getAndSet(null) : null;
        client = fixture == null ? mediaClient() : fixture;
        MediaSource media = buildMediaSource(session, client);
        player = new ExoPlayer.Builder(this)
            .setLoadControl(new DefaultLoadControl.Builder()
                .setBufferDurationsMs(10000, 30000, 1500, 2500)
                .setTargetBufferBytes(24 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false).build())
            .build();
        player.setVolume(0f); // Always mute before associating the source or preparing it.
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                rendered = true;
                updateChrome();
            }

            @Override public void onPlaybackStateChanged(int state) {
                buffering = state == Player.STATE_BUFFERING;
                if (state == Player.STATE_ENDED && !failed) {
                    completed = true;
                    userPaused = false;
                    int target = autoplay ? stepEpisode(1) : -1;
                    if (target >= 0) {
                        switchTo(target);
                        return;
                    }
                }
                updateChrome();
            }

            @Override public void onEvents(Player player, Player.Events events) { updateChrome(); }

            @Override public void onPlayerError(PlaybackException error) {
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    if (cause instanceof MediaStatus) {
                        int code = ((MediaStatus) cause).status;
                        fail(code == 401 || code == 403 ? "来源拒绝访问（HTTP " + code + "）"
                            : "来源媒体请求失败（HTTP " + code + "）");
                        return;
                    }
                    if (cause instanceof UnsupportedMedia) {
                        fail(cause.getMessage());
                        return;
                    }
                }
                fail("来源视频暂时无法播放");
            }
        });
        player.setMediaSource(media);
        applyRequestedSpeed();
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void fail(String message) {
        failed = true;
        main.removeCallbacks(progressTick);
        if (session != null) session.cancelRequests();
        mute.setEnabled(false);
        playPause.setEnabled(false);
        timeBar.setVisibility(View.GONE);
        timing.setText("");
        showFeedback(message + "。请返回选集后重新打开。", false, true);
        if (controlsHud != null) controlsHud.suspend();
        if (player != null) player.stop();
        updateButtons();
        returnToEpisodes.requestFocus();
    }

    @Override protected void onStart() {
        super.onStart();
        showControls();
        main.post(progressTick);
    }

    @Override protected void onStop() {
        main.removeCallbacks(progressTick);
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (session != null) SourcePlaybackSessions.close(session.id);
        if (controlsHud != null) controlsHud.destroy();
        playerView.setPlayer(null);
        if (player != null) player.release();
        player = null;
        super.onDestroy();
    }

    private static OkHttpClient mediaClient() {
        return new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(10000, TimeUnit.MILLISECONDS)
            .readTimeout(15000, TimeUnit.MILLISECONDS)
            .addNetworkInterceptor(chain -> {
                Response reply = chain.proceed(chain.request());
                int status = reply.code();
                // OkHttp may follow up these two responses even with
                // retryOnConnectionFailure disabled; this player stops instead.
                if (status == 421 || status == 503) {
                    reply.close();
                    throw new MediaStatus(status);
                }
                return reply;
            })
            .dns(host -> {
                try {
                    URI url = SourcePlaybackSessions.mediaAddress("https://" + host);
                    return Arrays.asList(NetworkGuard.sourceAddresses(url, System.nanoTime() + TimeUnit.SECONDS.toNanos(10)));
                } catch (IOException error) {
                    throw new UnknownHostException("来源媒体域名解析失败");
                }
            }).build();
    }

    private static final class MediaStatus extends IOException {
        final int status;
        MediaStatus(int status) { super("来源媒体请求失败（HTTP " + status + "）"); this.status = status; }
    }

    private static final class UnsupportedMedia extends IOException {
        UnsupportedMedia(String reason) { super(reason); }
    }

    private static final class SourceDataSource extends BaseDataSource {
        private static final int MAX_PLAYLIST = 2 * 1024 * 1024;
        private static final int MAX_FRAGMENT = 64 * 1024 * 1024;
        private static final long MAX_PROGRESSIVE = 1024L * 1024 * 1024;
        private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");
        private final SourcePlaybackSessions.Session session;
        private final OkHttpClient client;
        private final int type;
        private Response response;
        private Runnable cancel;
        private InputStream stream;
        private Uri uri;
        private long remaining, byteLimit;
        private boolean started;

        SourceDataSource(SourcePlaybackSessions.Session session, OkHttpClient client, int type) {
            super(true);
            this.session = session;
            this.client = client;
            this.type = type;
        }

        @Override public long open(DataSpec spec) throws IOException {
            transferInitializing(spec);
            try {
                return openChecked(spec);
            } catch (IOException | RuntimeException error) {
                close();
                if (error instanceof MediaStatus || error instanceof UnsupportedMedia) throw (IOException) error;
                if (!session.active() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("来源播放已取消");
                // The HTTP stack may embed an address with a signed query in its exceptions.
                throw new IOException("来源媒体连接失败");
            }
        }

        private long openChecked(DataSpec spec) throws IOException {
            if (spec.httpMethod != DataSpec.HTTP_METHOD_GET || spec.httpBody != null || spec.position < 0 ||
                spec.length != C.LENGTH_UNSET && spec.length <= 0) throw new IOException("来源媒体请求无效");
            int redirects = 0;
            URI target = SourcePlaybackSessions.mediaAddress(spec.uri.toString());
            String initialHost = target.getHost();
            boolean range = spec.position != 0 || spec.length != C.LENGTH_UNSET;
            long maxBytes = type == C.DATA_TYPE_MANIFEST ? MAX_PLAYLIST : type == C.DATA_TYPE_DRM ? 16 :
                session.hls ? MAX_FRAGMENT : MAX_PROGRESSIVE;
            if (spec.position >= MAX_PROGRESSIVE || spec.length > maxBytes || type != C.DATA_TYPE_MEDIA && range)
                throw new IOException("来源媒体范围无效");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(type == C.DATA_TYPE_MEDIA && session.hls ? 60 : 30);
            while (true) {
                if (!session.active()) throw new InterruptedIOException("来源播放已取消");
                Request.Builder request = new Request.Builder().url(target.toASCIIString())
                    .header("User-Agent", session.userAgent).header("Accept-Encoding", "identity");
                // Do not carry any caller-provided request headers. A cross-host
                // redirect does not inherit the fixed source Referer either.
                if (initialHost.equalsIgnoreCase(target.getHost()) && !session.referer.isEmpty()) request.header("Referer", session.referer);
                String cookie = session.cookieFor(target);
                if (!cookie.isEmpty()) request.header("Cookie", cookie);
                if (range) request.header("Range", "bytes=" + spec.position + "-" +
                    (spec.length == C.LENGTH_UNSET ? "" : spec.position + spec.length - 1));
                Call call = client.newCall(request.build());
                long timeout = deadline - System.nanoTime();
                if (timeout <= 0) throw new IOException("来源媒体请求超时");
                // Progressive MP4 can remain paused by downstream buffering;
                // its active reads are still bounded by the idle read timeout.
                if (session.hls) call.timeout().timeout(timeout, TimeUnit.NANOSECONDS);
                cancel = call::cancel;
                if (!session.begin(cancel)) throw new InterruptedIOException("来源播放已取消");
                response = call.execute();
                int code = response.code();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (++redirects > 3) throw new IOException("来源媒体跳转次数超限");
                    target = SourcePlaybackSessions.redirect(target, response.header("Location"));
                    finishConnection();
                    continue;
                }
                if (code != 200 && code != 206) throw new MediaStatus(code);
                if (!session.active()) throw new InterruptedIOException("来源播放已取消");
                uri = Uri.parse(target.toASCIIString());
                break;
            }

            ResponseBody body = response.body();
            if (body == null) throw new IOException("来源未返回媒体数据");
            String encoding = response.header("Content-Encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new UnsupportedMedia("来源媒体编码无法识别");
            String contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
            if (contentType.startsWith("text/html") || contentType.contains("json") || contentType.startsWith("application/xml"))
                throw new UnsupportedMedia("来源未返回视频内容");
            long length = checkedLength(spec, body.contentLength());
            if (length > maxBytes) throw new UnsupportedMedia("来源媒体超过大小限制");
            stream = body.byteStream();
            if (type == C.DATA_TYPE_MANIFEST || type == C.DATA_TYPE_DRM) {
                ByteArrayOutputStream snapshot = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (snapshot.size() + count > maxBytes) throw new UnsupportedMedia("来源媒体超过大小限制");
                    snapshot.write(buffer, 0, count);
                }
                byte[] bytes = snapshot.toByteArray();
                if (type == C.DATA_TYPE_DRM && bytes.length != 16) throw new UnsupportedMedia("来源 HLS 密钥格式无效");
                if (type == C.DATA_TYPE_MANIFEST) {
                    try { SourcePlaybackSessions.checkPlaylist(bytes); }
                    catch (IOException invalid) { throw new UnsupportedMedia(invalid.getMessage()); }
                }
                stream = new ByteArrayInputStream(bytes);
                length = bytes.length;
                finishConnection();
            }
            byteLimit = maxBytes;
            remaining = length;
            started = true;
            transferStarted(spec);
            return length;
        }

        private long checkedLength(DataSpec spec, long contentLength) throws IOException {
            if (response.code() == 206) {
                Matcher parsed = CONTENT_RANGE.matcher(response.header("Content-Range", ""));
                if (!parsed.matches()) throw new IOException("来源媒体范围响应无效");
                long first = Long.parseLong(parsed.group(1)), last = Long.parseLong(parsed.group(2));
                if (first != spec.position || last < first || last == Long.MAX_VALUE) throw new IOException("来源媒体范围响应不匹配");
                long length = last - first + 1;
                if (contentLength >= 0 && contentLength != length || spec.length != C.LENGTH_UNSET && length != spec.length ||
                    !"*".equals(parsed.group(3)) && Long.parseLong(parsed.group(3)) <= last)
                    throw new IOException("来源媒体范围响应不完整");
                return length;
            }
            if (spec.position != 0) throw new UnsupportedMedia("此来源视频不支持跳转");
            if (spec.length != C.LENGTH_UNSET) {
                if (contentLength >= 0 && contentLength < spec.length) throw new IOException("来源媒体范围响应不完整");
                return spec.length;
            }
            return contentLength == -1L ? C.LENGTH_UNSET : contentLength;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (!session.active()) throw new InterruptedIOException("来源播放已取消");
            if (length == 0) return 0;
            if (remaining == 0) return C.RESULT_END_OF_INPUT;
            try {
                if (byteLimit == 0) {
                    if (stream.read() == -1) return C.RESULT_END_OF_INPUT;
                    throw new UnsupportedMedia("来源媒体超过大小限制");
                }
                int count = stream.read(buffer, offset, (int) Math.min(length,
                    remaining == C.LENGTH_UNSET ? byteLimit : Math.min(remaining, byteLimit)));
                if (count < 0) {
                    if (remaining != C.LENGTH_UNSET) throw new EOFException();
                    return C.RESULT_END_OF_INPUT;
                }
                if (remaining != C.LENGTH_UNSET) remaining -= count;
                byteLimit -= count;
                bytesTransferred(count);
                return count;
            } catch (IOException error) {
                if (error instanceof UnsupportedMedia) throw error;
                if (!session.active() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("来源播放已取消");
                throw new IOException("来源媒体数据接收中断");
            }
        }

        @Override public Uri getUri() { return uri; }
        @Override public Map<String, List<String>> getResponseHeaders() {
            return response == null || response.header("Content-Type") == null ? Collections.emptyMap() :
                Collections.singletonMap("Content-Type", Collections.singletonList(response.header("Content-Type")));
        }

        private void finishConnection() {
            try { if (response != null && response.body() != null) response.close(); }
            finally {
                response = null;
                if (cancel != null) session.end(cancel);
                cancel = null;
            }
        }

        @Override public void close() {
            try { finishConnection(); }
            finally {
                uri = null;
                stream = null;
                if (started) {
                    started = false;
                    transferEnded();
                }
            }
        }
    }
}
