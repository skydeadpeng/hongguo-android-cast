package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.json.*;

/** One anonymous registered identity per metadata lookup, never an account session or a disk record. */
final class RegisteredPlaybackModel implements AutoCloseable {
    static final String API = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/";
    // Per-episode recovery endpoint: the legacy POST can earn a persistent empty 200.
    static final String VIDEO_TAB_API = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/";
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final long MAX_NANOS = TimeUnit.SECONDS.toNanos(20);
    static final class ModelError extends IOException {
        final String code;
        ModelError(String message, String code) { super(message); this.code = code; }
    }
    private final OfficialApiClient.Transport registration, modelTransport;
    private final DeviceBootstrap bootstrap, rotateBootstrap;
    private final long deadline;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean closed;
    private volatile OfficialApiClient registering;
    private volatile Thread owner;

    /** Anonymous identity source: returns {device_id, install_id, cdid}. */
    interface DeviceBootstrap { String[] register() throws Exception; }
    /** The v5 model endpoint hollows identities from the older log.snssdk.com registration
     * line; the upstream 73932 profile (sig_hash and friends) is what working TV clients
     * register with and is trusted there. */
    static String[] upstreamBootstrap() throws Exception {
        UpstreamDeviceRegister.Identity identity = UpstreamDeviceRegister.register();
        return new String[]{identity.deviceId, identity.iid, identity.cdid, identity.cookies};
    }
    /** The working TV clients register once per INSTALL and persist the identity
     * (hongguoTV's PreferencesPlayerDeviceStore); a served identity keeps being served
     * across process restarts, while fresh registrations mostly earn poisoned ones.
     * Cache in memory, persist to disk after the identity proves itself (a model
     * arrived), and reload the persisted identity on the next cold start. */
    private static final AtomicReference<String[]> SHARED_IDENTITY = new AtomicReference<>();
    private static volatile java.io.File identityFile;
    /** Wire the on-disk identity slot once from the application context. */
    static void identityStore(java.io.File file) { identityFile = file; }
    static synchronized String[] cachedBootstrap() throws Exception {
        String[] identity = SHARED_IDENTITY.get();
        if (identity != null) return identity;
        identity = loadPersistedIdentity();
        if (identity != null) { SHARED_IDENTITY.set(identity); return identity; }
        identity = upstreamBootstrap();
        SHARED_IDENTITY.set(identity);
        return identity;
    }
    /** Rotations always register a fresh identity and make it the shared one. */
    static String[] freshBootstrap() throws Exception {
        UpstreamDeviceRegister.Identity identity = UpstreamDeviceRegister.register();
        String[] fresh = {identity.deviceId, identity.iid, identity.cdid, identity.cookies};
        SHARED_IDENTITY.set(fresh);
        return fresh;
    }
    /** Persists an identity after it earned a served model; never throws. */
    private static void persistIdentity(String[] identity) {
        java.io.File file = identityFile;
        if (file == null || identity == null || identity.length < 2) return;
        try {
            JSONObject record = new JSONObject().put("device_id", identity[0]).put("install_id", identity[1]);
            if (identity.length > 2 && identity[2] != null) record.put("cdid", identity[2]);
            if (identity.length > 3 && identity[3] != null && !identity[3].isEmpty()) record.put("cookies", identity[3]);
            java.nio.file.Files.write(file.toPath(), record.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }
    private static String[] loadPersistedIdentity() {
        java.io.File file = identityFile;
        if (file == null || !file.isFile()) return null;
        try {
            JSONObject record = OfficialApiClient.parse(java.nio.file.Files.readAllBytes(file.toPath()));
            String did = record.optString("device_id", ""), iid = record.optString("install_id", ""), cdid = record.optString("cdid", ""), cookies = record.optString("cookies", "");
            if (OfficialApiClient.validID(did) && OfficialApiClient.validID(iid)) return new String[]{did, iid, cdid, cookies};
        } catch (Exception ignored) { }
        return null;
    }

    RegisteredPlaybackModel() { this(System.nanoTime() + MAX_NANOS); }
    RegisteredPlaybackModel(long deadline) { this(new OfficialTransport(), new ModelHttpTransport(), deadline); }
    RegisteredPlaybackModel(OfficialApiClient.Transport registration, OfficialApiClient.Transport modelTransport, long deadline) {
        this(registration, modelTransport, RegisteredPlaybackModel::cachedBootstrap, RegisteredPlaybackModel::freshBootstrap, deadline);
    }
    RegisteredPlaybackModel(OfficialApiClient.Transport registration, OfficialApiClient.Transport modelTransport, DeviceBootstrap bootstrap, long deadline) {
        this(registration, modelTransport, bootstrap, bootstrap, deadline);
    }
    RegisteredPlaybackModel(OfficialApiClient.Transport registration, OfficialApiClient.Transport modelTransport, DeviceBootstrap bootstrap, DeviceBootstrap rotateBootstrap, long deadline) {
        this.registration = Objects.requireNonNull(registration); this.modelTransport = Objects.requireNonNull(modelTransport);
        this.bootstrap = Objects.requireNonNull(bootstrap);
        this.rotateBootstrap = Objects.requireNonNull(rotateBootstrap);
        this.deadline = Math.min(deadline, System.nanoTime() + MAX_NANOS);
    }
    static String requestedVid(JSONObject params) throws IOException {
        if (params == null || params.length() != 1 || !(params.opt("vid") instanceof String) || !((String) params.opt("vid")).matches("[0-9]{1,30}"))
            throw new ModelError("分集请求信息无效", "INVALID_INPUT");
        return (String) params.opt("vid");
    }
    boolean current() { return !closed && System.nanoTime() < deadline; }
    private void check() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("App片源请求已取消");
        if (System.nanoTime() >= deadline) throw new SocketTimeoutException("App片源登记和取源总等待超时");
    }
    @Override public void close() {
        closed = true;
        OfficialApiClient client = registering; if (client != null) client.close();
        try { registration.close(); } catch (RuntimeException ignored) { }
        try { modelTransport.close(); } catch (RuntimeException ignored) { }
        Thread thread = owner; if (thread != null && thread != Thread.currentThread()) thread.interrupt();
    }
    JSONObject fetch(String vid) throws Exception {
        if (vid == null || !vid.matches("[0-9]{1,30}")) throw new ModelError("分集请求信息无效", "INVALID_INPUT");
        if (!started.compareAndSet(false, true)) throw new IOException("App片源请求已经结束");
        owner = Thread.currentThread();
        try {
            // This fresh in-memory device is unrelated to AccountSync/OfficialAccountVault.
            // The upstream registration line matches the working TV clients' profile; the
            // older PassportDevice line yields identities the v5 model endpoint hollows.
            check();
            String[] identity;
            try { identity = bootstrap.register(); traceV5("bootstrap ok did=" + identity[0] + (identity[0].equals(SHARED_IDENTITY.get() == null ? "" : SHARED_IDENTITY.get()[0]) ? " (shared)" : " (fresh)")); }
            catch (Exception failure) { traceV5("bootstrap failed: " + failure); check(); throw failure; }
            check();
            String did = identity[0], iid = identity[1], cdid = identity.length > 2 ? identity[2] : null;
            if (!OfficialApiClient.validID(did) || !OfficialApiClient.validID(iid))
                throw new ModelError("App片源设备登记未完成", "APP_INVALID_RESPONSE");
            // 2026-11-17 (evening): match the working TV clients' architecture exactly —
            // ONE v5 request per fetch, no internal retry ladder, no identity rotation,
            // no pacing. The working clients (hongguoTV 0.16.5) send a single player/v1
            // request; recovery lives at the episode level (their RecoveryBudget re-calls
            // the playback with the SAME persisted identity at 1s/2.5s/5s/15s/30s/60s/90s/
            // 105s). Our previous multi-attempt ladder burned identity goodwill in rapid
            // bursts and poisoned every rotation. This fetch now mirrors the single-shot
            // design: succeed or fail in under a second, let the JS web fallback or the
            // user's retry carry the recovery on the same blessed identity.
            JSONObject projected = null; ModelError lastFailure = null;
            try { projected = playerV1(did, iid, cdid, vid); }
            catch (ModelError failure) { lastFailure = failure; }
            check();
            if (projected == null) throw lastFailure;
            HOLLOW_STREAK.set(0);
            persistIdentity(new String[]{did, iid, cdid});
            return new JSONObject().put("vid", vid).put("video_model", projected);
        } finally {
            registering = null; owner = null;
            try { registration.close(); } catch (RuntimeException ignored) { }
            try { modelTransport.close(); } catch (RuntimeException ignored) { }
        }
    }
    /** Empty 200s and HTTP refusals are transient endpoint routing; business denials and
     * malformed models stay terminal for the alternate form as well. */
    private static boolean recoverable(ModelError failure) {
        return "APP_EMPTY_RESPONSE".equals(failure.code) || "HTTP_ERROR".equals(failure.code);
    }
    /** True while enough of the shared budget remains to start another ladder attempt and
     * still leave the JS web fallback time to finish within the overall resolve deadline. */
    private boolean ladderTimeLeft() { return deadline - System.nanoTime() > TimeUnit.SECONDS.toNanos(8); }
    /** Hollow fast-fail state: four empty v5 answers (roughly one full ladder) arm a
     * single-probe mode so later episodes fall to the web chain in seconds; any served
     * answer or a 3-minute quiet gap restores the full ladder. */
    private static final AtomicInteger HOLLOW_STREAK = new AtomicInteger();
    private static volatile long hollowMarkMs;
    private static boolean v5FastFailActive() {
        if (System.currentTimeMillis() - hollowMarkMs >= 180_000) { HOLLOW_STREAK.set(0); return false; }
        return HOLLOW_STREAK.get() >= 4;
    }
    private static void noteHollowFetch() {
        hollowMarkMs = System.currentTimeMillis();
        HOLLOW_STREAK.incrementAndGet();
    }
    /** Wait up to the given pause inside the remaining budget; cancellations surface at once. */
    private void pace(long milliseconds) throws IOException {
        long stop = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        while (System.nanoTime() < stop) {
            check();
            try { Thread.sleep(120); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new InterruptedIOException("App片源请求已取消"); }
        }
    }
    /** One fresh registration when the remaining budget allows a full extra identity attempt;
     * any failure ends the identity rotation with the previous failure intact. The retry client
     * is never closed here: it shares the injected registration transport, which the fetch
     * boundary owns and closes once — closing it per attempt would kill later rotations. */
    private String[] reRegister() throws IOException {
        if (deadline - System.nanoTime() < TimeUnit.SECONDS.toNanos(6)) return null;
        try {
            check(); String[] identity = rotateBootstrap.register(); check();
            if (OfficialApiClient.validID(identity[0]) && OfficialApiClient.validID(identity[1])) return identity;
        } catch (Exception ignored) {
            check(); // a cancellation must not surface as the previous business failure
        }
        return null;
    }
    /** 2026-11-16 起唯一仍被应答的模型形态：sinfonlinea 的 player/v1 + v5 三头签名。
     * 与 hongguoTV 0.16.5 MobilePlayback.kt 同构（query 无 ts、gzip 正文、不带 Cookie）；
     * cdid 与 Kotlin 孪生一致：会话自己的随机 UUID，与注册 payload 里的 cdid 相互独立。 */
    private JSONObject playerV1(String did, String iid, String cdid, String vid) throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("aid", "8662"); query.put("app_name", "novelread");
        query.put("version_code", "74332"); query.put("version_name", "7.4.3.32");
        query.put("channel", "vivo_8662_64"); query.put("device_platform", "android");
        query.put("device_type", "PGT-AN10"); query.put("os_version", "12"); query.put("update_version_code", "74332");
        query.put("iid", iid);
        query.put("cdid", PlaybackSignerV5.newCdid());
        query.put("device_id", did);
        JSONObject payload = new JSONObject()
            .put("biz_param", new JSONObject().put("detail_page_version", 0).put("device_level", 1).put("disable_digg_stat", false)
                .put("disable_video_relate_book", false).put("need_all_video_definition", true).put("need_mp4_align", false)
                .put("use_os_player", false).put("use_server_dns", false).put("video_platform", 1024))
            .put("mixed_video_id_map", new JSONObject().put("1004", new JSONArray().put(vid)))
            .put("dr_scene", "player");
        byte[] body = gzip(payload.toString().getBytes(StandardCharsets.UTF_8));
        PlaybackSignerV5.Request signed = PlaybackSignerV5.signPlayback(PlaybackSignerV5.PLAYER_V1_API, query, body, System.currentTimeMillis());
        Map<String, String> headers = new LinkedHashMap<>(signed.headers);
        headers.put("User-Agent", PlaybackSignerV5.PLAYER_V1_UA);
        headers.put("Accept", "application/json");
        headers.put("Content-Encoding", "gzip");
        headers.put("Connection", "close");
        check();
        OfficialApiClient.Response response = modelTransport.execute(new OfficialApiClient.Request(signed.url, "POST", headers, body, deadline));
        check();
        traceV5("player/v1 vid=" + vid + " http=" + response.status + " bytes=" + response.body.length);
        // Per-attempt hollow bookkeeping (a deadline-killed fetch must still arm the
        // fast-fail): every empty v5 answer counts, every served one clears it.
        if (response.body.length == 0) noteHollowFetch(); else HOLLOW_STREAK.set(0);
        return adopt(response, vid, deadline);
    }
    private JSONObject videoTab(String did, String iid, String vid) throws Exception {
        TreeMap<String, String> tabQuery = query(did, iid, "73932", "7.3.9.32");
        long tabTicket = System.currentTimeMillis(), tabKhronos = tabTicket / 1000;
        tabQuery.put("ts", Long.toString(tabKhronos)); tabQuery.put("_rticket", Long.toString(tabTicket));
        tabQuery.put("video_id", vid); tabQuery.put("video_id_type", "0"); tabQuery.put("device_level", "1");
        tabQuery.put("need_all_video_definition", "1"); tabQuery.put("need_mp4_align", "0"); tabQuery.put("use_os_player", "0");
        tabQuery.put("video_platform", "1024"); tabQuery.put("source", "0"); tabQuery.put("from_video_id", "");
        String tabString = PassportSigner.encode(tabQuery);
        Map<String, String> tabHeaders = new LinkedHashMap<>(PlayerSigner.signPlayer(tabString, null, tabKhronos));
        tabHeaders.put("User-Agent", playbackUserAgent("73932")); tabHeaders.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
        tabHeaders.put("X-XS-From-Web", "0"); tabHeaders.put("Passport-Sdk-Version", "50561");
        tabHeaders.put("Sdk-Version", "2"); tabHeaders.put("X-SS-DP", "8662");
        tabHeaders.put("X-Tt-Store-Region", "cn"); tabHeaders.put("X-Tt-Store-Region-Src", "did");
        tabHeaders.put("X-Tt-Request-Tag", "t=0;n=0");
        check();
        OfficialApiClient.Response tabResponse = modelTransport.execute(new OfficialApiClient.Request(VIDEO_TAB_API + "?" + tabString, "GET", tabHeaders, new byte[0], deadline));
        check();
        traceV5("video_tab vid=" + vid + " http=" + tabResponse.status + " bytes=" + tabResponse.body.length);
        return adopt(tabResponse, vid, deadline);
    }
    private JSONObject multiPost(String did, String iid, String vid) throws Exception {
        TreeMap<String, String> query = query(did, iid, "74332", "7.4.3.32");
        long ticket = System.currentTimeMillis(), khronos = ticket / 1000;
        // The verified playback profile carries ts/_rticket inside the signed query itself.
        query.put("ts", Long.toString(khronos)); query.put("_rticket", Long.toString(ticket));
        JSONObject payload = new JSONObject()
            .put("biz_param", new JSONObject().put("detail_page_version", 0).put("device_level", 3).put("disable_digg_stat", false)
                .put("need_all_video_definition", true).put("need_mp4_align", false).put("use_os_player", false).put("use_server_dns", false).put("video_platform", 1024))
            .put("mixed_video_id_map", new JSONObject().put("1004", new JSONArray().put(vid)));
        byte[] body = gzip(payload.toString().getBytes(StandardCharsets.UTF_8));
        String queryString = PassportSigner.encode(query);
        Map<String, String> headers = new LinkedHashMap<>(PlayerSigner.signPlayer(queryString, body, khronos));
        headers.put("User-Agent", playbackUserAgent("74332")); headers.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
        headers.put("Content-Type", "application/json; charset=UTF-8"); headers.put("Content-Encoding", "gzip");
        headers.put("X-XS-From-Web", "0"); headers.put("Passport-Sdk-Version", "5051452");
        headers.put("Sdk-Version", "2"); headers.put("X-SS-DP", "8662");
        headers.put("X-Tt-Store-Region", "cn"); headers.put("X-Tt-Store-Region-Src", "did");
        headers.put("Connection", "close");
        check();
        OfficialApiClient.Response response = modelTransport.execute(new OfficialApiClient.Request(API + "?" + queryString, "POST", headers, body, deadline));
        check();
        traceV5("legacy vid=" + vid + " http=" + response.status + " bytes=" + response.body.length);
        return adopt(response, vid, deadline);
    }
    private static TreeMap<String, String> query(String did, String iid, String versionCode, String versionName) {
        TreeMap<String, String> query = new TreeMap<>();
        String[][] fields = {{"ac","wifi"},{"aid","8662"},{"app_name","novelread"},{"channel","update_64"},{"device_brand","Redmi"},{"device_id",did},{"device_platform","android"},{"device_type","25053RT47C"},{"dpi","520"},{"iid",iid},{"language","zh"},{"manifest_version_code",versionCode},{"os","android"},{"os_api","36"},{"os_version","16"},{"resolution","1280*2772"},{"ssmix","a"},{"update_version_code",versionCode},{"version_code",versionCode},{"version_name",versionName}};
        for (String[] row : fields) query.put(row[0], row[1]); return query;
    }
    private static String playbackUserAgent(String versionCode) { return DanmakuSigner.USER_AGENT.replace("73532", versionCode); }
    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(plain); }
        return out.toByteArray();
    }
    /** One diagnostic line per player/v1 attempt; inert off-device (no android.util.Log). */
    private static void traceV5(String message) {
        try { android.util.Log.i("HongguoV5", message); } catch (Throwable ignored) { }
    }
    /** Shared response adoption for the multi-video POST and the video_tab recovery GET. */
    private JSONObject adopt(OfficialApiClient.Response response, String vid, long deadline) throws Exception {
        check();
        if (response == null || response.body.length > MAX_BYTES) throw new ModelError("App片源模型响应超过大小限制", "APP_INVALID_RESPONSE");
        if (response.status != 200) throw new ModelError("App片源服务返回 HTTP " + response.status, "HTTP_ERROR");
        byte[] data = response.body;
        String encoding = response.header("Content-Encoding");
        if (encoding.equalsIgnoreCase("gzip")) { try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) { data = read(in, deadline); } }
        else if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("identity")) throw new ModelError("App片源模型响应编码无效", "APP_INVALID_RESPONSE");
        if (new String(data, StandardCharsets.UTF_8).trim().isEmpty()) throw new ModelError("App接口未返回有效数据", "APP_EMPTY_RESPONSE");
        JSONObject envelope;
        try { envelope = OfficialApiClient.parse(data); }
        catch (Exception invalid) { check(); throw new ModelError("App片源模型响应格式无效", "APP_INVALID_RESPONSE"); }
        if (!zero(envelope.opt("code")) || envelope.has("error_code") && !zero(envelope.opt("error_code")))
            throw new ModelError("App接口暂未提供本集播放信息", "BUSINESS_REJECTED");
        // The legacy POST nests the entry under data[vid]; video_tab adds one more video_model_data
        // layer; player/v1 groups the episode map under data["1004"].
        JSONObject dataObject = envelope.optJSONObject("data");
        if (dataObject == null) throw new ModelError("App接口未返回本集片源模型", "APP_INVALID_RESPONSE");
        JSONObject payload = dataObject.optJSONObject(vid);
        if (payload == null) { JSONObject grouped = dataObject.optJSONObject("1004"); if (grouped != null) payload = grouped.optJSONObject(vid); }
        if (payload == null) payload = dataObject.optJSONObject("video_model_data") != null ? dataObject.optJSONObject("video_model_data").optJSONObject(vid) : null;
        if (payload == null) throw new ModelError("App接口未返回本集片源模型", "APP_INVALID_RESPONSE");
        identity(envelope, vid); identity(dataObject, vid); identity(payload, vid);
        rejectTrial(envelope); rejectTrial(dataObject); rejectTrial(payload);
        Object raw = payload.opt("video_model");
        JSONObject model;
        if (raw instanceof JSONObject) model = (JSONObject) raw;
        else if (raw instanceof String) { try { model = OfficialApiClient.parse(((String) raw).getBytes(StandardCharsets.UTF_8)); } catch (Exception invalid) { throw new ModelError("App片源模型响应格式无效", "APP_INVALID_RESPONSE"); } }
        else throw new ModelError("App接口未返回本集片源模型", "APP_INVALID_RESPONSE");
        identity(model, vid); rejectTrial(model);
        JSONObject projected = project(model); check();
        return projected;
    }
    private static boolean zero(Object value) { return value instanceof Number && Double.isFinite(((Number) value).doubleValue()) && ((Number) value).doubleValue() == 0 || value instanceof String && value.equals("0"); }
    // VOD video_id/vid strings are not the numeric episode item_id namespace.
    private static void identity(JSONObject object, String vid) throws IOException {
        for (String key : new String[]{"item_id", "episode_id"}) if (object.has(key)) {
            Object value = object.opt(key); String text = value instanceof String || value instanceof Number ? value.toString() : "";
            if (!vid.equals(text)) throw new ModelError("App片源与分集不一致", "APP_INVALID_RESPONSE");
        }
    }
    /** SaaSVideoModelData carries this outside video_model. Do not discard a
     * positive trial verdict while projecting the inner playback model. */
    private static void rejectTrial(JSONObject object) throws IOException {
        if (!object.has("is_trial_video")) return;
        Object value = object.opt("is_trial_video");
        if (!(value instanceof Boolean)) throw new ModelError("App片源试看状态无效", "APP_INVALID_RESPONSE");
        if (Boolean.TRUE.equals(value)) throw new ModelError("App接口仅提供本集试看，请在官方App查看", "BUSINESS_REJECTED");
    }
    private static void scalars(JSONObject from, JSONObject to, String... fields) throws Exception {
        for (String key : fields) if (from.has(key)) {
            Object value = from.opt(key);
            if (value != JSONObject.NULL && !(value instanceof String || value instanceof Number || value instanceof Boolean) || value instanceof String && ((String) value).length() > 16384)
                throw new ModelError("App片源模型字段无效", "APP_INVALID_RESPONSE");
            to.put(key, value);
        }
    }
    /** Keep only media fields consumed by the existing parser, never arbitrary session/identity data. */
    private static JSONObject project(JSONObject model) throws Exception {
        Object videos = model.opt("video_list"); List<Object> rows = new ArrayList<>();
        if (videos instanceof JSONArray) { JSONArray array = (JSONArray) videos; for (int i = 0; i < array.length(); i++) rows.add(array.opt(i)); }
        else if (videos instanceof JSONObject) { JSONObject object = (JSONObject) videos; for (Iterator<String> i = object.keys(); i.hasNext();) rows.add(object.opt(i.next())); }
        else throw new ModelError("App片源模型缺少清晰度列表", "APP_INVALID_RESPONSE");
        if (rows.isEmpty() || rows.size() > 128) throw new ModelError("App片源模型清晰度列表无效", "APP_INVALID_RESPONSE");
        JSONArray clean = new JSONArray();
        for (Object value : rows) {
            if (!(value instanceof JSONObject)) throw new ModelError("App片源模型清晰度无效", "APP_INVALID_RESPONSE");
            JSONObject row = (JSONObject) value, item = new JSONObject();
            scalars(row, item, "main_url", "backup_url", "codec_type", "quality_desc", "definition", "width", "height", "vwidth", "vheight", "spade_a");
            if (row.has("backup_url_list")) {
                JSONArray urls = row.optJSONArray("backup_url_list"); if (urls == null || urls.length() > 16) throw new ModelError("App备用片源列表无效", "APP_INVALID_RESPONSE");
                JSONArray copy = new JSONArray(); for (int i = 0; i < urls.length(); i++) { Object url = urls.opt(i); if (!(url instanceof String) || ((String) url).length() > 16384) throw new ModelError("App备用片源地址无效", "APP_INVALID_RESPONSE"); copy.put(url); } item.put("backup_url_list", copy);
            }
            if (row.has("video_meta")) { JSONObject meta = row.optJSONObject("video_meta"); if (meta == null) throw new ModelError("App片源尺寸信息无效", "APP_INVALID_RESPONSE"); JSONObject copy = new JSONObject(); scalars(meta, copy, "codec_type", "definition", "width", "height", "vwidth", "vheight", "bitrate"); item.put("video_meta", copy); }
            if (row.has("encrypt_info")) { JSONObject encryption = row.optJSONObject("encrypt_info"); if (encryption == null) throw new ModelError("App片源准备信息无效", "APP_INVALID_RESPONSE"); JSONObject copy = new JSONObject(); scalars(encryption, copy, "spade_a", "encrypt"); item.put("encrypt_info", copy); }
            clean.put(item);
        }
        JSONObject result = new JSONObject().put("video_list", clean); scalars(model, result, "video_duration", "duration", "key_seed"); return result;
    }
    private static byte[] read(InputStream input, long deadline) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        for (;;) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("App片源请求已取消");
            if (System.nanoTime() >= deadline) throw new SocketTimeoutException("App片源请求总等待超时");
            count = input.read(buffer); if (count < 0) break;
            if (count > 0) { if (output.size() + count > MAX_BYTES) throw new ModelError("App片源模型响应超过大小限制", "APP_INVALID_RESPONSE"); output.write(buffer, 0, count); }
        }
        return output.toByteArray();
    }
    static final class ModelHttpTransport implements OfficialApiClient.Transport {
        /** Wire engine seam: production runs OkHttp (the v5 endpoint hollows plain
         * HttpURLConnection requests — HTTP/1.1 without ALPN — with empty 200s, while
         * OkHttp's h2 clients are answered); tests inject a capturing fake. The pin is the
         * CDN node this attempt targets (null = system resolution). */
        interface Engine { OfficialApiClient.Response call(URI uri, OfficialApiClient.Request request, int timeoutMs, java.net.InetAddress pin) throws Exception; }
        private final Engine engine;
        // One lookup owns its calls, while SHARED_CLIENT still owns the shared h2 pool.
        // The same monitor serializes close and registration: a call created while
        // close is running either joins its owner's cancellation snapshot or never executes.
        private final java.util.Set<okhttp3.Call> activeCalls = new java.util.HashSet<>();
        private volatile boolean closed;
        ModelHttpTransport() { this.engine = this::productionOkhttpCall; }
        ModelHttpTransport(Engine engine) { this.engine = Objects.requireNonNull(engine); }
        @Override public void close() {
            okhttp3.Call[] owned;
            synchronized (activeCalls) {
                if (closed) return;
                closed = true;
                owned = activeCalls.toArray(new okhttp3.Call[0]);
            }
            for (okhttp3.Call call : owned) call.cancel();
        }
        static URI validate(OfficialApiClient.Request request) throws Exception {
            URI uri = new URI(request.url);
            boolean post = "/novel/player/multi_video_model/v1/".equals(uri.getRawPath()) && request.method.equals("POST");
            boolean tab = "/reading/bookapi/video_tab/multi_video_model/v1/".equals(uri.getRawPath()) && request.method.equals("GET") && request.body.length == 0;
            // The v5 player/v1 form lives on the sinfonlinea host; everything else stays on sinfonlineb.
            boolean player = "/novel/player/multi_video_model/player/v1".equals(uri.getRawPath()) && request.method.equals("POST")
                && "api5-normal-sinfonlinea.fqnovel.com".equals(uri.getHost());
            if (!"https".equals(uri.getScheme()) || !player && !"api5-normal-sinfonlineb.fqnovel.com".equals(uri.getHost()) || !post && !tab && !player || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null || request.body.length > 128 * 1024)
                throw new ModelError("App片源服务地址无效", "INVALID_INPUT");
            return uri;
        }
        private void check(long deadline) throws IOException {
            if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("App片源请求已取消");
            if (System.nanoTime() >= deadline) throw new SocketTimeoutException("App片源请求总等待超时");
        }
        /** OkHttp engine: h2 via ALPN, exact signed headers, callTimeout bounds the whole exchange.
         * The model endpoints sit on a rotating CDN pool where some nodes hollow requests with
         * empty 200s while others serve; the engine pins each request to one node from the
         * pool (system answer when unavailable) and the fetch ladder rotates the node. */
        private OfficialApiClient.Response productionOkhttpCall(URI uri, OfficialApiClient.Request request, int timeoutMs, java.net.InetAddress pin) throws Exception {
            okhttp3.OkHttpClient.Builder client = SHARED_CLIENT.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS);
            if (pin != null) {
                client.dns(hostname -> {
                    if (hostname.equals(uri.getHost())) return java.util.Collections.singletonList(pin);
                    return java.util.Arrays.asList(java.net.InetAddress.getAllByName(hostname));
                });
            }
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(uri.toURL());
            if (request.method.equals("POST")) builder.post(okhttp3.RequestBody.create(request.body));
            else builder.get();
            for (Map.Entry<String, String> header : request.headers.entrySet()) builder.header(header.getKey(), header.getValue());
            builder.header("Accept-Encoding", "gzip");
            okhttp3.Call call = client.build().newCall(builder.build());
            return executeTrackedCall(call, request);
        }
        private OfficialApiClient.Response executeTrackedCall(okhttp3.Call call, OfficialApiClient.Request request) throws Exception {
            boolean registered = false;
            try {
                synchronized (activeCalls) {
                    check(request.deadlineNanos);
                    activeCalls.add(call);
                    registered = true;
                }
                // close may have won immediately after registration. OkHttp also
                // remembers cancellation if close runs between this check and execute.
                check(request.deadlineNanos);
                OfficialApiClient.Response response = consume(call.execute(), request);
                check(request.deadlineNanos);
                return response;
            } finally {
                if (registered) {
                    synchronized (activeCalls) { activeCalls.remove(call); }
                } else call.cancel();
            }
        }
        private static final okhttp3.OkHttpClient SHARED_CLIENT = new okhttp3.OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).protocols(java.util.Arrays.asList(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .build();
        /** The registration and the model must share one client so the identity and its
         * model request ride the same pooled h2 connections, like the working TV clients. */
        static okhttp3.OkHttpClient sharedClient() { return SHARED_CLIENT; }
        private static OfficialApiClient.Response consume(okhttp3.Response response, OfficialApiClient.Request request) throws Exception {
            try {
                int status = response.code();
                if (status != 200) return new OfficialApiClient.Response(status, Collections.emptyMap(), new byte[0]);
                byte[] body = read(response.body().byteStream(), request.deadlineNanos);
                return new OfficialApiClient.Response(status, response.headers().toMultimap(), body);
            } finally { response.close(); }
        }
        @Override public OfficialApiClient.Response execute(OfficialApiClient.Request request) throws Exception {
            check(request.deadlineNanos); URI uri = validate(request);
            check(request.deadlineNanos); int timeout = (int) Math.max(1, Math.min(7000, TimeUnit.NANOSECONDS.toMillis(request.deadlineNanos - System.nanoTime())));
            java.net.InetAddress pin = nextNode(uri.getHost());
            try {
                OfficialApiClient.Response response = engine.call(uri, request, timeout, pin);
                check(request.deadlineNanos);
                return response;
            } catch (Exception failure) { check(request.deadlineNanos); throw failure; }
        }
        /** One CDN node per attempt, rotating over the current DNS answer; null = system resolution. */
        private java.net.InetAddress nextNode(String host) {
            try {
                java.net.InetAddress[] nodes = java.net.InetAddress.getAllByName(host);
                int index = nodeCursor.getAndIncrement() % nodes.length;
                return nodes[index];
            } catch (Exception broken) { return null; }
        }
        private static final java.util.concurrent.atomic.AtomicInteger nodeCursor = new java.util.concurrent.atomic.AtomicInteger();
    }
    /** Shared OkHttp client for the whole registered-identity lifecycle (register + model). */
    static okhttp3.OkHttpClient modelSharedClient() { return ModelHttpTransport.sharedClient(); }
}
