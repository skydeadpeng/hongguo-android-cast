package io.github.jackotom.hongguo.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Grants read-only access to completed download MP4s so an external player (MX Player/VLC,
 *  which decode H.265 in software) can play what the device cannot. Exposes exactly two shapes:
 *  /media?id=<download id> for one verified file, and /playlist?sid=<series id> which streams a
 *  generated m3u listing every completed episode of that series in order, so players queue them
 *  back to back. Both paths reuse the same integrity checks (path, symlink, size, complete MP4)
 *  the in-app player enforces; nothing else is reachable. */
public final class MediaProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private static boolean guarded(Uri uri, String path) {
        return uri != null && "content".equals(uri.getScheme()) && (uri.getAuthority() != null && uri.getAuthority().endsWith(".media"))
            && path.equals(uri.getPath()) && uri.getFragment() == null;
    }
    private File file(Uri uri) throws FileNotFoundException {
        if (getContext() == null || !guarded(uri, "/media")) throw new FileNotFoundException("外部播放地址无效");
        String id = uri.getQueryParameter("id");
        if (id == null || !id.matches("[0-9]{1,12}")) throw new FileNotFoundException("外部播放记录无效");
        File value;
        try {
            JSONObject row = new LibraryStore(getContext()).download(Long.parseLong(id));
            value = row == null ? null : LibraryStore.preparedFile(getContext(), row);
        } catch (Exception invalid) { value = null; }
        if (value == null || value.length() <= 0) throw new FileNotFoundException("本集下载已失效，请重新下载后再用外部播放器打开");
        return value;
    }
    /** Collects every completed episode of the series, ordered by episode index. */
    private List<JSONObject> seriesPlaylist(String sid) throws FileNotFoundException {
        if (sid == null || !sid.matches("[0-9]{6,30}")) throw new FileNotFoundException("外部播放剧集无效");
        List<JSONObject> rows = new ArrayList<>();
        try {
            JSONArray records = new LibraryStore(getContext()).downloads();
            for (int i = 0; i < records.length(); i++) {
                JSONObject row = records.optJSONObject(i);
                if (row == null || row.optBoolean("deleting") || row.optJSONObject("series") == null || row.optJSONObject("episode") == null) continue;
                if (!sid.equals(row.optJSONObject("series").optString("seriesId"))) continue;
                if (LibraryStore.preparedFile(getContext(), row) == null) continue;
                rows.add(row);
            }
        } catch (Exception invalid) { throw new FileNotFoundException("外部播放列表读取未完成"); }
        if (rows.isEmpty()) throw new FileNotFoundException("该剧暂无已下载完成的分集");
        Collections.sort(rows, (a, b) -> {
            int ai = a.optJSONObject("episode") == null ? 0 : a.optJSONObject("episode").optInt("index");
            int bi = b.optJSONObject("episode") == null ? 0 : b.optJSONObject("episode").optInt("index");
            return Integer.compare(bi, ai);
        });
        return rows;
    }
    private ParcelFileDescriptor playlist(Uri uri) throws FileNotFoundException {
        if (!guarded(uri, "/playlist")) throw new FileNotFoundException("外部播放地址无效");
        List<JSONObject> rows = seriesPlaylist(uri.getQueryParameter("sid"));
        StringBuilder m3u = new StringBuilder("#EXTM3U\n");
        String title = rows.get(0).optJSONObject("series") == null ? "红果短剧" : rows.get(0).optJSONObject("series").optString("title", "红果短剧");
        File directory = getContext().getExternalFilesDir("Download");
        for (int i = rows.size() - 1; i >= 0; i--) {
            JSONObject row = rows.get(i);
            JSONObject series = row.optJSONObject("series"), episode = row.optJSONObject("episode");
            m3u.append("#EXTINF:-1,").append(title).append(" · 第 ").append(episode == null ? 0 : episode.optInt("index")).append(" 集\n");
            File value = LibraryStore.preparedFile(directory, row);
            m3u.append(value == null ? "" : Uri.fromFile(value).toString()).append('\n');
        }
        byte[] payload = m3u.toString().getBytes(StandardCharsets.UTF_8);
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor reader = pipe[0];
            new Thread(() -> { try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { out.write(payload); } catch (IOException ignored) { } }, "MediaProvider-m3u").start();
            return reader;
        } catch (IOException failed) { throw new FileNotFoundException("外部播放列表未生成"); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("外部播放仅支持读取");
        if (guarded(uri, "/playlist")) return playlist(uri);
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "video/mp4"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File value = file(uri); String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1); Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) { if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = value.getName(); else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = value.length(); }
            cursor.addRow(row); return cursor;
        } catch (FileNotFoundException invalid) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("read only"); }
}
