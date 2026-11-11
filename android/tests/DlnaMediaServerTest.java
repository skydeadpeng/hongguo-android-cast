package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Real loopback HTTP fixture only; no multicast, LAN scanning, emulator, or media service. */
public final class DlnaMediaServerTest {
    private static int checks;
    static final InetAddress LOOP;
    static { try { LOOP = InetAddress.getByName("127.0.0.1"); } catch (Exception error) { throw new ExceptionInInitializerError(error); } }
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    interface Throwing { void run() throws Exception; }
    static void rejected(Throwing action, String label) throws Exception { boolean rejected = false; try { action.run(); } catch (Exception expected) { rejected = true; } check(rejected, label); }
    static void until(Throwing check, long millis) throws Exception {
        long end = System.nanoTime() + millis * 1000000;
        while (true) {
            try { check.run(); return; } catch (AssertionError error) { if (System.nanoTime() >= end) throw error; Thread.sleep(10); }
        }
    }
    static final class Pins {
        final AtomicInteger live = new AtomicInteger(), acquired = new AtomicInteger(), released = new AtomicInteger();
        void retain() { acquired.incrementAndGet(); live.incrementAndGet(); }
        void release() { released.incrementAndGet(); if (live.decrementAndGet() < 0) throw new AssertionError("Unbalanced lease"); }
    }
    static DlnaMediaServer server(long header, long idle, long life) throws Exception {
        return new DlnaMediaServer(LOOP, LOOP, (bind, tv) -> {
            if (!bind.isLoopbackAddress() || !tv.isLoopbackAddress()) throw new java.io.IOException("Fixture loopback only");
        }, new DlnaMediaServer.Timeouts(header, idle, life));
    }
    static Socket socket(URI url) throws Exception {
        Socket socket = new Socket(); socket.setSoTimeout(4000); socket.setReceiveBufferSize(1024); socket.connect(new InetSocketAddress(LOOP, url.getPort()), 1000); return socket;
    }
    static void send(Socket socket, String value) throws Exception { socket.getOutputStream().write(value.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush(); }
    static final class Reply {
        final int status; final Map<String,String> headers = new HashMap<>(); final byte[] body;
        Reply(byte[] bytes) {
            String all = new String(bytes, StandardCharsets.ISO_8859_1); int split = all.indexOf("\r\n\r\n");
            if (split < 0) throw new AssertionError("HTTP response header missing");
            String[] lines = all.substring(0, split).split("\r\n"); status = Integer.parseInt(lines[0].split(" ")[1]);
            for (int i=1;i<lines.length;i++) { int colon=lines[i].indexOf(':'); headers.put(lines[i].substring(0,colon).toLowerCase(Locale.ROOT),lines[i].substring(colon+1).trim()); }
            body = Arrays.copyOfRange(bytes,split+4,bytes.length);
        }
    }
    static Reply request(URI url, String method, String extra) throws Exception {
        try (Socket socket = socket(url)) { String target=url.getRawPath()+(url.getRawQuery()==null?"":"?"+url.getRawQuery());send(socket, method+" "+target+" HTTP/1.1\r\nHost: 127.0.0.1\r\n"+extra+"\r\n"); return new Reply(socket.getInputStream().readAllBytes()); }
    }
    static String readHeader(Socket socket) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); InputStream in=socket.getInputStream(); int state=0; byte[] end={'\r','\n','\r','\n'};
        while(state<4) { int b=in.read(); if(b<0)throw new AssertionError("Early HTTP close");out.write(b);state=b==end[state]?state+1:(b=='\r'?1:0); }
        return out.toString("US-ASCII");
    }
    public static void main(String[] args) throws Exception {
        File directory=Files.createTempDirectory("hongguo-dlna-media-test-").toFile(); File file=new File(directory,"ready.mp4");
        byte[] content=new byte[1000];for(int i=0;i<content.length;i++)content[i]=(byte)(i%251);Files.write(file.toPath(),content);
        try {
            check(DlnaMediaServer.privateIpv4(InetAddress.getByName("10.2.3.4")),"10/8 accepted");
            check(DlnaMediaServer.privateIpv4(InetAddress.getByName("172.16.0.1"))&&DlnaMediaServer.privateIpv4(InetAddress.getByName("172.31.255.255")),"172.16/12 accepted");
            check(DlnaMediaServer.privateIpv4(InetAddress.getByName("192.168.1.1")),"192.168/16 accepted");
            for(String value:new String[]{"127.0.0.1","0.0.0.0","8.8.8.8","172.15.0.1","172.32.0.1","169.254.1.1","224.0.0.1","::1"})check(!DlnaMediaServer.privateIpv4(InetAddress.getByName(value)),"non-LAN rejected "+value);
            rejected(()->new DlnaMediaServer(LOOP,LOOP),"production constructor rejects loopback fixture");
            rejected(()->new DlnaMediaServer(LOOP,InetAddress.getByName("8.8.8.8")),"production rejects public receiver");
            Pins pins=new Pins();
            try(DlnaMediaServer server=server(1000,1500,10000)) {
                DlnaMediaServer.Publication item=server.publish(file,"arbitrary title\r\nignored",pins::retain,pins::release);URI url=URI.create(item.url);
                check(url.getScheme().equals("http")&&url.getHost().equals("127.0.0.1")&&url.getPort()>0,"bound URL dynamic HTTP port");
                check(url.getPath().matches("/media/[a-f0-9]{64}\\.mp4"),"256-bit unguessable bearer path");check(pins.live.get()==1,"publication pins before first TV read");
                Reply full=request(url,"GET","");check(full.status==200&&Arrays.equals(full.body,content),"full GET exact registered bytes");
                check(full.headers.get("content-type").equals("video/mp4")&&full.headers.get("content-length").equals("1000"),"MP4 MIME and complete Content-Length");
                check(full.headers.get("accept-ranges").equals("bytes")&&full.headers.get("connection").equals("close"),"bounded one-request connections and byte ranges");
                check(full.headers.get("contentfeatures.dlna.org").contains("DLNA.ORG_OP=01")&&full.headers.get("transfermode.dlna.org").equals("Streaming"),"DLNA feature and transfer headers");
                Reply head=request(url,"HEAD","getcontentFeatures.dlna.org: 1\r\ntransferMode.dlna.org: Streaming\r\n");
                check(head.status==200&&head.body.length==0&&head.headers.get("content-length").equals("1000"),"HEAD declares complete size and sends no body");
                String[] ranges={"bytes=0-9","bytes=990-","bytes=-10","bytes=-9999","bytes=995-9999","bytes=0-0"};long[][] expected={{0,9},{990,999},{990,999},{0,999},{995,999},{0,0}};
                for(int i=0;i<ranges.length;i++) { Reply reply=request(url,"GET","Range: "+ranges[i]+"\r\n");int start=(int)expected[i][0],end=(int)expected[i][1];check(reply.status==206&&Arrays.equals(reply.body,Arrays.copyOfRange(content,start,end+1)),"single range exact "+ranges[i]);check(reply.headers.get("content-range").equals("bytes "+start+"-"+end+"/1000")&&Long.parseLong(reply.headers.get("content-length"))==end-start+1,"Content-Range/Length "+ranges[i]); }
                Reply rangedHead=request(url,"HEAD","Range: bytes=10-19\r\n");check(rangedHead.status==206&&rangedHead.body.length==0&&rangedHead.headers.get("content-length").equals("10"),"HEAD range no bytes");
                for(String value:new String[]{"bytes=1000-","bytes=20-10","bytes=-0","bytes=0-1,3-4","bytes=9223372036854775808-","items=0-1","bytes=abc-"}) {Reply bad=request(url,"GET","Range: "+value+"\r\n");check(bad.status==416&&bad.headers.get("content-range").equals("bytes */1000")&&bad.body.length==0,"invalid/multiple range rejected "+value);}
                check(request(url,"POST","").status==405,"unsupported method rejected");
                check(request(url,"GET","Range: bytes=0-1\r\nRange: bytes=2-3\r\n").status==400,"duplicate headers rejected");
                check(request(url,"GET","Transfer-Encoding: chunked\r\n").status==400,"request body transfer encoding rejected");
                URI traversal=URI.create(item.url.substring(0,item.url.indexOf("/media/"))+"/../ready.mp4");check(request(traversal,"GET","").status==404,"no filesystem path lookup or directory traversal");
                URI encoded=URI.create(item.url+"?file=../../ready.mp4");check(request(encoded,"GET","").status==404,"query cannot map a file");
                check(request(URI.create(item.url.substring(0,item.url.indexOf("/media/"))+"/"),"GET","").status==404,"directory listing absent");
                try(Socket oversized=socket(url)) {send(oversized,"GET "+url.getPath()+" HTTP/1.1\r\nX-Long: "+"x".repeat(9000)+"\r\n\r\n");boolean denied=false;try{denied=readHeader(oversized).contains(" 431 ");}catch(java.io.IOException expectedError){denied=true;}check(denied,"overlong header rejected by 431 or immediate reset");}
                try(DlnaMediaServer mismatch=new DlnaMediaServer(LOOP,InetAddress.getByName("127.0.0.2"),(bind,tv)->{if(!bind.isLoopbackAddress()||!tv.isLoopbackAddress())throw new java.io.IOException("Fixture only");})) {
                    DlnaMediaServer.Publication foreign=mismatch.publish(file,"peer",pins::retain,pins::release);URI deniedUrl=URI.create(foreign.url);
                    try(Socket wrong=socket(deniedUrl)){boolean disconnected=false;try{send(wrong,"GET "+deniedUrl.getPath()+" HTTP/1.1\r\nHost: fixture\r\n\r\n");disconnected=wrong.getInputStream().read()<0;}catch(java.io.IOException expectedError){disconnected=true;}check(disconnected,"immutable selected peer exact source IPv4 only");}
                }
                rejected(()->server.publish(directory,"directory",pins::retain,pins::release),"directory not publishable");
                File empty=new File(directory,"empty.mp4");Files.write(empty.toPath(),new byte[0]);rejected(()->server.publish(empty,"empty",pins::retain,pins::release),"empty incomplete MP4 rejected");
                File wrongType=new File(directory,"arbitrary.txt");Files.write(wrongType.toPath(),content);rejected(()->server.publish(wrongType,"wrong",pins::retain,pins::release),"non-MP4 not publishable");
                File symbolic=new File(directory,"symbolic.mp4");Files.createSymbolicLink(symbolic.toPath(),file.toPath());rejected(()->server.publish(symbolic,"symlink",pins::retain,pins::release),"symbolic media rejected");Files.delete(symbolic.toPath());
                until(()->check(pins.live.get()==1,"every completed request releases its own lease"),1000);
                server.revoke(item);server.revoke(item);check(pins.live.get()==0,"revoke is idempotent and releases publication pin");check(request(url,"GET","").status==404,"revoked endpoint not reusable");
                DlnaMediaServer.Publication changed=server.publish(file,"mutated",pins::retain,pins::release);Files.write(file.toPath(),new byte[999]);check(request(URI.create(changed.url),"GET","").status==410,"registered file size mutation aborts");server.revoke(changed);Files.write(file.toPath(),content);
            }
            check(pins.live.get()==0&&pins.acquired.get()==pins.released.get(),"all completed/error leases balanced");
            // A registered old URL can be revoked while its already-open response stays pinned.
            File large=new File(directory,"large.mp4");try(RandomAccessFile out=new RandomAccessFile(large,"rw")){out.setLength(32L*1024*1024);}
            Pins longPins=new Pins();DlnaMediaServer active=server(1500,3000,10000);DlnaMediaServer.Publication old=active.publish(large,"old",longPins::retain,longPins::release);URI oldUrl=URI.create(old.url);Socket opened=socket(oldUrl);send(opened,"GET "+oldUrl.getPath()+" HTTP/1.1\r\nHost: fixture\r\n\r\n");check(readHeader(opened).contains("200 OK"),"large media response started");
            until(()->check(longPins.live.get()==2,"active response owns independent lease"),500);active.revoke(old);check(longPins.live.get()==1,"revoke keeps already-open response protected");
            DlnaMediaServer.Publication next=active.publish(file,"next",longPins::retain,longPins::release);check(request(URI.create(next.url),"GET","").status==200,"new episode URL works while old request remains open");check(request(oldUrl,"GET","").status==404,"new request cannot reacquire revoked old episode");
            check(opened.getInputStream().read()>=0,"already-open response remains readable after revoke");active.close();active.close();check(longPins.live.get()==0&&longPins.acquired.get()==longPins.released.get(),"close cancels active sockets and synchronously balances all leases");opened.close();check(large.exists()&&file.exists(),"server never deletes manual or prepared files");rejected(()->active.publish(file,"closed",longPins::retain,longPins::release),"closed server rejects publication");
            // Header slowloris, bounded queue, and a non-reading receiver get finite cleanup.
            Pins boundedPins=new Pins();try(DlnaMediaServer bounded=server(300,400,2500)) {
                DlnaMediaServer.Publication boundedItem=bounded.publish(large,"slow",boundedPins::retain,boundedPins::release);URI url=URI.create(boundedItem.url);
                try(Socket slow=socket(url)) {send(slow,"GET ");Thread.sleep(650);boolean gone=false;try{gone=slow.getInputStream().read()<0;}catch(java.io.IOException expected){gone=true;}check(gone,"absolute header deadline closes slow sender");}
                List<Socket> blocked=new ArrayList<>();for(int i=0;i<22;i++){Socket s=socket(url);blocked.add(s);try{send(s,"GET ");}catch(java.io.IOException ignored){}}
                Thread.sleep(700);int closed=0;for(Socket s:blocked){try{if(s.getInputStream().read()<0)closed++;}catch(java.io.IOException ignored){closed++;}s.close();}check(closed==22,"bounded workers and queue reject or expire all excess clients");
                try(Socket noRead=socket(url)) {send(noRead,"GET "+url.getPath()+" HTTP/1.1\r\nHost: fixture\r\n\r\n");check(readHeader(noRead).contains("200 OK"),"slow-reading fixture acquired stream");until(()->check(boundedPins.live.get()==1,"write-idle watchdog releases stalled receiver lease"),1800);}
                bounded.revoke(boundedItem);check(boundedPins.live.get()==0,"watchdog plus revoke leaves no protection leak");
            }
            // Concurrent readers may finish in either order without dropping publication protection.
            Pins parallel=new Pins();try(DlnaMediaServer concurrent=server(1500,3000,10000)) {
                DlnaMediaServer.Publication item=concurrent.publish(large,"parallel",parallel::retain,parallel::release);URI url=URI.create(item.url);List<Socket> reads=new ArrayList<>();
                for(int i=0;i<4;i++){Socket s=socket(url);send(s,"GET "+url.getPath()+" HTTP/1.1\r\nHost: fixture\r\n\r\n");readHeader(s);reads.add(s);}until(()->check(parallel.live.get()==5,"four concurrent requests plus publication all protected"),500);concurrent.revoke(item);check(parallel.live.get()==4,"revoking owner keeps every concurrent reader lease");
                for(Socket s:reads)s.close();until(()->check(parallel.live.get()==0&&parallel.acquired.get()==parallel.released.get(),"parallel receiver disconnects balance every lease"),1500);
            }
            Pins capacity=new Pins();try(DlnaMediaServer boundedPublications=server(1500,3000,10000)) {
                List<DlnaMediaServer.Publication> items=new ArrayList<>();for(int i=0;i<8;i++)items.add(boundedPublications.publish(file,"item",capacity::retain,capacity::release));
                check(items.stream().map(item->item.url).distinct().count()==8,"each publication uses a fresh independent bearer");
                rejected(()->boundedPublications.publish(file,"overflow",capacity::retain,capacity::release),"publication registry is bounded at eight");
                check(capacity.live.get()==8,"rejected publication does not acquire a lease");
            }
            check(capacity.live.get()==0&&capacity.acquired.get()==capacity.released.get(),"close revokes every idle registered publication");
            System.out.println("PASS DlnaMediaServerTest checks="+checks+" actualServerSocket=true loopbackOnly=true range=true concurrentLeases=true boundedClients=true noLanScan=true deviceUntouched=true");
        } finally { try(java.util.stream.Stream<java.nio.file.Path> entries=Files.walk(directory.toPath())) {entries.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});} }
    }
}
