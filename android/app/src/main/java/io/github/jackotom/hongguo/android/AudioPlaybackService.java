package io.github.jackotom.hongguo.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phone-only background / screen-off audio playback.
 *
 * <p>The player's片源 always lands as a local mp4 in {@link PlaybackCache}; this service replays
 * that same file with a {@link MediaPlayer} that is never given a Surface, so only the audio
 * track is decoded. A foreground notification, a {@link MediaSession} (headset + lock-screen
 * controls), a PARTIAL_WAKE_LOCK and audio focus keep playback alive with the screen off.</p>
 *
 * <p>Queue navigation reuses {@link PlaybackSources} / {@link PlaybackCache} from the same package;
 * progress is written back through {@link LibraryStore} so returning to {@link PlayerActivity}
 * resumes at the right position. All classes used here are package-private peers.</p>
 */
public final class AudioPlaybackService extends Service {
    private static final String PREFIX = "io.github.jackotom.hongguo.audio.";
    static final String ACTION_START = PREFIX + "START";
    static final String ACTION_TOGGLE = PREFIX + "TOGGLE";
    static final String ACTION_PLAY = PREFIX + "PLAY";
    static final String ACTION_PAUSE = PREFIX + "PAUSE";
    static final String ACTION_NEXT = PREFIX + "NEXT";
    static final String ACTION_PREV = PREFIX + "PREV";
    static final String ACTION_STOP = PREFIX + "STOP";
    static final String EXTRA_LAUNCH = PREFIX + "LAUNCH";
    static final String EXTRA_POSITION = PREFIX + "POSITION";
    static final String EXTRA_QUALITY = PREFIX + "QUALITY";
    static final String EXTRA_CEILING = PREFIX + "CEILING";
    static final String EXTRA_SPEED = PREFIX + "SPEED";
    static final String EXTRA_URI = PREFIX + "URI";
    static final String EXTRA_AUTOPLAY = PREFIX + "AUTOPLAY";

    private static final String CHANNEL = "hongguo_audio";
    private static final int NOTIFICATION = 4207;
    private static final long SAVE_INTERVAL_MS = 15_000L;

    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
    static boolean active() { return ACTIVE.get(); }

