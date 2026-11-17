package io.github.jackotom.hongguo.android;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Golden-vector parity with src/native/playback-signature-v5.js and N3urda/hongguoTV 0.16.5
 * (kotlin-tv/core/src/test/resources/signing-v5-{f13,carrier}.json, GPL-3.0-only).
 * The Java twin must stay byte-identical to both. Vectors are inlined (synthetic SDK
 * samples upstream; they cover all 32 timestamp rotation classes and 4 carrier variants).
 */
public final class PlaybackSignerV5Test {
    static int checks;
    static void check(boolean ok, String text) { checks++; if (!ok) throw new AssertionError(text); }

    // {ts, querySm3, bodyMd5, digest+checksum(20B)}
    // Complete outputs independently generated with upstream Kotlin Signer, not this Java/JS twin.
    // Regeneration/evidence: verification/playback-repair-2026-11-17/protocol/run-oracle.sh
    static final String[][] F13 = {
        {"1800000000","97f67a81cea547c71c76702cc529330762c14971d85f3c335467579d1e9c15e3","0ff85a33ee8c8aa4f59a843d5199909f","ca03ec7a0c2444b3dcccf33453d0257936dbddf9"},
        {"1800000001","959b0479339d92d4ab2c428cff3daa0349a78072e56115d2fd8181ef00ef722d","49783eb0095375c17655cdc1ff329874","a1376d7977abbc1dbc536aae0429ac2c37e0b914"},
        {"1800000002","37c02962735148636add7f1430394e36013faedcd015f1c5519773153b0efc81","c9ee9a33be57c4125ee742ee67f68cff","acadd6dc830f1d3249c77c86d1a03ab737fc72a0"},
        {"1800000003","1ec50c23a9d19b673a4b7037cc51f20b594f04d37234da13b52321f5fd1c7a18","fcd833cf4fa23db062ea04a99de1f6f1","7f18dac9add0b303bc6fdd4bfd181b397ef5883e"},
        {"1800000004","b11761b148eaadce909a31b3c0c4e9dc7a6db19d89e4d036bf9948d4f7d2cd47","302e1e011fcde596282923682a4f74ca","ef2475d793d3228e1bb40c2d5258b32fb739daf5"},
        {"1800000005","925d3850c921a6cb9174d581243cc0c4150e51bdd144205b462acbd2cd46e5db","7dd8c5bccd64ae6e1c58ade2c929ac41","dc1da7cf8c897dffc390d0997c58f9167e92e6bd"},
        {"1800000006","69d9f1d85c7a7421e14153fa37535bc0d04a0dc755ad1e13e0a65ab2a96fbbba","34b3025ffca5e08743b49f6ce3b6abf8","d45e587fbbd4798ceab2623b5237f104a42934fe"},
        {"1800000007","c7c03633bfff0551b4975ea7b31929f5d1fa250ba492d472a5a511b3e5b4c45c","92df202c08c24d635f0154095848657a","bdb494221bc010227df67b21ad814938bd79227e"},
        {"1800000008","c0f19b22831d1561f17b85e4b58dc90b2d274bcf1ec00fe24f270f4bcacf9602","7418334aa5d188fc874f67deea9a21d7","fedb0bbc2c073944c7378d6e94eafce824ef9e27"},
        {"1800000009","e60f9c10bb6e74959462742e18470865a9d5a0e693504edc6e7c47155d54c898","e95c11c6ebf1b2019000b5d2b6efb2b9","24bdcc955e3f0cab49aba64f91712c9877fab7f2"},
        {"1800000010","9f44488778e4730fb6495e32faaea2bf97ee6be47e7a7ce73c5ce92f34b7f2d9","1f85408ee383baa76345e77f16a08293","fccc11f6138080539b1548d0b9055bd327cb6652"},
        {"1800000011","76bb13d24db94ecede1be7ab5047d442e279ebaedbec12a6002ff1736320eb70","6befdc0280d0eb9b00864f3b10fd6e9f","8b85e9d9dda0b53ab7bed85d7a0546f06769d6cb"},
        {"1800000012","f08e4771e3ec68f66503a4138ba295646ff135891793aa2c48703fbbe83f959a","7fe849ed9d3bc1057fb8e7d05d42d50a","abf54872e5b0589a490ad8f77fc80144edb44af8"},
        {"1800000013","22caa8612064c469bb6f3038b92d46c81660dbbf76e0bb1c1ea06d7dcf35df1c","fc1361b74ef50e9a8f1637e1fca4d838","7a22986242c909f50fae3460cacd6775b4c976b1"},
        {"1800000014","7fea02e2070957c54a1352e67e905f56b95a9d9ff7bba694a8bad7f0f2c910ed","48477c26e58747dabfc0b2290185eef2","c279b885497eeadf9ac22d4fb7a34b34ad70713e"},
        {"1800000015","ddf456d095ef231ae9a0332848c86372d34079e8e784bfa319f0c84703daf67d","e93ce6e2ce1bcdaeb93bed8463fbb5b5","3e5d88f088e9e56a668fb0020d9a1832adcf76bd"},
        {"1800000016","0ab8312b0ab537ab8614b73c84d1e651b91a383ff96524b0850526dfea8e2edf","e37a63574ca36affff4b9bab95d76fce","e47ce104e1708f47b58c35e62d757e34f59e9b2e"},
        {"1800000017","3804c6a08ddf104c3775f2383e7a31ad1246464d3ab402ec1169ddbfc7aaf354","78eecf206f357a9caca88b91ef134743","30d0ff8ad17ea5bc87fe24f6a03d21116f1a264c"},
        {"1800000018","f89abdaca254a6f22a01ccd23a9a3408c3c62f1ce67428e1e14fe2ecd7513df1","c105211c82a27ccd4ca00daccd7b8710","f1eb59c415f6edc215d0a8ab50ec88d8a44d77ce"},
        {"1800000019","a239a3356cc751cddcaee6e06a3d15e1caaae43f2f85a261f14945934ab54c4e","526fda00a40beaabeeb8c150f71cc210","8645f854bdaf96d4bf385432594d5a1d6cd6f403"},
        {"1800000020","50d51b627a3e67f00fd556534dc3d155ae8402198a8e6a734edc7bddabc6a4f1","4678211f3a092c6af8e75cca1afc31ca","fb3e103857894dc97a4dde6864f084cc3de4d97b"},
        {"1800000021","b78cc5d1b14cee008085e0e0f3a8b5be7d3dbca0cebc37f4b5ffd574a9df00c6","5fbf4ba47b8610fa654b96ca2818ba91","5701b2a57c5d497f7431cc610624b5f037ce6f3d"},
        {"1800000022","adf6b9936576b9708ae1a393182a1ef0fede156ffd41c69b9ecf9d3ce2263aa4","9dbfb4aa682f1cc4e0efe2896c603fe4","ae03abe442d4d601bc2135ee4529b72d27f648be"},
        {"1800000023","c00beda338b6c034ee147173ab78f678bc56eed8f2aa9c1cb7bac157b87e36f5","fd5139f800ac11ada82a542131551bac","90a1698d9022aa5a91daf9db53f7ed63bd35e821"},
        {"1800000024","ccc3b48e070a81622e0af3b030faba24be90b07c8d6d92ee64a94c907dec0b90","8f32ebc7f408721e94a14ff6fb31299b","8400797d526a9e55ad99dcbaa5d0be13b72c71fb"},
        {"1800000025","fea6f6ff01087847c4eb2f2c6e4424c6a7b92aec5d624a450529f9321fa03850","cdcf940a7d471c6db8f2f033ce593da0","a486f40927fc2c015fa56b441af15f42b45ac346"},
        {"1800000026","bada73694f51f051031c710f78a7ba3c6c677c634a63198757f8a1940ba6d22f","a78390d73256cd71e4830aab685c8a71","6c9e4b9fe7c7646e3ffcb6125f7aca766f444287"},
        {"1800000027","6d7e4dacc21b0b5d2cd54138f941bc98cff8ee59f27ac205527b3e3207d19691","094f2d484098b4a46ae820ddfb001bcc","9e2433cc7076f16614d3a9f5744037233cf2cae0"},
        {"1800000028","6b25972f9f1b7e059d1cbfafa2b168a4872f818d2d69987b388da7a9a056dc7c","5918ae7dcca9d95fca221de508800bf8","fb9cbb60efdf6e90192fe3bfe8912a03b465c6a7"},
        {"1800000029","d0c1ec04b4affb0401da90d6f403eb7a2ea095d7fb2789caa3f24eb97b4e84aa","5ec477ba383495da0215c7abc93fe061","28da0f1ae9f4bf3b47af5c8de041fa047564b1c3"},
        {"1800000030","9f1231092eccc944f23ace788382f6582c7539217899b49064e73fe7bb5f2c4b","cc13517bcf9e05a656bf58ed06e53790","3e820f120bec38f67ed05de1dd02b79e772d4c4a"},
        {"1800000031","7452b4261e5ba66aa463bbc4892aa93c11f3f4005b3747c6adabb6359212d2e8","f5b46ee83c8b3c78876cd15e992c3c82","26c2a2a9bd5a7f468b3f1f4525c0ba18f59d67c6"}
    };
    // Kotlin signPlayback selected tickets for the synthetic map/body below; catches
    // a shared wrong branchClass/signPlayback implementation that self-checks cannot.
    static final long[] SELECTED_TICKETS = {
        1800000000006L, 1800000001006L, 1800000002009L, 1800000003001L, 1800000004035L, 1800000005020L, 1800000006006L, 1800000007047L, 1800000008032L, 1800000009025L, 1800000010012L, 1800000011002L, 1800000012003L, 1800000013012L, 1800000014004L, 1800000015009L, 1800000016018L, 1800000017035L, 1800000018018L, 1800000019002L, 1800000020018L, 1800000021000L, 1800000022014L, 1800000023004L, 1800000024002L, 1800000025001L, 1800000026037L, 1800000027001L, 1800000028002L, 1800000029019L, 1800000030001L, 1800000031003L
    };
    // {ts, key, iv, source, encrypted}
    static final String[][] CARRIER = {
        {"1800000000","000102030405060708090a0b0c0d0e0f","101112131415161718191a1b1c1d1e1f","13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0d5fa1f44698eb3d8fd22476c91b6db00254a6f94b9de03284d7297bce1062b50759abfe4092e53789dc2e70c31567ba0c5ea0f34597ea3c8ed12375c81a6cbf0153a5f84a9cef3183d6287acd1f61b40658aafd4f91e43688db2d7fc21466b90b5daff24496e93b8dd02274c7196bbe0052a4f7499bee3082d52779cc1e60b30557a9fc4e90e33587da2c7ec11365b80a5caef14395e83a8cdf2173c6186abd0f51a3f6489aed3f81d42678cb1d6fb20456a8fb4d9fe23486d92b7dc01264b7095badf04294e7398bde2072c51769bc0e50a2f54799ec3e80d32577ca1c6eb10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0d5fa1f44698eb3d8fd22476c91b6db00254a","0653385d92a3ccf3177b608dbacbf41b3f23a8a5c2f79c4367cb90ddea1b44698fb3f8fd3243ec91b79b002d4a6b14bbdf03084d7293bce1066b70759abb640b2f53589dd2e78c33567ba0c5ea0bb45b7fa3e8e51233dc81a78bf01d2a5f04abceb3383572872cd3f65b406d9aab54f91e436885a2d37c21462b90bddaffa4496ed398dd12234c7196bbe00d3a4bf499bfa3282542779cc1e74b105d7a9bc4eb0e337875b2c36c11365ba0adcaef94395fc3a8cde217bc6387abf0fd1a3b648baf93d81552670cb1d6fb004d6a8fb4dbfe63486d92b7dc03264b5095badf84294e33b8bdf203ac51769bc0ed1a2fd4799fc3c80d2253fca3c6eb10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0d5fa1f44698eb3d8fd22476c91b6db00254a"},
        {"1800000001","000102030405060708090a0b0c0d0e0f","101112131415161718191a1b1c1d1e1f","13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0d5fa1f44698eb3d8fd22476c91b6db00254a6f94b9de03284d7297bce1062b50759abfe4092e53789dc2e70c31567ba0c5ea0f34597ea3c8ed12375c81a6cbf0153a5f84a9cef3183d6287acd1f61b40658aafd4f91e43688db2d7fc21466b90b5daff24496e93b8dd02274c7196bbe0052a4f7499bee3082d52779cc1e60b30557a9fc4e90e33587da2c7ec11365b80a5caef14395e83a8cdf2173c6186abd0f51a3f6489aed3f81d42678cb1d6fb20456a8fb4d9fe23486d92b7dc01264b7095badf04294e7398bde2072c51769bc0e50a2f54799ec3e80d32577ca1c6eb10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0d5fa1f44698eb3d8fd22476c91b6db00254a","7253185592a7ccf3177b408dbacb74193e63a8a5d2f39c4366cb90d5fa1f44698fb3d8fd32476c91b6db00254a6f94bbde0308457293bce3076b707d9abf64092f137895d2e30c33567b80c5fa0f34597fe3e8e51237dc83a68bd0152a5b84abcff3183562832cd3f71b606d8aaf54fb1f03688db2d37c21462bb0bdcaff24496fd3b8d502274c7197bbc0052a4f749bbea3282d52771cc1e60b105d7a9fc4eb0e737875a2c76c11375ba0a5caeb14395e8388c5e217bc6186ebd0fd1a3b648baf93f81d52638cb1d7fb00457a8f34d9ff23686d92b7dc01274b509dbadf84294e3398b5f207ac51779bc0e50a2fd4799fc3c80d3257fca1c7eb10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0d5fa1f44698eb3d8fd22476c91b6db00254a"},
        {"1800000002","000102030405060708090a0b0c0d0e0f","101112131415161718191a1b1c1d1e1f","13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0d5fa1f44698eb3d8fd22476c91b6db00254a6f94b9de03284d7297bce1062b50759abfe4092e53789dc2e70c31567ba0c5ea0f34597ea3c8ed12375c81a6cbf0153a5f84a9cef3183d6287acd1f61b40658aafd4f91e43688db2d7fc21466b90b5daff24496e93b8dd02274c7196bbe0052a4f7499bee3082d52779cc1e60b30557a9fc4e90e33587da2c7ec11365b80a5caef14395e83a8cdf2173c6186abd0f51a3f6489aed3f81d42678cb1d6fb20456a8fb4d9fe23486d92b7dc01264b7095badf04294e7398bde2072c51769bc0e50a2f54799ec3e80d32577ca1c6eb10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0d5fa1f44698eb3d8fd22476c91b6db00254a","5553185592a3ccf1173b408daacff41b3f6388add2f71c4167cb90ddea1fc46b8ff3f8f52243ec91b6db20255a6b14b9de0328457297bce1072b707d8abfe40b2f13589dd2e38c31567b80c5fa0bb45b7fa3c8ed02335c83a68bf01d3a5f04abcef3383d6287acd1f75b60658aabd4f91e034885a2d7fc21466bb0bddaffa44b6ed3b8d502274c7196fbe00d3a4b749bbee3282d52731cc1e64b30556a9fc4e90f33587da2c36c13365ba0adcaeb94395e83a8cde217bc6386ebd0f50a3fe489ae93f81542638cb1d7bb004d7a8fb4d9fe63486592b3dc03274b7095badb84294f73b8b5e207ac5377dbe0ed0a2bd47b9f83c8052257fca3c7eb10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0d5fa1f44698eb3d8fd22476c91b6db00254a"},
        {"1800000003","000102030405060708090a0b0c0d0e0f","101112131415161718191a1b1c1d1e1f","13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0d5fa1f44698eb3d8fd22476c91b6db00254a6f94b9de03284d7297bce1062b50759abfe4092e53789dc2e70c31567ba0c5ea0f34597ea3c8ed12375c81a6cbf0153a5f84a9cef3183d6287acd1f61b40658aafd4f91e43688db2d7fc21466b90b5daff24496e93b8dd02274c7196bbe0052a4f7499bee3082d52779cc1e60b30557a9fc4e90e33587da2c7ec11365b80a5caef14395e83a8cdf2173c6186abd0f51a3f6489aed3f81d42678cb1d6fb20456a8fb4d9fe23486d92b7dc01264b7095badf04294e7398bde2072c51769bc0e50a2f54799ec3e80d32577ca1c6eb10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0d5fa1f44698eb3d8fd22476c91b6db00254a","4b13185d82a34cf3177b608dbacbf41b3e63a8adc2f79c4367cbb0d5fa1fc46b8fb3d8f53243ec93b69b20254a6f14bbdf03084d6293bce3072b70758abf640b2e535895d2e30c31567ba0cdfa0fb45b7fe3e8ed0233dc81a7cbd01d2a5f84abcef338356287acd3f71b406d8aabd4fb1e03688da2d7fc23462bb0bdcaffa44b6e9398d50223cc7196bbc00d3a4f7499bfe3282d42739cc3e64b105d7a9f44e90f735875a2c7ec13375b80a5caef143b5fc3a8c5e217bc6186ebd0f51a3f6489ae93d81552630cb3d7fb20457a8b34d9fe23486d92b7dc03264b509dbadb842b4e73b8b5f207ac5377dbe0ed0a2bd4799e83c80d3253fca1c7eb10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0d5fa1f44698eb3d8fd22476c91b6db00254a"}
    };

