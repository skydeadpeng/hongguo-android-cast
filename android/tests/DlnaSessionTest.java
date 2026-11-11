package io.github.jackotom.hongguo.android;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

final class DlnaSessionTest {
    static int checks;
    static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    static final class Job implements DlnaSession.Task {final Runnable action;boolean cancelled;Job(Runnable action){this.action=action;}public void cancel(){cancelled=true;}}
    static final class Clock implements DlnaSession.Scheduler {
        final List<Job> preparations=new ArrayList<>(),polls=new ArrayList<>();final List<Runnable> commands=new ArrayList<>();boolean closed,queueCommands,refuseNext;long now;
        public boolean execute(Runnable action){if(closed||refuseNext){refuseNext=false;return false;}if(queueCommands)commands.add(action);else action.run();return true;}
        public DlnaSession.Task prepare(Runnable action){Job job=new Job(action);preparations.add(job);return job;}
        public DlnaSession.Task repeat(Runnable action,long delay){Job job=new Job(action);polls.add(job);return job;}
        public long nowMs(){return now;}public void close(){closed=true;for(Job job:preparations)job.cancel();for(Job job:polls)job.cancel();}
        void prepare(int index,boolean ignoreCancel){Job job=preparations.get(index);if(ignoreCancel||!job.cancelled)job.action.run();}
        void tick(){now+=1000;for(Job job:new ArrayList<>(polls))if(!job.cancelled)job.action.run();}
        void flush(){boolean prior=queueCommands;queueCommands=false;List<Runnable> pending=new ArrayList<>(commands);commands.clear();for(Runnable action:pending)action.run();queueCommands=prior;}
    }
    static final class Media {int references=1;boolean released;final int position;Media(int p){position=p;}}
    static final class Source implements DlnaSession.Source {
        final List<Media> media=new ArrayList<>();final List<Integer> confirmed=new ArrayList<>(),progress=new ArrayList<>();final List<String> qualities=new ArrayList<>();int fail=-1;boolean low,ignoreAlive;
        public DlnaSession.Prepared prepare(int position,String quality,java.util.function.BooleanSupplier alive)throws Exception{
            qualities.add(quality);if(position==fail)throw new IOException("synthetic source failure https://secret.invalid/?token=secret");
            Media item=new Media(position);media.add(item);return new DlnaSession.Prepared(new File("fixture-"+position+".mp4"),"剧 · 第 "+(position+1)+" 集",low?"720p":quality,position+1,()->item.references++,()->{item.references--;check(item.references>=0,"lease never negative");});
        }
        public void confirmed(int position,long progress,boolean cleanup){confirmed.add(position);}
        public void progress(int position,long value){progress.add(position);}
    }
    static final class Control implements DlnaSession.Controller {
        String uri="",state="STOPPED";long position,duration=100000;int sets,plays,pauses,seeks,stops,statuses;boolean confirm=true,failStatus,failSet,failStop;List<Long> sought=new ArrayList<>();
        public void setMedia(String value,String title)throws Exception{sets++;if(failSet)throw new IOException("Set failed");uri=value;state="TRANSITIONING";position=0;}
        public void play(){plays++;if(confirm)state="PLAYING";}
        public void pause(){pauses++;state="PAUSED_PLAYBACK";}
        public void stop()throws Exception{stops++;if(failStop)throw new IOException("network unavailable");state="STOPPED";}
        public void seek(long value){seeks++;sought.add(value);position=value;}
        public DlnaSession.Status status()throws Exception{statuses++;if(failStatus)throw new IOException("poll unavailable");return new DlnaSession.Status(state,uri,position,duration);}
    }
    static final class Server implements DlnaSession.Server {
        final Map<DlnaSession.Publication,DlnaSession.Prepared> live=new LinkedHashMap<>();int serial,revoked;boolean closed;
        public DlnaSession.Publication publish(DlnaSession.Prepared media){media.retain.run();DlnaSession.Publication p=new DlnaSession.Publication("http://192.168.1.2:1234/token"+(++serial)+".mp4",null);live.put(p,media);return p;}
        public void revoke(DlnaSession.Publication item){DlnaSession.Prepared media=live.remove(item);if(media!=null){revoked++;media.release.run();}}
        public void close(){if(closed)return;closed=true;for(DlnaSession.Publication p:new ArrayList<>(live.keySet()))revoke(p);}
        Runnable readCurrent(){DlnaSession.Prepared media=live.values().iterator().next();media.retain.run();return media.release;}
    }
    static final class Fixture {
        final Clock clock=new Clock();final Source source=new Source();final Control control=new Control();final Server server=new Server();final List<DlnaSession.Snapshot> snapshots=new ArrayList<>();
        final DlnaSession session=new DlnaSession(clock,control,source,server,snapshots::add,"123456","测试剧","合成电视",4,"1080p",true,true);
        void start(){session.start(0,0);clock.prepare(0,false);}
    }
    static void initialAndControl(){
        Fixture f=new Fixture();f.session.start(0,27000);check(f.session.snapshot().preparing&&f.server.live.isEmpty(),"source preparation before any TV URI");f.clock.prepare(0,false);
        check(f.session.snapshot().active&&f.session.snapshot().playing&&!f.session.snapshot().preparing,"first URI+PLAYING adopts");check(f.session.snapshot().current==0&&f.session.snapshot().episodeIndex==1,"episode identity snapshot");check(f.source.confirmed.equals(Arrays.asList(0)),"cleanup callback only after confirmation");check(f.source.media.get(0).references==1,"registration replaces temporary preparation owner");check(f.control.sought.equals(Arrays.asList(27000L)),"resume seek after TV confirms playing");
        f.session.pause();check(!f.session.snapshot().playing&&f.control.pauses==1,"pause controlled and observed");f.session.play();check(f.session.snapshot().playing,"resume controlled and observed");f.session.seek(50000);check(f.control.sought.get(1)==50000,"seek exact requested position");
        f.clock.now=6000;f.clock.tick();check(!f.source.progress.isEmpty(),"cast progress saved while no UI attached");f.session.stop();check(!f.session.snapshot().active&&f.session.snapshot().state.equals("STOPPED")&&f.server.closed&&f.clock.closed,"confirmed stop closes independent supply/schedulers");check(f.source.media.get(0).references==0,"stop releases current publication lease");
    }
    static void confirmationAndFailedSelection(){
        Fixture f=new Fixture();f.start();Runnable oldReader=f.server.readCurrent();f.control.confirm=false;f.session.select(1);f.clock.prepare(1,false);
        check(f.session.snapshot().preparing&&f.session.snapshot().current==0&&f.session.snapshot().targetCurrent==1,"old episode remains current until new URI plays");check(f.server.live.size()==2&&f.source.media.get(0).references==2,"old registration and read pinned while candidate waits");check(f.source.confirmed.size()==1,"no prune while candidate unconfirmed");
        f.control.state="PLAYING";f.clock.tick();check(f.session.snapshot().current==1&&f.server.live.size()==1,"confirmed new URI adopts then revokes old");check(f.source.media.get(0).references==1,"old active HTTP reader retains independent pin after revoke");oldReader.run();check(f.source.media.get(0).references==0,"old read finally releases pin");
        f.source.fail=2;f.session.select(2);f.clock.prepare(2,false);check(f.session.snapshot().current==1&&f.session.snapshot().targetCurrent==2&&!f.session.snapshot().preparing,"failed manual target retained for retry while old current remains");check(f.server.live.size()==1&&f.source.confirmed.size()==2,"source failure never revokes current or prunes");check(!f.session.snapshot().error.contains("secret.invalid"),"source errors redact URL/token");
        f.source.fail=-1;f.session.select(2);f.clock.prepare(3,false);f.control.state="PLAYING";f.clock.tick();check(f.session.snapshot().current==2,"same failed target explicit retry succeeds");f.session.stop();
    }
    static void latestWinsAndLeaks(){
        Fixture f=new Fixture();f.session.start(0,0);f.session.select(1);f.clock.prepare(0,true);check(f.server.live.isEmpty()&&f.source.media.get(0).references==0,"cancelled old preparation late-result discarded with balanced owner");f.clock.prepare(1,false);check(f.session.snapshot().current==1&&f.control.sets==1,"only newest episode dispatches remote URI");
        f.session.select(2);f.session.stop();f.clock.prepare(2,true);check(!f.session.snapshot().active&&f.source.media.get(2).references==0,"late prepared result after stop releases owner");
        Fixture g=new Fixture();g.start();g.control.confirm=false;g.session.select(1);g.clock.prepare(1,false);g.session.select(2);g.clock.prepare(2,false);check(g.server.live.size()==3,"exposed old candidate remains pinned through supersession");g.control.state="PLAYING";g.clock.tick();check(g.session.snapshot().current==2&&g.server.live.size()==1,"latest confirmed URI retires all older candidate/current registrations");g.session.stop();for(Media m:g.source.media)check(m.references==0,"all selection leases balanced on stop");
    }
    static void naturalEndAndFalseEnds(){
        Fixture f=new Fixture();f.start();f.control.position=99000;f.clock.tick();f.control.state="STOPPED";f.clock.tick();check(f.session.snapshot().preparing&&f.session.snapshot().targetCurrent==1,"only observed near-end playing then stopped prepares next");check(f.clock.preparations.size()==2,"natural end queues one next exactly");f.clock.tick();check(f.clock.preparations.size()==2,"stopped poll never duplicates next");f.clock.prepare(1,false);check(f.session.snapshot().current==1,"next episode confirms independently of UI");f.session.stop();
        for(String state:Arrays.asList("BUFFERING","PAUSED_PLAYBACK")){
            Fixture x=new Fixture();x.start();x.control.position=99000;x.clock.tick();x.control.state=state;x.clock.tick();x.control.state="STOPPED";x.control.position=0;x.control.duration=0;x.clock.tick();x.clock.tick();check(x.clock.preparations.size()==1,"state "+state+" invalidates prior natural-end evidence");x.session.stop();
        }
        Fixture transition=new Fixture();transition.start();transition.control.position=99000;transition.clock.tick();transition.control.state="TRANSITIONING";transition.control.position=0;transition.control.duration=0;transition.clock.tick();check(transition.clock.preparations.size()==1,"TRANSITIONING preserves recent evidence but does not itself complete playback");transition.control.state="STOPPED";transition.clock.tick();transition.clock.tick();check(transition.clock.preparations.size()==2,"repeated terminal status after TRANSITIONING confirms recent natural end");transition.session.stop();
        Fixture noMedia=new Fixture();noMedia.start();noMedia.control.position=99000;noMedia.clock.tick();noMedia.control.state="NO_MEDIA_PRESENT";noMedia.control.position=0;noMedia.control.duration=0;noMedia.control.uri="";noMedia.clock.tick();check(noMedia.clock.preparations.size()==1,"cleared NO_MEDIA_PRESENT needs another terminal confirmation");noMedia.control.state="STOPPED";noMedia.clock.tick();check(noMedia.clock.preparations.size()==2,"consecutive NO_MEDIA_PRESENT then STOPPED confirms recent natural end");noMedia.session.stop();
        Fixture zero=new Fixture();zero.control.duration=0;zero.start();zero.control.position=99000;zero.clock.tick();zero.control.state="STOPPED";zero.clock.tick();check(zero.clock.preparations.size()==1,"unknown duration never autoplays next");zero.session.stop();
        Fixture stopped=new Fixture();stopped.start();stopped.control.position=1000;stopped.clock.tick();stopped.control.state="STOPPED";stopped.clock.tick();check(stopped.clock.preparations.size()==1,"early STOPPED not natural end");stopped.session.stop();
        Fixture off=new Fixture();off.start();off.session.setAutoplay(false);off.control.position=99000;off.clock.tick();off.control.state="STOPPED";off.clock.tick();check(off.clock.preparations.size()==1,"autoplay preference off retains final episode");off.session.stop();
    }
    static void lossConfirmationAndQuality(){
        Fixture f=new Fixture();f.start();f.control.failStatus=true;int previous=f.control.statuses;for(int i=0;i<8;i++)f.clock.tick();check(f.control.statuses-previous==3&&f.session.snapshot().state.equals("DISCONNECTED"),"poll errors stop after three and expose disconnect");check(f.clock.preparations.size()==1&&!f.session.snapshot().error.isEmpty()&&f.server.live.size()==1,"loss never autoends or drops current stream");f.control.failStatus=false;f.session.play();check(f.session.snapshot().playing,"explicit command reconnects polling");f.control.failStop=true;f.session.stop();check(!f.session.snapshot().active&&f.session.snapshot().state.equals("DISCONNECTED")&&f.session.snapshot().error.contains("停止尚未确认"),"unconfirmed remote stop reports truth while closing local supply");
        Fixture wait=new Fixture();wait.control.confirm=false;wait.session.start(0,0);wait.clock.prepare(0,false);for(int i=0;i<14;i++)wait.clock.tick();check(!wait.session.snapshot().preparing&&wait.session.snapshot().state.equals("ERROR")&&wait.clock.preparations.size()==1,"finite new URI confirmation failure never skips episode");check(wait.server.live.size()==1,"TV-exposed failed URI retained until stop or replacement");wait.session.stop();
        Fixture low=new Fixture();low.source.low=true;low.start();check(low.session.snapshot().actualQuality.equals("720p"),"snapshot shows effective prepared quality");low.session.select(1);low.clock.prepare(1,false);check(low.source.qualities.equals(Arrays.asList("1080p","720p")),"next reuses successful effective quality without rewriting preference");low.session.close();for(Media m:low.source.media)check(m.references==0,"emergency lifecycle close releases all server pins");check(low.session.snapshot().state.equals("DISCONNECTED"),"service destruction not misreported as confirmed TV stop");
    }
    static void endedRestoreEpochAndRejectedDelivery(){
        Fixture ended=new Fixture();ended.start();ended.session.setAutoplay(false);check(!ended.session.snapshot().autoplay&&ended.session.snapshot().preferredQuality.equals("1080p"),"reattach snapshot carries autoplay and preferred quality");ended.control.position=99000;ended.clock.tick();ended.control.state="STOPPED";ended.clock.tick();check(ended.session.snapshot().state.equals("ENDED"),"only observed natural end becomes ENDED");ended.clock.tick();check(ended.session.snapshot().state.equals("ENDED"),"repeated stopped status preserves natural ended state");ended.session.play();check(ended.session.snapshot().playing&&ended.control.sought.contains(0L),"play after ENDED seeks zero and restarts end tracking");ended.control.position=99000;ended.clock.tick();ended.control.state="STOPPED";ended.clock.tick();check(ended.session.snapshot().state.equals("ENDED"),"replayed episode can naturally end again");ended.session.stop();
        Fixture restored=new Fixture();restored.start();restored.control.position=40000;restored.clock.tick();restored.control.confirm=false;restored.session.select(1);restored.clock.prepare(1,false);for(int i=0;i<12;i++)restored.clock.tick();check(restored.control.sought.contains(40000L)&&restored.session.snapshot().current==0,"failed new URI restores confirmed old progress not zero");check(restored.server.live.size()==2,"unconfirmed restoration keeps both exposed URLs pinned");restored.control.state="PLAYING";restored.clock.tick();check(restored.server.live.size()==1,"old playback reconfirmation retires failed candidate");restored.session.stop();
        Fixture epoch=new Fixture();epoch.start();epoch.clock.queueCommands=true;epoch.session.select(2);epoch.control.position=99000;epoch.clock.tick();epoch.control.state="STOPPED";epoch.clock.tick();check(epoch.clock.preparations.size()==1&&epoch.clock.commands.size()==1,"old natural end cannot overwrite queued manual latest target");epoch.clock.flush();check(epoch.session.snapshot().targetCurrent==2&&epoch.clock.preparations.size()==2,"queued latest manual target wins confirmed generation");epoch.clock.queueCommands=false;epoch.clock.prepare(1,false);epoch.session.stop();
        Fixture pending=new Fixture();pending.start();pending.control.confirm=false;pending.session.select(1);pending.clock.prepare(1,false);pending.clock.queueCommands=true;pending.session.select(2);pending.control.state="PLAYING";pending.clock.tick();check(pending.session.snapshot().current==0,"superseded pending URI cannot adopt while newer select waits in control queue");pending.clock.flush();pending.clock.queueCommands=false;pending.control.confirm=true;pending.clock.prepare(2,false);check(pending.session.snapshot().current==2,"latest pending URI alone becomes current");pending.session.stop();
        Fixture refused=new Fixture();refused.session.start(0,0);refused.clock.refuseNext=true;refused.clock.prepare(0,false);check(refused.source.media.get(0).references==0&&refused.server.live.isEmpty(),"shutdown-rejected prepared delivery balances its owner lease");refused.session.close();
        Fixture accepted=new Fixture();accepted.session.start(0,0);accepted.clock.queueCommands=true;accepted.clock.prepare(0,false);check(accepted.clock.commands.size()==1&&accepted.source.media.get(0).references==1,"prepared owner accepted into queued install");accepted.session.close();accepted.clock.flush();check(accepted.source.media.get(0).references==0&&accepted.server.live.isEmpty()&&accepted.control.sets==0,"close drains accepted queued install so its closed guard releases owner");
        Fixture bounded=new Fixture();bounded.start();bounded.control.confirm=false;for(int n=0;n<5;n++){bounded.session.select(1);bounded.clock.prepare(n+1,false);for(int i=0;i<12;i++)bounded.clock.tick();}check(bounded.server.live.size()<=4,"unverified exposed candidate registrations stay bounded at four");bounded.session.stop();for(Media m:bounded.source.media)check(m.references==0,"bounded failed candidates fully release on stop");
    }
    public static void main(String[] args){initialAndControl();confirmationAndFailedSelection();latestWinsAndLeaks();naturalEndAndFalseEnds();lossConfirmationAndQuality();endedRestoreEpochAndRejectedDelivery();System.out.println("DLNA_SESSION_OK checks="+checks+" session=actualProduction backgroundWithoutActivity=true sourceControlServer=fakes automaticNext=confirmedNearEndOnly networkRequests=0 deviceTests=not_run");}
}
