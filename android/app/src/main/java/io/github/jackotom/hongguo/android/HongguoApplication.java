package io.github.jackotom.hongguo.android;

import android.app.Application;
import android.util.Log;

/** Selects the on-device library before either activities or cast services start. */
public final class HongguoApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // The account chain compiles against plain JVM stubs in offline harnesses; the real sink lives here.
        AccountLog.setSink((level, tag, message) -> { if ("E".equals(level)) Log.e(tag, message); else Log.w(tag, message); });
        AccountSync.init(this);
        // The registered playback identity survives process restarts on disk, like the
        // working TV clients' device store; a served identity keeps being served.
        RegisteredPlaybackModel.identityStore(new java.io.File(getFilesDir(), "registered-identity.json"));
    }
}
