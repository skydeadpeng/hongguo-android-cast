package io.github.jackotom.hongguo.android;

/** Executes extracted production Activity methods against deterministic UI/lifecycle doubles. */
public final class PlayerHudActivityTest {
    static int checks;
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static PlayerHudHarness ready() {
        PlayerHudHarness p = new PlayerHudHarness(); p.active = true; p.prepared = true; p.video.playing = true; p.sync(); return p;
    }
    static boolean visible(PlayerHudHarness p) { return p.top.visibility == View.VISIBLE && p.bottom.visibility == View.VISIBLE; }
    static void key(PlayerHudHarness p, int code, int action) { p.dispatchKeyEvent(new KeyEvent(action, code)); }
    public static void main(String[] args) {
        PlayerHudHarness p = ready();
        p.main.advance(3999); check(visible(p), "loading-to-playing keeps controls for full grace period");
        p.main.advance(1); check(p.top.visibility == View.GONE && p.bottom.visibility == View.GONE, "production Activity hides RETURN and controls together");
        check(p.video.hasFocus(), "hidden controls return focus to playback surface");
        int calls = p.baseTouchCalls;
        p.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN));
        check(visible(p) && p.baseTouchCalls == calls + 1 && p.baseSawTop == View.GONE, "first tap dispatches to video before showing controls, no accidental button activation");
        p.main.advance(10000); check(visible(p), "a held touch prevents hiding");
        p.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_UP)); p.main.advance(3999); check(visible(p), "touch release arms a full new deadline");
        p.main.advance(1); check(!visible(p), "idle playback hides after touch release");
        p.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN)); p.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_CANCEL));
        p.main.advance(4000); check(!visible(p) && !p.touchingControls, "cancelled touch releases interaction lock");
        for (int key : new int[]{KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT}) {
            PlayerHudHarness tv = ready(); tv.main.advance(4000); int forwarded = tv.baseKeyCalls;
            key(tv, key, KeyEvent.ACTION_DOWN); check(visible(tv) && tv.toggle.hasFocus(), "TV wake focuses an actionable control " + key);
            key(tv, key, KeyEvent.ACTION_DOWN); key(tv, key, KeyEvent.ACTION_UP);
            check(tv.baseKeyCalls == forwarded && tv.toggleCalls == 0 && tv.seekCalls == 0, "first wake press/repeats/up only reveal, never pause/seek " + key);
            key(tv, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN); check(tv.baseKeyCalls > forwarded, "following navigation reaches native controls " + key);
            tv.main.advance(4000); check(!visible(tv), "TV wake shares auto-hide countdown " + key);
        }
        PlayerHudHarness blocked = ready(); blocked.toggle.enabled = false; blocked.main.advance(4000);
        key(blocked, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN); check(blocked.back.hasFocus(), "wake falls back to RETURN if playback control unavailable");
        PlayerHudHarness back = ready(); back.main.advance(4000);
        key(back, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN); check(back.finishes == 0 && back.baseKeyCalls == 0, "Back down never pauses or opens a HUD layer");
        key(back, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP); check(back.finishes == 1 && back.saves == 1, "one Back exits and saves exactly once even when controls hidden");
        check(back.main.jobs.isEmpty(), "exit cancels idle callback immediately");
        for (int mode = 0; mode < 9; mode++) {
            PlayerHudHarness state = ready(); state.main.advance(4000);
            switch (mode) {
                case 0: state.prepared = false; break;
                case 1: state.completed = true; break;
                case 2: state.userPaused = true; break;
                case 3: state.buffering = true; break;
                case 4: state.dragging = true; break;
                case 5: state.hudDialogs = 1; break;
                case 6: state.qualitySwitch = new Object(); break;
                case 7: state.video.playing = false; break;
                case 8: state.finishing = true; break;
            }
            state.sync(); check(visible(state) && state.main.jobs.isEmpty(), "blocked playback state reveals and cancels " + mode);
            state.main.advance(10000); check(visible(state), "blocked state stays visible " + mode);
        }
        PlayerHudHarness dialogPlayer = ready(); dialogPlayer.main.advance(4000); AlertDialog dialog = new AlertDialog();
        dialogPlayer.openDialog(dialog); check(dialogPlayer.hudDialogs == 1 && visible(dialogPlayer) && dialog.shown, "dialog pins controls before it opens");
        dialogPlayer.main.advance(20000); check(visible(dialogPlayer) && dialogPlayer.main.jobs.isEmpty(), "open episode/quality/speed dialog never loses controls");
        dialog.dismiss(); check(dialogPlayer.hudDialogs == 0 && visible(dialogPlayer), "dismiss releases only this player dialog");
        dialogPlayer.main.advance(4000); check(!visible(dialogPlayer), "dismiss starts fresh playing countdown");
        PlayerHudHarness info = ready(); info.generation = 12; info.attachInfo(info.video, 12); info.main.advance(4000);
        info.video.info(MediaPlayer.MEDIA_INFO_BUFFERING_START); check(info.buffering && visible(info), "real media info handler reveals while buffering");
        info.main.advance(20000); check(visible(info), "buffering does not hide RETURN");
        info.video.info(MediaPlayer.MEDIA_INFO_BUFFERING_END); info.main.advance(4000); check(!info.buffering && !visible(info), "buffer completion restores idle policy");
        info.generation++; info.video.info(MediaPlayer.MEDIA_INFO_BUFFERING_START); check(!info.buffering && !visible(info), "stale episode info cannot mutate current HUD");
        PlayerHudHarness paused = ready(); paused.onPause(); check(!paused.active && !paused.video.playing && visible(paused) && paused.main.jobs.isEmpty(), "Activity pause saves, pauses and removes HUD deadline");
        paused.main.advance(10000); check(visible(paused), "background player never hides another window");
        paused.onResume(); paused.main.advance(3999); check(paused.video.playing && visible(paused), "resume restores playback and grace period");
        paused.main.advance(1); check(!visible(paused), "resume idle eventually hides");
        PlayerHudHarness userPaused = ready(); userPaused.userPaused = true; userPaused.onPause(); userPaused.onResume(); userPaused.main.advance(10000);
        check(!userPaused.video.playing && visible(userPaused), "manual pause survives lifecycle and stays operable");
        PlayerHudHarness dead = ready(); dead.onDestroy(); dead.main.advance(10000);
        check(dead.destroyed && dead.main.jobs.isEmpty() && dead.video.stops == 1 && dead.resolver.shutdown && dead.cacheWorker.shutdown, "destroy cleans timer plus existing playback workers");
        dead.showControls(); dead.sync(); check(dead.main.jobs.isEmpty(), "post-destroy callback cannot re-arm HUD");
        // Vertical swipes switch episodes; horizontal drags and short moves never do.
        PlayerHudHarness swipes = ready(); swipes.current = 1;
        for (int i = 0; i < 3; i++) swipes.queue.add(new JSONObject());
        swipes.video.touch.touch(swipes.video, new MotionEvent(MotionEvent.ACTION_DOWN, 100, 400));
        swipes.video.touch.touch(swipes.video, new MotionEvent(MotionEvent.ACTION_UP, 96, 200));
        check(swipes.moveCalls == 1 && swipes.lastMove == 1 && swipes.current == 2, "an upward swipe advances to the next episode");
        swipes.video.touch.touch(swipes.video, new MotionEvent(MotionEvent.ACTION_DOWN, 100, 200));
        swipes.video.touch.touch(swipes.video, new MotionEvent(MotionEvent.ACTION_UP, 100, 430));
        check(swipes.moveCalls == 2 && swipes.lastMove == -1 && swipes.current == 1, "a downward swipe returns to the previous episode");
        int moves = swipes.moveCalls;
        swipes.video.touch.touch(swipes.video, new MotionEvent(MotionEvent.ACTION_DOWN, 100, 200));
        swipes.video.touch.touch(swipes.video, new MotionEvent(MotionEvent.ACTION_UP, 320, 210));
        check(swipes.moveCalls == moves && Toast.messages.isEmpty(), "a horizontal drag never switches episodes");
        swipes.video.touch.touch(swipes.video, new MotionEvent(MotionEvent.ACTION_DOWN, 100, 200));
        swipes.video.touch.touch(swipes.video, new MotionEvent(MotionEvent.ACTION_UP, 100, 230));
        check(swipes.moveCalls == moves && Toast.messages.isEmpty(), "a short vertical drag is ignored");
        swipes.current = 0;
        swipes.video.touch.touch(swipes.video, new MotionEvent(MotionEvent.ACTION_DOWN, 100, 200));
        swipes.video.touch.touch(swipes.video, new MotionEvent(MotionEvent.ACTION_UP, 100, 440));
        check(swipes.moveCalls == moves && Toast.messages.size() == 1 && Toast.messages.get(0).equals("已经是第一集"), "the first episode reports the boundary instead of moving");
        swipes.current = 2;
        swipes.video.touch.touch(swipes.video, new MotionEvent(MotionEvent.ACTION_DOWN, 100, 400));
        swipes.video.touch.touch(swipes.video, new MotionEvent(MotionEvent.ACTION_UP, 100, 180));
        check(swipes.moveCalls == moves && Toast.messages.size() == 2 && Toast.messages.get(1).equals("已经是最后一集"), "the last episode reports the boundary instead of moving");
        System.out.println("PASS PlayerHudActivityTest checks=" + checks + " actualActivityMethods=true topAndBottomTogether=true phoneTouch=true swipeEpisode=true tvWake=true lifecycle=true mediaInfo=true noNetwork=true deviceUntouched=true");
    }
}
