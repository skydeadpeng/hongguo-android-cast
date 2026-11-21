package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.json.*;

/** APK-local source catalog. WebView sees display data and opaque episode IDs only. */
final class SourceService {
    interface Reply {
        void success(Object result, String playbackSession);
        void failure(String code, String message);
    }
    private static final long TIMEOUT_MS = 25000;
    private static final int DETAIL_LIMIT = 16;
    private static final int MAX_LIST = 1024;
    private static final int MAX_VARIANTS = 50;
    private static final int INIT_QUEUE = 16;
    private static final String NATIVE_SOURCE = "hongguo";
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern CONTROLS = Pattern.compile("[\\x00-\\x1f\\x7f-\\x9f]");
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Lane> sources = new LinkedHashMap<>();
    private final ArrayDeque<Runnable> initializationQueue = new ArrayDeque<>();
    private FutureTask<Void> initialization;
    private boolean registryReady;
    private boolean registryFailed;
    private Job activePlay;
    private boolean closed;

    SourceService(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("android-source-settings-v1", Context.MODE_PRIVATE);
    }

    private void initialize() {
        if (initialization != null) return;
        initialization = new FutureTask<>(() -> {
            List<Lane> rows = new ArrayList<>();
            boolean loaded;
            try {
                JSONArray manifests = SourceModuleRuntime.listManifests(context);
                if (manifests.length() != 1) throw invalidResult();
                Set<String> ids = new HashSet<>();
                int restricted = 0;
                for (int i = 0; i < manifests.length(); i++) {
                    Lane lane = new Lane(context, manifests.getJSONObject(i));
                    rows.add(lane);
                    if (!ids.add(lane.id)) throw invalidResult();
                    if (lane.adult) restricted++;
                }
                if (!ids.contains(NATIVE_SOURCE) || restricted != 0) throw invalidResult();
                // Keep the native entry and ordinary sources at the start of the row.
                Collections.sort(rows, Comparator.comparingInt(lane -> lane.nativePlayback ? 0 : lane.adult ? 2 : 1));
                loaded = true;
            } catch (Exception error) { loaded = false; }
            final boolean success = loaded;
            main.post(() -> {
                if (closed || !success) {
                    for (Lane lane : rows) lane.worker.shutdownNow();
                    if (closed) return;
                    registryFailed = true;
                } else {
                    for (Lane lane : rows) sources.put(lane.id, lane);
                    registryReady = true;
                }
                while (!initializationQueue.isEmpty()) initializationQueue.removeFirst().run();
            });
            return null;
        });
        new Thread(initialization, "android-source-index").start();
    }

    private boolean enabled(Lane lane) {
        if (lane.nativePlayback) return true;
        try { return preferences.getBoolean(lane.id, !lane.adult); }
        catch (ClassCastException oldValue) { return !lane.adult; }
    }

    private JSONArray list() throws JSONException {
        JSONArray result = new JSONArray();
        for (Lane lane : sources.values()) {
            result.put(new JSONObject().put("id", lane.id).put("name", lane.name).put("description", lane.description)
                .put("version", lane.version).put("adult", lane.adult).put("enabled", enabled(lane))
                .put("nativePlayback", lane.nativePlayback).put("capabilities", lane.publicCapabilities())
                .put("allowEmptyCatalog", !Boolean.FALSE.equals(lane.manifest.opt("allowEmptyCatalog"))));
        }
        return result;
    }

