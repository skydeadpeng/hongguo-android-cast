package io.github.jackotom.hongguo.android;
import java.io.*;import java.util.*;
/** Exercises production quality stages; transport work is supplied as deterministic boundaries. */
public final class AdaptiveRecoveryQualityTest {
 static int checks;static void check(boolean v,String label){checks++;if(!v)throw new AssertionError(label);}
 static int ceiling(String quality)throws Exception{return (int)AdaptiveQuality.class.getDeclaredMethod("ceiling",String.class).invoke(null,quality);}
 static String next(String quality,int pixels)throws Exception{return (String)AdaptiveQuality.class.getDeclaredMethod("next",String.class,int.class).invoke(null,quality,pixels);}
 public static void main(String[]args)throws Exception{
  check("720p".equals(AdaptiveQuality.next("auto")),"default auto must attempt a bounded lower-resolution stage after failure");
  check("480p".equals(AdaptiveQuality.next("720p"))&&"360p".equals(AdaptiveQuality.next("480p"))&&AdaptiveQuality.next("360p")==null,"no fallback to highest auto or cycle");
  check("720p".equals(AdaptiveQuality.next("1080p")),"1080 falls to720");
  check(ceiling("720p")==1079&&ceiling("480p")==719&&ceiling("360p")==479&&ceiling("auto")==0&&ceiling("1080p")==0,"real short-edge upper boundaries");
  check("720p".equals(next("auto",1080))&&"480p".equals(next("auto",720))&&"360p".equals(next("auto",480))&&next("auto",360)==null,"observed video never rises in resolution");
  check("480p".equals(next("1080p",540))&&"720p".equals(next("auto",0)),"skip unusably high nominal grade; unknown retains bounded attempt");
  List<String> calls=new ArrayList<>(),progress=new ArrayList<>();
  AdaptiveQuality.Result<String> result=AdaptiveQuality.prepare("auto",q->{calls.add(q);if(!q.equals("360p"))throw new IOException("fixture transfer exhausted");return "360-local";},e->true,()->true,(q,n,e)->progress.add(q+":"+n),v->{});
  check(calls.equals(Arrays.asList("auto","720p","480p","360p"))&&result.stages==4&&result.quality.equals("360p"),"auto eventually accepts actual low grade in four bounded stages");
  check(progress.equals(Arrays.asList("auto:1","720p:2","480p:3","360p:4")),"progress exposes each real grade");
  for(String initial:new String[]{"1080p","auto","720p","480p","360p"}){
   calls.clear();try{AdaptiveQuality.prepare(initial,q->{calls.add(q);throw new IOException("unavailable");},e->true,()->true,(q,n,e)->{},v->{});throw new AssertionError("expected exhausted");}catch(IOException expected){}
   check(calls.size()==(initial.equals("auto")||initial.equals("1080p")?4:initial.equals("720p")?3:initial.equals("480p")?2:1),"finite source absence "+initial);
   check(new HashSet<>(calls).size()==calls.size(),"no duplicate/upshift stages "+initial);
  }
  for(Exception error:new Exception[]{new javax.net.ssl.SSLHandshakeException("certificate"),new IllegalArgumentException("metadata"),new InterruptedIOException("cancel")}){
   calls.clear();try{AdaptiveQuality.prepare("auto",q->{calls.add(q);throw error;},e->false,()->true,(q,n,e)->{},v->{});throw new AssertionError();}catch(Exception actual){check(actual==error&&calls.size()==1,"fatal error stops "+error.getClass().getSimpleName());}
  }
  calls.clear();try{AdaptiveQuality.prepare("auto",q->{calls.add(q);return q;},e->true,()->false,(q,n,e)->{},v->{});throw new AssertionError();}catch(InterruptedIOException expected){check(calls.isEmpty(),"canceled before source work");}
  boolean[]alive={true};List<String> discarded=new ArrayList<>();try{AdaptiveQuality.prepare("480p",q->{alive[0]=false;return q;},e->true,()->alive[0],(q,n,e)->{},discarded::add);throw new AssertionError();}catch(InterruptedIOException expected){check(discarded.equals(Collections.singletonList("480p")),"late candidate released");}
  System.out.println("ADAPTIVE_RECOVERY_QUALITY_OK checks="+checks+" maxStages=4 autoToLow=true monotonicCeilings=true networkRequests=0");
 }
}
