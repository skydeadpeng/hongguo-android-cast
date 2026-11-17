package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/**
 * The upstream desktop device registration for the 72232 cloud lane: the hongguo 7.3.9.32
 * profile with its own KDF salt and fixed sig_hash at log5-applog-lf.fqnovel.com. The
 * returned identity is what signedAccountRequest cloud reads must carry alongside the
 * web session cookie. Ported field-for-field from upstream playback-device.js.
 */
final class UpstreamDeviceRegister {
    private static final String URL = "https://log5-applog-lf.fqnovel.com/service/2/device_register/?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1";
    private static final byte[] KDF_SALT = DanmakuSigner.unhex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58");
    private static final String USER_AGENT = AccountSigner.USER_AGENT;

    static final class Identity {
        final String deviceId, iid, cdid, cookies;
        Identity(String deviceId, String iid, String cdid, String cookies) { this.deviceId = deviceId; this.iid = iid; this.cdid = cdid; this.cookies = cookies; }
    }

    private UpstreamDeviceRegister() { }

    static Identity register() throws Exception {
        return register(null);
    }

    /** Registers with an optional web-session Cookie header so the identity may bind to the account. */
    static Identity register(String cookieHeader) throws Exception {
        long now = System.currentTimeMillis();
        String cdid = UUID.randomUUID().toString();
        JSONObject custom = new JSONObject()
            .put("account_region_src", "fallback").put("account_region", "cn")
            .put("host_bit", 64).put("dragon_device_type", "phone");
        JSONObject header = new JSONObject()
            .put("display_name", "红果免费短剧").put("update_version_code", 73932).put("manifest_version_code", 73932)
            .put("app_version_minor", "7.3.9.32").put("aid", 8662).put("channel", "update_64").put("package", "com.phoenix.read")
            .put("app_version", "7.3.9.32").put("version_code", 73932).put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo")
            .put("sdk_target_version", 29).put("git_hash", "3a88f4e").put("os", "Android").put("os_version", "16").put("os_api", 36)
            .put("device_model", "25053RT47C").put("device_brand", "Redmi").put("device_manufacturer", "Redmi")
            .put("cpu_abi", "arm64-v8a").put("release_build", "b5e187f").put("density_dpi", 520).put("display_density", "mdpi")
            .put("resolution", "1280x2772").put("language", "zh").put("timezone", 8).put("access", "wifi")
            .put("not_request_sender", 0).put("carrier", "CHINA MOBILE").put("mcc_mnc", "46000")
            .put("rom", "eng.root.20231116.160340").put("rom_version", "W528JS release-keys").put("cdid", cdid)
            .put("sig_hash", "56a962410c494bbaf0b58dba20cae56f")
            .put("openudid", DanmakuSigner.hex(SearchProtocol.random(8)))
            .put("clientudid", UUID.randomUUID().toString())
            .put("ipv6_list", new org.json.JSONArray()).put("region", "CN").put("tz_name", "Asia/Shanghai")
            .put("tz_offset", 28800).put("sim_region", "cn").put("sim_serial_number", new org.json.JSONArray())
            .put("oaid_may_support", false).put("req_id", UUID.randomUUID().toString()).put("device_platform", "android")
            .put("custom", custom).put("pre_installed_channel", "").put("apk_first_install_time", now)
            .put("is_system_app", 0).put("sdk_flavor", "china").put("guest_mode", 0);
        byte[] json = ("{\"magic_tag\":\"ss_app_log\",\"header\":" + header + ",\"_gen_time\":" + now + "}").getBytes(StandardCharsets.UTF_8);
        byte[] compressed = PassportDevice.gzip(json, false);
        byte[] body = ttEncrypt(compressed);
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        // Byte-exact with the working TV clients: no Accept header and a plain
        // content-type — the ";tt-data=a" suffix lands identities in a hollowed pool.
        headers.put("Content-Type", "application/octet-stream");
        headers.put("log-encode-type", "gzip");
        headers.put("x-ss-dp", "8662");
        headers.put("x-ss-req-ticket", String.valueOf(now));
        headers.put("x-vc-bdturing-sdk-version", "4.0.3.cn");
        headers.put("lc", "101");
        headers.put("sdk-version", "2");
        headers.put("passport-sdk-version", "5051452");
        headers.put("x-neptune", "-8|50:51:59:30:40:47:49:39");
        headers.put("x-ss-stub", DanmakuSigner.hex(DanmakuSigner.md5(body)).toUpperCase(java.util.Locale.ROOT));
        if (cookieHeader != null && !cookieHeader.isEmpty()) headers.put("Cookie", cookieHeader);
        String raw = okhttpRegister(URL, headers, body);
        JSONObject reply;
        try { reply = new JSONObject(raw); }
        catch (Exception invalid) { throw new java.io.IOException("上游登记响应非 JSON: " + raw.substring(0, Math.min(200, raw.length()))); }
        // This endpoint answers the identity without a code field; an explicit non-zero code is the only refusal.
        if (reply.has("code") && reply.optInt("code", 0) != 0 || reply.has("Code") && reply.optInt("Code", 0) != 0)
            throw new java.io.IOException("上游设备登记被拒绝: " + raw.substring(0, Math.min(300, raw.length())));
        String deviceId = id(reply, "device_id"), iid = id(reply, "install_id");
        if (deviceId.isEmpty() || iid.isEmpty()) throw new java.io.IOException("上游设备登记未返回有效身份");
        String cookies = registrationCookies();
        trace("register ok device_id=" + deviceId);
        final Identity identity = new Identity(deviceId, iid, cdid, cookies);
        // The working TV clients warm a fresh identity with catalog browsing on the SAME
        // identity (bookmall/tab + registration cookies) before the first playback model
        // request; identities that hit player/v1 with no footprint earn hollowed pools.
        // Async: a slow catalog answer must never stall the model ladder behind it.
        new Thread(() -> warmupCatalog(identity), "hongguo-warmup").start();
        return identity;
    }

