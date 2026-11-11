package io.github.jackotom.hongguo.android;

import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class BrowserCastSessionTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static final class Job implements DlnaSession.Task {final Runnable action;boolean cancelled;Job(Runnable action){this.action=action;}public void cancel(){cancelled=true;}}
    static final class Clock implements DlnaSession.Scheduler {
        final List<Job> preparations=new ArrayList<>(),timers=new ArrayList<>();final List<Runnable> queue=new ArrayList<>();
        boolean queued,closed,reject;long now;
        public boolean execute(Runnable action){if(closed||reject){reject=false;return false;}if(queued)queue.add(action);else action.run();return true;}
        public DlnaSession.Task prepare(Runnable action){Job task=new Job(action);preparations.add(task);return task;}
        public DlnaSession.Task repeat(Runnable action,long delay){Job task=new Job(action);timers.add(task);return task;}
        public long nowMs(){return now;}
        public void close(){closed=true;for(Job job:preparations)job.cancel();for(Job job:timers)job.cancel();}
        void prepare(int index){preparations.get(index).action.run();}
        void tick(long millis){now+=millis;for(Job job:new ArrayList<>(timers))if(!job.cancelled)job.action.run();}
        void one(){queue.remove(0).run();}
        void drain(){while(!queue.isEmpty())one();}
    }
    static final class Lease {int references=1;final int index;Lease(int index){this.index=index;}void retain(){references++;}void release(){references--;check(references>=0,"lease never below zero");}}
    static final class Source implements DlnaSession.Source {
        final List<Lease> leases=new ArrayList<>();final List<Integer> selected=new ArrayList<>(),confirmed=new ArrayList<>(),prefetched=new ArrayList<>();
        final List<Boolean> autoplay=new ArrayList<>(),cleanups=new ArrayList<>();final List<String> qualities=new ArrayList<>();
        boolean closed,fail;int progress;Runnable onPrepare;
        public DlnaSession.Prepared prepare(int index,String quality,BooleanSupplier alive)throws Exception{
            qualities.add(quality);if(fail)throw new IOException("fixture failed https://secret.invalid/path?token=secret\nline");
            Lease lease=new Lease(index);leases.add(lease);if(onPrepare!=null)onPrepare.run();
            return new DlnaSession.Prepared(new File("synthetic-"+index+".mp4"),"测试剧 第 "+(index+1)+" 集",index==0?"720p":quality,index+1,lease::retain,lease::release);
        }
        public void selecting(int index){selected.add(index);}
        public void confirmed(int index,long ms,boolean cleanup){confirmed.add(index);cleanups.add(cleanup);}
        public void playbackConfirmed(int index,String quality,boolean auto){prefetched.add(index);}
        public void autoplayChanged(boolean value){autoplay.add(value);}
        public void progress(int index,long ms){progress++;}
        public void close(){closed=true;}
    }
    static final class Server implements DlnaSession.Server {
        final Map<DlnaSession.Publication,DlnaSession.Prepared> live=new LinkedHashMap<>();int serial;boolean closed,fail;Runnable onPublish;
        public DlnaSession.Publication publish(DlnaSession.Prepared media)throws Exception{
            if(fail)throw new IOException("fixture publication failure");media.retain.run();
            DlnaSession.Publication item=new DlnaSession.Publication("http://192.168.1.2:2222/media/synthetic-"+(++serial)+".mp4",null);live.put(item,media);
            if(onPublish!=null)onPublish.run();return item;
        }
        public void revoke(DlnaSession.Publication item){DlnaSession.Prepared media=live.remove(item);if(media!=null)media.release.run();}
        public void close(){if(closed)return;closed=true;for(DlnaSession.Publication item:new ArrayList<>(live.keySet()))revoke(item);}
        Runnable reader(){DlnaSession.Prepared item=live.values().iterator().next();item.retain.run();return item.release;}
    }
    static final class Fixture {
        final Clock clock=new Clock();final Source source=new Source();final Server server=new Server();
        final List<DlnaSession.Snapshot> snapshots=new ArrayList<>();
        final BrowserCastSession session=new BrowserCastSession(clock,source,server,snapshots::add,"sid","测试剧",5,"1080p",true,true);
        long seq;
        JSONObject state(){return session.receiverState();}
        void start(){session.start(0,0);session.receiverPaired("Safari");clock.prepare(0);}
        JSONObject event(String type,long p,long d)throws Exception{
            JSONObject value=state(),media=value.optJSONObject("media");
            return new JSONObject().put("sessionId",value.getString("sessionId")).put("mediaId",media==null?"":media.getString("id"))
                .put("eventSeq",++seq).put("commandId",value.getJSONObject("intent").getLong("commandId"))
                .put("type",type).put("positionMs",p).put("durationMs",d);
        }
        void send(String type,long p,long d)throws Exception{session.receiverEvent(event(type,p,d));}
        void progressing()throws Exception{send("playing",0,10000);send("timeupdate",9000,10000);}
        JSONObject command(String type,Object value)throws Exception{JSONObject s=state();return new JSONObject().put("sessionId",s.getString("sessionId")).put("mediaId",s.getJSONObject("media").getString("id")).put("type",type).put("value",value);}
        void close(){session.close();clock.drain();for(Lease lease:source.leases)check(lease.references==0,"all owners released on close");}
    }
    static void pairingAndConfirmation()throws Exception{
        Fixture f=new Fixture();f.session.start(0,3000);check(f.session.snapshot().active&&f.session.snapshot().state.equals("WAITING_RECEIVER"),"wait for receiver");
        check(f.clock.preparations.isEmpty()&&f.server.live.isEmpty(),"no source before pairing");
        check(f.session.snapshot().positionMs==3000,"waiting receiver retains local resume position");
        f.session.receiverPaired("Mac\nSafari");check(f.clock.preparations.size()==1&&f.session.snapshot().preparing,"pairing begins preparation");f.clock.prepare(0);
        check(f.source.confirmed.isEmpty()&&f.source.prefetched.isEmpty(),"publication is not playback");
        check(f.session.snapshot().state.equals("WAITING_RECEIVER_PLAY")&&!f.session.snapshot().playing,"wait honest actual playback");
        check(f.state().getJSONObject("intent").getLong("seekMs")==3000,"resume intent delivered");
        check(f.source.leases.get(0).references==1,"server owns publication after temporary owner release");
        f.send("blocked",0,0);check(f.session.snapshot().state.equals("WAITING_GESTURE")&&f.source.confirmed.isEmpty(),"gesture rejection not playing");
        f.send("playing",3000,10000);check(f.session.snapshot().playing&&f.source.confirmed.equals(Arrays.asList(0)),"actual playing adopts once");
        check(f.source.prefetched.equals(Arrays.asList(0))&&f.source.cleanups.equals(Arrays.asList(true)),"confirmed playback starts prefetch and cleanup callback");
        check(f.session.snapshot().actualQuality.equals("720p")&&f.session.snapshot().preferredQuality.equals("1080p"),"effective quality independent from preference");
        f.send("playing",3500,10000);check(f.source.confirmed.size()==1&&f.source.prefetched.size()==1,"duplicate playing no duplicate adoption");
        f.session.setAutoplay(false);check(!f.session.snapshot().autoplay&&f.source.autoplay.equals(Arrays.asList(false)),"autoplay source routed");
        JSONObject mutable=f.state();mutable.put("state","tampered");check(!f.state().getString("state").equals("tampered"),"JSON snapshots are detached");
        check(!f.session.diagnostics().contains("http://"),"diagnostics contain no media URL");f.close();
    }
    static void naturalEndAndLeases()throws Exception{
        Fixture f=new Fixture();f.start();f.progressing();Runnable oldReader=f.server.reader();JSONObject ended=f.event("ended",10000,10000);
        f.session.receiverEvent(ended);check(f.clock.preparations.size()==2&&f.session.snapshot().targetCurrent==1,"real ended advances next");
        f.session.receiverEvent(ended);check(f.clock.preparations.size()==2,"duplicate ended only advances once");
        check(f.source.leases.get(0).references==2&&f.server.live.size()==1,"old read and publication pinned while preparing");
        check(f.session.snapshot().current==0&&f.session.snapshot().positionMs==10000,"autoplay preparation retains old current episode confirmed end position");
        f.clock.prepare(1);check(f.server.live.size()==2&&f.source.confirmed.size()==1,"unconfirmed replacement retains old publication");
        check(f.session.snapshot().positionMs==10000&&f.state().getLong("positionMs")==0,"unconfirmed candidate JSON position is separate from phone old-current resume");
        f.send("playing",0,10000);check(f.session.snapshot().current==1&&f.server.live.size()==1&&f.source.confirmed.equals(Arrays.asList(0,1)),"new actual playing adopts then retires old");
        check(f.source.leases.get(0).references==1,"inflight old reader survives revocation");oldReader.run();check(f.source.leases.get(0).references==0,"old read eventually releases");
        check(f.source.qualities.equals(Arrays.asList("1080p","720p")),"next source uses successful effective quality");f.close();
    }
    static void falseEndsAndEvents()throws Exception{
        for(String interrupted:Arrays.asList("waiting","blocked","pause","seeking","error")){
            Fixture f=new Fixture();f.start();f.progressing();f.send(interrupted,9500,10000);f.send("ended",10000,10000);
            check(f.clock.preparations.size()==1,"no false ended after "+interrupted);f.close();
        }
        Fixture noPlay=new Fixture();noPlay.start();noPlay.send("timeupdate",9000,10000);noPlay.send("ended",10000,10000);check(noPlay.clock.preparations.size()==1,"progress without playing is not confirmation");noPlay.close();
        Fixture noProgress=new Fixture();noProgress.start();noProgress.send("playing",10000,10000);noProgress.send("ended",10000,10000);check(noProgress.clock.preparations.size()==1,"static tail not progress");noProgress.close();
        Fixture unknown=new Fixture();unknown.start();unknown.send("playing",0,0);unknown.send("timeupdate",9000,0);unknown.send("ended",10000,0);check(unknown.clock.preparations.size()==1,"unknown duration cannot end");unknown.close();
        Fixture early=new Fixture();early.start();early.progressing();early.send("ended",4000,10000);check(early.clock.preparations.size()==1,"early ended rejected");early.close();
        Fixture off=new Fixture();off.start();off.progressing();off.session.setAutoplay(false);off.send("ended",10000,10000);
        check(off.clock.preparations.size()==1&&off.session.snapshot().state.equals("ENDED"),"autoplay off ends without changing episode");
        off.session.play();check(off.state().getJSONObject("intent").getLong("seekMs")==0,"replay seeks beginning");off.send("playing",0,10000);off.send("timeupdate",9000,10000);off.send("ended",10000,10000);check(off.session.snapshot().state.equals("ENDED"),"replay can end again");off.close();
        Fixture stale=new Fixture();stale.start();stale.progressing();JSONObject old=stale.event("ended",10000,10000);stale.session.pause();stale.session.receiverEvent(old);check(stale.clock.preparations.size()==1,"old command ended rejected after pause");
        stale.session.play();stale.session.receiverEvent(old);check(stale.clock.preparations.size()==1,"old command still rejected after resume");
        stale.send("playing",0,10000);JSONObject duplicate=stale.event("timeupdate",9000,10000);stale.session.receiverEvent(duplicate);long accepted=stale.state().getLong("lastEventSeq");duplicate.put("type","ended").put("positionMs",10000);stale.session.receiverEvent(duplicate);
        check(stale.clock.preparations.size()==1&&stale.state().getLong("lastEventSeq")==accepted,"replayed event sequence rejected");
        JSONObject wrong=stale.event("ended",10000,10000).put("sessionId","wrong");stale.session.receiverEvent(wrong);check(stale.clock.preparations.size()==1,"wrong session rejected");
        JSONObject oldMedia=stale.event("ended",10000,10000);stale.session.select(1);stale.clock.prepare(1);stale.session.receiverEvent(oldMedia);check(stale.clock.preparations.size()==2&&stale.source.confirmed.size()==1,"old media never adopts or advances new candidate");stale.close();
    }
    static void disconnectAndRecovery()throws Exception{
        Fixture f=new Fixture();f.start();f.progressing();JSONObject lateEnd=f.event("ended",10000,10000);f.clock.tick(16001);
        check(f.session.snapshot().state.equals("DISCONNECTED")&&!f.session.snapshot().playing&&f.server.live.size()==1,"heartbeat loss preserves media without pretending playback");
        f.session.receiverEvent(lateEnd);check(f.clock.preparations.size()==1,"late ended cannot recover disconnected session");f.state();check(f.session.snapshot().state.equals("DISCONNECTED"),"poll heartbeat alone not actual playback recovery");
        f.send("playing",0,10000);f.send("timeupdate",9000,10000);f.send("ended",10000,10000);check(f.clock.preparations.size()==2,"new genuine playing and progress recover next");f.close();
        Fixture noTimer=new Fixture();noTimer.start();noTimer.progressing();JSONObject end=noTimer.event("ended",10000,10000);noTimer.clock.now+=16001;noTimer.session.receiverEvent(end);check(noTimer.clock.preparations.size()==1&&noTimer.session.snapshot().state.equals("DISCONNECTED"),"event checks stale heartbeat even before watchdog tick");noTimer.close();
    }
    static void cancellationRaces()throws Exception{
        for(String cancel:Arrays.asList("pause","seek","select","autoplay","stop")){
            Fixture f=new Fixture();f.start();f.progressing();JSONObject end=f.event("ended",10000,10000);f.clock.queued=true;f.session.receiverEvent(end);f.clock.one();
            check(f.clock.queue.size()==1,"automatic next staged in serial queue");
            switch(cancel){case "pause":f.session.pause();break;case "seek":f.session.seek(500);break;case "select":f.session.select(3);break;case "autoplay":f.session.setAutoplay(false);break;case "stop":f.session.stop();break;default:break;}
            f.clock.drain();check(f.clock.preparations.size()==(cancel.equals("select")?2:1),"queued auto next cancelled by "+cancel);
            if(cancel.equals("select"))check(f.session.snapshot().targetCurrent==3,"explicit selected episode wins");f.close();
        }
        Fixture nativePause=new Fixture();nativePause.start();nativePause.progressing();JSONObject end=nativePause.event("ended",10000,10000);JSONObject pause=nativePause.command("pause",false);nativePause.clock.queued=true;nativePause.session.receiverEvent(end);nativePause.clock.one();nativePause.session.receiverCommand(pause);nativePause.clock.drain();check(nativePause.clock.preparations.size()==1&&!nativePause.state().getJSONObject("intent").getBoolean("play"),"receiver command cancels queued EOF immediately");nativePause.close();
        Fixture f=new Fixture();f.session.start(0,0);f.session.receiverPaired("Safari");f.session.select(2);f.clock.prepare(0);check(f.server.live.isEmpty()&&f.source.leases.get(0).references==0,"stale background preparation releases owner");f.clock.prepare(1);check(f.state().getJSONObject("media").getInt("queuePosition")==2,"latest selection wins publication");f.close();
        Fixture late=new Fixture();late.start();late.session.select(1);late.session.close();late.clock.prepare(1);check(late.source.leases.get(1).references==0,"late result after close releases");late.close();
        Fixture rejected=new Fixture();rejected.session.start(0,0);rejected.session.receiverPaired("Safari");rejected.clock.reject=true;rejected.clock.prepare(0);check(rejected.source.leases.get(0).references==0,"rejected install delivery releases owner");rejected.close();
        Fixture inPublish=new Fixture();inPublish.session.start(0,0);inPublish.session.receiverPaired("Safari");inPublish.server.onPublish=()->inPublish.session.select(2);inPublish.clock.prepare(0);check(inPublish.server.live.isEmpty()&&inPublish.source.leases.get(0).references==0,"selection racing publication revokes never-exposed old URL");inPublish.server.onPublish=null;inPublish.clock.prepare(1);inPublish.close();
    }
    static void startOrdering()throws Exception{
        Fixture resume=new Fixture();resume.start();resume.send("playing",0,10000);resume.send("timeupdate",4000,10000);resume.session.select(2);resume.source.fail=true;resume.clock.prepare(1);
        check(resume.session.snapshot().current==0&&resume.session.snapshot().positionMs==4000,"failed next episode preserves confirmed old playback resume");resume.close();
        Fixture already=new Fixture();already.start();already.progressing();already.session.play();
        check(already.session.snapshot().playing,"idempotent play preserves established playing without demanding a new browser event");
        already.send("timeupdate",9500,10000);already.send("ended",10000,10000);check(already.clock.preparations.size()==2,"idempotent play retains real continuous playback evidence");already.close();
        Fixture f=new Fixture();check(f.snapshots.isEmpty(),"constructor does not publish premature inactive lifecycle callback");
        f.clock.queued=true;f.session.start(0,0);f.session.receiverPaired("Safari");f.session.select(2);f.clock.drain();
        check(f.session.snapshot().active&&f.session.snapshot().targetCurrent==2&&f.clock.preparations.size()==1,"selection queued before start execution still starts latest episode once");
        f.clock.prepare(0);f.clock.drain();check(f.state().getJSONObject("media").getInt("queuePosition")==2,"latest queue selection gets correct file");
        f.session.start(3,0);f.clock.drain();check(f.clock.preparations.size()==1,"duplicate start leaves existing session intact");f.close();
        Fixture paused=new Fixture();paused.clock.queued=true;paused.session.start(0,0);paused.session.receiverPaired("Safari");paused.session.pause();paused.clock.drain();paused.clock.prepare(0);paused.clock.drain();
        check(!paused.state().getJSONObject("intent").getBoolean("play"),"pause queued during initial startup wins play intent");paused.close();
    }
    static void failuresBoundsCommands()throws Exception{
        Fixture fail=new Fixture();fail.source.fail=true;fail.session.start(0,0);fail.session.receiverPaired("Safari");fail.clock.prepare(0);
        check(fail.session.snapshot().state.equals("ERROR")&&!fail.session.snapshot().preparing&&fail.clock.preparations.size()==1,"source failure stops on requested episode");check(!fail.session.snapshot().error.contains("secret.invalid")&&!fail.session.snapshot().error.contains("\n"),"source failure redacted");fail.source.fail=false;fail.session.select(0);fail.clock.prepare(1);fail.close();
        Fixture publish=new Fixture();publish.server.fail=true;publish.session.start(0,0);publish.session.receiverPaired("Safari");publish.clock.prepare(0);check(publish.source.leases.get(0).references==0&&publish.session.snapshot().state.equals("ERROR"),"publish failure balances owner");publish.close();
        Fixture bound=new Fixture();bound.start();for(int i=1;i<7;i++){bound.session.select(i%5);bound.clock.prepare(i);}check(bound.server.live.size()==4&&bound.session.snapshot().state.equals("ERROR"),"unconfirmed publications bounded at four");bound.close();
        Fixture cmd=new Fixture();cmd.start();cmd.send("playing",0,10000);cmd.session.receiverCommand(cmd.command("pause",false));check(cmd.session.snapshot().state.equals("PAUSED"),"receiver pause routes");cmd.session.receiverCommand(cmd.command("play",true));check(cmd.state().getJSONObject("intent").getBoolean("play"),"receiver play routes");cmd.session.receiverCommand(cmd.command("seek",5000));check(cmd.state().getJSONObject("intent").getLong("seekMs")==5000,"receiver seek routes");cmd.session.receiverCommand(cmd.command("autoplay",false));check(!cmd.session.snapshot().autoplay,"receiver autoplay routes");
        JSONObject oldCommand=cmd.command("next",0);cmd.session.receiverCommand(cmd.command("next",0));cmd.clock.prepare(1);cmd.session.receiverCommand(oldCommand);check(cmd.clock.preparations.size()==2,"stale browser media command rejected");cmd.session.receiverCommand(cmd.command("previous",0));check(cmd.session.snapshot().targetCurrent==0,"receiver previous routes");cmd.clock.prepare(2);cmd.session.receiverCommand(cmd.command("select",3));check(cmd.session.snapshot().targetCurrent==3,"receiver explicit select routes");cmd.clock.prepare(3);cmd.session.receiverCommand(cmd.command("stop",false));check(!cmd.session.snapshot().active&&cmd.server.closed&&cmd.source.closed&&cmd.clock.closed,"browser stop closes all resources");cmd.close();
    }
    public static void main(String[] args)throws Exception{
        pairingAndConfirmation();naturalEndAndLeases();falseEndsAndEvents();disconnectAndRecovery();cancellationRaces();startOrdering();failuresBoundsCommands();
        System.out.println("BROWSER_CAST_SESSION_OK checks="+checks+" session=actualProduction schedulerSourceServer=fakes actualPlayingRequired=true endedOnly=true fivePrefetchCallback=true leasesBalanced=true networkRequests=0 appleDeviceTests=not_run");
    }
}
