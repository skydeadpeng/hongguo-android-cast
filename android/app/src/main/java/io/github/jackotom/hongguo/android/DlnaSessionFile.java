package io.github.jackotom.hongguo.android;

import java.io.*;
import java.util.function.BooleanSupplier;

/** DLNA publishes complete ISO MP4, never an HLS playlist renamed by a cache extension. */
final class DlnaSessionFile {
    static final class NotMp4Exception extends IOException { NotMp4Exception(){super("投屏文件不是完整 ISO MP4");} }
    interface Attempt { File run() throws Exception; }
    interface Invalidate { void run() throws Exception; }
    private static void alive(BooleanSupplier alive)throws InterruptedIOException{if(Thread.currentThread().isInterrupted()||!alive.getAsBoolean())throw new InterruptedIOException("投屏准备已取消");}
    static File prepare(Attempt first,boolean webOrAutoCache,Attempt app,Invalidate invalidate,BooleanSupplier alive)throws Exception{
        alive(alive);File file=first.run();alive(alive);
        try{validate(file);return file;}
        catch(NotMp4Exception invalid){
            alive(alive);invalidate.run();
            if(!webOrAutoCache)throw invalid;
            // Exactly one same-quality App model. Ordinary HTTP/TLS/network retries already belong to PlaybackSources.download.
            alive(alive);File replacement=app.run();alive(alive);
            try{validate(replacement);return replacement;}
            catch(NotMp4Exception malformedApp){invalidate.run();throw malformedApp;}
        }
    }
    static void validate(File input)throws IOException{
        if(input==null||!input.isFile())throw new NotMp4Exception();
        try(RandomAccessFile file=new RandomAccessFile(input,"r")){
            long length=file.length(),offset=0;int count=0;boolean ftyp=false,moov=false,mdat=false;
            while(offset<length){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("投屏准备已取消");
                if(++count>10000||length-offset<8)throw new NotMp4Exception();
                file.seek(offset);long size=file.readInt()&0xffffffffL;int kind=file.readInt();long header=8;
                if(size==1){if(length-offset<16)throw new NotMp4Exception();size=file.readLong();header=16;if(size<0)throw new NotMp4Exception();}
                else if(size==0)size=length-offset;
                if(size<header||size>length-offset)throw new NotMp4Exception();
                if(kind==0x66747970){if(size-header<8)throw new NotMp4Exception();ftyp=true;}
                if(kind==0x6d6f6f76){if(size-header<8)throw new NotMp4Exception();moov=true;}
                if(kind==0x6d646174&&size>header)mdat=true;
                offset+=size; // Bounded by length-offset above, so this cannot overflow. Payload is seek-skipped, never buffered.
            }
            if(offset!=length||file.length()!=length||!ftyp||!moov||!mdat)throw new NotMp4Exception();
        }catch(EOFException truncated){throw new NotMp4Exception();}
    }
}