    /** Last registration's Set-Cookie, filtered to the values the working clients keep. */
    private static volatile String lastCookies = "";
    private static String registrationCookies() {
        try {
            java.util.LinkedHashSet<String> kept = new java.util.LinkedHashSet<>();
            for (String header : lastCookieHeaders) {
                String pair = header.split(";", 2)[0].trim();
                String name = pair.split("=", 2)[0].trim();
                if (pair.contains("=") && java.util.Arrays.asList("store-region", "store-region-src", "install_id", "ttreq").contains(name))
                    kept.add(pair);
            }
            return String.join("; ", kept);
        } catch (Exception ignored) { return ""; }
    }
    private static volatile java.util.List<String> lastCookieHeaders = java.util.Collections.emptyList();

    /** One catalog GET on the fresh identity — the browsing footprint real clients leave. */
    private static void warmupCatalog(Identity identity) {
        try {
            long now = System.currentTimeMillis();
            long ts = now / 1000;
            String query = "iid=" + identity.iid + "&device_id=" + identity.deviceId
                + "&ac=wifi&channel=update_64&aid=8662&app_name=novelread"
                + "&version_code=74332&version_name=7.4.3.32&device_platform=android&os=android&ssmix=a"
                + "&device_type=25053RT47C&device_brand=Redmi&language=zh&os_api=36&os_version=16"
                + "&manifest_version_code=74332&resolution=1280*2772&dpi=520&update_version_code=74332"
                + "&host_abi=arm64-v8a&dragon_device_type=phone&pv_player=74332&compliance_status=0"
                + "&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0"
                + "&cdid=" + identity.cdid + "&tab_type=16&bottom_tab_type=7";
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                .url("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/bookmall/tab/v1/?" + query)
                .get();
            rb.header("accept", "application/json; charset=utf-8,application/x-protobuf");
            rb.header("x-ss-req-ticket", String.valueOf(now));
            rb.header("sdk-version", "2");
            rb.header("x-xs-from-web", "0");
            rb.header("x-khronos", String.valueOf(ts));
            rb.header("x-ladon", java.util.Base64.getEncoder().encodeToString(new byte[]{(byte) ts, (byte) (ts >>> 8), (byte) (ts >>> 16), (byte) (ts >>> 24)}));
            rb.header("x-argus", java.util.Base64.getEncoder().encodeToString(new byte[]{(byte) (ts >>> 24), (byte) (ts >>> 16), (byte) (ts >>> 8), (byte) ts}));
            // Full v3-era signature set like the working clients' catalog calls.
            int nonce = (int) (now & 0xFFFF);
            rb.header("x-gorgon", PassportSigner.gorgon(query, new byte[0], (int) ts, nonce));
            rb.header("x-helios", heliosV3(ts));
            rb.header("user-agent", "com.phoenix.read/74332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)");
            if (identity.cookies != null && !identity.cookies.isEmpty()) rb.header("cookie", identity.cookies);
            okhttp3.OkHttpClient client = RegisteredPlaybackModel.modelSharedClient().newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build();
            try (okhttp3.Response response = client.newCall(rb.build()).execute()) {
                byte[] body = response.body() == null ? new byte[0] : response.body().bytes();
                trace("warmup http=" + response.code() + " bytes=" + body.length);
            }
        } catch (Exception failure) {
            trace("warmup failed: " + failure);
        }
    }

