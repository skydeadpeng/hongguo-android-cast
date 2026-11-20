package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;

/** Private per-source state: authenticated ciphertext only reaches disk. */
final class SourceRuntimeState {
    private static final String KEY_ALIAS="hongguo-source-state-aes-v1";
    private final SharedPreferences preferences;
    private final String source;
    SourceRuntimeState(Context context, String source) {
        preferences=context.getSharedPreferences("android-source-state-v1",Context.MODE_PRIVATE);
        this.source=source;
    }
    private String location(String key) throws Exception {
        return SourceRuntimeSupport.hex(SourceRuntimeSupport.digest("SHA-256",(source+"\0"+key).getBytes(StandardCharsets.UTF_8)));
    }
    private static synchronized Key key(boolean create) throws Exception {
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        Key key=keys.getKey(KEY_ALIAS,null);
        if (key==null && create) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            key=generator.generateKey();
        }
        return key;
    }
    String load(String name) throws Exception {
        String id=location(name);
        String text=preferences.getString(id,"");
        if (text.isEmpty()) return "";
        Key key=key(false);
        if (key==null) return "";
        try {
            byte[] sealed=SourceRuntimeSupport.unbase64(text);
            if (sealed.length<28 || sealed.length>1024*1024+28) return "";
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,sealed,0,12));
            cipher.updateAAD(id.getBytes(StandardCharsets.US_ASCII));
            return new String(cipher.doFinal(sealed,12,sealed.length-12),StandardCharsets.UTF_8);
        } catch (Exception corrupt) { return ""; }
    }
    void save(String name,String value) throws Exception {
        String id=location(name);
        byte[] plain=value.getBytes(StandardCharsets.UTF_8);
        if (plain.length>1024*1024) throw new IOException("来源状态过大");
        if (plain.length==0) {
            if (!preferences.edit().remove(id).commit()) throw new IOException("来源状态未能删除");
            return;
        }
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key(true));
        cipher.updateAAD(id.getBytes(StandardCharsets.US_ASCII));
        byte[] iv=cipher.getIV(), encrypted=cipher.doFinal(plain);
        if (iv.length!=12) throw new IOException("来源状态加密格式无效");
        byte[] sealed=Arrays.copyOf(iv,iv.length+encrypted.length);
        System.arraycopy(encrypted,0,sealed,iv.length,encrypted.length);
        if (!preferences.edit().putString(id,SourceRuntimeSupport.base64(sealed)).commit())
            throw new IOException("来源状态未能保存");
    }
}
