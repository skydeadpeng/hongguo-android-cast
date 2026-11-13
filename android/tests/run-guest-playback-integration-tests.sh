#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
if [[ "${1:-}" == --source-root && $# == 2 ]]; then SOURCE_ROOT="$(cd "$2" && pwd)";
elif [[ $# != 0 ]]; then echo 'USAGE run-guest-playback-integration-tests.sh [--source-root PROJECT]' >&2; exit 2; fi
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
[[ -x "$JAVA_HOME/bin/javac" && -x "$JAVA_HOME/bin/java" && -r "$JSON_JAR" ]] || { echo 'Set JAVA_HOME and JSON_JAR to an available JDK and android-json.jar.' >&2; exit 2; }
TMP="$(mktemp -d -t hongguo-guest-integration.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/android/content"
cat > "$TMP/android/content/Context.java" <<'JAVA'
package android.content;
import java.io.File;
public class Context {private final File cache;public Context(File cache){this.cache=cache;}public Context getApplicationContext(){return this;}public File getCacheDir(){return cache;}}
JAVA
cat > "$TMP/GuestBoundaryStubs.java" <<'JAVA'
package io.github.jackotom.hongguo.android;
import java.io.*;import java.net.*;import java.nio.file.*;import java.util.*;import org.json.*;
final class Fixture {static int appCalls;static final byte[] BYTES={1,2,3,4,5,6,7,8};interface Checked {void run()throws Exception;}}
final class MainActivity extends android.content.Context {
    interface Callback {void call(String script)throws Exception;}
    Callback callback;MainActivity(){super(null);}boolean isFinishing(){return false;}boolean isDestroyed(){return false;}
    void script(String script)throws Exception{if(callback==null)throw new AssertionError("unexpected bridge request");callback.call(script);}
}
final class EpisodeResolver {
    static final class GuestFailure extends IOException {GuestFailure(String message){super(message);}}
    static int calls;static JSONObject info;static Exception failure;
    static JSONObject resolveInfo(String sid,String vid)throws Exception {
        calls++;if(failure!=null)throw failure;if(info==null)throw new AssertionError("unconfigured resolver boundary");return new JSONObject(info.toString());
    }
}
final class NetworkGuard {
    static final String UA="fixture-agent",REFERER="https://hongguoduanju.com/";
    static final class MediaResponseException extends IOException {}
    static final class MediaRedirectException extends IOException {}
    static final class Transfer {
        final String url,ua,ref;final File file;final long max;
        Transfer(String u,File f,long m,String a,String r){url=u;file=f;max=m;ua=a;ref=r;}
    }
    static int heads;static String outcome="success";static final List<Transfer> transfers=new ArrayList<>();
    static URI validate(String value,boolean official)throws Exception {
        URI uri=new URI(value);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getPort()!=-1&&uri.getPort()!=443)throw new IOException("fixture invalid HTTPS URL");return uri;
    }
    static String media(String url)throws Exception{heads++;return url;}
    static void download(String url,File file,long max)throws Exception{download(url,file,max,UA,REFERER);}
    interface TransferProgress{void onProgress(long bytes,long totalBytes);}
    static void download(String url,File file,long max,TransferProgress progress)throws Exception{download(url,file,max,UA,REFERER);}
    static void download(String url,File file,long max,String ua,String ref,long deadline,TransferProgress progress)throws Exception{download(url,file,max,ua,ref);}
    static void download(String url,File file,long max,String ua,String ref)throws Exception {
        validate(url,false);if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("fixture cancelled before transfer");
        transfers.add(new Transfer(url,file,max,ua,ref));Files.write(file.toPath(),Fixture.BYTES);
        if(outcome.equals("http403"))throw new IOException("片源响应 HTTP 403");
        if(outcome.equals("http404"))throw new IOException("片源响应 HTTP 404");
        if(outcome.equals("http500"))throw new IOException("片源响应 HTTP 500");
        if(outcome.equals("dns"))throw new UnknownHostException("fixture no DNS");
        if(outcome.equals("timeout"))throw new SocketTimeoutException("fixture timeout");
        if(outcome.equals("tls"))throw new javax.net.ssl.SSLHandshakeException("fixture bad certificate");
        if(outcome.equals("generic"))throw new IOException("fixture general failure");
        if(outcome.equals("cancel"))throw new InterruptedIOException("fixture cancelled mid-transfer");
    }
}
final class PublicPlaybackVerifier {
    static final long MAX_FILE_BYTES=96L*1024*1024;
    static int calls,width,height;static double duration;static File file;static Exception failure;static boolean interrupt;static Fixture.Checked inspection;
    static void verify(File value,double seconds,int w,int h)throws Exception {
        calls++;file=value;duration=seconds;width=w;height=h;
        if(inspection!=null)inspection.run();if(failure!=null)throw failure;if(interrupt)Thread.currentThread().interrupt();
    }
}
final class CencDecryptor {static int calls;static void decryptInPlace(File file,byte[] key){calls++;throw new AssertionError("guest must never decrypt");}}
final class AppTransport {static boolean domain(String host,String...allowed){if(host==null)return false;for(String suffix:allowed)if(host.equals(suffix)||host.endsWith("."+suffix))return true;return false;}}
final class AppMediaSource {static final class AppSourceException extends IOException {final String code;AppSourceException(String message,String code){super(message);this.code=code;}}}
final class HuangguoApi {
    static void attach(android.content.Context context){throw new AssertionError("unexpected Huangguo route");}
    static int decodeEpisodeIndex(String vid){throw new AssertionError("unexpected Huangguo route");}
    static String episodeUrl(String sid,int index){throw new AssertionError("unexpected Huangguo route");}
}
final class PlaybackPlaylist {static JSONObject owner(JSONObject fallback,JSONObject episode){return fallback;}}
JAVA
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
# A minimal host JDK module image may lack ct.sym. In that case compile for the
# available host runtime; this is an offline integration test, not Android ABI QA.
RELEASE=()
if [[ -r "$JAVA_HOME/lib/ct.sym" ]]; then RELEASE=(--release 17); fi
"$JAVA_HOME/bin/javac" "${RELEASE[@]}" -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
 "$TMP/android/content/Context.java" "$TMP/GuestBoundaryStubs.java" \
 "$SRC/PlaybackSources.java" "$SRC/MediaQuality.java" "$SRC/PlaybackCache.java" "$SRC/PlaybackCachePolicy.java" \
 "$TEST_ROOT/GuestPlaybackIntegrationTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.GuestPlaybackIntegrationTest "$SRC/PlayerActivity.java"
