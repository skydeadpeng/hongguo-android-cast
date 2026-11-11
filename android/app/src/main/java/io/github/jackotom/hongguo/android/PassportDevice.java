package io.github.jackotom.hongguo.android;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.json.*;

/** Anonymous device bootstrap, entirely separate from account phone/code/token data. */
final class PassportDevice {
    static final int LIMIT=4*1024*1024;
    private static final byte[] MAGIC={0x74,0x63,5,0x10,0,0};
    private static final byte[] SALT=DanmakuSigner.unhex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58");
    private PassportDevice(){}
    static JSONObject create()throws Exception{byte[]open=new byte[8];new SecureRandom().nextBytes(open);return new JSONObject().put("cdid",UUID.randomUUID().toString()).put("clientudid",UUID.randomUUID().toString()).put("openudid",DanmakuSigner.hex(open)).put("created",System.currentTimeMillis());}
    static void validate(JSONObject d)throws Exception{if(d==null||!d.optString("cdid").matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")||!d.optString("clientudid").matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")||!d.optString("openudid").matches("[0-9a-fA-F]{16}")||d.optLong("created",0)<=0)throw new IOException("红果设备身份读取失败，请重新准备登录");}
    static byte[] payload(JSONObject d,long stamp)throws Exception{
        validate(d);JSONObject h=new JSONObject();String[][]pairs={{"access","wifi"},{"app_version","7.3.5.32"},{"app_version_minor",""},{"channel","update_64"},{"device_brand","Redmi"},{"device_manufacturer","Redmi"},{"device_model","25053RT47C"},{"device_category","phone"},{"device_platform","android"},{"os","Android"},{"os_version","16"},{"display_density","xxxhdpi"},{"resolution","2772x1280"},{"language","zh"},{"tz_name","Asia/Shanghai"},{"region","CN"},{"package","com.phoenix.read"},{"cpu_abi","arm64-v8a"},{"display_name","红果短剧"},{"sdk_version","3.7.2"},{"sdk_flavor","china"}};for(String[]p:pairs)h.put(p[0],p[1]);
        String[]keys={"aid","os_api","density_dpi","timezone","tz_offset","version_code","manifest_version_code","update_version_code","sdk_target_version","guest_mode","is_system_app","not_request_sender"};int[]values={8662,36,520,8,28800,73532,73532,73532,29,0,0,0};for(int i=0;i<keys.length;i++)h.put(keys[i],values[i]);h.put("apk_first_install_time",d.getLong("created")).put("cdid",d.getString("cdid")).put("clientudid",d.getString("clientudid")).put("openudid",d.getString("openudid")).put("oaid_may_support",true).put("ipv6_list",new JSONArray()).put("sim_serial_number",new JSONArray()).put("custom",new JSONObject().put("is_android_fold",0).put("filter_warn",0).put("web_ua","").put("is_android_pad",0).put("client_ipv4",""));return PassportSigner.bytes(new JSONObject().put("magic_tag","ss_app_log").put("header",h).put("_gen_time",stamp).toString());
    }
    static byte[] key(byte[]seed)throws Exception{if(seed==null||seed.length!=32)throw new IOException("设备加密参数无效");MessageDigest sha=MessageDigest.getInstance("SHA-512");return sha.digest(PassportSigner.join(sha.digest(seed),SALT));}
    static byte[] gzip(byte[]plain,boolean best)throws Exception{
        if(plain==null||plain.length>LIMIT)throw new IOException("账号请求数据过大");ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream gz=new GZIPOutputStream(out){ {if(best)def.setLevel(Deflater.BEST_COMPRESSION);} }){for(int i=0;i<plain.length;i+=16384){PassportSigner.check();gz.write(plain,i,Math.min(16384,plain.length-i));}}
        byte[]z=out.toByteArray();if(best)System.arraycopy(new byte[]{0x1f,(byte)0x8b,8,0,0,0,0,0,0,0},0,z,0,10);return z;
    }
    static byte[] encrypt(byte[]plain,byte[]seed)throws Exception{byte[]z=gzip(plain,true),hash=MessageDigest.getInstance("SHA-512").digest(z),derived=key(seed);Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(Arrays.copyOf(derived,16),"AES"),new IvParameterSpec(Arrays.copyOfRange(derived,16,32)));return PassportSigner.join(MAGIC,seed,cipher.doFinal(PassportSigner.join(hash,z)));}
    static boolean encrypted(byte[]wire){return wire.length>=6&&Arrays.equals(Arrays.copyOf(wire,6),MAGIC);}
    static byte[] decrypt(byte[]wire)throws Exception{
        if(wire==null||wire.length<118||wire.length>LIMIT||!encrypted(wire)||(wire.length-38)%16!=0)throw new IOException("设备注册加密响应无效");
        try{byte[]derived=key(Arrays.copyOfRange(wire,6,38));Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(Arrays.copyOf(derived,16),"AES"),new IvParameterSpec(Arrays.copyOfRange(derived,16,32)));byte[]plain=cipher.doFinal(Arrays.copyOfRange(wire,38,wire.length));if(plain.length<64||!MessageDigest.isEqual(Arrays.copyOf(plain,64),MessageDigest.getInstance("SHA-512").digest(Arrays.copyOfRange(plain,64,plain.length))))throw new IOException();return gunzip(Arrays.copyOfRange(plain,64,plain.length));}catch(Exception invalid){if(invalid instanceof InterruptedIOException)throw invalid;throw new IOException("设备注册加密响应校验失败");}
    }
    static byte[] gunzip(byte[]bytes)throws Exception{
        if(bytes.length>LIMIT)throw new IOException("红果账号响应过大");try(GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(bytes));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]buffer=new byte[16384];for(int n;(n=in.read(buffer))!=-1;){PassportSigner.check();if(n>LIMIT-out.size())throw new IOException("红果账号解压响应过大");out.write(buffer,0,n);}return out.toByteArray();}
    }
}
