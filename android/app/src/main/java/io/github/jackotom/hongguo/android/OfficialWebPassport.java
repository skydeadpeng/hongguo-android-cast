package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;
import org.json.*;

/**
 * Fixed-host client for the official website passport web endpoints (aid 513755).
 *
 * The QR handshake is the website's own browser flow: it carries no request signature, only a cookie
 * jar, so it is implemented natively and stays independent from the signed App API channel. Cookies
 * live in native memory and never reach the UI projection.
 */
final class OfficialWebPassport {
    static final String BASE="https://hongguoduanju.com";
    static final String USER_AGENT="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";
    static final int MAX_BODY=2*1024*1024;
    static final int MAX_COOKIES=64;
    static final int MAX_REDIRECTS=5;
    static final long TIMEOUT_NANOS=20_000_000_000L;

    interface Transport extends AutoCloseable {
        Reply execute(String url, Map<String,String> headers, long deadline) throws Exception;
        @Override default void close(){}
    }

    /** One HTTP reply with every header kept: the handshake needs each Set-Cookie separately. */
    static final class Reply {
        final int status;final String body;final Map<String,List<String>> headers;final String url;
        Reply(int status,String body,Map<String,List<String>> headers,String url){this.status=status;this.body=body==null?"":body;this.headers=headers==null?Collections.emptyMap():headers;this.url=url==null?"":url;}
        String header(String name){List<String> values=headers.get(name.toLowerCase(Locale.ROOT));return values==null||values.isEmpty()?"":values.get(0);}
        List<String> all(String name){List<String> values=headers.get(name.toLowerCase(Locale.ROOT));return values==null?Collections.emptyList():values;}
    }

    /** In-memory cookie jar: the official flow keeps its session in browser cookies only. */
    static final class CookieJar {
        private final LinkedHashMap<String,String> values=new LinkedHashMap<>();
        void absorb(List<String> rows){
            if(rows==null)return;
            for(String row:rows){
                if(row==null||row.length()>8192)continue;
                String pair=row.split(";",2)[0];
                int at=pair.indexOf('=');
                if(at<=0)continue;
                String name=pair.substring(0,at).trim(),value=pair.substring(at+1).trim();
                if(name.isEmpty()||name.length()>128||value.length()>4096)continue;
                if(!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}"))continue;
                if(expired(row))values.remove(name);else if(values.containsKey(name)||values.size()<MAX_COOKIES)values.put(name,value);
            }
        }
        /** A Max-Age=0 or epoch expiry deletes the cookie instead of storing an empty value. */
        private static boolean expired(String row){
            String lower=row.toLowerCase(Locale.ROOT);
            int at=lower.indexOf("max-age=");
            if(at>=0){int end=lower.indexOf(';',at);String raw=lower.substring(at+8,end<0?lower.length():end).trim();try{if(Long.parseLong(raw)<=0)return true;}catch(NumberFormatException ignored){}}
            return lower.contains("expires=thu, 01 jan 1970")||lower.contains("expires=thu, 1 jan 1970");
        }
        /** Restores a stored Cookie header; Set-Cookie rows and a request header are not the same shape. */
        void restore(String header){
            if(header==null||header.isEmpty()||header.length()>65536)return;
            for(String pair:header.split(";")){
                String text=pair.trim();int at=text.indexOf('=');
                if(at<=0)continue;
                String name=text.substring(0,at).trim(),value=text.substring(at+1).trim();
                if(name.isEmpty()||name.length()>128||value.length()>4096)continue;
                if(!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}"))continue;
                if(values.containsKey(name)||values.size()<MAX_COOKIES)values.put(name,value);
            }
        }
        String header(){StringBuilder builder=new StringBuilder();for(Map.Entry<String,String> entry:values.entrySet()){if(builder.length()>0)builder.append("; ");builder.append(entry.getKey()).append('=').append(entry.getValue());}return builder.toString();}
        boolean hasSession(){return values.containsKey("sessionid")||values.containsKey("sessionid_ss");}
        int size(){return values.size();}
    }

    private final Transport transport;
    OfficialWebPassport(){this(new Network());}
    OfficialWebPassport(Transport transport){if(transport==null)throw new IllegalArgumentException("红果官网通道无效");this.transport=transport;}

