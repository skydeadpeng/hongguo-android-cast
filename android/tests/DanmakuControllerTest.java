package io.github.jackotom.hongguo.android;
import android.os.Handler;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs the production controller against an offline source which deliberately returns after cancellation. */
public final class DanmakuControllerTest {
    static int checks;static void check(boolean x,String m){checks++;if(!x)throw new AssertionError(m);}
    static void waitFor(java.util.function.BooleanSupplier condition,Handler main)throws Exception{long until=System.nanoTime()+3_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<until){main.drain();Thread.sleep(5);}main.drain();check(condition.getAsBoolean(),"test source completion timeout");}
    static DanmakuClient.Result result(String text,long start){return new DanmakuClient.Result(Arrays.asList(new DanmakuClient.Item(text,text,start,-1)),"",false);}
    public static void main(String[]args)throws Exception{try{run();}catch(Throwable error){error.printStackTrace();System.exit(1);}}
    private static void run()throws Exception{
        Handler main=new Handler();DanmakuOverlay view=new DanmakuOverlay();List<String> notices=new ArrayList<>();CountDownLatch firstStarted=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        DanmakuController owner=new DanmakuController(null,main,view,notices::add,(sid,vid,start,duration)->{int n=calls.incrementAndGet();if(n==1){firstStarted.countDown();while(releaseFirst.getCount()>0)try{releaseFirst.await();}catch(InterruptedException ignored){/* simulate late response that cancellation could not stop */}}return result(vid,start);});
        owner.update("1","11",1,0,60000,true,false);Thread.sleep(20);check(calls.get()==0&&!view.enabled,"default disabled makes zero requests");
        owner.update("1","11",1,0,60000,true,true);check(firstStarted.await(2,TimeUnit.SECONDS),"first window started");
        owner.update("1","22",2,0,60000,true,true);releaseFirst.countDown();waitFor(()->!view.rows.isEmpty()&&view.rows.get(0).text.equals("22"),main);check(view.rows.stream().noneMatch(i->i.text.equals("11")),"late old VID never reaches current overlay");check(calls.get()==2,"one current owner per window");
        owner.update("1","22",2,1000,60000,true,true);Thread.sleep(20);main.drain();check(calls.get()==2,"near playhead cache avoids repeated request");
        owner.update("1","22",2,1000,60000,false,true);check(!view.enabled&&view.rows.isEmpty(),"cast/background stop display and requests");
        owner.update("1","22",2,1000,60000,true,true);main.drain();check(view.enabled&&calls.get()==2,"foreground cache reuse");
        owner.update("1","22",2,40000,60000,true,true);waitFor(()->calls.get()==3,main);waitFor(()->view.rows.stream().anyMatch(i->i.timeMs==30000),main);check(view.seeks>0,"seek resets timeline");
        for(int i=2;i<=10;i++){final long offset=i*30000L;final int expected=i+2;owner.update("1","22",2,offset,900000,true,true);waitFor(()->calls.get()==expected,main);waitFor(()->view.rows.stream().anyMatch(item->item.timeMs==offset),main);}
        int beforeEviction=calls.get();owner.update("1","22",2,0,900000,true,true);waitFor(()->calls.get()==beforeEviction+1,main);check(calls.get()==beforeEviction+1,"ninth cached window evicts oldest from bounded cache");
        owner.suspend();check(!view.enabled&&view.rows.isEmpty(),"suspend clears layer");owner.close();owner.close();owner.update("1","33",3,0,60000,true,true);check(calls.get()==beforeEviction+1&&!view.enabled,"destroy is terminal and idempotent");
        Handler failures=new Handler();DanmakuOverlay failedView=new DanmakuOverlay();AtomicInteger attempts=new AtomicInteger();List<String> errors=new ArrayList<>();DanmakuController failed=new DanmakuController(null,failures,failedView,errors::add,(sid,vid,start,duration)->{attempts.incrementAndGet();throw new java.io.IOException("upstream");});
        failed.update("1","11",1,0,60000,true,true);waitFor(()->errors.stream().anyMatch(i->i.contains("未载入")),failures);failed.update("1","11",1,100,60000,true,true);Thread.sleep(30);failures.drain();check(attempts.get()==1,"failure has cooldown not 500ms retry loop");check(errors.get(errors.size()-1).contains("播放不受影响"),"failure not presented as no comments");failed.update("1","11",1,100,60000,true,false);failed.update("1","11",1,100,60000,true,true);waitFor(()->attempts.get()==2,failures);failed.close();
        System.out.println("PASS DanmakuControllerTest checks="+checks+" lateCallbackRejected=true cacheBounded=true lifecycle=true cooldown=true networkRequests=0 deviceOperations=0");
    }
}
