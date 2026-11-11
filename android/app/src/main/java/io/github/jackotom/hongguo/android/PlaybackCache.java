package io.github.jackotom.hongguo.android;

import android.content.Context;
import java.io.File;
import org.json.JSONObject;
import java.util.List;
import java.util.ArrayList;

/** Small facade; callers perform file/network methods on a background executor. */
final class PlaybackCache {
    static final long BUDGET_BYTES = 256L * 1024 * 1024;
    static final long CAST_BUDGET_BYTES = 768L * 1024 * 1024;
    static final long MAX_FILE_BYTES = 96L * 1024 * 1024;
    private static PlaybackCache instance;
    private final PlaybackCachePolicy policy;
    private PlaybackCache(Context context) {
        policy = new PlaybackCachePolicy(new File(context.getApplicationContext().getCacheDir(), "playback-v2"), BUDGET_BYTES, MAX_FILE_BYTES, CAST_BUDGET_BYTES, PlaybackSources::download);
    }
    static synchronized PlaybackCache get(Context context) {
        if (instance == null) instance = new PlaybackCache(context);
        return instance;
    }
    static final class CastWindow implements AutoCloseable {
        private final PlaybackCachePolicy.CastWindow lease;
        private CastWindow(PlaybackCachePolicy.CastWindow lease) { this.lease = lease; }
        void moveTo(int currentIndex) { lease.moveTo(currentIndex); }
        @Override public void close() { lease.close(); }
    }
    CastWindow openCastWindow(String sid, int currentIndex) { return new CastWindow(policy.openCastWindow(sid, currentIndex)); }
    CastWindow openCastWindow(String sid, int currentIndex, boolean cleanup) { return new CastWindow(policy.openCastWindow(sid, currentIndex, cleanup)); }
    static final class QueueWindow implements AutoCloseable {
        private final PlaybackCachePolicy.QueueWindow lease;
        private QueueWindow(PlaybackCachePolicy.QueueWindow lease) { this.lease=lease; }
        void moveTo(int position) { lease.moveTo(position); }
        void prune() { lease.prune(); }
        @Override public void close() { lease.close(); }
    }
    QueueWindow openQueueWindow(JSONObject fallback,List<JSONObject> episodes,int position,boolean cleanup,int ahead,boolean casting) {
        List<PlaybackCachePolicy.QueueEntry> entries=new ArrayList<>();
        for(JSONObject episode:episodes)entries.add(new PlaybackCachePolicy.QueueEntry(PlaybackPlaylist.owner(fallback,episode).optString("seriesId"),episode.optInt("index")));
        return new QueueWindow(policy.openQueueWindow(entries,position,cleanup,ahead,casting));
    }
    File find(String sid, String vid, int index) throws Exception { return find(sid, vid, index, "auto"); }
    void protect(String sid, String vid, int index) { protect(sid, vid, index, "auto"); }
    void release(String sid, String vid, int index) { release(sid, vid, index, "auto"); }
    File fetch(String sid, String vid, int index, String url) throws Exception { return fetch(sid, vid, index, url, "auto"); }
    // Only legacy auto files lack complete-source provenance. Remove that exact
    // owned entry on use; do not clear explicit qualities, downloads or the window.
    private void discardLegacyAuto(String sid,String vid,int index,String quality) throws Exception {
        if (MediaQuality.normalize(quality).equals("auto")) policy.invalidate(sid,vid,index);
    }
    File find(String sid,String vid,int index,String quality) throws Exception { discardLegacyAuto(sid,vid,index,quality); return policy.find(sid,MediaQuality.cacheVid(vid,quality),index); }
    void protect(String sid,String vid,int index,String quality) { policy.protect(sid,MediaQuality.cacheVid(vid,quality),index); }
    void release(String sid,String vid,int index,String quality) { policy.release(sid,MediaQuality.cacheVid(vid,quality),index); }
    File fetch(String sid,String vid,int index,String url,String quality) throws Exception { discardLegacyAuto(sid,vid,index,quality); return policy.fetch(sid,MediaQuality.cacheVid(vid,quality),index,url,(value,file,max)->PlaybackSources.download(value,file,max,quality)); }
    File fetchForeground(String sid,String vid,int index,String url,String quality) throws Exception { discardLegacyAuto(sid,vid,index,quality); return policy.fetchForeground(sid,MediaQuality.cacheVid(vid,quality),index,url,(value,file,max)->PlaybackSources.download(value,file,max,quality)); }
    void invalidate(String sid,String vid,int index,String quality) throws Exception { policy.invalidate(sid,MediaQuality.cacheVid(vid,quality),index); }
    static String playbackCacheVid(String vid,String quality,int ceiling) {
        if(ceiling<0||ceiling>16384)throw new IllegalArgumentException("播放缓存清晰度边界无效");
        return MediaQuality.cacheVid(vid,quality)+(ceiling>0?"|playback-ceiling="+ceiling:"");
    }
    File find(String sid,String vid,int index,String quality,int ceiling) throws Exception {
        discardLegacyAuto(sid,vid,index,quality);return policy.find(sid,playbackCacheVid(vid,quality,ceiling),index);
    }
    void protect(String sid,String vid,int index,String quality,int ceiling) {policy.protect(sid,playbackCacheVid(vid,quality,ceiling),index);}
    void release(String sid,String vid,int index,String quality,int ceiling) {policy.release(sid,playbackCacheVid(vid,quality,ceiling),index);}
    void invalidate(String sid,String vid,int index,String quality,int ceiling) throws Exception {policy.invalidate(sid,playbackCacheVid(vid,quality,ceiling),index);}
    File fetch(String sid,String vid,int index,PlaybackSources.Plan plan,String quality) throws Exception {
        if(plan==null||!MediaQuality.normalize(quality).equals(plan.requestedQuality)||!sid.equals(plan.seriesId)||!vid.equals(plan.vid))throw new IllegalArgumentException("播放缓存片源身份或清晰度不匹配");
        discardLegacyAuto(sid,vid,index,quality);
        return policy.fetch(sid,playbackCacheVid(vid,quality,plan.playbackCeiling),index,plan.url,(value,file,max)->PlaybackSources.download(plan,file,max));
    }
    File fetchForeground(String sid,String vid,int index,PlaybackSources.Plan plan,String quality) throws Exception {
        if(plan==null||!MediaQuality.normalize(quality).equals(plan.requestedQuality)||!sid.equals(plan.seriesId)||!vid.equals(plan.vid))throw new IllegalArgumentException("播放缓存片源身份或清晰度不匹配");
        discardLegacyAuto(sid,vid,index,quality);
        return policy.fetchForeground(sid,playbackCacheVid(vid,quality,plan.playbackCeiling),index,plan.url,(value,file,max)->PlaybackSources.download(plan,file,max));
    }
    void prune(String sid, int currentIndex, int keepPrevious) throws Exception { policy.prune(sid, currentIndex, keepPrevious); }
    JSONObject status() throws Exception {
        PlaybackCachePolicy.Stats stats = policy.status();
        return new JSONObject().put("files", stats.files).put("bytes", stats.bytes).put("keepPreviousEpisodes", 1).put("budgetBytes", policy.budgetBytes()).put("maxFileBytes", MAX_FILE_BYTES);
    }
    JSONObject clear() throws Exception {
        PlaybackCachePolicy.Stats stats = policy.clear();
        return new JSONObject().put("removed", stats.removed).put("bytes", stats.bytes).put("remainingBytes", policy.status().bytes);
    }
}
