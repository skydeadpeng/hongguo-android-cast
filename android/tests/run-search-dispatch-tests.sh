#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
if [[ "${1:-}" = '--source-root' ]];then ROOT="$2";shift 2;fi
if [[ $# -ne 0 ]];then echo 'USAGE run-search-dispatch-tests.sh [--source-root PROJECT]' >&2;exit 2;fi
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d -t hongguo-search-dispatch.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
python3 - "$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/NativeBridge.java" "$TMP/SearchDispatchTest.java" <<'PY'
from pathlib import Path
import re,sys
source=Path(sys.argv[1]).read_text()
# Copy executable production declarations, routing expression and lifecycle body verbatim.
# The shell supplies only task/latch fixtures, never a replacement pool or route.
fields=re.findall(r'private final ThreadPoolExecutor\s+\w+\s*=\s*[^;]+;',source)
route=re.search(r'ThreadPoolExecutor executor\s*=\s*[^;]+;',source)
# Lifecycle now includes cancellation try/catch. Balance braces instead of truncating
# a real production body at its first nested block.
def method(pattern):
    found=re.search(pattern,source)
    if not found:return None
    start=source.find('{',found.start());depth=0;quote=None;escape=False
    for pos in range(start,len(source)):
        char=source[pos]
        if quote:
            if escape:escape=False
            elif char=='\\':escape=True
            elif char==quote:quote=None
            continue
        if char in ['"',"'"]:quote=char;continue
        if char=='{':depth+=1
        elif char=='}':
            depth-=1
            if depth==0:return source[found.start():pos+1]
    return None
close=method(r'void close\(\)\s*\{')
http_registry=re.search(r'private final ConcurrentHashMap<String, FutureTask<Void>> httpRequests[^;]+;',source)
http_start=source.find('    private void closeHttp()')
http_end=source.find('    private void respondHttpError(',http_start)
http_close=source[http_start:http_end] if http_start>=0 and http_end>http_start else ''
if not fields or not route or not close:
    print('SEARCH_DISPATCH_TESTS_FAILED production executor/route/lifecycle extraction did not match',file=sys.stderr)
    sys.exit(1)
java='''import java.util.concurrent.*;
public final class SearchDispatchTest {
    private volatile boolean closed;
    // Account cancellation is a collaborator, not the search executor under test.
    static final class AccountSync { static final AccountSync instance=new AccountSync(); static AccountSync get(){return instance;} void cancelLogin(){} }
    PRODUCTION_FIELDS
    HTTP_REGISTRY
    HTTP_CLOSE
    private ThreadPoolExecutor executor(String method) { PRODUCTION_ROUTE return executor; }
    PRODUCTION_CLOSE
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception {
        SearchDispatchTest bridge=new SearchDispatchTest();
        CountDownLatch entered=new CountDownLatch(2),blocked=new CountDownLatch(1),interrupted=new CountDownLatch(2);
        try {
            ThreadPoolExecutor search=bridge.executor("searchQuery"),ordinary=bridge.executor("appRequest"),download=bridge.executor("download");
            check(search!=ordinary&&search!=download,"search pool must be isolated from media and downloads");
            check(search.getCorePoolSize()==2&&search.getMaximumPoolSize()==2,"two bounded search workers");
            check(search.getQueue() instanceof SynchronousQueue,"no stale search backlog");
            check(search.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy,"search overload rejects instead of caller-thread blocking");
            Runnable wait=()->{entered.countDown();try{blocked.await();}catch(InterruptedException expected){interrupted.countDown();Thread.currentThread().interrupt();}};
            search.execute(wait);search.execute(wait);
            check(entered.await(2,TimeUnit.SECONDS),"two simultaneous search requests entered");
            boolean rejected=false;try{bridge.executor("searchQuery").execute(()->{throw new AssertionError("third search should not execute");});}catch(RejectedExecutionException expected){rejected=true;}
            check(rejected,"third blocked search rejected immediately");
            Future<String> media=bridge.executor("appRequest").submit(()->"media-ready");
            check("media-ready".equals(media.get(2,TimeUnit.SECONDS)),"normal appRequest completes while both searches are blocked");
            check(search.getActiveCount()==2,"normal request did not release search fixtures");
            check(bridge.httpWorker!=ordinary&&bridge.httpWorker!=search&&bridge.httpWorker.getMaximumPoolSize()==2,"HTTP page workers are independently bounded");
            check(bridge.httpWorker.getQueue().remainingCapacity()==8,"HTTP page backlog remains bounded");
            check("download-ready".equals(bridge.executor("download").submit(()->"download-ready").get(2,TimeUnit.SECONDS)),"download pool unaffected");
            bridge.close();
            check(bridge.closed,"production close marks lifetime closed");
            check(interrupted.await(2,TimeUnit.SECONDS),"close interrupts both active searches");
            check(search.isShutdown()&&ordinary.isShutdown()&&download.isShutdown(),"production close shuts every pool");
            for(String method:new String[]{"searchQuery","appRequest","download"}){
                boolean lateRejected=false;try{bridge.executor(method).execute(()->{});}catch(RejectedExecutionException expected){lateRejected=true;}
                check(lateRejected,"late task rejected after close: "+method);
            }
            check(search.awaitTermination(2,TimeUnit.SECONDS),"search threads terminate after close");
            System.out.println("SEARCH_DISPATCH_TESTS_OK checks="+checks+" productionFields=true productionRoute=true productionClose=true blockedSearches=2 thirdSearch=rejected media=responsive lateTasks=rejected networkRequests=0");
        } finally {blocked.countDown();bridge.close();}
    }
}
'''.replace('PRODUCTION_FIELDS','\n'.join(fields)).replace('PRODUCTION_ROUTE',route.group()).replace('PRODUCTION_CLOSE',close).replace('HTTP_REGISTRY',http_registry.group() if http_registry else '').replace('HTTP_CLOSE',http_close)
Path(sys.argv[2]).write_text(java)
PY
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$TMP/classes" "$TMP/SearchDispatchTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" SearchDispatchTest
