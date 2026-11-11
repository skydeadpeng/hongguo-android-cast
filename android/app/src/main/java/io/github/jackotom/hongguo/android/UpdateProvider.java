package io.github.jackotom.hongguo.android;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants only a read-only descriptor for the private update APK, never other app files. */
public final class UpdateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        if (getContext() == null || !"content".equals(uri.getScheme()) || !(getContext().getPackageName() + ".updates").equals(uri.getAuthority()) || !"/apk".equals(uri.getPath()) || uri.getQuery() != null || uri.getFragment() != null) throw new FileNotFoundException("更新文件地址无效");
        File value = new File(getContext().getFilesDir(), "updates/verified.apk");
        if (!value.isFile()) throw new FileNotFoundException("更新包已清理，请重新下载");
        try { File root = new File(getContext().getFilesDir(), "updates").getCanonicalFile(); if (!value.getCanonicalFile().equals(new File(root, "verified.apk"))) throw new FileNotFoundException("更新文件位置无效"); } catch (IOException invalid) { throw new FileNotFoundException("更新文件位置无效"); }
        return value;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("更新包仅支持读取");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { try { file(uri); return "application/vnd.android.package-archive"; } catch (FileNotFoundException invalid) { return null; } }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File value = file(uri); String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1); Object[] row = new Object[columns.length];
            for (int i=0;i<columns.length;i++) { if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "Hongguo-update.apk"; else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = value.length(); }
            cursor.addRow(row); return cursor;
        } catch (FileNotFoundException invalid) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("read only"); }
}
