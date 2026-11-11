package io.github.jackotom.hongguo.android;

import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Owns preparation leases only. It cannot publish media, control a renderer or advance an episode. */
final class DlnaPrefetch implements AutoCloseable {
    static final int AHEAD = 5;
    interface Loader {
        DlnaSession.Prepared prepare(int position, String quality, boolean foreground, BooleanSupplier alive) throws Exception;
    }
    private static final class Job {
        final int position; final String quality; final boolean foreground;
        volatile boolean cancelled; Future<?> future; boolean finished;
        DlnaSession.Prepared media; Exception failure;
        Job(int position, String quality, boolean foreground) { this.position=position; this.quality=quality; this.foreground=foreground; }
    }
    private final int count;
    private final Loader loader;
    // One background job at a time; a foreground selection has its own slot so cancelling
    // a slow unrelated resolver does not enqueue that selection behind the whole window.
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4), runnable->{Thread thread=new Thread(runnable,"hongguo-cast-prepare");thread.setDaemon(true);return thread;});
    private final Map<Integer,Job> ready = new LinkedHashMap<>();
    private final Set<Integer> attempted = new HashSet<>();
    private Job background, foreground;
    private int current=-1, selected=-1;
    private String quality="auto", lastError="";
    private boolean enabled, waitingConfirmation, closed;

    DlnaPrefetch(int count, Loader loader) {
        if(count<1||count>1000||loader==null)throw new IllegalArgumentException("投屏预取队列无效");
        this.count=count;this.loader=loader;
    }

    /** Pause scheduling before the prior foreground waiter is cancelled. Keep a matching transfer alive. */
    synchronized void selecting(int position) {
        if(closed)return;
        selected=position;waitingConfirmation=true;
        if(background!=null&&background.position!=position){attempted.remove(background.position);cancel(background);background=null;}
        if(foreground!=null&&foreground.position!=position){cancel(foreground);foreground=null;}
        if(background!=null&&background.position==position){foreground=background;background=null;}
        discardOutside(position);
    }

    DlnaSession.Prepared prepare(int position,String requested,BooleanSupplier alive)throws Exception {
        String wanted=MediaQuality.normalize(requested);
        Job job;
        synchronized(this) {
            requireAlive(alive);
            if(selected!=position)throw cancelled();
            job=ready.remove(position);
            if(job!=null&&!matches(job,wanted)){cancel(job);job=null;}
            if(job==null&&background!=null&&background.position==position) {
                job=background;background=null;
                if(!matches(job,wanted)){cancel(job);job=null;}
            }
            if(job==null&&foreground!=null&&foreground.position==position&&!foreground.cancelled&&matches(foreground,wanted))job=foreground;
            if(job==null) {
                if(foreground!=null)cancel(foreground);
                job=new Job(position,wanted,true);foreground=job;submit(job);
            } else foreground=job; // Transfer background ownership, not its interrupt flag, to this waiter.
            boolean retriedBackgroundFailure=false;
            while(true) {
                while(!job.finished&&!job.cancelled) {
                    requireAlive(alive);
                    try {wait(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw cancelled();}
                }
                requireAlive(alive);
                if(job.cancelled)throw cancelled();
                // A live foreground waiter may take over before a background failure arrives.
                // Give that request one genuine foreground attempt, without inheriting the
                // background's cancellation/error or ever retrying its own foreground failure.
                if(job.failure!=null&&!job.foreground&&!retriedBackgroundFailure) {
                    retriedBackgroundFailure=true;
                    job=new Job(position,wanted,true);foreground=job;submit(job);continue;
                }
                if(foreground==job)foreground=null;
                if(job.failure!=null)throw job.failure;
                if(job.media==null)throw new IllegalStateException("投屏准备结果已被使用");
                DlnaSession.Prepared media=job.media;job.media=null;
                return media;
            }
        }
    }

    synchronized void confirmed(int position,String actual,boolean autoplay) {
        if(closed)return;
        current=selected=position;waitingConfirmation=false;
        String nextQuality=MediaQuality.normalize(actual);
        boolean changedQuality=!quality.equals(nextQuality);quality=nextQuality;enabled=autoplay;
        if(background!=null&&(background.position<=current||background.position>current+AHEAD||changedQuality)){cancel(background);background=null;}
        if(foreground!=null){cancel(foreground);foreground=null;}
        discardOutside(current);
        if(changedQuality) {
            for(Job job:new ArrayList<>(ready.values()))if(!matches(job,quality)){ready.remove(job.position);cancel(job);}
            attempted.clear();
        }
        if(!enabled){clearBackground();return;}
        scheduleNext();
    }

    synchronized void autoplay(boolean value) {
        if(closed)return;
        enabled=value;
        if(!value)clearBackground();else scheduleNext();
    }

    private boolean matches(Job job,String wanted) {
        return job.quality.equals(wanted)||(job.media!=null&&job.media.quality.equals(wanted));
    }
    private void submit(Job job) {
        workers.purge();
        try {job.future=workers.submit(()->run(job));}
        catch(RejectedExecutionException rejected){job.failure=new IllegalStateException("投屏准备队列繁忙");job.finished=true;notifyAll();}
    }
    private void run(Job job) {
        DlnaSession.Prepared media=null;Exception error=null;
        try {media=loader.prepare(job.position,job.quality,job.foreground,()->!job.cancelled&&!Thread.currentThread().isInterrupted());}
        catch(Exception failure){error=failure;}
        synchronized(this) {
            job.finished=true;job.failure=error;
            if(closed||job.cancelled)release(media);
            else job.media=media;
            if(background==job) {
                background=null;
                if(!closed&&!job.cancelled&&enabled&&!waitingConfirmation&&job.position>current&&job.position<=current+AHEAD) {
                    if(error==null&&media!=null)ready.put(job.position,job);
                    else lastError="第 "+(job.position+1)+" 集："+clean(error);
                    scheduleNext();
                } else if(foreground!=job) {release(job.media);job.media=null;}
            }
            notifyAll();
        }
    }
    private void scheduleNext() {
        if(closed||!enabled||waitingConfirmation||current<0||background!=null)return;
        for(int position=current+1;position<count&&position<=current+AHEAD;position++) {
            if(ready.containsKey(position)||attempted.contains(position))continue;
            attempted.add(position);
            Job job=new Job(position,quality,false);background=job;submit(job);
            if(job.finished) {background=null;lastError="第 "+(position+1)+" 集："+clean(job.failure);continue;}
            return;
        }
    }
    private void discardOutside(int anchor) {
        for(Job job:new ArrayList<>(ready.values()))if(job.position<anchor||job.position>anchor+AHEAD){ready.remove(job.position);cancel(job);}
        attempted.removeIf(position->position<=anchor||position>anchor+AHEAD);
    }
    private void clearBackground() {
        if(background!=null){cancel(background);background=null;}
        for(Job job:ready.values())cancel(job);ready.clear();attempted.clear();lastError="";
    }
    private void cancel(Job job) {
        job.cancelled=true;
        if(job.future!=null){job.future.cancel(true);if(job.future instanceof Runnable)workers.remove((Runnable)job.future);}
        release(job.media);job.media=null;notifyAll();
    }
    private void requireAlive(BooleanSupplier alive)throws InterruptedIOException {
        if(closed||Thread.currentThread().isInterrupted()||!alive.getAsBoolean())throw cancelled();
    }
    private static InterruptedIOException cancelled(){return new InterruptedIOException("投屏准备已取消");}
    private static void release(DlnaSession.Prepared media){if(media!=null)media.releaseOwner();}
    private static String clean(Exception error) {
        if(error==null||error.getMessage()==null)return "准备未完成";
        String text=error.getMessage().replaceAll("https?://[^\\s\"'<>]+","[地址]").replaceAll("[\\p{Cntrl}]"," ");
        return text.substring(0,Math.min(120,text.length()));
    }
    synchronized int preparedCount(){int result=0;for(int position:ready.keySet())if(position>current&&position<=current+AHEAD)result++;return result;}
    synchronized String diagnostics() {
        return "投屏预取：后 "+AHEAD+" 集；已准备="+preparedCount()+"；当前预取="+(background==null?"无":"第 "+(background.position+1)+" 集")
            +"；状态="+(closed?"已关闭":!enabled?"连播关闭":waitingConfirmation?"等待本集确认":"开启")+"；最近失败="+(lastError.isEmpty()?"无":lastError);
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;enabled=false;clearBackground();
        if(foreground!=null){cancel(foreground);foreground=null;}
        workers.shutdownNow();notifyAll();
    }
}
