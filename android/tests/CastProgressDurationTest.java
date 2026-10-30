package io.github.jackotom.hongguo.android;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import org.json.*;

/** Actual sessions and actual service FileSource methods; all media/network boundaries use existing fixtures. */
final class CastProgressDurationTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static final class Probe implements DlnaSession.Source {
        final DlnaSessionTest.Source media=new DlnaSessionTest.Source();final List<long[]> progress=new ArrayList<>();
        public DlnaSession.Prepared prepare(int position,String quality,java.util.function.BooleanSupplier alive)throws Exception{return media.prepare(position,quality,alive);}
        public void confirmed(int position,long value,boolean cleanup){media.confirmed(position,value,cleanup);}
        public void progress(int position,long value){progress.add(new long[]{position,value,0});}
        public void progress(int position,long value,long duration){progress.add(new long[]{position,value,duration});}
        long[] last(){return progress.get(progress.size()-1);}
    }
    static void dlnaSession(){
        DlnaSessionTest.Clock clock=new DlnaSessionTest.Clock();DlnaSessionTest.Control control=new DlnaSessionTest.Control();Probe source=new Probe();DlnaSessionTest.Server server=new DlnaSessionTest.Server();
        DlnaSession session=new DlnaSession(clock,control,source,server,x->{},"111111","fixture","fixture receiver",3,"1080p",false,false);
        control.duration=60000;session.start(0,0);clock.prepare(0,false);control.position=1234;clock.now=6000;clock.tick();
        check(!source.progress.isEmpty()&&source.last()[0]==0&&source.last()[1]==1234,"actual DLNA session checkpoints current position");
        check(source.last()[2]==60000,"actual DLNA session must forward known receiver duration");
        control.duration=0;control.position=2345;clock.now=12000;clock.tick();check(source.last()[2]==60000,"temporary unknown status retains same-media known duration");session.close();
        DlnaSessionTest.Fixture legacy=new DlnaSessionTest.Fixture();legacy.start();legacy.clock.now=6000;legacy.clock.tick();check(!legacy.source.progress.isEmpty(),"default duration overload preserves legacy two-argument source callbacks");legacy.session.close();
    }
    static void browserSession()throws Exception{
        BrowserCastSessionTest.Clock clock=new BrowserCastSessionTest.Clock();BrowserCastSessionTest.Server server=new BrowserCastSessionTest.Server();Probe source=new Probe();
        BrowserCastSession session=new BrowserCastSession(clock,source,server,x->{},"111111","fixture",3,"1080p",false,false);session.start(0,0);session.receiverPaired("fixture browser");clock.prepare(0);
        send(session,1,"playing",0,70000);send(session,2,"timeupdate",4567,70000);
        check(source.last()[0]==0&&source.last()[1]==4567&&source.last()[2]==70000,"actual browser session forwards confirmed current receiver duration");
        clock.now=6000;send(session,3,"timeupdate",6000,0);check(source.last()[2]==70000,"browser temporary unknown duration retains known same-media bound");session.close();
    }
    static void send(BrowserCastSession session,int seq,String type,long position,long duration)throws Exception{JSONObject state=session.receiverState();session.receiverEvent(new JSONObject().put("sessionId",state.getString("sessionId")).put("mediaId",state.getJSONObject("media").getString("id")).put("commandId",state.getJSONObject("intent").getLong("commandId")).put("eventSeq",seq).put("type",type).put("positionMs",position).put("durationMs",duration));}
    static void progress(DlnaSession.Source source,int at,long position,long duration)throws Exception{try{Method method=source.getClass().getDeclaredMethod("progress",int.class,long.class,long.class);method.setAccessible(true);method.invoke(source,at,position,duration);}catch(NoSuchMethodException old){source.progress(at,position);}}
    static void service(boolean browser)throws Exception{
        try(CastPlaylistSourceTest.Fixture f=new CastPlaylistSourceTest.Fixture(browser,false,0,1)){
            f.start();progress(f.source,0,1357,60000);JSONObject row=f.history("111111");check(row.getLong("positionMs")==1357&&row.optLong("durationMs")==60000,"actual "+(browser?"browser":"DLNA")+" service persists reported duration with current episode");
            long stamp=row.getLong("updatedAt");progress(f.source,-1,100,90000);progress(f.source,9999,100,90000);check(f.history("111111").getLong("updatedAt")==stamp,"invalid queue position cannot persist foreign cast history");
        }
    }
    static byte[] validMp4Fixture()throws Exception{Class<?> builder=Class.forName("io.github.jackotom.hongguo.android.Mp4ConcatTest$Builder");java.lang.reflect.Constructor<?> c=builder.getDeclaredConstructors()[0];c.setAccessible(true);Object instance=c.newInstance(1000,240,600,600,new long[]{12,9,15,7,20,11},new long[]{8,8,8,8,8,8,8,8},new boolean[]{true,false,false,true,false,false},2,2,null,false);Method build=builder.getDeclaredMethod("build",int.class);build.setAccessible(true);return(byte[])build.invoke(instance,0);}
    static void mergedService()throws Exception{
        PlaybackSources.mediaFixture=validMp4Fixture();
        try(CastPlaylistSourceTest.Fixture f=new CastPlaylistSourceTest.Fixture(false,false,0,3)){
            f.start();Field active=f.source.getClass().getDeclaredField("activeMap");active.setAccessible(true);Object map=active.get(f.source);check(map!=null,"actual MP4 merged source confirms active timing map");Field field=map.getClass().getDeclaredField("starts");field.setAccessible(true);long[] starts=(long[])field.get(map);check(starts.length==4,"three actual merged fixture episodes expose all timing boundaries");
            long first=starts[1]-starts[0],second=starts[2]-starts[1],third=starts[3]-starts[2],total=starts[3];
            progress(f.source,0,first/2,total);JSONObject row=f.history("111111");check(row.getInt("index")==1&&row.getLong("positionMs")==first/2&&row.getLong("durationMs")==first,"merged first episode uses its own duration, not merged total");
            progress(f.source,0,starts[1]+second/2,total);row=f.history("111111");check(row.getInt("index")==2&&row.getLong("positionMs")==second/2&&row.getLong("durationMs")==second,"merged second episode maps both position and duration");
            progress(f.source,0,starts[2]+third/2,0);row=f.history("111111");check(row.getInt("index")==3&&row.getLong("durationMs")==third,"merged known MP4 timing remains valid when receiver total is unknown");
            progress(f.source,0,total+500,total);row=f.history("111111");check(row.getInt("index")==3&&row.getLong("positionMs")==third&&row.getLong("durationMs")==third,"merged overshoot clamps to actual final episode boundary");
        }finally{PlaybackSources.mediaFixture=null;}
    }
    public static void main(String[] args)throws Exception{dlnaSession();browserSession();service(false);service(true);mergedService();System.out.println("CAST_PROGRESS_DURATION_OK checks="+checks+" actualSessions=true actualServiceFileSources=true mergedDuration=perEpisode legacyTwoArg=compatible networkRequests=0");}
}
