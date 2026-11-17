#!/usr/bin/env node
// Offline Java production transport regression: fake OkHttp calls, no sockets or media.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceDir = path.join(root, 'android/app/src/main/java/io/github/jackotom/hongguo/android');
let sourceFile = path.join(sourceDir, 'RegisteredPlaybackModel.java');
let output;
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--source-file' && process.argv[i + 1]) sourceFile = path.resolve(process.argv[++i]);
  else if (process.argv[i] === '--output' && process.argv[i + 1]) output = path.resolve(process.argv[++i]);
  else throw new Error('Usage: node scripts/check-android-model-cancel-ownership.test.mjs [--source-file JAVA] [--output DIRECTORY]');
}
const temporary = !output;
output ||= fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-model-cancel-'));
fs.mkdirSync(output, { recursive: true });
const bundledJavaHome = '/Applications/Android Studio.app/Contents/jbr/Contents/Home';
const javaHome = process.env.JAVA_HOME || (fs.existsSync(path.join(bundledJavaHome, 'bin/javac')) ? bundledJavaHome : null);
const javaCommand = name => javaHome ? path.join(javaHome, 'bin', name) : name;
const cache = process.env.GRADLE_CACHE || path.join(os.homedir(), '.gradle/caches/modules-2/files-2.1');
function jar(relative, name, fallback) {
  const search = (directory, matches) => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))) {
      const candidate = path.join(directory, entry.name);
      if (entry.isFile() && matches(entry.name)) return candidate;
      if (entry.isDirectory()) { const found = search(candidate, matches); if (found) return found; }
    }
  };
  const directory = path.join(cache, relative);
  const found = search(directory, candidate => candidate === name) || (fallback && search(directory, fallback));
  if (!found) throw new Error(`Missing offline dependency ${name}`);
  return found;
}
const classpath = [
  process.env.JSON_JAR || path.join(os.homedir(), '.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar'),
  process.env.SDK_JAR || path.join(process.env.ANDROID_HOME || path.join(os.homedir(), 'Library/Android/sdk'), 'platforms/android-35/android.jar'),
  jar('com.squareup.okhttp3/okhttp', 'okhttp-4.12.0.jar'),
  jar('com.squareup.okio/okio-jvm', 'okio-jvm-3.6.0.jar'),
  jar('org.jetbrains.kotlin/kotlin-stdlib', 'kotlin-stdlib-2.0.20.jar', name => /^kotlin-stdlib-\d.*\.jar$/.test(name) && !/-(?:sources|javadoc|metadata)\.jar$/.test(name)),
  jar('org.jetbrains/annotations', 'annotations-13.0.jar'),
].join(path.delimiter);
const harness = String.raw`package io.github.jackotom.hongguo.android;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class CancelOwnershipHarness {
  static int passed, failed, checks;
  static Field activeField;
  static Method tracked;
  static final ExecutorService workers = Executors.newFixedThreadPool(4);
  interface Action { void run() throws Exception; }
  static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
  static void group(String name, Action action) {
    try { action.run(); passed++; System.out.println("PASS " + name); }
    catch (Throwable failure) { failed++; System.out.println("FAIL " + name + ": " + failure.getClass().getSimpleName() + " " + failure.getMessage()); }
    finally { Thread.interrupted(); }
  }
  @SuppressWarnings("unchecked") static Set<okhttp3.Call> active(RegisteredPlaybackModel.ModelHttpTransport owner) throws Exception {
    return (Set<okhttp3.Call>) activeField.get(Modifier.isStatic(activeField.getModifiers()) ? null : owner);
  }
  static OfficialApiClient.Request request() {
    return new OfficialApiClient.Request("https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1", "POST", Collections.emptyMap(), new byte[]{1}, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
  }
  static OfficialApiClient.Response run(RegisteredPlaybackModel.ModelHttpTransport owner, FakeCall call) throws Exception {
    try { return (OfficialApiClient.Response) tracked.invoke(owner, call, request()); }
    catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw (Error) cause;
    }
  }
  static Future<Throwable> task(RegisteredPlaybackModel.ModelHttpTransport owner, FakeCall call) {
    return workers.submit(() -> { try { run(owner, call); return null; } catch (Throwable failure) { return failure; } });
  }
  static Throwable result(Future<Throwable> future) throws Exception { return future.get(3, TimeUnit.SECONDS); }
  static void await(CountDownLatch latch) throws Exception { check(latch.await(3, TimeUnit.SECONDS), "fixture synchronization timeout"); }
  static final class FakeCall implements okhttp3.Call {
    final AtomicInteger cancels = new AtomicInteger(), executes = new AtomicInteger(), bodyCloses = new AtomicInteger();
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final boolean blocked, fail, ignoreCancel, failRead;
    volatile boolean canceled;
    FakeCall() { this(false, false, false, false); }
    FakeCall(boolean blocked, boolean fail, boolean ignoreCancel, boolean failRead) { this.blocked=blocked; this.fail=fail; this.ignoreCancel=ignoreCancel; this.failRead=failRead; }
    public okhttp3.Request request() { return new okhttp3.Request.Builder().url("https://offline.invalid/model").build(); }
    public okhttp3.Response execute() throws IOException {
      executes.incrementAndGet(); entered.countDown();
      if (blocked) try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("fixture timeout"); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new InterruptedIOException("fixture interrupted"); }
      if (fail || canceled && !ignoreCancel) throw new IOException("fixture call failed");
      okio.Source bytes = new okio.Source() {
        boolean read;
        public long read(okio.Buffer sink, long count) throws IOException { if (failRead) throw new IOException("fixture read failed"); if (read) return -1; read=true; sink.writeUtf8("{}"); return 2; }
        public okio.Timeout timeout() { return new okio.Timeout(); }
        public void close() { bodyCloses.incrementAndGet(); }
      };
      okhttp3.ResponseBody body = okhttp3.ResponseBody.create(okio.Okio.buffer(bytes), null, -1);
      return new okhttp3.Response.Builder().request(request()).protocol(okhttp3.Protocol.HTTP_2).code(200).message("fixture").body(body).build();
    }
    public void enqueue(okhttp3.Callback callback) { throw new AssertionError("asynchronous network calls forbidden"); }
    public void cancel() { cancels.incrementAndGet(); canceled=true; if (!ignoreCancel) release.countDown(); }
    public boolean isExecuted() { return executes.get() > 0; }
    public boolean isCanceled() { return canceled; }
    public okio.Timeout timeout() { return new okio.Timeout(); }
    public okhttp3.Call clone() { return new FakeCall(blocked, fail, ignoreCancel, failRead); }
  }
  public static void main(String[] args) throws Exception {
    try {
      try { activeField=RegisteredPlaybackModel.ModelHttpTransport.class.getDeclaredField("activeCalls"); }
      catch (NoSuchFieldException baseline) { activeField=RegisteredPlaybackModel.ModelHttpTransport.class.getDeclaredField("ACTIVE_CALLS"); }
      activeField.setAccessible(true);
      try { tracked=RegisteredPlaybackModel.ModelHttpTransport.class.getDeclaredMethod("executeTrackedCall", okhttp3.Call.class, OfficialApiClient.Request.class); tracked.setAccessible(true); }
      catch (NoSuchMethodException baseline) { tracked=null; }
      group("closing prefetch never cancels another transport", () -> {
        RegisteredPlaybackModel.ModelHttpTransport foreground=new RegisteredPlaybackModel.ModelHttpTransport(), prefetch=new RegisteredPlaybackModel.ModelHttpTransport();
        FakeCall a=new FakeCall(), b=new FakeCall();
        Set<okhttp3.Call> aSet=active(foreground), bSet=active(prefetch);
        aSet.add(a); bSet.add(b);
        try {
          prefetch.close();
          System.out.println("ISOLATION foregroundCancelCount="+a.cancels.get()+" prefetchCancelCount="+b.cancels.get()+" activeSetIsStatic="+Modifier.isStatic(activeField.getModifiers())+" networkRequests=0");
          check(a.cancels.get()==0 && b.cancels.get()==1, "unrelated foreground was canceled");
        } finally { aSet.clear(); bSet.clear(); }
      });
      if (tracked != null) {
        group("same instance cancels all its running calls exactly once", () -> {
          RegisteredPlaybackModel.ModelHttpTransport owner=new RegisteredPlaybackModel.ModelHttpTransport();
          FakeCall a=new FakeCall(true,false,false,false), b=new FakeCall(true,false,false,false);
          Future<Throwable> first=task(owner,a), second=task(owner,b); await(a.entered); await(b.entered);
          owner.close(); owner.close();
          check(result(first)!=null && result(second)!=null, "canceled calls cannot publish success");
          check(a.cancels.get()==1 && b.cancels.get()==1 && active(owner).isEmpty(), "all own calls canceled and removed");
        });
        group("concurrent foreground remains live when prefetch closes", () -> {
          RegisteredPlaybackModel.ModelHttpTransport foreground=new RegisteredPlaybackModel.ModelHttpTransport(), prefetch=new RegisteredPlaybackModel.ModelHttpTransport();
          FakeCall a=new FakeCall(true,false,false,false), b=new FakeCall(true,false,false,false);
          Future<Throwable> first=task(foreground,a), second=task(prefetch,b); await(a.entered); await(b.entered);
          prefetch.close(); check(result(second)!=null, "own prefetch stops");
          check(a.cancels.get()==0 && !first.isDone(), "unrelated foreground still running");
          a.release.countDown(); check(result(first)==null && a.bodyCloses.get()==1 && active(foreground).isEmpty(), "foreground completes and cleans its response");
        });
        group("close before registration cancels an unexecuted call", () -> {
          RegisteredPlaybackModel.ModelHttpTransport owner=new RegisteredPlaybackModel.ModelHttpTransport(); owner.close(); FakeCall call=new FakeCall();
          check(result(task(owner,call)) instanceof InterruptedIOException, "closed registration is typed cancellation");
          check(call.executes.get()==0 && call.cancels.get()==1 && active(owner).isEmpty(), "closed transport never executes call");
        });
        group("close winning the registration monitor cannot strand a later call", () -> {
          RegisteredPlaybackModel.ModelHttpTransport owner=new RegisteredPlaybackModel.ModelHttpTransport(); FakeCall call=new FakeCall(); CountDownLatch started=new CountDownLatch(1); Future<Throwable> pending;
          synchronized(active(owner)) {
            pending=workers.submit(() -> { started.countDown(); try { run(owner,call); return null; } catch(Throwable failure) { return failure; } });
            await(started); owner.close();
          }
          check(result(pending) instanceof InterruptedIOException && call.executes.get()==0 && call.cancels.get()==1 && active(owner).isEmpty(), "queued registration observes closure");
        });
        group("late success after close stays canceled and releases response", () -> {
          RegisteredPlaybackModel.ModelHttpTransport owner=new RegisteredPlaybackModel.ModelHttpTransport(); FakeCall call=new FakeCall(true,false,true,false);
          Future<Throwable> pending=task(owner,call); await(call.entered); owner.close(); call.release.countDown();
          check(result(pending) instanceof InterruptedIOException, "late success must not publish");
          check(call.cancels.get()==1 && call.bodyCloses.get()==1 && active(owner).isEmpty(), "late response released and call removed");
        });
        group("successful failed and read-failed calls leave no owned entries", () -> {
          for(FakeCall call:new FakeCall[]{new FakeCall(),new FakeCall(false,true,false,false),new FakeCall(false,false,false,true)}) {
            RegisteredPlaybackModel.ModelHttpTransport owner=new RegisteredPlaybackModel.ModelHttpTransport(); Throwable failure=result(task(owner,call));
            check((failure!=null)==(call.fail||call.failRead), "actual call outcome preserved");
            check(active(owner).isEmpty(), "completed call removed in finally"); owner.close();
            check(call.cancels.get()==0, "closing later must not recancel completed call");
          }
        });
        group("closed public execute stops before engine or DNS", () -> {
          AtomicInteger engines=new AtomicInteger(); RegisteredPlaybackModel.ModelHttpTransport owner=new RegisteredPlaybackModel.ModelHttpTransport((uri,request,timeout,pin) -> { engines.incrementAndGet(); throw new AssertionError("network forbidden"); }); owner.close();
          Throwable failure=null; try { owner.execute(request()); } catch(Throwable expected) { failure=expected; }
          check(failure instanceof InterruptedIOException && engines.get()==0, "closed execute terminates at first check");
        });
        group("close and registration races never leave a running unowned call", () -> {
          for(int iteration=0;iteration<100;iteration++) {
            RegisteredPlaybackModel.ModelHttpTransport owner=new RegisteredPlaybackModel.ModelHttpTransport(); FakeCall call=new FakeCall(true,false,false,false); CountDownLatch start=new CountDownLatch(1);
            Future<Throwable> pending=workers.submit(() -> { start.await(); try { run(owner,call); return null; } catch(Throwable failure) { return failure; } });
            Future<?> closing=workers.submit(() -> { try { start.await(); owner.close(); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); } });
            start.countDown(); closing.get(3,TimeUnit.SECONDS);
            check(result(pending)!=null && call.cancels.get()==1 && active(owner).isEmpty(), "race iteration "+iteration+" canceled and cleaned");
          }
        });
        group("shared OkHttp client and pool remain shared", () -> {
          okhttp3.OkHttpClient first=RegisteredPlaybackModel.ModelHttpTransport.sharedClient(), second=RegisteredPlaybackModel.modelSharedClient();
          check(first==second && first.connectionPool()==second.connectionPool(), "client and h2 pool preserved");
        });
      } else System.out.println("SKIP atomic registration cases: baseline has no tracked-call gate");
    } finally { workers.shutdownNow(); }
    System.out.println("ANDROID_MODEL_CANCEL_OWNERSHIP_"+(failed==0?"OK":"FAILED")+" groupsPassed="+passed+" groupsFailed="+failed+" checks="+checks+" networkRequests=0 videoFixtures=0");
    if(failed!=0)System.exit(1);
  }
}
`;
try {
  const harnessFile = path.join(output, 'CancelOwnershipHarness.java');
  fs.writeFileSync(harnessFile, harness);
  const classes = path.join(output, 'classes');
  fs.mkdirSync(classes, { recursive: true });
  const sources = ['DanmakuSigner', 'SearchProtocol', 'PassportConstants', 'PassportSigner', 'PassportDevice', 'PassportJson', 'OfficialApiClient', 'OfficialTransport', 'AppTransport', 'NetworkGuard', 'PlayerSigner', 'PlaybackSignerV5', 'AccountSigner', 'UpstreamDeviceRegister'].map(name => path.join(sourceDir, `${name}.java`));
  const compile = spawnSync(javaCommand('javac'), ['--release', '17', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes, ...sources, sourceFile, harnessFile], { encoding: 'utf8' });
  fs.writeFileSync(path.join(output, 'compile.txt'), `${compile.stdout || ''}${compile.stderr || ''}EXIT_STATUS=${compile.status}\n`);
  if (compile.status !== 0) { process.stderr.write(compile.stderr || String(compile.error)); process.exitCode = compile.status || 1; }
  else {
    const test = spawnSync(javaCommand('java'), ['-cp', `${classes}${path.delimiter}${classpath}`, 'io.github.jackotom.hongguo.android.CancelOwnershipHarness'], { encoding: 'utf8', timeout: 30000 });
    process.stdout.write(test.stdout || ''); process.stderr.write(test.stderr || '');
    fs.writeFileSync(path.join(output, 'result.txt'), `${test.stdout || ''}${test.stderr || ''}EXIT_STATUS=${test.status}\n`);
    process.exitCode = test.status ?? 1;
  }
} finally { if (temporary) fs.rmSync(output, { recursive: true, force: true }); }