    /** Called on the main thread; network, parsing and modules never execute here. */
    void submit(JSONObject params, Reply reply) {
        if (closed) { reply.failure("ERR_CANCELED", "来源页已关闭"); return; }
        if (registryFailed) { reply.failure("SOURCE_INVALID", "捆绑来源清单校验失败"); return; }
        if (!registryReady) {
            if (initializationQueue.size() >= INIT_QUEUE) { reply.failure("SOURCE_BUSY", "来源正在读取，请稍后重试"); return; }
            initializationQueue.addLast(() -> submit(params, reply));
            initialize();
            return;
        }
        try {
            String operation = string(params, "operation", 32, false);
            if ("list".equals(operation)) { reply.success(list(), null); return; }
            Lane lane = sources.get(string(params, "sourceId", 32, false));
            if (lane == null) throw new Failure("INVALID_INPUT", "来源未接入");
            if ("cancel".equals(operation)) {
                cancel(lane);
                reply.success(new JSONObject().put("ok", true), null);
                return;
            }
            if ("settings".equals(operation)) {
                Object setting = params.opt("enabled");
                if (!(setting instanceof Boolean)) throw invalid();
                if (lane.nativePlayback) {
                    if (!(Boolean) setting) throw new Failure("SOURCE_NATIVE", "红果由原有发现页提供");
                    reply.success(list(), null);
                    return;
                }
                preferences.edit().putBoolean(lane.id, (Boolean) setting).apply();
                if (!(Boolean) setting) cancel(lane);
                reply.success(list(), null);
                return;
            }
            if (!enabled(lane)) throw new Failure("SOURCE_DISABLED", "来源未启用");
            if (lane.nativePlayback) throw new Failure("SOURCE_NATIVE", "请从发现页浏览红果");
            if ("search".equals(operation) && !lane.capabilities.contains("search")) {
                throw new Failure("SOURCE_UNSUPPORTED", "此来源未提供搜索");
            }
            if ("account".equals(operation)) {
                throw new Failure("SOURCE_UNSUPPORTED", "此来源未提供账号接口");
            }

            // Validate renderer-supplied IDs and parameters before creating any network work.
            Request request = Request.read(operation, params, lane.id);
            if ("play".equals(operation) && activePlay != null) retire(activePlay, true, "ERR_CANCELED", "已选择其他分集");
            Job job = new Job(lane, request, reply);
            lane.pending.add(job);
            if ("play".equals(operation)) activePlay = job;
            main.postDelayed(job.timeout, TIMEOUT_MS);
            try { lane.worker.execute(job.task); }
            catch (RejectedExecutionException error) { retire(job, true, "SOURCE_BUSY", "来源请求较多，请稍后重试"); }
        } catch (Failure error) {
            reply.failure(error.code, error.getMessage());
        } catch (Exception error) {
            reply.failure("INVALID_INPUT", "来源参数无效");
        }
    }

    private void cancel(Lane lane) {
        lane.epoch++;
        for (Job job : new ArrayList<>(lane.pending)) retire(job, true, "ERR_CANCELED", "来源操作已取消");
        SourcePlaybackSessions.closeSource(lane.id);
    }

    void close() {
        if (closed) return;
        closed = true;
        initializationQueue.clear();
        if (initialization != null) initialization.cancel(true);
        for (Lane lane : sources.values()) {
            cancel(lane);
            lane.worker.shutdownNow();
        }
    }

    private boolean retire(Job job, boolean cancelTask, String code, String message) {
        if (!job.lane.pending.remove(job)) return false;
        main.removeCallbacks(job.timeout);
        if (activePlay == job) activePlay = null;
        if (cancelTask) {
            job.task.cancel(true);
            job.lane.cancelIfRunning(job);
            job.lane.worker.remove(job.task);
        }
        if (!closed && code != null) job.reply.failure(code, message);
        return true;
    }

