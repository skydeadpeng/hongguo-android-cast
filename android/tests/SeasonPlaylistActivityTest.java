package io.github.jackotom.hongguo.android;

import java.util.*;
import org.json.*;

/** Real Playlist parser and extracted production Activity methods; only Android/transport/cache boundaries are doubles. */
public final class SeasonPlaylistActivityTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static JSONObject season(String sid,String title)throws Exception{return new JSONObject().put("seriesId",sid).put("title",title).put("episodeCount",2);}
    static JSONObject row(String sid,String vid,int index)throws Exception{return new JSONObject().put("seriesId",sid).put("vid",vid).put("index",index).put("availability","unknown");}
    static AdaptiveActivityHarness fresh()throws Exception{
        PlaybackSources.reset();AdaptiveActivityHarness p=new AdaptiveActivityHarness();
        JSONObject a=season("111111","示例：第一季"),b=season("222222","示例：第二季");
        JSONArray rows=new JSONArray().put(row("111111","1001",1)).put(row("111111","1002",2)).put(row("222222","1001",1)).put(row("222222","2002",2));
        JSONObject descriptor=new JSONObject().put("version",1).put("series",new JSONArray().put(a).put(b)).put("episodes",rows);
        JSONArray parsed=PlaybackPlaylist.parse(descriptor,a,rows.getJSONObject(0));
        p.queue.clear();for(int i=0;i<parsed.length();i++)p.queue.add(parsed.getJSONObject(i));
        p.series=a;p.queueSeries=a;p.episode=p.queue.get(0);p.current=0;p.actualQuality="1080p";return p;
    }
    static void ready(AdaptiveActivityHarness p,int position)throws Exception{p.loadAt(position,null,0);p.pump();p.video.complete(1080,1920);}
    static String key(String sid,String vid,int index,String q){return sid+":"+vid+":"+index+":"+q;}
    static void currentSeasonBoundary()throws Exception {
        AdaptiveActivityHarness local=fresh();local.autoplay=true;
        check(!local.restoreSelection(null)&&local.queue.size()==2&&local.current==0,"fresh legacy descriptor is limited to its selected season before loading");
        check(local.queueIdentity.equals("111111")&&local.queueSeries.optString("seriesId").equals("111111"),"phone/TV queue identity is the selected real season");
        ready(local,0);local.video.completion.complete(local.video.player);local.pump();local.video.complete(1080,1920);
        check(local.current==1&&local.episode.optInt("index")==2&&!local.completed,"same-season automatic next episode stays enabled");
        local.video.completion.complete(local.video.player);local.pump();
        check(local.current==1&&local.completed&&local.status.text.contains("本剧已播完"),"season final completes without advancing to the next season");
        check(PlaybackSources.owners.stream().noneMatch(v->v.startsWith("222222:")),"local prefetch never resolves a removed next-season episode");
        check(local.store.ownedHistories.stream().noneMatch(v->v.startsWith("222222:")),"season ending never claims next-season watch history");
        local.selectEpisode();check(local.dialog.labels.length==2,"native chooser contains this season only");

        Bundle saved=new Bundle();saved.putString("seriesId","222222");saved.putString("vid","1001");saved.putInt("episodeIndex",1);saved.putInt("position",45678);
        AdaptiveActivityHarness recreated=fresh();
        check(recreated.restoreSelection(saved)&&recreated.current==0&&recreated.queue.size()==2,"old later-season saved selection restores before queue trimming");
        check(recreated.queueIdentity.equals("222222")&&recreated.queueSeries.optString("seriesId").equals("222222"),"restored current season gets a stable local cast identity");
        check(recreated.queue.get(0).optString("vid").equals("1001")&&recreated.queue.get(0).optInt("index")==1,"duplicate VID/index from first season cannot replace restored real owner");
        recreated.loadAt(recreated.current,null,saved.getInt("position",0));recreated.pump();recreated.video.complete(1080,1920);
        check(recreated.series.optString("seriesId").equals("222222")&&recreated.resumeAt==45678,"restored later-season media keeps its saved position");
        check(PlaybackSources.owners.stream().noneMatch(v->v.startsWith("111111:")),"restored later-season loading does not resolve the original first season");
        recreated.video.position=54321;Bundle again=new Bundle();recreated.savePlaybackState(again);
        check(again.getString("seriesId","").equals("222222")&&again.getInt("position",0)==54321,"watch progress persists with the selected later-season owner");

        AdaptiveActivityHarness manuallySelected=fresh();manuallySelected.current=3;
        check(!manuallySelected.restoreSelection(null)&&manuallySelected.current==1&&manuallySelected.queue.size()==2,"explicit later-season selection remaps its queue position without changing local index");
        check(manuallySelected.queue.get(1).optInt("index")==2&&manuallySelected.queueIdentity.equals("222222"),"manual later-season playback remains available with the correct identity");
        Bundle unknown=new Bundle();unknown.putString("seriesId","999999");unknown.putString("vid","1001");unknown.putInt("episodeIndex",1);
        AdaptiveActivityHarness invalid=fresh();check(!invalid.restoreSelection(unknown)&&invalid.current==0&&invalid.queue.size()==2&&invalid.queueIdentity.equals("111111"),"unknown saved season retains the explicit Intent selection and does not expand the queue");
        System.out.println("SEASON_STOP_ACTIVITY_OK sameSeasonAutoNext=true crossSeasonAutoNext=false legacyRestore=laterSeason networkRequests=0");
    }
    public static void main(String[]args)throws Exception{
        currentSeasonBoundary();
        // Remaining cases deliberately bypass Activity initialization to stress old owner/lease callbacks.
        AdaptiveActivityHarness p=fresh();p.autoplay=true;ready(p,1);p.pump();
        check(p.queue.get(1).optInt("index")==2&&p.queue.get(2).optInt("index")==1,"flat queue retains each season's local index");
        check(PlaybackSources.owners.contains("222222:1001:1080p"),"last episode prefetch resolves next season's actual owner");
        VideoView.Completion previousCompletion=p.video.completion;VideoView.Prepared previousPrepared=p.video.prepared;
        previousCompletion.complete(p.video.player);
        check(p.current==2&&p.series.optString("seriesId").equals("222222")&&p.episode.optInt("index")==1,"actual completion advances to second season episode one");
        check(p.localWindow.position==2,"exact flat cache window advances across local-index reset");
        check(p.store.ownedHistories.contains("111111:1002:2:0"),"ending history belongs to completed first season");
        p.pump();p.video.complete(1080,1920);
        check(p.store.ownedHistories.contains("222222:1001:1:0"),"second season confirmation stores its own history identity");
        check(p.title.text.contains("第二季")&&p.title.text.contains("第 1 集"),"native player reports actual season and local episode");
        p.video.position=45678;p.saveProgress();
        check(p.store.ownedHistories.get(p.store.ownedHistories.size()-1).equals("222222:1001:1:45678"),"actual progress persistence keeps season-specific position");
        Bundle saved=new Bundle();p.savePlaybackState(saved);
        check(saved.getString("seriesId","").equals("222222")&&saved.getString("vid","").equals("1001")&&saved.getInt("episodeIndex",-1)==1&&saved.getInt("position",-1)==45678,"actual Bundle save writes full season identity and confirmed position");
        AdaptiveActivityHarness recreated=fresh();check(recreated.restoreSelection(saved)&&recreated.current==0&&recreated.queue.size()==2,"actual restore selects second season despite duplicated vid and local index");
        Bundle invalid=new Bundle();invalid.putString("seriesId","999999");invalid.putString("vid","1001");invalid.putInt("episodeIndex",1);invalid.putInt("position",99999);
        check(!recreated.restoreSelection(invalid)&&recreated.current==0,"unknown restored season cannot redirect selection or validate its progress");
        previousCompletion.complete(p.video.player);previousPrepared.prepared(p.video.player);
        check(p.current==2&&p.series.optString("seriesId").equals("222222"),"late previous-season completion/prepared callbacks do not skip new season");
        p.autoplay=false;p.video.completion.complete(p.video.player);check(p.current==2&&p.completed,"autoplay off stops at exact episode");
        ready(p,3);p.autoplay=true;p.video.completion.complete(p.video.player);p.pump();
        check(p.current==3&&p.completed&&p.status.text.contains("本剧已播完"),"last supplied season ends without inventing another queue item");
        p.selectEpisode();check(p.dialog.labels[0].contains("第一季")&&p.dialog.labels[2].contains("第二季")&&p.dialog.labels[2].contains("第 1 集"),"native chooser distinguishes duplicate local episode numbers by season");

        AdaptiveActivityHarness late=fresh();late.loadAt(0,null,0);late.resolver.flush();
        AdaptiveActivityHarness.PreparedMedia old=late.pendingMedia.iterator().next();
        check(old.seriesId.equals("111111"),"prepared stage captures original season before handoff");
        late.loadAt(2,null,0);late.pump();late.releasePrepared(old);
        check(late.cache.releasedOwners.contains(key("111111","1001",1,"1080p")),"late first-season lease releases first-season key");
        check(late.cache.ownerReservations.size()==1&&late.cache.ownerReservations.containsKey(key("222222","1001",1,"1080p")),"same vid/index in second season remains independently reserved");
        check(!late.cache.releasedOwners.contains(key("222222","1001",1,"1080p")),"old lease cannot accidentally release newer season's current file");
        late.video.complete(1080,1920);late.switchQuality("720p");late.resolver.flush();
        late.loadAt(0,null,0);late.pump();
        check(late.cache.releasedOwners.contains(key("222222","1001",1,"720p")),"cancelled quality candidate releases its captured season");
        check(late.cache.ownerReservations.size()==1&&late.cache.ownerReservations.containsKey(key("111111","1001",1,"1080p")),"quality handoff cannot leak old-season or release current-season reservation");
        late.video.complete(1080,1920);late.video.fail(-1010,-1010);late.pump();
        check(late.cache.invalidatedOwners.contains(key("111111","1001",1,"1080p")),"decoder fallback invalidates actual current season only");

        AdaptiveActivityHarness errors=fresh();errors.autoplay=true;ready(errors,1);
        for(String q:new String[]{"1080p","720p","auto"})errors.cache.failures.put(q,new java.io.IOException("片源响应 HTTP 403"));
        errors.video.completion.complete(errors.video.player);errors.pump();
        check(errors.current==2&&!errors.completed&&errors.errors==1,"next-season preparation failure stays on its first episode without false end/skip");
        check(errors.store.ownedHistories.stream().noneMatch(v->v.startsWith("222222:")),"failed next-season preparation never claims receiver/player confirmation in history");
        check(errors.pendingMedia.isEmpty()&&errors.cache.ownerReservations.isEmpty(),"all failed stages release exact-season leases");
        int restored=PlaybackPlaylist.find(errors.queue,"222222","1001",1,errors.queueSeries);
        check(restored==2&&PlaybackPlaylist.find(errors.queue,"111111","1001",1,errors.queueSeries)==0,"restore identity distinguishes same vid and local index across seasons");
        String identity=PlaybackPlaylist.identity(errors.queueSeries,errors.queue);
        check(identity.startsWith("playlist:")&&identity.equals(PlaybackPlaylist.identity(errors.series,errors.queue)),"cast ownership identity stays stable when local current season changes");
        PlaybackCache.QueueWindow window=errors.localWindow;errors.closeLocalWindow();
        check(window.closed&&errors.localWindow==null,"local window closes explicitly for cast handoff/destroy");
        errors.cache.failures.clear();errors.loadAt(2,null,0);check(errors.localWindow!=null&&errors.localWindow.position==2,"returning locally recreates window at confirmed flat position");
        AdaptiveActivityHarness detached=fresh();detached.loadAt(0,null,0);detached.resolver.flush();
        AdaptiveActivityHarness.PreparedMedia pending=detached.pendingMedia.iterator().next();PlaybackCache.QueueWindow originalWindow=detached.localWindow;
        detached.closeLocalWindow();int closedPrunes=originalWindow.prunes;detached.releasePrepared(pending);
        check(originalWindow.closed&&originalWindow.prunes==closedPrunes+1,"late released media prunes its originating window even after close without a replacement");
        AdaptiveActivityHarness replaced=fresh();replaced.loadAt(0,null,0);replaced.resolver.flush();
        AdaptiveActivityHarness.PreparedMedia held=replaced.pendingMedia.iterator().next();PlaybackCache.QueueWindow replacementWindow=replaced.localWindow;replaced.closeLocalWindow();
        replaced.localWindow=replaced.cache.openQueueWindow(replaced.queueSeries,replaced.queue,2,true,1,false);
        AdaptiveActivityHarness.PreparedMedia nextHeld=replaced.reserveMedia(replaced.queue.get(2),"1080p",replaced.localWindow);
        PlaybackCache.QueueWindow latestWindow=replaced.localWindow;int retiredCount=replacementWindow.prunes,latestCount=latestWindow.prunes;replaced.releasePrepared(held);
        check(replacementWindow.prunes==retiredCount+1&&latestWindow.prunes==latestCount+1,"late old-window release prunes both retired and new active window");
        check(replaced.cache.ownerReservations.size()==1&&replaced.cache.ownerReservations.containsKey(key("222222","1001",1,"1080p")),"late closed-window cleanup leaves next-season pending media reservation intact");
        replaced.releasePrepared(nextHeld);
        System.out.println("SEASON_PLAYLIST_ACTIVITY_OK checks="+checks+" actualProductionMethods=true actualPlaylistParser=true transport=synthetic networkRequests=0 deviceTests=not_run");
    }
}
