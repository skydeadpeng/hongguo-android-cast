#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE_ROOT="$(cd "$ROOT/.." && pwd)"
RECOVERY_ONLY=false
LOOKUP_ONLY=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --source-root) SOURCE_ROOT="$2"; shift 2 ;;
    --recovery-only) RECOVERY_ONLY=true; shift ;;
    --lookup-only) LOOKUP_ONLY=true; shift ;;
    *) echo 'USAGE run-native-media-contract-tests.sh [--source-root PROJECT] [--recovery-only]' >&2; exit 2 ;;
  esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
SDK_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
TMP="$(mktemp -d -t hongguo-native-contract.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
if [ "$RECOVERY_ONLY" = false ] && [ "$LOOKUP_ONLY" = false ]; then
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR:$SDK_JAR" -d "$TMP/crypto" \
  "$SRC/CryptoBridge.java" "$SRC/AppTransport.java" "$SRC/NetworkGuard.java" "$ROOT/tests/NativeCryptoTransportTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/crypto:$JSON_JAR:$SDK_JAR" io.github.jackotom.hongguo.android.NativeCryptoTransportTest
fi
cat > "$TMP/OfflineMediaStubs.java" <<'STUB'
package io.github.jackotom.hongguo.android;
import java.io.*;import java.net.URI;import java.nio.file.Files;import java.util.function.Consumer;
final class MainActivity {Consumer<String> callback;boolean isFinishing(){return false;}boolean isDestroyed(){return false;}void script(String value){callback.accept(value);}}
final class EpisodeResolver {static int calls;static String result;static void reset(){calls=0;result=null;}static String resolve(String sid,String vid)throws Exception{calls++;if(result!=null)return result;throw new IOException("synthetic web miss");}}
final class AppTransport {static int heads;static boolean domain(String host,String...allowed){if(host==null)return false;host=host.toLowerCase(java.util.Locale.ROOT);for(String suffix:allowed)if(host.equals(suffix)||host.endsWith("."+suffix))return true;return false;}static String media(String url,String ua,String ref){heads++;return url;}}
final class NetworkGuard {
 static final class MediaRedirectException extends IOException {MediaRedirectException(){super("片源跳转不可用（地址无效或跳转次数超限）");}}
 static final class MediaResponseException extends IOException {MediaResponseException(String message){super(message);}}
 static final String UA="offline-agent",REFERER="https://hongguoduanju.com/";static int downloads,heads;static String lastReferer,lastUrl;
 static final java.util.List<String> attempted=new java.util.ArrayList<>();
 static final java.util.List<Boolean> cleanAtStart=new java.util.ArrayList<>();
 static final java.util.Map<String,String> outcomes=new java.util.HashMap<>(),agents=new java.util.HashMap<>(),referers=new java.util.HashMap<>();
 static void reset(){downloads=0;heads=0;lastReferer=null;lastUrl=null;attempted.clear();cleanAtStart.clear();outcomes.clear();agents.clear();referers.clear();AppTransport.heads=0;EpisodeResolver.reset();CencDecryptor.reset();}
 static URI validate(String value,boolean official)throws Exception{URI uri=new URI(value);if(!uri.getScheme().equals("https")||uri.getHost()==null)throw new IOException("url");return uri;}
 static String media(String url){heads++;return url;}
 static void download(String url,File target,long max)throws Exception{download(url,target,max,UA,REFERER);}
 static void download(String url,File target,long max,String ua,String ref)throws Exception{
  downloads++;lastReferer=ref;lastUrl=url;attempted.add(url);agents.put(url,ua);referers.put(url,ref);cleanAtStart.add(!target.exists()||target.length()==0);
  String outcome=outcomes.getOrDefault(url,"encrypted");
  Files.write(target.toPath(),outcome.equals("clear")?new byte[]{4,5,6}:new byte[]{1,2,3,4});
  if(outcome.equals("fail"))throw new IOException("synthetic transfer failure");
  if(outcome.startsWith("http"))throw new IOException("片源响应 HTTP "+outcome.substring(4));
  if(outcome.equals("deadline"))throw new IOException("播放缓存下载超时");
  if(outcome.equals("timeout"))throw new java.net.SocketTimeoutException("synthetic retryable network timeout");
  if(outcome.equals("eof"))throw new EOFException("片源响应不完整");
  if(outcome.equals("reset"))throw new java.net.SocketException("Connection reset");
  if(outcome.equals("media-response"))throw new MediaResponseException("片源返回了非视频错误页面");
  if(outcome.equals("interrupt")){Thread.currentThread().interrupt();throw new InterruptedIOException("synthetic cancellation");}
 }
}
final class CencDecryptor {
 static boolean fail;static int calls;
 static final java.util.Set<String> failures=new java.util.HashSet<>(),interruptions=new java.util.HashSet<>();
 static final java.util.Map<String,byte[]> expectedKeys=new java.util.HashMap<>();
 static final java.util.List<Boolean> keyMatches=new java.util.ArrayList<>();
 static void reset(){fail=false;calls=0;failures.clear();interruptions.clear();expectedKeys.clear();keyMatches.clear();}
 static void decryptInPlace(File target,byte[] key)throws Exception{calls++;byte[] expected=expectedKeys.get(NetworkGuard.lastUrl);keyMatches.add(expected==null||java.util.Arrays.equals(expected,key));if(!keyMatches.get(keyMatches.size()-1))throw new IOException("wrong synthetic candidate key");if(interruptions.contains(NetworkGuard.lastUrl)){Thread.currentThread().interrupt();throw new InterruptedIOException("synthetic CENC cancellation");}if(fail||failures.contains(NetworkGuard.lastUrl))throw new IOException("synthetic preparation error");Files.write(target.toPath(),new byte[]{7,8,9});}
}
STUB
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR:$SDK_JAR" -d "$TMP/plans" \
  "$TMP/OfflineMediaStubs.java" "$SRC/MediaQuality.java" "$SRC/DanmakuSigner.java" "$SRC/SpadeDecoder.java" "$SRC/AppMediaSource.java" "$SRC/PlaybackSources.java" "$SRC/PlaybackCachePolicy.java" \
  "$ROOT/tests/PlaybackSourcesContractTest.java" "$ROOT/tests/PlaybackSourcesRecoveryTest.java" "$ROOT/tests/PlaybackSourcesLookupRetryTest.java" "$ROOT/tests/PlaybackSourcesNativeLookupTest.java"
if [ "$RECOVERY_ONLY" = false ] && [ "$LOOKUP_ONLY" = false ]; then
  "$JAVA_HOME/bin/java" -cp "$TMP/plans:$JSON_JAR:$SDK_JAR" io.github.jackotom.hongguo.android.PlaybackSourcesContractTest
fi
if [ "$LOOKUP_ONLY" = false ]; then
  "$JAVA_HOME/bin/java" -cp "$TMP/plans:$JSON_JAR:$SDK_JAR" io.github.jackotom.hongguo.android.PlaybackSourcesRecoveryTest
fi
if [ "$RECOVERY_ONLY" = false ]; then
  "$JAVA_HOME/bin/java" -cp "$TMP/plans:$JSON_JAR:$SDK_JAR" io.github.jackotom.hongguo.android.PlaybackSourcesLookupRetryTest
  "$JAVA_HOME/bin/java" -cp "$TMP/plans:$JSON_JAR:$SDK_JAR" io.github.jackotom.hongguo.android.PlaybackSourcesNativeLookupTest
fi
echo 'PASS NativeMediaContracts offlineOnly=true deviceUntouched=true'
