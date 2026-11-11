#!/usr/bin/env python3
"""Extract exact production merge and close methods; replace only Android/cache preparation boundaries."""
from pathlib import Path
import sys
src,out=Path(sys.argv[1]),Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
source=(src/'DlnaCastService.java').read_text()
def block(marker):
 start=source.index(marker);cursor=source.index('{',start);depth=0;quote=None;escape=False;comment=None
 while cursor<len(source):
  ch=source[cursor];pair=source[cursor:cursor+2]
  if comment=='line':
   if ch=='\n':comment=None
  elif comment=='block':
   if pair=='*/':comment=None;cursor+=1
  elif quote:
   if escape:escape=False
   elif ch=='\\':escape=True
   elif ch==quote:quote=None
  elif pair=='//':comment='line';cursor+=1
  elif pair=='/*':comment='block';cursor+=1
  elif ch in ('"',"'"):quote=ch
  elif ch=='{':depth+=1
  elif ch=='}':
   depth-=1
   if depth==0:return source[start:cursor+1]
  cursor+=1
 raise ValueError(marker)
methods='\n'.join(block(x) for x in ['private DlnaSession.Prepared prepareMerged(', 'private java.util.List<File> filesOf(', 'public void close(){prefetch.close();'])
if 'private void deleteMergedDirectory()' in source:methods+='\n'+block('private void deleteMergedDirectory()')
text='''package io.github.jackotom.hongguo.android;
import java.io.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;import org.json.*;
final class DlnaMergedSourceHarness {
 static final class Request {final List<JSONObject> episodes=new ArrayList<>();final JSONObject series=new JSONObject();int mergeWindow=3;String identity="123456";}
 static final class WindowMap {final int position;final long[] starts;WindowMap(int p,long[] s){position=p;starts=s;}}
 static final class CloseOnly {void close(){}}
 final Request request=new Request();final File mergedDirectory;final List<File> files;
 final List<AtomicInteger> pins=new ArrayList<>();final CloseOnly prefetch=new CloseOnly(),window=new CloseOnly();
 WindowMap pendingMap,activeMap;
 DlnaMergedSourceHarness(File directory,List<File> files)throws Exception {
  mergedDirectory=directory;this.files=files;request.series.put("seriesId","123456").put("title","本季");
  for(int i=0;i<files.size();i++)request.episodes.add(new JSONObject().put("vid",String.valueOf(i+1)).put("index",i+1));
 }
 DlnaSession.Prepared prepare(int position,java.util.function.BooleanSupplier alive)throws Exception{return prepareMerged(position,"1080p",alive);}
 DlnaSession.Prepared prepareMedia(int position,String quality,boolean foreground,java.util.function.BooleanSupplier alive) {
  AtomicInteger pin=new AtomicInteger(1);pins.add(pin);
  return new DlnaSession.Prepared(files.get(position),"分集",quality,position+1,()->pin.incrementAndGet(),()->{if(pin.decrementAndGet()<0)throw new AssertionError("negative child lease");});
 }
 METHODS
}
'''.replace(' METHODS',methods)
(out/'DlnaMergedSourceHarness.java').write_text(text)
print('DLNA_MERGED_SOURCE_BINDING prepareMerged=actualProduction close=actualProduction concatPreparedServer=actual cacheBoundary=fixture externalRequests=0')
