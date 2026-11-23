package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Keys and constants one pinned SDK build carries: the per-app configuration and four image regions. */
final class SdkMaterials {
    static final int IMAGE_SIZE = 4501136, CONFIG_SIZE = 304;
    final byte[] signingKey = new byte[32], exchangeKey = new byte[32];
    final byte[] frame = new byte[16], substitutions = new byte[1024], exchangeInnerKey = new byte[16];
    final String unavailable;

    private SdkMaterials(String unavailable) { this.unavailable = unavailable; }

    /** Neither input is kept beyond this call; the caller still owns verifying the image hash first. */
    static SdkMaterials decode(byte[] image, byte[] config) throws Exception {
        if (image == null || image.length != IMAGE_SIZE || config == null || config.length != CONFIG_SIZE)
            throw new IOException("SDK 素材尺寸不符");
        byte[] raw;
        try { raw = Base64.getDecoder().decode(new String(config, StandardCharsets.US_ASCII)); }
        catch (IllegalArgumentException invalid) { throw new IOException("SDK 配置编码无效"); }
        if (raw.length != 228) throw new IOException("SDK 配置帧无效");
        byte[] application = "8662".getBytes(StandardCharsets.US_ASCII), nonce = SdkWire.copy(raw, 0, 4);
        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(
            MessageDigest.getInstance("MD5").digest(SdkWire.join(application, nonce, application)), "AES"));
        byte[] plain = aes.doFinal(raw, 4, raw.length - 4);
        int padding = plain[plain.length - 1] & 255;
        if (padding < 1 || padding > 16) throw new IOException("SDK 配置填充无效");
        for (int index = plain.length - padding; index < plain.length; index++)
            if ((plain[index] & 255) != padding) throw new IOException("SDK 配置填充无效");
        int length = plain.length - padding;
        if (length < 9) throw new IOException("SDK 配置内容过短");
        byte[] head = SdkWire.copy(plain, 0, 4), tail = SdkWire.copy(plain, length - 4, length);
        int mask = 0;
        for (byte value : SdkWire.join(nonce, head, tail)) mask ^= value & 255;
        byte[] payload = SdkWire.copy(plain, 4, length - 4);
        SdkWire.rc4(MessageDigest.getInstance("SHA-1").digest(SdkWire.join(head, application, nonce)), payload);
        byte[] message = new byte[payload.length];
        for (int index = 0; index < message.length; index++) message[index] = (byte) ((payload[payload.length - 1 - index] & 255) ^ mask);
        SdkMaterials materials = new SdkMaterials(new String(SdkWire.copy(image, 0x66c44, 0x66c4c), StandardCharsets.US_ASCII));
        boolean signSeen = false, commonSeen = false;
        try {
            for (SdkWire.Row row : SdkWire.read(message, 8192)) {
                if (row.field != 8 || row.encoding != 2) continue;
                String name = null; byte[] encoded = null;
                for (SdkWire.Row part : SdkWire.read(row.bytes, 8192)) {
                    if ((part.field != 1 && part.field != 2) || part.encoding != 2) throw new IOException("SDK 密钥条目无效");
                    if (part.field == 1 ? name != null : encoded != null) throw new IOException("SDK 密钥条目重复");
                    if (part.field == 1) name = new String(part.bytes, StandardCharsets.ISO_8859_1); else encoded = part.bytes;
                }
                if (name == null || encoded == null || !"sign_key".equals(name) && !"common_key".equals(name))
                    throw new IOException("SDK 密钥条目不完整");
                byte[] key = Base64.getDecoder().decode(encoded);
                if (key.length != 32) throw new IOException("SDK 密钥长度无效");
                if ("sign_key".equals(name)) {
                    if (signSeen) throw new IOException("SDK 密钥条目重复");
                    signSeen = true; System.arraycopy(key, 0, materials.signingKey, 0, 32);
                } else {
                    if (commonSeen) throw new IOException("SDK 密钥条目重复");
                    commonSeen = true; System.arraycopy(key, 0, materials.exchangeKey, 0, 32);
                }
            }
            if (!signSeen || !commonSeen) throw new IOException("SDK 密钥缺失");
        } catch (IllegalArgumentException invalid) { throw new IOException("SDK 密钥编码无效"); }
        System.arraycopy(image, 0x665f0, materials.frame, 0, 16);
        System.arraycopy(image, 0xffcf9, materials.substitutions, 0, 1024);
        for (int index = 0; index < 8; index++) {
            materials.exchangeInnerKey[index] = image[0x66690 + index];
            materials.exchangeInnerKey[8 + index] = (byte) (image[0x9d08c + index] ^ image[0x9d118 + index]);
        }
        return materials;
    }
}
