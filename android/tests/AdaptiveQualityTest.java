package io.github.jackotom.hongguo.android;
import java.io.*;import java.net.*;import java.util.*;
/** Actual production coordinator with deterministic complete-stage boundaries. */
public final class AdaptiveQualityTest {
 static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static boolean allowed(Exception e){return e instanceof IOException&&!(e instanceof javax.net.ssl.SSLException)&&!(e instanceof InterruptedIOException&&!(e instanceof SocketTimeoutException));}
 static AdaptiveQuality.Result<String> run(String q,Set<String> fail,List<String> calls)throws Exception{return AdaptiveQuality.prepare(q,s->{calls.add(s);if(fail.contains(s))throw new IOException("exhausted stage");return s+"-file";},AdaptiveQualityTest::allowed,()->true,(s,n,e)->{},v->{});}
 public static void main(String[]args)throws Exception{
  List<String>calls=new ArrayList<>();AdaptiveQuality.Result<String> r=run("1080p",new HashSet<>(Arrays.asList("1080p")),calls);check(calls.equals(Arrays.asList("1080p","720p"))&&r.quality.equals("720p")&&r.stages==2,"1080 exhausted then exact 720 success");
  calls.clear();r=run("1080p",new HashSet<>(Arrays.asList("1080p","720p")),calls);check(calls.equals(Arrays.asList("1080p","720p","480p"))&&r.quality.equals("480p")&&r.stages==3,"720 failure proceeds to 480 instead of highest auto");
  calls.clear();r=run("720p",Collections.singleton("720p"),calls);check(calls.equals(Arrays.asList("720p","480p"))&&r.stages==2,"720 starts no high-quality retry");
  calls.clear();r=run("auto",Collections.emptySet(),calls);check(calls.equals(Collections.singletonList("auto"))&&r.stages==1,"auto succeeds one stage");
  for(String start:new String[]{"1080p","720p","auto","480p","360p"}){calls.clear();boolean failed=false;try{run(start,new HashSet<>(Arrays.asList("1080p","720p","480p","360p","auto")),calls);}catch(IOException expected){failed=true;}check(failed&&calls.size()==(start.equals("1080p")||start.equals("auto")?4:start.equals("720p")?3:start.equals("480p")?2:1),"all unavailable terminates bounded "+start);}
  for(Exception error:new Exception[]{new javax.net.ssl.SSLHandshakeException("certificate"),new InterruptedIOException("cancel"),new IllegalArgumentException("metadata")}){
   calls.clear();boolean caught=false;try{AdaptiveQuality.prepare("1080p",q->{calls.add(q);throw error;},AdaptiveQualityTest::allowed,()->true,(q,n,e)->{},v->{});}catch(Exception e){caught=e==error;}check(caught&&calls.size()==1,"fatal classification never tries lower "+error.getClass().getSimpleName());
  }
  boolean[]alive={false};calls.clear();try{AdaptiveQuality.prepare("1080p",q->{calls.add(q);return q;},e->true,()->alive[0],(q,n,e)->{},v->{});throw new AssertionError("not cancelled");}catch(InterruptedIOException expected){check(calls.isEmpty(),"cancelled before stage performs no work");}
  alive[0]=true;calls.clear();List<String>discard=new ArrayList<>();try{AdaptiveQuality.prepare("1080p",q->{calls.add(q);alive[0]=false;return "ready";},e->true,()->alive[0],(q,n,e)->{},discard::add);throw new AssertionError("not cancelled");}catch(InterruptedIOException expected){check(discard.equals(Collections.singletonList("ready"))&&calls.size()==1,"late completed stale value released, never published or lowered");}
  alive[0]=true;calls.clear();try{AdaptiveQuality.prepare("1080p",q->{calls.add(q);alive[0]=false;throw new IOException("403");},e->true,()->alive[0],(q,n,e)->{},v->{});throw new AssertionError("not cancelled");}catch(InterruptedIOException expected){check(calls.size()==1,"cancel after exhausted stage prevents next lower network work");}
  calls.clear();try{AdaptiveQuality.prepare("1080p",q->{calls.add(q);return q;},e->true,()->true,(q,n,e)->Thread.currentThread().interrupt(),v->{});throw new AssertionError("not cancelled");}catch(InterruptedIOException expected){check(calls.isEmpty(),"interrupt during progress checked before transfer");}finally{Thread.interrupted();}
  check(AdaptiveQuality.next("auto").equals("720p")&&AdaptiveQuality.next("720p").equals("480p")&&AdaptiveQuality.next("360p")==null&&AdaptiveQuality.next("1080p").equals("720p"),"no upshift/cycle");
  System.out.println("PASS AdaptiveQualityTest checks="+checks+" actualCoordinator=true stagesMax=4 transportBoundary=doubles cancellation=true networkRequests=0");
 }
}
