package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Session -> real cache -> real range HTTP -> fake selected renderer boundary. */
public final class DlnaIntegrationTest {
    static int checks;
    static final String SID="synthetic-series";
    static final long BUDGET=256L*1024*1024,MAX_FILE=96L*1024*1024;
    static final int FILE_BYTES=1024*1024;
    static final InetAddress LOOP;
    static {try{LOOP=InetAddress.getByName("127.0.0.1");}catch(Exception error){throw new ExceptionInInitializerError(error);}}
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void await(BooleanSupplier condition,String label)throws Exception{long end=System.nanoTime()+2500000000L;while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);check(condition.getAsBoolean(),label);}
    static final class Job implements DlnaSession.Task {final Runnable action;boolean cancelled;Job(Runnable action){this.action=action;}public void cancel(){cancelled=true;}void run(){if(!cancelled)action.run();}}
    static final class Clock implements DlnaSession.Scheduler {
        final Deque<Runnable> serial=new ArrayDeque<>();final Deque<Job> preparations=new ArrayDeque<>();final List<Job> repeated=new ArrayList<>();long now;boolean closed;
        public boolean execute(Runnable action){if(closed)return false;serial.add(action);return true;}
        public DlnaSession.Task prepare(Runnable action){Job job=new Job(action);preparations.add(job);return job;}
        public DlnaSession.Task repeat(Runnable action,long delay){Job job=new Job(action);repeated.add(job);return job;}
        public long nowMs(){return now;}
        public void close(){closed=true;serial.clear();for(Job job:preparations)job.cancel();for(Job job:repeated)job.cancel();}
        void drain(){int steps=0;while(!serial.isEmpty()){if(++steps>1000)throw new AssertionError("Unbounded serial loop");serial.removeFirst().run();}}
        void prepareNext(){check(!preparations.isEmpty(),"preparation is explicit separate work");preparations.removeFirst().run();drain();}
        void tick(){now+=1000;for(Job job:new ArrayList<>(repeated))if(!job.cancelled)execute(job.action);drain();}
    }
    static final class Receiver implements DlnaSession.Controller {
        String requested="",uri="",state="STOPPED";long position,duration=100000;int sets,plays,stops;
        public void setMedia(String value,String title){check(value.startsWith("http://127.0.0.1:"),"renderer receives only fixture local HTTP URL");requested=value;sets++;}
        public void play(){plays++;}
        public void pause(){state="PAUSED_PLAYBACK";}
        public void stop(){stops++;state="STOPPED";}
        public void seek(long value){position=value;}
        public DlnaSession.Status status(){return new DlnaSession.Status(state,uri,position,duration);}
        void confirm(){uri=requested;state="PLAYING";position=0;}
        void nearEnd(){state="PLAYING";position=99000;}
        void ended(){state="STOPPED";position=0;}
    }
    static final class PreparedCache implements DlnaSession.Source {
        final PlaybackCachePolicy cache;final File[] files=new File[4];final AtomicInteger[] leases=new AtomicInteger[4];
        final List<Integer> prepared=new ArrayList<>(),confirmed=new ArrayList<>();final List<String> requestedQualities=new ArrayList<>();int downloads,progressWrites;volatile int latestConfirmed=-1;
        PreparedCache(File directory){for(int i=0;i<4;i++)leases[i]=new AtomicInteger();cache=new PlaybackCachePolicy(directory,BUDGET,MAX_FILE,(url,destination,max)->download(Integer.parseInt(url.substring(url.lastIndexOf(':')+1)),destination));}
        String vid(int position){return "synthetic-video-"+position+"-720p";}
        void retain(int position){cache.protect(SID,vid(position),position);leases[position].incrementAndGet();}
        void release(int position){cache.release(SID,vid(position),position);if(leases[position].decrementAndGet()<0)throw new AssertionError("Negative cache pin");int latest=latestConfirmed;if(latest>=0)try{cache.prune(SID,latest,1);}catch(IOException ignored){}}
        void download(int position,File destination)throws Exception{downloads++;byte[] chunk=new byte[4096];Arrays.fill(chunk,(byte)(position+17));chunk[0]=0;chunk[1]=0;chunk[2]=0;chunk[3]=24;chunk[4]='f';chunk[5]='t';chunk[6]='y';chunk[7]='p';try(FileOutputStream out=new FileOutputStream(destination)){for(int written=0;written<FILE_BYTES;written+=chunk.length)out.write(chunk);}}
        public DlnaSession.Prepared prepare(int position,String quality,BooleanSupplier alive)throws Exception{
            if(!alive.getAsBoolean())throw new InterruptedIOException("Cancelled fixture");retain(position);boolean success=false;
            try{File file=cache.fetchForeground(SID,vid(position),position,"fixture:"+position,(url,destination,max)->download(position,destination));if(!alive.getAsBoolean())throw new InterruptedIOException("Cancelled fixture");files[position]=file;prepared.add(position);requestedQualities.add(quality);success=true;return new DlnaSession.Prepared(file,"Synthetic episode "+(position+1),"720p",position+1,()->retain(position),()->release(position));}
            finally{if(!success)release(position);}
        }
        public void confirmed(int position,long progress,boolean cleanup)throws Exception{confirmed.add(position);latestConfirmed=position;if(cleanup)cache.prune(SID,position,1);}
        public void progress(int position,long progress){progressWrites++;}
        int totalPins(){int total=0;for(AtomicInteger value:leases)total+=value.get();return total;}
    }
    static final class HttpServer implements DlnaSession.Server {
        final DlnaMediaServer media;int revoked;
        HttpServer()throws Exception{media=new DlnaMediaServer(LOOP,LOOP,(bind,tv)->{if(!bind.isLoopbackAddress()||!tv.isLoopbackAddress())throw new IOException("Fixture only");},new DlnaMediaServer.Timeouts(3000,10000,30000));}
        public DlnaSession.Publication publish(DlnaSession.Prepared prepared)throws Exception{DlnaMediaServer.Publication item=media.publish(prepared.file,prepared.title,prepared.retain,prepared.release);return new DlnaSession.Publication(item.url,item);}
        public void revoke(DlnaSession.Publication item){revoked++;media.revoke((DlnaMediaServer.Publication)item.handle);}
        public void close(){media.close();}
    }
    static Socket connect(String value)throws Exception{URI uri=URI.create(value);Socket socket=new Socket();socket.setReceiveBufferSize(1024);socket.setSoTimeout(3000);socket.connect(new InetSocketAddress(LOOP,uri.getPort()),1000);return socket;}
    static String header(Socket socket)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] end={'\r','\n','\r','\n'};int state=0;InputStream input=socket.getInputStream();while(state<4){int b=input.read();if(b<0)throw new IOException("Missing response");out.write(b);state=b==end[state]?state+1:(b=='\r'?1:0);}return out.toString("US-ASCII");}
    static void send(Socket socket,String url,String range)throws Exception{String request="GET "+URI.create(url).getRawPath()+" HTTP/1.1\r\nHost: loopback-fixture\r\n"+(range==null?"":"Range: "+range+"\r\n")+"\r\n";socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();}
    static void verifyRange(String url,File expected)throws Exception{try(Socket socket=connect(url)){send(socket,url,"bytes=100-163");String headers=header(socket);byte[] body=socket.getInputStream().readAllBytes();check(headers.contains("206 Partial Content")&&headers.contains("Content-Range: bytes 100-163/"+FILE_BYTES),"actual media HTTP reports precise range");byte[] file=Files.readAllBytes(expected.toPath());check(Arrays.equals(body,Arrays.copyOfRange(file,100,164)),"renderer receives exact real cached MP4 byte range");}}
    static Socket held(String url)throws Exception{Socket socket=connect(url);send(socket,url,null);check(header(socket).contains("200 OK"),"actual old episode streaming response opened");return socket;}
    static int status(String url)throws Exception{try(Socket socket=connect(url)){send(socket,url,"bytes=0-0");return Integer.parseInt(header(socket).split(" ")[1]);}}
    static void endAndPrepare(Clock clock,Receiver receiver,DlnaSession session,int expected)throws Exception{receiver.nearEnd();clock.tick();receiver.ended();clock.tick();check(session.snapshot().preparing&&session.snapshot().targetCurrent==expected,"only confirmed near-end STOPPED prepares the next episode");clock.prepareNext();check(session.snapshot().targetCurrent==expected&&session.snapshot().current==expected-1,"unconfirmed next URI does not move current episode");}
    static void confirm(Clock clock,Receiver receiver,DlnaSession session,int expected){receiver.confirm();clock.tick();check(session.snapshot().current==expected&&session.snapshot().playing&&!session.snapshot().preparing,"matching URI plus PLAYING commits episode "+(expected+1));}
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("hongguo-dlna-integration-").toFile();File directory=new File(root,"playback");check(directory.mkdirs(),"isolated real cache directory created");File manual=new File(directory,"manual-user-download.mp4");byte[] sentinel="owned-manual-file-must-remain".getBytes(StandardCharsets.UTF_8);Files.write(manual.toPath(),sentinel);
        Clock clock=new Clock();Receiver receiver=new Receiver();PreparedCache source=new PreparedCache(directory);HttpServer server=new HttpServer();List<DlnaSession.Snapshot> snapshots=new ArrayList<>();DlnaSession session=new DlnaSession(clock,receiver,source,server,snapshots::add,SID,"Synthetic series","Loopback fake TV",4,"1080p",true,true);List<Socket> held=new ArrayList<>();
        try{
            session.start(0,0);clock.drain();check(session.snapshot().preparing&&source.downloads==0,"start queues source preparation without HTTP or skipped work");clock.prepareNext();String first=receiver.requested;check(source.confirmed.isEmpty()&&!session.snapshot().playing,"setting TV URI alone never confirms playback");verifyRange(first,source.files[0]);confirm(clock,receiver,session,0);check(session.snapshot().preferredQuality.equals("1080p")&&session.snapshot().actualQuality.equals("720p"),"preferred quality remains distinct from effective source");
            Socket firstRead=held(first);held.add(firstRead);await(()->source.leases[0].get()==2,"current publication plus actual HTTP read both pin cache");
            endAndPrepare(clock,receiver,session,1);String second=receiver.requested;check(!first.equals(second)&&status(first)==206,"old URL remains available while new TV URI is pending");confirm(clock,receiver,session,1);check(status(first)==404,"committed new URI revokes old endpoint");check(source.files[0].exists()&&source.leases[0].get()==1,"already-open old response stays pinned after URI transition");verifyRange(second,source.files[1]);Socket secondRead=held(second);held.add(secondRead);await(()->source.leases[1].get()==2,"current second episode acquires independent read pin");
            endAndPrepare(clock,receiver,session,2);String third=receiver.requested;confirm(clock,receiver,session,2);check(source.files[0].exists(),"actual prune retains out-of-window file while receiver still reads");check(source.files[1].exists(),"previous episode retained in rolling window");verifyRange(third,source.files[2]);firstRead.close();await(()->source.leases[0].get()==0,"revoked old read eventually releases cache lease");await(()->!source.files[0].exists(),"reader release immediately prunes the now-unpinned old file");Socket thirdRead=held(third);held.add(thirdRead);await(()->source.leases[2].get()==2,"third episode read pins the soon-to-be previous episode");
            endAndPrepare(clock,receiver,session,3);String fourth=receiver.requested;confirm(clock,receiver,session,3);verifyRange(fourth,source.files[3]);check(source.prepared.equals(Arrays.asList(0,1,2,3))&&source.confirmed.equals(Arrays.asList(0,1,2,3)),"real source and cache confirmed every episode in order without skipping");check(source.downloads==4,"one prepared local file per episode");check(source.requestedQualities.equals(Arrays.asList("1080p","720p","720p","720p")),"continuous session carries effective fallback while preserving preference");check(source.cache.status().bytes<=BUDGET&&source.cache.status().files==3,"real 256 MiB policy remains bounded and prunes old episode");check(source.cache.clear().removed==0&&source.files[1].exists()&&source.files[2].exists()&&source.files[3].exists(),"old active read, previous read, and current publication survive real cache clear");
            receiver.nearEnd();clock.tick();receiver.ended();clock.tick();check(session.snapshot().current==3&&session.snapshot().state.equals("ENDED")&&source.downloads==4,"fourth episode ends without phantom fifth preparation");session.stop();clock.drain();check(receiver.stops==1&&!session.snapshot().active&&clock.closed,"confirmed stop closes actual session and scheduler");check(source.totalPins()==0,"session close cancels HTTP and balances prepared/publication/read leases");check(!source.files[1].exists()&&source.cache.status().files==2,"stop release callbacks immediately prune stale held second episode");int removed=source.cache.clear().removed;check(removed==2&&source.cache.status().files==0,"unprotected remaining rolling files become clearable after stop");check(Arrays.equals(Files.readAllBytes(manual.toPath()),sentinel),"manual file sentinel survives prune, clear, and session close");check(server.revoked>=3&&snapshots.size()>8,"actual session emitted transitions and revoked committed old URLs");
            System.out.println("DLNA_INTEGRATION_OK checks="+checks+" actualSession=true actualMediaServer=true actualPlaybackCache=true episodes=4 rangeBytesVerified=true cacheBudgetMiB=256 pinnedReadsPreserved=true manualDataUnchanged=true loopbackOnly=true noLanScan=true deviceTests=not_run");
        }finally{session.close();server.close();for(Socket socket:held)try{socket.close();}catch(Exception ignored){}try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(root.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
    }
}
