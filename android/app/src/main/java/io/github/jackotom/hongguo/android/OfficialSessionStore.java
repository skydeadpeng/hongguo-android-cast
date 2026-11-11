package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import org.json.JSONObject;

/** Synchronous ciphertext commit: login cannot succeed before its actual session reaches disk. */
final class OfficialSessionStore implements OfficialAccountFlow.Store {
    interface Vault { String seal(String value)throws Exception; String open(String value)throws Exception; }
    static final String NAME="hongguo_official_session_v1", KEY="encrypted_session";
    private final SharedPreferences prefs;
    private final Vault vault;
    OfficialSessionStore(Context context){this(context.getSharedPreferences(NAME,Context.MODE_PRIVATE),new OfficialAccountVault());}
    OfficialSessionStore(SharedPreferences prefs,Vault vault){this.prefs=prefs;this.vault=vault;}
    public synchronized JSONObject load()throws Exception {
        String encoded=prefs.getString(KEY,"");if(encoded.isEmpty())return null;if(encoded.length()>768*1024)throw new IOException("官方账号凭据过长");return new JSONObject(vault.open(encoded));
    }
    public synchronized void save(JSONObject value)throws Exception {
        String text=value.toString();if(text.length()>512*1024)throw new IOException("官方账号会话过长");String encoded=vault.seal(text);String previous=prefs.getString(KEY,null);boolean saved=false;
        try {saved=prefs.edit().putString(KEY,encoded).commit();}
        finally {if(!saved){SharedPreferences.Editor restore=prefs.edit();if(previous==null)restore.remove(KEY);else restore.putString(KEY,previous);restore.commit();}}
        if(!saved)throw new IOException("官方账号信息保存未完成，请检查存储空间后重新登录");
    }
}
