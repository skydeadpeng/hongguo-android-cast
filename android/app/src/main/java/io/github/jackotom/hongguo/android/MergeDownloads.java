package io.github.jackotom.hongguo.android;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Same-series lossless merge of completed downloads. Collection filters strictly and the
 * export lands where the user can reach it; an incompatible mix keeps its typed message
 * from Mp4Concat instead of ever attempting a transcoding fallback.
 */
final class MergeDownloads {
    static final int MAX_EPISODES = 100;

    static final class Source {
        final long id;final int index;final File file;
        Source(long id,int index,File file){this.id=id;this.index=index;this.file=file;}
    }

    private MergeDownloads() { }

    /** Collects completed files of one series: deduped by episode index, sorted ascending. */
    static List<Source> collect(Context context, JSONArray rows, String seriesId) throws IOException {
        if (seriesId == null || !seriesId.matches("\\d{1,30}")) throw new IOException("剧集编号无效");
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        Map<Integer, Source> byIndex = new HashMap<>();
        LinkedHashSet<String> problems = new LinkedHashSet<>();
        for (int at = 0; rows != null && at < rows.length(); at++) {
            JSONObject row = rows.optJSONObject(at);
            if (row == null) continue;
            JSONObject series = row.optJSONObject("series");
            if (series == null || !seriesId.equals(series.optString("seriesId"))) continue;
            int index = row.optInt("index", -1);
            if (index < 1) continue;
            File file = fileOf(directory, row);
            if (file == null || !file.isFile() || file.length() <= 0) { problems.add("第 " + index + " 集的文件不存在"); continue; }
            Source source = new Source(row.optLong("id", -1), index, file);
            Source existing = byIndex.get(index);
            // The same episode may have been downloaded twice; the newest record wins.
            if (existing == null || source.id > existing.id) byIndex.put(index, source);
        }
        if (byIndex.isEmpty()) {
            IOException failure = new IOException("该剧还没有已完成的下载");
            for (String problem : problems) failure.addSuppressed(new IOException(problem));
            throw failure;
        }
        List<Source> ordered = new ArrayList<>(byIndex.values());
        ordered.sort(Comparator.comparingInt(source -> source.index));
        if (ordered.size() < 2) throw new IOException("合并至少需要两集已完成的下载");
        if (ordered.size() > MAX_EPISODES) throw new IOException("一次最多合并 " + MAX_EPISODES + " 集");
        return ordered;
    }

    private static File fileOf(File directory, JSONObject row) {
        if (directory == null) return null;
        File prepared = LibraryStore.preparedFile(directory, row);
        if (prepared != null) return prepared;
        String download = row.optString("downloadFile", "");
        if (!download.isEmpty() && !download.contains("/") && !download.contains("\\") && download.endsWith(".mp4")) {
            File file = new File(directory, download);
            if (file.isFile()) return file;
        }
        return null;
    }

    /** A filesystem-safe merged name; collisions are resolved at export time. */
    static String outputName(String seriesTitle, String seriesId) {
        String base = String.valueOf(seriesTitle == null ? "" : seriesTitle).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (base.isEmpty()) base = "短剧 " + seriesId;
        if (base.length() > 80) base = base.substring(0, 80).trim();
        return base + "_合并.mp4";
    }

    /**
     * Publishes the merged file: MediaStore Movies/Hongguo on API 29+, the app's own
     * downloads directory below that. Returns a user-presentable location string.
     */
    static Export export(Context context, File merged, String name) throws IOException {
        if (merged == null || !merged.isFile() || merged.length() <= 0) throw new IOException("合并输出不存在");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Hongguo");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri target = context.getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (target == null) throw new IOException("合并结果写入系统媒体库未成功");
            try (java.io.OutputStream output = context.getContentResolver().openOutputStream(target)) {
                if (output == null) throw new IOException("合并结果写入系统媒体库未成功");
                java.nio.file.Files.copy(merged.toPath(), output);
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            context.getContentResolver().update(target, values, null, null);
            return new Export(target, "Movies/Hongguo/" + name);
        }
        File directory = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "merged");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建合并输出目录");
        File target = new File(directory, uniqueName(directory, name));
        if (!merged.renameTo(target)) {
            if (!target.delete() || !merged.renameTo(target)) {
                try { java.nio.file.Files.copy(merged.toPath(), target.toPath()); } catch (IOException error) { throw new IOException("合并结果落盘失败", error); }
                if (!merged.delete()) { /* The temporary copy is cleaned up by the caller. */ }
            }
        }
        return new Export(Uri.fromFile(target), target.getAbsolutePath());
    }

    private static String uniqueName(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.isFile()) return name;
        String base = name.endsWith(".mp4") ? name.substring(0, name.length() - 4) : name;
        for (int at = 1; at < 1000; at++) {
            File numbered = new File(directory, base + " (" + at + ").mp4");
            if (!numbered.isFile()) return numbered.getName();
        }
        return base + " " + System.currentTimeMillis() + ".mp4";
    }

    /** Where the merged file landed and how to describe it to the user. */
    static final class Export {
        final Uri uri;final String location;
        Export(Uri uri,String location){this.uri=uri;this.location=location;}
    }

    /** Orders a mixed id list by the projected episode index; ids are validated by the caller. */
    static List<Long> orderedIds(List<Source> sources) {
        List<Long> ids = new ArrayList<>();
        for (Source source : sources) ids.add(source.id);
        return ids;
    }
}