    /**
     * Snapshot of what the audio service is currently playing, read by PlayerActivity when the
     * user returns to the app so video resumes at the exact episode/position (including any
     * episode switches made from the lock-screen/notification controls). Same process only.
     */
    private static volatile Bundle lastSnapshot;
    static Bundle snapshot() { return lastSnapshot == null ? null : new Bundle(lastSnapshot); }
    private void publishSnapshot() {
        if (episode == null || launchSeries == null) return;
        Bundle b = new Bundle();
        b.putString("series", launchSeries.toString());
        b.putString("episode", episode.toString());
        b.putInt("position", position());
        b.putFloat("speed", speed);
        b.putBoolean("playing", playing);
        lastSnapshot = b;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService resolver = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hongguo-audio-source"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    private LibraryStore store;
    private PlaybackCache cache;
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private boolean channelCreated, focusHeld, ducking, resumeOnFocus, noiseReceiverRegistered;

    // Playback state, rebuilt from the launch bundle handed over by PlayerActivity.
    private Bundle launch;
    private JSONObject series, episode;
    private JSONObject launchSeries;             // original top-level series, for returning to the player
    private JSONArray queue;
    private int current, ceiling;
    private String quality;
    private float speed = 1f;
    private boolean autoplay = true, offline;
    private int startPosition;
    private String directUri;               // optional, already-prepared local file for the first episode
    private volatile Future<?> resolution;
    private volatile int generation;
    private boolean playing, prepared, completionHandled;
    private long lastSavedAt;

    private final BroadcastReceiver noise = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pause();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        store = new LibraryStore(this);
        cache = PlaybackCache.get(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSession(this, "hongguo.audio");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(callback);
        session.setActive(true);
        ACTIVE.set(true);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) { stopPlayback(); return START_NOT_STICKY; }
        if (ACTION_TOGGLE.equals(action)) { if (playing) pause(); else play(); return START_NOT_STICKY; }
        if (ACTION_PLAY.equals(action)) { play(); return START_NOT_STICKY; }
        if (ACTION_PAUSE.equals(action)) { pause(); return START_NOT_STICKY; }
        if (ACTION_NEXT.equals(action)) { move(1); return START_NOT_STICKY; }
        if (ACTION_PREV.equals(action)) { move(-1); return START_NOT_STICKY; }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        Bundle incoming = intent.getBundleExtra(EXTRA_LAUNCH);
        if (incoming == null) { stopPlayback(); return START_NOT_STICKY; }
        boolean fresh = launch == null;
        launch = new Bundle(incoming);
        quality = intent.getStringExtra(EXTRA_QUALITY);
        ceiling = intent.getIntExtra(EXTRA_CEILING, 0);
        speed = intent.getFloatExtra(EXTRA_SPEED, 1f);
        autoplay = intent.getBooleanExtra(EXTRA_AUTOPLAY, true);
        if (speed <= 0 || speed > 4) speed = 1f;
        if (fresh) {
            startPosition = Math.max(0, intent.getIntExtra(EXTRA_POSITION, 0));
            directUri = intent.getStringExtra(EXTRA_URI);
            try {
                launchSeries = LibraryStore.series(new JSONObject(launch.getString("series")));
                series = launchSeries;
                JSONObject selected = LibraryStore.episode(new JSONObject(launch.getString("episode")));
                String descriptor = launch.getString("playlist");
                if (descriptor != null && !descriptor.isEmpty() && launch.getBoolean("resolveInPlayer", false)) {
                    queue = PlaybackPlaylist.parse(new JSONObject(descriptor), series, selected);
                } else {
                    queue = new JSONArray(launch.getString("episodes"));
                }
                offline = launch.getBoolean("download", false);
                current = findCurrent(selected);
                if (current < 0 || queue.length() == 0) throw new IllegalArgumentException("音频队列无效");
                // Keep the queue element verbatim (it may carry "_series" for cross-season
                // queues). Cleaning it with LibraryStore.episode would drop the owner and
                // break source lookup when a later episode belongs to another season.
                episode = queue.getJSONObject(current);
                series = PlaybackPlaylist.owner(series, episode);
            } catch (Exception error) {
                Toast.makeText(this, "后台播放信息无效：" + error.getMessage(), Toast.LENGTH_LONG).show();
                stopPlayback(); return START_NOT_STICKY;
            }
            startForegroundSafely();
            acquireLocks();
            if (!noiseReceiverRegistered) {
                registerReceiver(noise, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
                noiseReceiverRegistered = true;
            }
        }
        prepareAndPlay(startPosition, directUri);
        return START_NOT_STICKY;
    }

    private int findCurrent(JSONObject selected) {
        String sid = series.optString("seriesId"), vid = selected.optString("vid");
        int index = selected.optInt("index");
        for (int i = 0; i < queue.length(); i++) {
            JSONObject e = queue.optJSONObject(i);
            if (e != null && vid.equals(e.optString("vid"))) return i;
        }
        for (int i = 0; i < queue.length(); i++) {
            JSONObject e = queue.optJSONObject(i);
            if (e != null && index == e.optInt("index")
                    && sid.equals(PlaybackPlaylist.owner(series, e).optString("seriesId"))) return i;
        }
        return -1;
    }

    private void prepareAndPlay(int resumeAt, String uriHint) {
        final int token = ++generation;
        playing = false; prepared = false; completionHandled = false; startPosition = Math.max(0, resumeAt);
        updateSession(PlaybackState.STATE_BUFFERING, startPosition);
        releasePlayer();
        Future<?> previous = resolution;
        if (previous != null) previous.cancel(true);
        resolution = resolver.submit(() -> {
            try {
                if (token != generation || destroyed.get()) return;
                File media = resolveLocal(uriHint);
                if (media == null) throw new IllegalStateException("音频片源未就绪，请先在播放器打开本集");
                final File ready = media;
                main.post(() -> {
                    if (token != generation || destroyed.get()) return;
                    attachAndPlay(ready, token);
                });
            } catch (Exception error) {
                final String message = error.getMessage() == null ? "音频片源准备失败" : error.getMessage();
                main.post(() -> {
                    if (token != generation || destroyed.get()) return;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    updateSession(PlaybackState.STATE_STOPPED, 0);
                    notifyNow("准备失败", message);
                });
            }
        });
    }

    /** Resolve to an on-device mp4, reusing the same source+cache path as the video player. */
    private File resolveLocal(String uriHint) throws Exception {
        if (uriHint != null && !uriHint.isEmpty()) {
            File direct = fileFromUri(uriHint);
            if (direct != null && direct.isFile() && direct.length() > 0) return direct;
        }
        String sid = PlaybackPlaylist.owner(series, episode).optString("seriesId");
        String vid = episode.optString("vid");
        int index = episode.optInt("index");
        if (sid.isEmpty() || vid.isEmpty()) throw new IllegalStateException("分集信息无效");
        File cached = cache.find(sid, vid, index, quality, ceiling);
        if (cached != null && cached.isFile() && cached.length() > 0) return cached;
        PlaybackSources.requireCurrentSource(sid, vid);
        PlaybackSources.Plan plan = PlaybackSources.resolvePlayback(sid, vid, quality, ceiling, false, false, RegisteredPlaybackLookup::resolve);
        return cache.fetchForeground(sid, vid, index, plan, quality);
    }

    private File fileFromUri(String value) {
        try {
            android.net.Uri uri = android.net.Uri.parse(value);
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new File(uri.getPath());
        } catch (Exception ignored) { }
        return null;
    }

    private void attachAndPlay(File file, int token) {
        if (destroyed.get() || token != generation) return;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(file.getAbsolutePath());
            mp.setOnPreparedListener(ready -> {
                if (token != generation || destroyed.get() || player != ready) return;
                keepAudioOnly(ready);
                // MediaPlayer has no setPlaybackSpeed(); use PlaybackParams instead (API 23+).
                try {
                    if (Float.isFinite(speed) && speed > 0) {
                        android.media.PlaybackParams params = new android.media.PlaybackParams();
                        params.setSpeed(speed);
                        ready.setPlaybackParams(params);
                    }
                } catch (Exception ignored) { }
                int duration = ready.getDuration();
                int seek = duration > 0 ? Math.min(startPosition, Math.max(0, duration - 250)) : startPosition;
                if (seek > 0) { try { ready.seekTo(seek); } catch (Exception ignored) { } }
                prepared = true;
                ready.start();
                playing = true;
                requestFocus();
                updateSession(PlaybackState.STATE_PLAYING, ready.getCurrentPosition(), duration);
                notifyPlaying();
            });
            mp.setOnCompletionListener(finished -> {
                if (token != generation || destroyed.get() || completionHandled) return;
                completionHandled = true;
                saveProgress();
                if (autoplay && move(1)) return;
                playing = false; prepared = false;
                updateSession(PlaybackState.STATE_STOPPED, 0);
                notifyNow("播放完毕", "后台音频已停止");
            });
            mp.setOnErrorListener((failed, what, extra) -> {
                if (token != generation || destroyed.get()) return true;
                Toast.makeText(this, "音频播放失败，请回到播放器重试", Toast.LENGTH_LONG).show();
                playing = false; prepared = false;
                updateSession(PlaybackState.STATE_STOPPED, 0);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception error) {
            releasePlayer();
            Toast.makeText(this, "音频初始化失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Deselect video tracks so the decoder never renders a picture in the background. */
    private static void keepAudioOnly(MediaPlayer mp) {
        try {
            MediaPlayer.TrackInfo[] tracks = mp.getTrackInfo();
            for (int i = 0; i < tracks.length; i++) {
                // Only the video track is dropped; no Surface is ever attached, so no frame is
                // rendered. Audio selection is left to the decoder default to avoid re-prepares
                // on picky devices, but we explicitly deselect video to save the decoder.
                if (tracks[i].getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) mp.deselectTrack(i);
            }
        } catch (Exception ignored) { /* Some decoders reject track changes; no Surface is set anyway. */ }
    }

    private void play() {
        if (destroyed.get()) return;
        if (prepared && player != null) {
            if (requestFocus()) resumeOnFocus = false;
            try { player.start(); playing = true; } catch (Exception ignored) { }
        } else if (episode != null) {
            prepareAndPlay(startPosition, null);
        }
        updateSession(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position());
        notifyPlaying();
    }

    private void pause() {
        if (player != null && playing) { try { player.pause(); } catch (Exception ignored) { } }
        playing = false;
        saveProgress();
        updateSession(PlaybackState.STATE_PAUSED, position());
        notifyPlaying();
    }

    /** Move within the queue; returns true when another episode started. */
    private boolean move(int delta) {
        if (queue == null || queue.length() == 0) return false;
        int next = current + delta;
        if (next < 0 || next >= queue.length()) {
            if (delta > 0 && !autoplay) return false;
            if (next < 0) return false;
            return false;
        }
        JSONObject target = queue.optJSONObject(next);
        if (target == null) return false;
        saveProgress();
        current = next;
        episode = target;
        series = PlaybackPlaylist.owner(series, episode);
        launch.putString("episode", episode.toString());
        // Force a fresh lookup of the newly selected episode when the user returns to video.
        launch.remove("url"); launch.remove("playInfo");
        startPosition = 0; directUri = null;
        prepareAndPlay(0, null);
        return true;
    }

    private int position() {
        if (player == null || !prepared) return startPosition;
        try { return Math.max(0, player.getCurrentPosition()); } catch (Exception ignored) { return startPosition; }
    }

    private void seekTo(long ms) {
        if (!prepared || player == null) return;
        try { player.seekTo((int) Math.min(Integer.MAX_VALUE, Math.max(0, ms))); } catch (Exception ignored) { }
        updateSession(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position());
    }

    private void saveProgress() {
        if (episode == null) return;
        int pos = position();
        if (pos <= 0 && System.currentTimeMillis() - lastSavedAt < SAVE_INTERVAL_MS) return;
        try {
            long duration = 0;
            if (player != null && prepared) { try { duration = Math.max(0, player.getDuration()); } catch (Exception ignored) { } }
            store.history(series, episode, pos, duration);
            lastSavedAt = System.currentTimeMillis();
        } catch (Exception ignored) { /* History is best effort in background audio. */ }
        publishSnapshot();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed.get()) return;
            if (playing && prepared) {
                int pos = position();
                updateSession(PlaybackState.STATE_PLAYING, pos);
                publishSnapshot();  // keep the return-to-video position fresh every second
                if (System.currentTimeMillis() - lastSavedAt >= SAVE_INTERVAL_MS) saveProgress();
            }
            main.postDelayed(this, 1000);
        }
    };

    private final MediaSession.Callback callback = new MediaSession.Callback() {
        @Override public void onPlay() { play(); }
        @Override public void onPause() { pause(); }
        @Override public void onStop() { stopPlayback(); }
        @Override public void onSkipToNext() { move(1); }
        @Override public void onSkipToPrevious() { move(-1); }
        @Override public void onSeekTo(long pos) { seekTo(pos); }
    };

    private boolean requestFocus() {
        if (audioManager == null) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        switch (focusChange) {
                            case AudioManager.AUDIOFOCUS_GAIN:
                                if (ducking && player != null) { try { player.setVolume(1f, 1f); } catch (Exception ignored) { } ducking = false; }
                                if (resumeOnFocus && !playing) { resumeOnFocus = false; play(); }
                                break;
                            case AudioManager.AUDIOFOCUS_LOSS:
                                focusHeld = false; resumeOnFocus = false; pause();
                                break;
                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                                resumeOnFocus = playing; pause();
                                break;
                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                                if (player != null) { try { player.setVolume(0.25f, 0.25f); } catch (Exception ignored) { } ducking = true; }
                                break;
                            default: break;
                        }
                    }).build();
            }
            int result = audioManager.requestAudioFocus(focusRequest);
            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            focusHeld = audioManager.requestAudioFocus(focusLegacy, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return focusHeld;
    }

    private final AudioManager.OnAudioFocusChangeListener focusLegacy = change -> {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (ducking && player != null) { try { player.setVolume(1f, 1f); } catch (Exception ignored) { } ducking = false; }
            if (resumeOnFocus && !playing) { resumeOnFocus = false; play(); }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            focusHeld = false; resumeOnFocus = false; pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeOnFocus = playing; pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && player != null) {
            try { player.setVolume(0.25f, 0.25f); } catch (Exception ignored) { } ducking = true;
        }
    };

