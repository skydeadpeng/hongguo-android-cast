package io.github.jackotom.hongguo.android;

import java.io.File;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Actual session; deterministic state/intent boundary and real cache/HTTP only on loopback. */
public final class DlnaPausedEofCompatibilityTest {
    private static final String ZEEKR="Zeekr-8507_投屏";
    private static final long END=66000;
    private static int checks,passed,failed;
    private interface Checked {void run()throws Exception;}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void group(String name,Checked test){try{test.run();passed++;System.out.println("PASS PausedEof "+name);}catch(Throwable error){failed++;System.out.println("FAIL PausedEof "+name+": "+error.getMessage());}}
    static final class Receiver implements DlnaSession.Controller {
        final DlnaSessionTest.Control delegate=new DlnaSessionTest.Control();Runnable onStatus;long initialPosition;
        public void setMedia(String url,String title)throws Exception{delegate.setMedia(url,title);}
        public void play(){delegate.play();if(delegate.plays==1)delegate.position=initialPosition;}
        public void pause(){delegate.pause();}public void stop()throws Exception{delegate.stop();}public void seek(long ms){delegate.seek(ms);}
        public DlnaSession.Status status()throws Exception{DlnaSession.Status captured=delegate.status();Runnable hook=onStatus;onStatus=null;if(hook!=null)hook.run();return captured;}
    }
    static final class F implements AutoCloseable {
        final DlnaSessionTest.Clock clock=new DlnaSessionTest.Clock();final DlnaSessionTest.Source source=new DlnaSessionTest.Source();final DlnaSessionTest.Server server=new DlnaSessionTest.Server();final Receiver receiver=new Receiver();final List<DlnaSession.Snapshot>snapshots=new ArrayList<>();final DlnaSession session;final int selected;
        F(){this(ZEEKR,10,7,0,0);}F(String name,int count,int selected,long resume,long initial){this.selected=selected;receiver.delegate.duration=END;receiver.initialPosition=initial;session=new DlnaSession(clock,receiver,source,server,snapshots::add,"123456","合成66秒剧集",name,count,"1080p",true,true);session.start(selected,resume);clock.prepare(0,false);}
        void sample(String state,long position,long duration){receiver.delegate.state=state;receiver.delegate.position=position;receiver.delegate.duration=duration;clock.tick();}
        void arm(){sample("PLAYING",64000,END);sample("PLAYING",65000,END);}
        void paused(){sample("PAUSED_PLAYBACK",END,END);}void three(){for(int i=0;i<3;i++)paused();}
        void noNext(String label){check(clock.preparations.size()==1&&!session.snapshot().preparing,label);}
        void next(String label){check(clock.preparations.size()==2&&session.snapshot().preparing&&session.snapshot().current==selected&&session.snapshot().targetCurrent==selected+1,label);}
        public void close(){session.close();for(DlnaSessionTest.Media media:source.media)check(media.references==0,"all fake publication/read leases balance on close");}
    }
    private static void positionSevenToEightAndTimeGate(){
        try(F f=new F()){
            check(f.session.snapshot().episodeIndex==8&&f.session.snapshot().current==7,"reported current7 means zero-based position7, episode8");f.arm();f.paused();f.noNext("first exact66/66 pause does not advance");f.paused();f.noNext("second exact66/66 pause still does not advance");f.paused();f.next("third pause over two seconds prepares position8 episode9 exactly once");
            for(int i=0;i<6;i++)f.paused();check(f.clock.preparations.size()==2,"persistent paused EOF never duplicates the pending next episode");check(f.source.confirmed.equals(Arrays.asList(7))&&f.server.live.size()==1,"EOF detection itself neither confirms next media nor revokes current");f.clock.prepare(1,false);check(f.session.snapshot().current==8&&f.session.snapshot().episodeIndex==9&&f.source.confirmed.equals(Arrays.asList(7,8)),"only next matching URI PLAYING adopts position8 episode9");check(f.source.qualities.equals(Arrays.asList("1080p","1080p")),"quality preference and source preparation contract unchanged");
        }
        try(F f=new F()){
            f.arm();f.receiver.delegate.state="PAUSED_PLAYBACK";f.receiver.delegate.position=END;
            for(int i=0;i<3;i++)for(DlnaSessionTest.Job job:f.clock.polls)job.action.run();f.noNext("three repeated samples at one instant do not satisfy time gate");f.paused();f.noNext("only one elapsed second is insufficient even after repeated samples");f.paused();f.next("two elapsed seconds plus at least three valid samples confirms EOF");
        }
    }
    private static void exactProfileOnly(){
        for(String name:Arrays.asList("普通电视", "Zeekr", "Zeekr-8507", "FakeZeekr-8507_投屏", "Zeekr-8507_投屏 extra", "Zeekr-_投屏"))try(F f=new F(name,10,6,0,0)){f.arm();for(int i=0;i<6;i++)f.paused();f.noNext("nonprofile receiver pause remains a pause: "+name);f.sample("STOPPED",0,0);f.sample("STOPPED",0,0);f.noNext("nonprofile pause still invalidates ordinary terminal evidence");}
        try(F f=new F("zeekr-8507_投屏",10,6,0,0)){f.arm();f.three();f.next("known Zeekr profile is case insensitive, not dependent on synthetic fixture title");}
    }
    private static void strictRawTiming(){
        for(long[] shape:new long[][]{{END-1,END},{END+1,END},{END,0},{0,END},{0,0},{END+1000,END+1000}})try(F f=new F()){f.arm();for(int i=0;i<5;i++)f.sample("PAUSED_PLAYBACK",shape[0],shape[1]);f.noNext("only stable raw exact EOF qualifies: "+Arrays.toString(shape));}
        try(F f=new F()){f.arm();f.clock.now+=16000;f.three();f.noNext("stale progress older than15seconds cannot prove EOF");}
        try(F f=new F()){f.arm();f.clock.now=0;f.three();f.noNext("backwards monotonic-clock samples cannot use future progress evidence");}
        try(F f=new F(ZEEKR,10,6,0,END)){for(int i=0;i<5;i++)f.sample("PLAYING",END,END);f.three();f.noNext("initially adopted/fixed PLAYING at EOF never fabricates forward playback");}
        try(F f=new F()){f.sample("PLAYING",65000,END);f.sample("PLAYING",END+1,END);f.three();f.noNext("out-of-range PLAYING invalidates earlier progress");}
    }
    private static void interruptedSamples(){
        for(String state:Arrays.asList("STOPPED","NO_MEDIA_PRESENT","TRANSITIONING","UNKNOWN","BUFFERING","PAUSED_RECORDING","RECORDING","PAUSED_PLAYBACK"))try(F f=new F()){f.arm();f.paused();f.sample(state,1000,END);f.three();f.noNext("intervening state/early pause breaks consecutive EOF proof: "+state);}
        try(F f=new F()){f.arm();f.paused();f.receiver.delegate.failStatus=true;f.clock.tick();f.receiver.delegate.failStatus=false;f.three();f.noNext("one status exception destroys paused EOF proof, not just after disconnect");}
        for(String foreign:Arrays.asList("", "http://192.168.1.9/foreign.mp4"))try(F f=new F()){f.arm();f.paused();String own=f.receiver.delegate.uri;f.receiver.delegate.uri=foreign;f.paused();f.receiver.delegate.uri=own;f.three();f.noNext("empty/foreign paused URI never completes another publication");}
        try(F f=new F()){f.arm();f.paused();f.sample("PAUSED_PLAYBACK",67000,67000);f.three();f.noNext("duration change breaks proof even when previous duration returns");}
    }
    private static void explicitPauseAndSeek(){
        try(F f=new F()){f.arm();f.session.pause();f.three();f.noNext("explicit phone pause wins even if receiver reports exact EOF");check(f.receiver.delegate.pauses==1,"pause is not silently converted into another Play");}
        try(F f=new F()){f.arm();f.session.seek(END);for(int i=0;i<5;i++)f.sample("PLAYING",END,END);f.three();f.noNext("seek to end plus fixed PLAYING does not count as natural forward progress");f.session.seek(63000);f.sample("PLAYING",64000,END);f.sample("PLAYING",65000,END);f.three();f.next("actual new postseek forward progress rearms later EOF");}
        try(F f=new F()){f.arm();f.session.seek(64000);f.sample("PLAYING",END,END);f.three();f.noNext("postseek first sample at EOF is not fresh forward evidence");}
    }
    private static void automaticResumeSeek(){
        try(F f=new F(ZEEKR,10,6,64000,0)){check(f.receiver.delegate.sought.equals(Arrays.asList(64000L)),"initial resume actually performs native control seek");for(int i=0;i<5;i++)f.sample("PLAYING",END,END);f.three();f.noNext("adopt resume-seek cannot use preseek anchor to arm fixed EOF");}
        try(F f=new F(ZEEKR,10,6,64000,0)){f.sample("PLAYING",65000,END);f.three();f.noNext("one postresume PLAYING sample is an anchor, not two-point progress");}
        try(F f=new F(ZEEKR,10,6,62000,0)){f.sample("PLAYING",63000,END);f.sample("PLAYING",64000,END);f.sample("PLAYING",65000,END);f.three();f.next("resume remains compatible once actual forward playback is reobserved");}
    }
    private static void autoplayLastAndFailure(){
        try(F f=new F()){f.arm();f.session.setAutoplay(false);f.three();f.noNext("disabled autoplay never prepares the next episode");check(!f.session.snapshot().autoplay,"autoplay preference remains off");}
        try(F f=new F(ZEEKR,10,9,0,0)){f.arm();f.three();check(f.clock.preparations.size()==1&&f.session.snapshot().current==9&&"ENDED".equals(f.session.snapshot().state),"last episode completes without phantom next or wraparound");for(int i=0;i<6;i++)f.paused();check(f.clock.preparations.size()==1,"last repeated pause never prepares an eleventh episode");}
        try(F f=new F()){f.arm();f.source.fail=8;f.three();f.next("EOF requests only immediate next position");f.clock.prepare(1,false);check(!f.session.snapshot().preparing&&f.session.snapshot().current==7&&f.session.snapshot().targetCurrent==8&&!f.session.snapshot().error.isEmpty(),"next-source failure stays retryable on position8 episode9 while position7 episode8 stays current");for(int i=0;i<8;i++)f.paused();check(f.clock.preparations.size()==2&&f.server.live.size()==1&&f.source.confirmed.equals(Arrays.asList(7)),"failed next does not skip, retry forever, revoke or prune the current publication");}
    }
    private static void intent(F f,String mode){switch(mode){case "pause":f.session.pause();break;case "seek":f.session.seek(20000);break;case "off":f.session.setAutoplay(false);break;case "select":f.session.select(9);break;case "stop":f.session.stop();break;case "play":f.session.play();break;default:throw new AssertionError(mode);}}
    private static void inspectIntent(F f,String mode){
        if(mode.equals("select")){check(f.clock.preparations.size()==2&&f.session.snapshot().targetCurrent==9,"queued manual selection alone owns position9 episode10, not automatic position8 episode9");}
        else {check(f.clock.preparations.size()==1,"queued "+mode+" prevents any automatic next-source job");if(mode.equals("off"))check(!f.session.snapshot().autoplay,"queued autoplay-off applies");if(mode.equals("seek"))check(f.receiver.delegate.sought.contains(20000L),"queued user seek applies to current media");if(mode.equals("stop"))check(!f.session.snapshot().active,"queued stop closes the session");}
    }
    private static void intentsDuringStatus(){
        for(String mode:Arrays.asList("pause","seek","off","select","stop","play"))try(F f=new F()){f.arm();f.paused();f.paused();f.clock.queueCommands=true;f.receiver.onStatus=()->intent(f,mode);f.paused();f.clock.flush();f.clock.queueCommands=false;inspectIntent(f,mode);}
    }
    private static void intentsAfterAutoQueued(){
        for(String mode:Arrays.asList("pause","seek","off","select","stop","play"))try(F f=new F()){f.arm();f.paused();f.paused();f.clock.queueCommands=true;f.paused();check(f.clock.commands.size()==1&&f.clock.preparations.size()==1,"EOF queues automatic continuation without prematurely advancing generation");intent(f,mode);f.clock.flush();f.clock.queueCommands=false;inspectIntent(f,mode);if(mode.equals("seek")){f.sample("PLAYING",64000,END);f.sample("PLAYING",65000,END);f.three();f.next("cancelled automatic task does not orphan the epoch; actual postseek progress rearms position8");}}
    }
    private static void cacheAndHttpLeases()throws Exception{
        File root=Files.createTempDirectory("hongguo-zeekr-paused-cache-").toFile(),directory=new File(root,"playback");check(directory.mkdirs(),"isolated cache directory created");File manual=new File(directory,"manual-user-download.mp4");byte[] sentinel="manual-download-must-remain".getBytes(StandardCharsets.UTF_8);Files.write(manual.toPath(),sentinel);
        DlnaIntegrationTest.Clock clock=new DlnaIntegrationTest.Clock();DlnaIntegrationTest.Receiver receiver=new DlnaIntegrationTest.Receiver();receiver.duration=END;DlnaIntegrationTest.PreparedCache source=new DlnaIntegrationTest.PreparedCache(directory);DlnaIntegrationTest.HttpServer server=new DlnaIntegrationTest.HttpServer();DlnaSession session=new DlnaSession(clock,receiver,source,server,ignored->{},DlnaIntegrationTest.SID,"合成66秒剧集",ZEEKR,4,"1080p",true,true);List<Socket>reads=new ArrayList<>();
        try{
            session.start(0,0);clock.drain();clock.prepareNext();DlnaIntegrationTest.confirm(clock,receiver,session,0);String old=receiver.requested;Socket held=DlnaIntegrationTest.held(old);reads.add(held);DlnaIntegrationTest.await(()->source.leases[0].get()==2,"actual current publication plus HTTP read pin cache independently");
            for(int next=1;next<4;next++){
                receiver.state="PLAYING";receiver.position=64000;clock.tick();receiver.position=65000;clock.tick();receiver.state="PAUSED_PLAYBACK";receiver.position=END;receiver.duration=END;clock.tick();clock.tick();check(!session.snapshot().preparing,"two paused samples do not prepare real cache media");clock.tick();check(session.snapshot().preparing&&session.snapshot().targetCurrent==next&&session.snapshot().current==next-1,"third paused sample schedules only the immediately next real source");check(clock.preparations.size()==1&&source.downloads==next,"only one next preparation exists and no eager extra media is fetched");clock.prepareNext();String following=receiver.requested;check(DlnaIntegrationTest.status(old)==206&&source.confirmed.size()==next,"old source remains available until replacement URI is actually confirmed");receiver.duration=END;DlnaIntegrationTest.confirm(clock,receiver,session,next);check(DlnaIntegrationTest.status(old)==404,"confirmed new URI revokes only the old publication");DlnaIntegrationTest.verifyRange(following,source.files[next]);old=following;
                if(next==2){check(source.files[0].exists()&&source.leases[0].get()==1,"out-of-window file remains pinned by an active old HTTP reader");held.close();DlnaIntegrationTest.await(()->source.leases[0].get()==0,"old reader closes its last lease");DlnaIntegrationTest.await(()->!source.files[0].exists(),"only unpinned out-of-window cache is pruned");}
            }
            check(source.prepared.equals(Arrays.asList(0,1,2,3))&&source.confirmed.equals(Arrays.asList(0,1,2,3))&&source.downloads==4,"all actual cache episodes prepare and confirm once in sequence");check(source.cache.status().files==2&&source.cache.status().bytes<=DlnaIntegrationTest.BUDGET,"current and previous rolling cache stay within256MiB budget");receiver.state="PLAYING";receiver.position=64000;clock.tick();receiver.position=65000;clock.tick();receiver.state="PAUSED_PLAYBACK";receiver.position=END;clock.tick();clock.tick();clock.tick();check("ENDED".equals(session.snapshot().state)&&clock.preparations.isEmpty()&&source.downloads==4,"real cache last episode ends without phantom next");session.stop();clock.drain();check(source.totalPins()==0,"stop balances all actual preparation/publication/HTTP leases");check(source.cache.clear().removed==2&&Arrays.equals(Files.readAllBytes(manual.toPath()),sentinel),"manual file survives pruning, stop and final app-cache clear");
        }finally{session.close();server.close();for(Socket socket:reads)try{socket.close();}catch(Exception ignored){}try(java.util.stream.Stream<java.nio.file.Path>paths=Files.walk(root.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
    }
    public static void main(String[] args)throws Exception{
        group("zero-based-position7-to8-episode8-to9-and-time-gate",DlnaPausedEofCompatibilityTest::positionSevenToEightAndTimeGate);
        group("profile-is-strict-and-ordinary-pauses-stay-paused",DlnaPausedEofCompatibilityTest::exactProfileOnly);
        group("raw-exact-timing-and-fresh-forward-evidence",DlnaPausedEofCompatibilityTest::strictRawTiming);
        group("other-states-URI-errors-and-duration-break-proof",DlnaPausedEofCompatibilityTest::interruptedSamples);
        group("phone-pause-seek-to-end-and-real-postseek-progress",DlnaPausedEofCompatibilityTest::explicitPauseAndSeek);
        group("adopt-resume-seek-requires-new-real-progress",DlnaPausedEofCompatibilityTest::automaticResumeSeek);
        group("autoplay-off-last-episode-and-next-source-failure",DlnaPausedEofCompatibilityTest::autoplayLastAndFailure);
        group("manual-intent-arrives-during-blocked-status",DlnaPausedEofCompatibilityTest::intentsDuringStatus);
        group("manual-intent-wins-after-auto-continuation-queued",DlnaPausedEofCompatibilityTest::intentsAfterAutoQueued);
        group("actual-cache-range-HTTP-and-delayed-read-lease-handoff",DlnaPausedEofCompatibilityTest::cacheAndHttpLeases);
        System.out.println("DLNA_PAUSED_EOF_"+(failed==0?"OK":"FAILED")+" groupsPassed="+passed+" groupsFailed="+failed+" checks="+checks+" actualSession=true profile=ZeekrOnly actualCacheAndHttp=true loopbackOnly=true lanNetworkRequests=0 deviceTests=not_run");if(failed!=0)System.exit(1);
    }
}
