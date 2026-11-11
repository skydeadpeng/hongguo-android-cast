package io.github.jackotom.hongguo.android;
/** Only the lifecycle JVM harness uses this substitute. Production Keystore is compiled separately. */
final class AccountTokenVault {
 String seal(String token,String scope){return java.util.Base64.getEncoder().encodeToString((scope+":"+token).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 String open(String data,String scope)throws Exception {String decoded=new String(java.util.Base64.getDecoder().decode(data),java.nio.charset.StandardCharsets.UTF_8);if(!decoded.startsWith(scope+":"))throw new Exception("wrong scope");return decoded.substring(scope.length()+1);}
}
