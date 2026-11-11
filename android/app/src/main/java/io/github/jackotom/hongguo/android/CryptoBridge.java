package io.github.jackotom.hongguo.android;

import android.webkit.JavascriptInterface;
import java.util.Base64;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Bounded, stateless byte primitives for the unchanged desktop protocol module. No I/O or stored keys. */
public final class CryptoBridge {
    @JavascriptInterface public String process(String message) {
        try {
            if (message == null || message.length() > 256 * 1024) throw new Exception();
            JSONObject input = new JSONObject(message);
            byte[] data = Base64.getDecoder().decode(input.getString("data"));
            byte[] result;
            if ("digest".equals(input.optString("operation"))) {
                String algorithm = input.getString("algorithm");
                if (!algorithm.equals("MD5") && !algorithm.equals("SHA-512")) throw new Exception();
                result = MessageDigest.getInstance(algorithm).digest(data);
            } else if ("decrypt".equals(input.optString("operation")) && "AES-128-CBC".equals(input.optString("algorithm"))) {
                byte[] key = Base64.getDecoder().decode(input.getString("key")), iv = Base64.getDecoder().decode(input.getString("iv"));
                if (key.length != 16 || iv.length != 16) throw new Exception();
                Cipher cipher = Cipher.getInstance(input.optBoolean("autoPadding", true) ? "AES/CBC/PKCS5Padding" : "AES/CBC/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                result = cipher.doFinal(data);
            } else throw new Exception();
            return new JSONObject().put("data", Base64.getEncoder().encodeToString(result)).put("error", JSONObject.NULL).toString();
        } catch (Exception error) {
            return "{\"data\":null,\"error\":\"片源字节处理失败\"}";
        }
    }
}
