package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Actual service FileSource + actual session/prefetch/cache/history; media source and receiver I/O are explicit fakes. */
final class CastPlaylistSourceTest {
    static int checks;
    static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    static JSONObject season(String sid,String name,int count)throws Exception{return new JSONObject().put("seriesId",sid).put("title",name).put("episodeCount",count);}
    static JSONObject episode(String sid,int index)throws Exception{return new JSONObject().put("seriesId",sid).put("vid",vid(sid,index)).put("index",index).put("availability","unknown").put("webAvailable",false);}
    static String vid(String sid,int index){return sid+String.format(java.util.Locale.ROOT,"%03d",index);}
    static final class Fixture implements AutoCloseable {
        final boolean browser;final File directory;final CastPlaylistContext context;final JSONObject first,second;final List<JSONObject> entries=new ArrayList<>();
        final DlnaSession.Source source;final PlaybackCache cache;final LibraryStore store;final String identity;final int current,total;
        final DlnaSessionTest.Clock clock=new DlnaSessionTest.Clock();final DlnaSessionTest.Control control=new DlnaSessionTest.Control();final DlnaSessionTest.Server server=new DlnaSessionTest.Server();
        final DlnaSession dlna;final BrowserCastSession web;long seq;
        Fixture(boolean browser,boolean failNext,int selectedPosition,int mergeWindow)throws Exception{
            this.browser=browser;directory=Files.createTempDirectory("hongguo-playlist-source-").toFile();
            Field singleton=PlaybackCache.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
            PlaybackSources.calls.clear();PlaybackSources.failVid=failNext?vid("222222",2):"";
            first=season("111111","合成测试：第一季",3);second=season("222222","合成测试：第二季",7);
            JSONArray manifest=new JSONArray();for(int i=1;i<=3;i++)manifest.put(episode("111111",i));for(int i=1;i<=7;i++)manifest.put(episode("222222",i));
            JSONArray clean=PlaybackPlaylist.parse(new JSONObject().put("version",1).put("series",new JSONArray().put(first).put(second)).put("episodes",manifest),first,episode("111111",3));
            for(int i=0;i<clean.length();i++)entries.add(clean.getJSONObject(i));
            if(browser){BrowserPlaylistSourceHarness h=new BrowserPlaylistSourceHarness(directory);context=h;source=h.make(first,entries,selectedPosition,true);identity=h.identity();current=h.request.current;total=h.request.episodes.size();}
            else{DlnaPlaylistSourceHarness h=new DlnaPlaylistSourceHarness(directory);context=h;source=h.make(first,entries,selectedPosition,true,mergeWindow);identity=h.identity();current=h.request.current;total=h.request.episodes.size();}
            cache=PlaybackCache.get(context);store=new LibraryStore(context);
            dlna=browser?null:new DlnaSession(clock,control,source,server,value->{},identity,first.getString("title"),"合成电视",total,"1080p",true,true);
            web=browser?new BrowserCastSession(clock,source,server,value->{},identity,first.getString("title"),total,"1080p",true,true):null;
        }
        void start()throws Exception{
            if(browser){web.start(current,0);web.receiverPaired("Synthetic browser");}else dlna.start(current,0);
            clock.prepare(0,false);if(browser)send("playing",0,10000);
        }
        DlnaSession.Snapshot snapshot(){return browser?web.snapshot():dlna.snapshot();}
        void send(String type,long position,long duration)throws Exception{
            JSONObject state=web.receiverState(),media=state.getJSONObject("media");
            web.receiverEvent(new JSONObject().put("sessionId",state.getString("sessionId")).put("mediaId",media.getString("id"))
                .put("commandId",state.getJSONObject("intent").getLong("commandId")).put("eventSeq",++seq)
                .put("type",type).put("positionMs",position).put("durationMs",duration));
        }
        void end()throws Exception{
            if(browser){send("timeupdate",9500,10000);send("ended",10000,10000);}
            else{control.position=99000;clock.tick();control.state="STOPPED";clock.tick();}
        }
        void prepare(int number)throws Exception{clock.prepare(number,false);if(browser&&web.receiverState().optJSONObject("media")!=null)send("playing",0,10000);}
        void select(int position){if(browser)web.select(position);else dlna.select(position);}
        JSONObject history(String sid)throws Exception{
            JSONArray rows=store.library().getJSONArray("history");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(sid.equals(row.optString("seriesId")))return row;}return null;
        }
        File file(String sid,int index)throws Exception{return cache.find(sid,vid(sid,index),index,"1080p");}
        boolean ready(int index){try{return file("222222",index)!=null;}catch(Exception e){throw new RuntimeException(e);}}
        String diagnostics(){return browser?((BrowserPlaylistSourceHarness)context).diagnostics():((DlnaPlaylistSourceHarness)context).diagnostics();}
        public void close()throws Exception{
            if(browser)web.close();else dlna.close();
            waitFor(()->Thread.getAllStackTraces().keySet().stream().noneMatch(thread->thread.isAlive()&&thread.getName().equals("hongguo-cast-prepare")),"prefetch workers finish on close");
            PlaybackSources.failVid="";
            try(java.util.stream.Stream<Path> files=Files.walk(directory.toPath())){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException e){throw new UncheckedIOException(e);}});}
        }
    }
    static void waitFor(BooleanSupplier ready,String label)throws Exception{
        long until=System.nanoTime()+5_000_000_000L;while(!ready.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(10);check(ready.getAsBoolean(),label);
    }
    static void seasonBoundary(boolean browser,int mergeWindow)throws Exception{
        try(Fixture f=new Fixture(browser,false,2,mergeWindow)){
            f.start();check(f.current==2&&f.total==3&&f.snapshot().current==2&&f.snapshot().episodeIndex==3,"legacy first-season last episode is confirmed with only this season in the queue");
            check(f.identity.equals("111111")&&f.snapshot().seriesId.equals(f.identity),"service passes selected-season identity into the untouched session");
            waitFor(()->f.diagnostics().contains("当前预取=无"),"season-end prefetch is idle");
            check(PlaybackSources.calls.stream().noneMatch(v->v.startsWith("222222/")),"five-ahead and merged preparation never fetch removed next-season media");
            check(f.history("222222")==null,"prefetch and merging never create next-season history");
            f.end();check(f.snapshot().current==2&&f.snapshot().targetCurrent==2&&!f.snapshot().preparing,"season end stays on final confirmed episode without preparing another season");
            check(f.clock.preparations.size()==1,"season completion does not schedule a next-season preparation");
            check(f.history("111111").getInt("index")==3&&f.history("222222")==null,"season completion retains actual season history only");
        }
        System.out.println("PASS "+(browser?"Browser":"DLNA")+" season boundary stops autoplay / prefetch / merged window (merge="+mergeWindow+")");
    }
    static void sameSeason(boolean browser)throws Exception{
        try(Fixture f=new Fixture(browser,false,3,1)){
            f.start();check(f.current==0&&f.total==7&&f.snapshot().current==0&&f.snapshot().episodeIndex==1,"later-season ingress remaps flat selection to local queue position");
            check(f.identity.equals("222222")&&f.snapshot().seriesId.equals(f.identity),"later-season cast identity matches local player");
            waitFor(()->f.ready(6)&&f.diagnostics().contains("已准备=5"),"actual prefetch prepares the next five episodes in the selected season");
            check(f.file("222222",7)==null,"no sixth future episode cached before advancement");
            check(f.history("111111")==null&&f.history("222222").getInt("index")==1,"old fallback season gets no watch history");
            for(int i=2;i<=6;i++)check(PlaybackSources.calls.contains("222222/"+vid("222222",i)+"/1080p"),"resolver retains true selected-season episode identity "+i);
            File old=f.file("222222",1);Runnable reader=f.server.readCurrent();
            f.end();check(f.snapshot().current==0&&f.snapshot().targetCurrent==1,"EOF selects the next episode within this season while retaining confirmed current during preparation");
            check(f.history("222222").getInt("index")==1,"automatic target is not history before playback confirmation");
            f.prepare(1);
            check(f.snapshot().current==1&&f.snapshot().episodeIndex==2&&f.snapshot().title.contains("第二季"),"same-season next episode auto-confirms with true title/local index");
            check(f.snapshot().seriesId.equals(f.identity),"same-season snapshot identity remains stable");
            check(f.history("222222").getInt("index")==2&&f.history("222222").getString("vid").equals(vid("222222",2)),"confirmed history uses true VID and local episode index");
            check(Collections.frequency(PlaybackSources.calls,"222222/"+vid("222222",2)+"/1080p")==1,"auto-next consumes ready prefetch without re-resolving");
            f.select(4);f.prepare(2);
            check(f.snapshot().current==4&&f.snapshot().episodeIndex==5,"manual selection still uses true local episode index");
            check(old.exists(),"outstanding old reader survives moving outside retention window");
            reader.run();check(!old.exists(),"old cache is deleted after last reader release and queue prune");
            check(f.file("222222",5)!=null&&f.history("222222").getInt("index")==5,"old callback does not prune or rewrite current media");
            check(f.cache.status().getLong("budgetBytes")==PlaybackCache.CAST_BUDGET_BYTES,"cast retains original bounded cache budget");
        }
        System.out.println("PASS "+(browser?"Browser":"DLNA")+" same-season auto-next / five-ahead / real cache leases / true-owner history");
    }
    static void failedSameSeason(boolean browser)throws Exception{
        try(Fixture f=new Fixture(browser,true,3,1)){
            f.start();waitFor(()->f.ready(6)&&f.diagnostics().contains("当前预取=无"),"prefetch proceeds after one failed same-season source");
            File old=f.file("222222",1);Runnable reader=f.server.readCurrent();f.end();f.prepare(1);
            check(f.snapshot().current==0&&f.snapshot().targetCurrent==1&&!f.snapshot().preparing,"failed auto-next retains old confirmed current and explicit failed target");
            check(f.history("222222").getInt("index")==1,"failed future source never appears in watch history");
            check(f.server.live.size()==1&&old.exists(),"failed preparation preserves old publication and live read");
            check(!f.snapshot().error.isEmpty(),"failed same-season source exposes error rather than skipping");
            check(Collections.frequency(PlaybackSources.calls,"222222/"+vid("222222",2)+"/1080p")>=2,"foreground may retry failed background prefetch under existing policy");
            reader.run();check(old.exists(),"old publication retains file after extra reader closes");
        }
        System.out.println("PASS "+(browser?"Browser":"DLNA")+" failed same-season source retains old media and excludes unstarted history");
    }
    static void reconnectIdentity(boolean browser)throws Exception{
        try(Fixture f=new Fixture(browser,false,2,1)){
            f.source.close();DlnaSession.Source next;
            if(browser){
                BrowserPlaylistSourceHarness h=(BrowserPlaylistSourceHarness)f.context;
                next=h.make(f.first,f.entries,4,true);
                check(h.request.current==1&&h.request.episodes.size()==7,"browser old annotated queue is limited to selected later season and remaps current");
                check(h.identity().equals("222222")&&h.request.series.optString("seriesId").equals("222222"),"browser reconnect updates identity and initial title owner to selected season");
            }else{
                DlnaPlaylistSourceHarness h=(DlnaPlaylistSourceHarness)f.context;
                next=h.make(f.first,f.entries,4,true);
                check(h.request.current==1&&h.request.episodes.size()==7,"DLNA old annotated queue is limited to selected later season and remaps current");
                check(h.identity().equals("222222")&&h.request.series.optString("seriesId").equals("222222"),"DLNA reconnect updates identity and initial title owner to selected season");
            }
            next.close();
        }
    }
    public static void main(String[] args)throws Exception{
        reconnectIdentity(false);reconnectIdentity(true);seasonBoundary(false,1);seasonBoundary(true,1);seasonBoundary(false,5);sameSeason(false);sameSeason(true);failedSameSeason(false);failedSameSeason(true);
        System.out.println("CAST_PLAYLIST_SOURCE_OK checks="+checks+" crossSeasonAutoNext=false serviceConnect=serviceFileSource=session=prefetch=cache=history=actual resolverDownloader=mock receiverControl=fake networkRequests=0 deviceTests=not_run");
    }
}

