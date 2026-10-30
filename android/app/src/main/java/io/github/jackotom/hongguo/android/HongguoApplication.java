package io.github.jackotom.hongguo.android;

import android.app.Application;

/** Selects the on-device library before either activities or cast services start. */
public final class HongguoApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AccountSync.init(this);
    }
}
