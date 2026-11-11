package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Actual prefetch coordinator with deterministic transfer barriers; no external network. */
public final class DlnaPrefetchTest {
    static int checks,groups;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void await(BooleanSupplier condition,String label)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);check(condition.getAsBoolean(),label);}
    static void group(String name){groups++;System.out.println("PASS DlnaPrefetch "+name);}
    static final class Gate {
        final CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);volatile boolean ignoreInterrupt;
        void block()throws InterruptedException{entered.countDown();while(true)try{finish.await();return;}catch(InterruptedException error){if(!ignoreInterrupt)throw error;}}
        void entered()throws Exception{check(entered.await(4,TimeUnit.SECONDS),"loader barrier entered");}
    }
    static final class Loader implements DlnaPrefetch.Loader {
        final List<Integer> loads=Collections.synchronizedList(new ArrayList<>());
        final List<String> qualities=Collections.synchronizedList(new ArrayList<>());
        final Map<Integer,Gate> gates=new ConcurrentHashMap<>();final Set<Integer> failing=ConcurrentHashMap.newKeySet(),backgroundFailing=ConcurrentHashMap.newKeySet();
        final AtomicInteger leases=new AtomicInteger(),activeBackground=new AtomicInteger(),maxBackground=new AtomicInteger();
        final List<Boolean> priorities=Collections.synchronizedList(new ArrayList<>());volatile int low=-1;
        public DlnaSession.Prepared prepare(int position,String quality,boolean foreground,BooleanSupplier alive)throws Exception {
            loads.add(position);qualities.add(position+":"+quality);priorities.add(foreground);
            if(!foreground){int active=activeBackground.incrementAndGet();maxBackground.accumulateAndGet(active,Math::max);}
            try {
                Gate gate=gates.get(position);if(gate!=null)gate.block();
                if(failing.contains(position)||!foreground&&backgroundFailing.contains(position))throw new IOException("fixture fail https://secret.invalid/token?q=private");
                // A deliberately late resolver may ignore cancellation; the coordinator must discard its owner.
                if(gate==null||!gate.ignoreInterrupt)if(!alive.getAsBoolean())throw new InterruptedIOException("cancelled");
                return AdaptiveQuality.prepare(quality,q->{
                    if(position==low&&q.equals("1080p"))throw new IOException("synthetic 1080 unavailable");
                    leases.incrementAndGet();
                    return new DlnaSession.Prepared(new File("fixture-"+position+"-"+q+".mp4"),"episode "+(position+1),q,position+1,leases::incrementAndGet,()->{if(leases.decrementAndGet()<0)throw new AssertionError("negative owner");});
                },error->true,()->gate!=null&&gate.ignoreInterrupt||alive.getAsBoolean(),(q,s,e)->{},DlnaSession.Prepared::releaseOwner).value;
            }finally{if(!foreground)activeBackground.decrementAndGet();}
        }
        int calls(int position){synchronized(loads){return Collections.frequency(loads,position);}}
    }
    static DlnaSession.Prepared first(DlnaPrefetch queue)throws Exception{queue.selecting(0);return queue.prepare(0,"1080p",()->true);}
    static void fiveAndSliding()throws Exception {
        Loader loader=new Loader();DlnaPrefetch queue=new DlnaPrefetch(12,loader);DlnaSession.Prepared initial=first(queue);
        try{
            check(loader.loads.equals(Arrays.asList(0)),"no prefetch before confirmed PLAYING");
            queue.confirmed(0,"1080p",true);await(()->queue.preparedCount()==5,"first five prepared");
            check(loader.loads.equals(Arrays.asList(0,1,2,3,4,5)),"next five in strict order, not whole series");
            check(loader.maxBackground.get()==1,"only one background loader at a time");
            check(loader.priorities.get(0)&&loader.priorities.subList(1,6).stream().noneMatch(Boolean::booleanValue),"foreground/background priorities preserved");
            queue.confirmed(0,"1080p",true);Thread.sleep(30);check(loader.loads.size()==6,"duplicate confirmation does not download twice");
            queue.selecting(1);DlnaSession.Prepared next=queue.prepare(1,"1080p",()->true);check(loader.calls(1)==1,"completed prefetch transfers lease without duplicate download");
            initial.releaseOwner();queue.confirmed(1,next.quality,true);await(()->queue.preparedCount()==5,"sliding window refilled");
            check(loader.loads.equals(Arrays.asList(0,1,2,3,4,5,6)),"sliding only prepares newly added sixth");next.releaseOwner();
        }finally{initial.releaseOwner();queue.close();}
        await(()->loader.leases.get()==0,"five ready leases all released on stop");group("five-serial-sliding-cache-hit-duplicate-and-priority");
    }
    static void failureBounded()throws Exception {
        Loader loader=new Loader();loader.failing.add(2);DlnaPrefetch queue=new DlnaPrefetch(10,loader);DlnaSession.Prepared initial=first(queue);
        try{
            queue.confirmed(0,"1080p",true);await(()->loader.calls(5)==1&&queue.preparedCount()==4,"one failed future does not prevent other window entries");
            queue.confirmed(0,"1080p",true);Thread.sleep(30);check(loader.calls(2)==1,"failed prefetch is not automatically retried in same window");
            String diag=queue.diagnostics();check(diag.contains("第 3 集")&&!diag.contains("secret.invalid")&&!diag.contains("private"),"failure diagnostic is useful and URL-redacted");
            loader.failing.remove(2);queue.selecting(2);DlnaSession.Prepared retried=queue.prepare(2,"1080p",()->true);check(loader.calls(2)==2,"explicit foreground retries prior background failure");retried.releaseOwner();
        }finally{initial.releaseOwner();queue.close();}
        await(()->loader.leases.get()==0,"failure and foreground owners balanced");group("bounded-failure-no-storm-redaction-explicit-retry");
    }
    static void sameEpisodeTakeover()throws Exception {
        Loader loader=new Loader();Gate held=new Gate();loader.gates.put(1,held);DlnaPrefetch queue=new DlnaPrefetch(10,loader);DlnaSession.Prepared initial=first(queue);ExecutorService caller=Executors.newSingleThreadExecutor();
        try{
            queue.confirmed(0,"1080p",true);held.entered();queue.selecting(1);
            Future<DlnaSession.Prepared> waiting=caller.submit(()->queue.prepare(1,"1080p",()->true));Thread.sleep(30);
            check(!waiting.isDone()&&loader.calls(1)==1,"foreground shares current prefetch instead of cancelling");
            held.finish.countDown();DlnaSession.Prepared next=waiting.get(4,TimeUnit.SECONDS);check(loader.calls(1)==1&&next.episodeIndex==2,"shared transfer returns exact requested episode");
            next.releaseOwner();check(loader.calls(2)==0,"not-yet-confirmed target cannot start more prefetch");
        }finally{held.finish.countDown();caller.shutdownNow();initial.releaseOwner();queue.close();}
        await(()->loader.leases.get()==0,"shared transfer owner balance");group("foreground-adopts-active-same-episode-without-cancellation");
    }
    static void takeoverFailureRetriesOnce()throws Exception {
        for(boolean foregroundAlsoFails:new boolean[]{false,true}) {
            Loader loader=new Loader();loader.backgroundFailing.add(1);if(foregroundAlsoFails)loader.failing.add(1);
            Gate held=new Gate();loader.gates.put(1,held);DlnaPrefetch queue=new DlnaPrefetch(10,loader);DlnaSession.Prepared initial=first(queue);ExecutorService caller=Executors.newSingleThreadExecutor();
            try {
                queue.confirmed(0,"1080p",true);held.entered();queue.selecting(1);
                Future<DlnaSession.Prepared> waiting=caller.submit(()->queue.prepare(1,"1080p",()->true));Thread.sleep(20);held.finish.countDown();
                if(foregroundAlsoFails){boolean failed=false;try{waiting.get(4,TimeUnit.SECONDS);}catch(ExecutionException expected){failed=expected.getCause() instanceof IOException;}check(failed,"foreground own failure surfaces without endless retry");}
                else {DlnaSession.Prepared next=waiting.get(4,TimeUnit.SECONDS);check(next.episodeIndex==2,"one foreground retry recovers background takeover failure");next.releaseOwner();}
                check(loader.calls(1)==2&&loader.priorities.get(loader.priorities.size()-1),"only one genuine foreground retry after failed shared background");
                check(loader.calls(2)==0,"failure retry never starts or skips to later episode");
            }finally{held.finish.countDown();caller.shutdownNow();initial.releaseOwner();queue.close();}
            await(()->loader.leases.get()==0,"takeover failure lease cleanup");
        }
        Loader loader=new Loader();loader.backgroundFailing.add(1);Gate held=new Gate();loader.gates.put(1,held);DlnaPrefetch queue=new DlnaPrefetch(10,loader);DlnaSession.Prepared initial=first(queue);ExecutorService caller=Executors.newSingleThreadExecutor();AtomicBoolean alive=new AtomicBoolean(true);
        try {
            queue.confirmed(0,"1080p",true);held.entered();queue.selecting(1);
            Future<DlnaSession.Prepared> waiting=caller.submit(()->queue.prepare(1,"1080p",alive::get));alive.set(false);held.finish.countDown();
            boolean cancelled=false;try{waiting.get(4,TimeUnit.SECONDS);}catch(ExecutionException expected){cancelled=expected.getCause() instanceof InterruptedIOException;}
            check(cancelled&&loader.calls(1)==1,"cancelled foreground waiter never starts takeover retry");
        }finally{held.finish.countDown();caller.shutdownNow();initial.releaseOwner();queue.close();}
        await(()->loader.leases.get()==0,"cancelled waiter lease cleanup");group("shared-background-failure-one-foreground-retry-not-cancelled-waiter");
    }
    static void foregroundPriorityAndLateCancel()throws Exception {
        Loader loader=new Loader();Gate held=new Gate();held.ignoreInterrupt=true;loader.gates.put(1,held);DlnaPrefetch queue=new DlnaPrefetch(12,loader);DlnaSession.Prepared initial=first(queue);
        try{
            queue.confirmed(0,"1080p",true);held.entered();queue.selecting(8);
            DlnaSession.Prepared selected=queue.prepare(8,"1080p",()->true);check(selected.episodeIndex==9&&held.finish.getCount()==1,"unrelated slow cancelled background cannot block foreground target");selected.releaseOwner();
            queue.close();held.finish.countDown();await(()->loader.activeBackground.get()==0,"late cancelled background finishes");
            check(loader.calls(2)==0,"cancelled old window never continues downloading");
        }finally{held.finish.countDown();initial.releaseOwner();queue.close();}
        await(()->loader.leases.get()==0,"late result discarded without leak after close");group("foreground-priority-unrelated-slow-source-and-late-result-release");
    }
    static void qualityAndToggle()throws Exception {
        Loader loader=new Loader();loader.low=1;DlnaPrefetch queue=new DlnaPrefetch(9,loader);DlnaSession.Prepared initial=first(queue);
        try{
            queue.confirmed(0,"1080p",true);await(()->queue.preparedCount()==5,"quality fixture window ready");queue.selecting(1);
            DlnaSession.Prepared next=queue.prepare(1,"1080p",()->true);check(next.quality.equals("720p")&&next.file.getName().contains("720p"),"fallback retains effective quality and distinct cache identity");
            queue.confirmed(1,next.quality,true);await(()->queue.preparedCount()==5,"effective lower-quality window refilled");
            check(loader.qualities.contains("6:720p")&&loader.qualities.contains("2:720p"),"new background tasks begin at actual successful quality");next.releaseOwner();
            queue.autoplay(false);check(queue.preparedCount()==0&&queue.diagnostics().contains("连播关闭"),"autoplay off releases future leases");
            int calls=loader.loads.size();Thread.sleep(30);check(loader.loads.size()==calls,"autoplay off does not start more work");
            queue.autoplay(true);await(()->queue.preparedCount()==5,"autoplay on restarts bounded window");
        }finally{initial.releaseOwner();queue.close();}
        await(()->loader.leases.get()==0,"quality invalidation releases all variants");group("adaptive-quality-carry-forward-and-autoplay-off-on");
    }
    static void endOfQueueAndCancelledWaiter()throws Exception {
        Loader loader=new Loader();DlnaPrefetch queue=new DlnaPrefetch(3,loader);DlnaSession.Prepared initial=first(queue);
        try{
            queue.confirmed(0,"1080p",true);await(()->queue.preparedCount()==2,"last window limited by episode count");
            queue.selecting(2);DlnaSession.Prepared last=queue.prepare(2,"1080p",()->true);queue.confirmed(2,last.quality,true);last.releaseOwner();
            check(queue.preparedCount()==0&&loader.loads.size()==3,"last episode never prepares phantom next");
            queue.close();boolean cancelled=false;try{queue.prepare(2,"1080p",()->true);}catch(InterruptedIOException expected){cancelled=true;}check(cancelled,"closed coordinator rejects future work");
        }finally{initial.releaseOwner();queue.close();}
        await(()->loader.leases.get()==0,"last window cleanup balanced");group("last-episode-bounds-close-idempotence");
    }
    static void productionSessionNeverAutoSkips()throws Exception {
        Loader loader=new Loader();DlnaPrefetch queue=new DlnaPrefetch(10,loader);
        DlnaSessionTest.Clock clock=new DlnaSessionTest.Clock();DlnaSessionTest.Control control=new DlnaSessionTest.Control();DlnaSessionTest.Server server=new DlnaSessionTest.Server();
        DlnaSession.Source source=new DlnaSession.Source(){
            public void selecting(int position){queue.selecting(position);}public DlnaSession.Prepared prepare(int position,String quality,BooleanSupplier alive)throws Exception{return queue.prepare(position,quality,alive);}
            public void confirmed(int position,long progress,boolean cleanup){}public void playbackConfirmed(int position,String actual,boolean autoplay){queue.confirmed(position,actual,autoplay);}
            public void autoplayChanged(boolean value){queue.autoplay(value);}public void close(){queue.close();}
        };
        DlnaSession session=new DlnaSession(clock,control,source,server,snapshot->{},"fixture-series","fixture","Zeekr-fixture_投屏",10,"1080p",true,true);
        try{
            session.start(0,0);clock.prepare(0,false);await(()->queue.preparedCount()==5,"actual Session PLAYING hook starts five background preparations");
            check(control.sets==1&&control.plays==1&&session.snapshot().current==0&&session.snapshot().targetCurrent==0,"ready prefetch cannot publish/control/advance actual session");
            control.state="PAUSED_PLAYBACK";control.position=control.duration;clock.tick();clock.tick();clock.tick();check(control.sets==1,"no fresh forward evidence means paused EOF is not changed by prefetch");
            control.state="PLAYING";control.position=99000;clock.tick();control.state="STOPPED";clock.tick();check(session.snapshot().targetCurrent==1&&clock.preparations.size()==2,"existing natural end alone queues next");
            clock.prepare(1,false);check(session.snapshot().current==1&&control.sets==2&&loader.calls(1)==1,"actual next adopts already-ready cache without repeat transfer");
            session.setAutoplay(false);check(queue.preparedCount()==0,"session preference hook stops prefetch");session.stop();check(queue.diagnostics().contains("已关闭"),"session stop closes source resources");
        }finally{session.close();queue.close();}
        await(()->loader.leases.get()==0,"actual Session publication and prefetch owners balanced");group("actual-session-hooks-no-prefetch-play-no-changed-eof-and-stop");
    }
    static void restoredOldPlaybackRestartsWindow()throws Exception {
        Loader loader=new Loader();DlnaPrefetch queue=new DlnaPrefetch(12,loader);
        DlnaSessionTest.Clock clock=new DlnaSessionTest.Clock();DlnaSessionTest.Control control=new DlnaSessionTest.Control();DlnaSessionTest.Server server=new DlnaSessionTest.Server();
        DlnaSession.Source source=new DlnaSession.Source(){
            public void selecting(int position){queue.selecting(position);}public DlnaSession.Prepared prepare(int position,String quality,BooleanSupplier alive)throws Exception{return queue.prepare(position,quality,alive);}
            public void confirmed(int position,long progress,boolean cleanup){}public void playbackConfirmed(int position,String actual,boolean autoplay){queue.confirmed(position,actual,autoplay);}
            public void autoplayChanged(boolean value){queue.autoplay(value);}public void close(){queue.close();}
        };
        DlnaSession session=new DlnaSession(clock,control,source,server,snapshot->{},"fixture-series","fixture","fixture TV",12,"1080p",true,true);
        try {
            session.start(0,0);clock.prepare(0,false);await(()->queue.preparedCount()==5,"old current prefetch filled before failed selection");
            loader.failing.add(8);session.select(8);clock.prepare(1,false);
            check(session.snapshot().current==0&&session.snapshot().targetCurrent==8&&control.sets==1,"failed distant selection does not publish new media or alter current");
            check(queue.preparedCount()==0&&queue.diagnostics().contains("等待本集确认"),"failed source alone is not positive old-URI playback confirmation");
            clock.tick();await(()->queue.preparedCount()==5,"confirmed old URI PLAYING restores previous prefetch anchor");
            check(session.snapshot().current==0&&control.sets==1&&loader.calls(1)==2,"old current remains while its five-window resumes");
        }finally{session.close();queue.close();}
        await(()->loader.leases.get()==0,"restored window and publication owners balanced");group("actual-session-failed-selection-old-uri-playing-restores-window");
    }
    static void realCacheWindow()throws Exception {
        Path root=Files.createTempDirectory("hongguo-prefetch-cache-");AtomicInteger downloads=new AtomicInteger(),leases=new AtomicInteger();String sid="fixture-series";
        PlaybackCachePolicy cache=new PlaybackCachePolicy(root.toFile(),256L*1024*1024,96L*1024*1024,(url,file,max)->{downloads.incrementAndGet();Files.write(file.toPath(),new byte[4096]);});
        PlaybackCachePolicy.CastWindow window=cache.openCastWindow(sid,1);
        DlnaPrefetch queue=new DlnaPrefetch(12,(position,quality,foreground,alive)->{
            String vid=MediaQuality.cacheVid("fixture-"+position,quality);int index=position+1;
            cache.protect(sid,vid,index);leases.incrementAndGet();boolean success=false;
            try{
                File file=cache.find(sid,vid,index);if(file==null)file=foreground?cache.fetchForeground(sid,vid,index,"fixture:"+position,(u,f,m)->{downloads.incrementAndGet();Files.write(f.toPath(),new byte[4096]);}):cache.fetch(sid,vid,index,"fixture:"+position);
                if(!alive.getAsBoolean())throw new InterruptedIOException("fixture cancelled");success=true;
                return new DlnaSession.Prepared(file,"fixture",quality,index,()->{cache.protect(sid,vid,index);leases.incrementAndGet();},()->{cache.release(sid,vid,index);leases.decrementAndGet();});
            }finally{if(!success){cache.release(sid,vid,index);leases.decrementAndGet();}}
        });
        DlnaSession.Prepared initial=null;
        try{
            initial=first(queue);queue.confirmed(0,"1080p",true);await(()->queue.preparedCount()==5,"real cache five entries ready");
            cache.prune(sid,1,1);check(cache.status().files==6,"legacy prune keeps active cast's current plus five real files");
            queue.selecting(1);window.moveTo(2);DlnaSession.Prepared second=queue.prepare(1,"1080p",()->true);check(downloads.get()==6,"real cache ready foreground reuses file without downloading");second.releaseOwner();initial.releaseOwner();initial=null;queue.confirmed(1,"1080p",true);await(()->queue.preparedCount()==5,"real cache sliding refills");
            cache.prune(sid,2,1);check(cache.status().files==7&&downloads.get()==7,"real sliding current previous and five future retained");
            queue.selecting(2);window.moveTo(3);DlnaSession.Prepared third=queue.prepare(2,"1080p",()->true);queue.confirmed(2,"1080p",true);third.releaseOwner();await(()->queue.preparedCount()==5,"second real slide completes");
            cache.prune(sid,3,1);check(cache.find(sid,MediaQuality.cacheVid("fixture-0","1080p"),1)==null,"already-watched out-of-window real file cleaned");
            queue.close();window.close();check(leases.get()==0,"all real cache preparation leases released");cache.clear();check(cache.status().files==0,"stopped prefetch files clearable without hidden pins");
        }finally{if(initial!=null)initial.releaseOwner();queue.close();window.close();try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}}
        group("real-cache-five-window-sliding-hit-cleanup-and-stop-release");
    }
    public static void main(String[] args)throws Exception {
        fiveAndSliding();failureBounded();sameEpisodeTakeover();takeoverFailureRetriesOnce();foregroundPriorityAndLateCancel();qualityAndToggle();endOfQueueAndCancelledWaiter();productionSessionNeverAutoSkips();restoredOldPlaybackRestartsWindow();realCacheWindow();
        System.out.println("DLNA_PREFETCH_OK groups="+groups+" checks="+checks+" ahead=5 serial=true actualCoordinator=true actualSession=true actualCache=true foregroundPriority=true deviceTests=not_run networkRequests=0");
    }
}
