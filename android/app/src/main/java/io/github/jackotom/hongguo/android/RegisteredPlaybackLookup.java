package io.github.jackotom.hongguo.android;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Java playback lookup shared by phone, WebView TV and native TV. No Activity or account state. */
final class RegisteredPlaybackLookup {
    private RegisteredPlaybackLookup() {}

    static JSONObject resolve(String sid, String vid, String quality, int ceiling, long timeoutMs) throws Exception {
        if (timeoutMs <= 0) throw new SocketTimeoutException("App片源准备超时，请重试");
        long budget = Math.min(TimeUnit.MILLISECONDS.toNanos(timeoutMs), RegisteredPlaybackModel.MAX_NANOS);
        long deadline = System.nanoTime() + budget;
        return new AppMediaSource(requestedVid -> {
            try (RegisteredPlaybackModel model = new RegisteredPlaybackModel(deadline)) {
                return model.fetch(requestedVid);
            } catch (RegisteredPlaybackModel.ModelError error) {
                throw new AppMediaSource.AppSourceException(error.getMessage(), error.code);
            }
        }).resolve(vid, quality, ceiling == 0 ? null : Integer.valueOf(ceiling));
    }
}
