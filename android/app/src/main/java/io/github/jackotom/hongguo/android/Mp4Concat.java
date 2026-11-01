package io.github.jackotom.hongguo.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

    /**
     * Sample-level MP4 concatenation for casting windows. This is a remux, never a
     * transcode: files merge only when every input declares byte-identical sample
     * descriptions per track and identical timescales, mirroring the desktop merge's
     * copy-vs-encode gate. Anything incompatible is rejected, not silently degraded.
     *
     * Edit lists are chained rather than dropped: an encoder's priming trim (the usual
     * {@code media_time=1024}) must stay in front of every episode, otherwise the first
     * frames of each input would leak into the merged presentation.
     */
public final class Mp4Concat {
    private Mp4Concat() {}

    /** Per-input timing used to map a merged-file position back to its real episode. */
    public static final class Timing { public final long durationMs; Timing(long ms){durationMs=ms;} }

    public static final String INCOMPATIBLE_PREFIX = "分集编码参数不一致，无法无损合并：";

    /** Top-level box walk shared with the bounded ISO structure check. */
    private static final class Box {
        final String type; final long start, header, end; final byte[] body;
        Box(String type, long start, long header, long end, byte[] body){this.type=type;this.start=start;this.header=header;this.end=end;this.body=body;}
    }
    private static Box child(byte[] parent, long from, long to, String want) throws IOException {
        for (Box box: children(parent, from, to)) if (box.type.equals(want)) return box;
        return null;
    }
    private static List<Box> children(byte[] parent, long from, long to) throws IOException {
        List<Box> result=new ArrayList<>();
        long offset=from;
        while (offset+8<=to) {
            long size=u32(parent,offset); long header=8;
            if (size==1) { if (offset+16>to) throw new IOException("MP4 结构不完整"); size=u64(parent,offset+8); header=16; }
            else if (size==0) size=to-offset;
            if (size<header||offset+size>to) throw new IOException("MP4 结构不完整");
            byte[] typeBytes=Arrays.copyOfRange(parent,(int)offset+4,(int)offset+8);
            String type=new String(typeBytes,StandardCharsets.US_ASCII);
            byte[] body=size-header>0?Arrays.copyOfRange(parent,(int)(offset+header),(int)(offset+size)):new byte[0];
            result.add(new Box(type,offset,header,offset+size,body));
            offset+=size;
        }
        if (offset!=to) throw new IOException("MP4 结构不完整");
        return result;
    }
    private static long u32(byte[] data,long offset){ return ((data[(int)offset]&0xffL)<<24)|((data[(int)offset+1]&0xffL)<<16)|((data[(int)offset+2]&0xffL)<<8)|(data[(int)offset+3]&0xffL); }
    private static long u64(byte[] data,long offset){ return (u32(data,offset)<<32)|u32(data,offset+4); }
    private static int u16(byte[] data,long offset){ return ((data[(int)offset]&0xff)<<8)|(data[(int)offset+1]&0xff); }
    private static int fullBoxVersion(byte[] body){ return body[0]&0xff; }

    /**
     * Reads a track's edit list. The list maps the presentation timeline onto the media
     * timeline: every entry names a segment duration in movie timescale and the media time
     * it starts at, where -1 marks an empty (gap) segment. Encoder priming offsets such as
     * AAC's leading 1024 samples live here, so the list must survive a merge instead of
     * being dropped.
     */
    private static void readEdits(Box edts,Track track) throws IOException {
        Box elst=child(edts.body,0,edts.body.length,"elst");
        if (elst==null) throw new IOException("编辑列表缺少 elst");
        byte[] body=elst.body;
        if (body.length<8) throw new IOException("编辑列表无效");
        int version=fullBoxVersion(body);
        if (version!=0&&version!=1) throw new IOException("编辑列表版本不支持");
        long count=u32(body,4);
        if (count>16) throw new IOException("编辑列表条目过多");
        int size=version==1?20:12;
        if (body.length<8+count*size) throw new IOException("编辑列表无效");
        long[] durations=new long[(int)count],mediaTimes=new long[(int)count];
        for (int i=0;i<count;i++) {
            int at=8+i*size;
            long duration=version==1?u64(body,at):u32(body,at);
            long mediaTime=version==1?u64(body,at+8):(long)(int)u32(body,at+4);
            int rateInteger=(short)u16(body,at+(version==1?16:8));
            int rateFraction=(short)u16(body,at+(version==1?18:10));
            if (rateInteger!=1||rateFraction!=0) throw new IOException("编辑列表变速，不支持合并");
            durations[i]=duration; mediaTimes[i]=mediaTime;
        }
        if (count==0) return; // An empty list edits nothing.
        track.editDurations=durations; track.editMediaTimes=mediaTimes; track.hasEdits=true;
    }

