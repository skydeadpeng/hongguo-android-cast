// SPDX-License-Identifier: GPL-3.0-only
// Java port of GPL-3.0 danmaku-sign.js from jackotom/hongguo-drama,
// adapted there from dawei233/hongguo-drama c884fab6c6e36ad38173e251738b9b35f958383f.
package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Complete anonymous comment-client protocol; do not mix its POST body with GET signatures. */
final class DanmakuSigner {
    static final byte[] ARGUS_KEY = unhex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c");
    static final String USER_AGENT = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
    private DanmakuSigner() { }
    static String query(long ms, String device, String install) throws Exception {
        if (ms <= 0 || !device.matches("[1-9][0-9]{18}") || !install.matches("[1-9][0-9]{18}")) throw new IllegalArgumentException("弹幕设备标识无效");
        TreeMap<String,String> p = new TreeMap<>();
        String[][] fixed = {{"aid","8662"},{"app_name","novelread"},{"version_code","73532"},{"version_name","7.3.5.32"},{"manifest_version_code","73532"},{"update_version_code","73532"},{"channel","update_64"},{"device_platform","android"},{"os","android"},{"ssmix","a"},{"device_type","25053RT47C"},{"device_brand","Redmi"},{"language","zh"},{"os_api","36"},{"os_version","16"},{"resolution","1280*2772"},{"dpi","520"},{"ac","wifi"}};
        for (String[] pair : fixed) p.put(pair[0],pair[1]);
        p.put("device_id",device);p.put("iid",install);p.put("_rticket",Long.toString(ms));
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String,String> e : p.entrySet()) { if(out.length()>0)out.append('&');out.append(quote(e.getKey())).append('=').append(quote(e.getValue())); }
        return out.toString();
    }
    private static String quote(String s) throws Exception { return URLEncoder.encode(s,"UTF-8").replace("*","%2A").replace("%7E","~"); }
    static Map<String,String> headers(String query,long seconds,byte[] nonce) throws Exception {
        if(nonce==null||nonce.length!=11)throw new IllegalArgumentException("弹幕随机数无效");
        Map<String,String> out=new LinkedHashMap<>();out.put("X-Gorgon",gorgon(query,seconds,nonce));out.put("X-Khronos",Long.toString(seconds));out.put("X-SS-Req-Ticket",Long.toString(seconds*1000));out.put("X-SS-STUB","");out.put("X-Argus",argus(query,seconds,nonce));out.put("X-Ladon",ladon("8662",seconds,Arrays.copyOfRange(nonce,2,6)));return out;
    }
    static String gorgon(String q,long seconds,byte[] nonce)throws Exception {
        int head2=((nonce[0]&255)%7+1)<<5,head3=0xe0|(nonce[1]&15);
        byte[] seed=new byte[20];System.arraycopy(md5(bytes(q)),0,seed,0,4);seed[12]=0;seed[13]=1;seed[14]=7;seed[15]=4;ByteBuffer.wrap(seed).putInt(16,(int)seconds);
        int[] key={0x4a,0,0x16,head3,0x47,0x6c,0,head2},box=new int[256];for(int i=0;i<256;i++)box[i]=i;
        int j=0;for(int i=0;i<256;i++){j=(j+box[i]+key[i&7])&255;box[i]=box[j];} // Protocol assignment, intentionally NOT a swap.
        j=0;for(int offset=0;offset<20;offset++){int i=offset+1;j=(j+box[i])&255;box[i]=box[j];seed[offset]^=(byte)box[(box[i]+box[j])&255];}
        for(int i=0;i<20;i++){int n=seed[i]&255;int rotated=((n<<4)|(n>>>4))&255;int v=rotated^(seed[(i+1)%20]&255);seed[i]=(byte)((Integer.reverse(v)>>>24)^0xeb);}
        return hex(join(new byte[]{(byte)0x84,4,(byte)head2,(byte)head3,0,0},seed));
    }
    static String ladon(String aid,long stamp,byte[] prefix)throws Exception {
        byte[] digest=bytes(hex(md5(join(prefix,bytes(aid)))));long left=le(digest,0),right=le(digest,8);ArrayDeque<Long> queue=new ArrayDeque<>();queue.add(le(digest,16));queue.add(le(digest,24));long[] keys=new long[34];
        for(int i=0;i<34;i++){keys[i]=left;long mixed=(Long.rotateRight(right,8)+left)^i;queue.add(mixed);left=mixed^Long.rotateLeft(left,3);right=queue.remove();}
        byte[] data=pad(bytes(stamp+"-1611921764-3019"));for(int offset=0;offset<data.length;offset+=16){long a=le(data,offset),b=le(data,offset+8);for(long key:keys){b=key^(a+Long.rotateRight(b,8));a=b^Long.rotateLeft(a,3);}putLe(data,offset,a);putLe(data,offset+8,b);}return Base64.getEncoder().encodeToString(join(prefix,data));
    }
    static String argus(String query,long stamp,byte[] nonce)throws Exception {
        ByteArrayOutputStream p=new ByteArrayOutputStream();v(p,1,0x20200929L*2);v(p,2,2);v(p,3,Integer.toUnsignedLong(ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).getInt(6)));f(p,4,bytes("3019"));f(p,6,bytes("1611921764"));f(p,7,bytes("6.8.1.32"));f(p,8,bytes("v04.07.01-ml-android"));v(p,9,135135744);f(p,10,new byte[8]);v(p,12,stamp*2);
        f(p,13,Arrays.copyOf(sm3(new byte[16]),6));f(p,14,Arrays.copyOf(sm3(bytes(query)),6));ByteArrayOutputStream nested=new ByteArrayOutputStream();nested.write(8);nested.write(2+(nonce[10]&255)%49*2);for(int i=2;i<=4;i++)v(nested,i,1388734);f(p,15,nested.toByteArray());f(p,20,bytes("none"));v(p,21,738);
        byte[] data=pad(p.toByteArray()),derived=sm3(join(ARGUS_KEY,new byte[]{0x3c,(byte)0xcc,0x56,0x7b},ARGUS_KEY));long[] rounds=new long[72];for(int i=0;i<4;i++)rounds[i]=le(derived,i*8);
        for(int i=4;i<72;i++){long mixed=Long.rotateRight(rounds[i-1],3)^rounds[i-3];mixed^=Long.rotateRight(mixed,1);rounds[i]=~rounds[i-4]^mixed^((0x3dc94c3a046d678bL>>>((i-4)%62))&1)^3;}
        for(int offset=0;offset<data.length;offset+=16){long a=le(data,offset),b=le(data,offset+8);for(long round:rounds){long next=a^(Long.rotateLeft(b,1)&Long.rotateLeft(b,8))^Long.rotateLeft(b,2)^round;a=b;b=next;}putLe(data,offset,a);putLe(data,offset+8,b);}
        byte[] xor={(byte)0xd0,0x4f,(byte)0xfd,(byte)0xff,(byte)0xd0,0x4f,(byte)0xfd,(byte)0xff};for(int i=0;i<data.length;i++)data[i]^=xor[i%8];byte[] reverse=join(xor,data);for(int i=0;i<reverse.length/2;i++){byte tmp=reverse[i];reverse[i]=reverse[reverse.length-1-i];reverse[reverse.length-1-i]=tmp;}
        byte[] container=pad(join(new byte[]{(byte)0xa6,(byte)0xe7,(byte)0x83,(byte)0xee,0x70,1,0x10,9,0x18},reverse,new byte[]{0x56,0x7b}));Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(md5(Arrays.copyOfRange(ARGUS_KEY,0,16)),"AES"),new IvParameterSpec(md5(Arrays.copyOfRange(ARGUS_KEY,16,32))));return Base64.getEncoder().encodeToString(join(new byte[]{0x3c,(byte)0xcc},cipher.doFinal(container)));
    }
    static byte[] sm3(byte[] source){
        byte[] data=Arrays.copyOf(source,((source.length+9+63)/64)*64);data[source.length]=(byte)0x80;ByteBuffer.wrap(data).putLong(data.length-8,(long)source.length*8);
        int[] state={0x7380166f,0x4914b2b9,0x172442d7,0xda8a0600,0xa96f30bc,0x163138aa,0xe38dee4d,0xb0fb0e4e},w=new int[68];
        for(int offset=0;offset<data.length;offset+=64){for(int i=0;i<16;i++)w[i]=ByteBuffer.wrap(data).getInt(offset+i*4);for(int i=16;i<68;i++){int x=w[i-16]^w[i-9]^Integer.rotateLeft(w[i-3],15);w[i]=x^Integer.rotateLeft(x,15)^Integer.rotateLeft(x,23)^Integer.rotateLeft(w[i-13],7)^w[i-6];}
            int a=state[0],b=state[1],c=state[2],d=state[3],e=state[4],f=state[5],g=state[6],h=state[7];for(int i=0;i<64;i++){int a12=Integer.rotateLeft(a,12),s1=Integer.rotateLeft(a12+e+Integer.rotateLeft(i<16?0x79cc4519:0x7a879d8a,i),7),s2=s1^a12;int ff=i<16?a^b^c:(a&b)|(a&c)|(b&c),gg=i<16?e^f^g:(e&f)|(~e&g),t1=ff+d+s2+(w[i]^w[i+4]),t2=gg+h+s1+w[i];d=c;c=Integer.rotateLeft(b,9);b=a;a=t1;h=g;g=Integer.rotateLeft(f,19);f=e;e=t2^Integer.rotateLeft(t2,9)^Integer.rotateLeft(t2,17);}int[] next={a,b,c,d,e,f,g,h};for(int i=0;i<8;i++)state[i]^=next[i];}
        ByteBuffer out=ByteBuffer.allocate(32);for(int n:state)out.putInt(n);return out.array();
    }
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}static byte[] md5(byte[] data)throws Exception{return MessageDigest.getInstance("MD5").digest(data);}
    static byte[] pad(byte[] d){int n=16-d.length%16;byte[] out=Arrays.copyOf(d,d.length+n);Arrays.fill(out,d.length,out.length,(byte)n);return out;}
    static long le(byte[] d,int n){return ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).getLong(n);}static void putLe(byte[]d,int n,long v){ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).putLong(n,v);}
    static byte[] join(byte[]...arrays){int size=0;for(byte[]a:arrays)size+=a.length;byte[]out=new byte[size];int n=0;for(byte[]a:arrays){System.arraycopy(a,0,out,n,a.length);n+=a.length;}return out;}
    static String hex(byte[] d){StringBuilder s=new StringBuilder(d.length*2);for(byte b:d)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}static byte[] unhex(String s){byte[] out=new byte[s.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return out;}
    private static void v(ByteArrayOutputStream out,int field,long value){u(out,field*8);u(out,value);}private static void f(ByteArrayOutputStream out,int field,byte[] b){u(out,field*8+2);u(out,b.length);out.write(b,0,b.length);}private static void u(ByteArrayOutputStream out,long n){while((n&~127L)!=0){out.write((int)(n&127)|128);n>>>=7;}out.write((int)n);}
}
