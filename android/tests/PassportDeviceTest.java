package io.github.jackotom.hongguo.android;
import java.nio.file.*;import java.util.*;import java.io.*;import org.json.*;
public final class PassportDeviceTest {
 static int checks;static void check(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}interface Action{void run()throws Exception;}static void rejects(Action action)throws Exception{try{action.run();}catch(Exception expected){checks++;return;}throw new AssertionError("invalid input accepted");}
 public static void main(String[]args)throws Exception{
  JSONObject v=new JSONObject(Files.readString(Path.of(args[0])));byte[]seed=DanmakuSigner.unhex(v.getString("seed")),cipher=Base64.getDecoder().decode(v.getString("ciphertext")),plain=PassportSigner.bytes(v.getString("plain"));
  check(DanmakuSigner.hex(PassportDevice.key(seed)).equals(v.getString("derived_key")),"SHA512 key independent vector");check(Arrays.equals(PassportDevice.decrypt(cipher),plain),"OpenSSL independently encrypted vector decrypted");byte[]actual=PassportDevice.encrypt(plain,seed);check(Arrays.equals(PassportDevice.decrypt(actual),plain),"Java gzip AES-CBC roundtrip semantic equivalence");
  byte[]corrupt=cipher.clone();corrupt[corrupt.length-1]^=1;rejects(()->PassportDevice.decrypt(corrupt));rejects(()->PassportDevice.decrypt(new byte[38]));rejects(()->PassportDevice.decrypt(Arrays.copyOf(cipher,cipher.length+1)));rejects(()->PassportDevice.encrypt(plain,new byte[31]));
  JSONObject device=PassportDevice.create();PassportDevice.validate(device);JSONObject payload=OfficialApiClient.parse(PassportDevice.payload(device,123));check(payload.getString("magic_tag").equals("ss_app_log"),"registration type");JSONObject h=payload.getJSONObject("header");check(h.getInt("aid")==8662&&h.getInt("version_code")==73532&&h.getString("device_model").equals("25053RT47C"),"profile aligned to Passport");check(h.getString("cdid").equals(device.getString("cdid"))&&h.getString("openudid").equals(device.getString("openudid")),"stable device profile");check(!payload.toString().contains("mobile")&&!payload.toString().contains("token"),"no account data in registration");
  byte[]bomb=PassportDevice.gzip(new byte[PassportDevice.LIMIT],false);check(PassportDevice.gunzip(bomb).length==PassportDevice.LIMIT,"decompression exact bound");rejects(()->PassportDevice.gzip(new byte[PassportDevice.LIMIT+1],false));
  System.out.println("PASS PassportDeviceTest checks="+checks+" independentOpenSSL=true registrationProfile=true decompressionBound=true networkRequests=0");
 }
}
