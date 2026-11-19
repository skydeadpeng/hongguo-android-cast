package io.github.jackotom.hongguo.android;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Full final-URL + signature-header digests from the independently executed upstream Kotlin signer. */
public final class TabPlaybackSignerTest {
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static String digest(PlaybackSignerV5.Request request, JSONArray names) throws Exception {
        StringBuilder canonical = new StringBuilder(request.url).append('\n');
        for (int i = 0; i < names.length(); i++) {
            String name = names.getString(i); canonical.append(name).append(':').append(request.headers.get(name)).append('\n');
        }
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)))
            result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    public static void main(String[] args) throws Exception {
        JSONObject fixture = new JSONObject(Files.readString(Paths.get(args[0])));
        JSONArray vectors = fixture.getJSONArray("vectors");
        for (int i = 0; i < vectors.length(); i++) {
            JSONObject v = vectors.getJSONObject(i); int[] randoms = new int[7];
            for (int j = 0; j < 7; j++) randoms[j] = (int) v.getJSONArray("randoms").getLong(j);
            PlaybackSignerV5.Request request = TabPlaybackSigner.sign(v.getString("did"), v.getString("iid"), v.getString("cdid"),
                v.getString("vid"), "", v.getLong("now"), v.getString("nonce"), v.getLong("started"), randoms);
            check(v.getString("url").equals(request.url), "upstream final query/ticket vector " + i);
            check(v.getString("sha256").equals(digest(request, fixture.getJSONArray("canonicalHeaders"))), "full upstream signature digest vector " + i);
            check(AccountSigner.branchOf(request.url, null, (int) (v.getLong("now") / 1000)) == 0, "legacy Medusa branch zero");
            check(!request.url.contains("&ts=") && !request.url.contains("?ts="), "no legacy ts");
            check(request.body.length == 0 && !request.headers.containsKey("Content-Encoding"), "GET has no body or gzip");
            check(!request.headers.containsKey("passport-sdk-version") && !request.headers.containsKey("x-tt-request-tag")
                && !request.headers.containsKey("x-ss-stub"), "no unrelated account protocol headers");
        }
        check("install_id=123; ttreq=abc==; store-region=cn; store-region-src=did".equals(TabPlaybackSigner.registrationCookies(
            "sessionid=account; install_id=123; ttreq=abc==; store-region=cn; store-region-src=did; sid_guard=account")), "registration-only cookie whitelist");
        check(TabPlaybackSigner.registrationCookies("install_id=123\r\nCookie: account").isEmpty(), "reject cookie control characters");
        check(TabPlaybackSigner.registrationCookies(null).isEmpty(), "no implicit cookie jar");
        PlaybackSignerV5.Request cookie = TabPlaybackSigner.sign("123", "456", "synthetic-cdid", "789", "sessionid=account; install_id=456", 1800000000000L);
        check("install_id=456".equals(cookie.headers.get("Cookie")), "only registration cookie attached");
        System.out.println("TAB_PLAYBACK_SIGNER_OK checks=" + checks + " independentKotlinVectors=" + vectors.length() + " fullSignatureDigestMismatches=0 networkRequests=0");
    }
}
