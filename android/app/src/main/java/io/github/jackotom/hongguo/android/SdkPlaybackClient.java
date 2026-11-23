package io.github.jackotom.hongguo.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** The SDK playback route: an anonymous device, a session token from the SDK exchange and the signed
 *  player request that carries it. The Application arms it on arm64 devices only; it is inert until
 *  prepared, and switches itself off for the process once it keeps failing, so the signed-request
 *  ladder underneath always remains the fallback. */
final class SdkPlaybackClient {
    static final int MAX_MODEL_BYTES = 2 * 1024 * 1024, MAX_SESSION_BYTES = 128 * 1024, MAX_IDENTITY_BYTES = 4 * 1024 * 1024;
    /** The route may not eat the ladder's budget: at most this long out of one fetch, ladder reserve kept. */
    static final long ROUTE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(8), LADDER_RESERVE_NANOS = TimeUnit.SECONDS.toNanos(9);
    static final long SESSION_LIFETIME_NANOS = TimeUnit.MINUTES.toNanos(30), PREPARE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(60), SHORT_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** Silence on the SDK material connection that counts as a stall rather than a slow link. */
    private static final int STALL_MILLIS = 20_000;
    private static final int COMPRESSION = java.util.zip.Deflater.DEFAULT_COMPRESSION, FAILURE_LIMIT = 3;
    private static volatile Engine ENGINE;
    private static volatile okhttp3.Call ACTIVE;

    private SdkPlaybackClient() { }

    /** Arms the route: verifies and caches the SDK inputs on a background thread. Never blocks the caller. */
    static synchronized void install(File directory) {
        if (ENGINE != null || directory == null || !SdkDigestBridge.supported()) return;
        ENGINE = new Engine(directory);
        ENGINE.startPrepare();
    }

    /** Cancels the request in flight; the model's close() calls this, like its own transports. */
    static void cancel() { okhttp3.Call call = ACTIVE; if (call != null) call.cancel(); }

    /** The player/v1 response for `vid`, or null when the route cannot serve it and the ladder should take over. */
    static OfficialApiClient.Response fetch(String vid, long deadlineNanos, boolean speculative) throws Exception {
        Engine engine = ENGINE;
        if (engine == null || vid == null || !vid.matches("[1-9][0-9]{7,23}")) return null;
        return engine.fetch(vid, deadlineNanos, speculative);
    }

    private static final class Engine {
        private final File directory;
        private final SecureRandom random = new SecureRandom();
        private final Object progress = new Object(), identity = new Object(), session = new Object();
        private final AtomicBoolean preparing = new AtomicBoolean();
        private volatile boolean prepared;
        /** Set once the route can never work in this process (bad image, native failure, repeated refusals). */
        private volatile String unavailable;
        private volatile SdkMaterials materials;
        private volatile Map<String, String> registration, cookies;
        private volatile long nextPrepare;
        private String token;
        private long ladon, sessionAt;
        private int failures;

        /** The native core behind every request's body proof; the signature binds the first 16 of its 20 bytes.
         *  A fault or an overran call inside the image switches the route off for the rest of the process. */
        private final SdkSigner.Core digest = SdkDigestBridge.core(SdkDigestBridge.NATIVE);
        private final SdkSigner.Core core = (payload, selector, seconds) -> {
            try { return digest.digest(payload, selector, seconds); }
            catch (SdkDigestBridge.Broken broken) { unavailable = broken.getMessage(); throw broken; }
        };

        Engine(File directory) { this.directory = directory; }

        private void startPrepare() {
            Thread worker = new Thread(this::prepare, "hongguo-sdk-prepare");
            worker.setDaemon(true);
            worker.start();
        }

        /** Verifies the cached or downloaded SDK inputs and maps the image. Runs without any lock, so a
         *  fetch never waits on it beyond its own budget; a transient failure is retried later. */
        private void prepare() {
            if (prepared || unavailable != null || !preparing.compareAndSet(false, true)) return;
            try {
                byte[][] assets = SdkAssets.ensure(directory, Engine::range, System.nanoTime() + TimeUnit.SECONDS.toNanos(90));
                SdkMaterials decoded = SdkMaterials.decode(assets[0], assets[1]);
                // Android 11+ runs memfd for executable app memory; older releases only have ashmem.
                int status = SdkDigestBridge.nativeLoad(assets[0], android.os.Build.VERSION.SDK_INT >= 30 ? 1 : 0);
                String detail = SdkDigestBridge.nativeDetail();
                if (status != 0) { unavailable = "native load " + status + (detail.isEmpty() ? "" : ": " + detail); return; }
                materials = decoded;
                Map<String, String>[] saved = savedIdentity();
                if (saved != null) { registration = saved[0]; cookies = saved[1]; }
                prepared = true;
                trace("ready did=" + (registration == null ? "-" : registration.get("device_id")));
            } catch (Exception failure) {
                trace("prepare failed: " + failure);
                // A download that ran out of time keeps its progress and is worth another try as soon as
                // playback asks; anything else waits, so a device that cannot be served is not asked on every tap.
                nextPrepare = System.nanoTime() + (failure instanceof InterruptedIOException ? SHORT_RETRY_NANOS : PREPARE_RETRY_NANOS);
            } finally {
                preparing.set(false);
                synchronized (progress) { progress.notifyAll(); }
            }
        }