    /** OkHttp registration on the shared model client (one connection pool for the whole
     * registered-identity lifecycle, like the working TV clients); honors the system proxy
     * so diagnostics can capture it. Bounded to 10 seconds per exchange. */
    private static String okhttpRegister(String url, Map<String, String> headers, byte[] body) throws Exception {
        okhttp3.OkHttpClient client = RegisteredPlaybackModel.modelSharedClient().newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build();
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url)
            .post(okhttp3.RequestBody.create(body));
        for (Map.Entry<String, String> header : headers.entrySet()) builder.header(header.getKey(), header.getValue());
        try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
            trace("register http=" + response.code() + " bytes=" + (response.body() == null ? 0 : response.body().contentLength()));
            if (response.code() != 200) throw new java.io.IOException("上游设备登记 HTTP " + response.code());
            lastCookieHeaders = response.headers("Set-Cookie");
            okhttp3.ResponseBody payload = response.body();
            return payload == null ? "" : payload.string();
        }
    }
    private static void trace(String message) {
        try { android.util.Log.i("HongguoV5", message); } catch (Throwable ignored) { }
    }

    /** v3-era helios (license 1588093228) for catalog calls; line-for-line the JS twin. */
    private static String heliosV3(long ts) {
        try {
            java.security.SecureRandom random = new java.security.SecureRandom();
            int nonce = random.nextInt();
            byte[] leNonce = new byte[]{(byte) nonce, (byte) (nonce >>> 8), (byte) (nonce >>> 16), (byte) (nonce >>> 24)};
            java.security.MessageDigest md5 = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(concat(leNonce, "8662".getBytes(StandardCharsets.UTF_8)));
            byte[] ascii = PlaybackSignerV5.hexText(digest).getBytes(StandardCharsets.US_ASCII);
            long[] w = new long[4];
            for (int i = 0; i < 4; i++) w[i] = readLE64(ascii, i * 8);
            long[] table = new long[35];
            table[0] = w[0];
            long b0 = w[0], b8 = w[1];
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            queue.add(w[2]); queue.add(w[3]);
            for (int i = 0; i < 34; i++) {
                long x8 = Long.rotateRight(b8, 8) + b0;
                x8 ^= i;
                queue.add(x8);
                x8 ^= Long.rotateRight(b0, 61);
                table[i + 1] = x8;
                b0 = x8;
                b8 = queue.poll();
            }
            byte[] text = (ts + "-1588093228-8662").getBytes(StandardCharsets.UTF_8);
            int padding = 16 - text.length % 16;
            byte[] padded = java.util.Arrays.copyOf(text, text.length + padding);
            java.util.Arrays.fill(padded, text.length, padded.length, (byte) padding);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(leNonce, 0, 4);
            for (int at = 0; at < padded.length; at += 16) {
                long a = readLE64(padded, at), b = readLE64(padded, at + 8);
                for (int i = 0; i < 34; i++) {
                    b = table[i] ^ (a + Long.rotateRight(b, 8));
                    a = b ^ Long.rotateRight(a, 61);
                }
                out.write(le64(a), 0, 8); out.write(le64(b), 0, 8);
            }
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception failure) { return ""; }
    }
    private static long readLE64(byte[] data, int at) {
        return java.nio.ByteBuffer.wrap(data, at, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
    }
    private static byte[] le64(long value) {
        return java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }
    private static byte[] concat(byte[]... parts) {
        int size = 0; for (byte[] part : parts) size += part.length;
        byte[] out = new byte[size]; int at = 0;
        for (byte[] part : parts) { System.arraycopy(part, 0, out, at, part.length); at += part.length; }
        return out;
    }

    private static String id(JSONObject reply, String name) {
        String text = reply.optString(name + "_str", "");
        if (text.isEmpty()) text = reply.optString(name, "");
        return text.matches("[1-9][0-9]{0,29}") ? text : "";
    }

    /** ttEncrypt with the upstream KDF salt: 746305100000 || random32 || AES-CBC(sha512(c)||c). */
    private static byte[] ttEncrypt(byte[] compressed) throws Exception {
        byte[] random = SearchProtocol.random(32);
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        byte[] inner = sha512.digest(random);
        sha512.reset();
        byte[] derived = sha512.digest(join(inner, KDF_SALT));
        byte[] plaintext = join(sha512(compressed), compressed);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(java.util.Arrays.copyOfRange(derived, 0, 16), "AES"),
            new IvParameterSpec(java.util.Arrays.copyOfRange(derived, 16, 32)));
        return join(DanmakuSigner.unhex("746305100000"), random, cipher.doFinal(plaintext));
    }

    private static byte[] sha512(byte[] value) throws Exception { return MessageDigest.getInstance("SHA-512").digest(value); }
    private static byte[] join(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) size += part.length;
        byte[] out = new byte[size];
        int at = 0;
        for (byte[] part : parts) { System.arraycopy(part, 0, out, at, part.length); at += part.length; }
        return out;
    }
}
