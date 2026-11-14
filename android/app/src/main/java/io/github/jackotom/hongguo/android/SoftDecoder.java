package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.net.Uri;
import org.json.JSONObject;

/** Bridge to the tvNative-only software decoder layer (libVLC, which ships an HEVC software
 *  decoder). Loaded reflectively so the phone/TV flavors, which do not ship the decoder,
 *  compile and run unchanged. */
final class SoftDecoder {
    /** PlayerActivity-side callbacks for the decoder layer's lifecycle. */
    interface Host {
        JSONObject series();
        JSONObject episode();
        void onSoftEnded();                                  // one episode finished; player queues the next
        void onSoftClosed();                                 // user dismissed the overlay
        void onSoftProgress(long positionMs, long durationMs);
        void onSoftFailed(String message);                   // software decode could not play either
    }
    private SoftDecoder() {}
    static boolean available() {
        try { Class.forName("io.github.jackotom.hongguo.android.VlcSoftLayer"); return true; }
        catch (Throwable missing) { return false; }
    }
    /** Builds and shows the overlay. Returns the layer handle, or null when libVLC failed to start. */
    static Object start(Host host, Activity activity, Uri uri, String userAgent, String referer, long startAtMs, String title) {
        try {
            Class<?> type = Class.forName("io.github.jackotom.hongguo.android.VlcSoftLayer");
            Object layer = type.getDeclaredConstructor().newInstance();
            boolean shown = (Boolean) type.getMethod("start", SoftDecoder.Host.class, Activity.class, Uri.class, String.class, String.class, long.class, String.class)
                .invoke(layer, host, activity, uri, userAgent, referer, startAtMs, title);
            return shown ? layer : null;
        } catch (Throwable error) { return null; }
    }
    static void stop(Object layer) {
        if (layer == null) return;
        try { layer.getClass().getMethod("stop").invoke(layer); } catch (Throwable ignored) { }
    }
}