        private OfficialApiClient.Response fetch(String vid, long deadlineNanos, boolean speculative) throws Exception {
            if (unavailable != null) return null;
            long stop = Math.min(deadlineNanos - LADDER_RESERVE_NANOS, System.nanoTime() + ROUTE_BUDGET_NANOS);
            if (stop <= System.nanoTime()) return null;
            if (!prepared && !awaitPrepare(stop, speculative)) return null;
            // A fresh registration is a foreground decision; prefetch only reuses a saved identity.
            if (registration == null) {
                if (speculative) return null;
                synchronized (identity) { if (registration == null && !register(stop)) return null; }
            }
            synchronized (session) {
                if (unavailable != null) return null;
                if (token == null || System.nanoTime() - sessionAt > SESSION_LIFETIME_NANOS) {
                    token = null;
                    if (!exchange(stop)) return note("exchange");
                }
                OfficialApiClient.Response response = playback(vid, stop);
                if (response == null) return null;
                if (response.status == 200 && response.body.length > 0) { failures = 0; return response; }
                // An empty answer may be a spent session: one fresh exchange and one retry, then the ladder takes over.
                token = null;
                if (!exchange(stop)) return note("re-exchange");
                response = playback(vid, stop);
                if (response == null) return null;
                if (response.status != 200 || response.body.length == 0) return note("empty answer http=" + response.status);
                failures = 0;
                return response;
            }
        }

        /** Waits for the background preparation inside this fetch's own budget; a prefetch never restarts it. */
        private boolean awaitPrepare(long stop, boolean speculative) throws InterruptedException {
            if (!speculative && !preparing.get() && System.nanoTime() >= nextPrepare) startPrepare();
            synchronized (progress) {
                while (!prepared && unavailable == null) {
                    long remaining = stop - System.nanoTime();
                    if (remaining <= 0) return false;
                    TimeUnit.NANOSECONDS.timedWait(progress, remaining);
                }
            }
            return prepared;
        }

        private OfficialApiClient.Response note(String stage) {
            trace("giving up: " + stage);
            if (++failures >= FAILURE_LIMIT) unavailable = "repeated " + stage;
            return null;
        }

        private boolean register(long stop) {
            try {
                long now = System.currentTimeMillis();
                String cdid = UUID.randomUUID().toString(), openudid = DanmakuSigner.hex(random(8));
                String clientudid = UUID.randomUUID().toString(), requestId = UUID.randomUUID().toString();
                byte[] seed = new byte[32];
                random.nextBytes(seed);
                String plain = SdkRequest.registrationPayload(now, cdid, openudid, clientudid, requestId);
                byte[] body = PassportDevice.encrypt(plain.getBytes(StandardCharsets.UTF_8), seed);
                Map<String, String> params = SdkRequest.registrationParams(now, cdid, openudid);
                OfficialApiClient.Response response = send(SdkRequest.registrationUrl(params), SdkRequest.registrationHeaders(now, body), body,
                    MAX_IDENTITY_BYTES, stop, SdkRequest.REGISTER_URL);
                if (response == null || response.status != 200) throw new IOException("SDK 设备登记 HTTP " + (response == null ? "-" : response.status));
                byte[] bytes = response.body;
                if (PassportDevice.encrypted(bytes)) bytes = PassportDevice.decrypt(bytes);
                JSONObject payload = OfficialApiClient.parse(bytes);
                String did = value(payload, "device_id"), iid = value(payload, "install_id");
                if (!OfficialApiClient.validID(did) || !OfficialApiClient.validID(iid)) throw new IOException("SDK 设备登记未返回身份");
                params.put("device_id", did); params.put("iid", iid);
                Map<String, String> jar = registrationCookies(response);
                saveIdentity(params, jar);
                registration = params; cookies = jar;
                trace("registered did=" + did);
                return true;
            } catch (Exception failure) { trace("register failed: " + failure); return false; }
        }

