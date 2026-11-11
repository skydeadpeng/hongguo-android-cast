package io.github.jackotom.hongguo.android;
import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.Arrays;

/** Actual cache facade + policy, synthetic bytes only: no media or network. */
public final class CompleteSourceCacheTest {
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception {
        File temp=Files.createTempDirectory("hongguo-full-source-").toFile(),dir=new File(temp,"playback-v2");
        try {
            String sid="123456",vid="7654321",url="https://cdn.example.net/video.mp4";
            PlaybackCachePolicy old=new PlaybackCachePolicy(dir,PlaybackCache.BUDGET_BYTES,PlaybackCache.MAX_FILE_BYTES,(u,f,m)->Files.write(f.toPath(),new byte[]{1}));
            File legacy=old.fetch(sid,vid,1,url);
            File hd=old.fetch(sid,vid+"|quality=1080p",1,url);
            File sd=old.fetch(sid,vid+"|quality=720p",1,url);
            File manual=new File(temp,"manual-download.mp4");Files.write(manual.toPath(),new byte[]{7,8,9});
            File unrelated=new File(dir,"notes.txt");Files.write(unrelated.toPath(),new byte[]{6});
            PlaybackCache cache=PlaybackCache.get(new Context(temp));
            check(cache.find(sid,vid,1,"auto")==null,"legacy auto preview must never be reused");
            check(!legacy.exists(),"requested legacy auto entry is cleaned");
            check(hd.equals(cache.find(sid,vid,1,"1080p")),"existing1080p cache retained");
            check(sd.equals(cache.find(sid,vid,1,"720p")),"existing720p cache retained");
            check(MediaQuality.cacheVid(vid,"1080p").equals(vid+"|quality=1080p"),"1080 identity unchanged");
            check(MediaQuality.cacheVid(vid,"720p").equals(vid+"|quality=720p"),"720 identity unchanged");
            check(!MediaQuality.cacheVid(vid,"auto").equals(vid),"complete source auto uses revision identity");
            File full=cache.fetchForeground(sid,vid,1,url,"auto");
            check(!full.equals(legacy)&&Arrays.equals(Files.readAllBytes(full.toPath()),new byte[]{2,3}),"full auto published under fresh identity");
            check(cache.find(sid,vid,1,"auto").equals(full),"complete auto reused");
            check(cache.find(sid,vid,1).equals(full),"legacy facade find uses complete identity");
            check(cache.fetch(sid,vid,1,url).equals(full),"legacy facade fetch uses complete identity");
            Field instance=PlaybackCache.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,null);
            cache=PlaybackCache.get(new Context(temp));
            check(cache.find(sid,vid,1,"auto").equals(full),"complete cache survives process restart");
            String castSid="234567";
            PlaybackCache.CastWindow window=cache.openCastWindow(castSid,1);
            File[] files=new File[6];
            for(int i=1;i<=6;i++) files[i-1]=cache.fetch(castSid,"800"+i,i,url,"auto");
            for(File f:files)check(f.exists(),"current plus next5 retained");
            window.moveTo(3);
            check(!files[0].exists(),"watched older than previous cleaned");
            for(int i=1;i<6;i++)check(files[i].exists(),"previous/current/ahead window retained");
            window.close();
            check(Arrays.equals(Files.readAllBytes(manual.toPath()),new byte[]{7,8,9}),"manual download untouched");
            check(Arrays.equals(Files.readAllBytes(unrelated.toPath()),new byte[]{6}),"unowned cache file untouched");
            System.out.println("COMPLETE_SOURCE_CACHE_OK checks="+checks+" legacyAutoRejected=true explicitQualityPreserved=true prefetchAhead=5 cleanupPreserved=true networkRequests=0");
        } finally {try(java.util.stream.Stream<Path> all=Files.walk(temp.toPath())){for(Path p:all.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p);} }
    }
}
