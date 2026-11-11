package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Finite, bounded MPEG-TS HLS only. Ordinary identity AES-128 is not DRM. */
final class HuangguoHls {
    static final int MAX_PLAYLIST_BYTES = 512 * 1024, MAX_SEGMENTS = 1024;
    static final long MAX_SEGMENT_BYTES = 16L * 1024 * 1024;
    static final double MAX_SECONDS = 2 * 60 * 60;
    static final class Manifest {
        final String text, url;
        Manifest(String text, String url) { this.text=text; this.url=url; }
    }
    interface Transport {
        Manifest playlist(String url, long deadline) throws Exception;
        void download(String url, File target, long maxBytes, long deadline) throws Exception;
        default void key(String url,File target,long deadline) throws Exception { download(url,target,16,deadline); }
    }
    interface Remuxer {
        void remux(File source, File target, long maxBytes, double seconds, BooleanSupplier alive, long deadline) throws Exception;
    }
    static final class Segment {
        final String url, key; final byte[] iv; final int keyVersion;
        Segment(String url, String key, byte[] iv, int keyVersion) { this.url=url; this.key=key; this.iv=iv; this.keyVersion=keyVersion; }
    }
    static final class Playlist {
        final List<Segment> segments; final double seconds;
        Playlist(List<Segment> segments, double seconds) { this.segments=Collections.unmodifiableList(segments); this.seconds=seconds; }
    }
    static void check(BooleanSupplier alive, long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || !alive.getAsBoolean()) throw new InterruptedIOException("黄果投屏准备已取消");
        if (System.nanoTime() >= deadline) throw new java.net.SocketTimeoutException("黄果投屏准备超时，请重试");
    }
    static String address(String base, String relative) throws IOException {
        try {
            URI origin=new URI(base), reference=new URI(relative);
            // Java URI.resolve historically drops the filename for empty/query-only refs.
            // Preserve raw path/query bytes: decoding and re-encoding invalidates signed URLs.
            URI uri;
            if(relative.isEmpty())uri=origin;
            else if(reference.getScheme()==null&&reference.getRawAuthority()==null&&"".equals(reference.getRawPath())&&reference.getRawQuery()!=null) {
                uri=new URI(origin.getScheme()+"://"+origin.getRawAuthority()+origin.getRawPath()+"?"+reference.getRawQuery()+(reference.getRawFragment()==null?"":"#"+reference.getRawFragment()));
            } else uri=origin.resolve(reference);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getFragment()!=null || (uri.getPort()!=-1 && uri.getPort()!=443) || uri.toASCIIString().length()>16384) throw new Exception();
            return uri.toASCIIString();
        } catch (Exception invalid) { throw new IOException("黄果清单地址无效"); }
    }
    static Playlist parse(String text, String base) throws IOException {
        if (text==null || text.getBytes(StandardCharsets.UTF_8).length>MAX_PLAYLIST_BYTES) throw new IOException("黄果清单过大");
        address(base, "");
        String[] lines=text.replace("\r\n", "\n").split("\n", -1);
        if (lines.length==0 || !lines[0].equals("#EXTM3U")) throw new IOException("黄果未返回有效 HLS 清单");
        List<Segment> segments=new ArrayList<>(); String key=null; byte[] explicitIv=null;
        long sequence=0; int keyVersion=0; double duration=-1, seconds=0; boolean end=false, sequenceSeen=false;
        for (int i=1;i<lines.length;i++) {
            String row=lines[i].trim(); if(row.isEmpty())continue;
            if(end)throw new IOException("黄果清单结束后仍有内容");
            if(row.startsWith("#EXT-X-KEY:")) {
                keyVersion++;
                Map<String,String> values=attributes(row.substring(11)); String method=values.get("METHOD");
                if("NONE".equals(method)) { if(values.size()!=1)throw new IOException("黄果加密声明无效"); key=null;explicitIv=null; }
                else {
                    if(!"AES-128".equals(method) || !"identity".equals(values.getOrDefault("KEYFORMAT", "identity")) || !"1".equals(values.getOrDefault("KEYFORMATVERSIONS", "1"))) throw new IOException("该黄果片源使用不支持的加密或 DRM，无法投屏");
                    if(!values.containsKey("URI"))throw new IOException("黄果清单缺少密钥地址");
                    key=address(base,values.get("URI")); explicitIv=values.containsKey("IV")?iv(values.get("IV")):null;
                    for(String name:values.keySet())if(!Arrays.asList("METHOD","URI","IV","KEYFORMAT","KEYFORMATVERSIONS").contains(name))throw new IOException("黄果加密声明不受支持");
                }
            } else if(row.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                if(sequenceSeen||!segments.isEmpty()||duration>=0)throw new IOException("黄果清单序号无效");
                try { sequence=Long.parseLong(row.substring(22)); if(sequence<0)throw new NumberFormatException(); } catch(NumberFormatException invalid){throw new IOException("黄果清单序号无效");} sequenceSeen=true;
            } else if(row.startsWith("#EXTINF:")) {
                if(duration>=0)throw new IOException("黄果清单缺少分片地址");
                try { duration=Double.parseDouble(row.substring(8).split(",",2)[0]); } catch(NumberFormatException invalid){throw new IOException("黄果分片时长无效");}
                if(!Double.isFinite(duration)||duration<=0||duration>600)throw new IOException("黄果分片时长无效");
            } else if(row.equals("#EXT-X-ENDLIST")) {
                if(duration>=0||segments.isEmpty())throw new IOException("黄果清单不完整"); end=true;
            } else if(row.startsWith("#")) {
                // Reject unknown EXT tags rather than silently missing a gap, map, range or DRM rule.
                if(row.startsWith("#EXT") && !row.matches("#EXT-X-(?:VERSION:[0-9]+|TARGETDURATION:[0-9]+|PLAYLIST-TYPE:VOD|INDEPENDENT-SEGMENTS|ALLOW-CACHE:(?:YES|NO)|PROGRAM-DATE-TIME:.+)")) throw new IOException("该黄果 HLS 清单格式暂不支持投屏（需完整连续 TS 分片）");
            } else {
                if(duration<0||segments.size()>=MAX_SEGMENTS||sequence==Long.MAX_VALUE)throw new IOException("黄果清单分片数量或顺序无效");
                byte[] iv=explicitIv==null?sequenceIv(sequence):explicitIv.clone();
                segments.add(new Segment(address(base,row),key,iv,keyVersion));sequence++;seconds+=duration;duration=-1;
                if(seconds>MAX_SECONDS)throw new IOException("黄果投屏单集时长超出限制");
            }
        }
        if(!end)throw new IOException("黄果投屏仅支持已结束的完整点播清单");
        return new Playlist(segments,seconds);
    }
    private static Map<String,String> attributes(String input) throws IOException {
        Map<String,String> result=new LinkedHashMap<>(); int offset=0;
        while(offset<input.length()) {
            int equal=input.indexOf('=',offset);if(equal<0)throw new IOException("黄果加密声明无效");
            String name=input.substring(offset,equal);if(!name.matches("[A-Z0-9-]+"))throw new IOException("黄果加密声明无效");
            offset=equal+1;String value;
            if(offset<input.length()&&input.charAt(offset)=='"') { int close=input.indexOf('"',++offset);if(close<0)throw new IOException("黄果加密声明无效");value=input.substring(offset,close);offset=close+1; }
            else { int comma=input.indexOf(',',offset);int last=comma<0?input.length():comma;value=input.substring(offset,last);offset=last; }
            if(value.isEmpty()||result.put(name,value)!=null)throw new IOException("黄果加密声明无效");
            if(offset<input.length()) { if(input.charAt(offset++)!=','||offset==input.length())throw new IOException("黄果加密声明无效"); }
        }
        return result;
    }
    private static byte[] iv(String value) throws IOException {
        if(!value.matches("0[xX][0-9a-fA-F]{1,32}"))throw new IOException("黄果 AES IV 无效");
        String hex=value.substring(2);while(hex.length()<32)hex="0"+hex;byte[] result=new byte[16];
        for(int i=0;i<16;i++)result[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);return result;
    }
    static byte[] sequenceIv(long sequence) { byte[] iv=new byte[16];for(int i=15;i>=8;i--){iv[i]=(byte)sequence;sequence>>>=8;}return iv; }
    /** scratch is private and exclusive to this job; final publication belongs to PlaybackCachePolicy. */
    static void prepare(String url, File target, File scratch, long maxBytes, BooleanSupplier alive, long deadline, Transport transport, Remuxer remuxer) throws Exception {
        File input=new File(scratch,"source.ts"),part=new File(scratch,"segment.part"),keyFile=new File(scratch,"key.part");
        byte[] keyBytes=null;int keyVersion=-1;boolean complete=false;
        try {
            check(alive,deadline);Manifest manifest=transport.playlist(address(url,""),deadline);check(alive,deadline);
            Playlist playlist=parse(manifest.text,manifest.url);
            long downloaded=0, written=0;
            try(OutputStream output=new BufferedOutputStream(new FileOutputStream(input))) {
                for(Segment segment:playlist.segments) {
                    check(alive,deadline);
                    if(segment.key==null&&keyBytes!=null){Arrays.fill(keyBytes,(byte)0);keyBytes=null;keyVersion=-1;}
                    if(segment.key!=null&&segment.keyVersion!=keyVersion) {
                        if(keyBytes!=null)Arrays.fill(keyBytes,(byte)0);
                        keyBytes=null;keyVersion=-1;
                        transport.key(segment.key,keyFile,deadline);check(alive,deadline);
                        if(keyFile.length()!=16)throw new IOException("黄果 AES 密钥长度无效");
                        keyBytes=Files.readAllBytes(keyFile.toPath());remove(keyFile);keyVersion=segment.keyVersion;
                    }
                    long remaining=maxBytes-downloaded;
                    if(remaining<=0)throw new IOException("黄果投屏缓存超出单集大小限制");
                    transport.download(segment.url,part,Math.min(MAX_SEGMENT_BYTES,remaining),deadline);check(alive,deadline);
                    long length=part.length();if(length<=0||length>MAX_SEGMENT_BYTES||length>remaining)throw new IOException("黄果分片大小无效");downloaded+=length;
                    Cipher cipher=null;
                    if(segment.key!=null) { cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(keyBytes,"AES"),new IvParameterSpec(segment.iv)); }
                    // One bounded segment in memory simplifies validating TS sync before any bytes are appended.
                    byte[] bytes=Files.readAllBytes(part.toPath());check(alive,deadline);
                    try {
                        if(cipher!=null)bytes=cipher.doFinal(bytes);
                        if(bytes.length==0||bytes.length%188!=0)throw new IOException("黄果分片不是完整 MPEG-TS");
                        for(int p=0;p<bytes.length;p+=188)if(bytes[p]!=0x47)throw new IOException("黄果 TS 分片损坏或格式不受支持");
                        if(bytes.length>maxBytes-written)throw new IOException("黄果投屏缓存超出单集大小限制");
                        output.write(bytes);written+=bytes.length;
                    } finally { Arrays.fill(bytes,(byte)0); }
                    remove(part);check(alive,deadline);
                }
            }
            check(alive,deadline);remuxer.remux(input,target,maxBytes,playlist.seconds,alive,deadline);check(alive,deadline);
            DlnaSessionFile.validate(target);if(target.length()>maxBytes)throw new IOException("黄果投屏 MP4 超出单集大小限制");complete=true;
        } finally {
            if(keyBytes!=null)Arrays.fill(keyBytes,(byte)0);
            cleanup(complete?new File[]{keyFile,part,input}:new File[]{keyFile,part,input,target});
        }
    }
    private static void cleanup(File[] files) throws IOException {
        IOException failure=null;
        for(File file:files)try{remove(file);}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure!=null)throw failure;
    }
    static void remove(File file) throws IOException { if(file.exists()&&!file.delete())throw new IOException("黄果投屏临时文件清理失败"); }
}