    /** Parsed sample tables of one input file. */
    private static final class Track {
        String handler; byte[] stsd; long mdhdTimescale, mdhdDuration, tkhdDuration;
        List<long[]> stts=new ArrayList<>(); List<long[]> ctts=null; boolean hasCtts;
        long[] stscFirstChunk, stscSamples, stscDescription; long[] stszSizes; long stszFixed; int cttsVersion;
        long[] chunkOffsets; boolean co64; long[] stss; boolean hasStss;
        long decodeDuration; long[] editDurations, editMediaTimes; boolean hasEdits;
        long sampleCount() { return stszFixed>0?stszFixed:stszSizes.length; }
        /** Presentation length of this track in movie timescale. */
        long presentationDuration() {
            if (!hasEdits) return tkhdDuration;
            long total=0; for (long duration: editDurations) total+=duration;
            return total;
        }
    }
    private static final class Input {
        File file; byte[] ftyp, moov; long mvhdTimescale, mvhdDuration; final List<Track> tracks=new ArrayList<>();
        long dataStart=Long.MAX_VALUE, dataEnd=Long.MIN_VALUE;
    }
    /** A track's merged edit list plus the presentation length it covers, in movie timescale. */
    private static final class MergedEdits {
        final byte[] elst; final long duration;
        MergedEdits(byte[] elst,long duration){this.elst=elst;this.duration=duration;}
    }

