package io.github.jackotom.hongguo.android;
import java.util.*;import java.util.concurrent.*;import org.json.*;
public final class OfficialSeriesBridgeTest {
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check="+checks);}
 static void waitFor(java.util.function.BooleanSupplier condition)throws Exception{long until=System.nanoTime()+2_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(5);check(condition.getAsBoolean());}
 static String load(String id)throws Exception{return new JSONObject().put("id",id).put("method","load").put("seriesId","7390000000000000001").toString();}
 static String cancel(String id)throws Exception{return new JSONObject().put("id","cancel-"+id).put("method","cancel").put("requestId",id).toString();}
 public static void main(String[]args)throws Exception{
  MainActivity host=new MainActivity();OfficialSeriesBridge bridge=new OfficialSeriesBridge(host);
  try{
   bridge.postMessage(load("first"));host.first();waitFor(()->host.size()>0);bridge.postMessage(cancel("first"));host.last();host.all();check(host.scripts.isEmpty());
   bridge.postMessage(load("normal"));host.first();waitFor(()->host.size()>0);host.all();check(host.scripts.size()==1&&host.scripts.get(0).contains("normal"));
   bridge.postMessage(load("background"));host.first();waitFor(()->host.size()>0);bridge.suspend();host.all();check(host.scripts.size()==1);
   bridge.postMessage(load("paused"));host.all();check(host.scripts.size()==2&&host.scripts.get(1).contains("ERR_CANCELED"));
   bridge.resume();bridge.postMessage(load("resumed"));host.first();waitFor(()->host.size()>0);host.all();check(host.scripts.size()==3&&host.scripts.get(2).contains("resumed"));
   host.trusted=false;int calls=OfficialSeriesService.calls;bridge.postMessage(load("untrusted"));host.all();check(OfficialSeriesService.calls==calls);host.trusted=true;
   OfficialSeriesService.block=true;bridge.postMessage(load("blocked"));host.first();waitFor(()->OfficialSeriesService.entered.getCount()==0);bridge.postMessage(cancel("blocked"));host.first();waitFor(()->OfficialSeriesService.closed>0);host.all();check(host.scripts.size()==3);
   OfficialSeriesService.block=false;bridge.postMessage(load("destroyed"));host.first();waitFor(()->host.size()>0);bridge.close();host.all();check(host.scripts.size()==3);
   System.out.println("OFFICIAL_SERIES_BRIDGE_OK checks="+checks+" lateReplies=suppressed cancel=closesClient background=blocked network=fixture");
  }finally{bridge.close();OfficialSeriesService.release.countDown();}
 }
}
class MainActivity {boolean trusted=true;final Deque<Runnable> ui=new ConcurrentLinkedDeque<>();final List<String> scripts=new ArrayList<>();void runOnUiThread(Runnable r){ui.add(r);}boolean trusted(){return trusted;}void script(String s){scripts.add(s);}int size(){return ui.size();}void first(){Runnable r=ui.pollFirst();if(r!=null)r.run();}void last(){Runnable r=ui.pollLast();if(r!=null)r.run();}void all(){while(!ui.isEmpty())first();}}
class OfficialApiClient {void close(){OfficialSeriesService.closed++;OfficialSeriesService.release.countDown();}}
class OfficialSeriesService {static volatile int calls,closed;static volatile boolean block;static final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);OfficialSeriesService(MainActivity h){}JSONObject load(String id,java.util.function.Consumer<OfficialApiClient> active)throws Exception{calls++;active.accept(new OfficialApiClient());if(block){entered.countDown();release.await();}return new JSONObject().put("items",new JSONArray());}}
