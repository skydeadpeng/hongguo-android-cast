package io.github.jackotom.hongguo.android;

import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Production byte RPC and pre-DNS transport rejection tests. No request reaches DNS or a socket. */
public final class NativeCryptoTransportTest {
    private static int checks;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static byte[] hex(String value) { byte[] out = new byte[value.length()/2]; for (int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16); return out; }
    private static String b64(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static JSONObject call(JSONObject input) throws Exception { return new JSONObject(new CryptoBridge().process(input.toString())); }
    private static void rejected(Checked action, String reason) throws Exception {
        try { action.run(); } catch (Exception expected) { check(expected.getMessage().contains(reason), "typed preflight rejection"); return; }
        throw new AssertionError("request was not rejected before transport");
    }
    public static void main(String[] args) throws Exception {
        JSONObject digest = new JSONObject().put("operation","digest").put("algorithm","MD5").put("data",b64(new byte[]{97,98,99}));
        JSONObject result = call(digest);
        check(result.isNull("error") && Arrays.equals(Base64.getDecoder().decode(result.getString("data")),hex("900150983cd24fb0d6963f7d28e17f72")),"MD5 abc known vector and response contract");
        result=call(digest.put("algorithm","SHA-512"));
        check(Arrays.equals(Base64.getDecoder().decode(result.getString("data")),hex("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f")),"SHA512 abc known vector");
        // Public NIST SP800-38A fixture only; never a production content key.
        byte[] key=hex("2b7e151628aed2a6abf7158809cf4f3c"), iv=hex("000102030405060708090a0b0c0d0e0f"), plain=hex("6bc1bee22e409f96e93d7e117393172a");
        JSONObject decrypt=new JSONObject().put("operation","decrypt").put("algorithm","AES-128-CBC").put("data",b64(hex("7649abac8119b246cee98e9b12e9197d"))).put("key",b64(key)).put("iv",b64(iv)).put("autoPadding",false);
        result=call(decrypt); check(result.isNull("error") && Arrays.equals(Base64.getDecoder().decode(result.getString("data")),plain),"AES CBC NIST no-padding vector");
        Cipher reference=Cipher.getInstance("AES/CBC/PKCS5Padding"); reference.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
        result=call(decrypt.put("autoPadding",true).put("data",b64(reference.doFinal(plain))));
        check(result.isNull("error") && Arrays.equals(Base64.getDecoder().decode(result.getString("data")),plain),"AES CBC padding contract");
        for (JSONObject bad : new JSONObject[]{new JSONObject().put("operation","digest").put("algorithm","SHA-1").put("data",""),new JSONObject().put("operation","unknown").put("data",""),new JSONObject().put("operation","digest").put("algorithm","MD5").put("data","not base64 !"),new JSONObject(decrypt.toString()).put("key",b64(new byte[15])),new JSONObject(decrypt.toString()).put("iv",b64(new byte[15])),new JSONObject(decrypt.toString()).put("data",b64(new byte[7]))}) {
            result=call(bad); check(result.isNull("data") && !result.isNull("error"),"crypto malformed input fails without plaintext");
        }
        check(new JSONObject(new CryptoBridge().process(null)).isNull("data"),"null crypto RPC rejected");
        check(new JSONObject(new CryptoBridge().process(" ".repeat(256*1024+1))).isNull("data"),"bounded crypto message rejected");
        for(String host:new String[]{"fqnovel.com","api.fqnovel.com","API.FQNOVEL.COM","api3-normal.snssdk.com"}) check(AppTransport.domain(host,"fqnovel.com","snssdk.com"),"exact/suffix allowlist");
        for(String host:new String[]{"evilfqnovel.com","fqnovel.com.evil.net","api.snssdk.com.evil.net","","127.0.0.1"}) check(!AppTransport.domain(host,"fqnovel.com","snssdk.com"),"suffix boundary rejects unrelated host");
        check(!AppTransport.domain(null,"fqnovel.com"),"null host rejected");
        JSONObject request=new JSONObject().put("url","https://unrelated.example.net/not-requested");
        rejected(()->AppTransport.request(new JSONObject(request.toString()).put("method","TRACE")),"方法");
        rejected(()->AppTransport.request(new JSONObject(request.toString()).put("body","x".repeat(128*1024+1))),"过长");
        for(String header:new String[]{"authorization","cookie","host","content-length","transfer-encoding","x-unknown"}) rejected(()->AppTransport.request(new JSONObject(request.toString()).put("headers",new JSONObject().put(header,"test"))),"请求头");
        for(String value:new String[]{"a\r\nInjected: true","line\nend","x".repeat(16385)}) rejected(()->AppTransport.request(new JSONObject(request.toString()).put("headers",new JSONObject().put("user-agent",value))),"请求头");
        rejected(()->AppTransport.request(request),"地址");
        for(String url:new String[]{"https://fqnovel.com.evil.net/x","https://evilfqnovel.com/x","https://api.fqnovel.com@evil.net/x","http://api.fqnovel.com/x","https://api.fqnovel.com:444/x","https://api.fqnovel.com/x#fragment","https://127.0.0.1/x"}) {
            boolean failed=false; try { AppTransport.request(new JSONObject().put("url",url)); } catch(Exception expected) { failed=true; } check(failed,"malformed URL rejected before DNS");
        }
        System.out.println("PASS NativeCryptoTransportTest checks="+checks+" networkRequests=0 productionCrypto=true productionTransportValidation=true");
    }
}
