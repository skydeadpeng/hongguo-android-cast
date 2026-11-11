package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** No network or decoded media: lifecycle tests with explicit network/remux boundaries. */
final class HuangguoCastSourceTest {
    static final String SID="9912345678", VID=SID+"0001", KEY=VID+"|huangguo-cast-mp4-v1";
    static int checks;
    static AtomicBoolean alive=new AtomicBoolean(true);
    static File directory;
    static PlaybackCache cache;
    static void check(boolean condition,String message) { checks++;if(!condition)throw new AssertionError(message); }
    static void reset() throws Exception {
        directory=Files.createTempDirectory("huangguo-cast-source-").toFile();
        cache=new PlaybackCache(directory);alive.set(true);
        HuangguoApi.calls=0;NetworkGuard.calls=0;NetworkGuard.mode="ok";HuangguoMp4Remuxer.calls=0;
        HuangguoMp4Remuxer.mode="ok";
    }
    static DlnaSession.Prepared prepare() throws Exception {
        return HuangguoCastSource.prepare(cache,SID,VID,1,"fixture",true,alive::get,()->{});
    }
    static int pins() throws Exception {
        Field field=PlaybackCachePolicy.class.getDeclaredField("protectedKeys");field.setAccessible(true);
        Map<?,?> entries=(Map<?,?>)field.get(cache.policy);int count=0;for(Object value:entries.values())count+=(Integer)value;
        return count;
    }
    static void cleanScratch() {
        File scratch=new File(directory,"huangguo-hls-work");
        for(String name:new String[]{"source.ts","segment.part","key.part"})check(!new File(scratch,name).exists(),"scratch cleaned: "+name);
        File[] files=directory.listFiles();if(files!=null)for(File file:files)check(!file.getName().endsWith(".part"),"no part published");
    }
    static void dispose() throws IOException {
        try(java.util.stream.Stream<Path> paths=Files.walk(directory.toPath())) {
            for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
        }
    }
    static void rejected(String sid,String vid,int index) throws Exception {
        try { HuangguoCastSource.prepare(cache,sid,vid,index,"fixture",true,alive::get,()->{});throw new AssertionError("identity accepted"); }
        catch(IOException expected) { check(pins()==0,"bad identity creates no hold");check(HuangguoApi.calls==0,"bad identity makes no resolver call"); }
    }
    public static void main(String[] args) throws Exception {
        reset();try {
            rejected("123456",VID,1);rejected(SID,SID+"0002",1);rejected(SID,VID,0);rejected(SID,VID,10000);
            check(!HuangguoCastSource.matches("99123456780"),"SID must be exact length");
            DlnaSession.Prepared p=prepare();check(p.quality.equals("auto"),"canonical auto quality");
            check(p.episodeIndex==1&&p.title.equals("fixture"),"identity/title preserved");
            check(cache.find(SID,KEY,1,"auto").equals(p.file),"versioned auto cache key");
            check(cache.find(SID,KEY,1,"1080p")==null,"does not masquerade as 1080p");
            check(cache.find(SID,VID,1,"auto")==null,"normal playback namespace isolated");
            check(pins()==1,"one owner hold");cleanScratch();
            DlnaSession.Prepared reused=prepare();check(HuangguoApi.calls==1&&HuangguoMp4Remuxer.calls==1,"cache reuse avoids resolving/remux");
            reused.releaseOwner();reused.releaseOwner();check(pins()==1,"owner release idempotent");
            p.retain.run();p.releaseOwner();check(pins()==1,"reader survives owner release");
            cache.policy.clear();check(p.file.exists(),"reader pin protects cache clear");
            p.release.run();check(pins()==0,"reader releases final pin");
            cache.policy.clear();check(!p.file.exists(),"unheld cache cleared");
        } finally {dispose();}
        for(String mode:new String[]{"network-fail","cancel-stage","preview","remux-fail","bad-mp4","cancel-remux"}) {
            reset();try {
                NetworkGuard.mode=mode;HuangguoMp4Remuxer.mode=mode;
                try {prepare();throw new AssertionError("failure accepted: "+mode);}catch(IOException expected){}
                check(pins()==0,"failed prepare releases owner: "+mode);
                check(cache.policy.status().files==0,"no partial publication: "+mode);cleanScratch();
            } finally {dispose();}
        }
        reset();try {
            alive.set(false);
            try{prepare();throw new AssertionError("cancel accepted");}catch(InterruptedIOException expected){}
            check(pins()==0&&HuangguoApi.calls==0,"early cancellation releases without resolving");
            alive.set(true);DlnaSession.Prepared ready=prepare();ready.releaseOwner();
            // A poisoned owned cache must be invalidated and rebuilt, never published.
            try(FileOutputStream out=new FileOutputStream(ready.file)){out.write(1);}
            DlnaSession.Prepared rebuilt=prepare();check(HuangguoApi.calls==2,"invalid cached media rebuilt");rebuilt.releaseOwner();cleanScratch();
        } finally {dispose();}
        System.out.println("Huangguo cast lifecycle checks passed: "+checks);
    }
}
class HuangguoApi {
    static int calls;
    static final class CastStream { final String url;final double seconds;CastStream(String u,double s){url=u;seconds=s;} }
    static CastStream episodeForCast(String sid,String vid,int index,long deadline) {
        long remaining=deadline-System.nanoTime();HuangguoCastSourceTest.check(remaining>0&&remaining<=180_000_000_000L,"source forwards bounded overall deadline");
        calls++;HuangguoCastSourceTest.check(sid.equals(HuangguoCastSourceTest.SID)&&vid.equals(HuangguoCastSourceTest.VID)&&index==1,"exact resolver identity");
        return new CastStream("https://media.example.test/list.m3u8",NetworkGuard.mode.equals("preview")?100:10);
    }
}
class NetworkGuard {
    static final String UA="fixture";static String mode;static int calls;
    static class Reply {int status=200;String url="https://media.example.test/list.m3u8";
        String body="#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST\n";}
    static Reply publicGet(String url,long deadline) {calls++;return new Reply();}
    static void downloadHlsKey(String url,File destination,long deadline) {throw new AssertionError("Unexpected key");}
    static void download(String url,File destination,long max,String ua,String referer,long deadline)throws IOException {
        calls++;byte[] ts=new byte[188*3];for(int i=0;i<ts.length;i+=188)ts[i]=0x47;
        Files.write(destination.toPath(),ts);
        if(mode.equals("network-fail"))throw new IOException("boundary failure");
        if(mode.equals("cancel-stage"))HuangguoCastSourceTest.alive.set(false);
    }
}
class HuangguoMp4Remuxer {
    static int calls;static String mode;
    static void remux(File input,File target,long max,double seconds,BooleanSupplier alive,long deadline)throws Exception {
        calls++;HuangguoCastSourceTest.check(input.length()==564&&seconds==10,"complete staged TS reaches remux");
        try(DataOutputStream out=new DataOutputStream(new FileOutputStream(target))) {
            if(mode.equals("bad-mp4")){out.writeByte(1);return;}
            for(String type:new String[]{"ftyp","moov","mdat"}){out.writeInt(16);out.writeBytes(type);out.writeLong(0);}
        }
        if(mode.equals("remux-fail"))throw new IOException("boundary remux failure");
        if(mode.equals("cancel-remux"))HuangguoCastSourceTest.alive.set(false);
    }
}
