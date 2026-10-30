package io.github.jackotom.hongguo.android;
import java.nio.file.*;import java.util.*;import org.json.*;
public final class PassportSignerTest {
 static int checks;static void check(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
 public static void main(String[]args)throws Exception{
  JSONObject v=new JSONObject(Files.readString(Path.of(args[0])));String q=v.getString("query");byte[]body=PassportSigner.bytes(v.getString("body"));int ts=v.getInt("ts");PassportSigner.Branch b=PassportSigner.branch(q,body,ts);
  check(Integer.toUnsignedLong(b.iv)==v.getLong("iv"),"IV branch");check(DanmakuSigner.hex(b.queryHash).equals(v.getString("sm3")),"SM3 query");byte[]f=PassportSigner.f13(b,ts);check(DanmakuSigner.hex(f).equals(v.getString("f13")),"F13");check(DanmakuSigner.hex(PassportSigner.proto(q,ts,0x12345678,"7000000000000000001",b,f)).equals(v.getString("proto")),"full protobuf");check(PassportSigner.helios(ts,0x12345678).equals(v.getString("helios")),"Helios");check(PassportSigner.gorgon(q,body,ts,0x4567).equals(v.getString("gorgon")),"Gorgon");
  byte[]data=new byte[256];for(int i=0;i<data.length;i++)data[i]=(byte)i;JSONArray branches=v.getJSONArray("aes_v3");for(int i=0;i<branches.length();i++)check(DanmakuSigner.hex(PassportSigner.aesV3(data,ts+i)).equals(branches.getString(i)),"AESV3 branch "+i);
  String actual=PassportSigner.medusa(q,ts,0x12345678,0x87654321,0x33445566,"7000000000000000001",b);check(actual.equals(v.getString("medusa")),"Medusa");
  System.out.println("PASS PassportSignerTest checks="+checks+" fullProtocol=true goldenBranches=4 networkRequests=0");
 }
}
