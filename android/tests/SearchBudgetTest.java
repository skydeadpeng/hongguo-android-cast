package io.github.jackotom.hongguo.android;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.io.*;import android.content.SharedPreferences;import org.json.*;
public final class SearchBudgetTest {
 static int checks;
 static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 interface Task{void run()throws Exception;}
 static void rejects(Task task,String label)throws Exception{boolean failed=false;try{task.run();}catch(Exception expected){failed=true;}check(failed,label);}
 static final class Store implements SharedPreferences {
  final Map<String,String> values=new HashMap<>();
  Store(boolean registered){if(registered){values.put("device_id","1234567890123456");values.put("install_id","2234567890123456");}}
  public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
  public Editor edit(){return new Editor(){public Editor putString(String key,String value){values.put(key,value);return this;}public boolean commit(){return true;}};}
 }
 static JSONObject params()throws Exception{return new JSONObject().put("keyword","预算测试").put("offset",0);}
 static JSONObject response()throws Exception{return new JSONObject().put("status",200).put("body","{\"code\":0,\"search_tabs\":[]}");}
 public static void main(String[]args)throws Exception{
  Store store=new Store(true);AtomicInteger calls=new AtomicInteger();
  SearchClient.Transport bounded=(url,method,headers,body,deadline)->{calls.incrementAndGet();long left=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());check(left>0&&left<=1000,"explicit timeoutMs bounds real transport");check(!url.contains("timeoutMs")&&!url.contains("deadlineMs"),"local budget never enters signed URL");return response();};
  SearchClient.search(store,params().put("timeoutMs",1000),bounded);check(calls.get()==1,"bounded search executed");
  SearchClient.search(store,params().put("timeoutMs",2000).put("deadlineMs",System.currentTimeMillis()+800),(url,m,h,body,d)->{check(TimeUnit.NANOSECONDS.toMillis(d-System.nanoTime())<=800,"absolute deadline wins over timeout");return response();});
  SearchClient.search(store,params(),(url,m,h,body,d)->{long left=TimeUnit.NANOSECONDS.toMillis(d-System.nanoTime());check(left>37000&&left<=38000,"legacy default 38 seconds unchanged");return response();});
  AtomicInteger rejectedCalls=new AtomicInteger();SearchClient.Transport forbidden=(u,m,h,body,d)->{rejectedCalls.incrementAndGet();return response();};
  for(Object value:new Object[]{0,-1,1.5,"1000",JSONObject.NULL,38001})rejects(()->SearchClient.search(store,params().put("timeoutMs",value),forbidden),"invalid timeout budget");
  for(Object value:new Object[]{0,-1,1.5,"1000",JSONObject.NULL,9_007_199_254_740_992L})rejects(()->SearchClient.search(store,params().put("deadlineMs",value),forbidden),"invalid absolute deadline");
  rejects(()->SearchClient.search(store,params().put("deadlineMs",System.currentTimeMillis()-1),forbidden),"queue-expired request rejected before transport");check(rejectedCalls.get()==0,"invalid and expired requests open no network");
  rejects(()->SearchClient.search(store,params().put("timeoutMs",10),(u,m,h,body,d)->{Thread.sleep(30);return response();}),"late transport result rejected after deadline");
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
  try {
   Future<?> owner=pool.submit(()->{try{SearchClient.search(store,params().put("timeoutMs",3000),(u,m,h,body,d)->{entered.countDown();release.await();return response();});}catch(Exception e){throw new RuntimeException(e);}});
   check(entered.await(2,TimeUnit.SECONDS),"registration/search lock held by fixture");long start=System.nanoTime();
   rejects(()->SearchClient.search(store,params().put("timeoutMs",30),forbidden),"lock waiter obeys total budget");
   check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500,"lock wait does not retain old 38 second timeout");release.countDown();owner.get(2,TimeUnit.SECONDS);
  }finally{release.countDown();pool.shutdownNow();}
  Thread.currentThread().interrupt();rejects(()->SearchClient.search(store,params().put("timeoutMs",1000),forbidden),"cancelled request rejected");check(Thread.interrupted(),"interruption flag retained");
  Store fresh=new Store(false);AtomicInteger registered=new AtomicInteger();
  SearchClient.search(fresh,params().put("timeoutMs",2000),(u,m,h,body,d)->{
   check(TimeUnit.NANOSECONDS.toMillis(d-System.nanoTime())<=2000,"registration and search share caller deadline");
   if(m.equals("POST")){registered.incrementAndGet();return new JSONObject().put("status",200).put("body","{\"device_id_str\":\"1234567890123456\",\"install_id_str\":\"2234567890123456\"}");}
   return response();
  });check(registered.get()==1,"existing lazy registration preserved");
  System.out.println("SEARCH_BUDGET_OK checks="+checks+" deadlines=shared lock=bounded legacy=38s signedParams=unchanged networkRequests=0");
 }
}
