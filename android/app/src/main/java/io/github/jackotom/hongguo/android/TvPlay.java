package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.content.Intent;
import java.io.IOException;

/** Launch gate shared by the native TV screens: update installs never race the player. */
final class TvPlay {
    private TvPlay() { }
    static void launch(Activity activity, Intent intent) {
        try (UpdateOperationGate.Lease lease = UpdateInstallCheckpoint.operation()) {
            UpdateInstallCheckpoint.playerLaunching();
            activity.startActivity(intent);
        } catch (IOException busy) {
            TvUi.error(activity, busy.getMessage(), null);
        } catch (RuntimeException failure) {
            TvUi.error(activity, TvUi.message(failure), null);
        }
    }
}
