package io.github.jackotom.hongguo.android;

import android.content.SharedPreferences;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

public final class OfficialSessionStoreTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static final class Prefs implements InvocationHandler {
        Map<String,String>memory=new HashMap<>(),disk=new HashMap<>();int fail,commits;
        SharedPreferences prefs(){return(SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.class},this);}
        public Object invoke(Object proxy,Method method,Object[]args){switch(method.getName()){
            case "getString":return memory.containsKey(args[0])?memory.get(args[0]):args[1];
            case "getAll":return new HashMap<>(memory);
            case "edit":final Map<String,String>writes=new HashMap<>();final Set<String>remove=new HashSet<>();return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,m,a)->{switch(m.getName()){
                case "putString":writes.put((String)a[0],(String)a[1]);return editor;
                case "remove":remove.add((String)a[0]);return editor;
                case "commit":case "apply":commits++;for(String key:remove)memory.remove(key);memory.putAll(writes);if(fail-->0)return m.getName().equals("commit")?false:null;disk=new HashMap<>(memory);return m.getName().equals("commit")?true:null;
                default:throw new UnsupportedOperationException(m.getName());}});
            default:throw new UnsupportedOperationException(method.getName());}}
    }
    static final class Vault implements OfficialSessionStore.Vault {
        final SecretKey key;Vault()throws Exception{KeyGenerator gen=KeyGenerator.getInstance("AES");gen.init(128);key=gen.generateKey();}
        public String seal(String value)throws Exception{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD("official-only-test".getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(cipher.getIV())+":"+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
        public String open(String value)throws Exception{String[]p=value.split(":");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.getDecoder().decode(p[0])));cipher.updateAAD("official-only-test".getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(Base64.getDecoder().decode(p[1])),StandardCharsets.UTF_8);}
    }
    interface Checked{void run()throws Exception;}
    static void rejects(Checked action,String message)throws Exception{boolean failed=false;try{action.run();}catch(Exception expected){failed=true;}check(failed,message);}
    public static void main(String[]args)throws Exception {
        Prefs prefs=new Prefs();Vault vault=new Vault();OfficialSessionStore store=new OfficialSessionStore(prefs.prefs(),vault);check(store.load()==null&&prefs.commits==0,"empty store reads without writes");
        JSONObject state=new JSONObject().put("version",1).put("session",new JSONObject().put("token","PRIVATE_TOKEN").put("cookies",new JSONObject().put("sessionid","PRIVATE_COOKIE"))).put("profile",new JSONObject().put("uid","7123456789012345678").put("maskedPhone","138****0000"));
        store.save(state);String sealed=prefs.memory.get(OfficialSessionStore.KEY);check(!sealed.contains("PRIVATE_TOKEN")&&!sealed.contains("PRIVATE_COOKIE")&&!sealed.contains("7123456789012345678"),"only ciphertext reaches prefs");check(store.load().getJSONObject("session").getString("token").equals("PRIVATE_TOKEN"),"ciphertext reload preserves native session");check(prefs.memory.equals(prefs.disk)&&prefs.memory.size()==1,"one independent official storage key");
        Map<String,String>before=new HashMap<>(prefs.memory);prefs.fail=1;rejects(()->store.save(new JSONObject().put("token","CHANGED_TOKEN")),"commit failure reported");check(prefs.memory.equals(before)&&prefs.disk.equals(before),"failed Android commit restores prior in-memory and disk ciphertext");
        Prefs fresh=new Prefs();fresh.fail=1;OfficialSessionStore first=new OfficialSessionStore(fresh.prefs(),vault);rejects(()->first.save(state),"failed first save rejected");check(fresh.memory.isEmpty()&&fresh.disk.isEmpty(),"failed first save leaves key absent");
        prefs.memory.put(OfficialSessionStore.KEY,sealed.substring(0,sealed.length()-4)+"AAAA");rejects(store::load,"GCM tampering rejected");prefs.memory.put(OfficialSessionStore.KEY,sealed);
        OfficialSessionStore wrongKey=new OfficialSessionStore(prefs.prefs(),new Vault());rejects(wrongKey::load,"other alias/key cannot decrypt official session");
        int count=prefs.commits;rejects(()->store.save(new JSONObject().put("payload","x".repeat(600000))),"oversize session rejected");check(prefs.commits==count,"oversize value not written");
        check(!OfficialSessionStore.NAME.equals("hongguo_accounts_v1"),"retired preference file never reused");
        System.out.println("OFFICIAL_SESSION_STORE_OK checks="+checks+" crypto=AES-GCM-injected disk=private-preference-fixture AndroidKeystore=not_run networkRequests=0");
    }
}
