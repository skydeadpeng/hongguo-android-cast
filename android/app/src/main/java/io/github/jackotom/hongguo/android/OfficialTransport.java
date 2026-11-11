package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;

/** Fixed-host TLS with pinned public DNS results, cancellation, one total deadline, and bounded raw HTTP framing. */
final class OfficialTransport implements OfficialApiClient.Transport {
    private final AtomicBoolean executing=new AtomicBoolean();private volatile boolean closed;private volatile Socket active;private volatile Thread owner;
    @Override public void close(){closed=true;Socket socket=active;if(socket!=null)try{socket.close();}catch(IOException ignored){}Thread thread=owner;if(thread!=null&&thread!=Thread.currentThread())thread.interrupt();}
    private void check(long deadline)throws IOException{if(closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException("红果账号请求已取消");if(System.nanoTime()>=deadline)throw new SocketTimeoutException("红果账号请求超时");}
    private int timeout(long deadline)throws IOException{check(deadline);return(int)Math.max(1,Math.min(7000,(deadline-System.nanoTime())/1000000));}
    static URI validate(OfficialApiClient.Request request)throws Exception{
        URI uri=new URI(request.url);boolean registration="log.snssdk.com".equals(uri.getHost())&&"/service/2/device_register/".equals(uri.getRawPath())&&request.method.equals("POST");boolean api="api5-normal-lq.fqnovel.com".equals(uri.getHost())&&uri.getRawPath()!=null&&(uri.getRawPath().startsWith("/passport/")||uri.getRawPath().startsWith("/reading/")||uri.getRawPath().startsWith("/novel/"));
        if(!"https".equals(uri.getScheme())||uri.getRawUserInfo()!=null||uri.getPort()!=-1||uri.getRawFragment()!=null||!(registration||api)||!Arrays.asList("GET","POST").contains(request.method)||request.body.length>128*1024||request.method.equals("GET")&&request.body.length!=0)throw new IOException("红果账号服务地址或请求格式无效");
        if(request.url.length()>65536||request.url.indexOf('\r')>=0||request.url.indexOf('\n')>=0)throw new IOException("红果账号请求地址过长");return uri;
    }
    @Override public OfficialApiClient.Response execute(OfficialApiClient.Request request)throws Exception{
        if(!executing.compareAndSet(false,true))throw new IOException("红果账号请求正在处理中");owner=Thread.currentThread();Socket raw=null;long deadline=request.deadlineNanos;
        try{check(deadline);URI uri=validate(request);InetAddress[]addresses=NetworkGuard.publicAddresses(uri,deadline);IOException last=null;for(int i=0;i<Math.min(addresses.length,8);i++){check(deadline);Socket candidate=new Socket();active=candidate;try(AppTransport.SocketGuard ignored=AppTransport.guardSocket(candidate,deadline)){candidate.connect(new InetSocketAddress(addresses[i],443),timeout(deadline));check(deadline);raw=candidate;break;}catch(IOException error){candidate.close();check(deadline);last=error;}}
            if(raw==null)throw new IOException("红果账号服务连接失败",last);active=raw;
            try(AppTransport.SocketGuard ignored=AppTransport.guardSocket(raw,deadline);SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,uri.getHost(),443,true)){
                SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.setSoTimeout(timeout(deadline));tls.startHandshake();check(deadline);String path=uri.getRawPath();if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();StringBuilder message=new StringBuilder(request.method+" "+path+" HTTP/1.1\r\nHost: "+uri.getHost()+"\r\nAccept-Encoding: gzip\r\nConnection: close\r\n");int headerBytes=0;for(Map.Entry<String,String>h:request.headers.entrySet()){String key=h.getKey(),value=h.getValue();if(!key.matches("[A-Za-z0-9-]{1,128}")||value==null||value.length()>65536||controls(value)||Arrays.asList("host","content-length","transfer-encoding","connection","accept-encoding").contains(key.toLowerCase(Locale.ROOT)))throw new IOException("红果账号请求头无效");headerBytes+=key.length()+value.length();if(headerBytes>128*1024)throw new IOException("红果账号请求头过长");message.append(key).append(": ").append(value).append("\r\n");}if(request.method.equals("POST"))message.append("Content-Length: ").append(request.body.length).append("\r\n");message.append("\r\n");OutputStream output=tls.getOutputStream();output.write(message.toString().getBytes(StandardCharsets.UTF_8));if(request.method.equals("POST"))output.write(request.body);output.flush();InputStream input=new BufferedInputStream(tls.getInputStream());String status=line(input,8192);if(!status.matches("HTTP/1\\.[01] [0-9]{3}.*"))throw new IOException("红果账号响应状态无效");int code=Integer.parseInt(status.substring(9,12));Map<String,List<String>>headers=new LinkedHashMap<>();int total=status.length();for(;;){check(deadline);String row=line(input,16384);total+=row.length();if(total>65536)throw new IOException("红果账号响应头过长");if(row.isEmpty())break;int colon=row.indexOf(':');if(colon<1)throw new IOException("红果账号响应头无效");String key=row.substring(0,colon).toLowerCase(Locale.ROOT),value=row.substring(colon+1).trim();if(headers.containsKey(key)&&Arrays.asList("content-length","transfer-encoding","content-encoding","location","x-tt-token").contains(key))throw new IOException("红果账号响应头重复");headers.computeIfAbsent(key,k->new ArrayList<>()).add(value);}
                // Never follow a redirect carrying login data, even back to the same host.
                if(code!=200)return new OfficialApiClient.Response(code,headers,new byte[0]);
                Map<String,String>framing=new HashMap<>();for(Map.Entry<String,List<String>>e:headers.entrySet())framing.put(e.getKey(),e.getValue().get(0));framing.put("content-encoding","identity");ByteArrayOutputStream body=new ByteArrayOutputStream();NetworkGuard.streamBody(input,body,framing,OfficialApiClient.MAX_BYTES,deadline);check(deadline);return new OfficialApiClient.Response(code,headers,body.toByteArray());
            }
        }catch(Exception error){if(closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException("红果账号请求已取消");if(error instanceof SocketTimeoutException)throw new SocketTimeoutException("红果账号请求超时");throw new IOException("红果账号服务连接或响应失败，请稍后重试");}finally{if(raw!=null)try{raw.close();}catch(IOException ignored){}Socket pending=active;active=null;if(pending!=null&&pending!=raw)try{pending.close();}catch(IOException ignored){}owner=null;executing.set(false);}
    }
    private static boolean controls(String value){for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)==127)return true;return false;}
    private static String line(InputStream input,int max)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();while(out.size()<=max){int b=input.read();if(b<0)throw new EOFException("红果账号响应中断");if(b=='\n')return out.toString("US-ASCII").replaceFirst("\\r$","");out.write(b);}throw new IOException("红果账号响应行过长");}
}
