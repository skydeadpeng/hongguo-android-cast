package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.json.*;

/** Temporary owned files and memory preferences; never touches real downloads or a device. */
public final class PreparedDownloadTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private interface Checked{void run()throws Exception;}
    private static void fails(Checked action,String label)throws Exception{try{action.run();}catch(Exception expected){checks++;return;}throw new AssertionError(label);}
    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String,String> data=new HashMap<>();boolean writable=true;
        public String getString(String key,String fallback){return data.getOrDefault(key,fallback);}
        public Map<String,?> getAll(){return new HashMap<String,Object>(data);}
        public Editor edit(){return new Editor(){final Map<String,String> edits=new HashMap<>();public Editor putString(String key,String value){edits.put(key,value);return this;}public Editor putBoolean(String key,boolean value){return this;}public Editor putInt(String key,int value){return this;}public Editor putLong(String key,long value){return this;}public Editor putFloat(String key,float value){return this;}public Editor putStringSet(String key,java.util.Set<String> values){return this;}public Editor remove(String key){edits.remove(key);return this;}public boolean commit(){if(!writable)return false;data.putAll(edits);return true;}public void apply(){commit();}};}
    }
    private static final class MemoryContext extends Context {
        final MemoryPreferences prefs=new MemoryPreferences();final File directory;
        MemoryContext(File directory){this.directory=directory;}
        public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
        public File getExternalFilesDir(String type){return directory;}
    }
    private static byte[] mp4()throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(16);out.writeBytes("ftyp");out.writeBytes("isom");out.writeInt(0);out.writeInt(16);out.writeBytes("mdat");out.writeLong(1234);out.writeInt(8);out.writeBytes("moov");return bytes.toByteArray();}
    private static JSONObject series()throws Exception{return new JSONObject().put("seriesId","7693823855940357144").put("title","generated fixture");}
    private static JSONObject episode(int index)throws Exception{return new JSONObject().put("vid","10000"+index).put("index",index).put("availability","unknown");}
    private static JSONObject record(int index)throws Exception{return new JSONObject().put("series",series()).put("episode",episode(index));}
    private static String name(String suffix){return "7693823855940357144_4_"+suffix+".mp4";}
    private static void remove(File file)throws Exception{if(file.isDirectory()&&!Files.isSymbolicLink(file.toPath()))for(File child:Objects.requireNonNull(file.listFiles()))remove(child);Files.deleteIfExists(file.toPath());}
    public static void main(String[]args)throws Exception{
        File root=Files.createTempDirectory("hongguo-prepared-download-").toFile();
        try{
            File directory=new File(root,"Download");check(directory.mkdir(),"temporary app-owned directory created");
            File target=new File(directory,name("12345678-1234-4234-8234-123456789abc"));Files.write(target.toPath(),mp4());
            Files.setPosixFilePermissions(target.toPath(),PosixFilePermissions.fromString("rw-------"));
            Set<java.nio.file.attribute.PosixFilePermission> permissions=Files.getPosixFilePermissions(target.toPath());
            JSONObject row=record(4),metadata=LibraryStore.preparedMetadata(directory,row,target);
            row.put("preparedFile",metadata.getString("preparedFile")).put("preparedBytes",metadata.getLong("preparedBytes"));
            check(LibraryStore.preparedFile(directory,row).equals(target),"mode0600 owned file resolved without DownloadProvider");
            check(metadata.getString("preparedFile").equals(target.getName())&&!metadata.getString("preparedFile").contains("/"),"only basename persists");
            check(metadata.getLong("preparedBytes")==target.length(),"exact byte count persists, not provider zero");
            for(Object invalid:new Object[]{"../"+target.getName(),target.getAbsolutePath(),"7693823855940357144_5_12345678-1234-4234-8234-123456789abc.mp4","7693823855940357144_4_1700000000000.mp4","missing.mp4"})check(LibraryStore.preparedFile(directory,new JSONObject(row.toString()).put("preparedFile",invalid))==null,"invalid basename or episode mapping rejected");
            for(Object invalid:new Object[]{0,-1,41.5,"40",100000000L})check(LibraryStore.preparedFile(directory,new JSONObject(row.toString()).put("preparedBytes",invalid))==null,"invalid exact file size rejected");
            File outside=new File(root,target.getName());Files.write(outside.toPath(),mp4());
            fails(()->LibraryStore.preparedMetadata(directory,row,outside),"outside file metadata accepted");
            File symlink=new File(directory,name("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));Files.createSymbolicLink(symlink.toPath(),outside.toPath());
            check(LibraryStore.preparedFile(directory,new JSONObject(row.toString()).put("preparedFile",symlink.getName()))==null,"symlink download rejected");
            File linkedDirectory=new File(root,"linked");Files.createSymbolicLink(linkedDirectory.toPath(),directory.toPath());check(LibraryStore.preparedFile(linkedDirectory,row)==null,"symlink download root rejected");
            byte[] complete=mp4();Files.write(target.toPath(),Arrays.copyOf(complete,complete.length-1));check(LibraryStore.preparedFile(directory,row)==null,"truncated file never reports complete");
            Files.write(target.toPath(),complete);byte[] bad=complete.clone();bad[bad.length-8]=0;bad[bad.length-7]=0;bad[bad.length-6]=0;bad[bad.length-5]=7;Files.write(target.toPath(),bad);check(LibraryStore.preparedFile(directory,row)==null,"malformed MP4 size rejected despite equal length");Files.write(target.toPath(),complete);
            JSONObject legacy=record(4);
            check(LibraryStore.recoverPreparedFile(directory,legacy,null,0,"红果 · 已准备的视频").equals(target),"legacy provider-zero/null-URI recovers unique owned UUID file");
            check(LibraryStore.recoverPreparedFile(directory,legacy,target.toURI().toString(),0,"红果 · 已准备的视频").equals(target),"legacy exact file URI recovers only owned path");
            check(LibraryStore.recoverPreparedFile(directory,legacy,null,0,"红果 · 视频下载")==null,"ordinary DownloadManager record is not relabeled prepared");
            check(LibraryStore.recoverPreparedFile(directory,legacy,null,41,"红果 · 已准备的视频")==null,"positive provider size mismatch rejected");
            File second=new File(directory,name("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"));Files.write(second.toPath(),complete);
            check(LibraryStore.recoverPreparedFile(directory,legacy,null,0,"红果 · 已准备的视频")==null,"ambiguous legacy candidates never guessed");
            check(LibraryStore.recoverPreparedFile(directory,legacy,target.toURI().toString(),0,"红果 · 已准备的视频").equals(target),"exact owned provider path disambiguates UUID files");check(second.delete(),"second test file removed");
            MemoryContext context=new MemoryContext(directory);LibraryStore store=new LibraryStore(context);Map<Long,String> oldRows=new HashMap<>();Map<File,byte[]> oldFiles=new HashMap<>();
            for(int index=1;index<=3;index++){store.addDownload(index,series(),episode(index));oldRows.put((long)index,store.download(index).toString());File old=new File(directory,"7693823855940357144_"+index+"_1700000000000.mp4");Files.write(old.toPath(),complete);oldFiles.put(old,Files.readAllBytes(old.toPath()));}
            store.addDownload(12,series(),episode(4));JSONObject upgraded=store.bindPreparedDownload(12,directory,target);
            check(store.downloads().length()==4&&upgraded.has("preparedFile"),"existing prepared id upgraded without duplicate download");
            check(LibraryStore.preparedFile(context,store.download(12)).equals(target),"persisted prepared mapping survives reload");
            for(Map.Entry<Long,String> old:oldRows.entrySet())check(old.getValue().equals(store.download(old.getKey()).toString()),"each of three legacy records unchanged");
            for(Map.Entry<File,byte[]> old:oldFiles.entrySet())check(Arrays.equals(old.getValue(),Files.readAllBytes(old.getKey().toPath())),"each of three legacy manual files unchanged");
            store.addPreparedDownload(13,series(),episode(4),directory,target);check(store.downloads().length()==5&&store.download(13).getLong("preparedBytes")==complete.length,"new prepared record commits filename and size together");
            check(Files.getPosixFilePermissions(target.toPath()).equals(permissions),"prepared mapping never broadens 0600 permissions");
            context.prefs.writable=false;String before=store.downloads().toString();fails(()->store.addPreparedDownload(14,series(),episode(4),directory,target),"failed preference commit accepted");check(before.equals(store.downloads().toString()),"failed persistence leaves existing records unchanged");
            check(target.delete(),"prepared test file removed");check(LibraryStore.preparedFile(context,store.download(12))==null,"missing mapped file never reports completed");
            System.out.println("PASS PreparedDownloadTest checks="+checks+" mode0600=true providerUriNotRequired=true oldRecordsAndFilesPreserved=3 networkRequests=0 deviceOperations=0");
        }finally{remove(root);}
    }
}