class CastPlaylistContext extends Context {
    final File root;final Map<String,Prefs> stores=new HashMap<>();CastPlaylistContext(File root){this.root=root;}
    public File getCacheDir(){return new File(root,"cache");}
    public File getExternalFilesDir(String type){return new File(root,"manual");}
    public synchronized SharedPreferences getSharedPreferences(String name,int mode){return stores.computeIfAbsent(name,n->new Prefs());}
    static final class Prefs implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();
        public synchronized Map<String,?> getAll(){return new HashMap<>(values);}
        public synchronized String getString(String key,String fallback){Object value=values.get(key);return value instanceof String?(String)value:fallback;}
        public Editor edit(){return new Editor(){
            final Map<String,Object> next=new HashMap<>();final Set<String> removed=new HashSet<>();
            public Editor putString(String key,String value){next.put(key,value);return this;}
            public Editor putBoolean(String key,boolean value){next.put(key,value);return this;}
            public Editor putInt(String key,int value){next.put(key,value);return this;}
            public Editor putLong(String key,long value){next.put(key,value);return this;}
            public Editor putFloat(String key,float value){next.put(key,value);return this;}
            public Editor putStringSet(String key,Set<String> value){next.put(key,new HashSet<>(value));return this;}
            public Editor remove(String key){removed.add(key);return this;}
            public void apply(){commit();}
            public boolean commit(){synchronized(Prefs.this){for(String key:removed)values.remove(key);values.putAll(next);}return true;}
        };}
    }
}

