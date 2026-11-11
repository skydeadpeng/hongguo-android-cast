package io.github.jackotom.hongguo.android;
import java.io.*;import java.net.*;import org.json.*;
/** Calls the real JNI AppTransport request with a pre-socket DNS double. */
public final class AppTransportLookupTest {
 static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 public static void main(String[]args)throws Exception{
  String url="https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/?fixture=synthetic";
  Exception[]failures={new UnknownHostException("synthetic DNS"),new SocketTimeoutException("synthetic timeout"),new ConnectException("synthetic refused"),new SocketException("synthetic reset"),new IOException("outer",new UnknownHostException("wrapped synthetic DNS"))};
  String[]codes={"ENOTFOUND","ETIMEDOUT","ECONNREFUSED","ECONNRESET","ENOTFOUND"};
  for(int i=0;i<failures.length;i++){NetworkGuard.failure=failures[i];NetworkGuard.lookups=0;JSONObject result=AppTransport.request(new JSONObject().put("url",url).put("method","POST").put("body","{\"fixture\":true}"));check(codes[i].equals(result.optString("errorCode"))&&result.getInt("status")==0,"typed JNI classification preserves actual DNS/connect cause");check(NetworkGuard.lookups==1,"transport classifies but never repeats signed POST itself");check(result.getString("body").isEmpty()&&result.getJSONObject("headers").length()==0,"transport errors never masquerade as a server model");}
  for(Exception failure:new Exception[]{new IOException("synthetic invalid HTTP"),new InterruptedIOException("synthetic cancel"),new javax.net.ssl.SSLHandshakeException("synthetic TLS")}){NetworkGuard.failure=failure;NetworkGuard.lookups=0;boolean rejected=false;try{AppTransport.request(new JSONObject().put("url",url));}catch(Exception expected){rejected=true;}check(rejected&&NetworkGuard.lookups==1,"format/cancellation/TLS failures are not labelled for a source retry");}
  NetworkGuard.failure=new UnknownHostException("synthetic interrupted DNS");NetworkGuard.lookups=0;Thread.currentThread().interrupt();boolean rejected=false;try{AppTransport.request(new JSONObject().put("url",url));}catch(Exception expected){rejected=true;}finally{Thread.interrupted();}check(rejected&&NetworkGuard.lookups==0,"thread cancellation cannot become a retryable lookup result or start DNS");
  System.out.println("PASS AppTransportLookupTest checks="+checks+" networkRequests=0 productionTransport=true dns=stubbed socketAttempts=0");
 }
}
