package io.github.jackotom.hongguo.android;

/** Main-thread-only idle policy. Media and Activity state decide when hiding is allowed. */
final class PlayerHud {
    static final long HIDE_DELAY_MS = 4000;
    interface Scheduler { void cancel(Runnable action); void later(Runnable action, long delay); }
    interface Surface { void visible(boolean visible); }
    private final Scheduler scheduler;
    private final Surface surface;
    private boolean eligible, visible = true, destroyed;
    private final Runnable hide;
    PlayerHud(Scheduler scheduler, Surface surface) {
        this.scheduler = scheduler; this.surface = surface;
        hide = () -> { if (!destroyed && eligible && visible) { visible = false; surface.visible(false); } };
    }
    boolean isVisible() { return visible; }
    void playback(boolean canHide) {
        if (destroyed || eligible == canHide) return;
        eligible = canHide;
        if (!canHide) reveal();
        else if (visible) schedule();
    }
    void interact() { if (!destroyed) { reveal(); if (eligible) schedule(); } }
    void suspend() { if (!destroyed) { eligible = false; reveal(); } }
    void destroy() { destroyed = true; eligible = false; scheduler.cancel(hide); }
    private void reveal() { scheduler.cancel(hide); visible = true; surface.visible(true); }
    private void schedule() { scheduler.cancel(hide); scheduler.later(hide, HIDE_DELAY_MS); }
}
