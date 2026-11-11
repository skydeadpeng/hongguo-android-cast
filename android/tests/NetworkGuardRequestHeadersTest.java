package io.github.jackotom.hongguo.android;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Tests the production request writer over a real loopback TCP connection, not a CDN. */
public final class NetworkGuardRequestHeadersTest {
    static int passed,failed;
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    static String exchange(String referer)throws Exception {
        Method writer=NetworkGuard.class.getDeclaredMethod("writeMediaRequest",OutputStream.class,URI.class,String.class,String.class);
        URI uri=new URI("https://cdn.qznovelvod.com/synthetic%2Fepisode.mp4?sig=a%2Fb+c%2Bd&token=x%26y");
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> received=new CompletableFuture<>();
            Thread server=new Thread(()->{try(Socket peer=listener.accept()) {
                peer.setSoTimeout(3000);ByteArrayOutputStream bytes=new ByteArrayOutputStream();int next;
                while((next=peer.getInputStream().read())>=0){bytes.write(next);if(bytes.toString("US-ASCII").endsWith("\r\n\r\n"))break;if(bytes.size()>16384)throw new IOException("fixture overlong request");}
                received.complete(bytes.toString("US-ASCII"));
            }catch(Exception error){received.completeExceptionally(error);}},"media-header-loopback");server.setDaemon(true);server.start();
            try(Socket client=new Socket(listener.getInetAddress(),listener.getLocalPort())){
                writer.invoke(null,client.getOutputStream(),uri,"fixture original agent",referer);
                return received.get(4,TimeUnit.SECONDS);
            }finally{listener.close();server.join(1000);}
        }
    }
    static void group(String name,String ref,boolean expected){try{String raw=exchange(ref);check(raw.startsWith("GET /synthetic%2Fepisode.mp4?sig=a%2Fb+c%2Bd&token=x%26y HTTP/1.1\r\n"),"raw URL preserved");check(raw.contains("\r\nHost: cdn.qznovelvod.com\r\n")&&raw.contains("\r\nUser-Agent: fixture original agent\r\n"),"host/agent preserved");check(raw.contains("\r\nAccept-Encoding: identity\r\n")&&!raw.contains("\r\nRange:"),"full uncompressed transfer preserved");check(raw.contains("\r\nReferer:")==expected,"Referer header presence");if(expected)check(raw.contains("\r\nReferer: "+ref+"\r\n"),"exact ref value");passed++;System.out.println("PASS WIRE "+name+" referer="+(expected?ref:"ABSENT")+" method=GET range=ABSENT signedQuery=UNCHANGED");}catch(Throwable error){failed++;System.out.println("FAIL WIRE "+name+" "+error.getClass().getSimpleName()+": "+error.getMessage());}}
    public static void main(String[] args)throws Exception {
        group("app-empty", "",false);group("app-compat","https://novelquickapp.com/",true);group("web-unchanged",NetworkGuard.REFERER,true);
        System.out.println("NETWORK_MEDIA_HEADERS "+(failed==0?"PASS":"FAIL")+" groups="+passed+" failed="+failed+" transport=loopback_tcp externalRequests=0");if(failed>0)System.exit(1);
    }
}
