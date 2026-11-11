package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Android search wire format, isolated from the existing media-source signer.
 * Protocol interoperability reference: itangji/hongguorule FQ registration/search.
 * Uses newly registered anonymous sessions, never bundled device/account values.
 */
final class SearchProtocol {
    private static final SecureRandom RANDOM = new SecureRandom();
    static byte[] random(int length) { byte[] value=new byte[length]; RANDOM.nextBytes(value); return value; }
    static String hex(byte[] value) { StringBuilder s=new StringBuilder();for(byte b:value)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString(); }
    static byte[] unhex(String value) { byte[] out=new byte[value.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return out; }
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static byte[] join(byte[]... values) { ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] value:values)out.write(value,0,value.length);return out.toByteArray(); }
    private static byte[] hash(String algorithm,byte[] value)throws Exception { return MessageDigest.getInstance(algorithm).digest(value); }
    private static byte[] pad(byte[] value) { int n=16-value.length%16;byte[] out=Arrays.copyOf(value,value.length+n);Arrays.fill(out,value.length,out.length,(byte)n);return out; }
    private static byte[] aes(byte[] value,byte[] key,byte[] iv)throws Exception { Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));return cipher.doFinal(value); }
    static byte[] gzip(byte[] value)throws Exception { ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(out)){gzip.write(value);}return out.toByteArray(); }
    static byte[] registrationBody(byte[] json)throws Exception { return ttEncrypt(gzip(json),random(32)); }
    static byte[] ttEncrypt(byte[] compressed,byte[] nonce)throws Exception {
        if(nonce.length!=32)throw new IllegalArgumentException("invalid nonce length");
        byte[] fixed=Base64.getDecoder().decode("TdTC5rgxYgkOUrPHpnM7pByyRiuCmrWKGWs521cXdST0m69/COjWjSanLjfBqVovHwWlGJKu8pSXMrYqOKrdWA==");
        byte[] derived=hash("SHA-512",join(hash("SHA-512",nonce),fixed));
        byte[] encrypted=aes(pad(join(hash("SHA-512",compressed),compressed)),Arrays.copyOfRange(derived,0,16),Arrays.copyOfRange(derived,16,32));
        return join(unhex("746305100000"),nonce,encrypted);
    }
    static Map<String,String> headers(String query)throws Exception { return headers(query,System.currentTimeMillis()/1000,random(4),1+RANDOM.nextInt(Integer.MAX_VALUE-1)); }
    static Map<String,String> headers(String query,long seconds,byte[] nonce,int randomField)throws Exception {
        Map<String,String> out=new LinkedHashMap<>();
        out.put("user-agent","com.dragon.read");out.put("accept","application/json");
        out.put("x-ss-req-ticket",Long.toString(seconds*1000));out.put("x-khronos",Long.toString(seconds));
        out.put("x-ladon",ladon(seconds,nonce));out.put("x-argus",argus(query,seconds,randomField));return out;
    }
    static String query(Map<String,String> values) {
        StringBuilder out=new StringBuilder();for(Map.Entry<String,String> entry:values.entrySet()){if(out.length()>0)out.append('&');out.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));}return out.toString();
    }
    static String encode(String value) {
        StringBuilder out=new StringBuilder();for(byte b:utf8(value)){int c=b&255;if(c>=65&&c<=90||c>=97&&c<=122||c>=48&&c<=57||c==45||c==95||c==46||c==126)out.append((char)c);else if(c==32)out.append('+');else out.append('%').append("0123456789ABCDEF".charAt(c>>>4)).append("0123456789ABCDEF".charAt(c&15));}return out.toString();
    }
    private static String value(String query,String key) { for(String item:query.split("&")){int at=item.indexOf('=');if(at>=0&&item.substring(0,at).equals(key))return item.substring(at+1);}return ""; }
    private static long read64(byte[] value,int offset) { return ByteBuffer.wrap(value,offset,8).order(ByteOrder.LITTLE_ENDIAN).getLong(); }
    private static void write64(byte[] value,int offset,long number) { ByteBuffer.wrap(value,offset,8).order(ByteOrder.LITTLE_ENDIAN).putLong(number); }
    static String ladon(long seconds,byte[] nonce)throws Exception {
        if(nonce.length!=4)throw new IllegalArgumentException("invalid nonce length");
        byte[] key=hex(hash("MD5",join(nonce,utf8("1967")))).getBytes(StandardCharsets.US_ASCII);
        long[] round=new long[35];long a=read64(key,0),b=read64(key,8);round[0]=a;
        ArrayDeque<Long> queue=new ArrayDeque<>();queue.add(read64(key,16));queue.add(read64(key,24));
        for(int i=0;i<34;i++){long next=(Long.rotateRight(b,8)+a)^i;queue.add(next);next^=Long.rotateLeft(a,3);round[i+1]=next;a=next;b=queue.removeFirst();}
        byte[] plain=pad(utf8(seconds+"-1611921764-1967")),encrypted=new byte[plain.length];
        for(int offset=0;offset<plain.length;offset+=16){a=read64(plain,offset);b=read64(plain,offset+8);for(int i=0;i<34;i++){b=round[i]^(a+Long.rotateRight(b,8));a=b^Long.rotateLeft(a,3);}write64(encrypted,offset,a);write64(encrypted,offset+8,b);}
        return Base64.getEncoder().encodeToString(join(nonce,encrypted));
    }
    private static final class Proto {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        void varint(long value){value&=0xffffffffL;while(value>=128){out.write((int)(value&127)|128);value>>>=7;}out.write((int)value);}
        Proto number(int field,long value){varint(field*8);varint(value);return this;}
        Proto bytes(int field,byte[] value){varint(field*8+2);varint(value.length);out.write(value,0,value.length);return this;}
        Proto text(int field,String value){return bytes(field,utf8(value));}
        byte[] data(){return out.toByteArray();}
    }
    static String argus(String query,long seconds,int randomField)throws Exception {
        Proto pb=new Proto().number(1,1077940818).number(2,2).number(3,randomField).text(4,"1967").text(5,value(query,"device_id")).text(6,"1611921764").text(7,value(query,"version_name"));
        pb.text(8,"v04.04.05-ov-android").number(9,134744640).bytes(10,new byte[8]).number(11,0).number(12,seconds*2);
        pb.bytes(13,Arrays.copyOf(sm3(new byte[16]),6)).bytes(14,Arrays.copyOf(sm3(utf8(query)),6));
        pb.bytes(15,new Proto().number(1,1).number(2,1).number(3,1).number(7,0xC792ECCCL).data()).text(16,"").text(20,"none").number(21,738);
        pb.bytes(23,new Proto().text(1,"NX551J").number(2,8196).number(4,0x80E0D800L).data()).number(25,2);
        byte[] plain=pad(pb.data()),encrypted=new byte[plain.length];
        byte[] key=unhex("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304");
        long[] schedule=new long[72];for(int i=0;i<4;i++)schedule[i]=read64(key,i*8);
        long z=0x3DC94C3A046D678BL;
        for(int i=4;i<72;i++){long t=Long.rotateRight(schedule[i-1],3)^schedule[i-3];t^=Long.rotateRight(t,1);schedule[i]=~schedule[i-4]^t^(((z>>>((i-4)%62))&1)^3);}
        for(int off=0;off<plain.length;off+=16){long x=read64(plain,off),y=read64(plain,off+8);for(int i=0;i<72;i++){long next=x^(Long.rotateLeft(y,1)&Long.rotateLeft(y,8))^Long.rotateLeft(y,2)^schedule[i];x=y;y=next;}write64(encrypted,off,x);write64(encrypted,off+8,y);}
        byte[] magic=unhex("f2f7fcfff2f7fcff"),mixed=join(magic,encrypted,unhex("eafb2cfe8568519154"));
        for(int i=8;i<mixed.length;i++)mixed[i]^=magic[i%8];
        for(int i=0,j=mixed.length-1;i<j;i++,j--){byte temp=mixed[i];mixed[i]=mixed[j];mixed[j]=temp;}
        byte[] cipher=aes(pad(join(mixed,utf8("ao"))),unhex("d31e3718288a1027baab59f146a09a9c"),unhex("ea180a0336ed352fcd24e4d50018ae54"));
        return Base64.getEncoder().encodeToString(join(unhex("f281"),cipher));
    }
    static byte[] sm3(byte[] message) {
        int length=((message.length+9+63)/64)*64;byte[] data=Arrays.copyOf(message,length);data[message.length]=(byte)0x80;
        ByteBuffer.wrap(data,length-8,8).putLong((long)message.length*8);
        int[] v={0x7380166f,0x4914b2b9,0x172442d7,0xda8a0600,0xa96f30bc,0x163138aa,0xe38dee4d,0xb0fb0e4e};
        for(int offset=0;offset<length;offset+=64){int[] w=new int[68];for(int i=0;i<16;i++)w[i]=ByteBuffer.wrap(data,offset+i*4,4).getInt();
            for(int i=16;i<68;i++){int x=w[i-16]^w[i-9]^Integer.rotateLeft(w[i-3],15);w[i]=x^Integer.rotateLeft(x,15)^Integer.rotateLeft(x,23)^Integer.rotateLeft(w[i-13],7)^w[i-6];}
            int a=v[0],b=v[1],c=v[2],d=v[3],e=v[4],f=v[5],g=v[6],h=v[7];
            for(int i=0;i<64;i++){int ss1=Integer.rotateLeft(Integer.rotateLeft(a,12)+e+Integer.rotateLeft(i<16?0x79cc4519:0x7a879d8a,i),7);int ss2=ss1^Integer.rotateLeft(a,12);int ff=i<16?a^b^c:(a&b)|(a&c)|(b&c);int gg=i<16?e^f^g:(e&f)|(~e&g);int t1=ff+d+ss2+(w[i]^w[i+4]);int t2=gg+h+ss1+w[i];d=c;c=Integer.rotateLeft(b,9);b=a;a=t1;h=g;g=Integer.rotateLeft(f,19);f=e;e=t2^Integer.rotateLeft(t2,9)^Integer.rotateLeft(t2,17);}
            v[0]^=a;v[1]^=b;v[2]^=c;v[3]^=d;v[4]^=e;v[5]^=f;v[6]^=g;v[7]^=h;
        }ByteBuffer out=ByteBuffer.allocate(32);for(int n:v)out.putInt(n);return out.array();
    }
}
