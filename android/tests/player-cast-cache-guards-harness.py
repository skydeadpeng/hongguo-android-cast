#!/usr/bin/env python3
"""Execute complete PlayerActivity cache methods with deterministic cache and queue boundaries."""
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text()

def method(name):
    found = re.search(r'(?m)^    private [^\n{]*\b' + re.escape(name) + r'\([^\n;]*\)\s*\{', source)
    if not found:
        raise ValueError('missing production method ' + name)
    start = found.start()
    cursor = source.index('{', start)
    depth, quote, escaped, comment = 0, None, False, None
    while cursor < len(source):
        ch, pair = source[cursor], source[cursor:cursor + 2]
        if comment == 'line':
            if ch == '\n': comment = None
        elif comment == 'block':
            if pair == '*/': comment = None; cursor += 1
        elif quote:
            if escaped: escaped = False
            elif ch == '\\': escaped = True
            elif ch == quote: quote = None
        elif pair == '//': comment = 'line'; cursor += 1
        elif pair == '/*': comment = 'block'; cursor += 1
        elif ch in ('"', "'"): quote = ch
        elif ch == '{': depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return source[start:cursor + 1].replace('private ', '', 1)
        cursor += 1
    raise ValueError('unclosed production method ' + name)

template = '''package io.github.jackotom.hongguo.android;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import org.json.*;
final class PlayerCastCacheGuardsHarness {
 final GuardWorker cacheWorker=new GuardWorker(),main=new GuardWorker();final GuardCache cache=new GuardCache();
 final Set<String> caching=new HashSet<>();final List<JSONObject> queue=new ArrayList<>();JSONObject series,queueSeries;PlaybackCache.QueueWindow localWindow=new PlaybackCache.QueueWindow(cache);
 boolean cleanup=true,offline,destroyed,castWasActive,autoplay=true;int current,generation=7,automaticCeiling;String actualQuality="720p";Uri manual;
 PlayerCastCacheGuardsHarness()throws Exception {PlaybackSources.reset();series=new JSONObject().put("seriesId","fixture-series");for(int i=1;i<=8;i++)queue.add(new JSONObject().put("vid","v"+i).put("index",i));}
 Uri downloaded(JSONObject target){return manual;}
 void pump(){for(int i=0;i<8;i++){cacheWorker.flush();main.flush();}}
 METHODS
}
final class GuardWorker {
 final Deque<Runnable> pending=new ArrayDeque<>();boolean reject;
 void execute(Runnable task){if(reject)throw new RejectedExecutionException("fixture closed");pending.addLast(task);}
 void post(Runnable task){execute(task);}void flush(){int budget=100;while(!pending.isEmpty()){if(--budget==0)throw new AssertionError("unbounded work queue");pending.removeFirst().run();}}
}
final class Uri {final URI value;Uri(String raw){value=URI.create(raw);}static Uri parse(String raw){return new Uri(raw);}String getPath(){return value.getPath();}}
final class PlaybackCache {
 static final class QueueWindow {int position;final GuardCache cache;QueueWindow(GuardCache c){cache=c;}void moveTo(int p){position=p;}void prune(){cache.prunes.add((position+1)+":1");}void close(){}}
}
final class GuardCache {
 final List<String> fetches=new ArrayList<>(),prunes=new ArrayList<>(),ownerFetches=new ArrayList<>();final Set<String> hits=new HashSet<>();final List<Integer> transferCeilings=new ArrayList<>(),lookupCeilings=new ArrayList<>();final List<PlaybackSources.Plan> capturedPlans=new ArrayList<>();Runnable duringFetch;
 File find(String series,String vid,int index,String quality,int ceiling){lookupCeilings.add(ceiling);return hits.contains(vid+":"+quality+(ceiling>0?":"+ceiling:""))?new File("fixture-bound.mp4"):null;}
 File find(String series,String vid,int index,String quality){return hits.contains(vid+":"+quality)?new File("fixture.mp4"):null;}
 void fetch(String series,String vid,int index,PlaybackSources.Plan plan,String quality){capturedPlans.add(plan);transferCeilings.add(plan.playbackCeiling);fetch(series,vid,index,plan.url,quality);}
 void fetch(String series,String vid,int index,String url,String quality){fetches.add(vid+":"+quality);ownerFetches.add(series+":"+vid+":"+index+":"+quality);if(duringFetch!=null)duringFetch.run();}
 void prune(String series,int index,int ahead){prunes.add(index+":"+ahead);}
}
final class PlaybackSources {
 static final class Plan {final String url;final int playbackCeiling;Plan(String url){this(url,0);}Plan(String url,int ceiling){this.url=url;this.playbackCeiling=ceiling;}}
 static final List<String> calls=new ArrayList<>(),owners=new ArrayList<>();static Runnable duringResolve;static boolean fail;
 static void reset(){calls.clear();owners.clear();duringResolve=null;fail=false;}
 static Plan find(String url,String quality){return null;}
 static Plan resolvePlayback(String series,String vid,String quality,int ceiling,boolean fresh,boolean failedWeb)throws IOException{Plan p=resolve(series,vid,quality);return new Plan(p.url,ceiling);}
 static Plan resolve(String series,String vid,String quality)throws IOException{calls.add(vid+":"+quality);owners.add(series+":"+vid+":"+quality);if(duringResolve!=null)duringResolve.run();if(fail)throw new IOException("fixture lookup failure");return new Plan("https://fixture.invalid/"+vid+".mp4");}
}
'''
names=['pruneCache','cacheEpisode','schedulePrefetch']
if 'PlaybackPlaylist.' in source:names+=['owner','mediaKey']
if 'private static int stageCeiling(' in source:names+=['stageCeiling']
Path(sys.argv[2]).write_text(template.replace('METHODS', '\n'.join(method(name) for name in names)))
print('PLAYER_CAST_CACHE_BINDING actualMethods=pruneCache,cacheEpisode,schedulePrefetch boundaryDoubles=executor,cache,source networkRequests=0')
