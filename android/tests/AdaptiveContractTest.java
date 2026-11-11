package io.github.jackotom.hongguo.android;
import java.io.*;import java.net.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;import org.json.*;
public final class AdaptiveContractTest {
 static int checks;static Method classify;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static boolean eligible(Exception error)throws Exception{return (Boolean)classify.invoke(null,error);}
 static Exception quality(String msg)throws Exception{Class<?> type=Class.forName(PlaybackSources.class.getName()+"$QualityUnavailableException");Constructor<?> ctor=type.getDeclaredConstructor(String.class);ctor.setAccessible(true);return(Exception)ctor.newInstance(msg);}
 static JSONObject model()throws Exception{return new JSONObject().put("url","https://cdn.qznovelvod.com/fixture.mp4").put("seriesId","123").put("vid","456").put("source","app").put("requestedQuality","1080p").put("width",720).put("height",1280);}
 static Exception invalid(JSONObject info)throws Exception{try{PlaybackSources.register(info,"456",false);}catch(Exception e){return e;}throw new AssertionError("invalid plan accepted");}
 public static void main(String[] args)throws Exception{
  try{classify=PlaybackSources.class.getDeclaredMethod("canLowerQuality",Exception.class);classify.setAccessible(true);}catch(NoSuchMethodException e){System.out.println("ADAPTIVE_SOURCE_FAILED missing playback-only fallback classifier");System.exit(1);return;}
  for(Exception e:new Exception[]{new IOException("片源响应 HTTP 403"),new IOException("片源响应 HTTP 404"),new IOException("片源响应 HTTP 408"),new IOException("片源响应 HTTP 410"),new IOException("片源响应 HTTP 503"),new IOException("播放缓存下载超时"),new UnknownHostException("x"),new SocketException("x"),new SocketTimeoutException("x"),new EOFException("x"),new TimeoutException("x"),quality("尺寸不足")}){check(eligible(e),"known recoverable failure");check(eligible(new IOException("bounded source exhausted",e)),"wrapped recoverable failure");}
  for(Exception e:new Exception[]{new IOException("片源响应 HTTP 401"),new IOException("DNS 失败了"),new IOException("片源请求头无效"),new IOException("分集请求信息无效"),new IOException("fixture decryption failed"),new JSONException("invalid"),new IllegalArgumentException("invalid"),new SecurityException("invalid"),new URISyntaxException("x","invalid"),new CancellationException("cancel"),new InterruptedException("cancel"),new InterruptedIOException("cancel")})check(!eligible(e),"untyped/permanent/input/cancel must stop");
  Class<?> redirectType=Class.forName(NetworkGuard.class.getName()+"$MediaRedirectException");Constructor<?> redirectCtor=redirectType.getDeclaredConstructor();redirectCtor.setAccessible(true);Exception redirect=(Exception)redirectCtor.newInstance();
  check(eligible(redirect),"typed media redirect can recover");check(eligible(new IOException("source exhausted",redirect)),"wrapped typed media redirect can recover");check(!eligible(new IOException(redirect.getMessage())),"same message generic error remains terminal");check(!eligible(new IOException("仅接受标准 HTTPS 链接")),"initial URL validation text remains terminal");
  javax.net.ssl.SSLHandshakeException tls=new javax.net.ssl.SSLHandshakeException("cert");tls.initCause(new UnknownHostException("DNS"));check(!eligible(new IOException("wrapped",tls)),"TLS veto dominates recoverable cause");
  check(!eligible(new IOException("wrapped",new java.security.cert.CertificateException("cert",new SocketException("network")))),"certificate veto dominates socket cause");
  Thread.currentThread().interrupt();check(!eligible(new UnknownHostException("x")),"thread cancellation dominates DNS");Thread.interrupted();
  Exception low=invalid(model());check(eligible(low),"valid low dimensions produce typed quality missing");
  for(JSONObject row:new JSONObject[]{model().put("referer","https://evil.invalid/"),model().put("vid","999"),model().put("contentKey","bad"),model().put("url","https://evil.invalid/a"),model().put("width",-1),model().put("variants","bad")})check(!eligible(invalid(row)),"bad model never becomes a lower-quality search");
  JSONObject invalidVariant=new JSONObject(model().toString()).put("vid","999");check(!eligible(invalid(model().put("variants",new JSONArray().put(invalidVariant)))),"all metadata validated before low-quality rejection");
  MainActivity host=new MainActivity();PlaybackSources.attach(host);int[] calls={0};String[] code={"QUALITY_UNAVAILABLE"};host.callback=script->{try{calls[0]++;JSONArray a=new JSONArray("["+script.substring(script.lastIndexOf('(')+1,script.length()-1)+"]");PlaybackSources.result(new JSONObject().put("requestId",a.getString(0)).put("seriesId",a.getString(1)).put("vid",a.getString(2)).put("requestedQuality",a.getString(3)).put("error","fixture lookup missing").put("errorCode",code[0]));}catch(Exception e){throw new RuntimeException(e);}};
  try{PlaybackSources.resolve("123","456","1080p");throw new AssertionError("missing quality accepted");}catch(IOException e){check(eligible(e),"QUALITY_UNAVAILABLE survives real pending callback");}check(calls[0]==1,"missing quality not retried at same stage");
  code[0]="BUSINESS_ERROR";try{PlaybackSources.resolve("123","456","1080p");throw new AssertionError("business accepted");}catch(IOException e){check(!eligible(e),"unknown API error cannot fallback");}check(calls[0]==2,"business no retry");
  for(String bad:new String[]{"identity","quality","playInfo"}) {
   calls[0]=0;host.callback=script->{try{calls[0]++;JSONArray a=new JSONArray("["+script.substring(script.lastIndexOf('(')+1,script.length()-1)+"]");JSONObject reply=new JSONObject().put("requestId",a.getString(0)).put("seriesId",a.getString(1)).put("vid",a.getString(2)).put("requestedQuality",a.getString(3)).put("playInfo",model());
    if(bad.equals("identity"))reply.put("vid","999");else if(bad.equals("quality"))reply.put("requestedQuality","720p");else reply.put("playInfo","malformed");
    try{PlaybackSources.result(reply);}catch(Exception expected){} // NativeBridge rejects the callback separately.
   }catch(Exception e){throw new RuntimeException(e);}};
   long start=System.nanoTime();try{PlaybackSources.resolve("123","456","1080p");throw new AssertionError("bad callback accepted");}catch(IOException e){check(!eligible(e),"invalid callback is terminal, not timed-out network");}
   check(calls[0]==1&&System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1),"invalid callback completes original future immediately, without retry");
  }
  PlaybackSources.detach(host);
  System.out.println("ADAPTIVE_SOURCE_OK checks="+checks+" typedQualityCallback=true strictDownloads=true networkRequests=0");
 }
}
