package io.github.jackotom.hongguo.android;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.*;
final class DlnaSessionFileTest {
    static int checks;static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static byte[] box(String type,byte[] payload)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeInt(8+payload.length);d.writeBytes(type);d.write(payload);return b.toByteArray();}
    static byte[] combine(byte[]...rows)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] row:rows)b.write(row);return b.toByteArray();}
    static File file(Path root,String name,byte[] bytes)throws Exception{Path p=root.resolve(name);Files.write(p,bytes);return p.toFile();}
    static void bad(File file)throws Exception{boolean rejected=false;try{DlnaSessionFile.validate(file);}catch(DlnaSessionFile.NotMp4Exception expected){rejected=true;}check(rejected,"malformed file typed NotMp4 "+file.getName());}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("dlna-session-mp4-");try{
            byte[] ftyp=box("ftyp","isom0000".getBytes("US-ASCII")),moov=box("moov",new byte[8]),mdat=box("mdat",new byte[16]);
            File valid=file(root,"valid.mp4",combine(ftyp,moov,mdat));DlnaSessionFile.validate(valid);check(valid.exists(),"ordinary MP4 valid");
            File progressive=file(root,"late-moov.mp4",combine(ftyp,mdat,moov));DlnaSessionFile.validate(progressive);check(progressive.exists(),"mdat before moov supported");
            File fragmented=file(root,"fragmented.mp4",combine(ftyp,moov,box("moof",new byte[8]),mdat,box("moof",new byte[8]),mdat));DlnaSessionFile.validate(fragmented);check(fragmented.exists(),"fragmented MP4 supported");
            File playlist=file(root,"manual-kept.mp4","#EXTM3U\n#EXTINF:10\nsegment.ts\n".getBytes("UTF-8"));byte[] manual=Files.readAllBytes(playlist.toPath());bad(playlist);check(Arrays.equals(manual,Files.readAllBytes(playlist.toPath())),"manual bad file never deleted or rewritten");
            bad(file(root,"truncated.mp4",Arrays.copyOf(combine(ftyp,moov,mdat),ftyp.length+moov.length+10)));
            bad(file(root,"size-small.mp4",new byte[]{0,0,0,4,'f','t','y','p'}));
            bad(file(root,"large-overflow.mp4",new byte[]{0,0,0,1,'m','d','a','t',(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255}));
            bad(file(root,"missing-moov.mp4",combine(ftyp,mdat)));bad(file(root,"missing-mdat.mp4",combine(ftyp,moov)));bad(file(root,"tail.mp4",combine(ftyp,moov,mdat,new byte[]{1})));
            File large=root.resolve("large.mp4").toFile();try(RandomAccessFile out=new RandomAccessFile(large,"rw")){out.write(ftyp);out.write(moov);out.writeInt(1);out.writeBytes("mdat");out.writeLong(100L*1024*1024);out.setLength(ftyp.length+moov.length+100L*1024*1024);}DlnaSessionFile.validate(large);check(large.length()>96L*1024*1024,"large payload seek-skipped without buffering or imposing cache quota on manual files");
            AtomicInteger app=new AtomicInteger(),invalidations=new AtomicInteger();File result=DlnaSessionFile.prepare(()->playlist,true,()->{app.incrementAndGet();return valid;},invalidations::incrementAndGet,()->true);check(result==valid&&app.get()==1&&invalidations.get()==1,"typed web playlist refreshes App once at same caller quality");
            app.set(0);invalidations.set(0);boolean rejected=false;try{DlnaSessionFile.prepare(()->playlist,false,()->{app.incrementAndGet();return valid;},invalidations::incrementAndGet,()->true);}catch(DlnaSessionFile.NotMp4Exception expected){rejected=true;}check(rejected&&app.get()==0&&invalidations.get()==1,"App malformed file invalidated without more App retries");
            app.set(0);invalidations.set(0);rejected=false;try{DlnaSessionFile.prepare(()->playlist,true,()->{app.incrementAndGet();return playlist;},invalidations::incrementAndGet,()->true);}catch(DlnaSessionFile.NotMp4Exception expected){rejected=true;}check(rejected&&app.get()==1&&invalidations.get()==2,"bad replacement fails after one App attempt and clears owned malformed result");
            app.set(0);invalidations.set(0);boolean tls=false;try{DlnaSessionFile.prepare(()->{throw new javax.net.ssl.SSLHandshakeException("synthetic TLS");},true,()->{app.incrementAndGet();return valid;},invalidations::incrementAndGet,()->true);}catch(javax.net.ssl.SSLHandshakeException expected){tls=true;}check(tls&&app.get()==0&&invalidations.get()==0,"TLS not converted into retryable malformed file");
            boolean cancelled=false;try{DlnaSessionFile.prepare(()->playlist,true,()->{app.incrementAndGet();return valid;},invalidations::incrementAndGet,()->false);}catch(InterruptedIOException expected){cancelled=true;}check(cancelled&&app.get()==0&&invalidations.get()==0,"cancel never invalidates or obtains replacement");
            Thread.currentThread().interrupt();cancelled=false;try{DlnaSessionFile.validate(valid);}catch(InterruptedIOException expected){cancelled=true;}finally{Thread.interrupted();}check(cancelled,"file parser observes cancellation");
            check(Arrays.equals(manual,Files.readAllBytes(playlist.toPath())),"all MP4 verification and replacement boundaries leave manual bytes unchanged");
            System.out.println("DLNA_SESSION_FILE_OK checks="+checks+" parser=actualProduction completeIsoBoxes=true payload=seekSkipped webToApp=oneTypedSameQuality manualFilesPreserved=true networkRequests=0 deviceTests=not_run");
        }finally{try(java.util.stream.Stream<Path> files=Files.walk(root)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}});}}
    }
}
