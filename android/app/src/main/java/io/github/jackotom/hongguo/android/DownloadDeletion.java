package io.github.jackotom.hongguo.android;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deletes one explicitly confirmed local download; never accepts a client path. */
final class DownloadDeletion {
    interface Provider {
        Record query(long id) throws Exception;
        int remove(long id) throws Exception;
    }
    interface FileRemoval { void delete(File file) throws Exception; }
    static final class Record {
        final String localUri,description;final int status;
        Record(String localUri,String description){this(8,localUri,description);}
        Record(int status,String localUri,String description){this.status=status;this.localUri=localUri;this.description=description;}
    }
    private static final FileRemoval FILES=file->Files.deleteIfExists(file.toPath());
    static long confirmedId(JSONObject params) throws Exception {
        Object raw=params.opt("id");
        if(!Boolean.TRUE.equals(params.opt("confirmed")))throw new IOException("请先确认删除此下载");
        if(!(raw instanceof Number))throw new IOException("下载编号无效");
        double value=((Number)raw).doubleValue();
        if(!Double.isFinite(value)||value!=Math.rint(value)||value<1||value>9007199254740991d)throw new IOException("下载编号无效");
        return ((Number)raw).longValue();
    }
    static JSONObject delete(LibraryStore store,File directory,Provider provider,JSONObject params) throws Exception {
        return delete(store,directory,provider,params,FILES);
    }
    static JSONObject delete(LibraryStore store,File directory,Provider provider,JSONObject params,FileRemoval removal) throws Exception {
        long id=confirmedId(params);
        synchronized(LibraryStore.class) {
            JSONObject row=store.download(id);
            if(row==null) {
                if(store.deletedDownload(id))return new JSONObject().put("id",id).put("deleted",true).put("alreadyDeleted",true);
                throw new IOException("下载记录不存在，请重新读取下载列表");
            }
            JSONArray rows=store.downloads();int identityCount=0;
            for(int i=0;i<rows.length();i++){JSONObject candidate=rows.optJSONObject(i);if(candidate!=null&&candidate.optLong("id",-1)==id)identityCount++;}
            if(identityCount!=1)throw new IOException("下载记录身份重复，未删除文件");
            Record managed=provider.query(id); // A provider failure must not look like an absent row.
            File target=resolve(directory,row,managed,rows,id,provider);
            if(!row.optBoolean("deleting",false)||target!=null&&!target.getName().equals(row.optString("deletingFile")))
                store.stageDownloadDeletion(id,target==null?null:target.getName());
            if(managed!=null) {
                int removed=provider.remove(id);
                if(removed<0||provider.query(id)!=null)throw new IOException("系统下载取消或删除未完成，请重试");
            }
            if(target!=null) {
                // Recheck after provider work: never follow a replaced symlink or directory.
                target=owned(directory,row,target.getName());
                try {removal.delete(target);}catch(Exception error){throw new IOException("视频文件删除未完成，请重试",error);}
                if(Files.exists(target.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IOException("视频文件仍存在，请重试删除");
            }
            store.finishDownloadDeletion(id);
            return new JSONObject().put("id",id).put("deleted",true);
        }
    }
    static boolean managedName(JSONObject row,String name) throws Exception {
        return name!=null&&name.matches(Pattern.quote(LibraryStore.preparedPrefix(row))+"[0-9]{10,17}\\.mp4");
    }
    private static boolean boundName(JSONObject row,String name) throws Exception {
        return name!=null&&(LibraryStore.preparedName(row,name)||managedName(row,name));
    }
    private static File owned(File directory,JSONObject row,String name) throws Exception {
        if(directory==null||Files.isSymbolicLink(directory.toPath())||directory.exists()&&!directory.isDirectory()||!boundName(row,name))
            throw new IOException("下载文件归属校验未通过，未删除文件");
        File file=new File(directory,name);
        if(Files.isSymbolicLink(file.toPath())||!directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())||file.exists()&&!file.isFile())
            throw new IOException("下载文件路径校验未通过，未删除文件");
        return file;
    }
    private static File fromProvider(File directory,JSONObject row,Record managed) throws Exception {
        if(managed==null||managed.localUri==null||managed.localUri.isEmpty())return null;
        URI uri;
        try {uri=new URI(managed.localUri);}catch(Exception error){throw new IOException("系统下载文件路径无效",error);}
        if("content".equals(uri.getScheme()))return null; // Provider owns this exact locally recorded id.
        if(!"file".equals(uri.getScheme())||uri.getRawAuthority()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)
            throw new IOException("系统下载文件路径无效");
        File raw=new File(uri),file=owned(directory,row,raw.getName());
        // Reject lexical traversal as well as canonical escapes.
        if(!raw.getAbsoluteFile().equals(file.getAbsoluteFile())||!raw.getCanonicalFile().equals(file.getCanonicalFile()))
            throw new IOException("系统下载文件不属于此本地记录");
        return file;
    }
    private static File resolve(File directory,JSONObject row,Record managed,JSONArray rows,long id,Provider provider) throws Exception {
        File target=null;
        String key=row.has("deletingFile")?"deletingFile":row.has("preparedFile")?"preparedFile":row.has("downloadFile")?"downloadFile":null;
        if(key!=null) {
            Object value=row.opt(key);
            if(!(value instanceof String))throw new IOException("下载文件映射无效");
            if("preparedFile".equals(key)&&!LibraryStore.preparedName(row,(String)value))throw new IOException("下载文件映射无效");
            if("downloadFile".equals(key)&&!managedName(row,(String)value))throw new IOException("下载文件映射无效");
            target=owned(directory,row,(String)value);
            for(String mapping:new String[]{"preparedFile","downloadFile"})
                if(row.has(mapping)&&!((String)value).equals(row.optString(mapping,"")))throw new IOException("下载文件重试映射不一致");
        }
        File providerTarget=fromProvider(directory,row,managed);
        if(target!=null&&providerTarget!=null&&!target.equals(providerTarget))throw new IOException("系统下载文件与本地映射不一致");
        if(target==null)target=providerTarget;
        if(target==null&&managed!=null&&"红果 · 已准备的视频".equals(managed.description)) {
            // Legacy addCompletedDownload can omit local_uri. Only a unique exact UUID is recoverable.
            if(directory==null||!directory.isDirectory()||Files.isSymbolicLink(directory.toPath()))throw new IOException("下载目录暂不可用，请重试");
            File[] files=directory.listFiles();if(files==null||files.length>512)throw new IOException("旧下载文件定位未完成，请重试");
            String prefix=LibraryStore.preparedPrefix(row);
            for(int i=0;i<rows.length();i++){JSONObject other=rows.optJSONObject(i);if(other==null||other.optLong("id",-1)==id)continue;
                try {if(!other.has("preparedFile")&&!other.has("downloadFile")&&prefix.equals(LibraryStore.preparedPrefix(other)))throw new IOException("旧下载记录归属存在歧义，未删除文件");}catch(org.json.JSONException ignored){ }
            }
            for(File candidate:files)if(LibraryStore.preparedName(row,candidate.getName())) {
                File checked=owned(directory,row,candidate.getName());
                if(target!=null)throw new IOException("旧下载文件存在多个候选，未删除文件");
                target=checked;
            }
        }
        if(target!=null)for(int i=0;i<rows.length();i++) {
            JSONObject other=rows.optJSONObject(i);if(other==null||other.optLong("id",-1)==id)continue;
            for(String alias:new String[]{"preparedFile","downloadFile","deletingFile"})
                if(target.getName().equals(other.optString(alias,"")))throw new IOException("文件还被其他下载记录引用，未删除文件");
            if(!other.has("preparedFile")&&!other.has("downloadFile")&&!other.has("deletingFile")&&LibraryStore.preparedPrefix(row).equals(LibraryStore.preparedPrefix(other))) {
                Record otherManaged=provider.query(other.getLong("id"));
                File otherTarget=fromProvider(directory,other,otherManaged);
                if(otherTarget==null)throw new IOException("同集旧下载文件归属尚未确认，请先清理失效记录");
                if(target.equals(otherTarget))throw new IOException("文件还被其他系统下载引用，未删除文件");
            }
        }
        return target;
    }
}
