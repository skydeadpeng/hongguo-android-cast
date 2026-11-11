package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONException;
import org.json.JSONObject;

/** A single native preference source shared by the settings bridge and every player. */
final class PlayerOptions {
    private static final String FILE = "player_options";
    static final float[] SPEEDS = { .75f, 1f, 1.25f, 1.5f, 2f };
    private static final Map<MediaPlayer, Float> confirmedSpeeds = Collections.synchronizedMap(new WeakHashMap<>());
    private PlayerOptions() { }
    private static SharedPreferences preferences(Context context) {
        Context app = context.getApplicationContext();
        return (app == null ? context : app).getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    static String quality(String input) {
        if (!"auto".equals(input) && !"720p".equals(input) && !"1080p".equals(input)) throw new IllegalArgumentException("清晰度仅支持自动、720p 或 1080p");
        return input;
    }
    static float speed(double input) {
        for (float value : SPEEDS) if (Double.isFinite(input) && Math.abs(input - value) < .00001) return value;
        throw new IllegalArgumentException("倍速仅支持 0.75、1、1.25、1.5 或 2");
    }
    static void validateSpeed(double input) { speed(input); }
    static float observedSpeed(MediaPlayer player) {
        try {
            float result = player.getPlaybackParams().getSpeed();
            if (Float.isFinite(result) && result > 0) { confirmedSpeeds.put(player, result); return result; }
            // NuPlayer reports the transport rate 0 while paused. It does not mean that
            // the selected speed disappeared. Only reuse this same instance's readback,
            // never the user's unverified preference or a different decoder's value.
            if (result == 0 && !player.isPlaying()) {
                Float confirmed = confirmedSpeeds.get(player);
                return confirmed == null ? Float.NaN : confirmed;
            }
            confirmedSpeeds.remove(player); return Float.NaN;
        } catch (RuntimeException error) { confirmedSpeeds.remove(player); return Float.NaN; }
    }
    /** setPlaybackParams may start a paused player; always restore the transport state. */
    static float setVerifiedSpeed(MediaPlayer player, float requested, boolean playing) throws Exception {
        requested = speed(requested);
        float previous = observedSpeed(player);
        if (!Float.isFinite(previous)) {
            // A fresh prepared/paused decoder may also report 0 before its first rate
            // setting. Try the requested rate, but accept it only after positive readback.
            float raw = player.getPlaybackParams().getSpeed();
            if (raw != 0 || player.isPlaying()) throw new IllegalStateException("设备未返回实际倍速");
        }
        try {
            player.setPlaybackParams(new PlaybackParams().allowDefaults().setSpeed(requested));
            // This verification must be a fresh positive read, not the paused-zero cache.
            float actual = player.getPlaybackParams().getSpeed();
            if (!Float.isFinite(actual) || actual <= 0 || Math.abs(actual - requested) > .001f) throw new IllegalStateException("设备未接受此倍速");
            confirmedSpeeds.put(player, actual);
            if (playing) { if (!player.isPlaying()) player.start(); } else player.pause();
            return actual;
        } catch (RuntimeException error) {
            if (Float.isFinite(previous)) try { player.setPlaybackParams(new PlaybackParams().allowDefaults().setSpeed(previous)); observedSpeed(player); } catch (RuntimeException ignored) { }
            try { if (playing) { if (!player.isPlaying()) player.start(); } else player.pause(); } catch (RuntimeException ignored) { }
            throw new IllegalStateException("设备未接受此倍速", error);
        }
    }
    static synchronized JSONObject read(Context context) throws JSONException {
        SharedPreferences prefs = preferences(context);
        String selected = "auto"; float rate = 1f; boolean danmaku = false;
        try { selected = quality(prefs.getString("quality", "auto")); } catch (RuntimeException ignored) { }
        try { rate = speed(Double.parseDouble(prefs.getString("speed", "1.0"))); } catch (RuntimeException ignored) { }
        try { danmaku = "true".equals(prefs.getString("danmaku", "false")); } catch (RuntimeException ignored) { }
        return new JSONObject().put("quality", selected).put("speed", (double) rate).put("danmaku", danmaku);
    }
    /** Validate the whole update before committing either field. A failed commit is not success. */
    static synchronized JSONObject update(Context context, JSONObject changes) throws Exception {
        if (changes == null) throw new IllegalArgumentException("播放设置无效");
        JSONObject prior = read(context);
        String selected = prior.getString("quality"); float rate = (float) prior.getDouble("speed"); boolean danmaku = prior.getBoolean("danmaku");
        if (changes.has("quality")) {
            Object value = changes.get("quality");
            if (!(value instanceof String)) throw new IllegalArgumentException("清晰度设置无效");
            selected = quality((String) value);
        }
        if (changes.has("speed")) {
            Object value = changes.get("speed");
            if (!(value instanceof Number)) throw new IllegalArgumentException("倍速设置无效");
            rate = speed(((Number) value).doubleValue());
        }
        if (changes.has("danmaku")) {
            if (!(changes.opt("danmaku") instanceof Boolean)) throw new IllegalArgumentException("弹幕开关无效");
            danmaku = changes.getBoolean("danmaku");
        }
        SharedPreferences prefs = preferences(context);
        boolean committed = false;
        try { committed = prefs.edit().putString("quality", selected).putString("speed", Float.toString(rate)).putString("danmaku", Boolean.toString(danmaku)).commit(); }
        finally {
            // Android updates memory before reporting a disk-write failure. Restore that memory
            // too, so another Activity never observes a change which this call rejected.
            if (!committed) try { prefs.edit().putString("quality", prior.getString("quality")).putString("speed", Float.toString((float) prior.getDouble("speed"))).putString("danmaku", prior.getBoolean("danmaku") ? "true" : "false").commit(); } catch (RuntimeException ignored) { }
        }
        if (!committed) throw new IllegalStateException("播放设置保存未成功");
        return new JSONObject().put("quality", selected).put("speed", (double) rate).put("danmaku", danmaku);
    }
}
