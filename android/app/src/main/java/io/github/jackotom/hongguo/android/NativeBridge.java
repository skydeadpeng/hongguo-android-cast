package io.github.jackotom.hongguo.android;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.webkit.JavascriptInterface;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

final class NativeBridge {
    private final MainActivity activity;
    private volatile boolean closed;
    private final LibraryStore store;
    private final DownloadManager downloads;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    // Page races have their own bounded lane and never queue behind media preparation.
    private final ThreadPoolExecutor httpWorker = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
    private final ConcurrentHashMap<String, FutureTask<Void>> httpRequests = new ConcurrentHashMap<>();
    // Registered playback metadata never queues behind a media download or login.
    private final ThreadPoolExecutor modelWorker = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final ConcurrentHashMap<String, RegisteredPlaybackModel> modelCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FutureTask<Void>> modelTasks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor downloadWorker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    // Slow search/registration must not occupy playback's request workers.
    // No queued searches: reject overload promptly rather than exceed the JS deadline.
    private final ThreadPoolExecutor searchWorker = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    // Login/cloud control never occupies source or media request workers.
    private final ThreadPoolExecutor accountWorker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList("getInfo", "httpGet", "cancelHttp", "appRequest", "appRegisteredModel", "appResolveResult", "getLibrary", "getSeriesHistory", "toggleFavorite", "play", "download", "getDownloads", "deleteDownload", "playDownload", "getPlaybackCache", "clearPlaybackCache", "getPlaybackOptions", "setPlaybackOptions", "getSearchHistory", "addSearchHistory", "removeSearchHistory", "clearSearchHistory", "searchQuery", "openExternal", "getAccount", "officialSendCode", "officialLoginSMS", "officialVerifyMFA", "officialCancelLogin", "officialLogout", "officialSync", "officialImportGuest", "officialWebQrcode", "officialWebPoll", "officialWebLogout", "officialWebCancel", "getAnnouncements", "searchNewReleases", "mergeDownloads"));
    NativeBridge(MainActivity activity) { this.activity = activity; store = new LibraryStore(activity); downloads = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE); }
    @JavascriptInterface public void postMessage(String message) {
        if (message == null || message.length() > 256 * 1024 || message.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) return;
        final JSONObject request;
        final Object id;
        final String method;
        final JSONObject params;
        try {
            request = new JSONObject(message); id = request.get("id"); method = request.getString("method");
            if (!(id instanceof String || id instanceof Number) || String.valueOf(id).length() > 128 || !METHODS.contains(method)) throw new JSONException("请求无效");
            params = request.optJSONObject("params") == null ? new JSONObject() : request.getJSONObject("params");
        } catch (JSONException error) { return; }
        activity.runOnUiThread(() -> {
            if (closed || !activity.trusted()) return;
            // Control callbacks never enter a network queue; only registered page IDs are cancellable.
            if (method.equals("cancelHttp")) {
                Object requestId = params.opt("requestId");
                boolean cancelled = requestId instanceof String && (cancelHttp((String) requestId) | cancelRegisteredModel((String) requestId));
                try { respond(id, new JSONObject().put("cancelled", cancelled), null); } catch (JSONException ignored) { }
                return;
            }
            // Cancellation closes the active account socket immediately, never behind MFA polling.
            if (method.equals("officialCancelLogin")) {
                try { respond(id, "background".equals(params.optString("reason")) ? AccountSync.get().suspendLogin() : AccountSync.get().cancelLogin(), null); } catch (Exception error) { respond(id, null, "取消登录未完成，请重试"); }
                return;
            }
            if (method.equals("httpGet")) { startHttp(id, params); return; }
            if (method.equals("appRegisteredModel")) { startRegisteredModel(id, params); return; }
            // Completion callbacks must not wait behind HTTP or full-media preparation.
            if (method.equals("appResolveResult")) {
                try { handle(id, method, params); } catch (Exception error) { respond(id, null, "分集结果不匹配"); }
                return;
            }
            final AccountSync.Selection favoriteSelection;
            final LibraryStore favoriteStore;
            if (method.equals("toggleFavorite")) {
                try {
                    favoriteSelection = AccountSync.get().captureSelection();
                    favoriteStore = new LibraryStore(activity);
                    AccountSync.get().requireCurrent(favoriteSelection);
                } catch (Exception error) { respond(id, null, "账号正在切换，请稍后重试收藏"); return; }
            } else { favoriteSelection = null; favoriteStore = null; }
            ThreadPoolExecutor executor = method.startsWith("official") ? accountWorker : method.equals("searchQuery") || method.equals("searchNewReleases") ? searchWorker : method.equals("download") ? downloadWorker : worker;
            try { executor.execute(() -> { try (AutoCloseable updateLease = (method.equals("download") || method.equals("playDownload")) ? UpdateInstallCheckpoint.operation() : null) { handle(id, method, params, favoriteStore, favoriteSelection); } catch (Exception error) { respond(id, null, error.getMessage() == null ? "操作未成功，请重试" : error.getMessage()); } }); }
            catch (RejectedExecutionException error) { respond(id, null, "请求较多，请稍后重试"); }
        });
    }
    private void startRegisteredModel(Object id, JSONObject params) {
        final String key = String.valueOf(id), vid;
        try { vid = RegisteredPlaybackModel.requestedVid(params); }
        catch (Exception invalid) { respondHttpError(id, "分集请求信息无效", "INVALID_INPUT"); return; }
        if (closed) return;
        final RegisteredPlaybackModel operation = new RegisteredPlaybackModel();
        if (modelCalls.putIfAbsent(key, operation) != null) { operation.close(); respondHttpError(id, "片源请求编号重复", "INVALID_INPUT"); return; }
        FutureTask<Void> task = new FutureTask<Void>(() -> {
            JSONObject result = null; String error = null, code = null;
            try { result = operation.fetch(vid); }
            catch (Exception failure) {
                code = failure instanceof java.net.SocketTimeoutException ? "ETIMEDOUT" : failure instanceof java.io.InterruptedIOException ? "ERR_CANCELED" : failure instanceof RegisteredPlaybackModel.ModelError ? ((RegisteredPlaybackModel.ModelError) failure).code : "APP_INVALID_RESPONSE";
                error = code.equals("ETIMEDOUT") ? "App片源登记和取源超时，请重试" : code.equals("ERR_CANCELED") ? "App片源请求已取消" : failure instanceof RegisteredPlaybackModel.ModelError ? failure.getMessage() : "App片源登记或取源未完成，请重试";
            }
            final JSONObject ready = result; final String message = error, errorCode = code;
            activity.runOnUiThread(() -> {
                try {
                    if (!closed && operation.current() && modelCalls.get(key) == operation) {
                        JSONObject response = new JSONObject().put("id", id).put("result", ready == null ? JSONObject.NULL : ready).put("error", message == null ? JSONObject.NULL : message);
                        if (errorCode != null) response.put("errorCode", errorCode);
                        activity.script("window.__androidResponse && window.__androidResponse(" + response.toString() + ")");
                    }
                } catch (JSONException ignored) { }
                finally { modelCalls.remove(key, operation); operation.close(); }
            });
            return null;
        }) { @Override protected void done() { modelTasks.remove(key, this); } };
        modelTasks.put(key, task);
        if (closed || modelCalls.get(key) != operation) { cancelRegisteredModel(key); return; }
        try { modelWorker.execute(task); }
        catch (RejectedExecutionException busy) { modelTasks.remove(key, task); modelCalls.remove(key, operation); operation.close(); task.cancel(true); respondHttpError(id, "片源请求较多，请稍后重试", "ETIMEDOUT"); }
    }
    private boolean cancelRegisteredModel(String id) {
        RegisteredPlaybackModel operation = modelCalls.remove(id); FutureTask<Void> task = modelTasks.remove(id);
        if (operation != null) operation.close(); if (task != null) task.cancel(true);
        return operation != null || task != null;
    }
    private void closeRegisteredModels() {
        for (String id : new ArrayList<>(modelCalls.keySet())) cancelRegisteredModel(id);
        for (FutureTask<Void> task : modelTasks.values()) task.cancel(true);
        modelCalls.clear(); modelTasks.clear(); modelWorker.shutdownNow();
    }
    private static long httpInteger(JSONObject params, String key, long fallback) throws Exception {
        if (!params.has(key)) return fallback;
        Object value = params.opt(key);
        if (!(value instanceof Number)) throw new Exception("页面请求等待期限无效");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < 1 || number > 9007199254740991d) throw new Exception("页面请求等待期限无效");
        return ((Number) value).longValue();
    }
    private void startHttp(Object id, JSONObject params) {
        final String key = String.valueOf(id), url;
        final long deadline;
        try {
            url = LibraryStore.text(params, "url", 4096);
            deadline = NetworkGuard.pageDeadline(httpInteger(params, "timeoutMs", 25000), httpInteger(params, "deadlineMs", 0));
        } catch (Exception error) {
            respondHttpError(id, error.getMessage(), error instanceof java.net.SocketTimeoutException ? "ETIMEDOUT" : "INVALID_INPUT");
            return;
        }
        FutureTask<Void> task = new FutureTask<Void>(() -> {
            try {
                if (closed) return null;
                NetworkGuard.Reply reply = NetworkGuard.get(url, deadline);
                if (!Thread.currentThread().isInterrupted()) respond(id, new JSONObject().put("body", reply.body).put("status", reply.status).put("url", reply.url), null);
            } catch (Exception error) {
                String code = Thread.currentThread().isInterrupted() ? "ERR_CANCELED" :
                    error instanceof java.net.SocketTimeoutException || System.nanoTime() >= deadline ? "ETIMEDOUT" :
                    error instanceof java.io.InterruptedIOException ? "ERR_CANCELED" : null;
                respondHttpError(id, error.getMessage() == null ? "页面请求未成功，请重试" : error.getMessage(), code);
            }
            return null;
        }) {
            @Override protected void done() { httpRequests.remove(key, this); httpWorker.remove(this); }
        };
        if (closed) { task.cancel(true); return; }
        if (httpRequests.putIfAbsent(key, task) != null) { respondHttpError(id, "页面请求编号重复", "INVALID_INPUT"); return; }
        try { httpWorker.execute(task); }
        catch (RejectedExecutionException error) { task.cancel(true); respondHttpError(id, "页面请求较多，请稍后重试", null); }
    }
    private boolean cancelHttp(String id) {
        FutureTask<Void> task = httpRequests.remove(id);
        return task != null && task.cancel(true);
    }
    private void closeHttp() {
        for (FutureTask<Void> task : httpRequests.values()) task.cancel(true);
        httpRequests.clear();
        httpWorker.shutdownNow();
    }
    private void respondHttpError(Object id, String message, String code) {
        if (closed) return;
        try {
            JSONObject response = new JSONObject().put("id", id).put("result", JSONObject.NULL).put("error", message == null ? "页面请求未成功，请重试" : message);
            if (code != null) response.put("errorCode", code);
            final String source = "window.__androidResponse && window.__androidResponse(" + response.toString() + ")";
            activity.runOnUiThread(() -> { if (!closed) activity.script(source); });
        } catch (JSONException ignored) { }
    }
    private JSONObject currentSeriesHistory(JSONObject params) throws Exception {
        Object raw = params.opt("seriesId");
        if (!(raw instanceof String) || !((String) raw).matches("[0-9]{6,30}")) throw new JSONException("剧集身份无效");
        AccountSync.Selection selection = AccountSync.get().captureSelection();
        String before = AccountSync.get().ownerStamp();
        JSONObject row = new LibraryStore(activity).seriesHistory((String) raw);
        AccountSync.get().requireCurrent(selection);
        if (!before.equals(AccountSync.get().ownerStamp())) throw new JSONException("账号已切换，请重新读取本剧进度");
        return new JSONObject().put("owner", before).put("history", row == null ? JSONObject.NULL : row);
    }
    private JSONObject currentLibrary() throws Exception {
        AccountSync.Selection selection = AccountSync.get().captureSelection();
        String before = AccountSync.get().ownerStamp();
        JSONObject result = new LibraryStore(activity).library();
        AccountSync.get().requireCurrent(selection);
        if (!before.equals(AccountSync.get().ownerStamp())) throw new JSONException("账号已切换，请重新读取记录");
        return result.put("owner", before);
    }
    /** One fresh anonymous identity per lookup; the new-release endpoint needs no account. */
    private JSONObject newReleases(JSONObject params) throws Exception {
        int pages = Math.max(1, Math.min(2, params == null ? 1 : params.optInt("pages", 1)));
        long deadline = System.nanoTime() + 20_000_000_000L;
        try (OfficialApiClient client = new OfficialApiClient(OfficialApiClient.newSession())) {
            client.deadlineNanos(deadline);
            client.registerDevice();
            return new JSONObject().put("items", NewReleaseCatalog.fetch(client, pages, deadline));
        }
    }
    private final java.util.concurrent.atomic.AtomicBoolean mergeBusy = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static String seriesTitle(JSONArray records, String seriesId) {
        for (int at = 0; at < records.length(); at++) {
            JSONObject row = records.optJSONObject(at);
            JSONObject series = row == null ? null : row.optJSONObject("series");
            if (series != null && seriesId.equals(series.optString("seriesId")) && !row.optString("title", "").isEmpty()) return row.optString("title");
        }
        return "短剧 " + seriesId;
    }
    private void handle(Object id, String method, JSONObject params) throws Exception { handle(id, method, params, null, null); }
    private void handle(Object id, String method, JSONObject params, LibraryStore favoriteStore, AccountSync.Selection favoriteSelection) throws Exception {
        if (closed) return;
        switch (method) {
            case "getAccount": respond(id, AccountSync.get().getAccount(), null); break;
            case "officialSendCode": respond(id, AccountSync.get().sendCode(params), null); break;
            case "officialLoginSMS": respond(id, AccountSync.get().loginSMS(params), null); break;
            case "officialVerifyMFA": respond(id, AccountSync.get().verifyMfa(), null); break;
            case "officialLogout": respond(id, AccountSync.get().logout(), null); break;
            case "officialSync": respond(id, AccountSync.get().syncNow(), null); break;
            case "officialImportGuest": respond(id, AccountSync.get().importGuest(), null); break;
            case "officialWebQrcode": respond(id, AccountSync.get().webQrcode(), null); break;
            case "officialWebPoll": respond(id, AccountSync.get().webPoll(), null); break;
            case "officialWebLogout": respond(id, AccountSync.get().webLogout(), null); break;
            case "officialWebCancel": respond(id, AccountSync.get().webCancel(), null); break;
            case "getAnnouncements": respond(id, AnnouncementClient.fetch(), null); break;
            case "searchNewReleases": respond(id, newReleases(params), null); break;
            case "getPlaybackOptions": respond(id, PlayerOptions.read(activity), null); break;
            case "setPlaybackOptions": respond(id, PlayerOptions.update(activity, params), null); break;
            case "getSearchHistory": respond(id, store.searchHistory(), null); break;
            case "addSearchHistory": respond(id, store.addSearchHistory(params), null); break;
            case "removeSearchHistory": respond(id, store.removeSearchHistory(params), null); break;
            case "clearSearchHistory": respond(id, store.clearSearchHistory(), null); break;
            case "searchQuery": respond(id, SearchClient.search(activity, params), null); break;
            case "getInfo": respond(id, new JSONObject().put("version", BuildConfig.VERSION_NAME).put("isTv", BuildConfig.IS_TV).put("platform", "android"), null); break;
            case "appRequest": respond(id, AppTransport.request(params), null); break;
            case "appResolveResult": PlaybackSources.result(params); respond(id, new JSONObject().put("accepted", true), null); break;
            // Library reads remain on-device; the retired sync account is not selected.
            case "getLibrary": respond(id, currentLibrary(), null); break;
            case "getSeriesHistory": respond(id, currentSeriesHistory(params), null); break;
            case "toggleFavorite": AccountSync.get().requireCurrent(favoriteSelection); if (favoriteStore == null) throw new JSONException("收藏操作缺少账号归属"); favoriteStore.toggle(LibraryStore.series(params.getJSONObject("series"))); respond(id, currentLibrary(), null); break;
            case "getDownloads": respond(id, downloadRows(), null); break;
            case "mergeDownloads": {
                final String seriesId = LibraryStore.text(params, "seriesId", 30);
                if (!seriesId.matches("\\d{1,30}")) throw new Exception("剧集编号无效");
                if (!mergeBusy.compareAndSet(false, true)) { respond(id, null, "已有一次合并正在进行，请等待完成"); break; }
                final JSONArray records = store.downloads();
                worker.execute(() -> {
                    File merged = null;
                    try {
                        java.util.List<MergeDownloads.Source> sources = MergeDownloads.collect(activity, records, seriesId);
                        File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new Exception("下载目录尚未准备");
                        merged = File.createTempFile("merge-", ".mp4", directory);
                        Mp4Concat.concat(sources.stream().map(source -> source.file).collect(java.util.stream.Collectors.toList()), merged);
                        String title = sources.get(0).file.getName().startsWith(seriesId) ? seriesTitle(records, seriesId) : seriesTitle(records, seriesId);
                        MergeDownloads.Export export = MergeDownloads.export(activity, merged, MergeDownloads.outputName(seriesTitle(records, seriesId), seriesId));
                        merged = null;
                        respond(id, new JSONObject().put("exported", true).put("episodes", sources.size()).put("location", export.location).put("uri", export.uri.toString()), null);
                    } catch (Exception error) {
                        respond(id, null, error.getMessage() == null ? "合并未完成，请重试" : error.getMessage());
                    } finally {
                        mergeBusy.set(false);
                        if (merged != null && merged.exists() && !merged.delete()) { /* A stale temporary is cleaned up by the next merge. */ }
                    }
                });
                break;
            }
            case "deleteDownload": {
                DownloadDeletion.confirmedId(params);
                Object owner=new Object();
                try {
                    UpdateInstallCheckpoint.prepareDownloadDeletion(activity,owner);
                    respond(id,DownloadDeletion.delete(store,activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),new DownloadDeletion.Provider() {
                        public DownloadDeletion.Record query(long downloadId) throws Exception {
                            if(downloads==null)throw new java.io.IOException("系统下载服务暂不可用，请重试");
                            try(Cursor cursor=downloads.query(new DownloadManager.Query().setFilterById(downloadId))) {
                                if(cursor==null)throw new java.io.IOException("系统下载状态读取未完成，请重试");
                                if(!cursor.moveToFirst())return null;
                                if(cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))!=downloadId)throw new java.io.IOException("系统下载身份不一致");
                                int uriColumn=cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),descriptionColumn=cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION);
                                return new DownloadDeletion.Record(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),cursor.isNull(uriColumn)?null:cursor.getString(uriColumn),cursor.isNull(descriptionColumn)?null:cursor.getString(descriptionColumn));
                            }
                        }
                        public int remove(long downloadId) {return downloads.remove(downloadId);}
                    },params),null);
                } finally {UpdateInstallCheckpoint.release(owner);}
                break;
            }
            case "getPlaybackCache": respond(id, PlaybackCache.get(activity).status(), null); break;
            case "clearPlaybackCache": respond(id, PlaybackCache.get(activity).clear(), null); break;
            case "play": {
                JSONObject series = LibraryStore.series(params.getJSONObject("series")), episode = LibraryStore.episode(params.getJSONObject("episode"));
                Object rawQueue = params.opt("episodes");
                if (rawQueue != null && rawQueue != JSONObject.NULL && !(rawQueue instanceof JSONArray)) throw new JSONException("连续播放队列无效");
                JSONArray queue = LibraryStore.episodes(params.optJSONArray("episodes"), episode);
                if (params.has("playlist")) {
                    if (!(params.opt("playlist") instanceof JSONObject) || !params.optBoolean("resolveInPlayer", false)) throw new JSONException("跨季播放清单无效");
                    if (params.toString().getBytes(StandardCharsets.UTF_8).length > 240 * 1024) throw new JSONException("播放清单超过大小上限，请减少连播季数");
                    PlaybackPlaylist.parse(params.getJSONObject("playlist"), series, episode);
                }
                // Source preparation belongs to the player so first play, episode selection
                // and continuous playback share the same bounded quality recovery.
                if (params.has("resolveInPlayer") && !(params.opt("resolveInPlayer") instanceof Boolean)) throw new JSONException("播放准备方式无效");
                if (params.optBoolean("resolveInPlayer", false)) {
                    if (params.has("url") || params.has("playInfo")) throw new JSONException("播放准备参数冲突");
                    JSONObject defaults = PlayerOptions.read(activity);
                    Object requested = params.has("quality") ? params.get("quality") : defaults.get("quality");
                    if (!(requested instanceof String)) throw new JSONException("清晰度无效");
                    params.put("quality", MediaQuality.normalize((String) requested));
                    if (!params.has("speed")) params.put("speed", defaults.getDouble("speed"));
                    if (!(params.opt("speed") instanceof Number)) throw new JSONException("倍速无效");
                    PlayerOptions.validateSpeed(params.getDouble("speed"));
                    play(id, series, episode, queue, null, false, params); break;
                }
                JSONObject info = params.optJSONObject("playInfo");
                if (info == null) info = new JSONObject().put("url", LibraryStore.text(params, "url", 4096)).put("vid", episode.getString("vid")).put("source", "web");
                String planSid = info.optString("seriesId", series.getString("seriesId"));
                if (!planSid.equals(series.getString("seriesId"))) throw new JSONException("片源与剧集不一致");
                info.put("seriesId", planSid);
                JSONObject mediaOptions = PlayerOptions.read(activity);
                String requestedQuality = MediaQuality.normalize(params.optString("quality", info.optString("requestedQuality", "auto")));
                if (info.has("requestedQuality") && !requestedQuality.equals(info.getString("requestedQuality"))) throw new JSONException("清晰度结果不匹配");
                info.put("requestedQuality", requestedQuality);
                params.put("quality", requestedQuality);
                if (!params.has("speed")) params.put("speed", mediaOptions.getDouble("speed"));
                // Validate playback rate even when supplied directly over the bridge.
                if (!(params.opt("speed") instanceof Number)) throw new JSONException("倍速无效");
                PlayerOptions.validateSpeed(params.getDouble("speed"));
                PlaybackSources.Plan plan = PlaybackSources.registerInitial(info, series.getString("seriesId"), episode.getString("vid"), requestedQuality);
                params.put("playInfo", plan.json());
                play(id, series, episode, queue, plan.url, false, params); break;
            }
            case "download": {
                JSONObject series = LibraryStore.series(params.getJSONObject("series")), episode = LibraryStore.episode(params.getJSONObject("episode"));
                if (store.downloads().length() >= 200) throw new Exception("下载记录已达 200 条上限");
                JSONObject info = params.optJSONObject("playInfo");
                if (info == null) info = new JSONObject().put("url", LibraryStore.text(params, "url", 4096)).put("vid", episode.getString("vid")).put("source", "web");
                String planSid = info.optString("seriesId", series.getString("seriesId"));
                if (!planSid.equals(series.getString("seriesId"))) throw new JSONException("片源与剧集不一致");
                info.put("seriesId", planSid);
                JSONObject mediaOptions = PlayerOptions.read(activity);
                String requestedQuality = PlayerOptions.quality(params.optString("quality", info.optString("requestedQuality", "auto")));
                if (info.has("requestedQuality") && !requestedQuality.equals(info.getString("requestedQuality"))) throw new JSONException("清晰度结果不匹配");
                info.put("requestedQuality", requestedQuality);
                params.put("quality", requestedQuality);
                if (!params.has("speed")) params.put("speed", mediaOptions.getDouble("speed"));
                // Validate playback rate even when supplied directly over the bridge.
                if (!(params.opt("speed") instanceof Number)) throw new JSONException("倍速无效");
                PlayerOptions.validateSpeed(params.getDouble("speed"));
                PlaybackSources.Plan plan = PlaybackSources.register(info, episode.getString("vid"), true);
                String url = plan.url;
                if (!plan.source.equals("web")) { downloadPrepared(id, series, episode, plan); break; }
                if (Uri.parse(url).getPath() != null && Uri.parse(url).getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")) throw new Exception("当前为流式播放列表，请在线播放；下载支持完整视频文件");
                if (closed) return;
                String title = series.getString("title") + " · 第 " + episode.getInt("index") + " 集";
                String downloadFilename=series.getString("seriesId") + "_" + episode.getInt("index") + "_" + System.currentTimeMillis() + ".mp4";
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url)).setTitle(title).setDescription("红果 · 视频下载")
                    .addRequestHeader("User-Agent", plan.userAgent).addRequestHeader("Referer", plan.referer)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, downloadFilename);
                long downloadId = downloads.enqueue(request);
                try { store.addManagedDownload(downloadId, series, episode, downloadFilename); } catch (Exception error) { downloads.remove(downloadId); throw error; }
                respond(id, new JSONObject().put("id", downloadId), null); break;
            }
            case "playDownload": {
                long downloadId = params.optLong("id", -1); JSONObject row = store.download(downloadId);
                if (row == null) throw new Exception("下载记录不存在");
                Uri uri = downloadedUri(downloadId);
                if (uri == null) throw new Exception("下载尚未完成，或文件已被移除");
                JSONObject series = LibraryStore.series(row.getJSONObject("series")), episode = LibraryStore.episode(row.getJSONObject("episode"));
                JSONArray queue = downloadedEpisodes(series, episode);
                play(id, series, episode, queue, uri.toString(), true, params); break;
            }
            case "openExternal": {
                String url = NetworkGuard.external(LibraryStore.text(params, "url", 4096));
                activity.runOnUiThread(() -> { if (closed || activity.isFinishing() || activity.isDestroyed()) return; try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); respond(id, new JSONObject().put("opened", true), null); } catch (Exception error) { respond(id, null, "设备上未找到浏览器"); } }); break;
            }
            default: throw new Exception("操作无效");
        }
    }
    private void play(Object id, JSONObject series, JSONObject episode, JSONArray episodes, String url, boolean local, JSONObject params) {
        final UpdateOperationGate.Lease launchLease;
        try { launchLease = UpdateInstallCheckpoint.operation(); }
        catch (Exception busy) { respond(id, null, busy.getMessage()); return; }
        activity.runOnUiThread(() -> {
            try (UpdateOperationGate.Lease held = launchLease) {
                if (closed || activity.isFinishing() || activity.isDestroyed()) return;
                Intent intent = PlayLauncher.build(activity, series, episode, episodes, url, local, params);
                UpdateInstallCheckpoint.playerLaunching();
                activity.startActivity(intent); respond(id, new JSONObject().put("started", true), null);
            } catch (Exception error) { respond(id, null, "播放器启动未成功"); }
        });
    }
    /** Encrypted App media is prepared before it becomes a completed manual download. */
    private void downloadPrepared(Object id, JSONObject series, JSONObject episode, PlaybackSources.Plan plan) throws Exception {
        PlaybackCache cache = PlaybackCache.get(activity); String sid = series.getString("seriesId"), vid = episode.getString("vid"); int index = episode.getInt("index");
        java.io.File target = null; long downloadId = -1;
        cache.protect(sid, vid, index, plan.requestedQuality);
        try {
            java.io.File prepared = cache.fetch(sid, vid, index, plan.url, plan.requestedQuality);
            java.io.File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new Exception("下载目录尚未准备");
            target = new java.io.File(directory, sid + "_" + index + "_" + UUID.randomUUID() + ".mp4");
            java.nio.file.Files.copy(prepared.toPath(), target.toPath());
            String title = series.getString("title") + " · 第 " + index + " 集";
            downloadId = downloads.addCompletedDownload(title, "红果 · 已准备的视频", false, "video/mp4", target.getAbsolutePath(), target.length(), true);
            if (downloadId < 0) throw new Exception("系统下载记录准备失败");
            store.addPreparedDownload(downloadId, series, episode, directory, target);
            respond(id, new JSONObject().put("id", downloadId).put("prepared", true), null);
        } catch (Exception error) {
            if (downloadId >= 0) downloads.remove(downloadId);
            if (target != null) target.delete();
            throw error;
        } finally { cache.release(sid, vid, index, plan.requestedQuality); }
    }
    private Uri downloadedUri(long downloadId) {
        try {
            JSONObject row=store.download(downloadId);if(row==null)return null;
            if(row.has("preparedFile")) { java.io.File owned=LibraryStore.preparedFile(activity,row);return owned==null?null:Uri.fromFile(owned); }
            try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(downloadId))) {
                if (cursor == null || !cursor.moveToFirst() || cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) return null;
                java.io.File owned=recoverPreparedFile(row,cursor);if(owned!=null)return Uri.fromFile(owned);
            }
            return managedDownloadedUri(downloadId);
        } catch (Exception ignored) { return null; }
    }
    private Uri managedDownloadedUri(long downloadId) {
        try {
            Uri uri = downloads.getUriForDownloadedFile(downloadId);
            if (uri == null || !"content".equals(uri.getScheme())) return null;
            try (ParcelFileDescriptor file = activity.getContentResolver().openFileDescriptor(uri, "r")) {
                if (file == null) return null;
            }
            return uri;
        } catch (Exception ignored) { return null; }
    }
    private static String cursorText(Cursor cursor,String column) {
        try {int index=cursor.getColumnIndex(column);return index<0||cursor.isNull(index)?"":cursor.getString(index);}catch(Exception ignored){return "";}
    }
    private java.io.File recoverPreparedFile(JSONObject row,Cursor cursor) {
        try {
            java.io.File directory=activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            long bytes=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            java.io.File recovered=LibraryStore.recoverPreparedFile(directory,row,cursorText(cursor,DownloadManager.COLUMN_LOCAL_URI),bytes,cursorText(cursor,DownloadManager.COLUMN_DESCRIPTION));
            if(recovered==null)return null;
            store.bindPreparedDownload(row.getLong("id"),directory,recovered);
            return recovered;
        }catch(Exception ignored){return null;}
    }
    /** Never mix stream cache, failed downloads or another series into an offline queue. */
    private JSONArray downloadedEpisodes(JSONObject series, JSONObject current) throws Exception {
        JSONArray records = store.downloads(), queue = new JSONArray();
        String sid = series.getString("seriesId");
        for (int i = 0; i < records.length(); i++) {
            JSONObject row = records.optJSONObject(i);
            if (row == null || row.optJSONObject("series") == null || !sid.equals(row.getJSONObject("series").optString("seriesId"))) continue;
            if (downloadedUri(row.optLong("id", -1)) == null) continue;
            try { queue.put(LibraryStore.episode(row.getJSONObject("episode"))); } catch (JSONException ignored) { }
        }
        return LibraryStore.episodes(queue, current);
    }
    private JSONArray downloadRows() throws Exception {
        JSONArray records = store.downloads(), result = new JSONArray();
        for (int i = 0; i < records.length(); i++) {
            JSONObject row = records.optJSONObject(i); if (row == null) continue;
            long id = row.getLong("id"); String status = "failed"; long current = 0, total = -1; int reason = DownloadManager.ERROR_FILE_ERROR;
            java.io.File owned=LibraryStore.preparedFile(activity,row);
            if(owned!=null) { status="success";current=owned.length();total=current;reason=0; }
            else if(!row.has("preparedFile")) {
                try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        status = state == DownloadManager.STATUS_PENDING ? "pending" : state == DownloadManager.STATUS_RUNNING ? "running" : state == DownloadManager.STATUS_PAUSED ? "paused" : state == DownloadManager.STATUS_SUCCESSFUL ? "success" : "failed";
                        current = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)); total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)); reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                        if(state==DownloadManager.STATUS_SUCCESSFUL) {
                            owned=recoverPreparedFile(row,cursor);
                            if(owned!=null) {current=owned.length();total=current;reason=0;}
                            else if(managedDownloadedUri(id)==null) {status="failed";reason=DownloadManager.ERROR_FILE_ERROR;}
                        }
                    }
                } catch(Exception ignored) {status="failed";reason=DownloadManager.ERROR_FILE_ERROR;}
            }
            result.put(new JSONObject().put("id", id).put("title", row.optString("title")).put("index", row.optInt("index")).put("seriesId", row.optJSONObject("series")==null?"":row.optJSONObject("series").optString("seriesId")).put("status", status).put("downloadedBytes", current).put("totalBytes", total).put("reason", reason));
        }
        return result;
    }
    private void respond(Object id, Object result, String error) {
        if (closed) return;
        try {
            JSONObject response = new JSONObject().put("id", id).put("result", result == null ? JSONObject.NULL : result).put("error", error == null ? JSONObject.NULL : error);
            final String source = "window.__androidResponse && window.__androidResponse(" + response.toString() + ")";
            // Retry may replace the WebView while a worker response waits in the
            // UI queue. Check this bridge's lifetime on that queue, not only here.
            activity.runOnUiThread(() -> { if (!closed) activity.script(source); });
        } catch (JSONException ignored) { }
    }
    void close() { closed = true; closeRegisteredModels(); closeHttp(); worker.shutdownNow(); downloadWorker.shutdownNow(); searchWorker.shutdownNow(); accountWorker.shutdownNow(); try { AccountSync.get().cancelLogin(); } catch (Exception ignored) { } }
}
