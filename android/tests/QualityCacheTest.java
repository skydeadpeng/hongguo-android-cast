package io.github.jackotom.hongguo.android;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class QualityCacheTest {
    static int checks;
    static void check(boolean value,String label) { checks++; if(!value)throw new AssertionError(label); }
    public static void main(String[] args)throws Exception {
        for(String q:new String[]{"auto","720p","1080p"})check(MediaQuality.normalize(q).equals(q),"enum");
        check(MediaQuality.normalize(null).equals("auto"),"null default");
        for(String q:new String[]{"","4k","1080","AUTO","720P","../auto"}){try{MediaQuality.normalize(q);throw new AssertionError("invalid enum");}catch(IllegalArgumentException expected){checks++;}}
        check(MediaQuality.meets(0,0,"auto"),"auto no invented dimensions");
        check(!MediaQuality.meets(1920,0,"1080p"),"unknown is not HD");
        check(MediaQuality.meets(720,1280,"720p"),"portrait720");
        check(!MediaQuality.meets(720,1280,"1080p"),"do not call long edge1080p");
        check(MediaQuality.meets(1920,1080,"1080p"),"landscape1080");
        check(MediaQuality.cacheVid("1004","auto").equals("1004|quality=auto|complete=1"),"auto complete-source identity excludes legacy preview");
        check(new HashSet<>(Arrays.asList(MediaQuality.cacheVid("1004","auto"),MediaQuality.cacheVid("1004","720p"),MediaQuality.cacheVid("1004","1080p"))).size()==3,"all quality namespaces distinct");
        File dir=Files.createTempDirectory("hongguo-quality-cache-").toFile();
        try {
            PlaybackCachePolicy p=new PlaybackCachePolicy(dir,256,64,(url,out,max)->Files.write(out.toPath(),new byte[]{1}));
            String url="https://cdn.example.net/same-actual-url.mp4";
            File auto=p.fetch("123456","1004",4,url);
            File hd=p.fetch("123456",MediaQuality.cacheVid("1004","1080p"),4,url,(value,out,max)->{check(value.equals(url),"URL never rewritten");Files.write(out.toPath(),new byte[]{2,2});});
            check(!auto.equals(hd),"low cache is not HD cache");
            check(auto.length()==1 && hd.length()==2,"per-job downloader executes correct namespace");
            check(p.find("123456",MediaQuality.cacheVid("1004","720p"),4)==null,"missing720 cannot hit auto1080");
            check(p.status().files==2 && p.status().bytes==3,"shared budget counts both");
            p.protect("123456",MediaQuality.cacheVid("1004","1080p"),4);
            p.prune("123456",6,1);
            check(!auto.exists() && hd.exists(),"prune all old variants while protecting selected");
            p.release("123456",MediaQuality.cacheVid("1004","1080p"),4);p.prune("123456",6,1);
            check(!hd.exists() && p.status().files==0,"old variant finally released");
            System.out.println("PASS QualityCacheTest checks="+checks+" qualityNamespaces=3 legacyAutoExcluded=true sharedBudget=true networkRequests=0");
        }finally{File[] fs=dir.listFiles();if(fs!=null)for(File f:fs)f.delete();dir.delete();}
    }
}
