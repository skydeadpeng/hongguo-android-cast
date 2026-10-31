package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
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
        final String deviceId, iid, cdid;
        Identity(String deviceId, String iid, String cdid) { this.deviceId = deviceId; this.iid = iid; this.cdid = cdid; }
    }

    private UpstreamDeviceRegister() { }

    static Identity register() throws Exception {
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
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/octet-stream;tt-data=a");
        headers.put("log-encode-type", "gzip");
        headers.put("x-ss-dp", "8662");
        headers.put("x-ss-req-ticket", String.valueOf(now));
        headers.put("x-vc-bdturing-sdk-version", "4.0.3.cn");
        headers.put("lc", "101");
        headers.put("sdk-version", "2");
        headers.put("passport-sdk-version", "5051452");
        headers.put("x-neptune", "-8|50:51:59:30:40:47:49:39");
        headers.put("x-ss-stub", DanmakuSigner.hex(DanmakuSigner.md5(body)).toUpperCase(java.util.Locale.ROOT));
        String raw = AppTransport.upstreamRegisterPost(URL, headers, body, System.nanoTime() + 10_000_000_000L);
        JSONObject reply;
        try { reply = new JSONObject(raw); }
        catch (Exception invalid) { throw new java.io.IOException("上游登记响应非 JSON: " + raw.substring(0, Math.min(200, raw.length()))); }
        // This endpoint answers the identity without a code field; an explicit non-zero code is the only refusal.
        if (reply.has("code") && reply.optInt("code", 0) != 0 || reply.has("Code") && reply.optInt("Code", 0) != 0)
            throw new java.io.IOException("上游设备登记被拒绝: " + raw.substring(0, Math.min(300, raw.length())));
        String deviceId = id(reply, "device_id"), iid = id(reply, "install_id");
        if (deviceId.isEmpty() || iid.isEmpty()) throw new java.io.IOException("上游设备登记未返回有效身份");
        return new Identity(deviceId, iid, cdid);
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
