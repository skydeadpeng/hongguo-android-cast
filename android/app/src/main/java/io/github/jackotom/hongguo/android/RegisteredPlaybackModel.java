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

/** Anonymous registered playback identity, isolated from account sessions. */
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
    /** User-invoked identity reset: forget the shared and persisted identity so the next
     * foreground resolve registers a fresh one. Watch history and downloads are untouched.
     * Returns whether a persisted identity actually existed and was discarded. */
    static synchronized boolean resetSharedIdentity() {
        String[] previous = SHARED_IDENTITY.getAndSet(null);
        java.io.File file = identityFile;
        boolean persisted = file != null && file.isFile() && file.delete();
        return (previous != null) || persisted;
    }
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
        SdkPlaybackClient.cancel();
        try { registration.close(); } catch (RuntimeException ignored) { }
        try { modelTransport.close(); } catch (RuntimeException ignored) { }
        Thread thread = owner; if (thread != null && thread != Thread.currentThread()) thread.interrupt();
    }
    JSONObject fetch(String vid) throws Exception { return fetch(vid, false); }
    /** Speculative prefetch must not spend a foreground compatibility request. */
    JSONObject fetch(String vid, boolean speculative) throws Exception {
        if (vid == null || !vid.matches("[0-9]{1,30}")) throw new ModelError("分集请求信息无效", "INVALID_INPUT");
        if (!started.compareAndSet(false, true)) throw new IOException("App片源请求已经结束");
        owner = Thread.currentThread();
        try {
            check();
            // 2026-11-23 evening: the platform stopped answering every locally signed shape. The SDK
            // route (SdkPlaybackClient) leads on devices that can run it; whenever it cannot serve an
            // episode — unsupported device, not prepared yet, refusal or empty answer — the ladder
            // below runs exactly as before.
            JSONObject sdkModel = sdkModel(vid, speculative);
            check();
            if (sdkModel != null) {
                HOLLOW_STREAK.set(0); DENIAL_ROTATIONS.set(0);
                return new JSONObject().put("vid", vid).put("video_model", sdkModel);
            }
            check();
            // This fresh in-memory device is unrelated to AccountSync/OfficialAccountVault.
            // The upstream registration line matches the working TV clients' profile; the
            // older PassportDevice line yields identities the v5 model endpoint hollows.
            check();
            String[] identity;
            try { identity = bootstrap.register(); traceV5("bootstrap ok did=" + identity[0] + (identity[0].equals(SHARED_IDENTITY.get() == null ? "" : SHARED_IDENTITY.get()[0]) ? " (shared)" : " (fresh)")); }
            catch (Exception failure) { traceV5("bootstrap failed: " + failure); check(); throw failure; }
            check();
            String did = identity[0], iid = identity[1];
            String cdid = identity.length > 2 && identity[2] != null && !identity[2].isEmpty() ? identity[2] : PlaybackSignerV5.newCdid();
            String cookies = identity.length > 3 && identity[3] != null ? identity[3] : "";
            if (!OfficialApiClient.validID(did) || !OfficialApiClient.validID(iid))
                throw new ModelError("App片源设备登记未完成", "APP_INVALID_RESPONSE");
            // 2026-11-23: the platform answers only the V5-signed bodyless video_tab GET;
            // the legacy Argus tab GET and the v5 POST both earn hollow 200s, so the V5
            // GET leads and the v5 POST and the legacy tab GET follow (each was the sole
            // served shape earlier that week). When every shape answers hollow for a
            // foreground fetch, rotate the identity once (fresh registration, 30s quota)
            // and walk the same ladder on it. A degraded identity instead answers with
            // business denials (code!=0): those also rotate once, bounded by a process
            // allowance so a looping failure cannot spend registrations forever. HTTP,
            // format failures, service backoff and trial markers stay terminal;
            // speculative prefetch never spends a registration.
            String[] used = identity;
            JSONObject projected;
            try {
                projected = resolve(did, iid, cdid, vid, cookies);
            } catch (ModelError denialFailure) {
                if (!"BUSINESS_DENIED".equals(denialFailure.code) || speculative
                    || !denialAutoRotateAllowed() || !mayRotateForeground()) throw denialFailure;
                noteDenialRotation();
                String[] rotated = reRegister();
                if (rotated == null) throw denialFailure;
                String rCdid = rotated.length > 2 && rotated[2] != null && !rotated[2].isEmpty() ? rotated[2] : PlaybackSignerV5.newCdid();
                String rCookies = rotated.length > 3 && rotated[3] != null ? rotated[3] : "";
                traceV5("denial-rotated did=" + rotated[0]);
                projected = resolve(rotated[0], rotated[1], rCdid, vid, rCookies);
                used = rotated;
            }
            if (projected == null) {
                if (speculative || !mayRotateForeground()) throw new ModelError("App接口未返回有效数据", "APP_EMPTY_RESPONSE");
                String[] rotated = reRegister();
                if (rotated == null) throw new ModelError("App接口未返回有效数据", "APP_EMPTY_RESPONSE");
                String rCdid = rotated.length > 2 && rotated[2] != null && !rotated[2].isEmpty() ? rotated[2] : PlaybackSignerV5.newCdid();
                String rCookies = rotated.length > 3 && rotated[3] != null ? rotated[3] : "";
                traceV5("rotated did=" + rotated[0]);
                projected = resolve(rotated[0], rotated[1], rCdid, vid, rCookies);
                used = rotated;
                if (projected == null) throw new ModelError("App接口未返回有效数据", "APP_EMPTY_RESPONSE");
            }
            check();
            HOLLOW_STREAK.set(0);
            DENIAL_ROTATIONS.set(0);
            persistIdentity(used);
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
    /** The SDK route's answer, projected like the ladder's, or null for the ladder to take over.
     * Cancellation, deadline, service backoff and trial-only verdicts are terminal and surface
     * unchanged; every other SDK failure only changes which source serves this episode. */
    private JSONObject sdkModel(String vid, boolean speculative) throws Exception {
        OfficialApiClient.Response response;
        try { response = SdkPlaybackClient.fetch(vid, deadline, speculative); }
        catch (Exception failure) { check(); traceV5("sdk failed: " + failure); return null; }
        if (response == null) return null;
        try { return adopt(response, vid, deadline); }
        catch (ModelError failure) {
            check();
            // An empty SDK answer counts towards the hollow fast-fail like any other empty v5 answer.
            if ("APP_EMPTY_RESPONSE".equals(failure.code)) noteHollowFetch();
            if ("APP_SERVICE_BACKOFF".equals(failure.code) || "BUSINESS_REJECTED".equals(failure.code)) throw failure;
            traceV5("sdk not adopted: " + failure.code);
            return null;
        }
    }
    /** Foreground-only rotation quota: at most one fresh registration per 30 seconds
     * (hongguoTV playbackRotationAt). Claimed before registering so concurrent fetches
     * cannot each spend a registration on the same failure. */
    private static volatile long lastRotateClaimMs;
    private static boolean mayRotateForeground() {
        long now = System.currentTimeMillis();
        if (now - lastRotateClaimMs < 30_000) return false;
        lastRotateClaimMs = now;
        return true;
    }
    /** Test hook: clears the 30s rotation quota between offline cases. */
    static void resetRotationQuotaForTest() { lastRotateClaimMs = 0; }
    /** Business-denial rotation allowance: a degraded identity answers code!=0 instead of
     * hollow bodies, so denials rotate too — at most three fresh registrations per quiet
     * window per process. Any served model clears the count; the manual About-page reset
     * is the unbounded escape hatch. */
    private static final AtomicInteger DENIAL_ROTATIONS = new AtomicInteger();
    private static volatile long denialMarkMs;
    private static boolean denialAutoRotateAllowed() {
        if (System.currentTimeMillis() - denialMarkMs >= 600_000) DENIAL_ROTATIONS.set(0);
        return DENIAL_ROTATIONS.getAndIncrement() < 3;
    }
    private static void noteDenialRotation() { denialMarkMs = System.currentTimeMillis(); }
    /** Test hook: clears the denial-rotation allowance between offline cases. */
    static void resetDenialAllowanceForTest() { DENIAL_ROTATIONS.set(0); denialMarkMs = 0; }
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
    /** Playback model ladder: the V5 video_tab GET leads; the v5 POST and the legacy
     * Argus tab GET follow. Tab failures (typed errors, malformed gzip) fall through
     * like tabVideoModel's null return; cancellation and deadline still win first.
     * Returns null when the ladder is still hollow after the v5 POST answered empty —
     * the rotation signal. Any other v5 POST failure propagates unchanged. */
    private JSONObject resolve(String did, String iid, String cdid, String vid, String cookies) throws Exception {
        try { return videoTabV5(did, iid, cdid, vid); }
        catch (ModelError | java.util.zip.ZipException | java.io.EOFException tabFailure) { check(); }
        try { return playerV1(did, iid, cdid, vid); }
        catch (ModelError postFailure) {
            check();
            if (!"APP_EMPTY_RESPONSE".equals(postFailure.code)) throw postFailure;
        }
        try { return videoTab(did, iid, cdid, vid, cookies); }
        catch (ModelError | java.util.zip.ZipException | java.io.EOFException tabFailure) { check(); }
        return null;
    }
    /** The v5 player profile both V5 shapes share (jackotom 7.7, hongguoTV 0.1.37). */
    private static Map<String, String> v5Query(String did, String iid, String cdid) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("aid", "8662"); query.put("app_name", "novelread");
        query.put("version_code", "74332"); query.put("version_name", "7.4.3.32");
        query.put("channel", "vivo_8662_64"); query.put("device_platform", "android");
        query.put("device_type", "PGT-AN10"); query.put("os_version", "12"); query.put("update_version_code", "74332");
        query.put("iid", iid);
        query.put("cdid", cdid);
        query.put("device_id", did);
        return query;
    }
    /** Leading shape since 2026-11-23: the V5-signed bodyless video_tab GET. The empty
     * body makes every digest MD5(""), and no registration or account cookie is sent,
     * exactly like the v5 POST. The platform drops the legacy Argus GET below with a
     * hollow 200. */
    private JSONObject videoTabV5(String did, String iid, String cdid, String vid) throws Exception {
        check();
        Map<String, String> query = v5Query(did, iid, cdid);
        query.put("video_id", vid); query.put("video_id_type", "0"); query.put("device_level", "1");
        query.put("need_all_video_definition", "1"); query.put("need_mp4_align", "0"); query.put("use_os_player", "0");
        query.put("video_platform", "1024"); query.put("source", "0"); query.put("from_video_id", "");
        PlaybackSignerV5.Request signed = PlaybackSignerV5.signPlayback(VIDEO_TAB_API, query, new byte[0], System.currentTimeMillis());
        Map<String, String> headers = new LinkedHashMap<>(signed.headers);
        headers.put("User-Agent", PlaybackSignerV5.PLAYER_V1_UA);
        headers.put("Accept", "application/json");
        check();
        OfficialApiClient.Response response;
        try { response = modelTransport.execute(new OfficialApiClient.Request(signed.url, "GET", headers, signed.body, deadline)); }
        catch (ConnectException refused) {
            check(); // Cancellation/deadline wins over the connection classification.
            throw new ModelError("App片源连接暂未建立，请重试", "ECONNREFUSED");
        }
        check();
        traceV5("video_tab/v5 vid=" + vid + " http=" + response.status + " bytes=" + response.body.length);
        return adopt(response, vid, deadline);
    }
    /** v5 player request, second in the ladder: gzip JSON and no account/registration cookies. */
    private JSONObject playerV1(String did, String iid, String cdid, String vid) throws Exception {
        Map<String, String> query = v5Query(did, iid, cdid);
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
        OfficialApiClient.Response response;
        try { response = modelTransport.execute(new OfficialApiClient.Request(signed.url, "POST", headers, body, deadline)); }
        catch (ConnectException refused) {
            check(); // Cancellation/deadline wins over the connection classification.
            throw new ModelError("App片源连接暂未建立，请重试", "ECONNREFUSED");
        }
        check();
        traceV5("player/v1 vid=" + vid + " http=" + response.status + " bytes=" + response.body.length);
        // Per-attempt hollow bookkeeping (a deadline-killed fetch must still arm the
        // fast-fail): every empty v5 answer counts, every served one clears it.
        if (response.body.length == 0) noteHollowFetch(); else HOLLOW_STREAK.set(0);
        return adopt(response, vid, deadline);
    }
    private JSONObject videoTab(String did, String iid, String cdid, String vid, String cookies) throws Exception {
        check();
        PlaybackSignerV5.Request signed = TabPlaybackSigner.sign(did, iid, cdid, vid, cookies, System.currentTimeMillis());
        check();
        OfficialApiClient.Response response;
        try { response = modelTransport.execute(new OfficialApiClient.Request(signed.url, "GET", signed.headers, signed.body, deadline)); }
        catch (ConnectException refused) {
            check(); // Cancellation/deadline wins over the connection classification.
            throw new ModelError("App片源连接暂未建立，请重试", "ECONNREFUSED");
        }
        check();
        traceV5("video_tab vid=" + vid + " http=" + response.status + " bytes=" + response.body.length);
        return adopt(response, vid, deadline);
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
        if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("gzip") && !encoding.equalsIgnoreCase("identity"))
            throw new ModelError("App片源模型响应编码无效", "APP_INVALID_RESPONSE");
        // Explicit Accept-Encoding keeps this header on hollow HTTP 200 replies.
        // No wire bytes means no gzip member: classify empty/backoff below instead of EOF.
        if (encoding.equalsIgnoreCase("gzip") && data.length > 0) { try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) { data = read(in, deadline); } }
        if (new String(data, StandardCharsets.UTF_8).trim().isEmpty()) {
            if (!response.header("Retry-After").trim().isEmpty()) throw new ModelError("播放服务繁忙，请稍后重试", "APP_SERVICE_BACKOFF");
            throw new ModelError("App接口未返回有效数据", "APP_EMPTY_RESPONSE");
        }
        JSONObject envelope;
        try { envelope = OfficialApiClient.parse(data); }
        catch (Exception invalid) { check(); throw new ModelError("App片源模型响应格式无效", "APP_INVALID_RESPONSE"); }
        if (!zero(envelope.opt("code")) || envelope.has("error_code") && !zero(envelope.opt("error_code")))
            // code!=0 is how a degraded identity is told no; the denial-rotation path
            // (BUSINESS_DENIED) treats it as transient. Trial markers below stay
            // BUSINESS_REJECTED: those describe the episode, not the identity.
            throw new ModelError("App接口暂未提供本集播放信息", "BUSINESS_DENIED");
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
        interface NodeSelector { java.net.InetAddress select(String host); }
        private final Engine engine;
        private final NodeSelector nodeSelector;
        // One lookup owns its calls, while SHARED_CLIENT still owns the shared h2 pool.
        // The same monitor serializes close and registration: a call created while
        // close is running either joins its owner's cancellation snapshot or never executes.
        private final java.util.Set<okhttp3.Call> activeCalls = new java.util.HashSet<>();
        private volatile boolean closed;
        ModelHttpTransport() { this.engine = this::productionOkhttpCall; this.nodeSelector = this::nextNode; }
        // An injected wire engine is offline unless its test explicitly supplies a selector.
        ModelHttpTransport(Engine engine) { this(engine, host -> null); }
        ModelHttpTransport(Engine engine, NodeSelector nodeSelector) {
            this.engine = Objects.requireNonNull(engine); this.nodeSelector = Objects.requireNonNull(nodeSelector);
        }
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
            // The signed series detail (complete episode list, 2026-11-27) shares the model host and client.
            boolean detail = "/novel/player/video_detail/v1/".equals(uri.getRawPath()) && request.method.equals("POST");
            // The v5 player/v1 form lives on the sinfonlinea host; everything else stays on sinfonlineb.
            boolean player = "/novel/player/multi_video_model/player/v1".equals(uri.getRawPath()) && request.method.equals("POST")
                && "api5-normal-sinfonlinea.fqnovel.com".equals(uri.getHost());
            if (!"https".equals(uri.getScheme()) || !player && !"api5-normal-sinfonlineb.fqnovel.com".equals(uri.getHost()) || !post && !tab && !player && !detail || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null || request.body.length > 128 * 1024)
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
            // Compatibility GET retains OkHttp's full system DNS address list and
            // route selection. The existing POST node-selection policy is unchanged.
            java.net.InetAddress pin = request.method.equals("GET") ? null : nodeSelector.select(uri.getHost());
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