/** Explicit synthetic source boundary. It never opens a socket or fetches a real video. */
final class PlaybackSources {
    static final List<String> calls=new CopyOnWriteArrayList<>();static volatile String failVid="";static volatile byte[] mediaFixture;
    static final class Plan {final String url,source="app",seriesId,vid,requestedQuality;final int playbackCeiling=0;Plan(String sid,String vid,String quality){seriesId=sid;this.vid=vid;requestedQuality=quality;url="https://media.invalid/"+sid+"/"+vid;}}
    static Plan resolve(String sid,String vid,String quality)throws Exception{calls.add(sid+"/"+vid+"/"+quality);if(vid.equals(failVid))throw new IOException("synthetic source unavailable");return new Plan(sid,vid,quality);}
    static Plan recover(String sid,String vid,String quality,boolean fresh)throws Exception{return resolve(sid,vid,quality);}
    static boolean canLowerQuality(Exception error){return false;}
    static void download(Plan plan,File file,long max)throws Exception{download(plan.url,file,max,plan.requestedQuality);}
    static void download(String url,File file,long max)throws Exception{download(url,file,max,"auto");}
    static void download(String url,File file,long max,String quality)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("synthetic cancelled");
        byte[] fixture=mediaFixture;if(fixture!=null){Files.write(file.toPath(),fixture);return;}
        try(DataOutputStream out=new DataOutputStream(new FileOutputStream(file))){
            out.writeInt(16);out.writeBytes("ftyp");out.writeLong(0);
            out.writeInt(16);out.writeBytes("moov");out.writeLong(0);
            out.writeInt(9);out.writeBytes("mdat");out.writeByte(0);
        }
    }
}