    private void deliver(Job job, Object value, Exception failure) {
        if (!retire(job, false, null, null) || closed) return;
        if (job.epoch != job.lane.epoch || !enabled(job.lane)) {
            job.reply.failure("ERR_CANCELED", "来源操作已取消");
            return;
        }
        if (System.nanoTime() >= job.deadline) {
            job.reply.failure("ETIMEDOUT", "来源请求超时，请重试");
            return;
        }
        if (failure != null) {
            // Only our fixed messages and typed status codes reach the UI. Never return
            // arbitrary module/transport messages (which may contain a signed media URL).
            if (failure instanceof Failure) job.reply.failure(((Failure) failure).code, failure.getMessage());
            else if (failure instanceof SourceModuleRuntime.Failure) {
                SourceModuleRuntime.Failure sourceFailure = (SourceModuleRuntime.Failure) failure;
                if (sourceFailure.status >= 400 && sourceFailure.status <= 599) upstreamFailure(job.reply, Integer.toString(sourceFailure.status));
                else if ("SOURCE_ACCESS".equals(sourceFailure.code)) job.reply.failure("SOURCE_ACCESS", "来源要求额外的访问验证，当前仅支持公开内容");
                else if ("SOURCE_TIMEOUT".equals(sourceFailure.code)) job.reply.failure("ETIMEDOUT", "来源响应超时，请稍后重试");
                else if ("SOURCE_NETWORK".equals(sourceFailure.code)) job.reply.failure("SOURCE_NETWORK", "网络连接失败，请检查设备联网状态后重试");
                else if ("ABORT_ERR".equals(sourceFailure.code)) job.reply.failure("ERR_CANCELED", "来源操作已取消");
                else if ("LOGIN_REQUIRED".equals(sourceFailure.code)) job.reply.failure("LOGIN_REQUIRED", "请先登录此来源");
                else if ("SOURCE_SIZE".equals(sourceFailure.code)) job.reply.failure("SOURCE_SIZE", "来源返回的内容超过大小限制");
                else if ("SOURCE_ADDRESS".equals(sourceFailure.code)) job.reply.failure("SOURCE_ADDRESS", "来源返回了不支持的地址");
                else job.reply.failure("SOURCE_ERROR", "来源请求未完成，请重试");
            }
            else if (failure instanceof java.net.SocketTimeoutException) job.reply.failure("ETIMEDOUT", "来源请求超时，请重试");
            else job.reply.failure("SOURCE_ERROR", "来源请求未完成，请检查网络或稍后重试");
            return;
        }
        if (!(value instanceof Playback)) {
            job.reply.success(value, null);
            return;
        }
        Playback play = (Playback) value;
        try {
            Lane lane = job.lane;
            String session = SourcePlaybackSessions.open(lane.id, play.title, play.media, lane.manifest);
            job.reply.success(new JSONObject().put("ok", true).put("variants", play.variants), session);
        } catch (Exception error) {
            job.reply.failure("SOURCE_MEDIA_INVALID", "此分集的播放地址、格式或授权未通过校验");
        }
    }

    private static void upstreamFailure(Reply reply, String status) {
        reply.failure("HTTP_" + status, "来源返回 HTTP " + status + ("401".equals(status) || "403".equals(status) ? "，未授权播放或访问" : "，请稍后重试"));
    }

    private final class Job {
        final Lane lane;
        final Reply reply;
        final long epoch, deadline;
        final Runnable timeout;
        final FutureTask<Void> task;
        Job(Lane lane, Request request, Reply reply) {
            this.lane = lane;
            this.reply = reply;
            epoch = lane.epoch;
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
            timeout = () -> retire(this, true, "ETIMEDOUT", "来源请求超时，请重试");
            task = new FutureTask<>(() -> {
                lane.start(this);
                try {
                    Object output = lane.execute(request, epoch, deadline);
                    main.post(() -> deliver(this, output, null));
                } catch (Exception error) {
                    main.post(() -> deliver(this, null, error));
                } finally { lane.end(this); }
                return null;
            });
        }
    }

    private static final class Request {
        final String op, value, episode;
        final int page;
        int variant = -1;
        Request(String op, String value, String episode, int page) {
            this.op = op; this.value = value; this.episode = episode; this.page = page;
        }
        static Request read(String op, JSONObject params, String source) throws Exception {
            switch (op) {
                case "categories": return new Request(op, "", "", 1);
                case "catalog": return new Request(op, params.has("category") ? string(params, "category", 512, true) : "", "", page(params));
                case "search": return new Request(op, string(params, "keyword", 256, false), "", page(params));
                case "detail": return new Request(op, seriesKey(source, string(params, "seriesId", 512, false)), "", 1);
                case "play":
                    String episode = string(params, "episodeId", 68, false);
                    if (!episode.matches("ep_[0-9a-f]{64}")) throw invalid();
                    Request play = new Request(op, seriesKey(source, string(params, "seriesId", 512, false)), episode, 1);
                    if (params.has("variant")) {
                        String raw = string(params, "variant", 2, true);
                        if (!raw.isEmpty()) {
                            if (!raw.matches("(?:0|[1-9][0-9]?)")) throw invalid();
                            play.variant = Integer.parseInt(raw);
                            if (play.variant >= MAX_VARIANTS) throw invalid();
                        }
                    }
                    return play;
                default: throw invalid();
            }
        }
        private static int page(JSONObject params) throws Failure {
            if (!params.has("page")) return 1;
            Object raw = params.opt("page");
            if (!(raw instanceof Number)) throw invalid();
            double number = ((Number) raw).doubleValue();
            if (number < 1 || number > 100000 || number != Math.rint(number)) throw invalid();
            return (int) number;
        }
    }

