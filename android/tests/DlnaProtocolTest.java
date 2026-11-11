package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Offline protocol fixtures only: no socket, LAN discovery, renderer or media. */
public final class DlnaProtocolTest {
    static int checks;
    interface Check { void run() throws Exception; }
    static void ok(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static void eq(Object a, Object b, String label) { ok(Objects.equals(a,b), label + ": " + a + " != " + b); }
    static void rejects(Check check, String label) throws Exception { checks++; try { check.run(); } catch (IOException | IllegalArgumentException expected) { return; } throw new AssertionError("accepted " + label); }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static final String HOST = "192.168.1.20", SERVICE = "urn:schemas-upnp-org:service:AVTransport:1";
    static final URI LOCATION = URI.create("http://" + HOST + ":1400/xml/device.xml");
    static String description(String base, String control) {
        return "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\">" + base +
            "<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>电视 &amp; DLNA</friendlyName><UDN>uuid:test-renderer</UDN>" +
            "<serviceList><service><serviceType>" + SERVICE + "</serviceType><controlURL>" + control + "</controlURL></service></serviceList></device></root>";
    }
    static String reply(String location) { return "HTTP/1.1 200 OK\r\nST: " + DlnaDiscovery.TARGET + "\r\nUSN: uuid:test-renderer::" + DlnaDiscovery.TARGET + "\r\nLOCATION: " + location + "\r\n\r\n"; }
    static String soap(String action, String content) { return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:" + action + "Response xmlns:u=\"" + SERVICE + "\">" + content + "</u:" + action + "Response></s:Body></s:Envelope>"; }
    static DlnaNetwork.Response response(int status, String body) { return new DlnaNetwork.Response(status, Collections.emptyMap(), bytes(body)); }
    static DlnaNetwork.Response http(String value) throws IOException { return DlnaNetwork.readResponse(new ByteArrayInputStream(bytes(value)), 1024); }
    static void endpointTests() throws Exception {
        for (String ip : Arrays.asList("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.254", "192.168.1.20")) ok(DlnaNetwork.isPrivateIpv4(ip), "private " + ip);
        for (String ip : Arrays.asList("8.8.8.8", "127.0.0.1", "169.254.169.254", "100.64.0.1", "172.15.1.2", "172.32.0.1", "192.169.0.1", "0.0.0.0", "239.255.255.250", "::1", "10.0.0.256", "010.0.0.1", "10.1", "localhost", "192.168.1.20.example.com", "2130706433")) ok(!DlnaNetwork.isPrivateIpv4(ip), "not private " + ip);
        for (String uri : Arrays.asList("https://"+HOST+"/control", "http://8.8.8.8/control", "http://127.0.0.1/control", "http://"+HOST+"@8.8.8.8/control", "http://user@"+HOST+"/control", "http://"+HOST+":0/control", "http://"+HOST+":65536/control", "http://"+HOST+"/control#fragment", "http://192.168.1.21/control", "http://metadata.google.internal/control", "http://[::1]/control")) rejects(() -> DlnaNetwork.endpoint(URI.create(uri),HOST), "endpoint " + uri);
        eq(DlnaNetwork.endpoint(URI.create("http://"+HOST+"/a/../control"),HOST).getPath(), "/control", "normalized same peer");
        // Invalid destinations fail before socket creation; no request reaches LAN.
        rejects(() -> DlnaNetwork.request(URI.create("http://8.8.8.8/"), "GET", Collections.emptyMap(), null,100), "raw public destination");
        System.out.println("PASS DLNA peer validation literal RFC1918 only noDNS=true");
    }
    static void discoveryTests() throws Exception {
        eq(DlnaDiscovery.parseResponse(bytes(reply(LOCATION.toString())), bytes(reply(LOCATION.toString())).length, HOST), LOCATION, "SSDP response");
        for (String value : Arrays.asList(reply("http://8.8.8.8/x"), reply("http://192.168.1.21/x"), reply(LOCATION.toString()).replace("HTTP/1.1 200", "HTTP/1.1 302"), reply(LOCATION.toString()).replace(DlnaDiscovery.TARGET, "ssdp:all"), reply(LOCATION.toString()).replace("\r\n\r\n", "\r\nLOCATION: " + LOCATION + "\r\n\r\n"), reply(LOCATION.toString()) + "body")) rejects(() -> DlnaDiscovery.parseResponse(bytes(value), bytes(value).length,HOST), "SSDP rejection");
        rejects(() -> DlnaDiscovery.parseResponse(bytes(reply(LOCATION.toString())), bytes(reply(LOCATION.toString())).length,"8.8.8.8"),"SSDP public source");
        String search = new String(DlnaDiscovery.searchRequest(2),StandardCharsets.US_ASCII); ok(search.startsWith("M-SEARCH * HTTP/1.1\r\n"), "MSEARCH"); ok(search.contains("MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + DlnaDiscovery.TARGET), "MSEARCH target");
        DlnaDevice relative = DlnaDiscovery.parseDescription(bytes(description("", "../av/control")),LOCATION,HOST);
        eq(relative.controlUri.toString(),"http://"+HOST+":1400/av/control","relative control URL"); eq(relative.name,"电视 & DLNA","friendly name");
        DlnaDevice base = DlnaDiscovery.parseDescription(bytes(description("<URLBase>http://"+HOST+":9999/base/</URLBase>", "av/control")),LOCATION,HOST);
        eq(base.controlUri.toString(),"http://"+HOST+":9999/base/av/control","URLBase resolution");
        for (String body : Arrays.asList(description("<URLBase>http://8.8.8.8/</URLBase>","control"),description("<URLBase>http://192.168.1.21/</URLBase>","control"),description("","http://8.8.8.8/control"),description("","//192.168.1.21/control"),description("", "").replace("<controlURL></controlURL>",""),description("", "control").replace("MediaRenderer:1","MediaServer:1"),description("", "control").replace("<UDN>uuid:test-renderer</UDN>","<UDN>x</UDN><UDN>y</UDN>"))) rejects(() -> DlnaDiscovery.parseDescription(bytes(body),LOCATION,HOST),"description rejection");
        AtomicBoolean cancelled = new AtomicBoolean(false); AtomicInteger receives = new AtomicInteger(), gets = new AtomicInteger(), callbacks = new AtomicInteger();
        DlnaDiscovery.Scan fake = new DlnaDiscovery.Scan() {
            public void send(byte[] request) { ok(Arrays.equals(request,DlnaDiscovery.searchRequest(1)),"actual discover sends bounded search"); }
            public DlnaDiscovery.Advertisement receive(int timeout) { int n=receives.getAndIncrement(); if(n==2)cancelled.set(true); byte[] value=bytes(reply(LOCATION.toString())); return new DlnaDiscovery.Advertisement(value,value.length,HOST); }
            public void close() { }
        };
        List<DlnaDevice> devices = DlnaDiscovery.discover(500, device -> callbacks.incrementAndGet(),cancelled::get,fake,(uri,method,headers,payload,timeout)-> {gets.incrementAndGet();ok(timeout<=1500,"bounded description timeout");return response(200,description("","/control"));});
        eq(devices.size(),1,"deduplicated devices");eq(gets.get(),1,"duplicate descriptions not fetched");eq(callbacks.get(),1,"callback once");
        eq(DlnaDiscovery.discover(500,null,()->true).size(),0,"already cancelled discovery performs no network");
        AtomicInteger ownershipChecks=new AtomicInteger();
        java.util.function.Predicate<String> wifiOwner=address->{ownershipChecks.incrementAndGet();return address.equals("192.168.1.2");};
        DlnaDiscovery.validateLocalIpv4("192.168.1.2",wifiOwner);eq(ownershipChecks.get(),1,"selected Wi-Fi ownership checked once");
        rejects(()->DlnaDiscovery.validateLocalIpv4("10.0.0.2",wifiOwner),"another private cellular/VPN address not selected owned Wi-Fi");
        eq(ownershipChecks.get(),2,"private unowned selection reaches local ownership boundary");
        for(String invalid:Arrays.asList("8.8.8.8","127.0.0.1","169.254.1.2","::1","localhost","192.168.01.2")) {
            rejects(()->DlnaDiscovery.validateLocalIpv4(invalid,wifiOwner),"selected Wi-Fi literal validation "+invalid);
            rejects(()->DlnaDiscovery.discover(invalid,500,null,()->true),"public overload rejects invalid before socket even cancelled "+invalid);
        }
        eq(ownershipChecks.get(),2,"invalid selected addresses never consult local interface boundary");
        rejects(()->DlnaDiscovery.validateLocalIpv4("192.168.1.2",address->false),"Wi-Fi interface removed/down before discovery");
        System.out.println("PASS DLNA selected Wi-Fi IPv4 local ownership boundary no route fallback tests=fake noSockets=true");
        System.out.println("PASS DLNA actual discovery fake UDP+HTTP dedup cancelled bounded=true networkRequests=0");
    }
    static void xmlTests() throws Exception {
        for (String body : Arrays.asList("<!DOCTYPE x [<!ENTITY ext SYSTEM 'file:///etc/passwd'>]><x>&ext;</x>","<!DOCTYPE x SYSTEM 'http://169.254.169.254/'><x/>","<!DOCTYPE x [<!ENTITY a '123'><!ENTITY b '&a;&a;'>]><x>&b;</x>","<a>".repeat(40)+"x"+"</a>".repeat(40),"<x><a/><a/></x>".repeat(2000),"<x>&unknown;</x>","<x>\0</x>")) rejects(()->DlnaXml.parse(bytes(body)),"bounded XML/XXE");
        rejects(()->DlnaXml.parse(new byte[]{(byte)0xff,(byte)0xfe,0,60}),"UTF16/invalid UTF8");
        rejects(()->DlnaXml.escape("bad\u0001"),"control character"); rejects(()->DlnaXml.escape("bad\ud800"),"lone surrogate");
        eq(DlnaXml.escape("a<&\"'>🙂"),"a&lt;&amp;&quot;&apos;&gt;🙂","all XML escaping");
        System.out.println("PASS DLNA bounded XML XXE/DTD/entity/UTF8/depth rejected=true");
    }
    static void controlTests() throws Exception {
        DlnaDevice device = new DlnaDevice("uuid:renderer","Renderer",HOST,URI.create("http://"+HOST+":1400/control"),SERVICE);
        List<String> actions = new ArrayList<>(); List<String> documents = new ArrayList<>();
        DlnaControl control = new DlnaControl(device,(uri,method,headers,payload,timeout)->{
            eq(uri,device.controlUri,"same control peer");eq(method,"POST","SOAP method");eq(timeout,4000,"SOAP timeout");
            String soapAction=headers.get("SOAPAction");ok(soapAction.startsWith("\""+SERVICE+"#")&&soapAction.endsWith("\""),"quoted action header");
            String action=soapAction.substring(soapAction.indexOf('#')+1,soapAction.length()-1);actions.add(action);String xml=new String(payload,StandardCharsets.UTF_8);documents.add(xml);
            DlnaXml.Node request=DlnaXml.parse(payload).child("Body").children.get(0);eq(request.name,action,"actual XML action");eq(request.value("InstanceID"),"0","instance zero");
            if(action.equals("GetTransportInfo"))return response(200,soap(action,"<CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>"));
            if(action.equals("GetPositionInfo"))return response(200,soap(action,"<TrackURI>http://192.168.1.2:8080/media/token/video.mp4?a=1&amp;b=2</TrackURI><RelTime>00:01:23.456</RelTime><TrackDuration>00:05:10</TrackDuration>"));
            return response(200,soap(action,""));
        });
        String media="http://192.168.1.2:8080/media/token/video.mp4?a=1&b=2",title="标题 <&\"'> 🙂";
        control.setMedia(media,title); control.play(); control.pause(); control.seek(83456); control.stop(); DlnaControl.Status status=control.status();
        eq(actions,Arrays.asList("SetAVTransportURI","Play","Pause","Seek","Stop","GetTransportInfo","GetPositionInfo"),"all actual actions");eq(status.state,"PLAYING","transport state");eq(status.mediaUri,media,"media URI");eq(status.positionMs,83456L,"position");eq(status.durationMs,310000L,"duration");
        DlnaXml.Node mediaAction=DlnaXml.parse(bytes(documents.get(0))).child("Body").children.get(0);eq(mediaAction.value("CurrentURI"),media,"SOAP escaped URI roundtrip");
        DlnaXml.Node didl=DlnaXml.parse(bytes(mediaAction.value("CurrentURIMetaData")));eq(didl.child("item").value("title"),title,"DIDL escaping roundtrip prevents injected tags");eq(didl.child("item").value("res"),media,"DIDL resource roundtrip");eq(didl.child("item").children.size(),3,"no metadata injection");
        DlnaXml.Node seek=DlnaXml.parse(bytes(documents.get(3))).child("Body").children.get(0);eq(seek.value("Unit"),"REL_TIME","seek unit");eq(seek.value("Target"),"00:01:23","seek target floor seconds");
        rejects(()->control.setMedia("http://8.8.8.8/video.mp4",title),"no public media export");rejects(()->control.setMedia("file:///private.mp4",title),"no file media export");rejects(()->control.seek(-1),"negative seek");
        String fault="<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>701</errorCode><errorDescription>Transition not available</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>";
        for(int code:Arrays.asList(200,500)){DlnaControl bad=new DlnaControl(device,(u,m,h,b,t)->response(code,fault));try{bad.play();throw new AssertionError("fault accepted");}catch(IOException error){ok(error.getMessage().contains("701")&&error.getMessage().contains("Transition not available"),"SOAP fault visible HTTP"+code);}}
        for(String body:Arrays.asList(soap("Pause",""),soap("Play","").replace(SERVICE,"urn:other"),"<root/>",soap("Play","").replace("</s:Body>","<u:Extra xmlns:u='x'/></s:Body>"))){DlnaControl bad=new DlnaControl(device,(u,m,h,b,t)->response(200,body));rejects(bad::play,"mismatched SOAP response");}
        DlnaControl unknown = new DlnaControl(device,(u,m,h,b,t)-> {
            boolean info=h.get("SOAPAction").contains("#GetTransportInfo");
            return response(200,soap(info?"GetTransportInfo":"GetPositionInfo",info?"<CurrentTransportState>VENDOR_UNKNOWN</CurrentTransportState>":"<RelTime>NOT_IMPLEMENTED</RelTime><TrackDuration>NOT_IMPLEMENTED</TrackDuration>"));
        });
        eq(unknown.status().state,"UNKNOWN","unknown state never becomes STOPPED/completed");
        DlnaControl httpError = new DlnaControl(device,(u,m,h,b,t)->response(403,"not XML"));
        try { httpError.play();throw new AssertionError("HTTP error accepted"); }catch(IOException error){ok(error.getMessage().contains("HTTP 403"),"plain HTTP status visible");}
        for(String value:Arrays.asList("00:99:00","-1:00:00","9999999:00:00","x"))rejects(()->DlnaControl.timeMs(value),"invalid timestamp");eq(DlnaControl.timeMs("NOT_IMPLEMENTED"),0L,"unimplemented timestamp");
        System.out.println("PASS DLNA actual SOAP methods DIDL escaping status seek faults mismatch rejection=true");
    }
    static void httpTests() throws Exception {
        eq(new String(http("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello").body,StandardCharsets.UTF_8),"hello","content length");
        eq(new String(http("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhe\r\n3;foo=bar\r\nllo\r\n0\r\n\r\n").body,StandardCharsets.UTF_8),"hello","chunked actual parser");
        eq(new String(http("HTTP/1.0 200 OK\r\n\r\nclose").body,StandardCharsets.UTF_8),"close","close framing");
        eq(http("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 5\r\n\r\nfault").status,500,"SOAP fault HTTP preserved");
        for(String value:Arrays.asList("HTTP/1.1 302 Found\r\nLocation: http://8.8.8.8/\r\nContent-Length: 0\r\n\r\n","HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhi","HTTP/1.1 200 OK\r\nContent-Length: 2048\r\n\r\n","HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nx","HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\nx","HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n","HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n","HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1000\r\n","HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nxzz","HTTP/1.1 200 OK\n\n","HTTP/1.1 200 OK\r\nX-Header: "+"x".repeat(17000)+"\r\n\r\n","HTTP/1.1 200 OK\r\n\r\n"+"x".repeat(1025))) rejects(()->http(value),"HTTP framing/bounds/redirect");
        System.out.println("PASS DLNA actual bounded HTTP parser chunked length EOF redirect headers ambiguity rejected=true");
    }
    public static void main(String[] args) throws Exception { endpointTests();discoveryTests();xmlTests();controlTests();httpTests();System.out.println("DLNA_PROTOCOL_OK assertions="+checks+" networkRequests=0 deviceTests=not_run"); }
}
