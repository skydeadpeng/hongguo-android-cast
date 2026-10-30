#!/usr/bin/env python3
from pathlib import Path
import sys,re
source=Path(sys.argv[1]).read_text()
def method(name):
 m=re.search(r'^    private JSONObject '+name+r'\([^\n]*\) throws Exception \{',source,re.M);assert m,name
 start=m.start();i=source.index('{',start);depth=0;quote=None;escape=False
 while i<len(source):
  c=source[i]
  if quote:
   if escape:escape=False
   elif c=='\\':escape=True
   elif c==quote:quote=None
  elif c in ('"',"'"):quote=c
  elif c=='{':depth+=1
  elif c=='}':
   depth-=1
   if depth==0:return source[start:i+1].replace('private ','',1)
  i+=1
 raise AssertionError('unclosed '+name)
text='''package io.github.jackotom.hongguo.android;
import org.json.*;import java.io.*;
class AccountSync{
 static final AccountSync INSTANCE=new AccountSync();String uid="A";long epoch;boolean ready=true;
 static AccountSync get(){return INSTANCE;}String ownerStamp(){return "official:"+uid;}
 static class Selection{final String uid;final long epoch;Selection(String u,long e){uid=u;epoch=e;}}
 Selection captureSelection()throws IOException{if(!ready)throw new IOException("selection not ready");return new Selection(uid,epoch);}
 void requireCurrent(Selection s)throws IOException{if(!ready||!uid.equals(s.uid)||epoch!=s.epoch)throw new IOException("owner changed");}
}
class LibraryStore{
 static Runnable duringRead;static int reads;
 LibraryStore(Object ignored){}
 JSONObject library()throws Exception{reads++;if(duringRead!=null)duringRead.run();return new JSONObject().put("favorites",new JSONArray().put("secret of B")).put("history",new JSONArray());}
 JSONObject seriesHistory(String sid)throws Exception{reads++;if(duringRead!=null)duringRead.run();return new JSONObject().put("seriesId",sid).put("positionMs",4321);}
}
public class AccountLibraryReadHarness{
 final Object activity=new Object();static int checks,groups;static void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
 METHODS
 interface Read{JSONObject get()throws Exception;}
 static void denied(Read r,String message)throws Exception{boolean denied=false;try{r.get();}catch(Exception expected){denied=true;}check(denied,message);}
 static void reset(){AccountSync a=AccountSync.get();a.uid="A";a.epoch=0;a.ready=true;LibraryStore.reads=0;LibraryStore.duringRead=null;}
 public static void main(String[]args)throws Exception{
  AccountLibraryReadHarness h=new AccountLibraryReadHarness();JSONObject params=new JSONObject().put("seriesId","123456789");
  for(boolean point:new boolean[]{false,true}){reset();Read read=point?()->h.currentSeriesHistory(params):h::currentLibrary;LibraryStore.duringRead=()->{AccountSync a=AccountSync.get();a.uid="B";a.epoch++;a.uid="A";a.epoch++;};denied(read,"A->B->A library read must reject foreign snapshot, point="+point);groups++;}
  for(boolean point:new boolean[]{false,true}){reset();Read read=point?()->h.currentSeriesHistory(params):h::currentLibrary;JSONObject result=read.get();check(result.getString("owner").equals("official:A"),"stable owner remains exposed");check(LibraryStore.reads==1,"one read only");groups++;}
  for(boolean point:new boolean[]{false,true}){reset();Read read=point?()->h.currentSeriesHistory(params):h::currentLibrary;LibraryStore.duringRead=()->{AccountSync.get().uid="B";AccountSync.get().epoch++;};denied(read,"ordinary owner change denied");groups++;}
  reset();AccountSync.get().ready=false;denied(h::currentLibrary,"selection gap fails instead of guest fallback");check(LibraryStore.reads==0,"unready library not read");groups++;
  reset();denied(()->h.currentSeriesHistory(new JSONObject().put("seriesId","invalid")),"invalid id rejected before read");check(LibraryStore.reads==0,"no invalid lookup");groups++;
  System.out.println("ACCOUNT_LIBRARY_READ_OK groups="+groups+" checks="+checks+" actualNativeMethods=true ownerEpoch=true ABA=blocked networkRequests=0");
 }
}
'''.replace('METHODS',method('currentLibrary')+'\n'+method('currentSeriesHistory'))
Path(sys.argv[2]).write_text(text);print('ACCOUNT_LIBRARY_READ_BINDING actualMethods=true source='+sys.argv[1])
