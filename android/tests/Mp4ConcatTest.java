package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Synthetic multi-track MP4s merged by the production concat, then verified
 * sample-by-sample against the inputs with an independent table reader.
 */
public final class Mp4ConcatTest {
    private static int checks;

    /** Minimal valid MP4 writer: one vide and one soun track over explicit chunks. */
    private static final class Builder {
        private final int timescale, duration, mdiaTimescale, mdiaDuration;
        private final long[] videoSizes, audioSizes; private final boolean[] videoSync;
        private final int videoChunks, audioChunks; private final byte[] stsdOverride; private final boolean editList;
        Builder(int timescale,int duration,int mdiaTimescale,int mdiaDuration,long[] videoSizes,long[] audioSizes,boolean[] videoSync,int videoChunks,int audioChunks,byte[] stsdOverride,boolean editList) {
            this.timescale=timescale;this.duration=duration;this.mdiaTimescale=mdiaTimescale;this.mdiaDuration=mdiaDuration;
            this.videoSizes=videoSizes;this.audioSizes=audioSizes;this.videoSync=videoSync;this.videoChunks=videoChunks;this.audioChunks=audioChunks;this.stsdOverride=stsdOverride;this.editList=editList;
        }
        private static byte[] videoStsd() throws IOException {
            // Fixed decoder configuration: concat must see byte-identical stsd across inputs.
            byte[] entry=new byte[40]; put4(entry,0,entry.length);
            System.arraycopy("avc1".getBytes(StandardCharsets.US_ASCII),0,entry,4,4);
            entry[36]=0x33; entry[37]=0x77;
            return box("stsd",payload(new byte[8],entry));
        }
        private static byte[] audioStsd() throws IOException {
            byte[] entry=new byte[36]; put4(entry,0,entry.length);
            System.arraycopy("mp4a".getBytes(StandardCharsets.US_ASCII),0,entry,4,4);
            entry[32]=0x44; entry[33]=(byte)0xac;
            return box("stsd",payload(new byte[8],entry));
        }
        byte[] build(int seed) throws IOException {
            List<List<Integer>> vChunks=split(videoSizes.length,videoChunks), aChunks=split(audioSizes.length,audioChunks);
            byte filler=(byte)('A'+seed%20);
            byte[] ftyp=box("ftyp",payload("isom".getBytes(StandardCharsets.US_ASCII),"mp41".getBytes(StandardCharsets.US_ASCII)));
            // Pass 1 with base 0 learns the moov length; pass 2 writes real chunk offsets.
            byte[] moov=moov(0,vChunks,aChunks,filler,null);
            long dataBase=ftyp.length+moov.length+8; // mdat payload starts after its 8-byte header
            ByteArrayOutputStream data=new ByteArrayOutputStream();
            moov=moov(dataBase,vChunks,aChunks,filler,data);
            ByteArrayOutputStream file=new ByteArrayOutputStream(); file.write(ftyp); file.write(moov); file.write(box("mdat",data.toByteArray()));
            return file.toByteArray();
        }
        private byte[] moov(long base,List<List<Integer>> vChunks,List<List<Integer>> aChunks,byte filler,ByteArrayOutputStream data) throws IOException {
            List<Long> vOffsets=offsets(base,vChunks,videoSizes,data,filler,true);
            long audioStart=totalSize(videoSizes);
            List<Long> aOffsets=offsets(base+audioStart,aChunks,audioSizes,data,filler,false);
            return box("moov",payload(box("mvhd",mvhd()),
                box("trak",trak("vide",mdiaDuration,stsdOverride!=null?box("stsd",stsdOverride):videoStsd(),vChunks,vOffsets,videoSizes,videoSync)),
                box("trak",trak("soun",mdiaDuration/2,audioStsd(),aChunks,aOffsets,audioSizes,null))));
        }
        private List<Long> offsets(long base,List<List<Integer>> chunks,long[] sizes,ByteArrayOutputStream data,byte filler,boolean video) throws IOException {
            List<Long> result=new ArrayList<>(); long cursor=0;
            for (List<Integer> chunk: chunks) {
                result.add(base+cursor);
                for (int sample: chunk) { long size=sizes[sample]; cursor+=size; if (data==null) continue; for (int i=0;i<size;i++) data.write(video?((filler+i)&0xff):(i<4?0x11:(filler&0xff))); }
            }
            return result;
        }
        private static long totalSize(long[] sizes) { long total=0; for (long size: sizes) total+=size; return total; }
        private byte[] mvhd() {
            byte[] body=new byte[100];
            put4(body,12,timescale); put4(body,16,duration); put4(body,20,0x00010000);
            return body;
        }
        private byte[] trak(String handler,int mdhdDuration,byte[] stsd,List<List<Integer>> chunks,List<Long> offsets,long[] sizes,boolean[] sync) throws IOException {
            byte[] tkhd=new byte[92]; put4(tkhd,4,1); put4(tkhd,20,duration); put4(tkhd,76,0x01400000); put4(tkhd,80,0x01400000);
            byte[] mdhd=new byte[28]; put4(mdhd,12,mdiaTimescale); put4(mdhd,16,mdhdDuration); put4(mdhd,20,0x55c4);
            byte[] hdlr=box("hdlr",payload(new byte[8],handler.getBytes(StandardCharsets.US_ASCII),new byte[12]));
            ByteArrayOutputStream stbl=new ByteArrayOutputStream(); stbl.write(stsd);
            stbl.write(box("stts",sttsBody(sizes))); stbl.write(box("stsz",stszBody(sizes)));
            stbl.write(box("stsc",stscBody(chunks))); stbl.write(box("stco",stcoBody(offsets)));
            if (sync!=null) stbl.write(box("stss",stssBody(sync)));
            byte[] minf=box("minf",payload(box(handler.equals("vide")?"vmhd":"smhd",new byte[12]),box("stbl",stbl.toByteArray())));
            ByteArrayOutputStream mdia=new ByteArrayOutputStream(); mdia.write(box("mdhd",mdhd)); mdia.write(hdlr); mdia.write(minf);
            ByteArrayOutputStream trak=new ByteArrayOutputStream(); trak.write(box("tkhd",tkhd));
            if (editList) trak.write(box("edts",box("elst",elstBody())));
            trak.write(box("mdia",mdia.toByteArray()));
            return trak.toByteArray();
        }
        /** A real edit list: one segment covering the movie duration, starting at media time 40. */
        private byte[] elstBody() throws IOException {
            byte[] entry=new byte[12];
            put4(entry,0,duration); put4(entry,4,40); // segment duration, media time
            entry[8]=0; entry[9]=1; // media_rate_integer 1
            ByteArrayOutputStream count=new ByteArrayOutputStream(); put4(count,1); // one entry
            return payload(new byte[4],count.toByteArray(),entry);
        }
        private byte[] sttsBody(long[] sizes) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); put4(out,0); put4(out,sizes.length);
            for (int i=0;i<sizes.length;i++) { put4(out,1); put4(out,40); }
            return out.toByteArray();
        }
        private byte[] stszBody(long[] sizes) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); put4(out,0); put4(out,0); put4(out,sizes.length);
            for (long size: sizes) put4(out,size);
            return out.toByteArray();
        }
        private byte[] stscBody(List<List<Integer>> chunks) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); put4(out,0); put4(out,chunks.size());
            for (int i=0;i<chunks.size();i++) { put4(out,i+1); put4(out,chunks.get(i).size()); put4(out,1); }
            return out.toByteArray();
        }
        private byte[] stcoBody(List<Long> offsets) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); put4(out,0); put4(out,offsets.size());
            for (long offset: offsets) put4(out,offset);
            return out.toByteArray();
        }
        private byte[] stssBody(boolean[] sync) {
            int count=0; for (boolean value: sync) if (value) count++;
            ByteArrayOutputStream out=new ByteArrayOutputStream(); put4(out,0); put4(out,count);
            for (int i=0;i<sync.length;i++) if (sync[i]) put4(out,i+1);
            return out.toByteArray();
        }
        private static List<List<Integer>> split(int samples,int chunks) {
            List<List<Integer>> result=new ArrayList<>();
            for (int c=0;c<chunks;c++) result.add(new ArrayList<>());
            for (int i=0;i<samples;i++) result.get(Math.min(chunks-1,i*chunks/Math.max(1,samples))).add(i);
            for (List<Integer> chunk: result) if (chunk.isEmpty()) chunk.add(0);
            return result;
        }
    }
    private static byte[] box(String type,byte[] payload) {
        byte[] out=new byte[8+payload.length]; put4(out,0,out.length);
        System.arraycopy(type.getBytes(StandardCharsets.US_ASCII),0,out,4,4);
        System.arraycopy(payload,0,out,8,payload.length); return out;
    }
    private static byte[] payload(byte[]... parts) throws IOException { ByteArrayOutputStream out=new ByteArrayOutputStream(); for (byte[] part: parts) out.write(part); return out.toByteArray(); }
    private static void put4(ByteArrayOutputStream out,long value) { out.write((int)(value>>>24)&0xff); out.write((int)(value>>>16)&0xff); out.write((int)(value>>>8)&0xff); out.write((int)value&0xff); }
    private static void put4(byte[] target,int offset,long value) { target[offset]=(byte)(value>>>24); target[offset+1]=(byte)(value>>>16); target[offset+2]=(byte)(value>>>8); target[offset+3]=(byte)value; }

    /** Independent minimal MP4 reader used only by this test to verify the merge. */
    private static final class Reader {
        final byte[] data; final byte[] moov; final List<byte[]> traks=new ArrayList<>();
        Reader(File file) throws IOException {
            data=Files.readAllBytes(file.toPath());
            byte[] moovFound=null; long offset=0;
            while (offset+8<=data.length) {
                long size=u32(data,(int)offset); if (size<8||offset+size>data.length) throw new IOException("bad box"); int boxEnd=(int)(offset+size);
                String type=new String(data,(int)offset+4,4,StandardCharsets.US_ASCII);
                if (type.equals("moov")) moovFound=Arrays.copyOfRange(data,(int)offset+8,boxEnd);
                offset+=size;
            }
            if (moovFound==null||offset!=data.length) throw new IOException("moov missing or trailing bytes");
            moov=moovFound;
            long p=0;
            while (p<moov.length) { long size=u32(moov,(int)p); String type=new String(moov,(int)p+4,4,StandardCharsets.US_ASCII); if (size<8||p+size>moov.length) throw new IOException("bad moov child"); if (type.equals("trak")) traks.add(Arrays.copyOfRange(moov,(int)p+8,(int)(p+size))); p+=size; }
            if (traks.size()!=2) throw new IOException("expected two tracks");
        }
        private static long u32(byte[] source,int at){ return ((source[at]&0xffL)<<24)|((source[at+1]&0xffL)<<16)|((source[at+2]&0xffL)<<8)|(source[at+3]&0xffL); }
        private byte[] table(int track,String name) throws IOException {
            byte[] trak=traks.get(track);
            byte[] mdia=child(trak,"mdia"), minf=child(mdia,"minf"), stbl=child(minf,"stbl");
            return child(stbl,name);
        }
        private byte[] child(byte[] parent,String want) throws IOException {
            long p=0;
            while (p<parent.length) { long size=u32(parent,(int)p); if (size<8||p+size>parent.length) throw new IOException("bad child"); String type=new String(parent,(int)p+4,4,StandardCharsets.US_ASCII); if (type.equals(want)) return Arrays.copyOfRange(parent,(int)p+8,(int)(p+size)); p+=size; }
            throw new IOException("missing "+want);
        }
        /** Ordered (offset,size) of every sample, expanded from stco+stsc+stsz. */
        List<long[]> samples(int track) throws IOException {
            byte[] stco=table(track,"stco"), stsc=table(track,"stsc"), stsz=table(track,"stsz");
            long fixed=u32(stsz,4), count=u32(stsz,8);
            long[] sizes=new long[(int)count];
            for (int i=0;i<count;i++) sizes[i]=fixed>0?fixed:u32(stsz,12+i*4);
            long chunkCount=u32(stco,4);
            long[] perChunk=new long[(int)chunkCount];
            long runs=u32(stsc,4); int run=0;
            for (int c=0;c<chunkCount;c++) { while (run+1<runs&&u32(stsc,8+(run+1)*12)<=c+1) run++; perChunk[c]=u32(stsc,8+run*12+4); }
            List<long[]> out=new ArrayList<>();
            long sample=0;
            for (int c=0;c<chunkCount;c++) {
                long base=u32(stco,8+c*4);
                for (int s=0;s<perChunk[c]&&sample<count;s++) { out.add(new long[]{base,sizes[(int)sample]}); long size=sizes[(int)sample]; base+=size; sample++; }
            }
            if (sample!=count) throw new IOException("stsc covers "+sample+" of "+count);
            return out;
        }
        long[] stss(int track) throws IOException {
            byte[] trak=traks.get(track); byte[] stbl=child(child(child(trak,"mdia"),"minf"),"stbl");
            long p=0;
            while (p<stbl.length) { long size=u32(stbl,(int)p); String type=new String(stbl,(int)p+4,4,StandardCharsets.US_ASCII); if (size<8||p+size>stbl.length) throw new IOException("bad stbl child"); if (type.equals("stss")) { byte[] body=Arrays.copyOfRange(stbl,(int)p+8,(int)(p+size)); long count=u32(body,4); long[] out=new long[(int)count]; for (int i=0;i<count;i++) out[i]=u32(body,8+i*4); return out; } p+=size; }
            return null;
        }
        /** The media times of a track's merged edit list, in order. */
        long[] elst(int track) throws IOException {
            byte[] trak=traks.get(track);
            byte[] edts=child(trak,"edts"); byte[] elst=child(edts,"elst");
            long count=u32(elst,4); long[] out=new long[(int)count];
            for (int i=0;i<count;i++) out[i]=u32(elst,8+i*12+4);
            return out;
        }
    }

    private static void check(boolean value,String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void reject(Throwing action,String label) { checks++; try { action.run(); } catch (Exception expected) { return; } throw new AssertionError("accepted: "+label); }
    private interface Throwing { void run() throws Exception; }

    public static void main(String[] arguments) throws Exception {
        File directory=Files.createTempDirectory("hongguo-mp4-concat.").toFile();
        try {
            long[] videoSizes={12,9,15,7,20,11}, audioSizes={8,8,8,8,8,8,8,8};
            boolean[] videoSync={true,false,false,true,false,false};
            Builder first=new Builder(1000,240,600,600,videoSizes,audioSizes,videoSync,2,2,null,false);
            Builder second=new Builder(1000,200,600,500,videoSizes,audioSizes,videoSync,2,2,null,false);
            Builder third=new Builder(1000,160,600,400,videoSizes,audioSizes,videoSync,2,2,null,false);
            File a=write(directory,"a.mp4",first.build(0)), b=write(directory,"b.mp4",second.build(1)), c=write(directory,"c.mp4",third.build(2));
            for (File file: new File[]{a,b,c}) DlnaSessionFile.validate(file);

            File merged=new File(directory,"merged.mp4");
            List<Mp4Concat.Timing> timings=Mp4Concat.concat(Arrays.asList(a,b,c),merged);
            check(timings.size()==3,"three timings");
            check(timings.get(0).durationMs==1000&&timings.get(1).durationMs==833&&timings.get(2).durationMs==667,
                "mdhd durations mapped to ms: "+timings.get(0).durationMs+","+timings.get(1).durationMs+","+timings.get(2).durationMs);
            DlnaSessionFile.validate(merged);

            Reader reader=new Reader(merged);
            check(reader.samples(0).size()==18,"video samples sum: "+reader.samples(0).size());
            check(reader.samples(1).size()==24,"audio samples sum: "+reader.samples(1).size());
            verifySampleBytes(reader,0,Arrays.asList(a,b,c));
            verifySampleBytes(reader,1,Arrays.asList(a,b,c));
            long[] sync=reader.stss(0);
            check(sync!=null&&sync.length==6,"sync count preserved");
            check(sync[0]==1&&sync[1]==4&&sync[2]==7&&sync[3]==10&&sync[4]==13&&sync[5]==16,"sync indices shifted: "+Arrays.toString(sync));
            check(reader.stss(1)==null,"audio stays without stss");

            // Incompatible inputs are rejected, never silently degraded.
            byte[] otherStsd=new byte[40]; put4(otherStsd,0,otherStsd.length);
            System.arraycopy("avc1".getBytes(StandardCharsets.US_ASCII),0,otherStsd,4,4);
            otherStsd[36]=0x11; // different level bytes
            File stranger=write(directory,"stranger.mp4",new Builder(1000,100,600,250,videoSizes,audioSizes,videoSync,2,2,otherStsd,false).build(9));
            File incompatible=new File(directory,"incompatible.mp4");
            reject(()->Mp4Concat.concat(Arrays.asList(a,stranger),incompatible),"different stsd refused");
            File otherScale=write(directory,"scale.mp4",new Builder(900,100,600,250,videoSizes,audioSizes,videoSync,2,2,null,false).build(4));
            reject(()->Mp4Concat.concat(Arrays.asList(a,otherScale),incompatible),"timescale mismatch refused");
            check(!incompatible.exists(),"no partial output on refusal");

            // Edit lists are chained: each episode keeps its own priming trim.
            Builder editedBuilder=new Builder(1000,240,600,600,videoSizes,audioSizes,videoSync,2,2,null,true);
            File editedA=write(directory,"edited-a.mp4",editedBuilder.build(11));
            File editedB=write(directory,"edited-b.mp4",editedBuilder.build(12));
            File editedMerged=new File(directory,"edited-merged.mp4");
            Mp4Concat.concat(Arrays.asList(editedA,editedB),editedMerged);
            DlnaSessionFile.validate(editedMerged);
            Reader editedReader=new Reader(editedMerged);
            check(editedReader.elst(0).length==2,"both edit lists survive the merge");
            check(editedReader.elst(0)[0]==40,"the first edit keeps its media time");
            check(editedReader.elst(0)[1]==40+240,"the second edit is shifted by the first episode media");
            check(editedReader.elst(1).length==2,"the audio track chains its edit list too");
            check(editedReader.elst(1)[1]==40+320,"the audio shift uses the audio media span");
            // An episode with an edit list cannot be mixed with one without.
            File plain=write(directory,"plain.mp4",new Builder(1000,240,600,600,videoSizes,audioSizes,videoSync,2,2,null,false).build(13));
            reject(()->Mp4Concat.concat(Arrays.asList(editedA,plain),incompatible),"edit list mismatch refused");
            System.out.println("MP4_CONCAT_OK checks="+checks+" mergedBytes="+merged.length());
        } finally { delete(directory); }
    }
    private static void verifySampleBytes(Reader merged,int track,List<File> inputs) throws IOException {
        List<long[]> expected=new ArrayList<>();
        List<byte[]> sources=new ArrayList<>();
        List<Integer> owner=new ArrayList<>();
        for (int f=0;f<inputs.size();f++) { Reader input=new Reader(inputs.get(f)); for (long[] sample: input.samples(track)) { expected.add(sample); owner.add(f); } sources.add(input.data); }
        List<long[]> actual=merged.samples(track);
        check(actual.size()==expected.size(),"sample count matches for track "+track);
        for (int i=0;i<expected.size();i++) {
            long[] want=expected.get(i), got=actual.get(i);
            check(want[1]==got[1],"sample "+i+" size on track "+track);
            byte[] source=sources.get(owner.get(i));
            for (int b=0;b<want[1];b++) check(source[(int)(want[0]+b)]==merged.data[(int)(got[0]+b)],"sample "+i+" byte "+b+" differs on track "+track);
        }
    }

    private static File write(File directory,String name,byte[] bytes) throws IOException { File file=new File(directory,name); try (FileOutputStream out=new FileOutputStream(file)) { out.write(bytes); } return file; }
    private static void delete(File file) { File[] children=file.listFiles(); if (children!=null) for (File child: children) delete(child); file.delete(); }
}
