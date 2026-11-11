package io.github.jackotom.hongguo.android;

import java.io.File;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual FileSource merge method + MP4 remux + Prepared + HTTP server; loopback only. */
public final class DlnaMergedSourceTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static File mp4(File directory,String name,int seed,boolean incompatible)throws Exception {
        Class<?> builder=Class.forName("io.github.jackotom.hongguo.android.Mp4ConcatTest$Builder");
        Constructor<?> constructor=builder.getDeclaredConstructors()[0];constructor.setAccessible(true);
        byte[] config=incompatible?new byte[40]:null;if(config!=null)config[36]=7;
        Object instance=constructor.newInstance(1000,240,600,600,new long[]{12,9,15,7,20,11},new long[]{8,8,8,8,8,8,8,8},new boolean[]{true,false,false,true,false,false},2,2,config,false);
        Method build=builder.getDeclaredMethod("build",int.class);build.setAccessible(true);
        File file=new File(directory,name);Files.write(file.toPath(),(byte[])build.invoke(instance,seed));return file;
    }
    static boolean allPins(DlnaMergedSourceHarness source,int expected){return source.pins.stream().allMatch(pin->pin.get()==expected);}
    static void empty(File directory,String label){File[] rows=directory.listFiles();check(rows==null||rows.length==0,label);}
    static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
    public static void main(String[] args)throws Exception {
        File directory=Files.createTempDirectory("hongguo-merged-source-").toFile();
        try {
            List<File> inputs=Arrays.asList(mp4(directory,"a.mp4",0,false),mp4(directory,"b.mp4",1,false),mp4(directory,"c.mp4",2,false));
            publishedLifetime(directory,inputs);
            cancelledAndFallback(directory,inputs);
            System.out.println("DLNA_MERGED_SOURCE_OK checks="+checks+" prepareMerged=actualProduction concatPreparedServer=actual ownerRelease=survives headRange=survives retry=unique stop=cleanup childLeases=balanced externalRequests=0 deviceTests=not_run");
        } finally {delete(directory);}
    }
    static void publishedLifetime(File directory,List<File> inputs)throws Exception {
        File output=new File(directory,"merged");DlnaMergedSourceHarness source=new DlnaMergedSourceHarness(output,inputs);
        DlnaSession.Prepared first=source.prepare(0,()->true);
        check(first.windowCount==3&&first.file.isFile(),"three real MP4s produce a merged media");
        check(allPins(source,1),"prepared output owns each child once");
        try(DlnaMediaServer server=DlnaMediaServerTest.server(1000,1500,10000)) {
            DlnaMediaServer.Publication old=server.publish(first.file,first.title,first.retain,first.release);
            first.releaseOwner();first.releaseOwner();
            check(first.file.isFile(),"published merge must survive initial Prepared owner release");
            check(allPins(source,1),"publication keeps all child owners balanced");
            URI oldUrl=URI.create(old.url);
            check(DlnaMediaServerTest.request(oldUrl,"HEAD","").status==200,"HEAD succeeds before media GET");
            check(first.file.isFile(),"HEAD completion does not delete published merge");
            check(DlnaMediaServerTest.request(oldUrl,"GET","Range: bytes=0-31\r\n").status==206,"range GET succeeds");
            check(DlnaMediaServerTest.request(oldUrl,"GET","Range: bytes=32-63\r\n").body.length==32,"subsequent seek GET reads original merge");
            check(first.file.isFile(),"GET completion does not delete published merge");
            check(old.acquire(),"retain active old request before replacement");
            DlnaSession.Prepared next=source.prepare(0,()->true);
            check(!first.file.getCanonicalPath().equals(next.file.getCanonicalPath()),"retry same position gets unique file while old URI lives");
            DlnaMediaServer.Publication current=server.publish(next.file,next.title,next.retain,next.release);next.releaseOwner();
            server.revoke(old);
            check(first.file.isFile(),"revoked URI retains file for already-active request");
            old.releaseRequest();
            check(!first.file.exists(),"old output deleted when its last active request ends");
            check(next.file.isFile(),"old cleanup never deletes replacement output");
            source.close();
            check(next.file.isFile(),"FileSource close does not prematurely delete live publication");
            check(DlnaMediaServerTest.request(URI.create(current.url),"GET","").status==200,"live URI remains readable until server teardown");
            server.close();
            check(!next.file.exists(),"server stop deletes final merged output");
            check(allPins(source,0),"all original child owners released exactly once");
            empty(output,"normal stop leaves no merged or partial files");
        } finally {first.releaseOwner();source.close();}
    }
    static void cancelledAndFallback(File directory,List<File> inputs)throws Exception {
        File output=new File(directory,"cancelled");DlnaMergedSourceHarness cancelled=new DlnaMergedSourceHarness(output,inputs);AtomicInteger calls=new AtomicInteger();
        boolean interrupted=false;try {cancelled.prepare(0,()->calls.incrementAndGet()<=3);}catch(InterruptedIOException expected){interrupted=true;}
        check(interrupted,"cancel arriving after concat is honored before publication");
        check(allPins(cancelled,0),"cancel after concat balances all child owner leases");empty(output,"cancel removes owned output and partial");
        File odd=mp4(directory,"different.mp4",3,true);DlnaMergedSourceHarness fallback=new DlnaMergedSourceHarness(new File(directory,"fallback"),Arrays.asList(inputs.get(0),odd));
        DlnaSession.Prepared single=fallback.prepare(0,()->true);
        check(single.windowCount==1&&single.file.equals(inputs.get(0)),"incompatible encodings preserve single-episode fallback");
        check(fallback.pins.get(0).get()==1&&fallback.pins.get(1).get()==0,"fallback releases unused episode only");
        single.releaseOwner();check(allPins(fallback,0),"fallback releases first episode once");empty(fallback.mergedDirectory,"fallback leaves no merged temporary file");
        DlnaMergedSourceHarness tail=new DlnaMergedSourceHarness(new File(directory,"tail"),inputs);tail.request.mergeWindow=5;
        DlnaSession.Prepared finalEpisode=tail.prepare(2,()->true);
        check(finalEpisode.windowCount==1&&tail.pins.size()==1,"five-episode merge request truncates at current queue edge");
        finalEpisode.releaseOwner();check(allPins(tail,0),"tail single episode leases balance");
        check(inputs.stream().allMatch(File::isFile),"manual/source fixture files never deleted by merged cleanup");
    }
}