    private void abandonFocus() {
        if (!focusHeld || audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        else audioManager.abandonAudioFocus(focusLegacy);
        focusHeld = false;
    }

    private void acquireLocks() {
        if (cpu == null) {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            cpu = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hongguo:background-audio");
            cpu.setReferenceCounted(false);
        }
        if (!cpu.isHeld()) cpu.acquire();
        WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (manager != null) {
            // WIFI_MODE_FULL is deprecated but present on every supported API level; the
            // HIGH_PERF/LOW_LATENCY constants are API 29+ and would throw on older devices.
            if (wifi == null) { wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL, "hongguo:background-audio"); wifi.setReferenceCounted(false); }
            if (!wifi.isHeld()) wifi.acquire();
        }
        main.removeCallbacks(tick); main.post(tick);
    }

    private void releaseLocks() {
        if (wifi != null) { if (wifi.isHeld()) wifi.release(); wifi = null; }
        if (cpu != null) { if (cpu.isHeld()) cpu.release(); cpu = null; }
        main.removeCallbacks(tick);
    }

    private void releasePlayer() {
        MediaPlayer mp = player;
        player = null; prepared = false; playing = false;
        if (mp != null) {
            try { mp.setOnPreparedListener(null); mp.setOnCompletionListener(null); mp.setOnErrorListener(null); } catch (Exception ignored) { }
            try { if (mp.isPlaying()) mp.stop(); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
        }
    }

    private void startForegroundSafely() {
        try {
            ensureChannel();
            startForeground(NOTIFICATION, buildNotification(),
                Build.VERSION.SDK_INT >= 29 ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);
        } catch (Exception error) {
            // Foreground start can be restricted by the system; playback without it still runs
            // briefly, but the notification keeps the session discoverable when allowed.
            try { startForeground(NOTIFICATION, buildNotification()); } catch (Exception ignored) { }
        }
    }

    private void ensureChannel() {
        if (channelCreated || Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "红果后台音频", NotificationManager.IMPORTANCE_LOW));
        channelCreated = true;
    }

