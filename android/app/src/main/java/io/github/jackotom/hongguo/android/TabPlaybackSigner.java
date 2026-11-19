package io.github.jackotom.hongguo.android;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anonymous video_tab recovery GET, matched to N3urda/hongguoTV 478f006
 * ContentRepository.playbackRequest + Signer.signPlayback(mobileV5=false).
 * This is the legacy Medusa branch-0 protocol, not the PlayerSigner 551 protocol.
 * Pure transforms are shared with AccountSigner; account request construction is not.
 */
final class TabPlaybackSigner {
    static final String API = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/";
    static final String USER_AGENT = "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long PROCESS_STARTED = System.currentTimeMillis();
    private static final String PROCESS_NONCE;
    static {
        byte[] bytes = new byte[18]; RANDOM.nextBytes(bytes);
        PROCESS_NONCE = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private TabPlaybackSigner() { }

    static PlaybackSignerV5.Request sign(String did, String iid, String cdid, String vid,
                                         String registrationCookies, long now) throws Exception {
        int[] randoms = new int[7];
        for (int i = 0; i < randoms.length; i++) randoms[i] = RANDOM.nextInt();
        return sign(did, iid, cdid, vid, registrationCookies, now, PROCESS_NONCE, PROCESS_STARTED, randoms);
    }
    /** Deterministic seam for byte-for-byte, independently generated Kotlin oracle vectors. */
    static PlaybackSignerV5.Request sign(String did, String iid, String cdid, String vid,
                                         String registrationCookies, long now, String nonce,
                                         long processStarted, int[] randoms) throws Exception {
        if (did == null || !did.matches("[1-9][0-9]{0,29}") || iid == null || !iid.matches("[1-9][0-9]{0,29}")
                || vid == null || !vid.matches("[0-9]{1,30}") || cdid == null || cdid.isEmpty() || cdid.length() > 128
                || randoms == null || randoms.length != 7)
            throw new IllegalArgumentException("播放恢复请求信息无效");
        // Preserve VendorConstants.device insertion order. The registered identity overwrites
        // the two initial slots and adds cdid at the end, exactly like Profile.values.
        Map<String, String> values = new LinkedHashMap<>();
        String[][] fields = {{"iid",iid},{"device_id",did},{"ac","wifi"},{"channel","update_64"},
            {"aid","8662"},{"app_name","novelread"},{"version_code","73932"},{"version_name","7.3.9.32"},
            {"device_platform","android"},{"os","android"},{"ssmix","a"},{"device_type","25053RT47C"},
            {"device_brand","Redmi"},{"language","zh"},{"os_api","36"},{"os_version","16"},
            {"manifest_version_code","73932"},{"resolution","1280*2772"},{"dpi","520"},
            {"update_version_code","73932"},{"host_abi","arm64-v8a"},{"dragon_device_type","phone"},
            {"pv_player","73932"},{"compliance_status","0"},{"need_personal_recommend","1"},
            {"player_so_load","1"},{"is_android_pad_screen","0"},{"cdid",cdid},
            {"video_id",vid},{"video_id_type","0"},{"device_level","1"},{"need_all_video_definition","1"},
            {"need_mp4_align","0"},{"use_os_player","0"},{"video_platform","1024"},{"source","0"},{"from_video_id",""}};
        for (String[] field : fields) values.put(field[0], field[1]);
        int ts = (int) (now / 1000);
        String query = null, url = null; long ticket = now;
        for (int offset = 0; offset <= 1023; offset++) {
            ticket = now + offset;
            values.put("_rticket", Long.toString(ticket));
            query = AccountSigner.pythonUrlEncode(values); url = API + "?" + query;
            if (AccountSigner.branchOf(url, null, ts) == 0) break;
            if (offset == 1023) throw new IllegalStateException("播放恢复签名生成失败");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
        headers.put("x-ss-req-ticket", Long.toString(ticket));
        headers.put("sdk-version", "2"); headers.put("x-xs-from-web", "0");
        headers.put("x-khronos", Integer.toString(ts));
        headers.put("x-ladon", Base64.getEncoder().encodeToString(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(ts).array()));
        headers.put("x-argus", Base64.getEncoder().encodeToString(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ts).array()));
        headers.put("x-gorgon", AccountSigner.xGorgon(query, null, ts, randoms[0] >>> 16));
        headers.put("x-helios", AccountSigner.helios(ts, randoms[1]));
        headers.put("x-medusa", Base64.getEncoder().encodeToString(AccountSigner.buildMedusa(
            url, null, ts, did, "7.3.9.32", nonce, processStarted, randoms)));
        String cookie = registrationCookies(registrationCookies);
        if (!cookie.isEmpty()) headers.put("Cookie", cookie);
        return new PlaybackSignerV5.Request(url, headers, new byte[0]);
    }
    /** Only registration's routing cookies, never an account or generic cookie jar. */
    static String registrationCookies(String raw) {
        if (raw == null || raw.length() > 16384 || raw.chars().anyMatch(c -> c < 32 || c == 127)) return "";
        Map<String,String> allowed = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            int at = part.indexOf('='); if (at < 1) continue;
            String name = part.substring(0, at).trim(), value = part.substring(at + 1).trim();
            if (name.equals("install_id") || name.equals("ttreq") || name.equals("store-region") || name.equals("store-region-src"))
                allowed.put(name, value);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String,String> entry : allowed.entrySet()) {
            if (out.length() > 0) out.append("; "); out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }
}
