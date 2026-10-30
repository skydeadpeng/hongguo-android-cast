package io.github.jackotom.hongguo.android;
/** Compiles and executes the actual PlayerActivity recovery method against state doubles. */
public final class PlayerRecoveryStateTest {
 static int checks;
 static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 static PlayerRecoveryHarness player(){PlayerRecoveryHarness p=new PlayerRecoveryHarness();p.recoveryBudget.reset(7);p.current=4;return p;}
 public static void main(String[]args){
  PlayerRecoveryHarness p=player();p.lastGoodPosition=81000;p.video.position=99000;p.resumeAt=120000;p.userPaused=true;p.currentWeb=true;p.mediaPlayer.rate=2;p.requestedSpeed=.75f;
  check(p.recoverMedia(7,1,-1004,"stream failed"),"eligible real media failure enters recovery");
  check(p.calls==1&&p.selected==4,"recovery stays on same selected episode, never skips");
  check(p.position==99000,"actual playback position preserved");
  check(p.paused&&p.recovering&&p.web,"pause state and failed-web identity preserved");
  check(p.carriedSpeed==2,"actual confirmed 2x wins over unrelated preference");
  check("stream failed".equals(p.lastMediaError),"original error retained");
  check(!p.recoverMedia(7,1,-1004,"again")&&p.calls==1,"duplicate callback cannot add a recovery");
  PlayerRecoveryHarness zero=player();zero.lastGoodPosition=23000;zero.resumeAt=120000;zero.video.position=0;zero.actualSpeed=1.5f;zero.mediaPlayer.rate=Float.NaN;zero.requestedSpeed=2;
  check(zero.recoverMedia(7,1,-1007,"cache failed"),"damaged cached media eligible");
  check(zero.position==23000,"zero error-position uses last good point, not older resume history");
  check(zero.carriedSpeed==1.5f,"error-state decoder read failure preserves last actually confirmed speed");
  check(!zero.paused&&!zero.web,"playing App/cache recovery flags preserved");
  PlayerRecoveryHarness broken=player();broken.lastGoodPosition=34000;broken.video.throwsPosition=true;
  check(broken.recoverMedia(7,1,-1007,"broken")&&broken.position==34000,"error-state position exception falls back to last good progress");
  for(int mode=0;mode<6;mode++){
   PlayerRecoveryHarness skip=player();skip.offline=mode==0;skip.currentManual=mode==1;skip.destroyed=mode==4;skip.castWasActive=mode==5;
   check(!skip.recoverMedia(mode==3?6:7,1,mode==2?-1010:-1004,"skip"),"offline/manual/unsupported/stale/destroy/cast never automatically recover");
   check(skip.calls==0,"excluded failures do not call source or mutate episode");
  }
  System.out.println("PASS PlayerRecoveryStateTest checks="+checks+" actualPlayerMethod=true sameEpisode=true progressPauseConfirmedSpeedPreserved=true manualNeverRecovered=true");
 }
}