        private boolean exchange(long stop) {
            try {
                int pid = pid();
                long now = System.currentTimeMillis();
                SdkRequest.Signed first = SdkRequest.bootstrap(registration, cookies, now, materials, core, null, 0,
                    SdkRequest.Randoms.bootstrap(random), pid, COMPRESSION);
                OfficialApiClient.Response response = send(first.url, first.headers, first.body, MAX_SESSION_BYTES, stop, SdkRequest.EXCHANGE);
                if (response == null || response.status != 200 || response.body.length == 0)
                    throw new IOException("SDK 换取令牌 HTTP " + (response == null ? "-" : response.status));
                SdkSigner.Session session = SdkSigner.answer(response.body, materials.exchangeKey, materials.exchangeInnerKey);
                String token = session.token;
                long ladon = first.nextLadon;
                if (session.status == 202) {
                    now = System.currentTimeMillis();
                    SdkRequest.Signed second = SdkRequest.bootstrap(registration, cookies, now, materials, core, token, ladon,
                        SdkRequest.Randoms.bootstrap(random), pid, COMPRESSION);
                    response = send(second.url, second.headers, second.body, MAX_SESSION_BYTES, stop, SdkRequest.EXCHANGE);
                    if (response == null || response.status != 200 || response.body.length == 0)
                        throw new IOException("SDK 换取令牌续接 HTTP " + (response == null ? "-" : response.status));
                    session = SdkSigner.answer(response.body, materials.exchangeKey, materials.exchangeInnerKey);
                    if (session.status != 200) throw new IOException("SDK 会话状态 " + session.status);
                    token = session.token;
                    ladon = second.nextLadon;
                }
                this.token = token; this.ladon = ladon; this.sessionAt = System.nanoTime();
                trace("session ok");
                return true;
            } catch (Exception failure) { trace("exchange failed: " + failure); return false; }
        }

        private OfficialApiClient.Response playback(String vid, long stop) throws Exception {
            SdkRequest.Signed signed = SdkRequest.playback(registration, cookies, vid, System.currentTimeMillis(), materials, core, token, ladon,
                SdkRequest.Randoms.playback(random), pid());
            OfficialApiClient.Response response = send(signed.url, signed.headers, signed.body, MAX_MODEL_BYTES, stop, SdkRequest.PLAYER);
            if (response != null) ladon = signed.nextLadon;
            trace("player vid=" + vid + " http=" + (response == null ? "-" : response.status) + " bytes=" + (response == null ? 0 : response.body.length));
            return response;
        }

        private byte[] random(int count) { byte[] out = new byte[count]; random.nextBytes(out); return out; }

        /** The reference reads `<key>_str` first and falls back to the numeric `<key>`. */
        private static String value(JSONObject payload, String key) {
            String explicit = payload.optString(key + "_str", ""), numeric = payload.optString(key, "");
            return explicit.isEmpty() ? numeric : explicit;
        }

        /** Only the four device-protocol cookies the registration answer sets; never an account's. */
        private static Map<String, String> registrationCookies(OfficialApiClient.Response response) {
            Map<String, String> out = new HashMap<>();
            List<String> rows = response.headers.get("set-cookie");
            if (rows == null) return out;
            for (String row : rows) {
                if (row == null || row.length() > 8192) continue;
                for (java.net.HttpCookie cookie : java.net.HttpCookie.parse(row)) {
                    String name = cookie.getName();
                    if (!"odin_tt".equals(name) && !"ttreq".equals(name) && !"store-region".equals(name) && !"store-region-src".equals(name)) continue;
                    String value = cookie.getValue();
                    if (!value.isEmpty() && value.length() < 8192 && value.chars().allMatch(c -> c >= 32 && c < 127 && c != ';')) out.put(name, value);
                }
            }
            return out;
        }

        private File identityFile() { return new File(directory, "device.json"); }

        @SuppressWarnings("unchecked")
        private Map<String, String>[] savedIdentity() {
            File file = identityFile();
            if (!file.isFile() || file.length() > 128 * 1024) return null;
            try {
                JSONObject saved = OfficialApiClient.parse(java.nio.file.Files.readAllBytes(file.toPath()));
                JSONObject params = saved.optJSONObject("params"), jar = saved.optJSONObject("cookies");
                if (params == null || !"8662".equals(params.optString("aid")) || !OfficialApiClient.validID(params.optString("device_id"))
                    || !OfficialApiClient.validID(params.optString("iid"))) return null;
                return new Map[]{jsonMap(params), jar == null ? new HashMap<>() : jsonMap(jar)};
            } catch (Exception ignored) { return null; }
        }