    /** Requests one of the three fixed passport endpoints; every other path is refused. */
    JSONObject api(String path, Map<String,String> extra, CookieJar jar, long deadline) throws Exception {
        if(!apiPath(path))throw new IOException("红果官网接口路径无效");
        Map<String,String> headers=browserHeaders("application/json, text/javascript");
        String cookie=jar.header();
        if(!cookie.isEmpty())headers.put("Cookie",cookie);
        Reply reply=transport.execute(endpoint(path,extra),headers,deadline);
        jar.absorb(reply.all("set-cookie"));
        if(reply.status!=200)throw new IOException("红果网页登录请求失败（HTTP "+reply.status+"）");
        String raw=reply.body;
        if(raw==null||raw.trim().isEmpty()||raw.length()>MAX_BODY)throw new IOException("红果网页登录响应异常");
        try{return new JSONObject(raw);}catch(JSONException invalid){throw new IOException("红果网页登录响应格式异常");}
    }

    /**
     * Follows the confirmation redirect the server hands out after the phone approves the scan.
     * The chain stays on the official domain and is bounded; each hop may set the session cookie.
     */
    void followRedirect(String value, CookieJar jar, long deadline) throws Exception {
        if(value==null||value.isEmpty())throw new IOException("登录确认跳转地址无效");
        URI uri=new URI(value);
        for(int hop=0;hop<MAX_REDIRECTS;hop++){
            if(!"https".equalsIgnoreCase(uri.getScheme())||!official(uri.getHost()))throw new IOException("登录确认跳转到了不支持的站点");
            Map<String,String> headers=browserHeaders("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            String cookie=jar.header();
            if(!cookie.isEmpty())headers.put("Cookie",cookie);
            Reply reply=transport.execute(uri.toASCIIString(),headers,deadline);
            jar.absorb(reply.all("set-cookie"));
            String location=reply.header("location");
            if(reply.status>=300&&reply.status<400&&location!=null&&!location.isEmpty()){uri=new URI(uri.resolve(location).toString());continue;}
            if(reply.status!=200)throw new IOException("登录确认失败（HTTP "+reply.status+"）");
            return;
        }
        throw new IOException("登录确认跳转次数过多");
    }

    private static Map<String,String> browserHeaders(String accept){
        Map<String,String> headers=new LinkedHashMap<>();
        headers.put("User-Agent",USER_AGENT);headers.put("Referer",BASE+"/");headers.put("Origin",BASE);
        headers.put("Accept",accept);headers.put("Accept-Encoding","identity");headers.put("Connection","close");
        return headers;
    }

    private static String endpoint(String path, Map<String,String> extra) throws IOException {
        Map<String,String> values=new LinkedHashMap<>();
        values.put("aid","513755");values.put("passport_jssdk_version","3.4.10");values.put("passport_jssdk_type","normal");
        values.put("account_sdk_source","web");values.put("is_from_ttaccountsdk","1");values.put("language","zh");
        if(extra!=null)for(Map.Entry<String,String> entry:extra.entrySet()){if(entry.getValue()==null)continue;values.put(entry.getKey(),entry.getValue());}
        StringBuilder query=new StringBuilder();
        for(Map.Entry<String,String> entry:values.entrySet()){
            if(query.length()>0)query.append('&');
            query.append(URLEncoder.encode(entry.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(entry.getValue(),"UTF-8"));
        }
        return BASE+path+"?"+query;
    }

    private static boolean apiPath(String path){
        return "/passport/web/get_qrcode/".equals(path)||"/passport/web/check_qrconnect/".equals(path)||"/passport/account/info/v2/".equals(path);
    }

    /** The official domain and its subdomains only; a lookalike host never receives a cookie. */
    static boolean official(String host){
        if(host==null)return false;
        String value=host.toLowerCase(Locale.ROOT);
        return value.equals("hongguoduanju.com")||value.endsWith(".hongguoduanju.com");
    }

    /** Real TLS transport: public DNS only, bounded framing, no redirect following. */
    static final class Network implements Transport {
        @Override public Reply execute(String url, Map<String,String> headers, long deadline) throws Exception {
            URI uri=validate(url);
            InetAddress[] addresses=NetworkGuard.publicAddresses(uri,deadline);
            IOException last=null;
            for(int index=0;index<Math.min(addresses.length,8);index++){
                NetworkGuard.remaining(deadline,7000);
                Socket candidate=new Socket();boolean connected=false;
                try(NetworkGuard.SocketGuard ignored=NetworkGuard.guardSocket(candidate,deadline)){
                    candidate.connect(new InetSocketAddress(addresses[index],443),NetworkGuard.remaining(deadline,7000));
                    connected=true;
                }catch(IOException error){candidate.close();last=error;if(!connected)continue;}
                try(NetworkGuard.SocketGuard ignored=NetworkGuard.guardSocket(candidate,deadline);
                    SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(candidate,uri.getHost(),443,true)){
                    SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);
                    tls.setSoTimeout(NetworkGuard.remaining(deadline,15000));tls.startHandshake();
                    String path=uri.getRawPath();if(path==null||path.isEmpty())path="/";if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
                    StringBuilder message=new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\nHost: ").append(uri.getHost()).append("\r\n");
                    int headerBytes=0;
                    for(Map.Entry<String,String> header:headers.entrySet()){
                        String name=header.getKey(),value=header.getValue();
                        if(name==null||value==null||!name.matches("[A-Za-z0-9-]{1,64}")||value.length()>16384||controls(value))throw new IOException("红果官网请求头无效");
                        headerBytes+=name.length()+value.length();if(headerBytes>16384)throw new IOException("红果官网请求头过长");
                        message.append(name).append(": ").append(value).append("\r\n");
                    }
                    message.append("\r\n");
                    tls.getOutputStream().write(message.toString().getBytes(StandardCharsets.US_ASCII));tls.getOutputStream().flush();
                    InputStream input=new BufferedInputStream(tls.getInputStream());
                    String status=line(input,8192);
                    if(!status.matches("HTTP/1\\.[01] [0-9]{3}.*"))throw new IOException("红果官网响应状态无效");
                    int code=Integer.parseInt(status.substring(9,12));
                    Map<String,List<String>> replyHeaders=new LinkedHashMap<>();int total=status.length();
                    for(;;){NetworkGuard.remaining(deadline,15000);String row=line(input,16384);total+=row.length();if(total>65536)throw new IOException("红果官网响应头过长");if(row.isEmpty())break;
                        int colon=row.indexOf(':');if(colon<=0)throw new IOException("红果官网响应头无效");
                        String name=row.substring(0,colon).toLowerCase(Locale.ROOT),value=row.substring(colon+1).trim();
                        if(replyHeaders.containsKey(name)&&Arrays.asList("content-length","transfer-encoding","content-encoding","location").contains(name))throw new IOException("红果官网响应头重复");
                        replyHeaders.computeIfAbsent(name,key->new ArrayList<>()).add(value);}
                    Map<String,String> framing=new HashMap<>();for(Map.Entry<String,List<String>> entry:replyHeaders.entrySet())framing.put(entry.getKey(),entry.getValue().get(0));
                    String encoding=framing.getOrDefault("content-encoding","identity");
                    if(!encoding.equalsIgnoreCase("identity"))throw new IOException("红果官网响应编码无效");
                    ByteArrayOutputStream body=new ByteArrayOutputStream();
                    NetworkGuard.streamBody(input,body,framing,MAX_BODY,deadline);
                    return new Reply(code,body.toString("UTF-8"),replyHeaders,uri.toASCIIString());
                }catch(IOException error){last=error;}
            }
            throw new IOException("红果官网连接失败",last);
        }
        private static URI validate(String value) throws Exception {
            URI uri=new URI(value);
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new IOException("红果官网地址无效");
            if(!official(uri.getHost()))throw new IOException("红果官网地址无效");
            return uri;
        }
        private static boolean controls(String value){for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)==127)return true;return false;}
        private static String line(InputStream input,int max)throws IOException{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            while(out.size()<=max){int b=input.read();if(b<0)throw new EOFException("红果官网响应中断");if(b=='\n')return out.toString("US-ASCII").replaceFirst("\r$","");out.write(b);}
            throw new IOException("红果官网响应行过长");
        }
    }
}
