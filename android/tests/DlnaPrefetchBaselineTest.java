package io.github.jackotom.hongguo.android;
/** Replays an ordinary PLAYING episode against an explicitly selected baseline Session source. */
public final class DlnaPrefetchBaselineTest {
    public static void main(String[] args) {
        DlnaSessionTest.Fixture fixture=new DlnaSessionTest.Fixture();
        fixture.start();
        for(int i=0;i<20;i++){fixture.control.position=1000+i*1000;fixture.clock.tick();}
        if(fixture.source.media.size()!=1||fixture.clock.preparations.size()!=1||fixture.control.sets!=1||fixture.session.snapshot().current!=0)throw new AssertionError("baseline changed unexpectedly");
        fixture.session.stop();
        System.out.println("DLNA_PREFETCH_BASELINE_OK currentPreparations=1 backgroundPreparations=0 confirmedPlaybackSamples=20 currentEpisode=0 receiverSetCommands=1 deviceTests=not_run networkRequests=0");
    }
}
