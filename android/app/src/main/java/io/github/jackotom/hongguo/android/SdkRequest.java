package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Request builders of the SDK playback route: registration, the token exchange and the signed player request. */
final class SdkRequest {
    static final String REGISTER_URL = "https://log.snssdk.com/service/2/device_register/";
    static final String EXCHANGE = "https://mssdk.zijieapi.com/sdi/get_token";
    static final String PLAYER = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/player/v1";
    static final String REGISTER_AGENT = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; Xiaomi 14; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 QuicVersion:462f352c 2026-08-31)";
    private static final String[] BOOTSTRAP_FIELDS = {"os", "ssmix", "_rticket", "cdid", "channel", "app_name", "version_name", "manifest_version_code",
        "update_version_code", "resolution", "dpi", "device_type", "device_brand", "language", "os_api", "os_version", "ac", "host_abi", "dragon_device_type",
        "pv_player", "compliance_status", "need_personal_recommend", "player_so_load", "is_android_pad_screen", "rom_version", "device_id", "klink_egdi"};
    private static final String[] PLAYER_FIELDS = {"device_platform", "os", "ssmix", "_rticket", "cdid", "channel", "aid", "app_name", "version_code",
        "version_name", "manifest_version_code", "update_version_code", "resolution", "dpi", "device_type", "device_brand", "language", "os_api", "os_version",
        "ac", "host_abi", "dragon_device_type", "pv_player", "compliance_status", "need_personal_recommend", "player_so_load", "is_android_pad_screen",
        "rom_version", "device_id", "iid", "klink_egdi"};

    /** One signed request: final URL, headers in wire order and the body they bind. */
    static final class Signed {
        final String url; final Map<String, String> headers; final byte[] body; final long nextLadon;
        Signed(String url, Map<String, String> headers, byte[] body, long nextLadon) {
            this.url = url; this.headers = headers; this.body = body; this.nextLadon = nextLadon;
        }
    }

    /** Every random draw one signed request needs, in the order the reference consumes them. */
    static final class Randoms {
        final byte[] exchangeNonce = new byte[4], helios = new byte[4], traceA = new byte[16], traceB = new byte[8];
        int exchangeEntropy, nonce0, nonce1, nonce2, gorgonNonce, readingRequest;
        long ladonAllocation;

        static Randoms bootstrap(SecureRandom random) {
            Randoms randoms = new Randoms();
            random.nextBytes(randoms.exchangeNonce); randoms.exchangeEntropy = random.nextInt(256);
            return common(randoms, random);
        }

        static Randoms playback(SecureRandom random) {
            return common(new Randoms(), random);
        }

        private static Randoms common(Randoms randoms, SecureRandom random) {
            randoms.nonce0 = random.nextInt(); randoms.nonce1 = random.nextInt(); randoms.nonce2 = random.nextInt();
            randoms.ladonAllocation = random.nextLong() & ~15L; randoms.gorgonNonce = random.nextInt(1 << 16);
            random.nextBytes(randoms.helios); random.nextBytes(randoms.traceA); random.nextBytes(randoms.traceB);
            randoms.readingRequest = random.nextInt(900000000);
            return randoms;
        }
    }

    private SdkRequest() { }

    // ---- registration ----

    /** Registration parameters of the Xiaomi 14 / 7.3.9.32 profile, in the sorted order the reference sends. */
    static Map<String, String> registrationParams(long milliseconds, String cdid, String openudid) {
        String stamp = Long.toString(milliseconds / 1000), ticket = Long.toString(milliseconds);
        Map<String, String> params = new TreeMap<>();
        String[][] fields = {{"aid", "8662"}, {"app_name", "novelread"}, {"channel", "xiaomi_8662_64"}, {"version_code", "73932"},
            {"version_name", "7.3.9.32"}, {"manifest_version_code", "73932"}, {"update_version_code", "73932"}, {"device_platform", "android"},
            {"os", "android"}, {"ssmix", "a"}, {"device_type", "23127PN0CC"}, {"device_brand", "xiaomi"}, {"os_api", "34"}, {"os_version", "14"},
            {"resolution", "1200*2670"}, {"dpi", "460"}, {"ac", "wifi"}, {"language", "zh"}, {"openudid", openudid}, {"cdid", cdid},
            {"tt_data", "a"}, {"_rticket", ticket}, {"ts", stamp}, {"package", "com.phoenix.read"}, {"first_launch_timestamp", stamp},
            {"last_deeplink_update_version_code", "0"}, {"cpu_support64", "true"}, {"host_abi", "arm64-v8a"}, {"is_guest_mode", "0"},
            {"app_type", "normal"}, {"minor_status", "0"}, {"appTheme", "light"}, {"is_preinstall", "0"}, {"need_personal_recommend", "1"},
            {"is_android_pad", "0"}, {"is_android_fold", "0"}, {"md", "0"}, {"cronet_version", "8d40f833_2026-03-03"},
            {"ttnet_version", "4.2.243.31-douyin"}, {"use_store_region_cookie", "1"}};
        for (String[] field : fields) params.put(field[0], field[1]);
        return params;
    }

    /** The device header the registration body encrypts, in the reference's key order. */
    static String registrationPayload(long milliseconds, String cdid, String openudid, String clientudid, String requestId) {
        return "{\"magic_tag\":\"ss_app_log\",\"header\":{\"access\":\"wifi\",\"aid\":8662,\"app_version\":\"7.3.9.32\",\"app_version_minor\":\"\","
            + "\"apk_first_install_time\":" + milliseconds + ",\"cdid\":\"" + cdid + "\",\"channel\":\"xiaomi_8662_64\",\"clientudid\":\"" + clientudid + "\","
            + "\"cpu_abi\":\"arm64-v8a\",\"density_dpi\":460,\"device_brand\":\"xiaomi\",\"device_category\":\"phone\",\"device_manufacturer\":\"Xiaomi\","
            + "\"device_model\":\"Xiaomi 14\",\"device_platform\":\"android\",\"display_density\":\"xxhdpi\",\"display_name\":\"红果短剧\","
            + "\"git_hash\":\"fe95b95\",\"guest_mode\":0,\"ipv6_list\":[],\"is_system_app\":0,\"language\":\"zh\",\"manifest_version_code\":73932,"
            + "\"not_request_sender\":0,\"oaid_may_support\":true,\"openudid\":\"" + openudid + "\",\"os\":\"Android\",\"os_api\":34,\"os_version\":\"14\","
            + "\"package\":\"com.phoenix.read\",\"region\":\"CN\",\"release_build\":\"UKQ1.230804.001_1701234567\",\"req_id\":\"" + requestId + "\","
            + "\"resolution\":\"2670x1200\",\"rom\":\"hyperos\",\"rom_version\":\"OS1.0.32.0.UNCCNXM\",\"sdk_flavor\":\"china\",\"sdk_target_version\":29,"
            + "\"sdk_version\":\"3.7.3-rc.116-douyin\",\"sig_hash\":\"aea615ab910015038f73c47e45d21466\",\"sim_serial_number\":[],\"timezone\":8,"
            + "\"tz_name\":\"Asia/Shanghai\",\"tz_offset\":28800,\"update_version_code\":73932,\"version_code\":73932,\"custom\":{\"is_android_fold\":0,"
            + "\"filter_warn\":0,\"is_android_pad\":0,\"client_ipv4\":\"0.0.0.0\",\"web_ua\":\"Mozilla/5.0 (Linux; Android 14; Xiaomi 14 Build/UKQ1.230804.001; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36\"}},\"_gen_time\":" + milliseconds + "}";
    }

    /** Registration URL and headers; the body is the TT-encrypted payload, owned by the caller. */
    static Map<String, String> registrationHeaders(long milliseconds, byte[] body) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/octet-stream;tt-data=a"); headers.put("Content-Encoding", "gzip"); headers.put("Accept-Encoding", "gzip");
        headers.put("User-Agent", REGISTER_AGENT);
        headers.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
        headers.put("X-SS-STUB", DanmakuSigner.hex(DanmakuSigner.md5(body)).toUpperCase(Locale.ROOT)); headers.put("X-SS-DP", "8662");
        headers.put("X-SS-REQ-TICKET", Long.toString(milliseconds)); headers.put("X-Khronos", Long.toString(milliseconds / 1000));
        headers.put("X-Argus", Base64.getEncoder().encodeToString(SdkWire.le32(milliseconds / 1000))); headers.put("X-TT-Request-Tag", "t=0;n=1");
        headers.put("Log-Encode-Type", "gzip"); headers.put("Passport-SDK-Version", "60755"); headers.put("Passport-SDK-Settings", "device_transfer_s_0,device_transfer_ab_1");
        headers.put("Sdk-Version", "2"); headers.put("lc", "102"); headers.put("X-TT-Store-Region", "cn"); headers.put("X-TT-Store-Region-Src", "did");
        headers.put("X-VC-Bdturing-SDK-Version", "4.0.3.cn");
        return headers;
    }

    /** urllib's urlencode with quote_plus over a sorted parameter map. */
    static String registrationUrl(Map<String, String> params) {
        StringBuilder out = new StringBuilder(REGISTER_URL);
        for (Map.Entry<String, String> field : params.entrySet()) out.append(out.length() == REGISTER_URL.length() ? '?' : '&').append(field.getKey()).append('=').append(percent(field.getValue(), true));
        return out.toString();
    }

    // ---- token exchange and player request ----

    /** First or continuation exchange request for the saved anonymous device. */
    static Signed bootstrap(Map<String, String> registration, Map<String, String> cookies, long milliseconds, SdkMaterials materials, SdkSigner.Core core,
                            String token, long previousLadon, Randoms randoms, int pid, int level) throws Exception {
        Map<String, String> params = profile(registration);
        byte[] query = bootstrapQuery(params, milliseconds);
        byte[] body = SdkSigner.exchangeRequest(devicePayload(params, materials.unavailable, token), milliseconds, materials.exchangeKey,
            materials.exchangeInnerKey, randoms.exchangeNonce, randoms.exchangeEntropy, level);
        return sign(params, cookies, query, body, milliseconds, materials, core, token, previousLadon, true, randoms, pid, EXCHANGE);
    }

    /** The player/v1 request that carries an exchange token; its body is the gzipped episode selection. */
    static Signed playback(Map<String, String> registration, Map<String, String> cookies, String video, long milliseconds, SdkMaterials materials,
                           SdkSigner.Core core, String token, long previousLadon, Randoms randoms, int pid) throws Exception {
        return playbackWithBody(registration, cookies, gzip(playbackJson(video).getBytes(StandardCharsets.UTF_8)), milliseconds, materials, core,
            token, previousLadon, randoms, pid);
    }

    /** The episode selection the player request carries, before gzip. */
    static String playbackJson(String video) {
        return "{\"dr_scene\":\"player\",\"mixed_video_id_map\":{\"1004\":[\"" + video + "\"]},\"biz_param\":{\"detail_page_version\":0,"
            + "\"device_level\":4,\"disable_digg_stat\":false,\"need_all_video_definition\":true,\"need_mp4_align\":false,\"use_os_player\":false,"
            + "\"use_server_dns\":false,\"video_platform\":1024}}";
    }

    /** Signs the player request over a caller-supplied body. */
    static Signed playbackWithBody(Map<String, String> registration, Map<String, String> cookies, byte[] body, long milliseconds, SdkMaterials materials,
                                   SdkSigner.Core core, String token, long previousLadon, Randoms randoms, int pid) throws Exception {
        Map<String, String> params = profile(registration);
        params.put("_rticket", Long.toString(milliseconds));
        Signed signed = sign(params, cookies, playerQuery(params), body, milliseconds, materials, core, token, previousLadon, false, randoms, pid, PLAYER);
        Map<String, String> headers = signed.headers;
        headers.put("Content-Type", "application/json; charset=utf-8"); headers.put("Content-Encoding", "gzip"); headers.put("Accept", "application/json");
        headers.put("X-Reading-Request", milliseconds + "-" + (100000000L + randoms.readingRequest));
        String region = cookies.get("store-region");
        headers.put("X-TT-Store-Region", region == null || region.isEmpty() ? "cn" : region);
        headers.put("X-TT-Store-Region-Src", "did"); headers.put("X-SS-REQ-TICKET", Long.toString(milliseconds));
        return signed;
    }

    /** The registration profile as every signed request carries it: the 7.4.3.32 player version over the registered device. */
    static Map<String, String> profile(Map<String, String> registration) throws IOException {
        if (!"8662".equals(registration.get("aid")) || !digits(registration.get("device_id")) || !digits(registration.get("iid")))
            throw new IOException("SDK 设备身份无效");
        for (String key : new String[]{"cdid", "device_type", "device_brand", "os_version", "os_api", "resolution", "dpi"})
            if (registration.get(key) == null || registration.get(key).isEmpty()) throw new IOException("SDK 设备档案不完整");
        Map<String, String> params = new LinkedHashMap<>(registration);
        params.put("version_name", "7.4.3.32"); params.put("version_code", "74332"); params.put("manifest_version_code", "74332");
        params.put("update_version_code", "74332"); params.put("device_platform", "android"); params.put("os", "android");
        return params;
    }

    private static boolean digits(String value) { return value != null && value.matches("[1-9][0-9]{4,29}"); }

    private static byte[] bootstrapQuery(Map<String, String> params, long milliseconds) {
        StringBuilder out = new StringBuilder();
        pair(out, "lc_id", "646637794"); pair(out, "platform", "android"); pair(out, "device_platform", "android"); pair(out, "sdk_ver", SdkSigner.SDK_VERSION);
        pair(out, "sdk_ver_code", Long.toString(SdkSigner.SDK_CODE)); pair(out, "app_ver", params.get("version_name")); pair(out, "version_code", params.get("version_code"));
        pair(out, "aid", params.get("aid")); pair(out, "sdkid", ""); pair(out, "subaid", ""); pair(out, "iid", params.get("iid")); pair(out, "did", params.get("device_id"));
        pair(out, "bd_did", ""); pair(out, "client_type", "inhouse"); pair(out, "region_type", "ml"); pair(out, "mode", "2");
        pair(out, "full_update_version_code", params.get("update_version_code"));
        for (String key : BOOTSTRAP_FIELDS) {
            String value = key.equals("_rticket") ? Long.toString(milliseconds) : key.equals("need_personal_recommend") ? "1" : params.getOrDefault(key, "");
            pair(out, key, value);
        }
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] playerQuery(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        for (String key : PLAYER_FIELDS) {
            String value = params.get(key);
            if (value != null && !value.isEmpty()) pair(out, key, value);
        }
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void pair(StringBuilder out, String key, String value) {
        if (out.length() > 0) out.append('&');
        out.append(key).append('=').append(percent(value, false));
    }

    /** urllib's quote(str, safe='*'), or quote_plus when `plus` is set. */
    private static String percent(String value, boolean plus) {
        StringBuilder out = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 255;
            boolean keep = b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || b == '_' || b == '.' || b == '-' || b == '~' || !plus && b == '*';
            if (keep) out.append((char) b);
            else if (plus && b == ' ') out.append('+');
            else out.append('%').append(String.format(Locale.ROOT, "%02X", b));
        }
        return out.toString();
    }

    private static void put(SdkWire.Proto proto, int field, Object value) {
        if (value instanceof Long) proto.number(field, (Long) value);
        else if (value instanceof String) proto.text(field, (String) value);
        else if (value instanceof Float) proto.floats(field, (Float) value);
        else proto.bytes(field, (byte[]) value);
    }

    /** The SDK's public device proof with its wire defaults; only the registered device id is bound. */
    static byte[] plainDeviceProof(String deviceId) {
        return proof("8662", deviceId, "Ai6svO3PyrwDOUSmO6ZcResxu", "16", "25053RT47C", "Redmi", "Xiaomi");
    }

    /** The same proof bound to the registered profile, as every signed request carries it. */
    static byte[] deviceProof(Map<String, String> params) {
        String brand = params.get("device_brand");
        return proof(params.get("aid"), params.get("device_id"), params.get("cdid"), params.get("os_version"), params.get("device_type"), brand, brand);
    }

    private static byte[] proof(String aid, String deviceId, String cdid, String osVersion, String deviceType, String brand, String maker) {
        Object[][] fields = {{1, 2L}, {2, 4L}, {3, aid}, {4, deviceId}, {5, cdid}, {6, "!noperm!"},
            {7, 1777775L}, {8, 1777775L}, {9, 6L}, {10, 1777775L}, {11, "!notset!"}, {12, "Asia/Shanghai,8"}, {13, "zh_CN"}, {14, 8L},
            {16, 255.24993896484375f}, {17, 35.58599090576172f}, {18, 3.467449188232422f}, {19, 3.467449188232422f}, {20, 255.1754913330078f},
            {21, 42.17544174194336f}, {22, osVersion}, {23, 82L}, {24, 72L}, {25, 3456776033270L}, {26, 3456776033270L},
            {27, 3456776033270L}, {28, 3456776033274L}, {29, 1L}, {30, deviceType}, {31, brand}, {32, deviceType},
            {33, deviceType}, {34, maker}, {35, brand}, {36, brand}, {38, 62L}};
        SdkWire.Proto proto = new SdkWire.Proto();
        for (Object[] field : fields) put(proto, (Integer) field[0], field[1]);
        return proto.toByteArray();
    }

    /** The device description the exchange carries; unknown properties keep the SDK's explicit sentinel. */
    static byte[] devicePayload(Map<String, String> params, String marker, String token) {
        int[] strings = {1, 19, 20, 23, 28, 31, 32, 38, 39, 50, 53, 93, 94, 95, 96, 109, 110, 111, 115, 135, 2000, 2001};
        int[] numbers = {13, 29, 30, 36, 37, 41, 42, 43, 49, 51, 52, 56, 57, 58, 59, 60, 61, 62, 63, 64, 66, 67, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78,
            79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 113, 114, 116, 117, 118};
        Map<Integer, Object> values = new TreeMap<>();
        for (int field : strings) values.put(field, marker);
        for (int field : numbers) values.put(field, SdkSigner.signed(-999999));
        String[][] described = {{"2", "device_brand"}, {"3", "device_type"}, {"6", "os_version"}, {"7", "resolution"}, {"9", "rom_version"}};
        for (String[] field : described) { String value = params.get(field[1]); values.put(Integer.parseInt(field[0]), value == null || value.isEmpty() ? marker : value); }
        values.put(5, "Android"); values.put(12, "Asia/Shanghai,8");
        for (String[] field : new String[][]{{"8", "dpi"}, {"48", "os_api"}}) {
            String raw = params.getOrDefault(field[1], "");
            values.put(Integer.parseInt(field[0]), SdkSigner.signed(raw.matches("[0-9]+") ? Long.parseLong(raw) : -999999));
        }
        SdkWire.Proto inner = new SdkWire.Proto();
        for (Map.Entry<Integer, Object> field : values.entrySet()) put(inner, field.getKey(), field.getValue());
        SdkWire.Proto proto = new SdkWire.Proto();
        put(proto, 1, inner.toByteArray());
        if (token != null) put(proto, 2, token);
        put(proto, 3, "android"); put(proto, 4, SdkSigner.SDK_VERSION); put(proto, 5, SdkSigner.signed(SdkSigner.SDK_CODE)); put(proto, 6, params.get("aid"));
        put(proto, 7, params.get("version_name")); put(proto, 8, params.get("device_id")); put(proto, 11, SdkSigner.signed(-999999));
        put(proto, 16, SdkSigner.signed(-999999)); put(proto, 17, marker);
        return proto.toByteArray();
    }

    /** gzip with the reference's header (level 9 flags, no mtime, no OS). */
    static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1f); out.write(0x8b); out.write(8); out.write(0);
        for (int index = 0; index < 4; index++) out.write(0);
        out.write(2); out.write(3);
        Deflater deflater = new Deflater(9, true);
        try {
            deflater.setInput(plain); deflater.finish();
            byte[] buffer = new byte[2048];
            while (!deflater.finished()) { int count = deflater.deflate(buffer); if (count == 0) break; out.write(buffer, 0, count); }
            if (!deflater.finished()) throw new IOException("SDK 请求体压缩未完成");
        } finally { deflater.end(); }
        CRC32 crc = new CRC32(); crc.update(plain);
        for (int index = 0; index < 4; index++) out.write((int) (crc.getValue() >>> (8 * index)));
        for (int index = 0; index < 4; index++) out.write(plain.length >>> (8 * index));
        return out.toByteArray();
    }

    /** The protobuf the X-Medusa envelope seals, with the pieces it is bound to. */
    static final class Message {
        final byte[] proto, proof, prefix, queryDigest; final int seconds;
        Message(byte[] proto, byte[] proof, byte[] prefix, byte[] queryDigest, int seconds) {
            this.proto = proto; this.proof = proof; this.prefix = prefix; this.queryDigest = queryDigest; this.seconds = seconds;
        }
    }

    /** The sealed message of one request: identity, SDK context, the body proof and the environment block. */
    static Message message(Map<String, String> params, byte[] query, byte[] body, long milliseconds, SdkMaterials materials, SdkSigner.Core core,
                           String token, Randoms randoms, int pid, boolean exchange) throws Exception {
        int seconds = (int) (milliseconds / 1000);
        byte[] proof = SdkSigner.proof(query, body, seconds, core);
        byte[] prefix = new byte[8]; SdkWire.putLe32(prefix, 0, exchange ? 2 : 0x0504);
        byte[] counters = new SdkWire.Proto().number(1, 2).number(2, 1388734).number(3, 1388734).number(5, 1388734).toByteArray();
        byte[] environment = new SdkWire.Proto().number(1, 2).text(6, "v04.10.03.04-bugfix").number(7, pid * 2L).text(8, params.getOrDefault("channel", ""))
            .bytes(12, deviceProof(params)).text(14, params.get("version_name")).number(15, 4228).number(19, milliseconds * 2).toByteArray();
        String state = "{\"cmr\":16777216,\"cmr2\":16777216,\"vpn\":0,\"kd\":0,\"dyn\":\"\",\"do\":0,\"tk\":" + (token != null) + "}";
        byte[] queryDigest = DanmakuSigner.sm3(query);
        byte[] message = new SdkWire.Proto().bytes(1, materials.frame).number(2, 10).number(3, (randoms.nonce0 & 0xffffffffL) * 2).text(4, "8662")
            .text(5, params.get("device_id")).text(6, "646637794").text(7, params.get("version_name")).text(8, SdkSigner.SDK_VERSION)
            .number(9, SdkSigner.SDK_CODE * 2).bytes(10, prefix).number(12, seconds * 2L).bytes(13, proof).bytes(14, Arrays.copyOf(queryDigest, 6))
            .bytes(15, counters).text(16, token == null ? "" : token).number(17, seconds * 2L).text(20, "none").number(21, 738).bytes(23, environment).text(24, state)
            .toByteArray();
        return new Message(message, proof, prefix, queryDigest, seconds);
    }

    private static Signed sign(Map<String, String> params, Map<String, String> cookies, byte[] query, byte[] body, long milliseconds,
                               SdkMaterials materials, SdkSigner.Core core, String token, long previousLadon, boolean exchange, Randoms randoms, int pid,
                               String endpoint) throws Exception {
        Message message = message(params, query, body, milliseconds, materials, core, token, randoms, pid, exchange);
        int seconds = message.seconds;
        String medusa = SdkSigner.envelope(message.proto, message.queryDigest, message.proof, seconds, randoms.nonce1, randoms.nonce2, materials.signingKey,
            materials.frame, message.prefix, materials.substitutions);
        long nextLadon = SdkSigner.advanceLadon(previousLadon, randoms.ladonAllocation, seconds);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/octet-stream"); headers.put("Accept", "*/*"); headers.put("X-TT-Request-Tag", "t=0;n=0"); headers.put("X-SS-DP", "8662");
        headers.put("X-Khronos", Long.toString(seconds)); headers.put("X-Argus", Base64.getEncoder().encodeToString(SdkWire.le32(seconds)));
        headers.put("X-Ladon", Base64.getEncoder().encodeToString(SdkWire.be32(nextLadon)));
        headers.put("X-Gorgon", SdkSigner.gorgon(query, body, seconds, randoms.gorgonNonce, Arrays.copyOf(message.prefix, 2)));
        headers.put("X-Helios", PlayerSigner.ladon(seconds, SdkSigner.LICENSE, 8662L, randoms.helios)); headers.put("X-Medusa", medusa);
        headers.put("X-SS-STUB", DanmakuSigner.hex(DanmakuSigner.md5(body)).toUpperCase(Locale.ROOT)); headers.put("X-VC-Bdturing-SDK-Version", "4.0.3.cn");
        headers.put("X-TT-Trace-ID", "00-" + DanmakuSigner.hex(randoms.traceA) + "-" + DanmakuSigner.hex(randoms.traceB) + "-01");
        headers.put("User-Agent", "com.phoenix.read/74332 (Linux; U; Android " + params.get("os_version") + "; zh_CN; " + params.get("device_type")
            + "; Cronet/TTNetVersion:8d40f833_2026-03-03)");
        StringBuilder cookie = new StringBuilder("install_id=").append(params.get("iid"));
        for (String name : new String[]{"odin_tt", "ttreq", "store-region"}) {
            String value = cookies.get(name);
            if (value != null && !value.isEmpty()) cookie.append("; ").append(name).append('=').append(value);
        }
        headers.put("Cookie", cookie.append("; store-region-src=did").toString());
        return new Signed(endpoint + "?" + new String(query, StandardCharsets.US_ASCII), headers, body, nextLadon);
    }
}
