package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.json.*;

/** Exercises the real LibraryStore with Android-like memory-before-disk commit semantics. */
public final class SearchHistoryStoreTest {
    private static final String FILE="hongguo_library_v1",KEY="search_keywords_v1";
    private static int checks,passed,failed;
    private interface Checked {void run()throws Exception;}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void group(String name,Checked test){try{test.run();passed++;System.out.println("PASS "+name);}catch(Throwable error){failed++;System.out.println("FAIL "+name+": "+error.getClass().getSimpleName()+" "+error.getMessage());}}
    private static JSONObject call(LibraryStore store,String method,JSONObject params)throws Exception {
        try {
            Method target=params==null?LibraryStore.class.getDeclaredMethod(method):LibraryStore.class.getDeclaredMethod(method,JSONObject.class);
            return(JSONObject)(params==null?target.invoke(store):target.invoke(store,params));
        }catch(NoSuchMethodException baseline){throw new AssertionError("missing native search-history API: "+method);}
        catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw(Exception)cause;throw(Error)cause;}
    }
    private static JSONObject mutate(LibraryStore store,String method,Object keyword)throws Exception{return call(store,method,new JSONObject().put("keyword",keyword));}
    private static JSONObject read(LibraryStore store)throws Exception{return call(store,"searchHistory",null);}
    private static List<String> words(JSONObject result)throws Exception {
        check(result.length()==1&&result.opt("keywords") instanceof JSONArray,"all RPC results have exactly {keywords:[strings]}");
        List<String> values=new ArrayList<>();JSONArray array=result.getJSONArray("keywords");
        for(int i=0;i<array.length();i++){check(array.get(i) instanceof String,"history never coerces non-string records");values.add(array.getString(i));}
        return values;
    }
    private static void equal(JSONObject result,String...expected)throws Exception{check(words(result).equals(Arrays.asList(expected)),"newest-first search keywords match expected");}
    private static void rejects(Checked action,String message)throws Exception{try{action.run();}catch(Exception expected){checks++;return;}throw new AssertionError(message);}
    private static Map<String,Object> snapshot(Map<String,Object> data){Map<String,Object> result=new HashMap<>();for(Map.Entry<String,Object> item:data.entrySet())result.put(item.getKey(),item.getValue() instanceof Set?new HashSet<>((Set<?>)item.getValue()):item.getValue());return result;}
    static final class Prefs implements SharedPreferences {
        final Map<String,Object> disk,memory;int commits,failCommits;boolean throwCommit;
        Prefs(Map<String,Object> disk){this.disk=disk;memory=snapshot(disk);}
        public Map<String,?>getAll(){return snapshot(memory);}
        public String getString(String key,String fallback){Object value=memory.get(key);if(value==null)return fallback;if(!(value instanceof String))throw new ClassCastException("synthetic wrong preference type");return(String)value;}
        public Editor edit(){return new Edit();}
        final class Edit implements SharedPreferences.Editor {
            final Map<String,Object> writes=new HashMap<>();final Set<String> removals=new HashSet<>();
            private Editor put(String key,Object value){writes.put(key,value);removals.remove(key);return this;}
            public Editor putString(String key,String value){return value==null?remove(key):put(key,value);}
            public Editor putStringSet(String key,Set<String> value){return value==null?remove(key):put(key,new HashSet<>(value));}
            public Editor putInt(String key,int value){return put(key,value);}public Editor putLong(String key,long value){return put(key,value);}public Editor putFloat(String key,float value){return put(key,value);}public Editor putBoolean(String key,boolean value){return put(key,value);}
            public Editor remove(String key){removals.add(key);writes.remove(key);return this;}
            public boolean commit(){commits++;for(String key:removals)memory.remove(key);memory.putAll(writes);if(throwCommit){throwCommit=false;throw new IllegalStateException("synthetic disk-write failure");}if(failCommits>0){failCommits--;return false;}disk.clear();disk.putAll(snapshot(memory));return true;}
            public void apply(){commit();}
        }
    }
    static final class AppContext extends Context {
        final Map<String,Map<String,Object>>disks;final Map<String,Prefs>opened=new HashMap<>();
        AppContext(){this(new HashMap<>());}AppContext(Map<String,Map<String,Object>>disks){this.disks=disks;}
        public SharedPreferences getSharedPreferences(String name,int mode){return opened.computeIfAbsent(name,key->new Prefs(disks.computeIfAbsent(key,x->new HashMap<>())));}
        Prefs prefs(){return(Prefs)getSharedPreferences(FILE,0);}AppContext reopen(){return new AppContext(disks);}
    }
    public static void main(String[]args)throws Exception {
        group("strict submission trim and exact duplicate ordering",()->{
            AppContext context=new AppContext();LibraryStore store=new LibraryStore(context);equal(read(store));check(context.prefs().commits==0,"get never writes preferences");
            equal(mutate(store,"addSearchHistory"," \ufeff红果\u3000"),"红果");equal(mutate(store,"addSearchHistory","都市  剧"),"都市  剧","红果");equal(mutate(store,"addSearchHistory","都市 剧"),"都市 剧","都市  剧","红果");
            equal(mutate(store,"addSearchHistory"," 红果 "),"红果","都市 剧","都市  剧");mutate(store,"addSearchHistory","Case");equal(mutate(store,"addSearchHistory","case"),"case","Case","红果","都市 剧","都市  剧");
            int before=context.prefs().commits;equal(mutate(store,"addSearchHistory"," \t\r\n\u00a0\u1680\u2000\u2028\u2029\u202f\u205f\u3000\ufeff"),"case","Case","红果","都市 剧","都市  剧");check(context.prefs().commits==before,"pure ECMAScript whitespace is never saved");
        });
        group("committed history survives a new context and store without touching other records",()->{
            AppContext context=new AppContext();Prefs prefs=context.prefs();prefs.edit().putString("favorites","[{\"seriesId\":\"123456\"}]").putString("history","[{\"vid\":\"456789\"}]").putString("downloads","[{\"id\":7}]").commit();context.getSharedPreferences("player_options",0).edit().putString("quality","1080p").putString("speed","1.5").commit();Map<String,Object> neighbors=snapshot(prefs.memory);Map<String,Object> options=snapshot(context.disks.get("player_options"));
            LibraryStore store=new LibraryStore(context);mutate(store,"addSearchHistory","first");mutate(store,"addSearchHistory","second");equal(read(new LibraryStore(context.reopen())),"second","first");Map<String,Object> actual=snapshot(prefs.memory);actual.remove(KEY);check(actual.equals(neighbors)&&context.disks.get("player_options").equals(options),"favorites/watchhistory/downloads/player options are byte-for-byte unchanged");check(prefs.memory.keySet().equals(new HashSet<>(Arrays.asList("favorites","history","downloads",KEY))),"search uses one independent preference key only");
        });
        group("twenty-item bound duplicate promotion deletion and clear persist",()->{
            AppContext context=new AppContext();LibraryStore store=new LibraryStore(context);for(int i=0;i<25;i++)mutate(store,"addSearchHistory","keyword-"+i);List<String> list=words(read(store));check(list.size()==20&&list.get(0).equals("keyword-24")&&list.get(19).equals("keyword-5"),"only latest twenty unique words remain");
            list=words(mutate(store,"addSearchHistory","keyword-10"));check(list.size()==20&&list.get(0).equals("keyword-10")&&new HashSet<>(list).size()==20,"duplicate moves to front without growing array");list=words(mutate(store,"removeSearchHistory"," keyword-10 "));check(list.size()==19&&!list.contains("keyword-10"),"remove uses the same trimmed exact keyword");
            int commits=context.prefs().commits;mutate(store,"removeSearchHistory","missing");mutate(store,"removeSearchHistory"," \t ");check(context.prefs().commits==commits,"missing and blank deletes do not write or affect another word");equal(call(store,"clearSearchHistory",null));equal(read(new LibraryStore(context.reopen())));check("[]".equals(context.prefs().getString(KEY,"missing")),"clear is genuinely committed");
        });
        group("input types and lengths reject before persistence",()->{
            AppContext context=new AppContext();LibraryStore store=new LibraryStore(context);for(Object invalid:new Object[]{JSONObject.NULL,7,true,new JSONObject(),new JSONArray(),"x".repeat(101)})for(String method:new String[]{"addSearchHistory","removeSearchHistory"})rejects(()->mutate(store,method,invalid),"non-string or excessive keyword accepted");for(String method:new String[]{"addSearchHistory","removeSearchHistory"})rejects(()->call(store,method,new JSONObject()),"missing keyword accepted");check(context.prefs().commits==0,"invalid requests do not write history");
            String hundred="词".repeat(100);equal(mutate(store,"addSearchHistory",hundred),hundred);String emoji="😀".repeat(50);check(words(mutate(store,"addSearchHistory",emoji)).get(0).equals(emoji),"UTF-16 length contract agrees with browser maxlength");rejects(()->mutate(store,"addSearchHistory","😀".repeat(51)),"overlong surrogate-pair keyword accepted");
        });
        group("bad JSON old types and malformed records are repaired read-only",()->{
            AppContext context=new AppContext();Prefs prefs=context.prefs();LibraryStore store=new LibraryStore(context);for(Object old:new Object[]{"{broken",42,true,1L,2.5f,new HashSet<>(Arrays.asList("legacy"))}){prefs.memory.put(KEY,old);int before=prefs.commits;equal(read(store));check(prefs.commits==before&&Objects.equals(prefs.memory.get(KEY),old),"invalid old preference does not cause a write");}
            JSONArray mixed=new JSONArray().put(JSONObject.NULL).put(9).put(true).put(new JSONObject()).put(new JSONArray()).put("").put(" \t ").put(" good ").put("good").put("GOOD").put("x".repeat(101));for(int i=0;i<30;i++)mixed.put("old-"+i);String raw=mixed.toString();prefs.memory.put(KEY,raw);int before=prefs.commits;List<String> repaired=words(read(store));check(repaired.size()==20&&repaired.get(0).equals("good")&&repaired.get(1).equals("GOOD")&&repaired.get(19).equals("old-17"),"legacy read filters invalid records trims deduplicates and caps twenty");check(prefs.commits==before&&prefs.memory.get(KEY).equals(raw),"sanitized get never rewrites saved legacy data");
        });
        group("failed commits restore Android in-memory state and never report success",()->{
            for(String method:new String[]{"addSearchHistory","removeSearchHistory","clearSearchHistory"}){AppContext context=new AppContext();LibraryStore store=new LibraryStore(context);mutate(store,"addSearchHistory","old");Prefs prefs=context.prefs();Map<String,Object> before=snapshot(prefs.memory),disk=snapshot(prefs.disk);prefs.failCommits=2;rejects(()->call(store,method,method.equals("clearSearchHistory")?null:new JSONObject().put("keyword",method.equals("removeSearchHistory")?"old":"new")),"failed commit returned a successful RPC result");check(prefs.memory.equals(before)&&prefs.disk.equals(disk),"failed commit and failed restore-disk write still restore memory: "+method);equal(read(store),"old");equal(read(new LibraryStore(context.reopen())),"old");}
        });
        group("failed first writes and typed legacy values restore exactly",()->{
            for(Object previous:new Object[]{null,"{broken",42,2L,2.5f,true,new HashSet<>(Arrays.asList("old-set"))}){AppContext context=new AppContext();Prefs prefs=context.prefs();if(previous!=null){prefs.memory.put(KEY,previous);prefs.disk.put(KEY,previous);}Map<String,Object> prior=snapshot(prefs.memory);prefs.failCommits=2;LibraryStore store=new LibraryStore(context);rejects(()->mutate(store,"addSearchHistory","new"),"failed first or legacy write returned success");check(prefs.memory.equals(prior)&&prefs.disk.equals(prior),"rollback preserves missing key or the exact original SharedPreferences value type");}
            AppContext context=new AppContext();LibraryStore store=new LibraryStore(context);mutate(store,"addSearchHistory","old");Prefs prefs=context.prefs();prefs.throwCommit=true;rejects(()->mutate(store,"addSearchHistory","new"),"throwing disk commit returned success");equal(read(store),"old");equal(read(new LibraryStore(context.reopen())),"old");
        });
        group("two store instances serialize bounded updates",()->{
            AppContext context=new AppContext();LibraryStore a=new LibraryStore(context),b=new LibraryStore(context);read(a);CountDownLatch start=new CountDownLatch(1);List<Throwable> errors=Collections.synchronizedList(new ArrayList<>());Thread one=new Thread(()->{try{start.await();for(int i=0;i<15;i++)mutate(a,"addSearchHistory","a-"+i);}catch(Throwable error){errors.add(error);}}),two=new Thread(()->{try{start.await();for(int i=0;i<15;i++)mutate(b,"addSearchHistory","b-"+i);}catch(Throwable error){errors.add(error);}});one.start();two.start();start.countDown();one.join(3000);two.join(3000);check(!one.isAlive()&&!two.isAlive()&&errors.isEmpty(),"store writers complete under the shared lock");List<String> list=words(read(a));check(list.size()==20&&new HashSet<>(list).size()==20&&words(read(new LibraryStore(context.reopen()))).equals(list),"concurrent updates stay unique bounded and committed");
        });
        System.out.println("SEARCH_HISTORY_NATIVE_"+(failed==0?"OK":"FAILED")+" groupsPassed="+passed+" groupsFailed="+failed+" checks="+checks+" networkRequests=0 deviceTests=not_run");if(failed!=0)throw new AssertionError("search history native gate failed");
    }
}
