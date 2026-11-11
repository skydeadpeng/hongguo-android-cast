#!/usr/bin/env python3
"""Bind exact production FileSource bodies to minimal Android context and mock download boundaries."""
from pathlib import Path
import re,sys
src,out=Path(sys.argv[1]),Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
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
for production,harness,first,session in [('DlnaCastService','DlnaPlaylistSourceHarness','new DlnaDevice("uuid:fixture","Synthetic receiver","192.168.1.8",new java.net.URI("http://192.168.1.8/control"),"urn:schemas-upnp-org:service:AVTransport:1")','DlnaSession'),('BrowserCastService','BrowserPlaylistSourceHarness','"192.168.1.2"','BrowserCastSession')]:
 source=(src/(production+'.java')).read_text()
 request=block(source,source.index('    private static final class Request')).replace('private static final class Request','static final class Request')
 connect=block(source,source.index('    public void connect('))
 clean=block(source,source.index('    private static String cleanMessage('))
 filesource=block(source,source.index('    private final class FileSource')).replace('private final class FileSource','final class FileSource').replace(production+'.this',harness+'.this')
 windowmap=''
 marker='    private static final class WindowMap'
 if marker in source: windowmap=block(source,source.index(marker)).replace('private static final class WindowMap','static final class WindowMap')
 result='''package io.github.jackotom.hongguo.android;
import java.io.*;import java.util.*;import java.util.concurrent.*;import org.json.*;
final class HARNESS extends CastPlaylistContext {
 Request request,pending;FileSource source;SESSION session;boolean destroyed;DlnaSession.Snapshot snapshot=DlnaSession.Snapshot.idle("");
 HARNESS(File directory){super(directory);}
 DlnaSession.Source make(JSONObject series,List<JSONObject> episodes,int current,boolean autoplay)throws Exception {
  return make(series,episodes,current,autoplay,1);
 }
 DlnaSession.Source make(JSONObject series,List<JSONObject> episodes,int current,boolean autoplay,int mergeWindow)throws Exception {
  JSONArray input=new JSONArray();for(JSONObject row:episodes)input.put(row);
  connect(FIRST,series,input,current,"1080p",autoplay,true,0EXTRA);
  if(request==null)throw new IOException(snapshot.error);return source;
 }
 void begin(Request value){request=value;source=new FileSource(value);}
 void publish(DlnaSession.Snapshot value){snapshot=value;}
 String identity(){return request.identity;}
 String diagnostics(){return source.diagnostics();}
 WINDOWMAP
 REQUEST
 CONNECT
 CLEAN
 SOURCE
}
'''.replace('HARNESS',harness).replace('FIRST',first).replace('EXTRA',',mergeWindow' if marker in source else '').replace('REQUEST',request).replace('CONNECT',connect).replace('CLEAN',clean).replace('SESSION',session).replace('SOURCE',filesource).replace(' WINDOWMAP',' '+windowmap)
 (out/(harness+'.java')).write_text(result)
android=out/'android';(android/'content').mkdir(parents=True,exist_ok=True);(android/'os').mkdir(parents=True,exist_ok=True)
(android/'content'/'Context.java').write_text('''package android.content;
public abstract class Context {
 public static final int MODE_PRIVATE=0;
 public Context getApplicationContext(){return this;}
 public abstract java.io.File getCacheDir();
 public abstract java.io.File getExternalFilesDir(String type);
 public abstract SharedPreferences getSharedPreferences(String name,int mode);
}
''')
(android/'content'/'SharedPreferences.java').write_text('''package android.content;
public interface SharedPreferences {
 java.util.Map<String,?> getAll();String getString(String key,String fallback);Editor edit();
 interface Editor {
 Editor putString(String key,String value);Editor putBoolean(String key,boolean value);
 Editor putInt(String key,int value);Editor putLong(String key,long value);Editor putFloat(String key,float value);
 Editor putStringSet(String key,java.util.Set<String> value);Editor remove(String key);
 void apply();boolean commit();
 }
}
''')
(android/'os'/'Environment.java').write_text('package android.os;public final class Environment{public static final String DIRECTORY_DOWNLOADS="Download";}')
print('CAST_PLAYLIST_SOURCE_BINDING connectFileSource=actualProduction AndroidContext=stub resolverDownloader=mock sessionCacheHistoryPrefetch=actual networkRequests=0')
