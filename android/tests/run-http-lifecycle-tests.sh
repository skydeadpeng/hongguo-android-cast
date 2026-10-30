#!/bin/bash
set -euo pipefail
OWN="$(cd "$(dirname "$0")/../.." && pwd)"
ROOT="$OWN"
if [[ "${1:-}" == --source-root ]];then ROOT="$2";shift 2;fi
[[ $# == 0 ]] || { echo 'Usage --source-root PROJECT' >&2;exit 2; }
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-http-lifecycle.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$TMP/classes" "$SRC/NetworkGuard.java" "$OWN/android/tests/HttpPageBudgetTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.HttpPageBudgetTest
python3 - "$SRC/NativeBridge.java" "$TMP/HttpDispatchTest.java" <<'PY'
from pathlib import Path
import sys,re
s=Path(sys.argv[1]).read_text()
start=s.find('    private static long httpInteger(');end=s.find('    private void respondHttpError(',start)
if start<0 or end<start:raise SystemExit('HTTP_DISPATCH_FAILED new production methods absent')
fields=re.findall(r'private final ThreadPoolExecutor\s+\w+\s*=\s*[^;]+;',s)
mapfield=re.search(r'private final ConcurrentHashMap<String, FutureTask<Void>> httpRequests[^;]+;',s)
if not mapfield:raise SystemExit('HTTP_DISPATCH_FAILED production page registry absent')
j='''import java.util.*;import java.util.concurrent.*;import org.json.*;
public final class HttpDispatchTest {
 private volatile boolean closed;
 FIELDS
 PRODUCTION
 final List<String> replies=Collections.synchronizedList(new ArrayList<>());
 private void respond(Object id,Object result,String error){if(!closed)replies.add(id+":"+result+":"+error);}
 private void respondHttpError(Object id,String error,String code){if(!closed)replies.add(id+":"+code);}
 static int checks;static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
 static JSONObject params(long budget)throws Exception{return new JSONObject().put("url","https://hongguoduanju.com/player/123").put("timeoutMs",budget).put("deadlineMs",System.currentTimeMillis()+budget);}
 static void waitFor(java.util.function.BooleanSupplier condition,String label)throws Exception{long stop=System.nanoTime()+2_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<stop)Thread.sleep(5);check(condition.getAsBoolean(),label);}
 public static void main(String[]args)throws Exception{
  HttpDispatchTest b=new HttpDispatchTest();
  try{
   check(b.httpWorker!=b.worker&&b.httpWorker!=b.searchWorker&&b.httpWorker.getMaximumPoolSize()==2,"independent two-worker HTTP lane");
   check(b.httpWorker.getQueue().remainingCapacity()==8,"bounded page queue");
   for(Object bad:new Object[]{0,-1,2.5,"20",JSONObject.NULL,Double.MAX_VALUE}){JSONObject p=params(500);p.put("timeoutMs",bad);int before=NetworkGuard.called.get();b.startHttp("invalid"+bad,p);check(NetworkGuard.called.get()==before,"bad budget never reaches network");}
   for(Object bad:new Object[]{0,-1,2.5,"20",JSONObject.NULL,Double.MAX_VALUE}){JSONObject p=params(500);p.put("deadlineMs",bad);int before=NetworkGuard.called.get();b.startHttp("invalid-wall"+bad,p);check(NetworkGuard.called.get()==before,"bad absolute deadline never reaches network");}
   JSONObject expired=params(500);expired.put("deadlineMs",System.currentTimeMillis()-1);b.startHttp("expired",expired);check(b.replies.stream().anyMatch(x->x.equals("expired:ETIMEDOUT")),"expired before enqueue is typed timeout");
   check(!b.cancelHttp("app-request-id"),"non-HTTP and unknown IDs are not cancellable");
   NetworkGuard.block=true;NetworkGuard.entered=new CountDownLatch(2);
   b.startHttp("one",params(2000));b.startHttp("two",params(2000));check(NetworkGuard.entered.await(1,TimeUnit.SECONDS),"both HTTP workers entered");
   b.startHttp("one",params(2000));check(b.replies.stream().anyMatch(x->x.equals("one:INVALID_INPUT")),"duplicate RPC cannot replace active page task");
   for(int i=0;i<8;i++)b.startHttp("capacity-"+i,params(2000));b.startHttp("overload",params(2000));check(!b.httpRequests.containsKey("overload")&&b.httpWorker.getQueue().size()==8,"overload rejects and removes its registry entry");for(int i=0;i<8;i++)check(b.cancelHttp("capacity-"+i),"cancel queued capacity fixture");
   Future<String> media=b.worker.submit(()->"media-ok");check("media-ok".equals(media.get(1,TimeUnit.SECONDS)),"HTTP work cannot block media lane");
   b.startHttp("queued-cancel",params(2000));check(b.cancelHttp("queued-cancel"),"queued HTTP is cancellable before execution");check(b.httpWorker.getQueue().isEmpty(),"queued cancellation removes the Future from executor queue");
   b.startHttp("queued",params(35));check(b.httpRequests.containsKey("queued"),"queued request tracked");
   Thread.sleep(50);NetworkGuard.block=false;NetworkGuard.release.countDown();waitFor(()->b.httpRequests.isEmpty(),"completion removes every registry entry");
   check(b.replies.stream().anyMatch(x->x.equals("queued:ETIMEDOUT")),"expired queue task reports timeout");check(NetworkGuard.called.get()==2,"expired and cancelled queued tasks did not start network work");
   NetworkGuard.block=true;NetworkGuard.release=new CountDownLatch(1);NetworkGuard.entered=new CountDownLatch(1);
   b.startHttp("cancel-running",params(2000));check(NetworkGuard.entered.await(1,TimeUnit.SECONDS),"running cancel fixture entered");
   check(b.cancelHttp("cancel-running"),"running HTTP cancel accepted");waitFor(()->NetworkGuard.interrupted.get()>0,"running Future interrupted");check(!b.httpRequests.containsKey("cancel-running"),"cancel removes registry immediately");
   check(!b.cancelHttp("cancel-running"),"completed cancel cannot affect another request");
   NetworkGuard.entered=new CountDownLatch(2);b.startHttp("close-one",params(2000));b.startHttp("close-two",params(2000));check(NetworkGuard.entered.await(1,TimeUnit.SECONDS),"close fixture entered");b.startHttp("close-queued",params(2000));
   b.closed=true;b.closeHttp();check(b.httpRequests.isEmpty()&&b.httpWorker.isShutdown(),"close cancels active and queued page tasks");
   waitFor(()->b.httpWorker.isTerminated(),"page workers terminate after close");int replies=b.replies.size();b.startHttp("after-close",params(2000));Thread.sleep(20);check(b.replies.size()==replies&&b.httpRequests.isEmpty(),"close blocks late requests and responses");
   System.out.println("HTTP_DISPATCH_OK checks="+checks+" actualMethods=true mediaLane=responsive registry=empty cancel=interrupt networkRequests=0");
  }finally{NetworkGuard.release.countDown();b.closed=true;b.closeHttp();b.worker.shutdownNow();b.searchWorker.shutdownNow();b.downloadWorker.shutdownNow();}
 }
}
final class LibraryStore {static String text(JSONObject p,String key,int max)throws Exception{Object x=p.get(key);if(!(x instanceof String)||((String)x).length()>max)throw new Exception("bad URL");return(String)x;}}
final class NetworkGuard {
 static volatile boolean block;static final java.util.concurrent.atomic.AtomicInteger called=new java.util.concurrent.atomic.AtomicInteger(),interrupted=new java.util.concurrent.atomic.AtomicInteger();static CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(1);static List<String> completed=Collections.synchronizedList(new ArrayList<>());
 static final class Reply {final String body="fixture",url="https://hongguoduanju.com/player/123";final int status=200;}
 static long pageDeadline(long t,long wall)throws Exception{if(t<1||t>25000)throw new Exception("bad budget");long ms=Math.min(t,wall>0?wall-System.currentTimeMillis():t);if(ms<=0)throw new java.net.SocketTimeoutException();return System.nanoTime()+ms*1_000_000L;}
 static Reply get(String url,long deadline)throws Exception{if(System.nanoTime()>=deadline)throw new java.net.SocketTimeoutException();called.incrementAndGet();entered.countDown();if(block)try{release.await();}catch(InterruptedException e){interrupted.incrementAndGet();Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}return new Reply();}
}
'''.replace('FIELDS','\n'.join(fields)+'\n'+mapfield.group()).replace('PRODUCTION',s[start:end])
Path(sys.argv[2]).write_text(j)
PY
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/dispatch" "$TMP/HttpDispatchTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/dispatch:$JSON_JAR" HttpDispatchTest
