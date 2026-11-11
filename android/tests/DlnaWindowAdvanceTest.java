package io.github.jackotom.hongguo.android;

import java.io.*;
import java.util.*;

/**
 * Merged-window advance: one published media covering windowCount queue
 * positions must resume the queue after the whole window, never per episode.
 */
final class DlnaWindowAdvanceTest {
    static int checks;
    static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}

    /** Source whose prepared media at a position covers windowAt(position) episodes. */
    static final class WindowSource implements DlnaSession.Source {
        final Map<Integer,Integer> windows=new HashMap<>();final List<Integer> confirmed=new ArrayList<>(),progress=new ArrayList<>();
        int windowAt(int position){Integer value=windows.get(position);return value==null?1:value;}
        public DlnaSession.Prepared prepare(int position,String quality,java.util.function.BooleanSupplier alive)throws Exception{
            int span=windowAt(position);
            File file=new File("merged-"+position+"-"+span+".mp4");
            return new DlnaSession.Prepared(file,"剧 · 第 "+(position+1)+(span>1?"-"+(position+span):"")+" 集",quality,position+1,span,()->{},()->{});
        }
        public void confirmed(int position,long value,boolean cleanup){confirmed.add(position);}
        public void progress(int position,long value){progress.add(position);}
    }

    static final class Fixture {
        final DlnaSessionTest.Clock clock=new DlnaSessionTest.Clock();
        final WindowSource source=new WindowSource();
        final DlnaSessionTest.Control control=new DlnaSessionTest.Control();
        final DlnaSessionTest.Server server=new DlnaSessionTest.Server();
        final List<DlnaSession.Snapshot> snapshots=new ArrayList<>();
        final DlnaSession session=new DlnaSession(clock,control,source,server,snapshots::add,"123456","合并测试剧","合成电视",8,"1080p",true,true);
        void start(){session.start(0,0);clock.prepare(0,false);}
    }

    public static void main(String[] arguments){
        mergedWindowAdvancesBySpan();
        partialWindowAtQueueEndAdvancesByOne();
        System.out.println("DLNA_WINDOW_ADVANCE_OK checks="+checks);
    }

    static void mergedWindowAdvancesBySpan(){
        Fixture f=new Fixture();
        f.source.windows.put(0,3);f.source.windows.put(3,3);
        f.start();
        check(f.session.snapshot().current==0,"window starts at selected position");
        f.clock.now=6000;f.control.position=99000;f.clock.tick();
        check(f.source.progress.contains(0),"window progress still reports the window's first position");
        f.control.state="STOPPED";f.clock.tick();
        check(f.session.snapshot().targetCurrent==3,"natural end of a 3-episode window targets position 3, not 1");
        check(f.clock.preparations.size()==2,"exactly one follow-up preparation queued");
        f.clock.prepare(1,false);f.control.state="PLAYING";f.clock.tick();
        check(f.session.snapshot().current==3,"second window adopts at position 3");
        // The second merged window ends at the queue edge: only 5 episodes remain from 3, so 3,4,5 then advance to 6.
        f.control.position=99000;f.clock.tick();f.control.state="STOPPED";f.clock.tick();
        f.clock.prepare(2,false);f.control.state="PLAYING";f.clock.tick();
        check(f.session.snapshot().current==6,"consecutive windows chain without overlap");
        f.session.stop();
    }

    static void partialWindowAtQueueEndAdvancesByOne(){
        Fixture f=new Fixture();
        f.source.windows.put(0,7); // queue of 8: window at 0 shrinks to 7? then end lands at 7 with a single episode
        f.start();
        f.control.position=99000;f.clock.tick();f.control.state="STOPPED";f.clock.tick();
        f.clock.prepare(1,false);f.control.state="PLAYING";f.clock.tick();
        check(f.session.snapshot().current==7,"window beyond queue end lands on the last position");
        // Last position returns a single-episode window; its end must stop at the queue edge.
        f.control.position=99000;f.clock.tick();f.control.state="STOPPED";f.clock.tick();
        check(f.clock.preparations.size()==2,"final single-episode window never queues another preparation");
        check(f.session.snapshot().state.equals("ENDED"),"session reports ENDED at the queue edge");
        f.session.stop();
    }
}
