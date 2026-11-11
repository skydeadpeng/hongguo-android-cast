package io.github.jackotom.hongguo.android;

import java.util.*;

/** Executes the production idle policy with a deterministic main-loop clock. */
public final class PlayerHudTest {
    static int checks;
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static final class Clock implements PlayerHud.Scheduler {
        long now; int hidden, shown; boolean visible = true; Runnable last;
        final Map<Runnable, Long> jobs = new HashMap<>();
        public void cancel(Runnable action) { jobs.remove(action); }
        public void later(Runnable action, long delay) { jobs.put(action, now + delay); last = action; }
        void advance(long millis) {
            long target = now + millis;
            while (!jobs.isEmpty()) {
                Map.Entry<Runnable, Long> first = Collections.min(jobs.entrySet(), Map.Entry.comparingByValue());
                if (first.getValue() > target) break;
                now = first.getValue(); Runnable work = first.getKey(); jobs.remove(work); work.run();
            }
            now = target;
        }
        PlayerHud hud() { return new PlayerHud(this, value -> { visible = value; if (value) shown++; else hidden++; }); }
    }
    public static void main(String[] args) {
        Clock c = new Clock(); PlayerHud h = c.hud();
        check(h.isVisible(), "initially visible");
        c.advance(20000); check(c.visible && c.jobs.isEmpty(), "no timer before real playback");
        h.playback(true); check(c.jobs.size() == 1, "playing schedules one timer");
        for (int i = 0; i < 7; i++) { c.advance(500); h.playback(true); }
        check(c.visible && c.jobs.size() == 1, "progress ticks do not replace the deadline");
        c.advance(499); check(c.visible, "visible until full four seconds");
        c.advance(1); check(!h.isVisible() && !c.visible && c.hidden == 1, "idle playback hides at exactly four seconds");
        c.advance(40000); check(c.hidden == 1 && c.jobs.isEmpty(), "hidden HUD does not schedule a busy loop");
        h.interact(); check(h.isVisible() && c.visible && c.jobs.size() == 1, "tap reveals and starts one new deadline");
        c.advance(3500); h.interact(); c.advance(3999); check(c.visible, "interaction resets the complete countdown");
        c.advance(1); check(!c.visible, "reset deadline eventually hides");
        h.playback(false); check(c.visible && c.jobs.isEmpty(), "pause/loading/dialog reveals and cancels");
        Runnable stale = c.last; stale.run(); check(c.visible, "late cancelled callback cannot hide a blocked HUD");
        c.advance(20000); check(c.visible, "blocked controls remain visible indefinitely");
        h.playback(true); h.suspend(); c.advance(20000);
        check(c.visible && c.jobs.isEmpty(), "activity pause cancels the timer and clears eligibility");
        h.interact(); check(c.jobs.isEmpty(), "paused interaction cannot re-arm timer");
        h.playback(true); check(c.jobs.size() == 1, "resume gets a fresh deadline");
        h.destroy(); stale = c.last; c.advance(20000); stale.run();
        check(c.visible && c.jobs.isEmpty(), "destroy cancels and rejects late callbacks");
        int shown = c.shown; h.interact(); h.playback(true); h.suspend();
        check(c.shown == shown && c.jobs.isEmpty(), "destroyed HUD never mutates another surface");
        for (int i = 0; i < 20; i++) {
            Clock separate = new Clock(); PlayerHud player = separate.hud(); player.playback(true); separate.advance(PlayerHud.HIDE_DELAY_MS);
            check(!separate.visible, "each player owns its own timer " + i);
            player.playback(false); check(separate.visible, "completion/loading reveals after idle " + i);
        }
        System.out.println("PASS PlayerHudTest checks=" + checks + " actualProductionPolicy=true delayMs=4000 noNetwork=true deviceUntouched=true");
    }
}