    private static String string(JSONObject object, String field, int max, boolean empty) throws Failure {
        Object raw = object.opt(field);
        if (!(raw instanceof String)) throw invalid();
        String value = (String) raw;
        if ((!empty && value.isEmpty()) || !value.trim().equals(value) || value.length() > max || CONTROLS.matcher(value).find()) throw invalid();
        return value;
    }
    // A provider may advertise a slug (including Unicode) rather than an integer.
    // A renderer URL or path traversal is never accepted as a series identity.
    private static String seriesKey(String source, String value) throws Failure {
        if (value.equals(".") || value.equals("..") || value.getBytes(StandardCharsets.UTF_8).length > 512 ||
            value.matches("(?s).*[/\\\\:?#%|\"<>].*")) throw invalid();
        return value;
    }
    private static int integer(Object raw, int min, int max) throws Failure {
        if (!(raw instanceof Number)) throw invalid();
        double n = ((Number) raw).doubleValue();
        if (n != Math.rint(n) || n < min || n > max) throw invalid();
        return (int) n;
    }
    private static Failure invalid() { return new Failure("INVALID_INPUT", "来源参数无效"); }
    private static Failure invalidResult() { return new Failure("SOURCE_RESPONSE_INVALID", "来源返回的数据格式不受支持"); }
    private static String text(Object value, int max) {
        if (!(value instanceof String)) return "";
        String input = (String) value;
        input = input.substring(0, Math.min(input.length(), 2 * max + 256));
        String result = CONTROLS.matcher(TAGS.matcher(input).replaceAll("")).replaceAll(" ").trim();
        return result.substring(0, Math.min(result.length(), max));
    }
    private static String text(JSONObject row, int max, String... fields) {
        for (String field : fields) {
            String value = text(row.opt(field), max);
            if (!value.isEmpty()) return value;
        }
        return "";
    }
    private static JSONObject object(Object value) throws Failure {
        if (!(value instanceof JSONObject)) throw invalidResult();
        return (JSONObject) value;
    }
    private static String identity(Object raw, int max, boolean empty) throws Failure {
        if (raw instanceof Number) {
            double n = ((Number) raw).doubleValue();
            if (!Double.isFinite(n) || n != Math.rint(n) || Math.abs(n) > 9007199254740991d) throw invalidResult();
            raw = String.valueOf((long) n);
        }
        if (!(raw instanceof String)) throw invalidResult();
        String value = (String) raw;
        if ((!empty && value.isEmpty()) || !value.trim().equals(value) || value.length() > max || CONTROLS.matcher(value).find()) throw invalidResult();
        return value;
    }
    private static int number(Object raw) {
        if (raw instanceof String && ((String) raw).matches("[0-9]{1,6}")) raw = Integer.valueOf((String) raw);
        if (!(raw instanceof Number)) return 0;
        double n = ((Number) raw).doubleValue();
        return n == Math.rint(n) && n >= 1 && n <= 100000 ? (int) n : 0;
    }
    private static boolean identityMatches(JSONObject row, String key, String expected) {
        Object value = row.opt(key);
        return value == null || value == JSONObject.NULL || "".equals(value) || expected.equals(value);
    }
    private static JSONArray array(Object value) throws Failure {
        if (!(value instanceof JSONArray) || ((JSONArray) value).length() > MAX_LIST) throw invalidResult();
        return (JSONArray) value;
    }

