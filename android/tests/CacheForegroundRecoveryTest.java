package io.github.jackotom.hongguo.android;
import java.io.*;
import java.lang.reflect.*;
import java.net.SocketTimeoutException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual policy, temporary owned files, deterministic handoff timing; no network/device. */
public final class CacheForegroundRecoveryTest {
    static int checks;
    interface Checked { void run() throws Exception; }
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void write(File file,int bytes)throws Exception{Files.write(file.toPath(),new byte[bytes]);}
    static void reject(Checked action,String label)throws Exception{try{action.run();}catch(Exception expected){checks++;return;}throw new AssertionError(label);}
    static Object call(String name,PlaybackCachePolicy policy,Class<?>[]types,Object...args)throws Exception{
        try{return PlaybackCachePolicy.class.getDeclaredMethod(name,types).invoke(policy,args);}catch(InvocationTargetException error){throw (Exception)error.getCause();}
    }
    static File foreground(PlaybackCachePolicy policy,String vid,String url,PlaybackCachePolicy.Downloader transfer)throws Exception{
        try{return (File)call("fetchForeground",policy,new Class<?>[]{String.class,String.class,int.class,String.class,PlaybackCachePolicy.Downloader.class},"series",vid,2,url,transfer);}
        catch(NoSuchMethodException baseline){return policy.fetch("series",vid,2,url,transfer);}
    }
    static void invalidate(PlaybackCachePolicy policy,String vid)throws Exception{call("invalidate",policy,new Class<?>[]{String.class,String.class,int.class},"series",vid,2);}
    static void waiting(Thread thread)throws Exception{for(int i=0;i<200;i++){if(thread.getState()==Thread.State.WAITING){checks++;return;}Thread.sleep(5);}throw new AssertionError("foreground did not join actual Flight");}
    static Thread job(Checked action,AtomicReference<Exception> error){return new Thread(()->{try{action.run();}catch(Exception value){error.set(value);}});}
    static void join(Thread thread)throws Exception{thread.join(4000);check(!thread.isAlive(),"task terminates without deadlock");}
    static void remove(File file)throws Exception{if(file.isDirectory()&&!Files.isSymbolicLink(file.toPath()))for(File child:Objects.requireNonNull(file.listFiles()))remove(child);Files.deleteIfExists(file.toPath());}
    public static void main(String[]args)throws Exception{
        File root=Files.createTempDirectory("cache-foreground-recovery-").toFile();
        try{
            for(int mode=0;mode<4;mode++){
                final int failureMode=mode;CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();List<String> urls=Collections.synchronizedList(new ArrayList<>());
                PlaybackCachePolicy.Downloader transfer=(url,file,limit)->{int n=calls.incrementAndGet();urls.add(url);if(n==1){entered.countDown();if(!finish.await(3,TimeUnit.SECONDS))throw new IOException("fixture timeout");if(failureMode==1)throw new SocketTimeoutException("old prefetch timeout");if(failureMode==2)throw new InterruptedIOException("old prefetch cancelled");throw new IOException("old prefetch HTTP403");}if(failureMode==3)throw new IOException("fresh foreground also failed");write(file,12);};
                PlaybackCachePolicy policy=new PlaybackCachePolicy(new File(root,"handoff-"+mode),256,96,transfer);AtomicReference<Exception> ownerError=new AtomicReference<>(),frontError=new AtomicReference<>();AtomicReference<File> result=new AtomicReference<>();
                Thread owner=job(()->policy.fetch("series","video",2,"old-url"),ownerError);owner.start();check(entered.await(3,TimeUnit.SECONDS),"prefetch started");policy.protect("series","video",2);
                Thread front=job(()->result.set(foreground(policy,"video","fresh-url",transfer)),frontError);front.start();waiting(front);finish.countDown();join(owner);join(front);
                check(ownerError.get()!=null,"prefetch failure remains visible to its owner");
                if(mode<3){check(frontError.get()==null&&result.get()!=null,"live foreground independently takes over failed or cancelled background prefetch");check(calls.get()==2&&urls.get(1).equals("fresh-url"),"new Flight uses foreground fresh URL exactly once");check(result.get().length()==12,"only new complete bytes published");}
                else{check(frontError.get()!=null&&calls.get()==2,"failed fresh foreground is not recursively retried");check(policy.status().files==0,"both failed transfers leave no file");}
                check(Arrays.stream(Objects.requireNonNull(new File(root,"handoff-"+mode).listFiles())).noneMatch(f->f.getName().endsWith(".part")),"partial file cleaned in every outcome");
            }
            AtomicInteger ownCalls=new AtomicInteger();PlaybackCachePolicy.Downloader failure=(u,f,m)->{ownCalls.incrementAndGet();throw new IOException("own failure");};
            PlaybackCachePolicy own=new PlaybackCachePolicy(new File(root,"own"),256,96,failure);
            reject(()->foreground(own,"video","own",failure),"own foreground failure accepted");check(ownCalls.get()==1,"new foreground owner never adds another 2x8 prepare cycle");
            CountDownLatch enter=new CountDownLatch(1),finish=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
            PlaybackCachePolicy.Downloader cancelWait=(u,f,m)->{calls.incrementAndGet();enter.countDown();if(!finish.await(3,TimeUnit.SECONDS))throw new IOException("fixture timeout");write(f,7);};
            PlaybackCachePolicy cancelled=new PlaybackCachePolicy(new File(root,"waiter-cancel"),256,96,cancelWait);AtomicReference<Exception> ownerError=new AtomicReference<>(),waiterError=new AtomicReference<>();AtomicBoolean interrupted=new AtomicBoolean();
            Thread owner=job(()->cancelled.fetch("series","video",2,"old"),ownerError);owner.start();check(enter.await(3,TimeUnit.SECONDS),"cancellation owner starts");
            Thread waiter=new Thread(()->{try{foreground(cancelled,"video","fresh",cancelWait);}catch(Exception error){waiterError.set(error);interrupted.set(Thread.currentThread().isInterrupted());}});waiter.start();waiting(waiter);waiter.interrupt();join(waiter);finish.countDown();join(owner);
            check(waiterError.get()!=null&&interrupted.get(),"generation cancellation retains interruption");check(calls.get()==1&&ownerError.get()==null,"cancelled waiter does not cancel or redownload shared owner");
            PlaybackCachePolicy files=new PlaybackCachePolicy(new File(root,"invalidated"),256,96,(u,f,m)->write(f,8));
            File bad=files.fetch("series","video|quality=720p",2,"bad"),other=files.fetch("series","video|quality=1080p",2,"good");
            File manual=new File(root,"manual-download.mp4"),unrelated=new File(bad.getParentFile(),"manual-owned-elsewhere.mp4");write(manual,23);write(unrelated,19);byte[] manualBytes=Files.readAllBytes(manual.toPath());
            write(bad,1);check(files.find("series","video|quality=720p",2)!=null,"generic policy contract does not guess corruption from one byte");files.protect("series","video|quality=720p",2);invalidate(files,"video|quality=720p");
            check(!bad.exists()&&files.find("series","video|quality=720p",2)==null,"explicit failed decoder invalidates protected exact key");check(other.exists(),"other quality preserved");check(Arrays.equals(manualBytes,Files.readAllBytes(manual.toPath()))&&unrelated.length()==19,"manual and unrelated files unchanged");
            CountDownLatch in=new CountDownLatch(1),out=new CountDownLatch(1);AtomicInteger invalidCalls=new AtomicInteger();
            PlaybackCachePolicy.Downloader invalidTransfer=(u,f,m)->{int count=invalidCalls.incrementAndGet();write(f,count==1?4:9);if(count==1){in.countDown();if(!out.await(3,TimeUnit.SECONDS))throw new IOException("fixture timeout");}};
            PlaybackCachePolicy active=new PlaybackCachePolicy(new File(root,"active-invalidated"),256,96,invalidTransfer);AtomicReference<Exception> invalidOwner=new AtomicReference<>(),newOwner=new AtomicReference<>();AtomicReference<File> newFile=new AtomicReference<>();
            Thread old=job(()->active.fetch("series","video",2,"bad-web"),invalidOwner);old.start();check(in.await(3,TimeUnit.SECONDS),"old web cache starts");active.protect("series","video",2);invalidate(active,"video");active.protect("series","video",2);
            Thread replacement=job(()->newFile.set(foreground(active,"video","fresh-app",invalidTransfer)),newOwner);replacement.start();waiting(replacement);out.countDown();join(old);join(replacement);
            check(invalidOwner.get()!=null&&newOwner.get()==null,"protection cannot resurrect explicitly invalidated web flight");check(invalidCalls.get()==2&&newFile.get().length()==9,"fresh App replaces invalidated temporary bytes");
            PlaybackCachePolicy interruptedCommit=new PlaybackCachePolicy(new File(root,"interrupted-owner"),256,96,(u,f,m)->{write(f,3);Thread.currentThread().interrupt();});AtomicReference<Exception> interruptedError=new AtomicReference<>();Thread interruptedOwner=job(()->interruptedCommit.fetch("series","video",2,"u"),interruptedError);interruptedOwner.start();join(interruptedOwner);
            check(interruptedError.get() instanceof InterruptedIOException&&interruptedCommit.status().files==0,"interrupted owner never publishes after a transfer returns");
            System.out.println("PASS CacheForegroundRecoveryTest checks="+checks+" realPolicyConcurrency=true sharedFailureRetryBound=1 ownFailureRetry=0 exactInvalidation=true manualFilesPreserved=true networkRequests=0 deviceOperations=0");
        }finally{remove(root);}
    }
}
