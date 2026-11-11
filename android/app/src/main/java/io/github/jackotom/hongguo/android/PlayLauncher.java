package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Single construction point of the player Intent shared by the WebView bridge and the native TV UI. */
final class PlayLauncher {
    private PlayLauncher() { }
    static Intent build(Context context, JSONObject series, JSONObject episode, JSONArray episodes, String url, boolean local, JSONObject params) throws JSONException {
        JSONObject defaults = PlayerOptions.read(context);
        String quality = MediaQuality.normalize(params.optString("quality", defaults.getString("quality")));
        double speed = params.optDouble("speed", defaults.getDouble("speed"));
        PlayerOptions.validateSpeed(speed);
        Intent intent = new Intent(context, PlayerActivity.class).putExtra("series", series.toString()).putExtra("episode", episode.toString())
            .putExtra("episodes", episodes.toString()).putExtra("url", url).putExtra("download", local)
            .putExtra("quality", quality).putExtra("speed", speed)
            .putExtra("autoplay", params.optBoolean("autoplay", true)).putExtra("cleanupPlaybackCache", params.optBoolean("cleanupPlaybackCache", true))
            .putExtra("keepPreviousEpisodes", 1).putExtra("playInfo", params.optJSONObject("playInfo") == null ? "" : params.getJSONObject("playInfo").toString());
        // The compact descriptor crosses Binder once; decorated per-episode owners
        // exist only inside the player/services, never duplicated in Intent extras.
        if (!local && params.optJSONObject("playlist") != null) intent.putExtra("playlist", params.getJSONObject("playlist").toString());
        return intent;
    }
}
