package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real production body consumer with offline headers and bytes; never calls a network. */
public final class MediaResponseTest {
    static int checks;
    static void check(boolean value,String label) { checks++;if(!value)throw new AssertionError(label); }
    static byte[] body="<html>temporary CDN error</html>".getBytes(StandardCharsets.UTF_8);
    static long consume(InputStream input,OutputStream output,Map<String,String> headers,long max,long deadline) throws Exception {
        java.lang.reflect.Method method;
        try {method=NetworkGuard.class.getDeclaredMethod("streamMediaBody",InputStream.class,OutputStream.class,Map.class,long.class,long.class);}
        catch(NoSuchMethodException baseline) {method=NetworkGuard.class.getDeclaredMethod("streamBody",InputStream.class,OutputStream.class,Map.class,long.class,long.class);}
        try {return (Long)method.invoke(null,input,output,headers,max,deadline);}
        catch(java.lang.reflect.InvocationTargetException failure) {throw (Exception)failure.getCause();}
    }
    static Map<String,String> headers(String type) {
        Map<String,String> result=new HashMap<>();result.put("content-length",Integer.toString(body.length));
        if(type!=null)result.put("content-type",type);return result;
    }
    public static void main(String[] args) throws Exception {
        for(String type:new String[]{"text/html","Text/HTML; charset=UTF-8","application/json","application/problem+json; charset=utf-8","application/xhtml+xml","text/plain","application/xml","text/xml"}) {
            ByteArrayOutputStream output=new ByteArrayOutputStream();IOException failure=null;
            try {consume(new ByteArrayInputStream(body),output,headers(type),1024,System.nanoTime()+1_000_000_000L);}catch(IOException error){failure=error;}
            check(failure!=null,"HTTP200 non-media must be rejected: "+type);
            check(failure.getClass().getSimpleName().equals("MediaResponseException"),"typed retryable media response, not a fake decode failure");
            check(output.size()==0,"error page never published or copied into output");
            check(!failure.getMessage().contains("<html>"),"response body not leaked to error messages");
        }
        body=new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,0,'i','s','o','m','m','p','4','2'};
        for(String type:new String[]{null,"video/mp4","Video/MP4; codecs=avc1","application/octet-stream","binary/octet-stream","audio/mp4"}) {
            ByteArrayOutputStream output=new ByteArrayOutputStream();long count=consume(new ByteArrayInputStream(body),output,headers(type),1024,System.nanoTime()+1_000_000_000L);
            check(count==body.length&&Arrays.equals(body,output.toByteArray()),"binary media contract stays byte exact: "+type);
        }
        Map<String,String> truncated=headers("video/mp4");truncated.put("content-length",Integer.toString(body.length+1));
        boolean eof=false;try {consume(new ByteArrayInputStream(body),new ByteArrayOutputStream(),truncated,1024,System.nanoTime()+1_000_000_000L);}catch(EOFException expected){eof=true;}
        check(eof,"truncated media keeps explicit EOF type for bounded source refresh");
        body="{\"code\":0}".getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream json=new ByteArrayOutputStream();
        check(NetworkGuard.streamBody(new ByteArrayInputStream(body),json,headers("application/json"),1024,System.nanoTime()+1_000_000_000L)==body.length&&Arrays.equals(body,json.toByteArray()),"AppTransport generic JSON consumer is NOT subjected to media-only MIME rules");
        System.out.println("PASS MediaResponseTest checks="+checks+" productionBodyConsumer=true errorPageBytesPublished=0 networkRequests=0");
    }
}
