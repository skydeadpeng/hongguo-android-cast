package io.github.jackotom.hongguo.android;

import java.io.*;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runs unchanged on a desktop JVM or Android app_process. All cases parse local bytes only. */
public final class DlnaEndpointResolutionTest {
    private static final String HOST="192.168.1.20",SERVICE="urn:schemas-upnp-org:service:AVTransport:1";
    private static int checks,passed,failed;
    private interface Checked {void run()throws Exception;}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void group(String label,Checked test){try{test.run();passed++;System.out.println("PASS EndpointResolution "+label);}catch(Throwable error){failed++;System.out.println("FAIL EndpointResolution "+label+": "+error.getMessage());}}
    private static Object invoke(Class<?> owner,String method,Class<?>[] types,Object...args)throws Exception{
        Method declared=owner.getDeclaredMethod(method,types);declared.setAccessible(true);
        try{return declared.invoke(null,args);}catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw(Exception)cause;throw(Error)cause;}
    }
    private static String target(URI uri)throws Exception{return(String)invoke(DlnaNetwork.class,"requestTarget",new Class<?>[]{URI.class},uri);}
    private static URI base(URI uri)throws Exception{return(URI)invoke(DlnaDiscovery.class,"controlBase",new Class<?>[]{URI.class,String.class},uri,HOST);}
    private static String description(String rootBase,String control){return "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\">"+(rootBase==null?"":"<URLBase>"+rootBase+"</URLBase>")+"<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Fixture</friendlyName><UDN>uuid:fixture</UDN><serviceList><service><serviceType>"+SERVICE+"</serviceType><controlURL>"+control+"</controlURL></service></serviceList></device></root>";}
    private static DlnaDevice parse(String rootBase,String control,String location)throws Exception{return DlnaDiscovery.parseDescription(description(rootBase,control).getBytes(StandardCharsets.UTF_8),URI.create(location),HOST);}
    private static void rejects(Checked operation,String label)throws Exception{boolean failed=false;try{operation.run();}catch(IOException expected){failed=true;}check(failed,label);}
    public static void main(String[]args)throws Exception{
        group("authority-only description LOCATION keeps slash before relative AVTransport path",()->{
            DlnaDevice device=parse(null,"AVTransport/uuid-fixture/control","http://"+HOST+":16690");
            check(device.controlUri.toString().equals("http://"+HOST+":16690/AVTransport/uuid-fixture/control"),"authority must not concatenate port and AVTransport path");
            check(device.controlUri.getRawPath().equals("/AVTransport/uuid-fixture/control"),"Android URI raw path must be absolute");
        });
        group("explicit authority-only URLBase is normalized before platform resolve",()->{
            DlnaDevice device=parse("http://"+HOST+":16690","AVTransport/uuid-fixture/control","http://"+HOST+":16690/device.xml");
            check(device.controlUri.toString().equals("http://"+HOST+":16690/AVTransport/uuid-fixture/control"),"declared authority-only base resolves correctly");
        });
        group("canonical base always has an explicit path on every runtime",()->{
            check(base(URI.create("http://"+HOST+":16690")).toString().equals("http://"+HOST+":16690/"),"empty path canonicalized before URI.resolve");
            check(base(URI.create("http://"+HOST+":16690?base=a%2Fb")).toString().equals("http://"+HOST+":16690/?base=a%2Fb"),"encoded base query preserved without double escaping");
            check(base(URI.create("http://"+HOST+":16690/folder/")).toString().equals("http://"+HOST+":16690/folder/"),"nonempty base path unchanged");
        });
        group("existing slash base and absolute control paths keep existing endpoint semantics",()->{
            for(String location:Arrays.asList("http://"+HOST+":16690/","http://"+HOST+":16690/device.xml")){
                DlnaDevice device=parse(null,"/AVTransport/control",location);check(device.controlUri.toString().equals("http://"+HOST+":16690/AVTransport/control"),"absolute control path unchanged");
            }
            check(parse(null,"../av/control","http://"+HOST+":16690/xml/device.xml").controlUri.toString().equals("http://"+HOST+":16690/av/control"),"relative parent path unchanged");
            check(parse("http://"+HOST+":16690/folder/","av/control","http://"+HOST+":16690/device.xml").controlUri.toString().equals("http://"+HOST+":16690/folder/av/control"),"directory URLBase unchanged");
        });
        group("encoded control query remains byte-identical",()->{
            DlnaDevice device=parse(null,"AVTransport/control?x=a%2Fb&amp;y=%26","http://"+HOST+":16690");
            check(device.controlUri.getRawQuery().equals("x=a%2Fb&y=%26"),"query encoding preserved");
            check(target(device.controlUri).equals("/AVTransport/control?x=a%2Fb&y=%26"),"origin-form target retains query bytes");
        });
        group("HTTP request targets are always slash-prefixed origin-form",()->{
            check(target(URI.create("http://"+HOST+":16690")).equals("/"),"empty target is root slash");
            check(target(URI.create("http://"+HOST+":16690?x=1")).equals("/?x=1"),"empty target with query is root slash");
            check(target(URI.create("http://"+HOST+":16690/AVTransport/control")).equals("/AVTransport/control"),"normal absolute target unchanged");
            rejects(()->target(URI.create("AVTransport/uuid-fixture/control")),"relative request path rejected instead of serialized");
        });
        group("all peer and URL authority restrictions remain strict",()->{
            for(String control:Arrays.asList("http://8.8.8.8/control","http://192.168.1.21/control","//192.168.1.21/control","https://"+HOST+"/control","http://user@"+HOST+"/control","/control#fragment"))rejects(()->parse(null,control,"http://"+HOST+":16690"),"invalid control endpoint stays rejected");
            rejects(()->parse("http://8.8.8.8","AVTransport/control","http://"+HOST+":16690"),"invalid base cannot acquire trusted peer");
        });
        if(args.length==4)group("captured real renderer description parsed locally without network",()->{
            byte[] bytes;try(InputStream in=new FileInputStream(args[0]);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);bytes=out.toByteArray();}
            DlnaDevice device=DlnaDiscovery.parseDescription(bytes,URI.create(args[1]),args[2]);
            check(device.controlUri.toASCIIString().equals(args[3]),"actual saved description endpoint matches independently verified control URL");
            String path=target(device.controlUri);check(path.startsWith("/AVTransport/"),"actual renderer request line includes AVTransport prefix after slash");
            System.out.println("CAPTURED_DESCRIPTION endpoint="+device.controlUri+" requestTarget="+path+" networkRequests=0");
        });
        System.out.println("DLNA_ENDPOINT_RESOLUTION_"+(failed==0?"OK":"FAILED")+" groupsPassed="+passed+" groupsFailed="+failed+" checks="+checks+" runtime="+System.getProperty("java.vm.name")+" networkRequests=0 deviceTests=not_run");if(failed>0)System.exit(1);
    }
}
