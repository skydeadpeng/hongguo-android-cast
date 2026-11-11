package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Same-series lossless merge of completed downloads. Collection filters strictly and the
 * result stays in the app's downloads directory as an in-app entry; an incompatible mix
 * keeps its typed message from Mp4Concat instead of ever attempting a transcoding fallback.
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

    /**
     * Keeps the merged video inside the app's downloads directory under a strict
     * <seriesId>_merged_<uuid>.mp4 name so it stays playable and deletable in-app;
     * ROM galleries no longer receive a copy they may list confusingly.
     */
    static File relocate(File directory, File merged, String seriesId) throws IOException {
        if (merged == null || !merged.isFile() || merged.length() <= 0) throw new IOException("合并输出不存在");
        if (seriesId == null || !seriesId.matches("\\d{1,30}")) throw new IOException("剧集编号无效");
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new IOException("下载目录尚未准备");
        File target = new File(directory, seriesId + "_merged_" + UUID.randomUUID() + ".mp4");
        if (!merged.renameTo(target)) {
            try { java.nio.file.Files.copy(merged.toPath(), target.toPath()); }
            catch (IOException error) { target.delete(); throw new IOException("合并结果落盘失败", error); }
            if (!merged.delete()) { /* The temporary copy is cleaned up by the caller. */ }
        }
        return target;
    }

    /** Orders a mixed id list by the projected episode index; ids are validated by the caller. */
    static List<Long> orderedIds(List<Source> sources) {
        List<Long> ids = new ArrayList<>();
        for (Source source : sources) ids.add(source.id);
        return ids;
    }
}
