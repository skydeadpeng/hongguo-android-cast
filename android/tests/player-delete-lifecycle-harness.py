"""Execute production onDestroy body with observable media-handle cleanup doubles."""
from pathlib import Path
import sys
source=Path(sys.argv[1]).read_text();start=source.index('    @Override protected void onDestroy() {');i=source.index('{',start);depth=1;j=i+1
while depth:
 if source[j]=='{':depth+=1
 if source[j]=='}':depth-=1
 j+=1
method=source[i+1:j-1].replace('super.onDestroy();','superDestroyed = true;')
out='''import java.util.concurrent.atomic.AtomicInteger;
public final class PlayerDeleteLifecycleHarness {
 static final AtomicInteger LIVE_PLAYERS = new AtomicInteger();static int checks;
 boolean registeredPlayer=true,destroyed,superDestroyed,decoderPreparing;int generation,failAt;int cleanup;
 final Resource danmaku=new Resource(),controlsHud=new Resource(),video=new Resource(),main=new Resource(),resolver=new Resource(),cacheWorker=new Resource();
 final class Resource {void close(){touch();}void destroy(){touch();}void stopPlayback(){touch();}void removeCallbacksAndMessages(Object o){touch();}void shutdownNow(){touch();}}
 void touch(){checks++;if(LIVE_PLAYERS.get()!=1)throw new AssertionError("deletion became possible before media cleanup");cleanup++;if(cleanup==failAt)throw new IllegalStateException("injected cleanup failure");}
 void cancelPlaybackWatchdogs(){touch();}void detachCast(){touch();}void cancelQualitySwitch(){touch();}void discardPendingMedia(){touch();}void releaseCurrent(){touch();}void closeLocalWindow(){touch();}
 void onDestroy(){BODY}
 public static void main(String[]args){
  LIVE_PLAYERS.set(1);PlayerDeleteLifecycleHarness p=new PlayerDeleteLifecycleHarness();p.onDestroy();checks++;if(LIVE_PLAYERS.get()!=0||p.registeredPlayer||!p.superDestroyed||p.cleanup!=12)throw new AssertionError("final state "+p.cleanup);
  LIVE_PLAYERS.set(1);p=new PlayerDeleteLifecycleHarness();p.failAt=7;try{p.onDestroy();throw new AssertionError();}catch(IllegalStateException expected){}checks++;if(LIVE_PLAYERS.get()!=1||!p.registeredPlayer)throw new AssertionError("failed cleanup must remain busy");
  System.out.println("PLAYER_DELETE_LIFECYCLE_OK checks="+checks+" mediaHandlesReleasedBeforeDelete=true");
 }
}
'''.replace('BODY',method)
Path(sys.argv[2]).write_text(out)
