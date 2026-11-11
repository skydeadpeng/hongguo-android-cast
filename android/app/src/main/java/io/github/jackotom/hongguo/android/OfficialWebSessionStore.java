package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** The website session owns its own encrypted slot: it never shares a key or a file with the mobile passport session. */
final class OfficialWebSessionStore implements OfficialWebAccount.Store {
    private static final String NAME="hongguo_official_web_v1",KEY="encrypted_session";
    private static final String ALIAS="hongguo_official_web_v1";
    private static final byte[] AAD="hongguo:official-web:session:v1".getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences prefs;
    private final OfficialAccountVault vault;
    OfficialWebSessionStore(Context context){this(context.getSharedPreferences(NAME,Context.MODE_PRIVATE),new OfficialAccountVault(ALIAS,AAD));}
    OfficialWebSessionStore(SharedPreferences prefs,OfficialAccountVault vault){this.prefs=prefs;this.vault=vault;}
    public synchronized JSONObject load()throws Exception {
        String encoded=prefs.getString(KEY,"");if(encoded.isEmpty())return null;
        if(encoded.length()>768*1024)throw new IOException("官网账号凭据过长");
        JSONObject value=new JSONObject(vault.open(encoded));
        if(value.optInt("version",0)!=1)throw new IOException("官网账号凭据格式无效");
        return value;
    }
    public synchronized void save(JSONObject value)throws Exception {
        String text=value.toString();if(text.length()>512*1024)throw new IOException("官网账号会话过长");
        String encoded=vault.seal(text);String previous=prefs.getString(KEY,null);boolean saved=false;
        try {saved=prefs.edit().putString(KEY,encoded).commit();}
        finally {if(!saved){SharedPreferences.Editor restore=prefs.edit();if(previous==null)restore.remove(KEY);else restore.putString(KEY,previous);restore.commit();}}
        if(!saved)throw new IOException("官网账号信息保存未完成，请检查存储空间后重试");
    }
    public synchronized void clear(){prefs.edit().remove(KEY).commit();}
}
