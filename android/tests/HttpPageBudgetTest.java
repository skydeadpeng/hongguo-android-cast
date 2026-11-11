package io.github.jackotom.hongguo.android;
import java.io.*;import java.net.*;import java.util.concurrent.*;
/** Actual URL/deadline guards, with no DNS or network requests. */
public final class HttpPageBudgetTest {
 static int checks;interface Work {void run()throws Exception;}
 static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 static void reject(Work w,String label)throws Exception {try{w.run();throw new AssertionError(label);}catch(IOException|URISyntaxException expected){checks++;}}
 public static void main(String[]args)throws Exception {
  for(String u:new String[]{"https://hongguoduanju.com/detail?series_id=123","https://www.hongguoduanju.com/detail?series_id=123","https://hongguoduanju.com/api/search/result?aid=8662&query=%E6%B5%8B%E8%AF%95&page=1","https://hongguoduanju.com/api/search/result?page=50&query=a+b&aid=8662"})check(NetworkGuard.validate(u,true)!=null,"exact metadata URL accepted");
  for(String p:new String[]{"/detail","/detail?series_id=","/detail?series_id=123&extra=1","/detail?series_id=123&series_id=456","/detail?series_id=1234567890123456789012345678901","/detail?series_id=1x","/detail/?series_id=123","/%64etail?series_id=123","/api/search/other?aid=8662&query=a&page=1","/api/search/result?aid=1967&query=a&page=1","/api/search/result?aid=8662&query=&page=1","/api/search/result?aid=8662&query=%0A&page=1","/api/search/result?aid=8662&query=a&page=0","/api/search/result?aid=8662&query=a&page=51","/api/search/result?aid=8662&query=a&page=1.5","/api/search/result?aid=8662&query=a&page=01","/api/search/result?aid=8662&query=a&page=1&page=2","/api/search/result?aid=8662&query=a&page=1&url=https%3A%2F%2F127.0.0.1","/api/search/result?aid=8662&query=a&page=1&","/api/search/result?aid=8662&query=%zz&page=1"})reject(()->NetworkGuard.validate("https://hongguoduanju.com"+p,true),"reject metadata variant "+p);
  for(String u:new String[]{"http://hongguoduanju.com/detail?series_id=123","https://127.0.0.1/detail?series_id=123","https://u:p@hongguoduanju.com/detail?series_id=123","https://hongguoduanju.com:444/detail?series_id=123","https://hongguoduanju.com.evil.test/detail?series_id=123","https://www.hongguoduanju.com/api/search/result?aid=8662&query=a&page=1","https://hongguoduanju.com/api/search/result?aid=8662&query=a&page=1#x"})reject(()->NetworkGuard.validate(u,true),"origin/transport");
  for(long timeout:new long[]{0,-1,25001,Long.MAX_VALUE})reject(()->NetworkGuard.pageDeadline(timeout,0),"invalid timeout");
  reject(()->NetworkGuard.pageDeadline(25,-1),"negative absolute deadline");reject(()->NetworkGuard.pageDeadline(25,Long.MAX_VALUE),"unsafe absolute deadline");reject(()->NetworkGuard.pageDeadline(25,System.currentTimeMillis()-1),"expired before queue");
  long start=System.nanoTime(),deadline=NetworkGuard.pageDeadline(1234,0);check(deadline-start<=TimeUnit.MILLISECONDS.toNanos(1250)&&deadline-start>=TimeUnit.MILLISECONDS.toNanos(1200),"relative timeout");
  start=System.nanoTime();deadline=NetworkGuard.pageDeadline(25000,System.currentTimeMillis()+75);check(deadline-start<=TimeUnit.MILLISECONDS.toNanos(90),"caller expiry reduces total budget");
  long expired=System.nanoTime()-1;reject(()->NetworkGuard.get("https://hongguoduanju.com/player/123",expired),"queue-expired get rejects before DNS");
  Thread.currentThread().interrupt();try{reject(()->NetworkGuard.get("https://hongguoduanju.com/player/123",System.nanoTime()+1_000_000_000L),"pre-cancelled get rejects before DNS");check(Thread.currentThread().isInterrupted(),"interruption retained");}finally{Thread.interrupted();}
  System.out.println("HTTP_PAGE_BUDGET_OK checks="+checks+" actualGuard=true networkRequests=0");
 }
}
