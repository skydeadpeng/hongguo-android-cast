package io.github.jackotom.hongguo.android;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Official sessions use a new key, never the retired self-hosted token alias. */
final class OfficialAccountVault implements OfficialSessionStore.Vault {
    private static final String ALIAS="hongguo_official_session_v1";
    private static final byte[] AAD="hongguo:official-account:session:v1".getBytes(StandardCharsets.UTF_8);
    private final String alias;
    private final byte[] aad;
    OfficialAccountVault(){this(ALIAS,AAD);}
    /** Each credential kind owns its key alias and associated data, so slots never share a secret. */
    OfficialAccountVault(String alias, byte[] aad){if(alias==null||alias.isEmpty()||aad==null||aad.length==0)throw new IllegalArgumentException("凭据保险箱参数无效");this.alias=alias;this.aad=aad;}
    private SecretKey key()throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(alias)){
            // A key invalidated by a screen-lock or biometric change is permanently unrecoverable; without this
            // branch every later login would fail until the user wiped the app data.
            try{SecretKey existing=(SecretKey)store.getKey(alias,null);if(existing!=null)return existing;AccountLog.warn("official_account_keystore_key_missing");}
            catch(UnrecoverableKeyException broken){AccountLog.warn("official_account_keystore_key_unrecoverable");}
            try{store.deleteEntry(alias);}catch(Exception ignored){}
        }
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();
    }
    public String seal(String value)throws Exception {Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(aad);return Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);}
    public String open(String value)throws Exception {String[] parts=value.split(":",-1);if(parts.length!=2)throw new IllegalArgumentException("官方账号凭据格式无效");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));cipher.updateAAD(aad);return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);}
}
