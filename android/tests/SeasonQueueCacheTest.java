package io.github.jackotom.hongguo.android;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public final class SeasonQueueCacheTest {
    static int checks;
    static void ok(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    static List<PlaybackCachePolicy.QueueEntry> queue(){List<PlaybackCachePolicy.QueueEntry> q=new ArrayList<>();for(String sid:new String[]{"a","b","c"})for(int i=1;i<=3;i++)q.add(new PlaybackCachePolicy.QueueEntry(sid,i));return q;}
    static String sid(int pos){return new String[]{"a","b","c"}[pos/3];}
    static int index(int pos){return pos%3+1;}
    static void fill(PlaybackCachePolicy p)throws Exception{for(int i=0;i<9;i++)p.fetch(sid(i),"v"+index(i),index(i),"fixture");p.fetch("other","v1",1,"fixture");}
    static boolean has(PlaybackCachePolicy p,int i)throws Exception{return p.find(sid(i),"v"+index(i),index(i))!=null;}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("hongguo-season-cache-");
        try {
            PlaybackCachePolicy p=new PlaybackCachePolicy(dir.toFile(),1000,100,2000,(u,f,m)->Files.write(f.toPath(),new byte[32]));
            fill(p);PlaybackCachePolicy.QueueWindow w=p.openQueueWindow(queue(),2,true,5,true);
            ok(p.budgetBytes()==2000,"cast budget preserved");
            for(int i=0;i<9;i++)ok(has(p,i)==(i>=1&&i<=7),"exact cross-season five-ahead retention "+i);
            ok(p.find("other","v1",1)!=null,"unrelated series not deleted");
            p.protect("a","v2",2);w.moveTo(4);ok(has(p,1),"active old reader retained after crossing season");ok(has(p,3),"previous actual episode kept");
            p.release("a","v2",2);w.prune();ok(!has(p,1),"old season file cleaned after reader release");
            w.moveTo(5);ok(!has(p,3)&&has(p,4)&&has(p,5)&&has(p,6),"window follows flat order instead of new index+old SID");
            w.close();ok(p.budgetBytes()==1000,"cast stop returns normal budget");ok(!has(p,7)&&!has(p,8),"future beyond next cleared on close");
            w.close();w.prune();ok(p.find("other","v1",1)!=null,"idempotent close does not touch unrelated files");
            fill(p);PlaybackCachePolicy.QueueWindow local=p.openQueueWindow(queue(),2,true,1,false);ok(p.budgetBytes()==1000,"local keeps existing budget");
            for(int i=0;i<9;i++)ok(has(p,i)==(i>=1&&i<=3),"local cross-season next-one "+i);
            local.moveTo(3);ok(has(p,2)&&has(p,3),"previous season final retained entering next season");local.close();
            fill(p);PlaybackCachePolicy.QueueWindow keep=p.openQueueWindow(queue(),0,false,5,true);keep.moveTo(8);for(int i=0;i<9;i++)ok(has(p,i),"cleanup off respected "+i);keep.close();
            fill(p);PlaybackCachePolicy.QueueWindow one=p.openQueueWindow(queue(),0,false,1,false),two=p.openQueueWindow(queue(),5,true,1,true);
            ok(has(p,0)&&has(p,1),"other active window respected");two.close();one.close();
            fill(p);PlaybackCachePolicy.QueueWindow late=p.openQueueWindow(queue(),0,true,5,true);p.protect("b","v2",2);late.close();ok(has(p,4),"reader held after close");p.release("b","v2",2);late.prune();ok(!has(p,4),"late post-close reader releases deferred old cache");
            // The previous session's late reader must not erase the newer closed
            // session's current/next files (these are needed for local resume).
            fill(p);PlaybackCachePolicy.QueueWindow retired=p.openQueueWindow(queue(),0,true,5,true);p.protect("a","v1",1);retired.close();
            fill(p);PlaybackCachePolicy.QueueWindow newer=p.openQueueWindow(queue(),4,true,5,true);
            ok(has(p,4)&&has(p,5),"new active session owns second-season current and next");newer.close();
            ok(has(p,4)&&has(p,5),"new closed session retains current and next for resume");p.release("a","v1",1);retired.prune();
            ok(has(p,4)&&has(p,5),"old late release cannot erase newer closed-session resume cache");
            ok(!has(p,0),"old release follows latest retention and cleans obsolete reader without a new callback");
            // Supersession is limited to overlapping series, not the whole old queue.
            fill(p);PlaybackCachePolicy.QueueWindow older=p.openQueueWindow(queue(),0,true,5,true);p.protect("c","v3",3);older.close();
            List<PlaybackCachePolicy.QueueEntry> onlyB=Arrays.asList(new PlaybackCachePolicy.QueueEntry("b",1),new PlaybackCachePolicy.QueueEntry("b",2),new PlaybackCachePolicy.QueueEntry("b",3));
            p.fetch("b","v2",2,"fixture");p.fetch("b","v3",3,"fixture");PlaybackCachePolicy.QueueWindow partial=p.openQueueWindow(onlyB,1,false,1,false);partial.close();
            p.release("c","v3",3);older.prune();ok(!has(p,8),"older window still cleans nonoverlapping scope after replacement");ok(has(p,4)&&has(p,5),"newer cleanup-off preference not overridden by retired cleanup");
            boolean rejected=false;try{p.openQueueWindow(queue(),9,true,5,true);}catch(IllegalArgumentException expected){rejected=true;}ok(rejected,"invalid flat position rejected");
            rejected=false;try{p.openQueueWindow(Arrays.asList(new PlaybackCachePolicy.QueueEntry("a",1),new PlaybackCachePolicy.QueueEntry("a",1)),0,true,5,true);}catch(IllegalArgumentException expected){rejected=true;}ok(rejected,"duplicate coordinates rejected");
            // An old unprotected transfer must not refill the queue after a forward jump.
            CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);Path asyncDir=dir.resolve("async");
            PlaybackCachePolicy async=new PlaybackCachePolicy(asyncDir.toFile(),1000,100,2000,(u,f,m)->{started.countDown();finish.await(3,TimeUnit.SECONDS);Files.write(f.toPath(),new byte[16]);});
            PlaybackCachePolicy.QueueWindow active=async.openQueueWindow(queue(),0,true,5,true);
            ExecutorService executor=Executors.newSingleThreadExecutor();Future<Boolean> download=executor.submit(()->{try{async.fetch("a","v1",1,"fixture");return false;}catch(IOException expected){return true;}});
            ok(started.await(3,TimeUnit.SECONDS),"old transfer started");active.moveTo(6);finish.countDown();ok(download.get(5,TimeUnit.SECONDS),"obsolete old transfer rejected");ok(async.find("a","v1",1)==null,"obsolete transfer not published");active.close();executor.shutdownNow();
            System.out.println("PASS SeasonQueueCacheTest checks="+checks+" ahead=5 realTemporaryFiles=true oldReadersProtected=true networkRequests=0");
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException e){throw new RuntimeException(e);}});}}
    }
}
