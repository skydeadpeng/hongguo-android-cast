package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import org.json.*;
import java.util.*;

final class LibraryStore {
    private static final String SEARCH_KEY = "search_keywords_v1";
    private static final int SEARCH_LIMIT = 20;
    interface HistoryAccess {
        JSONArray rows() throws JSONException;
        void record(JSONObject row) throws JSONException;
        // Null preserves older history-only providers; official providers override with an isolated list.
        default JSONArray favorites() throws JSONException { return null; }
        default void toggleFavorite(JSONObject series) throws JSONException { throw new JSONException("此账号收藏暂不可用"); }
        default int position(JSONObject series, JSONObject episode) throws JSONException { return -1; }
        default JSONObject projection() throws JSONException { return null; }
        default JSONObject seriesHistory(String seriesId) throws JSONException { return null; }
    }
    interface HistoryFactory { HistoryAccess open(); }
    private static volatile HistoryFactory historyFactory;
    static void setHistoryFactory(HistoryFactory factory) { historyFactory=factory; }
    private final SharedPreferences preferences;
    private final HistoryAccess accountHistory;
    LibraryStore(Context context) {
        preferences = context.getSharedPreferences("hongguo_library_v1", Context.MODE_PRIVATE);
        HistoryFactory factory=historyFactory;accountHistory=factory==null?null:factory.open();
    }
    private JSONArray historyRows() throws JSONException { return accountHistory==null?read("history"):accountHistory.rows(); }
    static String text(JSONObject object, String key, int max) throws JSONException {
        String value = object.optString(key, "").trim();
        if (value.length() > max) throw new JSONException(key + " 过长");
        return value;
    }
    static JSONObject series(JSONObject input) throws JSONException {
        if (input == null) throw new JSONException("缺少剧集");
        String id = text(input, "seriesId", 30), title = text(input, "title", 200);
        if (!id.matches("[0-9]{6,30}") || title.isEmpty()) throw new JSONException("剧集信息无效");
        JSONObject result = new JSONObject().put("seriesId", id).put("title", title)
            .put("coverUrl", text(input, "coverUrl", 4096)).put("description", text(input, "description", 8000))
            .put("episodeCount", Math.max(0, Math.min(10000, input.optInt("episodeCount"))))
            .put("episodeLabel", text(input, "episodeLabel", 100));
        JSONArray tags = input.optJSONArray("tags"), clean = new JSONArray();
        if (tags != null) for (int i = 0; i < Math.min(8, tags.length()); i++) { String tag = tags.optString(i); if (tag.length() <= 80) clean.put(tag); }
        if (input.has("seasonIndex")) {
            Object raw = input.opt("seasonIndex");
            if (!(raw instanceof Number) || !Double.isFinite(((Number) raw).doubleValue()) || ((Number) raw).doubleValue() != Math.rint(((Number) raw).doubleValue()) || ((Number) raw).doubleValue() < 0 || ((Number) raw).doubleValue() > 10000) throw new JSONException("分季序号无效");
            result.put("seasonIndex", ((Number) raw).intValue());
        }
        if (input.has("multiSeason")) { if (!(input.opt("multiSeason") instanceof Boolean)) throw new JSONException("分季标记无效"); result.put("multiSeason", input.getBoolean("multiSeason")); }
        return result.put("tags", clean);
    }
    static JSONObject episode(JSONObject input) throws JSONException {
        if (input == null) throw new JSONException("缺少分集");
        Object rawVid = input.opt("vid"), rawIndex = input.opt("index");
        if (!(rawVid instanceof String) || !((String) rawVid).matches("[0-9]{1,30}") || !(rawIndex instanceof Number)) throw new JSONException("分集信息无效");
        String vid = (String) rawVid;
        double value = ((Number) rawIndex).doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < 1 || value > 10000) throw new JSONException("分集信息无效");
        int index = (int) value;
        String availability = input.optString("availability", "");
        if (!availability.equals("available") && !availability.equals("unknown")) availability = input.optBoolean("webAvailable") ? "available" : "unknown";
        return new JSONObject().put("vid", vid).put("index", index).put("webAvailable", availability.equals("available")).put("availability", availability);
    }
    /** Compact bounded player queue. The selected episode wins any duplicate vid or index. */
    static JSONArray episodes(JSONArray input, JSONObject current) throws JSONException {
        JSONObject selected = episode(current);
        if (input != null && input.length() > 1000) throw new JSONException("连续播放队列最多支持 1000 集");
        List<JSONObject> rows = new ArrayList<>();
        Set<String> vids = new HashSet<>();
        Set<Integer> indexes = new HashSet<>();
        rows.add(selected); vids.add(selected.getString("vid")); indexes.add(selected.getInt("index"));
        if (input != null) for (int i = 0; i < input.length(); i++) {
            JSONObject row = episode(input.getJSONObject(i));
            String vid = row.getString("vid"); int index = row.getInt("index");
            if (vids.contains(vid) || indexes.contains(index)) continue;
            rows.add(row); vids.add(vid); indexes.add(index);
        }
        if (rows.size() > 1000) throw new JSONException("连续播放队列最多支持 1000 集");
        rows.sort(Comparator.comparingInt(row -> row.optInt("index")));
        JSONArray result = new JSONArray();
        for (JSONObject row : rows) result.put(row);
        return result;
    }
    private JSONArray read(String key) { try { return new JSONArray(preferences.getString(key, "[]")); } catch (JSONException error) { return new JSONArray(); } }
    private static boolean searchSpace(int value) {
        return value>=9&&value<=13 || value==32 || value==0xa0 || value==0x1680 || value>=0x2000&&value<=0x200a || value==0x2028 || value==0x2029 || value==0x202f || value==0x205f || value==0x3000 || value==0xfeff;
    }
    /** Match JavaScript trim without requiring newer Android String.strip APIs. */
    private static String trimSearch(String value) {
        int start=0,end=value.length();
        while(start<end&&searchSpace(value.codePointAt(start)))start+=Character.charCount(value.codePointAt(start));
        while(end>start&&searchSpace(value.codePointBefore(end)))end-=Character.charCount(value.codePointBefore(end));
        return value.substring(start,end);
    }
    private static String submittedKeyword(JSONObject params) throws JSONException {
        Object raw=params==null?null:params.opt("keyword");
        if(!(raw instanceof String))throw new JSONException("搜索词必须是字符串");
        if(((String)raw).length()>100)throw new JSONException("搜索词最多 100 个字符");
        return trimSearch((String)raw);
    }
    private JSONArray searchKeywords() {
        JSONArray clean=new JSONArray();Set<String> seen=new HashSet<>();
        Object raw=preferences.getAll().get(SEARCH_KEY);
        if(!(raw instanceof String))return clean;
        try {
            JSONArray rows=new JSONArray((String)raw);
            for(int i=0;i<rows.length()&&clean.length()<SEARCH_LIMIT;i++) {
                Object value=rows.opt(i);if(!(value instanceof String))continue;
                String keyword=trimSearch((String)value);
                if(!keyword.isEmpty()&&keyword.length()<=100&&seen.add(keyword))clean.put(keyword);
            }
        }catch(JSONException ignored){/* Invalid legacy data is an empty history, not an app error. */}
        return clean;
    }
    JSONObject searchHistory() throws JSONException { synchronized(LibraryStore.class) { return new JSONObject().put("keywords",searchKeywords()); } }
    JSONObject addSearchHistory(JSONObject params) throws Exception {
        String keyword=submittedKeyword(params);
        synchronized(LibraryStore.class) {
            JSONArray old=searchKeywords();if(keyword.isEmpty())return new JSONObject().put("keywords",old);
            JSONArray next=new JSONArray().put(keyword);
            for(int i=0;i<old.length()&&next.length()<SEARCH_LIMIT;i++)if(!keyword.equals(old.getString(i)))next.put(old.getString(i));
            return saveSearchKeywords(next);
        }
    }
    JSONObject removeSearchHistory(JSONObject params) throws Exception {
        String keyword=submittedKeyword(params);
        synchronized(LibraryStore.class) {
            JSONArray old=searchKeywords(),next=new JSONArray();boolean found=false;
            for(int i=0;i<old.length();i++) {String value=old.getString(i);if(keyword.equals(value))found=true;else next.put(value);}
            return found?saveSearchKeywords(next):new JSONObject().put("keywords",old);
        }
    }
    JSONObject clearSearchHistory() throws Exception { synchronized(LibraryStore.class) { return saveSearchKeywords(new JSONArray()); } }
    private JSONObject saveSearchKeywords(JSONArray keywords) throws Exception {
        Map<String,?> old=preferences.getAll();Object previous=old.get(SEARCH_KEY);boolean committed=false;
        try {committed=preferences.edit().putString(SEARCH_KEY,keywords.toString()).commit();}
        finally {
            // Android commits update in-memory preferences even if their disk write fails.
            if(!committed)try {restoreSearchValue(previous).commit();}catch(RuntimeException ignored){ }
        }
        if(!committed)throw new IOException("搜索历史保存未成功");
        return new JSONObject().put("keywords",keywords);
    }
    private SharedPreferences.Editor restoreSearchValue(Object previous) {
        SharedPreferences.Editor editor=preferences.edit();
        if(previous instanceof String)editor.putString(SEARCH_KEY,(String)previous);
        else if(previous instanceof Boolean)editor.putBoolean(SEARCH_KEY,(Boolean)previous);
        else if(previous instanceof Integer)editor.putInt(SEARCH_KEY,(Integer)previous);
        else if(previous instanceof Long)editor.putLong(SEARCH_KEY,(Long)previous);
        else if(previous instanceof Float)editor.putFloat(SEARCH_KEY,(Float)previous);
        else if(previous instanceof Set) {Set<String> values=new HashSet<>();for(Object value:(Set<?>)previous)if(value instanceof String)values.add((String)value);editor.putStringSet(SEARCH_KEY,values);}
        else editor.remove(SEARCH_KEY);
        return editor;
    }
    /** Synchronous disk barrier for application replacement; call only off the UI thread. */
    static void checkpoint(Context context) throws IOException {
        synchronized (LibraryStore.class) {
            SharedPreferences prefs = context.getSharedPreferences("hongguo_library_v1", Context.MODE_PRIVATE);
            // A new generation snapshots the entire current preference map, including pending apply writes.
            if (!prefs.edit().putLong("update_checkpoint_at", System.currentTimeMillis()).commit())
                throw new IOException("本机观看记录保存未完成，请稍后重试更新");
        }
    }
    JSONObject library() throws JSONException { synchronized (LibraryStore.class) { JSONArray accountFavorites = accountHistory == null ? null : accountHistory.favorites(); JSONObject result = new JSONObject().put("favorites", accountFavorites == null ? read("favorites") : accountFavorites).put("history", historyRows()); JSONObject projection = accountHistory == null ? null : accountHistory.projection(); if (projection != null) result.put("projection", projection); return result; } }
    JSONObject toggle(JSONObject series) throws JSONException {
        synchronized (LibraryStore.class) {
            if (accountHistory != null && accountHistory.favorites() != null) { accountHistory.toggleFavorite(series); return library(); }
            JSONArray old = read("favorites"), next = new JSONArray(); boolean found = false;
            for (int i = 0; i < old.length(); i++) { JSONObject row = old.optJSONObject(i); if (row == null) continue; if (series.getString("seriesId").equals(row.optString("seriesId"))) found = true; else next.put(row); }
            if (!found) { if (next.length() >= 200) throw new JSONException("收藏已达 200 部上限"); next.put(new JSONObject(series.toString()).put("favoriteAt", Math.max(1, System.currentTimeMillis()))); }
            preferences.edit().putString("favorites", next.toString()).apply(); return library();
        }
    }
    void history(JSONObject series, JSONObject episode, long position) { history(series, episode, position, 0); }
    void history(JSONObject series, JSONObject episode, long position, long duration) {
        synchronized (LibraryStore.class) {
            try {
                JSONObject row = new JSONObject(series.toString()).put("vid", episode.getString("vid")).put("index", episode.getInt("index"))
                    .put("positionMs", Math.max(0, position)).put("durationMs", Math.max(0, Math.min(86_400_000L, duration))).put("updatedAt", System.currentTimeMillis());
                if(accountHistory!=null) { accountHistory.record(row);return; }
                JSONArray next = new JSONArray().put(row), old = read("history");
                for (int i = 0; i < old.length(); i++) {
                    JSONObject item = old.optJSONObject(i); if (item == null) continue;
                    if (series.getString("seriesId").equals(item.optString("seriesId"))) {
                        if (row.optLong("durationMs") == 0 && episode.getString("vid").equals(item.optString("vid"))) row.put("durationMs", Math.max(0, Math.min(86_400_000L, item.optLong("durationMs"))));
                    } else if (next.length() < 100) next.put(item);
                }
                preferences.edit().putString("history", next.toString()).apply();
            } catch (JSONException ignored) { }
        }
    }
    int position(JSONObject series, JSONObject episode) {
        synchronized (LibraryStore.class) {
            if (accountHistory != null) try { int position = accountHistory.position(series, episode); if (position >= 0) return position; } catch (JSONException failure) { return 0; }
            JSONArray rows;try{rows=historyRows();}catch(JSONException ignored){return 0;}
            for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.optJSONObject(i); if (row != null && row.optString("seriesId").equals(series.optString("seriesId")) && row.optString("vid").equals(episode.optString("vid"))) return (int) Math.min(Integer.MAX_VALUE, row.optLong("positionMs")); }
            return 0;
        }
    }
    JSONObject seriesHistory(String seriesId) throws JSONException {
        if (seriesId == null || !seriesId.matches("[0-9]{6,30}")) throw new JSONException("剧集身份无效");
        synchronized (LibraryStore.class) {
            if (accountHistory != null) { JSONObject direct = accountHistory.seriesHistory(seriesId); if (direct != null) return direct; }
            JSONArray rows = historyRows();
            for (int i=0;i<rows.length();i++) { JSONObject row=rows.optJSONObject(i); if (row!=null&&seriesId.equals(row.optString("seriesId"))) return new JSONObject(row.toString()); }
            return null;
        }
    }
    JSONArray downloads() { synchronized (LibraryStore.class) { return read("downloads"); } }
    void addDownload(long id, JSONObject series, JSONObject episode) throws JSONException {
        addDownload(id, series, episode, null);
    }
    void addManagedDownload(long id, JSONObject series, JSONObject episode, String filename) throws Exception {
        JSONObject row = new JSONObject().put("series",series).put("episode",episode);
        if (filename==null || !filename.matches(java.util.regex.Pattern.quote(preparedPrefix(row))+"[0-9]{10,17}\\.mp4")) throw new IOException("下载文件名无效");
        addDownload(id,series,episode,new JSONObject().put("downloadFile",filename));
    }
    void addPreparedDownload(long id, JSONObject series, JSONObject episode, File directory, File target) throws Exception {
        JSONObject row = new JSONObject().put("series",series).put("episode",episode);
        JSONObject metadata = preparedMetadata(directory,row,target);
        addDownload(id,series,episode,metadata);
    }
    private void addDownload(long id, JSONObject series, JSONObject episode, JSONObject prepared) throws JSONException {
        synchronized (LibraryStore.class) {
            JSONArray old = read("downloads"), next = new JSONArray();
            if (old.length() >= 200) throw new JSONException("下载记录已达 200 条上限");
            JSONObject row = new JSONObject().put("id", id).put("title", series.getString("title")).put("index", episode.getInt("index")).put("series", series).put("episode", episode);
            if (prepared != null && prepared.has("preparedFile")) row.put("preparedFile",prepared.getString("preparedFile")).put("preparedBytes",prepared.getLong("preparedBytes"));
            else if (prepared != null) row.put("downloadFile",prepared.getString("downloadFile"));
            next.put(row);
            for (int i = 0; i < old.length(); i++) next.put(old.get(i));
            if (!preferences.edit().putString("downloads", next.toString()).commit()) throw new JSONException("下载记录保存未成功");
        }
    }
    /** Upgrade only the requested legacy prepared record; never rename/delete a user's file. */
    JSONObject bindPreparedDownload(long id,File directory,File target) throws Exception {
        synchronized(LibraryStore.class) {
            JSONArray rows=read("downloads");JSONObject result=null;
            for(int i=0;i<rows.length();i++) {JSONObject row=rows.optJSONObject(i);if(row==null||row.optLong("id",-1)!=id)continue;
                JSONObject metadata=preparedMetadata(directory,row,target);
                row.put("preparedFile",metadata.getString("preparedFile")).put("preparedBytes",metadata.getLong("preparedBytes"));result=row;break;
            }
            if(result==null)throw new IOException("下载记录不存在");
            if(!preferences.edit().putString("downloads",rows.toString()).commit())throw new IOException("下载文件映射保存未成功");
            return result;
        }
    }
    /** Persist an exact retry target before any irreversible provider/file operation. */
    void stageDownloadDeletion(long id,String filename) throws Exception {
        synchronized (LibraryStore.class) {
            JSONArray rows=read("downloads");JSONObject selected=null;int matches=0;
            for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&row.optLong("id",-1)==id){selected=row;matches++;}}
            if(matches!=1)throw new IOException("下载记录不存在或身份重复");
            selected.put("deleting",true);
            if(filename!=null)selected.put("deletingFile",filename);
            commitDownloadDeletion(rows,null);
        }
    }
    boolean deletedDownload(long id) {
        synchronized (LibraryStore.class) {
            JSONArray ids=read("deleted_download_ids_v1");String expected=Long.toString(id);
            for(int i=0;i<ids.length();i++)if(expected.equals(ids.optString(i)))return true;
            return false;
        }
    }
    void finishDownloadDeletion(long id) throws Exception {
        synchronized (LibraryStore.class) {
            JSONArray rows=read("downloads"),next=new JSONArray();int matches=0;
            for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&row.optLong("id",-1)==id)matches++;else next.put(rows.get(i));}
            if(matches!=1)throw new IOException("下载记录已变化，请重新读取后重试");
            JSONArray ids=read("deleted_download_ids_v1"),deleted=new JSONArray().put(Long.toString(id));
            for(int i=0;i<ids.length()&&deleted.length()<1000;i++)if(!Long.toString(id).equals(ids.optString(i)))deleted.put(ids.get(i));
            commitDownloadDeletion(next,deleted);
        }
    }
    /** Android updates SharedPreferences memory even when disk commit returns false. */
    private void commitDownloadDeletion(JSONArray rows,JSONArray deleted) throws IOException {
        String previous=preferences.getString("downloads",null),previousDeleted=preferences.getString("deleted_download_ids_v1",null);
        try {
            SharedPreferences.Editor edit=preferences.edit().putString("downloads",rows.toString());
            if(deleted!=null)edit.putString("deleted_download_ids_v1",deleted.toString());
            if(edit.commit())return;
        }catch(RuntimeException ignored){ }
        try {
            SharedPreferences.Editor restore=preferences.edit();
            if(previous==null)restore.remove("downloads");else restore.putString("downloads",previous);
            if(previousDeleted==null)restore.remove("deleted_download_ids_v1");else restore.putString("deleted_download_ids_v1",previousDeleted);
            restore.commit();
        }catch(RuntimeException ignored){ }
        throw new IOException("下载删除状态保存未成功，请重试；已删除的视频文件不会恢复");
    }
    static File preparedFile(Context context,JSONObject row) {
        return preparedFile(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),row);
    }
    static File preparedFile(File directory,JSONObject row) {
        try {
            if(row==null||!(row.opt("preparedFile") instanceof String)||!(row.opt("preparedBytes") instanceof Number))return null;
            double value=((Number)row.opt("preparedBytes")).doubleValue();
            if(!Double.isFinite(value)||value!=Math.rint(value))return null;
            long bytes=row.getLong("preparedBytes");
            return ownedPreparedFile(directory,row,row.getString("preparedFile"),bytes);
        }catch(Exception ignored){return null;}
    }
    static String preparedPrefix(JSONObject row) throws Exception {
        JSONObject series=row.getJSONObject("series"),episode=episode(row.getJSONObject("episode"));
        Object sid=series.opt("seriesId");if(!(sid instanceof String)||!((String)sid).matches("[0-9]{6,30}"))throw new IOException("下载剧集信息无效");
        return sid+"_"+episode.getInt("index")+"_";
    }
    static boolean preparedName(JSONObject row,String name) throws Exception {
        String prefix=preparedPrefix(row);
        return name.startsWith(prefix)&&name.substring(prefix.length()).matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}\\.mp4");
    }
    private static File ownedPreparedFile(File directory,JSONObject row,String name,long expectedBytes) throws Exception {
        if(directory==null||!directory.isDirectory()||Files.isSymbolicLink(directory.toPath())||!preparedName(row,name)||expectedBytes<=0||expectedBytes>96L*1024*1024)return null;
        File file=new File(directory,name);
        if(Files.isSymbolicLink(file.toPath())||!directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())||!file.isFile()||!file.canRead()||file.length()!=expectedBytes||!completePreparedMp4(file))return null;
        return file;
    }
    static JSONObject preparedMetadata(File directory,JSONObject row,File target) throws Exception {
        if(target==null)throw new IOException("已准备的视频文件无效");
        File checked=ownedPreparedFile(directory,row,target.getName(),target.length());
        if(checked==null||!checked.getCanonicalFile().equals(target.getCanonicalFile()))throw new IOException("已准备的视频文件校验未通过");
        return new JSONObject().put("preparedFile",checked.getName()).put("preparedBytes",checked.length());
    }
    /** Recover pre-mapping addCompletedDownload records only from this module's UUID filenames. */
    static File recoverPreparedFile(File directory,JSONObject row,String localUri,long providerSize,String description) {
        try {
            if(!"红果 · 已准备的视频".equals(description)||directory==null||!directory.isDirectory()||Files.isSymbolicLink(directory.toPath()))return null;
            if(localUri!=null&&!localUri.isEmpty()) {
                URI uri=new URI(localUri);
                if("file".equals(uri.getScheme())&&uri.getRawAuthority()==null) {
                    File candidate=new File(uri);File owned=ownedPreparedFile(directory,row,candidate.getName(),providerSize>0?providerSize:candidate.length());
                    if(owned!=null&&owned.getCanonicalFile().equals(candidate.getCanonicalFile()))return owned;
                }
            }
            File[] files=directory.listFiles();if(files==null||files.length>512)return null;
            File found=null;
            for(File candidate:files) {
                if(!preparedName(row,candidate.getName()))continue;
                File owned=ownedPreparedFile(directory,row,candidate.getName(),providerSize>0?providerSize:candidate.length());
                if(owned==null)continue;if(found!=null)return null;found=owned;
            }
            return found;
        }catch(Exception ignored){return null;}
    }
    private static boolean completePreparedMp4(File file) {
        try(RandomAccessFile input=new RandomAccessFile(file,"r")) {
            long length=input.length(),at=0;boolean ftyp=false,moov=false,mdat=false;int boxes=0;
            while(at<length) {
                if(++boxes>4096||length-at<8)return false;input.seek(at);long size=input.readInt()&0xffffffffL;int type=input.readInt();long header=8;
                if(size==1){if(length-at<16)return false;size=input.readLong();header=16;}else if(size==0)size=length-at;
                if(size<header||size>length-at)return false;
                if(type==0x66747970){if(size<header+8)return false;ftyp=true;}
                if(type==0x6d6f6f76)moov=true;if(type==0x6d646174&&size>header)mdat=true;
                at+=size;
            }
            return ftyp&&moov&&mdat;
        }catch(Exception ignored){return false;}
    }
    JSONObject download(long id) { JSONArray rows = downloads(); for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.optJSONObject(i); if (row != null && row.optLong("id", -1) == id) return row; } return null; }
}
