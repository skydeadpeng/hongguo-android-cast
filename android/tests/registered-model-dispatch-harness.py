from pathlib import Path
import re,sys
s=Path(sys.argv[1]).read_text();start=s.index('    private void startRegisteredModel(');end=s.index('    private static long httpInteger(',start)
fields=[]
for name in ['modelWorker','modelCalls','modelTasks']:
 fields.append(re.search(r'private final [^;\n]+\b'+name+r'\b[^;\n]+;',s).group())
code=r'''
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import org.json.*;
public final class RegisteredModelDispatchTest {
 private volatile boolean closed;FIELDS
 final FakeActivity activity=new FakeActivity();
 static int checks;static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
 void respondHttpError(Object id,String error,String code){if(!closed)activity.scripts.add("ERROR "+id+":"+code);}
 PRODUCTION
 static void waitFor(java.util.function.BooleanSupplier condition,String label)throws Exception{long stop=System.nanoTime()+2_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<stop)Thread.sleep(2);check(condition.getAsBoolean(),label);}
 static JSONObject params()throws Exception{return new JSONObject().put("vid","7694655440692448281");}
 public static void main(String[]args)throws Exception{
  RegisteredModelDispatchTest b=new RegisteredModelDispatchTest();
  try{
   check(b.modelWorker.getMaximumPoolSize()==2&&b.modelWorker.getQueue() instanceof SynchronousQueue,"independent two-worker no-queue model lane");
   b.startRegisteredModel("bad",new JSONObject().put("vid",23));check(b.modelCalls.isEmpty()&&RegisteredPlaybackModel.instances.size()==0&&b.activity.scripts.get(0).contains("INVALID_INPUT"),"malformed input never creates anonymous device");b.activity.scripts.clear();
   b.startRegisteredModel("success",params());waitFor(()->b.modelTasks.isEmpty(),"success worker finished");check(b.activity.scripts.isEmpty()&&b.modelCalls.size()==1,"reply waits for UI lifetime check");b.activity.drain();check(b.activity.scripts.size()==1&&b.activity.scripts.get(0).contains("video_model")&&b.modelCalls.isEmpty(),"valid live model reaches only its request then retires");b.activity.scripts.clear();
   RegisteredPlaybackModel.block=true;b.startRegisteredModel("one",params());b.startRegisteredModel("two",params());waitFor(()->RegisteredPlaybackModel.entered.get()==2,"both model workers entered");b.startRegisteredModel("overflow",params());check(b.activity.scripts.size()==1&&b.activity.scripts.get(0).contains("overflow:ETIMEDOUT")&&b.modelCalls.size()==2,"overload promptly rejects and never queues extra model");b.activity.scripts.clear();
   check(b.cancelRegisteredModel("one"),"running request cancels directly without model lane");check(!b.cancelRegisteredModel("missing"),"unknown ID cannot cancel another request");RegisteredPlaybackModel second=b.modelCalls.get("two");second.release.countDown();waitFor(()->b.modelTasks.isEmpty(),"canceled and unaffected workers finish");b.activity.drain();check(b.activity.scripts.size()==1&&b.activity.scripts.get(0).contains("two")&&!b.activity.scripts.get(0).contains("one"),"late canceled success drops while other request succeeds");check(b.modelCalls.isEmpty(),"every terminal model registry entry retired");b.activity.scripts.clear();
   RegisteredPlaybackModel.block=false;b.startRegisteredModel("reuse",params());waitFor(()->b.modelTasks.isEmpty(),"old same-ID reply queued");check(b.cancelRegisteredModel("reuse"),"cancellation after background finish accepted before UI reply");b.startRegisteredModel("reuse",params());waitFor(()->b.modelTasks.isEmpty(),"new same-ID worker finished");b.activity.drain();check(b.activity.scripts.size()==1&&b.modelCalls.isEmpty(),"same-ID generation check prevents stale UI callback and preserves new one");b.activity.scripts.clear();
   RegisteredPlaybackModel.fail=true;b.startRegisteredModel("failure",params());waitFor(()->b.modelTasks.isEmpty(),"business error worker finished");b.activity.drain();check(b.activity.scripts.size()==1&&b.activity.scripts.get(0).contains("APP_EMPTY_RESPONSE")&&!b.activity.scripts.get(0).contains("video_model"),"typed empty model never becomes success");b.activity.scripts.clear();RegisteredPlaybackModel.fail=false;
   RegisteredPlaybackModel.block=true;b.startRegisteredModel("close-one",params());b.startRegisteredModel("close-two",params());waitFor(()->b.modelCalls.size()==2,"close registered jobs");b.closed=true;b.closeRegisteredModels();waitFor(()->b.modelWorker.isTerminated(),"closed lane terminates");b.activity.drain();check(b.modelCalls.isEmpty()&&b.modelTasks.isEmpty()&&b.activity.scripts.isEmpty(),"close removes jobs and prevents all queued late UI results");b.startRegisteredModel("after-close",params());check(b.modelCalls.isEmpty()&&b.activity.scripts.isEmpty(),"closed bridge never accepts another job");
   System.out.println("REGISTERED_MODEL_DISPATCH_OK checks="+checks+" actualNativeMethods=true noQueue=true cancelControlResponsive=true lateGenerationRepliesRejected=true networkRequests=0");
  }finally{b.closed=true;b.closeRegisteredModels();}
 }
}
final class FakeActivity {
 final Queue<Runnable> ui=new ConcurrentLinkedQueue<>();final List<String> scripts=Collections.synchronizedList(new ArrayList<>());
 void runOnUiThread(Runnable callback){ui.add(callback);}void script(String source){scripts.add(source);}void drain(){Runnable action;while((action=ui.poll())!=null)action.run();}
}
final class RegisteredPlaybackModel implements AutoCloseable {
 static volatile boolean block,fail;static final AtomicInteger entered=new AtomicInteger();static final List<RegisteredPlaybackModel>instances=Collections.synchronizedList(new ArrayList<>());
 final CountDownLatch release=new CountDownLatch(1);volatile boolean closed;
 RegisteredPlaybackModel(){instances.add(this);}
 static final class ModelError extends java.io.IOException{final String code;ModelError(){super("fixture empty model");code="APP_EMPTY_RESPONSE";}}
 static String requestedVid(JSONObject value)throws Exception{if(value.length()!=1||!(value.opt("vid") instanceof String))throw new Exception("invalid");return value.getString("vid");}
 JSONObject fetch(String vid)throws Exception{if(block){entered.incrementAndGet();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}}if(fail)throw new ModelError();return new JSONObject().put("vid",vid).put("video_model",new JSONObject().put("fixture",true));}
 boolean current(){return !closed;}public void close(){closed=true;release.countDown();}
}
'''.replace('FIELDS','\n'.join(fields)).replace('PRODUCTION',s[start:end])
Path(sys.argv[2]).write_text(code)
