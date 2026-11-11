package io.github.jackotom.hongguo.android;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Byte-for-byte golden vectors for the 72232 account signer, generated from the upstream implementation. */
public final class AccountSignerTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void eq(Object left, Object right, String message) { checks++; if (!left.equals(right)) throw new AssertionError(message + "\n  expected: " + left + "\n  actual:   " + right); }

    public static void main(String[] args) throws Exception {
        JSONObject golden = new JSONObject(Files.readString(Paths.get(args[0])));
        long now = golden.getLong("ticketBase");
        String nonce = golden.getString("nonce");
        long processStarted = golden.getLong("processStarted");
        for (int at = 0; at < golden.getJSONArray("vectors").length(); at++) {
            JSONObject vector = golden.getJSONArray("vectors").getJSONObject(at);
            JSONArray randomsJson = vector.getJSONArray("randoms");
            int[] randoms = new int[randomsJson.length()];
            for (int r = 0; r < randoms.length; r++) randoms[r] = (int) randomsJson.getLong(r);
            JSONObject identity = vector.getJSONObject("identity");
            // org.json keys() order is undefined; the golden carries the exact insertion order.
            Map<String, String> values = new LinkedHashMap<>();
            org.json.JSONArray ordered = vector.optJSONArray("orderedValues");
            if (ordered != null) for (int pair = 0; pair < ordered.length(); pair++) values.put(ordered.getJSONArray(pair).getString(0), ordered.getJSONArray(pair).getString(1));
            else for (java.util.Iterator<String> keys = vector.getJSONObject("values").keys(); keys.hasNext();) { String key = keys.next(); values.put(key, vector.getJSONObject("values").getString(key)); }
            AccountSigner.Signed signed = AccountSigner.request(vector.getString("apiPath"), values,
                identity.getString("device_id"), identity.getString("iid"), identity.getString("cdid"),
                now, nonce, processStarted, randoms);
            eq(signed.url, vector.getString("url"), "url for " + vector.getString("apiPath"));
            JSONObject headers = vector.getJSONObject("headers");
            for (String name : new String[]{"x-gorgon", "x-ladon", "x-argus", "x-helios", "x-medusa", "x-khronos", "x-ss-req-ticket"}) {
                eq(signed.headers.get(name), headers.getString(name), name + " for " + vector.getString("apiPath"));
            }
            System.out.println("PASS vector " + vector.getString("apiPath"));
        }
        System.out.println("ACCOUNT_SIGNER_OK checks=" + checks + " vectors=" + golden.getJSONArray("vectors").length());
    }
}
