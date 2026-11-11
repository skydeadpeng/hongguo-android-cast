package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Offline protocol fixtures only, not playback acceptance media. No network, Android or DRM. */
public final class HuangguoHlsTest {
    static int checks;
    static final String URL="https://media.example/vod/index.m3u8";
    interface Work{void run()throws Exception;}
    static void ok(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    static void reject(Work work,String name)throws Exception{checks++;try{work.run();throw new AssertionError("accepted "+name);}catch(IOException expected){}}
    static String list(String middle){return "#EXTM3U\n#EXT-X-TARGETDURATION:10\n"+middle+"#EXT-X-ENDLIST\n";}
    static String repeat(String text,int count){StringBuilder result=new StringBuilder();for(int i=0;i<count;i++)result.append(text);return result.toString();}
    static long deadline(){return System.nanoTime()+10_000_000_000L;}
    static byte[] ts(){byte[] bytes=new byte[188*3];for(int i=0;i<bytes.length;i+=188)bytes[i]=0x47;return bytes;}
    static byte[] encrypt(byte[] bytes,byte[] key,byte[] iv)throws Exception{Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));return c.doFinal(bytes);}
    static void mp4(File target)throws Exception{try(DataOutputStream out=new DataOutputStream(new FileOutputStream(target))){out.writeInt(16);out.writeBytes("ftyp");out.writeLong(0);out.writeInt(16);out.writeBytes("moov");out.writeLong(0);out.writeInt(9);out.writeBytes("mdat");out.writeByte(1);}}
    static class Fake implements HuangguoHls.Transport {
        String text;Map<String,byte[]> data=new HashMap<>();List<String> requests=new ArrayList<>();AtomicBoolean alive;
        Fake(String text){this.text=text;}
        public HuangguoHls.Manifest playlist(String url,long end){if(!url.equals(URL))throw new AssertionError("manifest URL changed");return new HuangguoHls.Manifest(text,URL);}
        public void download(String url,File target,long max,long end)throws Exception{requests.add(url);byte[] bytes=data.get(url);if(bytes==null)throw new IOException("missing");if(bytes.length>max)throw new IOException("oversized");Files.write(target.toPath(),bytes);if(alive!=null)alive.set(false);}
    }
    static void clean(File scratch,File target)throws Exception{ok(!target.exists(),"no partial output");for(String name:new String[]{"source.ts","segment.part","key.part"})ok(!new File(scratch,name).exists(),"clean "+name);}
    public static void main(String[] args)throws Exception{
        ok(HuangguoHls.address(URL+"?signed=kept", "").equals(URL+"?signed=kept"),"full manifest URL unchanged");
        ok(HuangguoHls.address("https://cdn.example/vod/full%20name.m3u8?sig=old", "?key=a%2Fb%3D").equals("https://cdn.example/vod/full%20name.m3u8?key=a%2Fb%3D"),"query-only raw signature");
        HuangguoHls.Playlist query=HuangguoHls.parse(list("#EXT-X-KEY:METHOD=AES-128,URI=\"?key=abc%2F\"\n#EXTINF:1,\n?segment=1%2F\n"),URL+"?old=1");
        ok(query.segments.get(0).key.equals(URL+"?key=abc%2F"),"query-only key keeps filename");
        ok(query.segments.get(0).url.equals(URL+"?segment=1%2F"),"query-only segment keeps filename");
        HuangguoHls.Playlist p=HuangguoHls.parse(list("#EXT-X-MEDIA-SEQUENCE:258\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key?x=1,2\"\n#EXTINF:2.5,\na.ts\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:3,\nb.ts\n"),URL);
        ok(p.segments.size()==2&&p.seconds==5.5,"duration");ok(p.segments.get(0).key.equals("https://media.example/key?x=1,2"),"relative key");ok(p.segments.get(0).iv[14]==1&&p.segments.get(0).iv[15]==2,"sequence IV");ok(p.segments.get(1).key==null,"NONE clears encryption");
        p=HuangguoHls.parse(list("#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0xA\n#EXTINF:1,\na\n"),URL);ok(p.segments.get(0).iv[15]==10,"short explicit IV padded");
        for(String body:new String[]{"#EXT-X-STREAM-INF:BANDWIDTH=10\na.m3u8\n","#EXT-X-MAP:URI=\"init\"\n#EXTINF:1,\na\n","#EXT-X-BYTERANGE:8@0\n#EXTINF:1,\na\n","#EXT-X-DISCONTINUITY\n#EXTINF:1,\na\n","#EXT-X-GAP\n#EXTINF:1,\na\n","#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:1,\na\n","#EXT-X-KEY:METHOD=AES-128,KEYFORMAT=\"com.widevine\",URI=\"key\"\n#EXTINF:1,\na\n","#EXTINF:NaN,\na\n","#EXTINF:1,\nhttp://media.example/a\n","#EXTINF:1,\nhttps://user:pw@media.example/a\n","#EXTINF:1,\nhttps://media.example:8443/a\n","#EXT-X-KEY:METHOD=NONE,IV=0x1\n#EXTINF:1,\na\n","#EXTINF:1,\n#EXTINF:2,\na\n","#EXTINF:1,\na\n#EXT-X-MEDIA-SEQUENCE:3\n"})reject(()->HuangguoHls.parse(list(body),URL),body.split("\n")[0]);
        reject(()->HuangguoHls.parse("#EXTM3U\n#EXTINF:1,\na\n",URL),"live");reject(()->HuangguoHls.parse(list("#EXTINF:1,\na\n")+"b\n",URL),"after end");
        reject(()->HuangguoHls.parse(repeat("x",HuangguoHls.MAX_PLAYLIST_BYTES+1),URL),"playlist budget");
        String many=repeat("#EXTINF:1,\na\n",HuangguoHls.MAX_SEGMENTS+1);reject(()->HuangguoHls.parse(list(many),URL),"segment budget");
        Path root=Files.createTempDirectory("huangguo-hls-test-");File scratch=Files.createDirectory(root.resolve("work")).toFile(),target=root.resolve("result.part").toFile();
        try{
            byte[] key=new byte[16];Arrays.fill(key,(byte)7);byte[] bytes=ts();
            Fake f=new Fake(list("#EXT-X-MEDIA-SEQUENCE:1\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:1,\na.ts\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:1,\nb.ts\n"));f.data.put("https://media.example/vod/key",key);f.data.put("https://media.example/vod/a.ts",encrypt(bytes,key,HuangguoHls.sequenceIv(1)));f.data.put("https://media.example/vod/b.ts",bytes);
            HuangguoHls.prepare(URL,target,scratch,4096,()->true,deadline(),f,(source,dest,max,secs,alive,end)->{ok(source.length()==bytes.length*2,"AES and NONE concatenated");ok(secs==2,"playlist duration to remuxer");mp4(dest);});ok(target.exists(),"complete MP4 only");ok(f.requests.size()==3,"bounded calls");Files.delete(target.toPath());clean(scratch,target);
            byte[] secondKey=new byte[16];Arrays.fill(secondKey,(byte)9);
            Fake rotation=new Fake(list("#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:1,\na.ts\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:1,\nb.ts\n")) {
                int keys;
                public void key(String url,File file,long end)throws Exception {Files.write(file.toPath(),keys++==0?key:secondKey);requests.add(url);}
            };
            rotation.data.put("https://media.example/vod/a.ts",encrypt(bytes,key,HuangguoHls.sequenceIv(0)));
            rotation.data.put("https://media.example/vod/b.ts",encrypt(bytes,secondKey,HuangguoHls.sequenceIv(1)));
            HuangguoHls.prepare(URL,target,scratch,4096,()->true,deadline(),rotation,(source,dest,max,secs,alive,end)->{ok(source.length()==bytes.length*2,"same-URI key rotation");mp4(dest);});
            ok(Collections.frequency(rotation.requests,"https://media.example/vod/key")==2,"new KEY declaration refreshes key");Files.delete(target.toPath());clean(scratch,target);
            Fake bad=new Fake(list("#EXTINF:1,\na.ts\n"));bad.data.put("https://media.example/vod/a.ts",new byte[]{1,2,3});
            reject(()->HuangguoHls.prepare(URL,target,scratch,4096,()->true,deadline(),bad,(a,b,c,d,e,g)->{throw new AssertionError("bad TS reached muxer");}),"corrupt segment");clean(scratch,target);
            Fake clear=new Fake(list("#EXTINF:1,\na.ts\n"));clear.data.put("https://media.example/vod/a.ts",bytes);
            reject(()->HuangguoHls.prepare(URL,target,scratch,100,()->true,deadline(),clear,(a,b,c,d,e,g)->mp4(b)),"download size");clean(scratch,target);
            AtomicBoolean live=new AtomicBoolean(true);clear.alive=live;
            reject(()->HuangguoHls.prepare(URL,target,scratch,4096,live::get,deadline(),clear,(a,b,c,d,e,g)->mp4(b)),"cancel after transfer");clean(scratch,target);clear.alive=null;
            reject(()->HuangguoHls.prepare(URL,target,scratch,4096,()->true,deadline(),clear,(a,b,c,d,e,g)->{mp4(b);throw new IOException("mux failure");}),"partial mux");clean(scratch,target);
            reject(()->HuangguoHls.prepare(URL,target,scratch,4096,()->true,System.nanoTime()-1,clear,(a,b,c,d,e,g)->mp4(b)),"deadline");clean(scratch,target);
            Fake wrongKey=new Fake(f.text);wrongKey.data.putAll(f.data);wrongKey.data.put("https://media.example/vod/key",new byte[15]);
            reject(()->HuangguoHls.prepare(URL,target,scratch,4096,()->true,deadline(),wrongKey,(a,b,c,d,e,g)->mp4(b)),"key size");clean(scratch,target);
        }finally{try(java.util.stream.Stream<Path> walk=Files.walk(root)){walk.sorted(Comparator.reverseOrder()).forEach(x->{try{Files.delete(x);}catch(IOException e){throw new RuntimeException(e);}});}}
        System.out.println("PASS HuangguoHlsTest checks="+checks+" network=0 playbackAcceptance=not_run");
    }
}
