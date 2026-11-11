#!/usr/bin/env python3
"""Bind actual Android Service lifecycle/dispatch methods to offline system and network boundaries."""
from pathlib import Path
import re,sys
src,out=Path(sys.argv[1]),Path(sys.argv[2]);service=(src/'DlnaCastService.java').read_text();library=(src/'LibraryStore.java').read_text()
def block(source,start):
 cursor=source.index('{',start);depth=0;quote=None;escape=False;comment=None
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
 raise ValueError('unclosed production block')
def method(source,name):
 found=re.search(r'(?m)^    (?! )(?:@Override )?(?:(?:private|public|protected) )?(?:static )?[^\n{;]*\b'+re.escape(name)+r'\([^\n;]*\)[^{\n]*\{',source)
 if not found:raise ValueError('missing actual method '+name)
 return block(source,found.start()).replace('private ','',1)
methods='\n'.join(method(service,name) for name in ['onBind','onStartCommand','snapshot','diagnostics','addListener','removeListener','connect','accept','publish','select','play','pause','seek','setAutoplay','stopCast','cleanMessage','onDestroy'])
request=block(service,service.index('    private static final class Request')).replace('private static final class Request','static final class Request')
text='''package io.github.jackotom.hongguo.android;
import java.io.*;import java.util.*;import java.util.concurrent.*;import org.json.*;
class DlnaServiceHarness extends CastServiceBase {
 static final String KEEP="io.github.jackotom.hongguo.DLNA_KEEP",STOP="io.github.jackotom.hongguo.DLNA_STOP";
 interface Listener {void onSnapshot(DlnaSession.Snapshot value);}
 final IBinder binder=new Binder();final CastHandler main=new CastHandler();final Set<Listener> listeners=new CopyOnWriteArraySet<>();
 final CastLifecycle lifecycle=new CastLifecycle();java.util.function.Supplier<String> diagnosticReader;DlnaSession session;DlnaSession.Snapshot snapshot=DlnaSession.Snapshot.idle("");boolean destroyed;int serial;Future<?> connecting;Request pending;
 int foregrounds,releases,begins;Request lastRequest;
 void ensureForeground(){foregrounds++;}void releaseLocks(){releases++;}
 void begin(Request request){begins++;lastRequest=request;pending=null;serial++;snapshot=DlnaSession.Snapshot.opening(request.series.optString("seriesId"),request.series.optString("title"),request.device.name,request.current,request.quality,request.autoplay);}
 REQUEST
 METHODS
}
class DlnaServiceLibrary {
 LIBRARY
}
'''.replace('REQUEST',request).replace('METHODS',methods).replace('LIBRARY','\n'.join(method(library,name) for name in ['text','series','episode','episodes']))
text=text.replace('LibraryStore.','DlnaServiceLibrary.')
playlist=src/'PlaybackPlaylist.java'
if playlist.exists():
 body=playlist.read_text()
 imports='\n'.join(re.findall(r'(?m)^import [^;]+;',body))
 body=re.sub(r'(?m)^(?:package|import) [^;]+;\s*','',body)
 text=text.replace('import java.io.*;',imports+'\nimport java.io.*;',1)+'\n'+body
 if 'final String quality,identity;' in request:
  text=text.replace('Snapshot.opening(request.series.optString("seriesId"),','Snapshot.opening(request.identity,')
out.write_text(text)
print('DLNA_SERVICE_BINDING actualLifecycleMethods=true actualQueueValidation=true systemAndConnect=fakes networkRequests=0')
