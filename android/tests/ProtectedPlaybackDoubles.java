package io.github.jackotom.hongguo.android;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.function.Consumer;
/** Offline transports only. EpisodeResolver and PlaybackSources remain actual production classes. */
final class MainActivity {
    Consumer<String> callback;
    boolean isFinishing(){ return false; }
    boolean isDestroyed(){ return false; }
    void script(String value){ callback.accept(value); }
}
final class NetworkGuard {
    static final String UA="fixture-agent", REFERER="https://hongguoduanju.com/";
    static final class MediaResponseException extends IOException { }
    static final class MediaRedirectException extends IOException { }
    static final class Reply {
        final String body, url; final int status;
        Reply(String body,int status,String url){this.body=body;this.status=status;this.url=url;}
    }
    static final List<String> requests=new ArrayList<>();
    static final List<String> responses=new ArrayList<>();
    static int heads;
    static void reset(String... html){requests.clear();responses.clear();Collections.addAll(responses,html);heads=0;}
    static Reply get(String url) {
        requests.add(url);
        if(requests.size()>responses.size())throw new AssertionError("unexpected extra webpage GET");
        return new Reply(responses.get(requests.size()-1),200,url);
    }
    // The resolver passes an explicit budget; the double records the same single GET.
    static Reply get(String url,int timeoutMs,long deadlineMs) { return get(url); }
    static URI validate(String value,boolean official)throws Exception {
        URI uri=new URI(value);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getPort()!=-1&&uri.getPort()!=443)throw new IOException("fixture invalid HTTPS URL");
        return uri;
    }
    static String media(String url){heads++;return url;}
    static void download(String url,File destination,long max)throws Exception {throw new AssertionError("unexpected media request");}
    static void download(String url,File destination,long max,String ua,String ref)throws Exception {throw new AssertionError("unexpected media request");}
}
final class AppTransport {
    static boolean domain(String host,String... allowed){if(host==null)return false;for(String suffix:allowed)if(host.equals(suffix)||host.endsWith("."+suffix))return true;return false;}
}
final class CencDecryptor {
    static void decryptInPlace(File file,byte[] key){throw new AssertionError("unexpected media decrypt");}
}
