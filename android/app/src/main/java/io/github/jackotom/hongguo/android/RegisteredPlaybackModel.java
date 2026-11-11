package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import org.json.*;

/** One anonymous registered identity per metadata lookup, never an account session or a disk record. */
final class RegisteredPlaybackModel implements AutoCloseable {
    static final String API = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/";
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final long MAX_NANOS = TimeUnit.SECONDS.toNanos(20);
    static final class ModelError extends IOException {
        final String code;
        ModelError(String message, String code) { super(message); this.code = code; }
    }
    private final OfficialApiClient.Transport registration, modelTransport;
    private final long deadline;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean closed;
    private volatile OfficialApiClient registering;
    private volatile Thread owner;

    RegisteredPlaybackModel() { this(new OfficialTransport(), new ModelHttpTransport(), System.nanoTime() + MAX_NANOS); }
    RegisteredPlaybackModel(OfficialApiClient.Transport registration, OfficialApiClient.Transport modelTransport, long deadline) {
        this.registration = Objects.requireNonNull(registration); this.modelTransport = Objects.requireNonNull(modelTransport);
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
        owner = Thread.currentThread(); OfficialApiClient client = null;
        try {
            check(); client = new OfficialApiClient(OfficialApiClient.newSession(), registration); registering = client;
            check(); client.deadlineNanos(deadline); client.registerDevice(); check();
            // This fresh in-memory device is unrelated to AccountSync/OfficialAccountVault.
            JSONObject anonymous = client.session();
            String did = anonymous.optString("device_id"), iid = anonymous.optString("install_id");
            if (!anonymous.optBoolean("registered") || !OfficialApiClient.validID(did) || !OfficialApiClient.validID(iid))
                throw new ModelError("App片源设备登记未完成", "APP_INVALID_RESPONSE");
            TreeMap<String, String> query = query(did, iid);
            byte[] body = ("{\"biz_param\":{\"need_all_video_definition\":true,\"video_platform\":3},\"content_type\":1,\"video_id\":\"" + vid + "\"}").getBytes(StandardCharsets.UTF_8);
            SecureRandom random = new SecureRandom();
            int[] nonces = {random.nextInt(), random.nextInt(), random.nextInt(), random.nextInt()};
            PassportSigner.Signed signed = PassportSigner.sign(query, body, System.currentTimeMillis(), did, nonces);
            Map<String, String> headers = new LinkedHashMap<>(signed.headers);
            headers.put("User-Agent", DanmakuSigner.USER_AGENT); headers.put("Accept", "application/json");
            headers.put("Content-Type", "application/json; charset=utf-8"); headers.put("X-XS-From-Web", "0");
            headers.put("Sdk-Version", "2"); headers.put("X-SS-DP", "8662");
            headers.put("X-Tt-Store-Region", "cn"); headers.put("X-Tt-Store-Region-Src", "did");
            check();
            OfficialApiClient.Response response = modelTransport.execute(new OfficialApiClient.Request(API + "?" + signed.query, "POST", headers, body, deadline));
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
            JSONObject payload = envelope.optJSONObject("data");
            if (!zero(envelope.opt("code")) || envelope.has("error_code") && !zero(envelope.opt("error_code")) || payload != null && payload.has("error_code") && !zero(payload.opt("error_code")))
                throw new ModelError("App接口暂未提供本集播放信息", "BUSINESS_REJECTED");
            if (payload == null) throw new ModelError("App接口未返回本集片源模型", "APP_INVALID_RESPONSE");
            identity(envelope, vid); identity(payload, vid);
            Object raw = payload.opt("video_model");
            JSONObject model;
            if (raw instanceof JSONObject) model = (JSONObject) raw;
            else if (raw instanceof String) { try { model = OfficialApiClient.parse(((String) raw).getBytes(StandardCharsets.UTF_8)); } catch (Exception invalid) { throw new ModelError("App片源模型响应格式无效", "APP_INVALID_RESPONSE"); } }
            else throw new ModelError("App接口未返回本集片源模型", "APP_INVALID_RESPONSE");
            identity(model, vid); JSONObject projected = project(model); check();
            return new JSONObject().put("vid", vid).put("video_model", projected);
        } finally {
            registering = null; owner = null;
            if (client != null) client.close(); else try { registration.close(); } catch (RuntimeException ignored) { }
            try { modelTransport.close(); } catch (RuntimeException ignored) { }
        }
    }
    private static TreeMap<String, String> query(String did, String iid) {
        TreeMap<String, String> query = new TreeMap<>();
        String[][] fields = {{"ac","wifi"},{"aid","8662"},{"app_name","novelread"},{"channel","update_64"},{"device_brand","Redmi"},{"device_id",did},{"device_platform","android"},{"device_type","25053RT47C"},{"dpi","520"},{"iid",iid},{"language","zh"},{"manifest_version_code","73532"},{"os","android"},{"os_api","36"},{"os_version","16"},{"resolution","1280*2772"},{"ssmix","a"},{"update_version_code","73532"},{"version_code","73532"},{"version_name","7.3.5.32"}};
        for (String[] row : fields) query.put(row[0], row[1]); return query;
    }
    private static boolean zero(Object value) { return value instanceof Number && Double.isFinite(((Number) value).doubleValue()) && ((Number) value).doubleValue() == 0 || value instanceof String && value.equals("0"); }
    // VOD video_id/vid strings are not the numeric episode item_id namespace.
    private static void identity(JSONObject object, String vid) throws IOException {
        for (String key : new String[]{"item_id", "episode_id"}) if (object.has(key)) {
            Object value = object.opt(key); String text = value instanceof String || value instanceof Number ? value.toString() : "";
            if (!vid.equals(text)) throw new ModelError("App片源与分集不一致", "APP_INVALID_RESPONSE");
        }
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
            if (row.has("encrypt_info")) { JSONObject encryption = row.optJSONObject("encrypt_info"); if (encryption == null) throw new ModelError("App片源准备信息无效", "APP_INVALID_RESPONSE"); JSONObject copy = new JSONObject(); scalars(encryption, copy, "spade_a"); item.put("encrypt_info", copy); }
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
        private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> { Thread thread = new Thread(runnable, "hongguo-model-deadline"); thread.setDaemon(true); return thread; });
        private volatile boolean closed; private volatile HttpURLConnection active;
        @Override public void close() { closed = true; HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
        static URI validate(OfficialApiClient.Request request) throws Exception {
            URI uri = new URI(request.url);
            if (!"https".equals(uri.getScheme()) || !"api5-normal-sinfonlineb.fqnovel.com".equals(uri.getHost()) || !"/novel/player/video_model/v1/".equals(uri.getRawPath()) || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null || !request.method.equals("POST") || request.body.length > 128 * 1024)
                throw new ModelError("App片源服务地址无效", "INVALID_INPUT");
            return uri;
        }
        private void check(long deadline) throws IOException {
            if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("App片源请求已取消");
            if (System.nanoTime() >= deadline) throw new SocketTimeoutException("App片源请求总等待超时");
        }
        @Override public OfficialApiClient.Response execute(OfficialApiClient.Request request) throws Exception {
            check(request.deadlineNanos); URI uri = validate(request); HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(); active = connection;
            ScheduledFuture<?> alarm = WATCHDOG.schedule(connection::disconnect, Math.max(1, request.deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            try {
                check(request.deadlineNanos); int timeout = (int) Math.max(1, Math.min(7000, TimeUnit.NANOSECONDS.toMillis(request.deadlineNanos - System.nanoTime())));
                connection.setRequestMethod("POST"); connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout); connection.setInstanceFollowRedirects(false);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(request.body.length);
                for (Map.Entry<String, String> header : request.headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                connection.setRequestProperty("Accept-Encoding", "gzip");
                try (OutputStream out = connection.getOutputStream()) { check(request.deadlineNanos); out.write(request.body); }
                check(request.deadlineNanos); int status = connection.getResponseCode(); check(request.deadlineNanos);
                if (status != 200) return new OfficialApiClient.Response(status, Collections.emptyMap(), new byte[0]);
                long length = connection.getContentLengthLong(); if (length > MAX_BYTES) throw new ModelError("App片源模型响应超过大小限制", "APP_INVALID_RESPONSE");
                byte[] body; try (InputStream input = connection.getInputStream()) { body = read(input, request.deadlineNanos); }
                check(request.deadlineNanos); return new OfficialApiClient.Response(status, connection.getHeaderFields(), body);
            } catch (Exception failure) { check(request.deadlineNanos); throw failure; }
            finally { alarm.cancel(false); connection.disconnect(); if (active == connection) active = null; }
        }
    }
}
