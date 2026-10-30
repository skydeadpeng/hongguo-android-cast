#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [ "$#" -gt 0 ]; do
 case "$1" in
  --source-root) [ "$#" -ge 2 ] || exit 2; SOURCE_ROOT="$2"; shift 2;;
  *) echo 'USAGE run-http403-tests.sh [--source-root PROJECT]' >&2; exit 2;;
 esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-http403.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
cat > "$TMP/Http403Stubs.java" <<'STUB'
package io.github.jackotom.hongguo.android;
import java.io.*;import java.net.*;import java.nio.file.Files;import java.util.*;import java.util.function.Consumer;
final class MainActivity {Consumer<String> callback;boolean isFinishing(){return false;}boolean isDestroyed(){return false;}void script(String value){callback.accept(value);}}
final class EpisodeResolver {static int calls;static String resolve(String sid,String vid)throws Exception{calls++;throw new IOException("fixture web lookup must not run");}}
final class AppTransport {static int heads;static boolean domain(String host,String...allowed){if(host==null)return false;host=host.toLowerCase(Locale.ROOT);for(String suffix:allowed)if(host.equals(suffix)||host.endsWith("."+suffix))return true;return false;}static String media(String url,String ua,String ref){heads++;return url;}}
final class NetworkGuard {
 static final class MediaRedirectException extends IOException {MediaRedirectException(){super("片源跳转不可用（地址无效或跳转次数超限）");}}
 static final class MediaResponseException extends IOException {MediaResponseException(String message){super(message);}}
 static final String UA="fixture-agent",REFERER="https://hongguoduanju.com/";
 static final class Transfer {final String url,ua,ref;final boolean clean;Transfer(String url,String ua,String ref,boolean clean){this.url=url;this.ua=ua;this.ref=ref;this.clean=clean;}}
 static int heads;static String lastUrl;static final Map<String,Integer> counts=new HashMap<>();static final List<Transfer> transfers=new ArrayList<>();static final Map<String,String> outcomes=new HashMap<>();
 static void reset(){heads=0;lastUrl=null;transfers.clear();outcomes.clear();counts.clear();EpisodeResolver.calls=0;AppTransport.heads=0;CencDecryptor.reset();}
 static URI validate(String value,boolean official)throws Exception{URI uri=new URI(value);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getPort()!=-1&&uri.getPort()!=443)throw new IOException("仅允许有效HTTPS服务地址");return uri;}
 static String media(String url)throws Exception{heads++;return url;}
 static void download(String url,File target,long max)throws Exception{download(url,target,max,UA,REFERER);}
 static void download(String url,File target,long max,String ua,String ref)throws Exception{
  validate(url,false);lastUrl=url;transfers.add(new Transfer(url,ua,ref,!target.exists()||target.length()==0));
  Files.write(target.toPath(),new byte[]{1,2,3,4});
  int count=counts.getOrDefault(url,0)+1;counts.put(url,count);String outcome=outcomes.getOrDefault(url,"success");if(outcome.equals("http403-once"))outcome=count==1?"http403":"success";
  switch(outcome){
   case "http403":throw new IOException("片源响应 HTTP 403");
   case "dns":throw new UnknownHostException("fixture DNS miss");
   case "http401":throw new IOException("片源响应 HTTP 401");
   case "generic":throw new IOException("fixture generic transfer failure");
   case "tls":throw new javax.net.ssl.SSLHandshakeException("fixture certificate verification failed");
   case "tls-403-message":throw new javax.net.ssl.SSLHandshakeException("片源响应 HTTP 403");
   case "tls-dns":{javax.net.ssl.SSLHandshakeException failure=new javax.net.ssl.SSLHandshakeException("fixture certificate verification failed");failure.initCause(new UnknownHostException("fixture nested DNS"));throw failure;}
   case "wrapped-cancel":throw new IOException("fixture cancelled owner",new InterruptedIOException("fixture cancelled child"));
   case "thread-cancel":Thread.currentThread().interrupt();throw new IOException("fixture interrupt flag");
   case "empty":Files.write(target.toPath(),new byte[0]);break;
   case "oversized":Files.write(target.toPath(),new byte[(int)max+1]);break;
   default:break;
  }
 }
}
final class CencDecryptor {
 static final Map<String,byte[]> expectedKeys=new HashMap<>();static final Set<String> failures=new HashSet<>();static final List<String> urls=new ArrayList<>();static final List<Boolean> keyMatches=new ArrayList<>();
 static void reset(){expectedKeys.clear();failures.clear();urls.clear();keyMatches.clear();}
 static void decryptInPlace(File target,byte[] key)throws Exception{String url=NetworkGuard.lastUrl;urls.add(url);byte[] expected=expectedKeys.get(url);boolean matches=expected!=null&&Arrays.equals(expected,key);keyMatches.add(matches);if(!matches)throw new IOException("fixture wrong candidate key");if(failures.contains(url))throw new IOException("fixture permanent decrypt failure");Files.write(target.toPath(),new byte[]{7,8,9});}
}
STUB
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
 "$TMP/Http403Stubs.java" "$SRC/MediaQuality.java" "$SRC/SpadeDecoder.java" "$SRC/DanmakuSigner.java" "$SRC/AppMediaSource.java" "$SRC/PlaybackSources.java" "$SRC/PlaybackCachePolicy.java" \
 "$TEST_ROOT/Http403RecoveryTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.Http403RecoveryTest
