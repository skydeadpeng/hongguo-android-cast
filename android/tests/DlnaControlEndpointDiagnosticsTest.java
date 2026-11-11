package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Actual controller and immutable endpoint with injected byte replies; never opens a socket. */
public final class DlnaControlEndpointDiagnosticsTest {
    private static final String HOST="192.168.1.20", SOAP="http://schemas.xmlsoap.org/soap/envelope/";
    private static final String SECRET="PRIVATE_MEDIA_TOKEN", TITLE="PRIVATE_TITLE_中文 & <title>";
    private static final String CONTROL_PATH="/AVTransport/PRIVATE_CONTROL_PATH/control", CONTROL_QUERY="PRIVATE_CONTROL_QUERY=credential";
    private static final String MEDIA="http://192.168.1.2:9080/media/"+SECRET+".mp4?private=secret&second=2";
    private static int checks,passed,failed;
    private interface Checked {void run() throws Exception;}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void group(String name,Checked test){try{test.run();passed++;System.out.println("PASS ControlEndpointDiagnostics "+name);}catch(Throwable failure){failed++;System.out.println("FAIL ControlEndpointDiagnostics "+name+": "+failure.getMessage());}}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String hash(byte[] value)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(value);StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.substring(0,12);}
    private static String service(int version){return "urn:schemas-upnp-org:service:AVTransport:"+version;}
    private static DlnaDevice device(int port,int version,String path,String query)throws Exception{return new DlnaDevice("uuid:fixture","Fixture",HOST,URI.create("http://"+HOST+(port<0?"":":"+port)+path+(query.isEmpty()?"":"?"+query)),service(version));}
    private static DlnaDevice device()throws Exception{return device(16690,1,CONTROL_PATH,CONTROL_QUERY);}
    private static String action(Map<String,String> headers){String value=headers.get("SOAPAction");return value.substring(value.indexOf('#')+1,value.length()-1);}
    private static String xml(String action,int version,String contents){return "<s:Envelope xmlns:s=\""+SOAP+"\"><s:Body><u:"+action+"Response xmlns:u=\""+service(version)+"\">"+contents+"</u:"+action+"Response></s:Body></s:Envelope>";}
    private static String fault(String code){return "<s:Envelope xmlns:s=\""+SOAP+"\"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>"+code+"</errorCode><errorDescription>"+SECRET+"</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>";}
    private static String esc(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static byte[] expectedRequest(String action,int version,String args){return bytes("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\""+SOAP+"\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:"+action+" xmlns:u=\""+service(version)+"\"><InstanceID>0</InstanceID>"+args+"</u:"+action+"></s:Body></s:Envelope>");}
    private static byte[] expectedSet(int version){String metadata="<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>"+esc(TITLE)+"</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=01\">"+esc(MEDIA)+"</res></item></DIDL-Lite>";return expectedRequest("SetAVTransportURI",version,"<CurrentURI>"+esc(MEDIA)+"</CurrentURI><CurrentURIMetaData>"+esc(metadata)+"</CurrentURIMetaData>");}
    private static String diagnostics(DlnaControl control){return control.diagnostics();}
    private static IOException rejected(Checked request)throws Exception{try{request.run();}catch(IOException failure){return failure;}throw new AssertionError("expected unchanged rejection semantics");}
    private static void boundedPrivate(String text){
        check(text.length()<=768,"diagnostic total remains at most 768 characters");
        for(String forbidden:Arrays.asList(SECRET,TITLE,"PRIVATE_TITLE","PRIVATE_CONTROL_PATH","PRIVATE_CONTROL_QUERY","PRIVATE_CONTENT_TYPE",MEDIA,"192.168.1.2:","http://","https://","<s:Envelope","<DIDL-Lite","CurrentURIMetaData"))check(!text.contains(forbidden),"diagnostics never reflects private path, body, query, title or headers: "+forbidden);
        check(!text.matches("(?s).*[\\p{Cntrl}&&[^\\n]].*"),"diagnostic contains no raw control characters beyond line separators");
    }
    private static String fieldLine(String text,String field){int start=text.indexOf(field);check(start>=0,"diagnostic field exists: "+field);int end=text.indexOf('\n',start);return text.substring(start,end<0?text.length():end);}
    private static void field(String text,String name,String expected){Matcher m=Pattern.compile("(?<![A-Za-z])"+Pattern.quote(name)+"\\s*[:=：]\\s*"+Pattern.quote(expected)+"(?![A-Za-z0-9])").matcher(text);check(m.find(),"precise exchange field "+name+"="+expected);}
    static final class Call {final URI endpoint;final String action,method;final Map<String,String>headers;final byte[]body;final int timeout;Call(URI endpoint,String method,Map<String,String>headers,byte[]body,int timeout){this.endpoint=endpoint;this.method=method;this.headers=Map.copyOf(headers);this.body=body.clone();this.timeout=timeout;action=action(headers);}}
    static final class Reply {final int status;final byte[]body;final IOException failure;Reply(int status,byte[]body){this.status=status;this.body=body;failure=null;}Reply(IOException failure){status=0;body=null;this.failure=failure;}}
    static final class Fixture implements DlnaNetwork.Transport {
        final DlnaDevice device;final DlnaControl control;final List<Call>calls=new ArrayList<>();final List<Reply>replies=new ArrayList<>();int next;
        Fixture()throws Exception{this(device());}Fixture(DlnaDevice device){this.device=device;control=new DlnaControl(device,this);}
        void respond(int status,String body){replies.add(new Reply(status,bytes(body)));}void respond(int status,byte[]body){replies.add(new Reply(status,body));}void fail(IOException failure){replies.add(new Reply(failure));}
        public DlnaNetwork.Response exchange(URI endpoint,String method,Map<String,String>headers,byte[]body,int timeout)throws IOException {
            Call call=new Call(endpoint,method,headers,body,timeout);calls.add(call);Reply reply;
            if(next<replies.size())reply=replies.get(next++);else{String fields=call.action.equals("GetTransportInfo")?"<CurrentTransportState>PLAYING</CurrentTransportState>":call.action.equals("GetPositionInfo")?"<RelTime>NOT_IMPLEMENTED</RelTime><TrackDuration>NOT_IMPLEMENTED</TrackDuration>":"";reply=new Reply(200,bytes(xml(call.action,Integer.parseInt(device.serviceType.substring(device.serviceType.lastIndexOf(':')+1)),fields)));}
            if(reply.failure!=null)throw reply.failure;
            return new DlnaNetwork.Response(reply.status,Collections.singletonMap("content-type","PRIVATE_CONTENT_TYPE;credential="+SECRET),reply.body);
        }
    }
    private static void exchange(String line,Call request,byte[]response,String type)throws Exception{
        field(line,"requestBytes",Integer.toString(request.body.length));field(line,"requestHash",hash(request.body));field(line,"responseBytes",Integer.toString(response.length));field(line,"responseHash",hash(response));field(line,"responseType",type);
    }
    public static void main(String[] args)throws Exception {
        group("explicit endpoint and version with private path fingerprint only",()->{
            Fixture f=new Fixture();String text=diagnostics(f.control);check(text.contains("控制="+HOST+":16690")&&text.contains("AVTransport=1"),"receiver host, port and AVTransport version are inspectable");check(text.contains("路径指纹="+hash(bytes(f.device.controlUri.getRawPath()))),"path fingerprint uses SHA256 rawPath UTF8 first twelve hex");check(fieldLine(text,"启动失败=").contains("无"),"initial startup has not failed");boundedPrivate(text);check(f.calls.isEmpty(),"reading endpoint metadata sends no request");
        });
        group("default port80 v2 and query independent UTF8 rawPath hash",()->{
            String path="/控制/PRIVATE_CONTROL_PATH";Fixture a=new Fixture(device(-1,2,path,"PRIVATE_CONTROL_QUERY=one")),b=new Fixture(device(80,2,path,"PRIVATE_CONTROL_QUERY=two"));
            for(Fixture f:Arrays.asList(a,b)){String text=diagnostics(f.control);check(text.contains("控制="+HOST+":80")&&text.contains("AVTransport=2"),"missing port maps to80 and advertised service version survives");check(text.contains("路径指纹="+hash(bytes(path))),"query is omitted and unescaped rawPath is hashed as UTF8");boundedPrivate(text);}
        });
        group("all seven original SOAP calls preserve exact byte and header contracts",()->{
            for(int version:new int[]{1,2}){Fixture f=new Fixture(device(16690,version,CONTROL_PATH,CONTROL_QUERY));f.control.setMedia(MEDIA,TITLE);f.control.play();f.control.pause();f.control.seek(12345);f.control.stop();f.control.status();String[]actions={"SetAVTransportURI","Play","Pause","Seek","Stop","GetTransportInfo","GetPositionInfo"};check(f.calls.size()==actions.length,"no new request, retry or fallback");for(int i=0;i<actions.length;i++){Call call=f.calls.get(i);check(call.action.equals(actions[i])&&call.method.equals("POST")&&call.timeout==4000&&call.endpoint.equals(f.device.controlUri),"original action order, endpoint, method and timeout retained");check(call.headers.equals(Map.of("Content-Type","text/xml; charset=\"utf-8\"","SOAPAction","\""+service(version)+"#"+actions[i]+"\"")),"exact SOAP request headers retained");byte[]expected=i==0?expectedSet(version):expectedRequest(actions[i],version,i==1?"<Speed>1</Speed>":i==3?"<Unit>REL_TIME</Unit><Target>00:00:12</Target>":"");check(Arrays.equals(call.body,expected),"exact UTF8 SOAP request bytes retained for "+actions[i]);}boundedPrivate(diagnostics(f.control));}
        });
        String[][] types={{"HTML","  <!DOCTYPE html><HTML><body>"+SECRET+"</body></HTML>"},{"TEXT","server failed "+SECRET+" "+MEDIA},{"XML","<failure><private>"+SECRET+"</private></failure>"},{"XML_BOM","\ufeff<?xml version=\"1.0\"?><failure>"+SECRET+"</failure>"},{"EMPTY",""}};
        for(String[] example:types)group("HTTP500 "+example[0]+" stores exact hashes without reflecting response",()->{
            Fixture f=new Fixture();byte[] body=bytes(example[1]);f.respond(500,body);IOException failure=rejected(()->f.control.setMedia(MEDIA,TITLE));check(failure.getClass().getSimpleName().equals("ActionException"),"transport rejection remains typed");String text=diagnostics(f.control);exchange(fieldLine(text,"最近失败交换="),f.calls.get(0),body,example[0]);String startup=fieldLine(text,"启动失败=");check(startup.contains("SetAVTransportURI")&&startup.contains("500"),"first startup failure retains its command and HTTP");exchange(startup,f.calls.get(0),body,example[0]);boundedPrivate(text);check(f.calls.size()==1,"response fingerprinting never adds a request");
        });
        group("binary reply has bounded finite classification and byte fingerprint",()->{
            Fixture f=new Fixture();byte[] body={0,(byte)0xff,2,3};f.respond(500,body);rejected(()->f.control.play());String text=diagnostics(f.control);exchange(fieldLine(text,"最近失败交换="),f.calls.get(0),body,"BINARY");boundedPrivate(text);
        });
        group("network failure reports no fabricated response or empty body fingerprint",()->{
            Fixture f=new Fixture();SocketTimeoutException timeout=new SocketTimeoutException("PRIVATE_TRANSPORT "+MEDIA);f.fail(timeout);IOException failure=rejected(()->f.control.play());check(failure.getCause()==timeout,"network failure preserves exact cause");String text=diagnostics(f.control),line=fieldLine(text,"最近失败交换=");field(line,"requestBytes",Integer.toString(f.calls.get(0).body.length));field(line,"requestHash",hash(f.calls.get(0).body));check(line.contains("未取得")&&!line.contains(hash(new byte[0])),"no response is distinct from an actual EMPTY response");check(!line.matches("(?s).*responseHash\\s*[:=：]\\s*[a-f0-9]{12}.*"),"unreceived response never obtains a digest");boundedPrivate(text);
        });
        group("startup SOAP fault survives a later nonSOAP poll failure and successful polls",()->{
            Fixture f=new Fixture();byte[] first=bytes(fault("701")),later=bytes("<html>PRIVATE_POLL_FAILURE</html>");f.respond(500,first);rejected(()->f.control.setMedia(MEDIA,TITLE));String startup=fieldLine(diagnostics(f.control),"启动失败=");check(startup.contains("SetAVTransportURI")&&startup.contains("UPnP=701"),"startup captures original SOAP action and UPnP code");f.respond(500,later);rejected(f.control::status);String failed=diagnostics(f.control);check(fieldLine(failed,"启动失败=").equals(startup),"later GetTransportInfo failure cannot overwrite first startup exchange");exchange(fieldLine(failed,"最近失败交换="),f.calls.get(1),later,"HTML");check(failed.contains("GetTransportInfo"),"latest status failure remains visible separately");String latest=fieldLine(failed,"最近失败交换=");f.control.status();String recovered=diagnostics(f.control);check(fieldLine(recovered,"启动失败=").equals(startup)&&fieldLine(recovered,"最近失败交换=").equals(latest),"successful poll does not clear either failure history");check(f.calls.size()==4,"failed and successful status calls preserve original sequence only");boundedPrivate(recovered);
        });
        group("Play startup failure retains its own request after SetURI acceptance",()->{
            Fixture f=new Fixture();f.control.setMedia(MEDIA,TITLE);byte[] body=bytes(fault("718"));f.respond(500,body);rejected(f.control::play);String text=diagnostics(f.control),startup=fieldLine(text,"启动失败=");check(startup.contains("Play")&&startup.contains("UPnP=718"),"Play failing after accepted SetURI is preserved");exchange(startup,f.calls.get(1),body,"XML");boundedPrivate(text);
        });
        group("only a fresh SetURI attempt clears the previous startup cause",()->{
            Fixture f=new Fixture();f.respond(500,fault("701"));rejected(()->f.control.setMedia(MEDIA,TITLE));String initial=fieldLine(diagnostics(f.control),"启动失败=");f.respond(500,fault("501"));rejected(f.control::play);check(fieldLine(diagnostics(f.control),"启动失败=").equals(initial),"later explicit Play failure still preserves first startup cause");f.control.setMedia(MEDIA,TITLE);String restarted=diagnostics(f.control);check(fieldLine(restarted,"启动失败=").contains("无"),"new SetURI starts a new startup attempt");check(restarted.contains("Play=尚未请求"),"new SetURI resets old Play acknowledgement");int count=f.calls.size();for(int i=0;i<20;i++)boundedPrivate(diagnostics(f.control));check(f.calls.size()==count,"diagnostic reads do not send hidden requests");
        });
        group("a new failing SetURI replaces the prior startup epoch before acceptance",()->{
            Fixture f=new Fixture();f.respond(500,fault("701"));rejected(()->f.control.setMedia(MEDIA,TITLE));byte[] second=bytes(fault("501"));f.respond(500,second);rejected(()->f.control.setMedia(MEDIA,TITLE));String text=diagnostics(f.control),startup=fieldLine(text,"启动失败=");check(startup.contains("UPnP=501")&&!startup.contains("UPnP=701"),"reset occurs when fresh SetURI starts, not only after a successful reply");exchange(startup,f.calls.get(1),second,"XML");check(f.calls.size()==2,"a fresh explicit attempt adds exactly its own one SetURI request");boundedPrivate(text);
        });
        group("status failure alone is not fabricated as a startup failure",()->{
            Fixture f=new Fixture();f.respond(500,"<html>PRIVATE_POLL_ONLY</html>");rejected(f.control::status);String text=diagnostics(f.control);check(fieldLine(text,"启动失败=").contains("无"),"read-only polling never becomes startup failure");check(text.contains("GetTransportInfo"),"actual poll action retained");boundedPrivate(text);
        });
        System.out.println("DLNA_CONTROL_ENDPOINT_DIAGNOSTICS_"+(failed==0?"OK":"FAILED")+" groupsPassed="+passed+" groupsFailed="+failed+" checks="+checks+" actualController=true networkRequests=0 deviceTests=not_run");if(failed!=0)System.exit(1);
    }
}
