package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Shared DLNA/browser preparation. Never writes a manual download or exposes signed HLS URLs. */
final class HuangguoCastSource {
    private static final Semaphore PREPARATION = new Semaphore(1, true);
    private static final String CACHE_VERSION = "|huangguo-cast-mp4-v1";
    static boolean matches(String sid) { return sid!=null&&sid.matches("99[0-9]{8}"); }
    static DlnaSession.Prepared prepare(PlaybackCache cache,String sid,String vid,int index,String title,boolean foreground,BooleanSupplier alive,Runnable prune) throws Exception {
        if(!matches(sid)||vid==null||!vid.equals(sid+String.format(java.util.Locale.ROOT,"%04d",index))||index<1||index>9999)throw new IOException("黄果投屏分集身份不匹配");
        String key=vid+CACHE_VERSION;boolean success=false;
        cache.protect(sid,key,index,"auto");
        Runnable release=()->{cache.release(sid,key,index,"auto");prune.run();};
        try {
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
            HuangguoHls.check(alive,deadline);
            File file=cache.find(sid,key,index,"auto");
            if(file!=null)try{DlnaSessionFile.validate(file);}catch(DlnaSessionFile.NotMp4Exception invalid){cache.invalidate(sid,key,index,"auto");file=null;}
            if(file==null)file=cache.fetchCast(sid,key,index,foreground,(unused,target,max)->download(sid,vid,index,target,max,alive,deadline));
            HuangguoHls.check(alive,deadline);DlnaSessionFile.validate(file);
            DlnaSession.Prepared prepared=new DlnaSession.Prepared(file,title,"auto",index,()->cache.protect(sid,key,index,"auto"),release);
            success=true;return prepared;
        } finally { if(!success)release.run(); }
    }
    private static void download(String sid,String vid,int index,File target,long max,BooleanSupplier alive,long deadline) throws Exception {
        boolean acquired=false;
        try {
            while(!acquired){HuangguoHls.check(alive,deadline);acquired=PREPARATION.tryAcquire(100,TimeUnit.MILLISECONDS);}
            HuangguoHls.check(alive,deadline);
            // One global staging area bounds extra disk usage to source + one segment. A process death
            // leaves only these three owned names, which the next job removes before downloading.
            File scratch=new File(target.getParentFile(),"huangguo-hls-work");
            if(Files.isSymbolicLink(scratch.toPath())||(!scratch.isDirectory()&&!scratch.mkdir()))throw new IOException("黄果投屏临时目录无效");
            for(String name:new String[]{"source.ts","segment.part","key.part"}) {
                File owned=new File(scratch,name);if(Files.isSymbolicLink(owned.toPath()))throw new IOException("黄果投屏临时路径无效");HuangguoHls.remove(owned);
            }
            HuangguoApi.CastStream stream=HuangguoApi.episodeForCast(sid,vid,index,deadline);HuangguoHls.check(alive,deadline);
            HuangguoHls.Transport transport=new HuangguoHls.Transport(){
                public HuangguoHls.Manifest playlist(String url,long end) throws Exception {
                    NetworkGuard.Reply reply=NetworkGuard.publicGet(url,end);
                    if(reply.status!=200)throw new IOException("黄果清单响应 HTTP "+reply.status);
                    HuangguoHls.Playlist parsed=HuangguoHls.parse(reply.body,reply.url);
                    if(stream.seconds>0&&parsed.seconds<stream.seconds*0.8)throw new IOException("黄果仅提供预览片段，无法完整投屏");
                    return new HuangguoHls.Manifest(reply.body,reply.url);
                }
                public void key(String url,File destination,long end) throws Exception {
                    NetworkGuard.downloadHlsKey(url,destination,end);
                }
                public void download(String url,File destination,long bytes,long end) throws Exception {
                    NetworkGuard.download(url,destination,bytes,NetworkGuard.UA,"https://huangguoai.com/",end);
                }
            };
            HuangguoHls.prepare(stream.url,target,scratch,max,alive,deadline,transport,HuangguoMp4Remuxer::remux);
        } catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new InterruptedIOException("黄果投屏准备已取消");}
        finally {if(acquired)PREPARATION.release();}
    }
}
