package io.github.jackotom.hongguo.android;
/** Executes actual mobile adapter methods with only service and framework boundaries doubled. */
public final class BrowserCastUiTest {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static DlnaSession.Snapshot state(String name){DlnaSession.Snapshot s=new DlnaSession.Snapshot();s.active=true;s.state=name;s.playing=name.equals("PLAYING");s.deviceName="Mac Safari";s.durationMs=100000;s.positionMs=39000;return s;}
 public static void main(String[]args)throws Exception{
  DlnaUiHarness failure=new DlnaUiHarness();failure.showBrowserPairWhenReady=true;DlnaSession.Snapshot startupError=new DlnaSession.Snapshot();startupError.error="启动失败";Toast.shown.clear();failure.onProtocolCastSnapshot(true,startupError);check(Toast.shown.size()==1&&!failure.showBrowserPairWhenReady&&!failure.browserCastSelected&&failure.video.stops==0,"startup error visible without hijacking local play");failure.detachCast();
  DlnaUiHarness h=new DlnaUiHarness();h.browserCastService=new BrowserCastService();h.browserCastBound=true;
  DlnaSession.Snapshot waiting=state("WAITING_RECEIVER");waiting.durationMs=0;waiting.positionMs=27000;h.browserCastService.state=waiting;h.showBrowserPairWhenReady=true;
  h.onProtocolCastSnapshot(true,waiting);check(h.browserCastSelected&&h.casting()&&h.video.stops==1,"browser takes over once");check(h.pairDialogs==1&&!h.showBrowserPairWhenReady,"pair dialog only once after server ready");
  h.onProtocolCastSnapshot(false,new DlnaSession.Snapshot());check(h.browserCastSelected&&h.casting()&&h.loads==0,"other idle service never drops browser cast");
  h.onProtocolCastSnapshot(true,waiting);check(h.pairDialogs==1&&h.video.stops==1,"poll does not reopen pairing or stop local again");
  DlnaSession.Snapshot playing=state("PLAYING");h.browserCastService.state=playing;h.onProtocolCastSnapshot(true,playing);check(h.status.text.contains("浏览器 · Mac Safari")&&!h.status.text.contains("DLNA"),"truthful protocol label");check(h.seek.enabled&&h.prepared,"confirmed browser enables seek");
  h.loadAt(2,null,0);h.castCommand("toggle",0);h.castCommand("play",0);h.castCommand("seek",45000);h.castCommand("seekRelative",-90000);h.castCommand("autoplay",0);
  check(h.browserCastService.calls.toString().equals("[select:2, pause, play, seek:45000, seek:0, autoplay:false]"),"all phone commands route browser");check(h.castService.calls.isEmpty(),"new commands never sent to DLNA");
  for(String name:new String[]{"WAITING_RECEIVER_PLAY","WAITING_GESTURE","PREPARING","DISCONNECTED","ERROR"}){
   DlnaSession.Snapshot pending=state(name);pending.current=0;pending.targetCurrent=1;h.onProtocolCastSnapshot(true,pending);
   check(!h.seek.enabled&&!h.prepared,"unconfirmed candidate disallows old-timeline seek "+name);check(h.status.text.contains("正在切到第 2 集"),"target separate from confirmed current");
  }
  DlnaSession.Snapshot blocked=state("WAITING_GESTURE");h.onProtocolCastSnapshot(true,blocked);check(h.status.text.contains("点按开始播放")&&!h.completed,"blocked autoplay visible, not ended");
  h.onProtocolCastSnapshot(true,playing);DlnaSession.Snapshot idle=new DlnaSession.Snapshot();h.browserCastService.state=idle;h.onProtocolCastSnapshot(true,idle);check(!h.browserCastSelected&&!h.casting()&&h.loads==1&&h.loadedPosition==39000,"stop restores last confirmed position");
  h.onProtocolCastSnapshot(false,playing);check(!h.browserCastSelected&&h.casting(),"old DLNA still attaches after browser stop");int stops=h.video.stops;h.browserCastService.state=playing;h.castService.state=playing;h.onProtocolCastSnapshot(true,playing);check(!h.browserCastSelected&&h.video.stops==stops,"simultaneous other service cannot hijack live DLNA");
  h.browserCastSelected=true;h.castSnapshot=playing;BuildConfig.IS_TV=true;check(!h.casting(),"TV has no browser sender");BuildConfig.IS_TV=false;
  BrowserCastService old=h.browserCastService;h.detachCast();check(h.browserCastService==null&&!h.browserCastBound&&old.calls.contains("removeListener")&&!old.calls.contains("stopCast"),"Activity detach leaves foreground playback alive");
  System.out.println("BROWSER_CAST_UI_OK assertions="+checks+" actualPlayerMethods=true deviceTests=not_run");
 }
}
