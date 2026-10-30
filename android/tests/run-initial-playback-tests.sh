#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
INITIAL=true
RECOVERY=true
while [ "$#" -gt 0 ]; do
 case "$1" in
  --source-root) SOURCE_ROOT="$2"; shift 2;;
  --initial-only) RECOVERY=false; shift;;
  --1080-only) INITIAL=false; shift;;
  *) echo 'USAGE run-initial-playback-tests.sh [--source-root PROJECT] [--initial-only|--1080-only]' >&2; exit 2;;
 esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-initial-playback.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
cat > "$TMP/InitialPlaybackStubs.java" <<'STUB'
package io.github.jackotom.hongguo.android;
import java.io.*;import java.net.*;import java.nio.file.Files;import java.util.*;import java.util.function.Consumer;
final class MainActivity {Consumer<String> callback;boolean isFinishing(){return false;}boolean isDestroyed(){return false;}void script(String value){callback.accept(value);}}
final class EpisodeResolver {static int calls;static String result;static String resolve(String sid,String vid)throws Exception{calls++;if(result!=null)return result;throw new IOException("synthetic web miss");}}
final class AppTransport {static int heads;static boolean domain(String host,String...allowed){if(host==null)return false;host=host.toLowerCase(Locale.ROOT);for(String suffix:allowed)if(host.equals(suffix)||host.endsWith("."+suffix))return true;return false;}static String media(String url,String ua,String ref){heads++;return url;}}
final class NetworkGuard {
 static final class MediaRedirectException extends IOException {MediaRedirectException(){super("片源跳转不可用（地址无效或跳转次数超限）");}}
 static final class MediaResponseException extends IOException {MediaResponseException(String message){super(message);}}
 static final String UA="fixture-agent",REFERER="https://hongguoduanju.com/";
 static Exception probeError;static int downloads,heads;static String lastUrl;static String redirectedUrl;
 static final List<String> attempted=new ArrayList<>();static final List<Boolean> cleanAtStart=new ArrayList<>();
 static final Map<String,String> outcomes=new HashMap<>(),agents=new HashMap<>(),referers=new HashMap<>();
 static void reset(){downloads=0;heads=0;lastUrl=null;probeError=null;redirectedUrl=null;attempted.clear();cleanAtStart.clear();outcomes.clear();agents.clear();referers.clear();AppTransport.heads=0;EpisodeResolver.calls=0;EpisodeResolver.result=null;CencDecryptor.reset();}
 static URI validate(String value,boolean official)throws Exception{URI uri=new URI(value);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getPort()!=-1&&uri.getPort()!=443)throw new IOException("仅允许有效HTTPS服务地址");return uri;}
 static String media(String url)throws Exception{heads++;if(probeError!=null)throw probeError;return redirectedUrl==null?url:redirectedUrl;}
 static void download(String url,File target,long max)throws Exception{download(url,target,max,UA,REFERER);}
 static void download(String url,File target,long max,String ua,String ref)throws Exception{
  downloads++;lastUrl=url;attempted.add(url);agents.put(url,ua);referers.put(url,ref);cleanAtStart.add(!target.exists()||target.length()==0);
  Files.write(target.toPath(),new byte[]{1,2,3,4});String outcome=outcomes.getOrDefault(url,"success");
  switch(outcome){
   case "dns":throw new UnknownHostException("synthetic CDN DNS unavailable");
   case "wrapped-dns":throw new IOException("synthetic media lookup failed",new UnknownHostException("synthetic typed DNS cause"));
   case "nested-dns":throw new IOException("synthetic media outer",new IOException("synthetic inner",new UnknownHostException("synthetic nested typed DNS cause")));
   case "tls":throw new javax.net.ssl.SSLHandshakeException("synthetic invalid certificate");
   case "tls-dns":{javax.net.ssl.SSLHandshakeException failure=new javax.net.ssl.SSLHandshakeException("synthetic TLS owner");failure.initCause(new UnknownHostException("synthetic DNS child"));throw failure;}
   case "interrupt":Thread.currentThread().interrupt();throw new InterruptedIOException("synthetic cancelled transfer");
   case "wrapped-interrupt":throw new IOException("synthetic wrapped cancellation",new InterruptedIOException("synthetic cancellation child"));
   case "dns-text":throw new IOException("UnknownHostException synthetic text only");
   case "http503":throw new IOException("片源响应 HTTP 503");
   case "timeout":throw new SocketTimeoutException("synthetic timeout");
   case "fail":throw new IOException("synthetic fatal transfer");
   default:break;
  }
 }
}
final class CencDecryptor {
 static int calls;static final Map<String,byte[]> expectedKeys=new HashMap<>();static final List<Boolean> keyMatches=new ArrayList<>();
 static void reset(){calls=0;expectedKeys.clear();keyMatches.clear();}
 static void decryptInPlace(File target,byte[] key)throws Exception{calls++;byte[] expected=expectedKeys.get(NetworkGuard.lastUrl);boolean match=expected==null||Arrays.equals(expected,key);keyMatches.add(match);if(!match)throw new IOException("wrong fixture content key");Files.write(target.toPath(),new byte[]{7,8,9});}
}
STUB
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
 "$TMP/InitialPlaybackStubs.java" "$SRC/MediaQuality.java" "$SRC/SpadeDecoder.java" "$SRC/DanmakuSigner.java" "$SRC/AppMediaSource.java" "$SRC/PlaybackSources.java" "$SRC/PlaybackCachePolicy.java" \
 "$TEST_ROOT/InitialPlaybackFallbackTest.java" "$TEST_ROOT/Playback1080RecoveryTest.java"
STATUS=0
if [ "$INITIAL" = true ]; then
 "$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.InitialPlaybackFallbackTest || STATUS=1
fi
if [ "$RECOVERY" = true ]; then
 "$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.Playback1080RecoveryTest || STATUS=1
fi
if [ "$STATUS" = 0 ]; then echo 'PASS InitialPlaybackAnd1080 offlineOnly=true networkRequests=0 deviceUntouched=true'; else echo 'FAIL InitialPlaybackAnd1080 offlineOnly=true networkRequests=0 deviceUntouched=true'; fi
exit "$STATUS"