        private void saveIdentity(Map<String, String> params, Map<String, String> jar) throws Exception {
            JSONObject paramsJson = new JSONObject(), cookiesJson = new JSONObject();
            for (Map.Entry<String, String> field : params.entrySet()) paramsJson.put(field.getKey(), field.getValue());
            for (Map.Entry<String, String> field : jar.entrySet()) cookiesJson.put(field.getKey(), field.getValue());
            byte[] bytes = new JSONObject().put("params", paramsJson).put("cookies", cookiesJson).toString().getBytes(StandardCharsets.UTF_8);
            File target = identityFile(), temporary = new File(directory, "device.json.tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.getFD().sync(); }
            if (!temporary.renameTo(target) && !(target.delete() && temporary.renameTo(target))) throw new IOException("SDK 设备身份保存失败");
        }

        private static Map<String, String> jsonMap(JSONObject object) throws Exception {
            Map<String, String> out = new HashMap<>();
            for (java.util.Iterator<String> keys = object.keys(); keys.hasNext(); ) { String key = keys.next(); out.put(key, object.optString(key, "")); }
            return out;
        }

        private static int pid() {
            try { return android.os.Process.myPid(); }
            catch (Throwable unavailable) { return 1; }
        }

        /** One ranged GET of the official APK, on the APK or on a verified carrier redirect. Only the status
         *  line and headers are decided here: the body stays open for SdkAssets, which checks the range
         *  before it keeps a byte and resumes after the ones it already has. */
        private static SdkAssets.Reply range(String url, long start, long end, long deadlineNanos) throws Exception {
            URI target = new URI(url);
            if (!SdkAssets.carrier(target)) throw new IOException("SDK 素材地址无效");
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("SDK 素材下载超时");
            // A connection that goes quiet is abandoned after the stall time; a slow one that keeps moving may
            // run until the deadline, because whatever it delivered is kept for the next attempt.
            okhttp3.OkHttpClient client = RegisteredPlaybackModel.modelSharedClient().newBuilder()
                .connectTimeout(STALL_MILLIS, TimeUnit.MILLISECONDS).readTimeout(STALL_MILLIS, TimeUnit.MILLISECONDS)
                .callTimeout(remaining, TimeUnit.NANOSECONDS).build();
            okhttp3.Request request = new okhttp3.Request.Builder().url(url).header("Range", "bytes=" + start + "-" + end).header("Accept-Encoding", "identity")
                .header("User-Agent", "com.phoenix.read/74332").get().build();
            okhttp3.Call call = client.newCall(request);
            ACTIVE = call;
            okhttp3.Response response;
            try { response = call.execute(); }
            catch (IOException | RuntimeException failure) { call.cancel(); throw failure; }
            finally { ACTIVE = null; }
            if (response.code() != 206) {
                response.close();
                return new SdkAssets.Reply(response.code(), response.header("Location"), response.header("Content-Range"), response.header("Content-Encoding"), null);
            }
            // Closing the body closes the response; SdkAssets does once it has the range.
            return new SdkAssets.Reply(response.code(), null, response.header("Content-Range"), response.header("Content-Encoding"), response.body().byteStream());
        }

        /** POSTs one signed request to its single allowed endpoint; null when this fetch's budget is already spent. */
        private static OfficialApiClient.Response send(String url, Map<String, String> headers, byte[] body, int cap, long stop, String allowed) throws Exception {
            if (!url.startsWith(allowed)) throw new IOException("SDK 请求地址无效");
            long remaining = stop - System.nanoTime();
            if (remaining <= 0) return null;
            int timeout = (int) Math.max(1, Math.min(6000, TimeUnit.NANOSECONDS.toMillis(remaining)));
            okhttp3.OkHttpClient client = RegisteredPlaybackModel.modelSharedClient().newBuilder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).callTimeout(timeout, TimeUnit.MILLISECONDS).build();
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url).post(okhttp3.RequestBody.create(body));
            // Accept-Encoding is left to OkHttp so it also inflates a gzip answer itself, as the reference client does.
            for (Map.Entry<String, String> header : headers.entrySet())
                if (!header.getKey().equalsIgnoreCase("Content-Length") && !header.getKey().equalsIgnoreCase("Accept-Encoding")) builder.header(header.getKey(), header.getValue());
            okhttp3.Call call = client.newCall(builder.build());
            ACTIVE = call;
            try (okhttp3.Response response = call.execute()) {
                int status = response.code();
                if (status != 200) return new OfficialApiClient.Response(status, response.headers().toMultimap(), new byte[0]);
                return new OfficialApiClient.Response(status, response.headers().toMultimap(), read(response.body().byteStream(), cap, stop));
            } finally { ACTIVE = null; }
        }

        private static byte[] read(InputStream in, int cap, long stop) throws Exception {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) >= 0; ) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("SDK 请求已取消");
                if (System.nanoTime() >= stop) throw new SocketTimeoutException("SDK 请求超时");
                if (count > 0) {
                    if (out.size() + count > cap) throw new IOException("SDK 响应过大");
                    out.write(buffer, 0, count);
                }
            }
            return out.toByteArray();
        }

        private static void trace(String message) {
            try { android.util.Log.i("HongguoSDK", message); } catch (Throwable ignored) { }
        }
    }
}