    public static void main(String[] args) {
        check(F13.length == 32, "f13 vector count actual=" + F13.length);
        for (int i = 0; i < F13.length; i++) {
            String[] row = F13[i];
            long ts = Long.parseLong(row[0]);
            byte[] out = PlaybackSignerV5.hashF13(PlaybackSignerV5.hex(row[1]), PlaybackSignerV5.hex(row[2]), ts);
            check(out.length == 20, "f13 length class " + i);
            check(PlaybackSignerV5.hexText(out).equals(row[3]), "f13 full digest+checksum class " + i + " ts=" + ts);
        }
        check(CARRIER.length == 4, "carrier vector count actual=" + CARRIER.length);
        for (int i = 0; i < CARRIER.length; i++) {
            String[] row = CARRIER[i];
            byte[] encrypted = new PlaybackSignerV5.AesV3V5(PlaybackSignerV5.hex(row[1]), Long.parseLong(row[0]))
                .encrypt(PlaybackSignerV5.hex(row[3]), PlaybackSignerV5.hex(row[2]));
            check(PlaybackSignerV5.hexText(encrypted).equals(row[4]), "carrier variant " + i);
        }
        // signPlayback: only _rticket moves; the signature binds the exact body bytes, the
        // timestamp and the identity. Mirrors SignerV5Test.adjustsOnlyTicketAndBinds....
        // The stub expectation is the fixed protocol digest of the body below.
        byte[] body = new byte[]{31, -117, 8, 0, 1, 2, 3, 4};
        Map<String, String> values = new LinkedHashMap<>();
        values.put("device_id", "123"); values.put("iid", "456"); values.put("cdid", "synthetic");
        values.put("version_name", "7.4.3.32"); values.put("channel", "vivo_8662_64");
        values.put("os_version", "12"); values.put("device_type", "PGT-AN10"); values.put("_rticket", "1800000000000");
        for (long ts = 1800000000L; ts <= 1800000031L; ts++) {
            PlaybackSignerV5.Request request = PlaybackSignerV5.signPlayback("https://example.test/model", values, body, ts * 1000L);
            String query = request.url.substring(request.url.indexOf('?') + 1);
            check(!query.contains("ts="), "v5 query carries no ts parameter ts=" + ts);
            check(PlaybackSignerV5.branchClass(query, body, ts) == 12, "ticket search lands on class 12 ts=" + ts);
            String ticket = "_rticket=" + SELECTED_TICKETS[(int) (ts - 1800000000L)];
            check(query.endsWith(ticket), "ticket matches independent Kotlin golden ts=" + ts);
            check(request.headers.get("x-khronos").equals(Long.toString(ts)), "x-khronos ts=" + ts);
            check("8311EA30C1C81ED466F1C2D5BB128C5F".equals(request.headers.get("x-ss-stub")), "x-ss-stub binds gzip bytes ts=" + ts);
            check(Arrays.equals(request.body, body), "body passed through ts=" + ts);
            String medusa = request.headers.get("x-medusa");
            check(medusa != null && !medusa.isEmpty(), "x-medusa present ts=" + ts);
            Map<String, String> other = new LinkedHashMap<>(values); other.put("device_id", "789");
            check(!medusa.equals(PlaybackSignerV5.medusa(request.url, body, ts, other)), "x-medusa binds identity ts=" + ts);
        }
        System.out.println("PlaybackSignerV5Test PASS checks=" + checks + " vectors=f13-full20:32+carrier:4+kotlin-tickets:32 rotations=32");
    }
}