    private static Input parse(File file) throws IOException {
        byte[] whole=readAll(file);
        Input input=new Input(); input.file=file;
        long mdatCount=0;
        for (Box box: children(whole,0,whole.length)) {
            if (box.type.equals("ftyp")) { if (input.ftyp!=null) throw new IOException("多个 ftyp"); input.ftyp=Arrays.copyOfRange(whole,(int)box.start,(int)box.end); }
            else if (box.type.equals("moov")) { if (input.moov!=null) throw new IOException("多个 moov"); input.moov=Arrays.copyOfRange(whole,(int)box.start,(int)box.end); }
            else if (box.type.equals("mdat")) { mdatCount++; input.dataStart=Math.min(input.dataStart,box.start+box.header); input.dataEnd=Math.max(input.dataEnd,box.end); }
        }
        if (input.ftyp==null||input.moov==null||mdatCount==0||input.dataStart>=input.dataEnd) throw new IOException("缺少 ftyp/moov/mdat");
        Box mvhd=child(input.moov,8,input.moov.length,"mvhd");
        if (mvhd==null) throw new IOException("缺少 mvhd");
        int version=fullBoxVersion(mvhd.body);
        if (version==0) { input.mvhdTimescale=u32(mvhd.body,12); input.mvhdDuration=u32(mvhd.body,16); }
        else if (version==1) { input.mvhdTimescale=u32(mvhd.body,20); input.mvhdDuration=u64(mvhd.body,24); }
        else throw new IOException("mvhd 版本不支持");
        for (Box item: children(input.moov,8,input.moov.length)) {
            if (!item.type.equals("trak")) continue;
            Track track=new Track();
            Box tkhd=child(item.body,0,item.body.length,"tkhd");
            if (tkhd==null) throw new IOException("缺少 tkhd");
            int tkVersion=fullBoxVersion(tkhd.body);
            track.tkhdDuration=tkVersion==0?u32(tkhd.body,20):u64(tkhd.body,28);
            Box mdia=child(item.body,0,item.body.length,"mdia");
            if (mdia==null) throw new IOException("缺少 mdia");
            Box edts=child(item.body,0,item.body.length,"edts");
            if (edts!=null) readEdits(edts,track);
            Box mdhd=child(mdia.body,0,mdia.body.length,"mdhd");
            if (mdhd==null) throw new IOException("缺少 mdhd");
            int mdVersion=fullBoxVersion(mdhd.body);
            if (mdVersion==0) { track.mdhdTimescale=u32(mdhd.body,12); track.mdhdDuration=u32(mdhd.body,16); }
            else if (mdVersion==1) { track.mdhdTimescale=u32(mdhd.body,20); track.mdhdDuration=u64(mdhd.body,24); }
            else throw new IOException("mdhd 版本不支持");
            Box hdlr=child(mdia.body,0,mdia.body.length,"hdlr");
            if (hdlr==null||hdlr.body.length<12) throw new IOException("缺少 hdlr");
            track.handler=new String(Arrays.copyOfRange(hdlr.body,8,12),StandardCharsets.US_ASCII);
            Box minf=child(mdia.body,0,mdia.body.length,"minf");
            if (minf==null) throw new IOException("缺少 minf");
            Box stbl=child(minf.body,0,minf.body.length,"stbl");
            if (stbl==null) throw new IOException("缺少 stbl");
            parseSampleTables(stbl,track,input);
            input.tracks.add(track);
        }
        if (input.tracks.isEmpty()) throw new IOException("没有轨道");
        return input;
    }
    private static void parseSampleTables(Box stbl,Track track,Input input) throws IOException {
        List<Box> tables=children(stbl.body,0,stbl.body.length);
        long declared=0;
        for (Box table: tables) {
            byte[] b=table.body;
            switch (table.type) {
                case "stsd": {
                    if (b.length<8) throw new IOException("stsd 无效");
                    track.stsd=b; break;
                }
                case "stts": {
                    long count=u32(b,4);
                    for (long i=0;i<count;i++) { long samples=u32(b,8+i*8),delta=u32(b,12+i*8); track.stts.add(new long[]{samples,delta}); track.decodeDuration+=samples*delta; }
                    declared+=track.stts.stream().mapToLong(e->e[0]).sum();
                    break;
                }
                case "ctts": {
                    track.hasCtts=true; track.cttsVersion=fullBoxVersion(b);
                    if (track.ctts==null) track.ctts=new ArrayList<>();
                    long count=u32(b,4);
                    for (long i=0;i<count;i++) track.ctts.add(new long[]{u32(b,8+i*8),u32(b,12+i*8)});
                    break;
                }
                case "stsc": {
                    long count=u32(b,4);
                    int n=(int)count;
                    track.stscFirstChunk=new long[n]; track.stscSamples=new long[n]; track.stscDescription=new long[n];
                    for (int i=0;i<n;i++) { track.stscFirstChunk[i]=u32(b,8+i*12); track.stscSamples[i]=u32(b,12+i*12); track.stscDescription[i]=u32(b,16+i*12); }
                    break;
                }
                case "stsz": {
                    track.stszFixed=u32(b,4);
                    long count=u32(b,8);
                    if (track.stszFixed==0) { track.stszSizes=new long[(int)count]; for (int i=0;i<count;i++) track.stszSizes[i]=u32(b,12+i*4); }
                    else track.stszSizes=new long[0];
                    break;
                }
                case "stco": case "co64": {
                    track.co64=table.type.equals("co64");
                    long count=u32(b,4);
                    track.chunkOffsets=new long[(int)count];
                    for (int i=0;i<count;i++) track.chunkOffsets[i]=track.co64?u64(b,8+i*8):u32(b,8+i*4);
                    break;
                }
                case "stss": {
                    track.hasStss=true;
                    long count=u32(b,4);
                    track.stss=new long[(int)count];
                    for (int i=0;i<count;i++) track.stss[i]=u32(b,8+i*4);
                    break;
                }
                default: break;
            }
        }
        if (track.stsd==null||track.stts.isEmpty()||track.stscFirstChunk==null||track.stszSizes==null&&track.stszFixed<=0||track.chunkOffsets==null) throw new IOException("样本表不完整");
        if (track.mdhdDuration<=0||track.tkhdDuration<=0) throw new IOException("轨道时长无效");
        long samples=track.sampleCount();
        if (declared!=0&&declared!=samples) throw new IOException("stts 与 stsz 样本数不一致");
        // Expand chunk runs and verify every chunk lands inside the mdat payload region.
        int chunkTotal=track.chunkOffsets.length, runIndex=0; long covered=0;
        for (int chunk=1;chunk<=chunkTotal;chunk++) {
            while (runIndex+1<track.stscFirstChunk.length&&track.stscFirstChunk[runIndex+1]<=chunk) runIndex++;
            covered+=track.stscSamples[runIndex];
            long offset=track.chunkOffsets[chunk-1];
            if (offset<input.dataStart||offset>=input.dataEnd) throw new IOException("样本块不在 mdat 数据区");
        }
        if (covered!=samples) throw new IOException("stsc 与 stsz 样本数不一致");
        for (long description: track.stscDescription) if (description!=1) throw new IOException("多样本描述索引不支持");
        if (track.mdhdTimescale<=0||track.mdhdTimescale>1000000) throw new IOException("mdhd timescale 异常");
    }
    private static byte[] readAll(File file) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try (FileInputStream in=new FileInputStream(file)) { byte[] buffer=new byte[65536]; int read; while ((read=in.read(buffer))>0) out.write(buffer,0,read); }
        return out.toByteArray();
    }

    /** Files must already be fully prepared and validated as castable MP4s. */
    public static List<Timing> concat(List<File> files, File output) throws IOException {
        if (files==null||files.size()<2) throw new IOException("合并至少需要两个分集");
        List<Input> inputs=new ArrayList<>();
        for (File file: files) inputs.add(parse(file));
        Input first=inputs.get(0);
        for (Input input: inputs) {
            if (input.tracks.size()!=first.tracks.size()) throw new IOException(INCOMPATIBLE_PREFIX+"轨道数量不同");
            if (input.mvhdTimescale!=first.mvhdTimescale) throw new IOException(INCOMPATIBLE_PREFIX+"时间标度不同");
            for (int t=0;t<first.tracks.size();t++) {
                Track a=first.tracks.get(t), b=input.tracks.get(t);
                if (!a.handler.equals(b.handler)) throw new IOException(INCOMPATIBLE_PREFIX+"轨道类型不同");
                if (!Arrays.equals(a.stsd,b.stsd)) throw new IOException(INCOMPATIBLE_PREFIX+"第"+(t+1)+"轨编码配置不同");
                if (a.mdhdTimescale!=b.mdhdTimescale) throw new IOException(INCOMPATIBLE_PREFIX+"轨道时间标度不同");
                if (a.hasCtts!=b.hasCtts) throw new IOException(INCOMPATIBLE_PREFIX+"ctts 配置不同");
                if (a.hasStss!=b.hasStss) throw new IOException(INCOMPATIBLE_PREFIX+"关键帧表不同");
                if ((a.stszFixed>0)!=(b.stszFixed>0)) throw new IOException(INCOMPATIBLE_PREFIX+"样本大小表形式不同");
                if (a.co64!=b.co64) { /* offsets widen freely; tables stay valid */ }
                // Every input must present the same way, or the merged timeline would drift.
                if (a.hasEdits!=b.hasEdits) throw new IOException(INCOMPATIBLE_PREFIX+"编辑列表配置不同");
                if (a.hasEdits&&a.editDurations.length!=b.editDurations.length) throw new IOException(INCOMPATIBLE_PREFIX+"编辑列表条目数不同");
            }
        }
        // Pass 1: build tables with zero offsets to learn the moov size, then pass 2 with real offsets.
        boolean co64=needsCo64(inputs);
        long payload=0; for (Input input: inputs) payload+=input.dataEnd-input.dataStart;
        byte[] moov=buildMoov(inputs,co64,0);
        long base=first.ftyp.length+8+moov.length; // ftyp + mdat header + moov
        moov=buildMoov(inputs,co64,base);
        if (moov.length!=base-first.ftyp.length-8) { // extremely unlikely table-size dependency on offsets
            base=first.ftyp.length+8+moov.length;
            moov=buildMoov(inputs,co64,base);
        }
        File parent=output.getParentFile();
        if (parent!=null&&!parent.exists()&&!parent.mkdirs()) throw new IOException("无法创建合并输出目录");
        File partial=new File(output.getParentFile(),output.getName()+".part");
        try (FileOutputStream out=new FileOutputStream(partial)) {
            out.write(first.ftyp);
            out.write(moov);
            byte[] mdatHeader=new byte[8]; mdatHeader[0]=(byte)((8+payload)>>>24); mdatHeader[1]=(byte)((8+payload)>>>16); mdatHeader[2]=(byte)((8+payload)>>>8); mdatHeader[3]=(byte)(8+payload); System.arraycopy("mdat".getBytes(StandardCharsets.US_ASCII),0,mdatHeader,4,4); out.write(mdatHeader);
            byte[] buffer=new byte[65536];
            for (Input input: inputs) {
                try (FileInputStream in=new FileInputStream(input.file)) {
                    long skip=input.dataStart;
                    while (skip>0) { long advanced=in.skip(skip); if (advanced<=0) throw new IOException("读取分集失败"); skip-=advanced; }
                    long remaining=input.dataEnd-input.dataStart;
                    while (remaining>0) { int read=in.read(buffer,0,(int)Math.min(buffer.length,remaining)); if (read<=0) throw new IOException("读取分集失败"); out.write(buffer,0,read); remaining-=read; }
                }
            }
        }
        if (!partial.renameTo(output)) { if (!output.delete()||!partial.renameTo(output)) throw new IOException("合并文件落盘失败"); }
        List<Timing> timings=new ArrayList<>();
        for (Input input: inputs) {
            Track video=null;
            for (Track track: input.tracks) if (track.handler.equals("vide")) { video=track; break; }
            long scale=video!=null?video.mdhdTimescale:input.mvhdTimescale;
            long duration=video!=null?video.mdhdDuration:input.mvhdDuration;
            timings.add(new Timing(Math.round(duration*1000.0/scale)));
        }
        return timings;
    }

    /**
     * Chains the per-input edit lists into one list for the merged track. Every input's
     * media times are shifted by the media already written for the preceding inputs, so an
     * encoder's priming trim stays in front of each episode instead of only the first:
     * without it the first 1024 samples of every episode would be played out loud.
     *
     * A leading empty edit (media time -1) is the file's own start padding. Only the first
     * episode keeps it; repeating it would insert a gap between every pair of episodes.
     */
    private static MergedEdits mergedEdits(int index,List<Input> inputs) throws IOException {
        Track first=inputs.get(0).tracks.get(index);
        if (!first.hasEdits) return new MergedEdits(null,0);
        List<Long> durations=new ArrayList<>(),mediaTimes=new ArrayList<>();
        long offset=0;
        for (int i=0;i<inputs.size();i++) {
            Track track=inputs.get(i).tracks.get(index);
            int start=0;
            while (i>0&&start<track.editDurations.length&&track.editMediaTimes[start]<0) start++; // drop a repeated lead-in gap
            for (int e=start;e<track.editDurations.length;e++) {
                durations.add(track.editDurations[e]);
                mediaTimes.add(track.editMediaTimes[e]<0?-1:track.editMediaTimes[e]+offset);
            }
            // The next input's media starts where this one ended on the merged timeline.
            offset+=track.decodeDuration;
        }
        long total=0; for (long duration: durations) total+=duration;
        return new MergedEdits(editList(durations,mediaTimes),total);
    }

    /** Builds an elst box: version 0 with 32-bit entries, version 1 when values overflow. */
    private static byte[] editList(List<Long> durations,List<Long> mediaTimes) throws IOException {
        boolean wide=false;
        for (int i=0;i<durations.size();i++) if (durations.get(i)>0xFFFFFFFFL||mediaTimes.get(i)>Integer.MAX_VALUE||mediaTimes.get(i)<Integer.MIN_VALUE) wide=true;
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        body.write(wide?1:0); // version
        body.write(0); body.write(0); body.write(0); // flags
        writeU32(body,durations.size());
        for (int i=0;i<durations.size();i++) {
            if (wide) { writeU64(body,durations.get(i)); writeU64(body,mediaTimes.get(i)); }
            else { writeU32(body,durations.get(i)); writeU32(body,mediaTimes.get(i)); }
            body.write(0); body.write(1); // media_rate_integer 1
            body.write(0); body.write(0); // media_rate_fraction 0
        }
        return box("elst",body.toByteArray());
    }
    private static boolean needsCo64(List<Input> inputs) {
        long total=0;
        for (Input input: inputs) total+=input.dataEnd-input.dataStart;
        return total>0xF0000000L; // stay clear of the unsigned 32-bit edge
    }
    private static byte[] buildMoov(List<Input> inputs,boolean co64,long base) throws IOException {
        Input first=inputs.get(0);
        // A track's movie duration is its presentation length; the movie is the longest track.
        long[] trackTotals=new long[first.tracks.size()];
        for (int t=0;t<trackTotals.length;t++) {
            long sum=0; for (Input input: inputs) sum+=input.tracks.get(t).presentationDuration();
            trackTotals[t]=sum;
        }
        long mvhdTotal=0;
        for (long total: trackTotals) mvhdTotal=Math.max(mvhdTotal,total);
        // Walk the first moov, splicing replaced boxes.
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for (Box top: children(first.moov,8,first.moov.length)) {
            if (top.type.equals("mvhd")) { out.write(box("mvhd",replaceMvhdDuration(top.body,mvhdTotal))); continue; }
            if (!top.type.equals("trak")) { out.write(slice(first.moov,top)); continue; }
            int index=trakIndex(first,top.start);
            long mdhdTotal=0;
            for (Input input: inputs) mdhdTotal+=input.tracks.get(index).mdhdDuration;
            out.write(box("trak",rebuildTrak(first,top,index,inputs,co64,base,mdhdTotal,trackTotals[index])));
        }
        return box("moov",out.toByteArray());
    }
    private static int trakIndex(Input first,long trakStart) throws IOException {
        int seen=0;
        for (Box item: children(first.moov,8,first.moov.length)) { if (item.type.equals("trak")) { if (item.start==trakStart) return seen; seen++; } }
        throw new IOException("trak 定位失败");
    }
    private static byte[] slice(byte[] source,Box box) { return Arrays.copyOfRange(source,(int)box.start,(int)box.end); }
    private static byte[] rebuildTrak(Input first,Box trak,int index,List<Input> inputs,boolean co64,long base,long mdhdTotal,long tkhdTotal) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        MergedEdits edits=mergedEdits(index,inputs);
        for (Box item: children(trak.body,0,trak.body.length)) {
            if (item.type.equals("tkhd")) { out.write(box("tkhd",replaceTkhdDuration(item.body,tkhdTotal))); continue; }
            // The source edit list describes its own media times; the merged one is rebuilt.
            if (item.type.equals("edts")) { if (edits.elst!=null) out.write(box("edts",edits.elst)); continue; }
            if (!item.type.equals("mdia")) { out.write(slice(trak.body,item)); continue; }
            ByteArrayOutputStream mdia=new ByteArrayOutputStream();
            for (Box part: children(item.body,0,item.body.length)) {
                if (part.type.equals("mdhd")) { mdia.write(box("mdhd",replaceMdhd(first.tracks.get(index),part.body,mdhdTotal))); continue; }
                if (!part.type.equals("minf")) { mdia.write(slice(item.body,part)); continue; }
                ByteArrayOutputStream minf=new ByteArrayOutputStream();
                for (Box inner: children(part.body,0,part.body.length)) {
                    if (inner.type.equals("stbl")) minf.write(box("stbl",rebuildStbl(index,inputs,co64,base,inner)));
                    else minf.write(slice(part.body,inner));
                }
                mdia.write(box("minf",minf.toByteArray()));
            }
            out.write(box("mdia",mdia.toByteArray()));
        }
        return out.toByteArray();
    }
    private static byte[] rebuildStbl(int index,List<Input> inputs,boolean co64,long base,Box original) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for (Box table: children(original.body,0,original.body.length)) {
            switch (table.type) {
                case "stts": out.write(box("stts",mergedStts(index,inputs))); break;
                case "ctts": out.write(box("ctts",mergedCtts(index,inputs))); break;
                case "stsc": out.write(box("stsc",mergedStsc(index,inputs))); break;
                case "stsz": out.write(box("stsz",mergedStsz(index,inputs))); break;
                case "stco": case "co64": out.write(box(co64?"co64":"stco",mergedOffsets(index,inputs,co64,base))); break;
                case "stss": out.write(box("stss",mergedStss(index,inputs))); break;
                default: out.write(slice(original.body,table)); break;
            }
        }
        return out.toByteArray();
    }
    private static byte[] replaceMvhdDuration(byte[] body,long duration) {
        byte[] copy=body.clone();
        int version=fullBoxVersion(copy);
        if (version==0) writeU32(copy,16,duration); else writeU64(copy,24,duration);
        return copy;
    }
    private static byte[] replaceTkhdDuration(byte[] body,long duration) {
        byte[] copy=body.clone();
        int version=fullBoxVersion(copy);
        if (version==0) writeU32(copy,20,duration); else writeU64(copy,28,duration);
        return copy;
    }
    private static byte[] replaceMdhd(Track reference,byte[] body,long duration) {
        byte[] copy=body.clone();
        int version=fullBoxVersion(copy);
        if (version==0) writeU32(copy,16,duration); else writeU64(copy,24,duration);
        return copy;
    }
    private static byte[] mergedStts(int index,List<Input> inputs) throws IOException {
        ByteArrayOutputStream entries=new ByteArrayOutputStream();
        long count=0;
        for (Input input: inputs) for (long[] entry: input.tracks.get(index).stts) { writeU32(entries,entry[0]); writeU32(entries,entry[1]); count++; }
        ByteArrayOutputStream box=new ByteArrayOutputStream(); writeU32(box,0); writeU32(box,count); box.write(entries.toByteArray()); return box.toByteArray();
    }
    private static byte[] mergedCtts(int index,List<Input> inputs) throws IOException {
        ByteArrayOutputStream entries=new ByteArrayOutputStream();
        long count=0;
        for (Input input: inputs) for (long[] entry: input.tracks.get(index).ctts) { writeU32(entries,entry[0]); writeU32(entries,entry[1]); count++; }
        // Preserve the source version: v1 deltas are signed and must not be re-read as v0.
        ByteArrayOutputStream box=new ByteArrayOutputStream(); writeU32(box,inputs.get(0).tracks.get(index).cttsVersion&0xffffffffL); writeU32(box,count); box.write(entries.toByteArray()); return box.toByteArray();
    }
    private static byte[] mergedStsc(int index,List<Input> inputs) throws IOException {
        ByteArrayOutputStream entries=new ByteArrayOutputStream();
        long chunk=1,count=0,lastSamples=-1;
        for (Input input: inputs) {
            Track track=input.tracks.get(index);
            int total=track.chunkOffsets.length, run=0;
            for (long c=1;c<=total;c++) {
                while (run+1<track.stscFirstChunk.length&&track.stscFirstChunk[run+1]<=c) run++;
                long samples=track.stscSamples[run];
                if (samples!=lastSamples) { writeU32(entries,chunk); writeU32(entries,samples); writeU32(entries,1); count++; lastSamples=samples; }
                chunk++;
            }
        }
        ByteArrayOutputStream box=new ByteArrayOutputStream(); writeU32(box,0); writeU32(box,count); box.write(entries.toByteArray()); return box.toByteArray();
    }
    private static byte[] mergedStsz(int index,List<Input> inputs) throws IOException {
        Track first=inputs.get(0).tracks.get(index);
        ByteArrayOutputStream box=new ByteArrayOutputStream(); writeU32(box,0);
        if (first.stszFixed>0) {
            long total=0; for (Input input: inputs) total+=input.tracks.get(index).sampleCount();
            writeU32(box,first.stszFixed); writeU32(box,total);
            return box.toByteArray();
        }
        ByteArrayOutputStream sizes=new ByteArrayOutputStream(); long total=0;
        for (Input input: inputs) for (long size: input.tracks.get(index).stszSizes) { writeU32(sizes,size); total++; }
        writeU32(box,0); writeU32(box,total); box.write(sizes.toByteArray());
        return box.toByteArray();
    }
    private static byte[] mergedOffsets(int index,List<Input> inputs,boolean co64,long base) throws IOException {
        ByteArrayOutputStream entries=new ByteArrayOutputStream(); long count=0, cursor=base;
        for (Input input: inputs) {
            Track track=input.tracks.get(index);
            // Each input's payload region lands sequentially in the output mdat.
            for (long offset: track.chunkOffsets) {
                long mapped=cursor+(offset-input.dataStart);
                if (co64) writeU64(entries,mapped); else writeU32(entries,mapped);
                count++;
            }
            cursor+=input.dataEnd-input.dataStart;
        }
        ByteArrayOutputStream box=new ByteArrayOutputStream(); writeU32(box,0); writeU32(box,count); box.write(entries.toByteArray()); return box.toByteArray();
    }
    private static byte[] mergedStss(int index,List<Input> inputs) throws IOException {
        ByteArrayOutputStream entries=new ByteArrayOutputStream(); long count=0, shift=0;
        for (Input input: inputs) {
            for (long sample: input.tracks.get(index).stss) { writeU32(entries,shift+sample); count++; }
            shift+=input.tracks.get(index).sampleCount();
        }
        ByteArrayOutputStream box=new ByteArrayOutputStream(); writeU32(box,0); writeU32(box,count); box.write(entries.toByteArray()); return box.toByteArray();
    }
    private static byte[] box(String type,byte[] payload) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        writeU32(out,8+payload.length);
        out.write(type.getBytes(StandardCharsets.US_ASCII),0,4);
        out.write(payload);
        return out.toByteArray();
    }
    private static void writeU32(ByteArrayOutputStream out,long value) { out.write((int)(value>>>24)&0xff); out.write((int)(value>>>16)&0xff); out.write((int)(value>>>8)&0xff); out.write((int)value&0xff); }
    private static void writeU64(ByteArrayOutputStream out,long value) { writeU32(out,value>>>32); writeU32(out,value&0xffffffffL); }
    private static void writeU32(byte[] target,int offset,long value) { target[offset]=(byte)(value>>>24); target[offset+1]=(byte)(value>>>16); target[offset+2]=(byte)(value>>>8); target[offset+3]=(byte)value; }
    private static void writeU64(byte[] target,int offset,long value) { writeU32(target,offset,value>>>32); writeU32(target,offset+4,value&0xffffffffL); }
}