    private PendingIntent action(String action) {
        return PendingIntent.getService(this, action.hashCode(), new Intent(this, AudioPlaybackService.class).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        ensureChannel();
        PendingIntent open = PendingIntent.getActivity(this, 1, backToPlayerIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        String title = series == null ? "红果后台播放" : series.optString("title", "红果后台播放");
        String sub = episode == null ? "仅播放音频" : episodeTitle(episode);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(sub).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= 21) {
            builder.setStyle(new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        }
        builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "上一集", action(ACTION_PREV)).build());
        builder.addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
            playing ? "暂停" : "播放", action(ACTION_TOGGLE)).build());
        builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "下一集", action(ACTION_NEXT)).build());
        builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "退出", action(ACTION_STOP)).build());
        return builder.build();
    }

    private void notifyNow(String title, String sub) {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, buildNotification()); }
        catch (Exception ignored) { }
    }

    private void notifyPlaying() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION, buildNotification());
        } catch (Exception ignored) { }
    }

    private static String episodeTitle(JSONObject episode) {
        String title = episode.optString("title", "");
        if (title.isEmpty()) title = "第" + episode.optInt("index") + "集";
        return title + " · 仅播放音频";
    }

    private void updateSession(int state, int pos) { updateSession(state, pos, 0); }

    private void updateSession(int state, int pos, long duration) {
        if (session == null) return;
        try {
            long total = duration > 0 ? duration : 0;
            PlaybackState.Builder builder = new PlaybackState.Builder()
                .setState(state, Math.max(0, pos), playing && state == PlaybackState.STATE_PLAYING ? speed : 1f)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP);
            session.setPlaybackState(builder.build());
            if (episode != null) {
                MediaMetadata.Builder meta = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, series == null ? "红果" : series.optString("title", "红果"))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, episodeTitle(episode))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, total);
                session.setMetadata(meta.build());
            }
        } catch (Exception ignored) { }
    }

    private Intent backToPlayerIntent() {
        Intent intent = new Intent(this, PlayerActivity.class);
        if (launch != null) {
            Bundle back = new Bundle(launch);
            back.remove("url"); back.remove("playInfo");
            if (episode != null) back.putString("episode", episode.toString());
            if (launchSeries != null) back.putString("series", launchSeries.toString());
            intent.putExtras(back);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    private void stopPlayback() {
        saveProgress();
        generation++;
        Future<?> pending = resolution;
        if (pending != null) pending.cancel(true);
        releasePlayer();
        abandonFocus();
        releaseLocks();
        if (noiseReceiverRegistered) { try { unregisterReceiver(noise); } catch (Exception ignored) { } noiseReceiverRegistered = false; }
        try { if (session != null) { session.setActive(false); session.release(); } } catch (Exception ignored) { }
        session = null;
        ACTIVE.set(false);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (destroyed.compareAndSet(false, true)) {
            saveProgress();
            generation++;
            Future<?> pending = resolution;
            if (pending != null) pending.cancel(true);
            releasePlayer();
            abandonFocus();
            releaseLocks();
            if (noiseReceiverRegistered) { try { unregisterReceiver(noise); } catch (Exception ignored) { } noiseReceiverRegistered = false; }
            try { if (session != null) { session.setActive(false); session.release(); } } catch (Exception ignored) { }
            session = null;
            ACTIVE.set(false);
            resolver.shutdownNow();
        }
        super.onDestroy();
    }
}
