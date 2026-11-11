package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Offline receiver interoperability fixtures. All discovery and HTTP transports are fakes. */
public final class DlnaReceiverCompatibilityTest {
    interface Check { void run() throws Exception; }
    interface Reply { DlnaNetwork.Response exchange(URI uri, int call) throws IOException; }
    static final String HOST="192.168.1.20", SERVICE="urn:schemas-upnp-org:service:AVTransport:1";
    static final URI LOCATION=URI.create("http://"+HOST+":1400/xml/device.xml");
    static int groups, failed, assertions;
    static void group(String name, Check test) {
        groups++;
        try { test.run(); System.out.println("PASS receiver-compat "+name); }
        catch (Throwable error) { failed++; System.out.println("FAIL receiver-compat "+name+" "+error.getClass().getSimpleName()+": "+error.getMessage()); }
    }
    static void ok(boolean value,String label) { assertions++;if(!value)throw new AssertionError(label); }
    static void eq(Object actual,Object expected,String label) { ok(Objects.equals(actual,expected),label+" actual="+actual+" expected="+expected); }
    static void rejects(Check check,String label) throws Exception { assertions++;try { check.run(); }catch(IOException|IllegalArgumentException expected){return;}throw new AssertionError("accepted "+label); }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static String xml(String id) { return "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Receiver</friendlyName><UDN>uuid:"+id+"</UDN><serviceList><service><serviceType>"+SERVICE+"</serviceType><controlURL>/control</controlURL></service></serviceList></device></root>"; }
    static DlnaNetwork.Response response(int status,String body) { return new DlnaNetwork.Response(status,Collections.emptyMap(),bytes(body)); }
    static DlnaNetwork.Response http(String headers,String body,int maxBody) throws IOException { return DlnaNetwork.readResponse(new ByteArrayInputStream(bytes("HTTP/1.1 200 OK\r\n"+headers+"\r\n"+body)),maxBody); }
    static DlnaDiscovery.Advertisement ad(URI location) {
        byte[] data=bytes("HTTP/1.1 200 OK\r\nST: "+DlnaDiscovery.TARGET+"\r\nUSN: uuid:renderer::"+DlnaDiscovery.TARGET+"\r\nLOCATION: "+location+"\r\n\r\n");
        return new DlnaDiscovery.Advertisement(data,data.length,HOST);
    }
    static List<DlnaDiscovery.Advertisement> repeat(URI location,int count) { List<DlnaDiscovery.Advertisement> ads=new ArrayList<>();for(int i=0;i<count;i++)ads.add(ad(location));return ads; }
    static final class Fixture implements DlnaDiscovery.Scan {
        final List<DlnaDiscovery.Advertisement> ads;
        final AtomicBoolean cancelled=new AtomicBoolean();
        int sends,receives,gets,callbacks; List<DlnaDevice> devices;
        Fixture(List<DlnaDiscovery.Advertisement> ads) { this.ads=ads; }
        public void send(byte[] request) { sends++;eq(new String(request,StandardCharsets.US_ASCII),new String(DlnaDiscovery.searchRequest(1),StandardCharsets.US_ASCII),"bounded MediaRenderer search"); }
        public DlnaDiscovery.Advertisement receive(int timeout) {
            ok(timeout>=1&&timeout<=250,"receive timeout bound");
            int n=receives++;if(n<ads.size())return ads.get(n);
            cancelled.set(true);return new DlnaDiscovery.Advertisement(bytes("invalid"),7,HOST);
        }
        public void close() { }
        void run(Reply reply) throws IOException {
            devices=DlnaDiscovery.discover(500,device->callbacks++,cancelled::get,this,(uri,method,headers,body,timeout)->{
                gets++;eq(method,"GET","description method");ok(headers.isEmpty(),"description no extra headers");eq(body.length,0,"description no body");ok(timeout>=100&&timeout<=1500,"description timeout bound");
                return reply.exchange(uri,gets);
            });
        }
    }
    static void headerGroups() {
        group("DLNA dotted extension response header",()->{
            DlnaNetwork.Response r=http("Content-Length: 2\r\ncontentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0\r\n","ok",32);
            eq(r.headers.get("contentfeatures.dlna.org"),"DLNA.ORG_OP=01;DLNA.ORG_CI=0","DLNA header preserved");eq(new String(r.body,StandardCharsets.UTF_8),"ok","body decoded");
        });
        group("vendor underscore response header",()->eq(http("Content-Length: 0\r\nX_Cast_Mode: media\r\n","",32).headers.get("x_cast_mode"),"media","vendor field preserved"));
        group("all RFC HTTP token punctuation accepted",()->{
            for(char c:"!#$%&'*+-.^_`|~".toCharArray())eq(http("Content-Length: 0\r\nX"+c+"Cast: yes\r\n","",32).headers.get(("X"+c+"Cast").toLowerCase(Locale.ROOT)),"yes","token punctuation "+c);
        });
        group("response header name length remains bounded",()->{
            eq(http("Content-Length: 0\r\n"+"X".repeat(80)+": yes\r\n","",32).headers.get("x".repeat(80)),"yes","80 characters accepted");
            rejects(()->http("Content-Length: 0\r\n"+"X".repeat(81)+": yes\r\n","",32),"81-character header");
        });
        group("invalid header name separators remain rejected",()->{
            for(char c:"()<>@,;\\\"/[]?={}".toCharArray())rejects(()->http("Content-Length: 0\r\nX"+c+"Cast: yes\r\n","",32),"separator "+c);
            for(String name:Arrays.asList("X Cast","X\tCast"," X-Cast","\tX-Cast","X-Cast ",""))rejects(()->http("Content-Length: 0\r\n"+name+": yes\r\n","",32),"name whitespace");
        });
        group("case insensitive duplicate extension fields rejected",()->{
            rejects(()->http("Content-Length: 0\r\nContentFeatures.dlna.org: a\r\ncontentfeatures.dlna.org: b\r\n","",32),"duplicate dotted field");
            rejects(()->http("Content-Length: 0\r\nX_Cast_Mode: a\r\nx_cast_mode: b\r\n","",32),"duplicate underscore field");
        });
        group("CRLF control and folding remain rejected",()->{
            for(String h:Arrays.asList("X-Cast: a\nb\r\n","X-Cast: a\rX: b\r\n","X-Cast: a\u0000\r\n","X-Cast: a\u007f\r\n","X-Cast: a\r\n folded\r\n","X-Cast: a\r\n\tfolded\r\n"))rejects(()->http("Content-Length: 0\r\n"+h,"",32),"control or folded header");
        });
        group("header count total byte and body budgets remain",()->{
            StringBuilder sixtyFour=new StringBuilder("Content-Length: 0\r\n");for(int i=0;i<63;i++)sixtyFour.append("X-").append(i).append(": ok\r\n");
            eq(http(sixtyFour.toString(),"",32).headers.size(),64,"64 headers accepted");
            rejects(()->http(sixtyFour+"X-65: no\r\n","",32),"65 headers");
            rejects(()->http("X-Cast: "+"a".repeat(16384)+"\r\n","",32),"header byte budget");
            rejects(()->http("Content-Length: 33\r\n","a".repeat(33),32),"content length body bound");
            rejects(()->http("","a".repeat(33),32),"EOF body bound");
        });
        group("HTTP framing and redirect rejection preserved",()->{
            eq(new String(http("Content-Length: 2\r\nX_Cast: yes\r\n","ok",32).body,StandardCharsets.UTF_8),"ok","length body");
            eq(new String(http("Transfer-Encoding: chunked\r\ncontentFeatures.dlna.org: yes\r\n","2\r\nok\r\n0\r\n\r\n",32).body,StandardCharsets.UTF_8),"ok","chunked body");
            rejects(()->http("Content-Length: 2\r\nTransfer-Encoding: chunked\r\n","ok",32),"conflicting framing");
            rejects(()->http("Content-Length: 2\r\ncontent-length: 2\r\n","ok",32),"duplicate length");
            rejects(()->http("Content-Length: 3\r\n","ok",32),"truncated length");
            rejects(()->http("Transfer-Encoding: gzip\r\n","ok",32),"unsupported transfer");
            rejects(()->http("Content-Encoding: gzip\r\n","ok",32),"compressed body");
            rejects(()->DlnaNetwork.readResponse(new ByteArrayInputStream(bytes("HTTP/1.1 302 Found\r\nLocation: http://8.8.8.8/\r\nContent-Length: 0\r\n\r\n")),32),"redirect");
        });
    }
    static void discoveryGroups() {
        group("first 503 retries on next SSDP advertisement",()->{
            Fixture f=new Fixture(repeat(LOCATION,3));f.run((uri,n)->response(n==1?503:200,n==1?"busy":xml("one")));
            eq(f.gets,2,"503 then successful retry");eq(f.devices.size(),1,"receiver recovered");eq(f.callbacks,1,"one recovered callback");
        });
        group("first IOException retries on next advertisement",()->{
            Fixture f=new Fixture(repeat(LOCATION,3));f.run((uri,n)->{if(n==1)throw new IOException("synthetic reset");return response(200,xml("one"));});
            eq(f.gets,2,"I/O then successful retry");eq(f.devices.size(),1,"receiver recovered");eq(f.callbacks,1,"one recovered callback");
        });
        group("first malformed XML retries then succeeds",()->{
            Fixture f=new Fixture(repeat(LOCATION,3));f.run((uri,n)->response(200,n==1?"<bad>":xml("one")));
            eq(f.gets,2,"parse failure retry");eq(f.devices.size(),1,"valid description recovered");eq(f.callbacks,1,"one callback");
        });
        group("persistent HTTP failure at most two GETs per URL",()->{
            Fixture f=new Fixture(repeat(LOCATION,10));f.run((uri,n)->response(503,"busy"));eq(f.gets,2,"per-location retry limit");eq(f.devices.size(),0,"no failed device");eq(f.callbacks,0,"no failed callback");
        });
        group("persistent I/O failure at most two GETs per URL",()->{
            Fixture f=new Fixture(repeat(LOCATION,10));f.run((uri,n)->{throw new IOException("synthetic down");});eq(f.gets,2,"I/O per-location retry limit");eq(f.devices.size(),0,"no failed device");
        });
        group("persistent invalid XML at most two GETs per URL",()->{
            Fixture f=new Fixture(repeat(LOCATION,10));f.run((uri,n)->response(200,"<bad>"));eq(f.gets,2,"parse per-location retry limit");eq(f.devices.size(),0,"no parsed device");
        });
        group("successful URL GET and callback are each once",()->{
            Fixture f=new Fixture(repeat(LOCATION,10));f.run((uri,n)->response(200,xml("one")));eq(f.gets,1,"success dedup GET");eq(f.devices.size(),1,"success dedup devices");eq(f.callbacks,1,"success dedup callback");
        });
        group("retry waits for a later advertisement no eager probes",()->{
            Fixture f=new Fixture(repeat(LOCATION,1));f.run((uri,n)->response(503,"busy"));eq(f.gets,1,"no unannounced retry");eq(f.devices.size(),0,"failure not published");
        });
        group("global description GET limit remains 24 with retries",()->{
            List<DlnaDiscovery.Advertisement> ads=new ArrayList<>();for(int i=0;i<30;i++)ads.addAll(repeat(URI.create("http://"+HOST+":1400/xml/device"+i+".xml"),2));
            Fixture f=new Fixture(ads);Map<URI,Integer> perUrl=new HashMap<>();f.run((uri,n)->{perUrl.merge(uri,1,Integer::sum);return response(503,"busy");});
            eq(f.gets,24,"global GET bound");eq(perUrl.size(),12,"two announced attempts per location consume global budget");for(int count:perUrl.values())eq(count,2,"all descriptions bounded");eq(f.devices.size(),0,"no failed devices");
        });
        group("discovered devices remain capped at 24",()->{
            List<DlnaDiscovery.Advertisement> ads=new ArrayList<>();for(int i=0;i<30;i++)ads.add(ad(URI.create("http://"+HOST+":1400/xml/device"+i+".xml")));
            Fixture f=new Fixture(ads);f.run((uri,n)->response(200,xml("device-"+n)));eq(f.gets,24,"GET bound");eq(f.devices.size(),24,"device bound");eq(f.callbacks,24,"callback bound");
        });
        group("received advertisement budget remains 128",()->{
            List<DlnaDiscovery.Advertisement> ads=new ArrayList<>();for(int i=0;i<140;i++)ads.add(new DlnaDiscovery.Advertisement(bytes("invalid"),7,HOST));
            Fixture f=new Fixture(ads);f.run((uri,n)->response(200,xml("unexpected")));eq(f.receives,128,"datagram bound");eq(f.gets,0,"invalid adverts no GET");
        });
        group("already cancelled discovery does not send or GET",()->{
            Fixture f=new Fixture(repeat(LOCATION,3));f.cancelled.set(true);f.run((uri,n)->response(200,xml("one")));eq(f.sends,0,"cancel before search");eq(f.receives,0,"cancel before receive");eq(f.gets,0,"cancel before GET");
        });
        group("cancellation after failed GET prevents second attempt",()->{
            Fixture f=new Fixture(repeat(LOCATION,3));f.run((uri,n)->{f.cancelled.set(true);return response(503,"busy");});eq(f.gets,1,"cancel blocks retry");eq(f.devices.size(),0,"cancel no failed publish");eq(f.receives,1,"cancel stops receiving");
        });
        group("invalid announcements never trigger description GET",()->{
            List<DlnaDiscovery.Advertisement> ads=new ArrayList<>();ads.add(ad(URI.create("http://8.8.8.8/x")));ads.add(ad(URI.create("http://192.168.1.21/x")));ads.add(ad(URI.create("http://user@"+HOST+"/x")));ads.add(ad(URI.create("https://"+HOST+"/x")));
            Fixture f=new Fixture(ads);f.run((uri,n)->response(200,xml("unexpected")));eq(f.gets,0,"peer destinations validated before GET");eq(f.callbacks,0,"no invalid callback");
        });
        group("different valid locations with same device callback once",()->{
            List<DlnaDiscovery.Advertisement> ads=new ArrayList<>(repeat(LOCATION,2));URI alternate=URI.create("http://"+HOST+":1400/xml/alternate.xml");ads.addAll(repeat(alternate,2));
            Fixture f=new Fixture(ads);f.run((uri,n)->response(200,xml("same")));eq(f.gets,2,"one GET per successful URL");eq(f.devices.size(),1,"host and UDN dedup");eq(f.callbacks,1,"callback dedup");
        });
        group("original discovery deadline remains bounded",()->{
            Fixture f=new Fixture(repeat(LOCATION,4));long start=System.nanoTime();f.run((uri,n)->{try{Thread.sleep(510);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}return response(503,"busy");});
            long elapsed=(System.nanoTime()-start)/1000000L;eq(f.gets,1,"expired deadline no retry");ok(elapsed>=500&&elapsed<2000,"deadline fixture elapsed="+elapsed);
        });
    }
    static void endToEndGroups() {
        group("real HTTP extension headers through description discovery",()->{
            Fixture f=new Fixture(repeat(LOCATION,3));
            f.run((uri,n)->{
                String description=xml("http-description");
                return http("Content-Type: text/xml; charset=utf-8\r\nContent-Length: "+bytes(description).length+"\r\ncontentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0\r\nX_Cast_Mode: DLNA\r\n",description,512*1024);
            });
            eq(f.devices.size(),1,"real HTTP bytes produce receiver");eq(f.gets,1,"successful parsed HTTP URL GET once");eq(f.callbacks,1,"parsed HTTP discovery callback once");
            DlnaDevice receiver=f.devices.get(0);eq(receiver.id,"uuid:http-description","parsed device id");eq(receiver.controlUri,URI.create("http://"+HOST+":1400/control"),"parsed control endpoint");
        });
        group("real HTTP extension headers through complete SOAP control",()->{
            DlnaDevice device=new DlnaDevice("uuid:soap-http","HTTP receiver",HOST,URI.create("http://"+HOST+":1400/control"),SERVICE);
            String media="http://192.168.1.2:8080/media/token/video.mp4?a=1&b=2";
            List<String> actions=new ArrayList<>();
            DlnaControl control=new DlnaControl(device,(uri,method,headers,payload,timeout)->{
                eq(uri,device.controlUri,"SOAP endpoint");eq(method,"POST","SOAP method");eq(timeout,4000,"SOAP timeout");
                String quoted=headers.get("SOAPAction");ok(quoted!=null&&quoted.startsWith("\""+SERVICE+"#")&&quoted.endsWith("\""),"quoted SOAP action");
                String action=quoted.substring(quoted.indexOf('#')+1,quoted.length()-1);actions.add(action);
                DlnaXml.Node request=DlnaXml.parse(payload).child("Body").children.get(0);eq(request.name,action,"actual request body action");eq(request.value("InstanceID"),"0","SOAP instance zero");
                if(action.equals("SetAVTransportURI"))eq(request.value("CurrentURI"),media,"actual selected URI XML roundtrip");
                String content="";
                if(action.equals("GetTransportInfo"))content="<CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>";
                if(action.equals("GetPositionInfo"))content="<TrackURI>"+DlnaXml.escape(media)+"</TrackURI><RelTime>00:00:12.345</RelTime><TrackDuration>00:02:00</TrackDuration>";
                String body="<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:"+action+"Response xmlns:u=\""+SERVICE+"\">"+content+"</u:"+action+"Response></s:Body></s:Envelope>";
                return http("Content-Type: text/xml; charset=utf-8\r\nContent-Length: "+bytes(body).length+"\r\ncontentFeatures.dlna.org: DLNA.ORG_OP=01\r\nX_Cast_Mode: video\r\n",body,512*1024);
            });
            control.setMedia(media,"第 9 集");control.play();DlnaControl.Status status=control.status();
            eq(actions,Arrays.asList("SetAVTransportURI","Play","GetTransportInfo","GetPositionInfo"),"complete actual SOAP sequence");eq(status.state,"PLAYING","HTTP and SOAP report playing");eq(status.mediaUri,media,"current media URI confirmed");eq(status.positionMs,12345L,"current play position");eq(status.durationMs,120000L,"duration");
        });
    }
    public static void main(String[] args) {
        headerGroups();discoveryGroups();endToEndGroups();
        System.out.println("DLNA_RECEIVER_COMPAT groups="+groups+" failed="+failed+" assertions="+assertions+" networkRequests=0 deviceTests=not_run");
        if(failed!=0)System.exit(1);
    }
}
