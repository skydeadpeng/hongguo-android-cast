package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/** Real coordinator, deterministic receiver failures; no network, media or device access. */
final class DlnaSessionDiagnosticsTest {
    private static int checks, passed, failed;
    interface Checked { void run() throws Exception; }
    private static void check(boolean value,String label) { checks++;if(!value)throw new AssertionError(label); }
    private static void group(String name,Checked test) {
        try { test.run();passed++;System.out.println("PASS SessionDiagnostics "+name); }
        catch(Throwable error) { failed++;System.out.println("FAIL SessionDiagnostics "+name+": "+error.getMessage()); }
        finally { Thread.interrupted(); }
    }
    static final class Receiver implements DlnaSession.Controller {
        final DlnaSessionTest.Control delegate=new DlnaSessionTest.Control();
        Exception failure;int statusRequests;
        public void setMedia(String url,String title) throws Exception { delegate.setMedia(url,title); }
        public void play() { delegate.play(); }
        public void pause() { delegate.pause(); }
        public void stop() throws Exception { delegate.stop(); }
        public void seek(long ms) { delegate.seek(ms); }
        public DlnaSession.Status status() throws Exception {
            statusRequests++;if(failure!=null)throw failure;return delegate.status();
        }
    }
    static final class Fixture implements AutoCloseable {
        final DlnaSessionTest.Clock clock=new DlnaSessionTest.Clock();
        final DlnaSessionTest.Source source=new DlnaSessionTest.Source();
        final DlnaSessionTest.Server server=new DlnaSessionTest.Server();
        final Receiver receiver=new Receiver();
        final List<DlnaSession.Snapshot> snapshots=new ArrayList<>();
        final DlnaSession session=new DlnaSession(clock,receiver,source,server,snapshots::add,"123456","诊断夹具","合成接收端",4,"1080p",true,true);
        Fixture(boolean confirm) { receiver.delegate.confirm=confirm;session.start(1,0);clock.prepare(0,false); }
        void failThree(Exception failure) { receiver.failure=failure;for(int i=0;i<3;i++)clock.tick(); }
        public void close() { Thread.interrupted();session.close(); }
    }
    private static String errorFor(Exception failure) {
        try(Fixture f=new Fixture(false)) { f.failThree(failure);return f.session.snapshot().error; }
    }
    public static void main(String[] args) {
        group("unadopted candidate disconnects without stale preparation or lease loss",()->{
            try(Fixture f=new Fixture(false)) {
                check(f.session.snapshot().preparing&&f.source.confirmed.isEmpty(),"unadopted URI starts in receiver confirmation, not confirmed playback");
                int before=f.receiver.statusRequests;f.receiver.failure=new IOException("GetPositionInfo 701 synthetic failure");
                f.clock.tick();f.clock.tick();check(f.session.snapshot().preparing,"first two temporary failures do not terminate confirmation");
                f.clock.tick();DlnaSession.Snapshot snapshot=f.session.snapshot();
                check("DISCONNECTED".equals(snapshot.state)&&!snapshot.preparing&&!snapshot.playing,"third failure clears preparing and playing consistently");
                check(snapshot.active&&f.server.live.size()==1&&f.source.media.get(0).references==1,"unconfirmed remote media remains published and pinned");
                check(f.source.confirmed.isEmpty()&&f.source.progress.isEmpty(),"failed status never confirms progress or cache pruning");
                for(int i=0;i<8;i++)f.clock.tick();
                check(f.receiver.statusRequests-before==3,"existing finite three-query disconnect design is retained");
                check(f.receiver.delegate.sets==1&&f.receiver.delegate.plays==1&&f.receiver.delegate.seeks==0&&f.clock.preparations.size()==1,"diagnostics neither resend commands nor prepare or skip an episode");
            }
        });
        group("last action and code survive with URL and control characters redacted",()->{
            try(Fixture f=new Fixture(false)) {
                f.receiver.failure=new IOException("GetTransportInfo first failure");f.clock.tick();
                f.receiver.failure=new IOException("GetTransportInfo second failure");f.clock.tick();
                f.receiver.failure=new IOException("GetPositionInfo（701） https://secret.invalid/video?token=private\r\ntrace\u0000end");f.clock.tick();
                String error=f.session.snapshot().error;
                check(error.contains("GetPositionInfo")&&error.contains("701"),"third failure exposes its actual action and code");
                check(!error.contains("first failure")&&!error.contains("second failure"),"only the latest failed query is diagnosed");
                check(!error.contains("secret.invalid")&&!error.contains("private")&&error.contains("[地址]"),"media URL and token are removed");
                check(!error.matches("(?s).*[\\p{Cntrl}].*"),"untrusted controls never enter a snapshot");
                check(error.contains("未自动跳集")&&error.contains("停止尚未确认"),"diagnostic does not imply successful playback, EOF or confirmed stop");
            }
        });
        group("direct timeout is not mislabeled as cancellation",()->{
            String error=errorFor(new SocketTimeoutException("Read timed out"));
            check(error.contains("状态请求超时")&&!error.contains("已取消"),"socket read timeout is a status timeout, not user cancellation");
        });
        group("wrapped timeout preserves action without cancellation",()->{
            String error=errorFor(new IOException("GetPositionInfo query failed",new SocketTimeoutException("Read timed out")));
            check(error.contains("GetPositionInfo")&&error.contains("状态请求超时")&&!error.contains("已取消"),"nested socket timeout remains typed and retains action");
        });
        group("empty direct and wrapped timeout messages remain actionable",()->{
            for(Exception failure:new Exception[]{new SocketTimeoutException(),new IOException((String)null,new SocketTimeoutException()),new IOException("  ",new SocketTimeoutException())}) {
                String error=errorFor(failure);check(error.contains("状态请求超时")&&!error.contains("已取消"),"empty timeout receives explicit status timeout text");
            }
        });
        group("non-timeout interrupted IO remains cancellation",()->{
            check(errorFor(new InterruptedIOException("synthetic operation interrupted")).contains("已取消"),"true interrupted IO is not timeout");
        });
        group("real interrupted IO takes priority over its nested timeout",()->{
            InterruptedIOException failure=new InterruptedIOException("operation cancelled while reading");failure.initCause(new SocketTimeoutException("Read timed out"));
            String error=errorFor(failure);check(error.contains("已取消")&&!error.contains("状态请求超时"),"a non-timeout interrupted IO remains cancellation even when wrapping a timeout cause");
        });
        group("wrapped interrupted exception remains cancellation",()->{
            check(errorFor(new IOException("GetTransportInfo query failed",new InterruptedException("cancelled"))).contains("已取消"),"wrapped interruption is not hidden by an outer IO error");
        });
        group("actual thread interruption takes priority over a timeout cause",()->{
            try(Fixture f=new Fixture(false)) {
                Thread.currentThread().interrupt();f.failThree(new SocketTimeoutException("Read timed out"));
                check(Thread.currentThread().isInterrupted()&&f.session.snapshot().error.contains("已取消"),"true thread cancellation is preserved rather than cleared or called timeout");
            }
        });
        group("exception cycles and long diagnostic messages are bounded",()->{
            IOException a=new IOException("GetPositionInfo "+"x".repeat(400)),b=new IOException("nested");a.initCause(b);b.initCause(a);
            String error=errorFor(a);check(error.contains("GetPositionInfo")&&error.length()<250,"cause traversal terminates and keeps the existing 160-character diagnostic limit");
        });
        group("explicit latest selection retires old publications only after its URI confirms",()->{
            try(Fixture f=new Fixture(false)) {
                Runnable finishOldRead=f.server.readCurrent();f.failThree(new IOException("GetPositionInfo unavailable"));
                check(f.source.media.get(0).references==2,"disconnect keeps both publication and active reader leases");
                f.receiver.failure=null;f.receiver.delegate.confirm=true;f.session.select(2);
                check(f.session.snapshot().preparing&&f.session.snapshot().targetCurrent==2,"new explicit user selection resets disconnect and owns the target");
                f.clock.prepare(1,false);
                check(f.session.snapshot().current==2&&f.source.confirmed.size()==1&&f.source.confirmed.get(0)==2,"only the manually selected matching URI is adopted");
                check(f.server.live.size()==1&&f.source.media.get(0).references==1,"new URI confirmation revokes old publication but never its active read");
                finishOldRead.run();check(f.source.media.get(0).references==0,"old reader independently releases its final lease");
            }
        });
        System.out.println("DLNA_SESSION_DIAGNOSTICS_"+(failed==0?"OK":"FAILED")+" groupsPassed="+passed+" groupsFailed="+failed+" checks="+checks+" actualSession=true finitePollFailures=3 networkRequests=0 deviceTests=not_run");
        if(failed!=0)System.exit(1);
    }
}
