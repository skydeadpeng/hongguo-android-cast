package io.github.jackotom.hongguo.android;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/** Golden-vector parity with src/native/playback-signature.test.cjs — the Java twin must stay byte-identical. */
public final class PlayerSignerTest {
    static int checks;
    static void check(boolean ok, String text) { checks++; if (!ok) throw new AssertionError(text); }
    static void equal(Object actual, Object expected, String text) { check(java.util.Objects.equals(actual, expected), text + " actual=" + actual); }

    static final String[][] GOLDEN = {
        { "aid=1967&device_id=dev_aaaaaaaa&iid=111", "1700000000", "659662259",
          "8oHyrVYEy1xDCDlx64ko40qC6JC9VYY/ZkjJzEl50iSFDw8Yfph1hupLxfKrA9GswSEz0Yi4goA7OQ4u+H8ECdOaQDL4GduQ3LULSq9Q4iSEGbS3L6G24GXChtnmPr6n46M2PLlKpXvF7uE0lhH4yD+X69FpdM41gQwx5Y5U3NdwXFaxlqYFszyp7dlUE6BEBWqwDsiAH8Jk9TGKr/jfHt4fVmMc9zRyNRdGeV0x6aP//x7aV+8Po8a/9KK3UNG5KEpmIgzRT6NUttkBP9gRNUh3" },
        { "aid=1967&device_id=dev_bbbbbbbbbbbbbbbb&iid=222", "1700000001", "1315257197",
          "8oGWgilKQHD4eCdd0VB0vBSbTAkv/3xWE2YWJkxic35CgXkxrUlMTz05HJAak5b5Xx9yxohnoAAcEX//MI9t7XBBtnPwOTaJzkJgBLaYOOotfI7YuqhsEbE5BB363bhKz/Qw/+MI5gH6pgdAXfsRj68u7Qwu3gqCY1NYstqNZFFVrph7dWtXDBYM1aoVEyfkYD2haoaOr9D/gl1zdzAAzbW7we6lr9/zFApbuNsbr2dagdUrQc51QNN0HBOtIkpVBVunDwXM8qR271Ei+Pa4EgKy" },
        { "aid=1967&device_id=X&iid=333", "1234567890", "554000487",
          "8oEOCRTb0LQf+M+MsliYu4M9kBzAlzXvFgL/Ub198hyoBGxYfNXLAENmH+K/10JQ+Rbhm2zRxNtNv5wVd885xcb6C6F10zp/9jVeGHGz8plz8W3UXHHNrFKjJptjQElGDbyaVLSKt56+HfwO7ENYMfjVv/5PNi1upVciiHqyOPDDAtwi/fZkwBoU1kC7RolMOuedn4lCg/s0CjnNvxFT0QpF/K0yoW3Zpu30tkadIET7WbZ3+7KT3c1yAA6RKWI4mmw=" },
        { "aid=1967&device_id=test_device_id&iid=456&version_name=6.5.1.32", "1700000000", "420072733",
          "8oHu8LnZm0OtJJIaSr1427D+yeH70h5aZTVjoSW7MfFi/hk1LhjTqsEEWYpwcHfK4/2SXuJxTtopRcPeHzVpeFpUD4B4yxEG7REejpCZ1OqeAaLsw/lMebwUjUHrUjnAZCKq0QyimnwdiTiYIsH3GfiK/xlPcFcARqwX7RkI1qnnWUgiJVDEacSm8yhBat6MvDVOa7+U2MgcLu7Q26pFzX8VX5F+ryP76ETs1nRGLmlhHkwyiKb6CETstvGQnJgocgZBNNvlHD2/P+4LWjFMvhNK" },
    };

