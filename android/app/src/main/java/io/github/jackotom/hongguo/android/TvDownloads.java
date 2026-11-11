package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native UI facade over the same owned downloads, preparation and deletion policies as the bridge. */
final class TvDownloads {
    private TvDownloads() { }
    private static DownloadManager manager(Context context) throws IOException {
        DownloadManager service = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (service == null) throw new IOException("系统下载服务暂不可用，请重试");
        return service;
    }
    /** Network and file preparation must run on the caller's background worker. Never lower a manual download's quality. */
    static long start(Context context, JSONObject inputSeries, JSONObject inputEpisode) throws Exception {
        return start(context, inputSeries, inputEpisode, PlayerOptions.read(context).getString("quality"));
    }
    static long start(Context context, JSONObject inputSeries, JSONObject inputEpisode, String requestedQuality) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("下载准备已取消");
        try (UpdateOperationGate.Lease held = UpdateInstallCheckpoint.operation()) {
            LibraryStore store = new LibraryStore(context);
            JSONObject series = LibraryStore.series(inputSeries), episode = LibraryStore.episode(inputEpisode);
            if (store.downloads().length() >= 200) throw new IOException("下载记录已达 200 条上限");
            String quality = PlayerOptions.quality(requestedQuality);
            PlaybackSources.Plan plan = PlaybackSources.resolve(series.getString("seriesId"), episode.getString("vid"), quality);
            if (!quality.equals(plan.requestedQuality) || !series.getString("seriesId").equals(plan.seriesId) || !episode.getString("vid").equals(plan.vid))
                throw new IOException("下载片源身份或清晰度不匹配");
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("下载准备已取消");
            DownloadManager service = manager(context);
            if (!plan.source.equals("web")) return prepared(context, service, store, series, episode, plan);
            String path = Uri.parse(plan.url).getPath();
            if (path != null && path.toLowerCase(Locale.ROOT).endsWith(".m3u8")) throw new IOException("此片源为流式播放列表，请在线播放；下载支持完整视频文件");
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("下载准备已取消");
            String filename = series.getString("seriesId") + "_" + episode.getInt("index") + "_" + System.currentTimeMillis() + ".mp4";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(plan.url))
                .setTitle(series.getString("title") + " · 第 " + episode.getInt("index") + " 集").setDescription("红果 · 视频下载")
                .addRequestHeader("User-Agent", plan.userAgent).addRequestHeader("Referer", plan.referer)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, filename);
            long id = service.enqueue(request);
            try { store.addManagedDownload(id, series, episode, filename); }
            catch (Exception error) { service.remove(id); throw error; }
            return id;
        }
    }
    private static long prepared(Context context, DownloadManager service, LibraryStore store, JSONObject series, JSONObject episode, PlaybackSources.Plan plan) throws Exception {
        PlaybackCache cache = PlaybackCache.get(context);
        String sid = series.getString("seriesId"), vid = episode.getString("vid"); int index = episode.getInt("index");
        File target = null; long id = -1;
        cache.protect(sid, vid, index, plan.requestedQuality);
        try {
            File ready = cache.fetch(sid, vid, index, plan, plan.requestedQuality);
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("下载准备已取消");
            File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new IOException("下载目录尚未准备");
            target = new File(directory, sid + "_" + index + "_" + UUID.randomUUID() + ".mp4");
            Files.copy(ready.toPath(), target.toPath());
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("下载准备已取消");
            id = service.addCompletedDownload(series.getString("title") + " · 第 " + index + " 集", "红果 · 已准备的视频", false, "video/mp4", target.getAbsolutePath(), target.length(), true);
            if (id < 0) throw new IOException("系统下载记录准备失败");
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("下载准备已取消");
            store.addPreparedDownload(id, series, episode, directory, target);
            return id;
        } catch (Exception error) {
            if (id >= 0) try { service.remove(id); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            if (target != null) try { Files.deleteIfExists(target.toPath()); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        } finally { cache.release(sid, vid, index, plan.requestedQuality); }
    }
    static JSONArray rows(Context context) throws Exception {
        LibraryStore store = new LibraryStore(context); DownloadManager service = manager(context);
        JSONArray records = store.downloads(), result = new JSONArray();
        for (int i = records.length() - 1; i >= 0; i--) {
            JSONObject raw = records.optJSONObject(i); if (raw == null) continue;
            JSONObject row = new JSONObject(raw.toString());
            String status = "failed"; long current = 0, total = -1;
            File owned = LibraryStore.preparedFile(context, row);
            if (owned != null) { status = "success"; current = total = owned.length(); }
            else if (!row.has("preparedFile")) {
                try (Cursor cursor = service.query(new DownloadManager.Query().setFilterById(row.getLong("id")))) {
                    if (cursor == null) throw new IOException("系统下载状态读取未完成，请重试");
                    if (cursor.moveToFirst()) {
                        int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        status = state == DownloadManager.STATUS_PENDING ? "pending" : state == DownloadManager.STATUS_RUNNING ? "running" : state == DownloadManager.STATUS_PAUSED ? "paused" : state == DownloadManager.STATUS_SUCCESSFUL ? "success" : "failed";
                        current = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        if (state == DownloadManager.STATUS_SUCCESSFUL) {
                            owned = recover(context, store, row, cursor);
                            if (owned != null) current = total = owned.length();
                            else if (managedUri(context, service, row.getLong("id")) == null) status = "failed";
                        }
                    }
                }
            }
            if (row.optBoolean("deleting")) status = "deleting";
            String label = status.equals("pending") ? "等待下载" : status.equals("running") ? "正在下载" : status.equals("paused") ? "已暂停" : status.equals("success") ? "已完成 · 可离线播放" : status.equals("deleting") ? "删除未完成 · 可重试" : "下载失败或文件已移除";
            row.put("status", status).put("statusLabel", label).put("downloadedBytes", Math.max(0, current)).put("totalBytes", total).put("playable", status.equals("success"));
            result.put(row);
        }
        return result;
    }
    static JSONObject delete(Context context, long id) throws Exception {
        DownloadManager service = manager(context); Object owner = new Object();
        try {
            UpdateInstallCheckpoint.prepareDownloadDeletion(context, owner);
            return DownloadDeletion.delete(new LibraryStore(context), context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), new DownloadDeletion.Provider() {
                public DownloadDeletion.Record query(long selected) throws Exception {
                    try (Cursor cursor = service.query(new DownloadManager.Query().setFilterById(selected))) {
                        if (cursor == null) throw new IOException("系统下载状态读取未完成，请重试");
                        if (!cursor.moveToFirst()) return null;
                        if (cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)) != selected) throw new IOException("系统下载身份不一致");
                        return new DownloadDeletion.Record(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)), text(cursor, DownloadManager.COLUMN_LOCAL_URI), text(cursor, DownloadManager.COLUMN_DESCRIPTION));
                    }
                }
                public int remove(long selected) { return service.remove(selected); }
            }, new JSONObject().put("id", id).put("confirmed", true));
        } finally { UpdateInstallCheckpoint.release(owner); }
    }
    static void play(Activity activity, JSONObject shown) {
        try {
            LibraryStore store = new LibraryStore(activity); DownloadManager service = manager(activity);
            JSONObject row = store.download(shown.getLong("id"));
            if (row == null || row.optBoolean("deleting")) throw new IOException("下载记录已变化，请刷新列表");
            Uri uri = downloadedUri(activity, service, store, row);
            if (uri == null) throw new IOException("下载尚未完成，或文件已被移除");
            JSONObject series = LibraryStore.series(row.getJSONObject("series")), episode = LibraryStore.episode(row.getJSONObject("episode"));
            JSONArray queue = new JSONArray(), records = store.downloads();
            for (int i = 0; i < records.length(); i++) {
                JSONObject candidate = records.optJSONObject(i);
                if (candidate == null || candidate.optBoolean("deleting") || candidate.optJSONObject("series") == null || !series.getString("seriesId").equals(candidate.getJSONObject("series").optString("seriesId"))) continue;
                if (downloadedUri(activity, service, store, candidate) != null) queue.put(LibraryStore.episode(candidate.getJSONObject("episode")));
            }
            TvPlay.launch(activity, PlayLauncher.build(activity, series, episode, LibraryStore.episodes(queue, episode), uri.toString(), true, new JSONObject()));
        } catch (Exception error) { TvUi.error(activity, TvUi.message(error), null); }
    }
    private static String text(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column); return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }
    private static File recover(Context context, LibraryStore store, JSONObject row, Cursor cursor) throws Exception {
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File owned = LibraryStore.recoverPreparedFile(directory, row, text(cursor, DownloadManager.COLUMN_LOCAL_URI), cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), text(cursor, DownloadManager.COLUMN_DESCRIPTION));
        if (owned != null) store.bindPreparedDownload(row.getLong("id"), directory, owned);
        return owned;
    }
    private static Uri downloadedUri(Context context, DownloadManager service, LibraryStore store, JSONObject row) throws Exception {
        if (row.has("preparedFile")) { File file = LibraryStore.preparedFile(context, row); return file == null ? null : Uri.fromFile(file); }
        try (Cursor cursor = service.query(new DownloadManager.Query().setFilterById(row.getLong("id")))) {
            if (cursor == null) throw new IOException("系统下载状态读取未完成，请重试");
            if (!cursor.moveToFirst() || cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) return null;
            File recovered = recover(context, store, row, cursor); if (recovered != null) return Uri.fromFile(recovered);
        }
        return managedUri(context, service, row.getLong("id"));
    }
    private static Uri managedUri(Context context, DownloadManager service, long id) {
        try {
            Uri uri = service.getUriForDownloadedFile(id);
            if (uri == null || !"content".equals(uri.getScheme())) return null;
            try (ParcelFileDescriptor file = context.getContentResolver().openFileDescriptor(uri, "r")) { return file == null ? null : uri; }
        } catch (Exception ignored) { return null; }
    }
}
