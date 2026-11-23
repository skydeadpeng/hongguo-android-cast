package io.github.jackotom.hongguo.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Replays the SDK playback route's golden vectors: upstream's Python output against this Java port.
 *  The fixture is loaded as the classpath resource sdk-playback-vectors.json (android/tests is on the classpath). */
public final class SdkPlaybackVectorsTest {
    private static int passed, failed;
    private static String fixture;
    private static SdkMaterials materials;
    private static byte[] signKey, commonKey, frame, substitutions, inner;
    /** The reference's stand-in for the native core: SHA-256 over the call's three inputs. */
    private static final SdkSigner.Core FAKE_CORE = (payload, selector, seconds) -> Arrays.copyOf(hash("SHA-256",
        SdkWire.join(payload, new byte[]{(byte) selector}, SdkWire.le32(seconds))), 16);

    public static void main(String[] args) throws Exception {
        try (InputStream resource = SdkPlaybackVectorsTest.class.getResourceAsStream("/sdk-playback-vectors.json")) {
            if (resource == null) throw new IllegalStateException("sdk-playback-vectors.json is not on the classpath");
            fixture = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        JSONObject root = new JSONObject(fixture);
        JSONArray vectors = root.getJSONArray("vectors");
        int pid = root.getInt("pid");
        Map<String, String> params = text(root.getJSONObject("params")), cookies = text(root.getJSONObject("cookies"));
        buildMaterials(vectors);
        for (int index = 0; index < vectors.length(); index++) {
            JSONObject vector = vectors.getJSONObject(index);
            try { replay(vector.getString("name"), vector.getJSONObject("inputs"), vector.get("output"), vector.getJSONArray("draws"), params, cookies, pid); }
            catch (Throwable failure) { failed++; System.out.println("FAIL " + vector.getString("name") + ": " + failure); }
        }
        System.out.println("SDK vectors: " + passed + " passed, " + failed + " failed");
        // The native adapter's own statuses: a disabled adapter (5), a fault (8) or an overran call (9)
        // retire the route for the process; every other failure is only that request's.
        int retired = 0;
        for (int status = 1; status <= 9; status++) {
            boolean broken = broken(status);
            if (broken != (status == 5 || status == 8 || status == 9)) { failed++; System.out.println("FAIL native status " + status + (broken ? " must not" : " must") + " retire the route"); }
            else if (broken) retired++;
        }
        System.out.println("SDK native statuses: " + retired + " retire the route, " + (failed == 0 ? "6 do not" : "mismatch"));
        if (failed > 0) System.exit(1);
    }

    private static boolean broken(int status) throws Exception {
        SdkSigner.Core core = SdkDigestBridge.core(new SdkDigestBridge.Native() {
            @Override public int digest(byte[] payload, int selector, int seconds, byte[] out) { return status; }
            @Override public String detail() { return "status " + status; }
        });
        try { core.digest(new byte[52], 0, 1); return false; }
        catch (SdkDigestBridge.Broken broken) { return true; }
        catch (java.io.IOException onlyThisRequest) { return false; }
    }

    private static void buildMaterials(JSONArray vectors) throws Exception {
        JSONObject config = find(vectors, "config_decode"), parts = find(vectors, "image_parts").getJSONObject("output");
        signKey = bytes(config.getJSONObject("output").getJSONObject("signing_key")); commonKey = bytes(config.getJSONObject("output").getJSONObject("exchange_key"));
        frame = bytes(parts.getJSONObject("frame")); substitutions = bytes(parts.getJSONObject("substitutions")); inner = bytes(parts.getJSONObject("inner"));
        String unavailable = parts.getString("unavailable");
        byte[] image = new byte[SdkMaterials.IMAGE_SIZE];
        for (int index = 0; index < image.length; index++) image[index] = (byte) ((index * 31 + 7) & 255);
        System.arraycopy(frame, 0, image, 0x665f0, 16); System.arraycopy(substitutions, 0, image, 0xffcf9, 1024);
        for (int index = 0; index < 8; index++) {
            image[0x66690 + index] = inner[index]; image[0x9d08c + index] = (byte) (inner[8 + index] ^ 0x5a); image[0x9d118 + index] = 0x5a;
        }
        System.arraycopy(unavailable.getBytes(StandardCharsets.US_ASCII), 0, image, 0x66c44, 8);
        materials = SdkMaterials.decode(image, config.getJSONObject("inputs").getString("encoded").getBytes(StandardCharsets.US_ASCII));
        equal("config_decode.signing_key", hex(materials.signingKey), hex(signKey)); equal("config_decode.exchange_key", hex(materials.exchangeKey), hex(commonKey));
        equal("image_parts.frame", hex(materials.frame), hex(frame)); equal("image_parts.substitutions", hex(materials.substitutions), hex(substitutions));
        equal("image_parts.inner", hex(materials.exchangeInnerKey), hex(inner)); equal("image_parts.unavailable", materials.unavailable, unavailable);
    }

    private static void replay(String name, JSONObject in, Object out, JSONArray draws, Map<String, String> params, Map<String, String> cookies, int pid) throws Exception {
        long milliseconds = in.optLong("milliseconds", 0);
        int seconds = (int) in.optLong("seconds", milliseconds / 1000);
        if (name.equals("config_decode") || name.equals("image_parts")) return;
        if (name.startsWith("gorgon")) {
            equal(name, SdkSigner.gorgon(bytes(in.getJSONObject("query")), bytes(in.getJSONObject("body")), seconds, in.getInt("nonce"), bytes(in.getJSONObject("flags"))), out);
        } else if (name.startsWith("device_payload")) {
            equal(name, hex(SdkRequest.devicePayload(params, materials.unavailable, in.isNull("token") ? null : in.getString("token"))), hex(out));
        } else switch (name) {
            case "device_proof": equal(name, hex(SdkRequest.plainDeviceProof(in.getString("device_id"))), hex(out)); break;
            case "bound_device_proof": equal(name, hex(SdkRequest.deviceProof(params)), hex(out)); break;
            case "xtea": {
                JSONObject expected = (JSONObject) out;
                byte[] data = bytes(in.getJSONObject("data")), key = bytes(in.getJSONObject("key")), nonce = bytes(in.getJSONObject("nonce"));
                byte[] sealed = SdkWire.xtea(data, key, nonce, false);
                equal(name + ".sealed", hex(sealed), hex(expected.getJSONObject("sealed")));
                equal(name + ".opened", hex(SdkWire.xtea(bytes(expected.getJSONObject("sealed")), key, nonce, true)), hex(expected.getJSONObject("opened")));
                equal(name + ".roundtrip", hex(SdkWire.xtea(sealed, key, nonce, true)), hex(data)); break;
            }
            case "exchange_seal": {
                JSONObject expected = (JSONObject) out;
                byte[] sealed = SdkSigner.seal(bytes(in.getJSONObject("plaintext")), commonKey, inner, bytes(in.getJSONObject("nonce")), in.getInt("entropy"), 0);
                equal(name + ".sealed", hex(sealed), hex(expected.getJSONObject("sealed")));
                equal(name + ".opened", hex(SdkSigner.open(sealed, commonKey, inner, true)), hex(expected.getJSONObject("opened"))); break;
            }
            case "exchange_request": equal(name, hex(SdkSigner.exchangeRequest(bytes(in.getJSONObject("plaintext")), milliseconds, commonKey, inner,
                bytes(in.getJSONObject("nonce")), in.getInt("entropy"), 0)), hex(out)); break;
            case "exchange_answer": {
                SdkSigner.Session session = SdkSigner.answer(bytes(in.getJSONObject("packet")), commonKey, inner);
                equal(name + ".token", session.token, ((JSONObject) out).getString("token"));
                equal(name + ".status", session.status, ((JSONObject) out).getInt("status")); break;
            }
            case "selector_checksum": {
                byte[] query = bytes(in.getJSONObject("query_digest")), body = bytes(in.getJSONObject("body_digest"));
                equal(name + ".selector", Integer.toUnsignedLong(SdkSigner.selector(query, body, seconds)), ((JSONObject) out).getLong("selector"));
                equal(name + ".checksum", Integer.toUnsignedLong(SdkSigner.checksum(query)), Integer.toUnsignedLong(((JSONObject) out).getInt("checksum"))); break;
            }
            case "digest_framing": equal(name, hex(SdkSigner.proof(bytes(in.getJSONObject("query")), bytes(in.getJSONObject("body")), seconds, FAKE_CORE)), hex(((JSONObject) out).getJSONObject("proof"))); break;
            case "digest_adapter": {
                // The native adapter answers 20 bytes; only the first 16 reach the signature.
                SdkSigner.Core adapter = SdkDigestBridge.core(new SdkDigestBridge.Native() {
                    @Override public int digest(byte[] payload, int selector, int seconds, byte[] out) {
                        byte[] full;
                        try { full = Arrays.copyOf(hash("SHA-256", SdkWire.join(payload, new byte[]{(byte) selector}, SdkWire.le32(seconds))), 20); }
                        catch (Exception impossible) { throw new IllegalStateException(impossible); }
                        System.arraycopy(full, 0, out, 0, 20);
                        return 0;
                    }
                    @Override public String detail() { return ""; }
                });
                equal(name, hex(SdkSigner.proof(bytes(in.getJSONObject("query")), bytes(in.getJSONObject("body")), seconds, adapter)), hex(out));
                SdkSigner.Core failing = SdkDigestBridge.core(new SdkDigestBridge.Native() {
                    @Override public int digest(byte[] payload, int selector, int seconds, byte[] out) { return 7; }
                    @Override public String detail() { return "import fork"; }
                });
                try { SdkSigner.proof(bytes(in.getJSONObject("query")), bytes(in.getJSONObject("body")), seconds, failing); equal(name + ".failure", "no exception", "IOException"); }
                catch (java.io.IOException expected) { equal(name + ".failure", expected.getMessage(), "SDK 摘要失败 7: import fork"); }
                break;
            }
            case "advance_ladon": equal(name, SdkSigner.advanceLadon(in.getLong("previous"), Long.parseUnsignedLong(in.getString("allocation"), 16), seconds), ((Number) out).longValue()); break;
            case "envelope": equal(name, SdkSigner.envelope(bytes(in.getJSONObject("proto")), bytes(in.getJSONObject("query_digest")), bytes(in.getJSONObject("body_proof")),
                seconds, (int) in.getLong("key_nonce"), (int) in.getLong("frame_nonce"), signKey, frame, le64(0x0504), substitutions), out); break;
            case "bootstrap": signed(name, (JSONObject) out, SdkRequest.bootstrap(params, cookies, milliseconds, materials, FAKE_CORE, null, 0, randoms(draws, true), pid, 0),
                params, milliseconds, null, randoms(draws, true), pid, true); break;
            case "bootstrap_token": signed(name, (JSONObject) out, SdkRequest.bootstrap(params, cookies, milliseconds, materials, FAKE_CORE, in.getString("token"),
                in.getLong("previous_ladon"), randoms(draws, true), pid, 0), params, milliseconds, in.getString("token"), randoms(draws, true), pid, true); break;
            case "playback": {
                JSONObject expected = (JSONObject) out;
                signed(name, expected, SdkRequest.playbackWithBody(params, cookies, bytes(expected.getJSONObject("body")), milliseconds, materials, FAKE_CORE,
                    in.getString("token"), in.getLong("previous_ladon"), randoms(draws, false), pid), params, milliseconds, in.getString("token"), randoms(draws, false), pid, false);
                String json = SdkRequest.playbackJson(in.getString("video"));
                equal(name + ".body_json", json, new String(gunzip(bytes(expected.getJSONObject("body"))), StandardCharsets.UTF_8));
                equal(name + ".gzip_roundtrip", json, new String(gunzip(SdkRequest.gzip(json.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8)); break;
            }
            case "registration": {
                JSONObject expected = (JSONObject) out;
                String cdid = expected.getString("cdid"), openudid = expected.getString("openudid"), uuid = in.getString("uuid");
                Map<String, String> mine = SdkRequest.registrationParams(milliseconds, cdid, openudid);
                equal(name + ".params", new TreeMap<>(mine).toString(), new TreeMap<>(text(expected.getJSONObject("params"))).toString());
                equal(name + ".url", SdkRequest.registrationUrl(mine), expected.getString("url"));
                String plain = SdkRequest.registrationPayload(milliseconds, cdid, openudid, uuid, uuid);
                equal(name + ".payload", plain, expected.getString("plain"));
                byte[] body = PassportDevice.encrypt(plain.getBytes(StandardCharsets.UTF_8), bytes(expected.getJSONObject("seed")));
                equal(name + ".roundtrip", new String(PassportDevice.decrypt(body), StandardCharsets.UTF_8), plain);
                Map<String, String> headers = SdkRequest.registrationHeaders(milliseconds, body), wanted = text(expected.getJSONObject("headers"));
                // The two zlib writers differ in the gzip header flags, so the body digest is checked for self-consistency only.
                equal(name + ".stub", headers.get("X-SS-STUB"), hex(hash("MD5", body)).toUpperCase(Locale.ROOT));
                headers.remove("X-SS-STUB"); wanted.remove("X-SS-STUB");
                equal(name + ".headers", new TreeMap<>(headers).toString(), new TreeMap<>(wanted).toString());
                equal(name + ".header_order", new ArrayList<>(headers.keySet()).toString(), without(wireOrder(name), "X-SS-STUB").toString()); break;
            }
            default: throw new AssertionError("unknown vector " + name);
        }
        passed++;
    }

    private static void signed(String name, JSONObject expected, SdkRequest.Signed signed, Map<String, String> params, long milliseconds, String token,
                               SdkRequest.Randoms randoms, int pid, boolean exchange) throws Exception {
        equal(name + ".url", signed.url, expected.getString("url")); equal(name + ".body", hex(signed.body), hex(expected.getJSONObject("body")));
        equal(name + ".next_ladon", signed.nextLadon, expected.getLong("next_ladon"));
        equal(name + ".headers", new TreeMap<>(signed.headers).toString(), new TreeMap<>(text(expected.getJSONObject("headers"))).toString());
        equal(name + ".header_order", new ArrayList<>(signed.headers.keySet()).toString(), wireOrder(name).toString());
        byte[] query = signed.url.substring(signed.url.indexOf('?') + 1).getBytes(StandardCharsets.US_ASCII);
        byte[] mine = SdkRequest.message(SdkRequest.profile(params), query, signed.body, milliseconds, materials, FAKE_CORE, token, randoms, pid, exchange).proto;
        byte[] wanted = bytes(expected.getJSONObject("message"));
        if (!hex(mine).equals(hex(wanted))) {
            int at = 0;
            while (at < mine.length && at < wanted.length && mine[at] == wanted[at]) at++;
            System.out.println("   sealed message differs at byte " + at + " of " + mine.length + "/" + wanted.length
                + "\n   expected " + hex(Arrays.copyOfRange(wanted, Math.max(0, at - 12), Math.min(wanted.length, at + 20)))
                + "\n   actual   " + hex(Arrays.copyOfRange(mine, Math.max(0, at - 12), Math.min(mine.length, at + 20))));
        }
        equal(name + ".message", hex(mine), hex(wanted));
    }

    /** Key order of the reference's header object. JSON objects drop it, so it is read back from the fixture text. */
    private static List<String> wireOrder(String name) {
        String[] lines = fixture.substring(fixture.indexOf("\"headers\": {", fixture.indexOf("\"name\": \"" + name + "\""))).split("\n");
        List<String> out = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            Matcher key = Pattern.compile("^\\s*\"([^\"]+)\":").matcher(lines[index]);
            if (!key.find()) break;
            out.add(key.group(1));
        }
        return out;
    }

    private static List<String> without(List<String> values, String drop) {
        List<String> out = new ArrayList<>(values); out.remove(drop); return out;
    }

    /** Replays the recorded random draws in the reference's order. */
    private static SdkRequest.Randoms randoms(JSONArray draws, boolean exchange) throws Exception {
        SdkRequest.Randoms randoms = new SdkRequest.Randoms();
        List<String> values = new ArrayList<>();
        for (int index = 0; index < draws.length(); index++) values.add(draws.getJSONObject(index).getString("value"));
        int at = 0;
        if (exchange) { System.arraycopy(unhex(values.get(at++)), 0, randoms.exchangeNonce, 0, 4); randoms.exchangeEntropy = (int) number(values.get(at++)); }
        randoms.nonce0 = (int) number(values.get(at++)); randoms.nonce1 = (int) number(values.get(at++)); randoms.nonce2 = (int) number(values.get(at++));
        randoms.ladonAllocation = number(values.get(at++)); randoms.gorgonNonce = (int) number(values.get(at++));
        System.arraycopy(unhex(values.get(at++)), 0, randoms.helios, 0, 4);
        System.arraycopy(unhex(values.get(at++)), 0, randoms.traceA, 0, 16);
        System.arraycopy(unhex(values.get(at++)), 0, randoms.traceB, 0, 8);
        randoms.readingRequest = at < values.size() ? (int) number(values.get(at)) : 0;
        return randoms;
    }

    private static long number(String hex) { return Long.parseUnsignedLong(hex, 16); }
    private static byte[] le64(long value) { byte[] out = new byte[8]; DanmakuSigner.putLe(out, 0, value); return out; }
    private static byte[] bytes(JSONObject wrapped) throws Exception { return DanmakuSigner.unhex(wrapped.getString("hex")); }
    private static byte[] unhex(String value) { return DanmakuSigner.unhex(value); }
    private static String hex(byte[] value) { return DanmakuSigner.hex(value); }
    private static String hex(Object wrapped) throws Exception { return ((JSONObject) wrapped).getString("hex"); }
    private static byte[] hash(String algorithm, byte[] value) throws Exception { return MessageDigest.getInstance(algorithm).digest(value); }
    private static byte[] gunzip(byte[] value) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(value)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            for (int count; (count = gzip.read(buffer)) > 0; ) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
    private static Map<String, String> text(JSONObject object) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (java.util.Iterator<String> keys = object.keys(); keys.hasNext(); ) { String key = keys.next(); out.put(key, String.valueOf(object.get(key))); }
        return out;
    }
    private static JSONObject find(JSONArray vectors, String name) throws Exception {
        for (int index = 0; index < vectors.length(); index++) if (vectors.getJSONObject(index).getString("name").equals(name)) return vectors.getJSONObject(index);
        throw new AssertionError("missing vector " + name);
    }
    private static void equal(String name, Object actual, Object expected) {
        if (String.valueOf(actual).equals(String.valueOf(expected))) passed++;
        else { failed++; System.out.println("FAIL " + name + "\n   expected " + expected + "\n   actual   " + actual); }
    }
}
