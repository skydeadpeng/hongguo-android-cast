package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic resolver-future boundaries; no DNS, HTTP, Android or media requests. */
public final class NetworkGuardRaceTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static final Callable<InetAddress[]> UNUSED = () -> { throw new AssertionError("Unexpected resolver execution"); };
    private static InetAddress[] addresses(String... literals) throws Exception {
        InetAddress[] result = new InetAddress[literals.length];
        for (int i = 0; i < literals.length; i++) result[i] = InetAddress.getByName(literals[i]);
        return result;
    }
    private static long deadline(long millis) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
    private static final class Lane implements Future<InetAddress[]> {
        final InetAddress[] answer; final Throwable failure;
        volatile boolean done, cancelled; int gets, cancels;
        Lane(boolean done, InetAddress[] answer, Throwable failure) { this.done=done;this.answer=answer;this.failure=failure; }
        public boolean cancel(boolean interrupt) { cancels++;cancelled=true;done=true;return true; }
        public boolean isCancelled() { return cancelled; }
        public boolean isDone() { return done; }
        public InetAddress[] get() throws ExecutionException {
            gets++;
            if (!done) throw new AssertionError("Read unfinished lane");
            if (cancelled) throw new CancellationException();
            if (failure!=null) throw new ExecutionException(failure);
            return answer;
        }
        public InetAddress[] get(long timeout, TimeUnit unit) throws ExecutionException { return get(); }
    }
    private static final class Pool extends AbstractExecutorService {
        final Lane first, second; final int rejectAt;
        final CountDownLatch submitted = new CountDownLatch(1);
        int submissions;
        Pool(Lane first,Lane second) { this(first,second,0); }
        Pool(Lane first,Lane second,int rejectAt) { this.first=first;this.second=second;this.rejectAt=rejectAt; }
        @SuppressWarnings("unchecked") public <T> Future<T> submit(Callable<T> task) {
            int count=++submissions;
            if(count==rejectAt)throw new RejectedExecutionException("fixture saturation");
            if(count>2)throw new AssertionError("Unexpected third resolver submission");
            if(count==2)submitted.countDown();
            return (Future<T>)(count==1?first:second);
        }
        public void execute(Runnable task) { throw new AssertionError("Unexpected execution"); }
        public void shutdown() {}
        public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long timeout,TimeUnit unit) { return true; }
    }
    private static InetAddress[] race(Pool pool,long deadline) throws IOException {
        return NetworkGuard.racePublic(pool,UNUSED,UNUSED,deadline);
    }
    private static void closed(Pool pool) {
        check(pool.first.cancels==1,"first future cancelled once");
        check(pool.second.cancels==1,"second future cancelled once");
    }
    public static void main(String[] args) throws Exception {
        InetAddress[] good=addresses("1.1.1.1","8.8.8.8");
        // Both ready deterministically reproduces the old bug: failed first was consumed twice.
        for (Lane bad : new Lane[]{new Lane(true,null,new UnknownHostException("fixture")),
                new Lane(true,null,null),new Lane(true,new InetAddress[0],null),
                new Lane(true,addresses("198.18.0.1"),null),
                new Lane(true,addresses("1.1.1.1","127.0.0.1"),null),
                new Lane(true,new InetAddress[]{null},null),
                new Lane(true,null,new IllegalStateException("fixture"))}) {
            Pool pool=new Pool(bad,new Lane(true,good,null));
            check(race(pool,deadline(1000))==good,"invalid first does not poison public second");
            check(pool.first.gets==1&&pool.second.gets==1,"consume each completed lane exactly once");closed(pool);
        }
        Pool fast=new Pool(new Lane(true,good,null),new Lane(false,null,null));
        check(race(fast,deadline(1000))==good,"first valid lane wins");
        check(fast.second.gets==0,"unfinished loser never read");closed(fast);
        Pool secondWins=new Pool(new Lane(false,null,null),new Lane(true,good,null));
        check(race(secondWins,deadline(1000))==good,"second valid lane wins before first finishes");
        check(secondWins.first.gets==0,"hung first never consumed");closed(secondWins);
        for(boolean failedFirst:new boolean[]{false,true}) {
            Pool hung=new Pool(new Lane(failedFirst,null,failedFirst?new IOException("fixture"):null),new Lane(false,null,null));
            long start=System.nanoTime();
            try {race(hung,deadline(60));throw new AssertionError("Hung lanes accepted");}
            catch(SocketTimeoutException expected) {check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2),"pending lanes obey shared deadline");}
            check(hung.first.gets==(failedFirst?1:0),"failed first not reread while second hangs");closed(hung);
        }
        Pool bothBad=new Pool(new Lane(true,null,new UnknownHostException("fixture")),new Lane(true,addresses("10.0.0.1"),null));
        try {race(bothBad,deadline(1000));throw new AssertionError("Private lane accepted");}
        catch(IOException expected) {check(bothBad.first.gets==1&&bothBad.second.gets==1,"both failures consumed once");}closed(bothBad);
        Pool cancelled=new Pool(new Lane(false,null,null),new Lane(false,null,null));
        AtomicReference<Throwable> result=new AtomicReference<>();AtomicBoolean interrupted=new AtomicBoolean();
        Thread caller=new Thread(()->{
            try {race(cancelled,deadline(5000));result.set(new AssertionError("Cancelled race accepted"));}
            catch(Throwable failure){result.set(failure);interrupted.set(Thread.currentThread().isInterrupted());}
        });
        caller.start();check(cancelled.submitted.await(1,TimeUnit.SECONDS),"both lanes submitted before interrupt");
        caller.interrupt();caller.join(2000);
        check(!caller.isAlive(),"interrupted caller stops promptly");
        check(result.get() instanceof InterruptedIOException&&interrupted.get(),"interrupt type and flag preserved");closed(cancelled);
        Pool rejected=new Pool(new Lane(false,null,null),new Lane(false,null,null),2);
        try {race(rejected,deadline(1000));throw new AssertionError("Rejected submission accepted");}
        catch(RejectedExecutionException expected){check(rejected.first.cancels==1&&rejected.second.cancels==0,"second submission rejection cancels first only");}
        Pool rejectedFirst=new Pool(new Lane(false,null,null),new Lane(false,null,null),1);
        try {race(rejectedFirst,deadline(1000));throw new AssertionError("Rejected first accepted");}
        catch(RejectedExecutionException expected){check(rejectedFirst.submissions==1&&rejectedFirst.first.cancels==0,"first rejection creates no future");}
        Pool expired=new Pool(new Lane(false,null,null),new Lane(false,null,null));
        try {race(expired,deadline(-1));throw new AssertionError("Expired request accepted");}
        catch(SocketTimeoutException expected){check(expired.submissions==0,"expired request submits no resolver");}
        Thread.currentThread().interrupt();
        try {race(expired,deadline(1000));throw new AssertionError("Interrupted request accepted");}
        catch(InterruptedIOException expected){check(expired.submissions==0&&Thread.currentThread().isInterrupted(),"pre-interrupted request submits no resolver");}
        finally {Thread.interrupted();}
        System.out.println("PASS NetworkGuardRaceTest checks="+checks+" networkRequests=0 deadlineAndCancellation=actualProduction");
    }
}
