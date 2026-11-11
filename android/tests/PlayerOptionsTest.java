package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import java.util.*;
import org.json.*;

/** Real PlayerOptions code; memory/disk-failure and decoder behavior are explicit local doubles. */
public final class PlayerOptionsTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String message) throws Exception { try { action.run(); } catch (Exception expected) { checks++; return; } throw new AssertionError(message); }
    private static final class Preferences implements SharedPreferences {
        final Map<String,Object> data = new HashMap<>(); boolean writable = true; int commits;
        public String getString(String key, String fallback) { Object value = data.get(key); return value == null ? fallback : (String) value; }
        public Editor edit() { return new Editor() {
            final Map<String,Object> edits = new HashMap<>();
            public Editor putString(String key, String value) { edits.put(key,value); return this; }
            // Like SharedPreferencesImpl, memory changes even when the disk write fails.
            public boolean commit() { commits++; data.putAll(edits); return writable; }
            public void apply() { commit(); }
        }; }
    }
    private static final class MemoryContext extends Context {
        final Preferences prefs; final Context app; String requestedFile;
        MemoryContext(Preferences prefs) { this(prefs, null); }
        MemoryContext(Preferences prefs, Context app) { this.prefs=prefs; this.app=app; }
        public Context getApplicationContext() { return app == null ? this : app; }
        public SharedPreferences getSharedPreferences(String name, int mode) { requestedFile=name; return prefs; }
    }
    public static void main(String[] args) throws Exception {
        Preferences prefs = new Preferences(); MemoryContext app = new MemoryContext(prefs);
        for(String internal:new String[]{"480p","360p"}) rejects(()->PlayerOptions.quality(internal),"internal recovery grade must not become a manual download preference");
        JSONObject defaults = PlayerOptions.read(app);
        check(defaults.getString("quality").equals("auto") && defaults.getDouble("speed")==1, "defaults auto/1");
        check(prefs.commits==0 && prefs.data.isEmpty(), "read has no mutation");
        check("player_options".equals(app.requestedFile), "one native preference file");
        check(!defaults.getBoolean("danmaku"), "danmaku is opt-in by default");
        check(PlayerOptions.update(app,new JSONObject().put("danmaku",true)).getBoolean("danmaku"), "enable danmaku after commit");
        check(PlayerOptions.read(new MemoryContext(prefs)).getBoolean("danmaku"), "danmaku survives new context");
        String savedDanmaku=PlayerOptions.read(app).toString();
        for(Object bad:new Object[]{"true",1,JSONObject.NULL}) { rejects(()->PlayerOptions.update(app,new JSONObject().put("danmaku",bad)),"invalid danmaku accepted");check(savedDanmaku.equals(PlayerOptions.read(app).toString()),"invalid danmaku update remains atomic"); }
        PlayerOptions.update(app,new JSONObject().put("danmaku",false));
        check(!PlayerOptions.read(app).getBoolean("danmaku"),"disable danmaku");
        for (String q : new String[]{"auto","720p","1080p"}) {
            JSONObject value=PlayerOptions.update(app,new JSONObject().put("quality",q));
            check(q.equals(value.getString("quality")), "quality returned only after commit");
            check(q.equals(PlayerOptions.read(new MemoryContext(prefs)).getString("quality")), "quality survives new context");
        }
        for (float speed : PlayerOptions.SPEEDS) {
            PlayerOptions.validateSpeed(speed);
            JSONObject value=PlayerOptions.update(app,new JSONObject().put("speed",(double)speed));
            check(value.getDouble("speed")==speed, "supported numeric speed");
            check(PlayerOptions.read(new MemoryContext(prefs)).getDouble("speed")==speed, "speed survives new activity");
            check(PlayerOptions.read(app).getString("quality").equals("1080p"), "partial speed preserves quality");
        }
        for (Object bad : new Object[]{JSONObject.NULL, true, 720, "720", "720P", "auto ", "4k", "", new JSONObject()}) {
            String before=PlayerOptions.read(app).toString(); int commits=prefs.commits;
            rejects(()->PlayerOptions.update(app,new JSONObject().put("quality",bad)), "invalid quality accepted");
            check(before.equals(PlayerOptions.read(app).toString()) && commits==prefs.commits, "validation atomic before write");
        }
        for (Object bad : new Object[]{JSONObject.NULL, true, "1.5", 0, -1, 3, .5, 1.2, new JSONObject()}) {
            String before=PlayerOptions.read(app).toString();
            rejects(()->PlayerOptions.update(app,new JSONObject().put("quality","720p").put("speed",bad)), "invalid speed accepted");
            check(before.equals(PlayerOptions.read(app).toString()), "invalid speed does not partly change quality");
        }
        for (double bad : new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.MAX_VALUE}) rejects(()->PlayerOptions.validateSpeed(bad), "nonfinite speed accepted");
        rejects(()->PlayerOptions.update(app,null), "null update accepted");
        PlayerOptions.update(app,new JSONObject().put("quality","720p").put("speed",1.25));
        String before=PlayerOptions.read(app).toString(); prefs.writable=false;
        rejects(()->PlayerOptions.update(app,new JSONObject().put("quality","1080p").put("speed",2)), "failed disk commit returned success");
        check(before.equals(PlayerOptions.read(app).toString()), "failed disk commit restores in-memory previous options"); prefs.writable=true;
        for (Object bad : new Object[]{"corrupt",123,true}) {
            prefs.data.put("quality",bad);prefs.data.put("speed",bad);
            JSONObject value=PlayerOptions.read(app);check(value.getString("quality").equals("auto")&&value.getDouble("speed")==1,"corrupt persisted values use defaults");
        }
        Preferences unrelated=new Preferences(); MemoryContext wrapper=new MemoryContext(unrelated,app);
        PlayerOptions.update(wrapper,new JSONObject().put("quality","1080p").put("speed",1.5));
        check(unrelated.data.isEmpty()&&PlayerOptions.read(app).getDouble("speed")==1.5,"application context prevents activity preference forks");
        for (float rate : PlayerOptions.SPEEDS) for (boolean playing : new boolean[]{false,true}) {
            MediaPlayer player=new MediaPlayer();player.playing=playing;
            check(PlayerOptions.setVerifiedSpeed(player,rate,playing)==rate,"confirmed actual rate");
            check(player.playing==playing,"paused/playing state survives implicit-start setPlaybackParams");
        }
        MediaPlayer unsupported=new MediaPlayer();unsupported.playing=false;unsupported.rejectAbove=1;
        rejects(()->PlayerOptions.setVerifiedSpeed(unsupported,2,false),"unsupported rate accepted");
        check(!unsupported.playing&&unsupported.rate==1,"unsupported exception restores old rate and pause");
        MediaPlayer ignored=new MediaPlayer();ignored.playing=true;ignored.ignoreAbove=1;
        rejects(()->PlayerOptions.setVerifiedSpeed(ignored,1.5f,true),"silently ignored rate falsely accepted");
        check(ignored.playing&&ignored.rate==1,"readback mismatch preserves playing and prior rate");
        MediaPlayer noState=new MediaPlayer();noState.readUnsupported=true;
        check(Float.isNaN(PlayerOptions.observedSpeed(noState)),"unsupported query is unknown, not fake 1x");
        rejects(()->PlayerOptions.setVerifiedSpeed(noState,1,false),"unknown actual rate accepted");
        check(noState.setCalls==0,"unsupported query never mutates playback");
        MediaPlayer nonfinite=new MediaPlayer();nonfinite.rate=Float.NaN;
        check(Float.isNaN(PlayerOptions.observedSpeed(nonfinite)),"invalid decoder state remains unknown");
        MediaPlayer pausedZero=new MediaPlayer();pausedZero.pausedReadsZero=true;pausedZero.playing=true;pausedZero.rate=2;
        check(PlayerOptions.observedSpeed(pausedZero)==2,"playing positive actual rate is confirmed");
        pausedZero.pause();
        check(pausedZero.getPlaybackParams().getSpeed()==0,"Android NuPlayer paused-zero behavior is simulated");
        check(PlayerOptions.observedSpeed(pausedZero)==2,"paused zero retains same-instance verified 2x, not preference");
        check(PlayerOptions.setVerifiedSpeed(pausedZero,1.5f,false)==1.5f,"paused speed update verifies positive actual readback");
        check(!pausedZero.playing&&PlayerOptions.observedSpeed(pausedZero)==1.5f,"paused speed update stays paused and retains confirmed value");
        MediaPlayer oldQuality=new MediaPlayer();oldQuality.pausedReadsZero=true;oldQuality.playing=true;
        check(PlayerOptions.setVerifiedSpeed(oldQuality,2,true)==2,"old quality actually confirms 2x while playing");oldQuality.pause();
        float carried=PlayerOptions.observedSpeed(oldQuality);check(carried==2,"quality transition carries verified old-player value");
        MediaPlayer candidate=new MediaPlayer();candidate.pausedReadsZero=true;
        check(Float.isNaN(PlayerOptions.observedSpeed(candidate)),"fresh paused decoder never inherits another instance's confirmed rate");
        check(PlayerOptions.setVerifiedSpeed(candidate,carried,false)==2,"fresh paused candidate gets 2x and positive readback before pause");
        check(!candidate.playing&&candidate.getPlaybackParams().getSpeed()==0&&PlayerOptions.observedSpeed(candidate)==2,"candidate paused-zero readback preserves its own confirmed 2x");
        check(!oldQuality.playing&&oldQuality.rate==2,"preparing candidate does not mutate or resume old player");
        candidate.start();check(PlayerOptions.observedSpeed(candidate)==2&&candidate.rate==2,"resume after paused quality switch really stays 2x");
        candidate.rate=1.25f;check(PlayerOptions.observedSpeed(candidate)==1.25f,"playing actual readback replaces prior confirmation");
        candidate.rate=0;check(Float.isNaN(PlayerOptions.observedSpeed(candidate)),"playing zero remains unknown, never stale positive success");
        MediaPlayer freshUnsupported=new MediaPlayer();freshUnsupported.pausedReadsZero=true;freshUnsupported.rejectAbove=1;
        rejects(()->PlayerOptions.setVerifiedSpeed(freshUnsupported,2,false),"fresh paused decoder rejection is not success");
        check(!freshUnsupported.playing&&Float.isNaN(PlayerOptions.observedSpeed(freshUnsupported)),"failed fresh paused setting stays paused without fake confirmation");
        MediaPlayer stale=new MediaPlayer();stale.pausedReadsZero=true;stale.rate=2;stale.playing=true;
        check(PlayerOptions.observedSpeed(stale)==2,"stale-test decoder first confirms positive actual 2x");stale.pause();stale.ignoreAllSets=true;
        rejects(()->PlayerOptions.setVerifiedSpeed(stale,2,false),"paused cached confirmation cannot fake a fresh positive setting readback");
        check(!stale.playing&&PlayerOptions.observedSpeed(stale)==2,"failed unchanged request preserves previous confirmed paused state without reporting success");
        System.out.println("PASS PlayerOptionsTest checks="+checks+" preferenceCommit=true failedCommitMemoryRestored=true speedReadback=true pausePreserved=true mediaPlayerDouble=true networkRequests=0 deviceOperations=0");
    }
}