    private static final class Failure extends IOException {
        final String code;
        Failure(String code, String message) { super(message); this.code = code; }
    }
    private static final class Playback {
        final String title;
        final JSONObject media;
        final JSONArray variants;
        Playback(String title, JSONObject media, JSONArray variants) { this.title = title; this.media = media; this.variants = variants; }
    }
    private static final class Episode {
        final JSONObject chapter;
        final JSONObject display;
        int issuedVariants;
        Episode(JSONObject chapter, JSONObject display) { this.chapter = chapter; this.display = display; }
    }
    private static final class Detail {
        final JSONObject display;
        final Map<String, Episode> episodes;
        final String releaseStatus;
        final long expires = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        Detail(JSONObject display, Map<String, Episode> episodes, String releaseStatus) {
            this.display = display; this.episodes = episodes; this.releaseStatus = releaseStatus;
        }
    }

    private static final class Lane {
        final String id, name, description, version;
        final boolean adult, nativePlayback;
        final JSONObject manifest;
        final Set<String> capabilities = new HashSet<>();
        final Set<String> imageHosts = new HashSet<>();
        final Context context;
        final ThreadPoolExecutor worker;
        final Set<Job> pending = new HashSet<>(); // main thread only
        volatile long epoch;
        // Owned by this lane's one worker. Epoch changes invalidate detail identities.
        long detailEpoch = -1;
        private volatile SourceModuleRuntime runtime;
        private Job running;
        final LinkedHashMap<String, Detail> details = new LinkedHashMap<>(16, .75f, true);
        Lane(Context context, JSONObject manifest) throws Exception {
            this.context = context; this.manifest = manifest;
            id = string(manifest, "id", 32, false);
            name = string(manifest, "name", 100, false);
            description = text(manifest.opt("description"), 350);
            version = text(manifest.opt("version"), 32);
            Object level = manifest.opt("adult");
            if (!(level instanceof Boolean) || !id.matches("[a-z0-9-]+")) throw invalidResult();
            adult = (Boolean) level; nativePlayback = NATIVE_SOURCE.equals(id);
            if (nativePlayback && adult) throw invalidResult();
            JSONArray offered = manifest.optJSONArray("capabilities");
            if (offered != null) for (int i = 0; i < offered.length(); i++) capabilities.add(offered.getString(i));
            // Covers enter WebView. Only exact, declared hosts and credential-free paths
            // are eligible here; arbitrary CDN URLs and signed URLs remain native-only.
            for (String field : Arrays.asList("hosts", "aliases")) {
                JSONArray values = manifest.optJSONArray(field);
                if (values != null) for (int i = 0; i < values.length(); i++) {
                    String host = values.optString(i).toLowerCase(Locale.ROOT);
                    if (host.matches("[a-z0-9.-]+") && host.indexOf('.') > 0) imageHosts.add(host);
                }
            }
            worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4),
                runnable -> new Thread(runnable, "android-source-" + id));
            worker.allowCoreThreadTimeOut(true);
        }
        JSONArray publicCapabilities() {
            JSONArray result = new JSONArray();
            if (capabilities.contains("search")) result.put("search");
            return result;
        }
        synchronized void start(Job job) { running = job; }
        synchronized void end(Job job) { if (running == job) running = null; }
        synchronized void cancelIfRunning(Job job) {
            // Do not cancel a different job if this one was still queued or just finished.
            if (running == job && runtime != null) runtime.cancel();
        }
        Object execute(Request request, long revision, long deadline) throws Exception {
            NetworkGuard.remaining(deadline, 25000);
            if (epoch != revision) throw new Failure("ERR_CANCELED", "来源操作已取消");
            if (detailEpoch != revision) { details.clear(); detailEpoch = revision; }
            if (runtime == null) runtime = new SourceModuleRuntime(context, id);
            switch (request.op) {
                case "categories": return categories(invoke("categories", deadline, false));
                case "catalog": return listing(invoke("catalogPage", deadline, request.page, request.value));
                case "search": return listing(invoke("search", deadline, request.page, request.value));
                case "detail": return fetchDetail(request.value, deadline).display;
                case "play": {
                    Detail detail = details.get(request.value);
                    if (detail == null || detail.expires <= System.nanoTime()) {
                        // Never refetch or resolve an episode ID that the app did not issue.
                        throw new Failure("SOURCE_DETAIL_EXPIRED", "分集列表已失效，请返回后重新打开详情");
                    }
                    Episode episode = detail.episodes.get(request.episode);
                    if (episode == null || !episode.display.getBoolean("playable"))
                        throw new Failure("EPISODE_NOT_OPEN", "此分集未开放或不属于当前剧集");
                    if (request.variant >= episode.issuedVariants) {
                        throw new Failure("SOURCE_VARIANT_INVALID", "请先播放当前分集，再选择该分集的线路");
                    }
                    String title = detail.display.getString("title");
                    JSONObject task = new JSONObject().put("dramaId", id + ":" + request.value)
                        .put("dramaTitle", title).put("chapter", episode.chapter)
                        .put("index", episode.display.getInt("index")).put("total", detail.episodes.size())
                        .put("outPath", "").put("releaseStatus", detail.releaseStatus).put("downloadQuality", 0);
                    JSONObject media = object(invoke("resolveMedia", deadline, task));
                    return selectMedia(media, episode, request.variant, text(title + " · " + episode.display.getString("title"), 512));
                }
                default: throw invalid();
            }
        }
        Object invoke(String method, long deadline, Object... args) throws Exception {
            JSONArray input = new JSONArray();
            for (Object value : args) input.put(value);
            return runtime.invoke(method, input, deadline);
        }

        Playback selectMedia(JSONObject resolved, Episode episode, int selected, String title) throws Exception {
            JSONArray raw = resolved.optJSONArray("variants");
            if (raw == null || raw.length() == 0) raw = new JSONArray().put(resolved);
            if (raw.length() > MAX_VARIANTS) throw invalidResult();
            int chosenIndex = selected < 0 ? 0 : selected;
            JSONObject chosen = raw.optJSONObject(chosenIndex);
            if (chosen == null) throw new Failure("SOURCE_VARIANT_INVALID", "此分集的线路已变化，请重新打开详情");
            String main = string(resolved, "url", 8192, false);
            String url = string(chosen, "url", 8192, false);
            SourceModuleRuntime.validateMedia(url);
            JSONArray variants = new JSONArray();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject row = object(raw.opt(i));
                String label = text(row.opt("label"), 64);
                if (label.isEmpty()) {
                    int quality = number(row.opt("quality"));
                    label = (quality > 0 ? quality + "P · " : "") + "线路 " + (i + 1);
                }
                variants.put(new JSONObject().put("id", String.valueOf(i)).put("label", label));
            }
            JSONObject media = new JSONObject().put("url", url);
            for (String name : Arrays.asList("referer", "userAgent", "credentials", "format", "durationMs", "playlist", "hlsKey", "cencKey")) {
                Object field = chosen.opt(name);
                if (field == null && (url.equals(main) || "referer".equals(name) || "userAgent".equals(name))) field = resolved.opt(name);
                if (field != null) media.put(name, field);
            }
            episode.issuedVariants = variants.length();
            return new Playback(title, media, variants);
        }
        JSONArray categories(Object raw) throws Exception {
            JSONArray rows = array(raw instanceof JSONObject ? ((JSONObject) raw).opt("items") : raw), result = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = object(rows.get(i));
                String key = identity(item.opt("id"), 512, true);
                String label = text(item, 128, "name", "label");
                if (label.isEmpty() || !seen.add(key)) throw invalidResult();
                result.put(new JSONObject().put("id", key).put("label", label));
            }
            return result;
        }
        JSONObject listing(Object raw) throws Exception {
            JSONObject page = object(raw);
            JSONArray rows = array(page.opt("items")), items = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject card = card(object(rows.get(i)));
                if (seen.add(card.getString("seriesId"))) items.put(card);
            }
            return new JSONObject().put("items", items).put("hasMore", Boolean.TRUE.equals(page.opt("hasMore")));
        }
        JSONObject card(JSONObject row) throws Exception {
            Object rawKey = row.opt("sourceId");
            if (rawKey == null || rawKey == JSONObject.NULL || "".equals(rawKey)) {
                Object scoped = row.opt("id");
                if (scoped instanceof String && ((String) scoped).startsWith(id + ":")) rawKey = ((String) scoped).substring(id.length() + 1);
            }
            String key = seriesKey(id, identity(rawKey, 512, false));
            String title = text(row, 512, "title", "name");
            if (!identityMatches(row, "source", id) || !identityMatches(row, "id", id + ":" + key) || title.isEmpty()) throw invalidResult();
            String allowedCover = "";
            for (String field : Arrays.asList("coverUrl", "cover", "cover_url", "image", "poster", "pic")) {
                allowedCover = image(row.opt(field), 0);
                if (!allowedCover.isEmpty()) break;
            }
            JSONArray tags = new JSONArray(), rawTags = row.optJSONArray("tags");
            if (rawTags != null) for (int i = 0; i < Math.min(rawTags.length(), 16); i++) {
                String tag = text(rawTags.opt(i), 48);
                if (!tag.isEmpty()) tags.put(tag);
            }
            int count = 0;
            for (String field : Arrays.asList("totalEpisode", "episodeCount", "total_episode", "chapterCount", "total")) {
                count = number(row.opt(field));
                if (count > 0) break;
            }
            return new JSONObject().put("sourceId", id).put("seriesId", key).put("title", title)
                .put("coverUrl", allowedCover).put("description", text(row, 4000, "desc", "intro", "description"))
                .put("episodeCount", count).put("tags", tags);
        }
        String image(Object value, int depth) {
            if (depth > 3) return "";
            if (value instanceof JSONArray) {
                JSONArray list = (JSONArray) value;
                for (int i = 0; i < Math.min(list.length(), 8); i++) {
                    String candidate = image(list.opt(i), depth + 1);
                    if (!candidate.isEmpty()) return candidate;
                }
                return "";
            }
            if (value instanceof JSONObject) {
                JSONObject row = (JSONObject) value;
                for (String field : Arrays.asList("url", "src", "cover", "image", "pic")) {
                    String candidate = image(row.opt(field), depth + 1);
                    if (!candidate.isEmpty()) return candidate;
                }
                return "";
            }
            if (!(value instanceof String)) return "";
            try {
                String cover = (String) value;
                URI uri = SourceModuleRuntime.validateMedia(cover);
                String host = uri.getHost().toLowerCase(Locale.ROOT);
                return imageHosts.contains(host) && uri.getRawQuery() == null && uri.getRawFragment() == null ? cover : "";
            } catch (Exception ignored) { return ""; }
        }
        Detail fetchDetail(String key, long deadline) throws Exception {
            JSONObject raw = object(invoke("detail", deadline, key));
            JSONObject drama = object(raw.opt("drama"));
            JSONObject display = card(drama);
            if (!key.equals(display.getString("seriesId"))) throw invalidResult();
            JSONArray chapters = array(raw.opt("chapters")), rows = new JSONArray();
            Map<String, Episode> episodes = new HashMap<>();
            for (int i = 0; i < chapters.length(); i++) {
                JSONObject chapter = object(chapters.get(i));
                String rawId = identity(chapter.opt("id"), 8192, false);
                String prefix = id + ":" + key + ":";
                if (!rawId.startsWith(prefix) || rawId.length() == prefix.length() || !identityMatches(chapter, "source", id)) throw invalidResult();
                String episodeId = publicEpisodeId(rawId);
                int index = number(chapter.opt("currentEpisode"));
                if (index == 0) index = i + 1;
                String title = text(chapter, 160, "title");
                JSONObject item = new JSONObject().put("episodeId", episodeId).put("index", index)
                    .put("title", title.isEmpty() ? "第 " + index + " 集" : title)
                    .put("playable", !Boolean.TRUE.equals(chapter.opt("vip")) && !Boolean.TRUE.equals(chapter.opt("locked")) &&
                        !Boolean.FALSE.equals(chapter.opt("playable")));
                if (episodes.put(episodeId, new Episode(chapter, item)) != null) throw invalidResult();
                rows.put(item);
            }
            display.put("episodes", rows);
            if (rows.length() > 0) display.put("episodeCount", rows.length());
            Detail result = new Detail(display, episodes, text(drama.opt("releaseStatus"), 64));
            details.put(key, result);
            while (details.size() > DETAIL_LIMIT) details.remove(details.keySet().iterator().next());
            return result;
        }
        private static String publicEpisodeId(String input) throws Exception {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder("ep_");
            for (byte value : hash) result.append(hex[(value & 0xff) >>> 4]).append(hex[value & 0xf]);
            return result.toString();
        }
    }
}