    public static void main(String[] args) throws Exception {
        // 1. ProtoBuf exact bean and varint truncation.
        PlayerSigner.ProtoBuf bean = new PlayerSigner.ProtoBuf();
        bean.putVarint(1, 1077940818L).putVarint(2, 2).putVarint(3, 305419896)
            .putUtf8(4, "1967").putUtf8(5, "test_device_id").putUtf8(6, "1611921764")
            .putUtf8(7, "").putUtf8(8, "").putVarint(9, 0).putUtf8(10, "").putUtf8(11, "")
            .putVarint(12, 3400000000L).putBytes(13, PlayerSigner.hex("106e34a2b8c7")).putBytes(14, PlayerSigner.hex("ac5095fc674e"))
            .putProtobuf(15, new PlayerSigner.ProtoBuf().putVarint(1, 1).putVarint(2, 1).putVarint(3, 1).putVarint(7, 0xc792ecccL))
            .putUtf8(16, "").putUtf8(20, "none").putVarint(21, 738)
            .putProtobuf(23, new PlayerSigner.ProtoBuf().putUtf8(1, "NX551J").putVarint(2, 8196).putVarint(4, 0x80e0d800L))
            .putVarint(25, 2);
        equal(hexOf(bean.toBuf()),
            "08d2a4808204100218f8acd191012204313936372a0e746573745f6465766963655f6964320a313631313932313736343a004200480052005a006080c49fd50c6a06106e34a2b8c77206ac5095fc674e7a0c08011001180138ccd9cbbc0c820100a201046e6f6e65a801e205ba01110a064e583535314a1084402080b0838708c80102",
            "protobuf bean hex");
        equal(hexOf(new PlayerSigner.ProtoBuf().putVarint(1, -1L).putVarint(2, 4294967301L).toBuf()), "08ffffffff0f1005", "varint uint32 truncation");

        // 2. Simon ciphertext and final four expanded round keys.
        long[] key = { 1111111111111111111L, 2222222222222222222L, 3333333333333333333L, 4444444444444444444L };
        long[] encrypted = PlayerSigner.simonEncrypt(1234567890123456789L, Long.parseUnsignedLong("9876543210987654321"), key);
        equal(encrypted[0], 6071150433487837324L, "simon ciphertext low");
        equal(encrypted[1], 2027321285990159935L, "simon ciphertext high");
        long[] expanded = PlayerSigner.simonKeyExpansion(key);
        long[] tail = { expanded[68], expanded[69], expanded[70], expanded[71] };
        long[] want = { Long.parseUnsignedLong("13679004903447286117"), 6988159389011166349L, Long.parseUnsignedLong("12845848347567849394"), Long.parseUnsignedLong("14469362449063813004") };
        check(Arrays.equals(tail, want), "simon expanded tail " + Arrays.toString(tail));

        // 3. Ladon fixed random golden.
        equal(PlayerSigner.ladon(1700000000L, 1611921764L, 1967, PlayerSigner.hex("01020304")),
            "AQIDBKM7Y7bWnOIUXmTybD6/MpGbuge4sBwUJsyHVivhtMsH", "ladon golden");

        // 4. Hash vectors.
        equal(hexOf(PlayerSigner.bodyHash("")), "106e34a2b8c7", "bodyHash empty");
        equal(hexOf(PlayerSigner.queryHash("")), "106e34a2b8c7", "queryHash empty");
        equal(hexOf(PlayerSigner.queryHash("aid=1967&device_id=123")), "925b8d86b20f", "queryHash sample");
        equal(hexOf(PlayerSigner.bodyHash("ac3ef48caa08fa3ed5e025da69edc645")), "5048b6e949d0", "bodyHash stub");

        // 5. Argus golden vectors (incl. nonce truncation) and signPlayer wiring.
        for (int i = 0; i < GOLDEN.length; i++) {
            String[] row = GOLDEN[i];
            String device = queryDevice(row[0]);
            equal(PlayerSigner.argus(row[0], "", Long.parseLong(row[1]), 1967, 1611921764, device, "", Integer.parseInt(row[2])), row[3], "argus vector " + (i + 1));
            equal(PlayerSigner.argus(row[0], "", Long.parseLong(row[1]), 1967, 1611921764, device, "", (int) (Integer.parseInt(row[2]) + 4294967296L)), row[3], "nonce truncation " + (i + 1));
            Map<String, String> headers = PlayerSigner.signPlayer(row[0], null, Long.parseLong(row[1]), Integer.parseInt(row[2]), PlayerSigner.hex("01020304"));
            equal(headers.get("X-Argus"), row[3], "signPlayer argus " + (i + 1));
            if (Long.parseLong(row[1]) == 1700000000L)
                equal(headers.get("X-Ladon"), "AQIDBKM7Y7bWnOIUXmTybD6/MpGbuge4sBwUJsyHVivhtMsH", "signPlayer ladon " + (i + 1));
            equal(headers.get("X-Khronos"), row[1], "signPlayer khronos " + (i + 1));
            equal(headers.get("X-SS-Req-Ticket"), Long.toString(Long.parseLong(row[1]) * 1000), "signPlayer ticket " + (i + 1));
            check(!headers.containsKey("X-SS-STUB"), "no stub without body " + (i + 1));
        }
        // 6. Body stub binding.
        byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> withBody = PlayerSigner.signPlayer(GOLDEN[3][0], body, 1700000000L, 420072733, PlayerSigner.hex("01020304"));
        equal(withBody.get("X-SS-STUB"), "ac3ef48caa08fa3ed5e025da69edc645", "stub of body");
        check(!withBody.get("X-Argus").equals(GOLDEN[3][3]), "argus changes with body");
        check(!PlayerSigner.signPlayer(GOLDEN[3][0], "{\" x\":1}".getBytes(StandardCharsets.UTF_8), 1700000000L, 420072733, PlayerSigner.hex("01020304")).get("X-SS-STUB").equals(withBody.get("X-SS-STUB")), "stub binds exact body bytes");
        check(!PlayerSigner.signPlayer(GOLDEN[3][0] + "&extra=%2A", null, 1700000000L, 420072733, PlayerSigner.hex("01020304")).get("X-Argus").equals(GOLDEN[3][3]), "argus binds encoded query bytes");

        // 7. Pre-API-33 runtime linkage and encoded-query compatibility.
        // Scan the compiled class descriptors, not the source or the host JDK's available methods.
        try (InputStream compiled = PlayerSigner.class.getResourceAsStream("PlayerSigner.class")) {
            check(compiled != null, "compiled signer is available for the API compatibility check");
            String classFile = new String(compiled.readAllBytes(), StandardCharsets.ISO_8859_1);
            check(!classFile.contains("(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"),
                "Android API 26–32 lacks URLDecoder.decode(String, Charset)");
        }
        String encoded = "%61id=1967&device%5Fid=%E7%BA%A2%E6%9E%9C+%2B&version_name=0.1.20%2Btv";
        equal(PlayerSigner.queryParam(encoded, "aid"), "1967", "percent-encoded parameter name");
        equal(PlayerSigner.queryParam(encoded, "device_id"), "红果 +", "UTF-8 and form plus handling");
        equal(PlayerSigner.queryParam("device_id=bad%+value", "device_id"), "bad%+value", "invalid percent escape preserves original text");
        Map<String, String> encodedHeaders = PlayerSigner.signPlayer(encoded, null, 1700000000L, 420072733, PlayerSigner.hex("01020304"));
        equal(encodedHeaders.get("X-Argus"), "8oEOCRTb0LQf+M+MsliYu4M9kBzAlzXvFgL/Ub198hyoBGxYfNXLAENmH+K/10JQ+Ra0gXmHLAFH8GNh819d8QNawzUgjR0BmQ5TGizvBwV5Xlvr+io3wzIMQDTWeSQqUGy3hDaRqhINL8g/Zn3Hh5mg6UMMGvOQRE+V+68q6vke6exVDht30yiDCnpibW6ywGhf5eXuwzQtMOnSBipHhCf1ML2vSAM5lGn8oSFsOLcMkhxw6A1QLT8Y3hILYO1KbJxYCO+Rl4SzWXMhi0UKd5Tn", "encoded Argus pre-fix vector preserved");
        equal(encodedHeaders.get("X-Ladon"), "AQIDBKM7Y7bWnOIUXmTybD6/MpGbuge4sBwUJsyHVivhtMsH", "encoded aid produces original Ladon vector");

        System.out.println("PLAYER_SIGNER_OK checks=" + checks);
    }

    static String queryDevice(String query) {
        String value = PlayerSigner.queryParam(query, "device_id");
        return value == null ? "" : value;
    }
    static String hexOf(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }
}
