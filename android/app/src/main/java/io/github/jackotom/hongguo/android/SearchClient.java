package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.json.*;

/** Lazy anonymous registration + real short-drama cursor search. No media/source side effects. */
final class SearchClient {
    private static final String PREFERENCES="hongguo_anonymous_search_v1";
    private static final String REGISTER="https://log.snssdk.com/service/2/device_register/?";
    private static final String SEARCH="https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/search/tab/v?";
    private static final ReentrantLock LOCK=new ReentrantLock();
    interface Transport { JSONObject request(String url,String method,Map<String,String> headers,byte[] body,long deadline)throws Exception; }
    static JSONObject search(Context context,JSONObject params)throws Exception {
        return search(context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE),params,AppTransport::searchRequest);
    }
    static JSONObject search(SharedPreferences preferences,JSONObject params,Transport transport)throws Exception {
        String keyword=text(params,"keyword",100,true),searchId=text(params,"searchId",512,false),passback=text(params,"passback",512,false);
        Object offsetValue=params.opt("offset");
        if(!(offsetValue instanceof Number)||((Number)offsetValue).doubleValue()!=((Number)offsetValue).intValue())throw new IOException("搜索页位置无效");
        int offset=((Number)offsetValue).intValue();if(offset<0||offset>10000)throw new IOException("搜索页位置无效");
        long deadline=requestDeadline(params);
        boolean locked=false;
        try {
            cancelled(deadline);
            locked=LOCK.tryLock(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);if(!locked)throw new SocketTimeoutException();
            cancelled(deadline);
            String device=preferences.getString("device_id",""),install=preferences.getString("install_id","");
            if(!validId(device)||!validId(install)){
                JSONObject registered=register(transport,Math.min(deadline,System.nanoTime()+18_000_000_000L));
                device=stringId(registered,"device_id_str");install=stringId(registered,"install_id_str");
                cancelled(deadline);
                if(!preferences.edit().putString("device_id",device).putString("install_id",install).commit())throw new IOException("搜索初始化保存失败，请重试");
            }
            String query=searchQuery(keyword,offset,searchId,passback,device,install);
            JSONObject envelope=transport.request(SEARCH+query,"GET",SearchProtocol.headers(query),new byte[0],deadline);
            cancelled(deadline);
            JSONObject result=response(envelope,"搜索");
            if(!(result.opt("code") instanceof Number)||result.optInt("code",-1)!=0||result.optJSONArray("search_tabs")==null)throw new IOException("搜索服务未返回有效结果，请重试");
            return result;
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("搜索已取消");}
        catch(SocketTimeoutException e){throw new IOException("搜索请求超时，请重试");}
        catch(IOException e){String message=e.getMessage();if(message!=null&&message.startsWith("搜索"))throw e;throw new IOException("搜索服务连接未成功，请重试");}
        catch(Exception e){throw new IOException("搜索服务响应无效，请重试");}
        finally{if(locked)LOCK.unlock();}
    }
    /** Local scheduling fields never enter the signed App query or registration payload. */
    private static long requestDeadline(JSONObject params)throws IOException {
        long budget=38_000L;
        if(params.has("timeoutMs"))budget=budgetInteger(params.opt("timeoutMs"),1L,38_000L,"搜索等待期限无效");
        if(params.has("deadlineMs")) {
            long end=budgetInteger(params.opt("deadlineMs"),1L,9_007_199_254_740_991L,"搜索截止时间无效");
            long remaining=end-System.currentTimeMillis();
            if(remaining<=0)throw new SocketTimeoutException("搜索请求已过期，请重试");
            budget=Math.min(budget,remaining);
        }
        return System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget);
    }
    private static long budgetInteger(Object value,long minimum,long maximum,String message)throws IOException {
        if(!(value instanceof Number))throw new IOException(message);
        Number number=(Number)value;double raw=number.doubleValue();long whole=number.longValue();
        if(!Double.isFinite(raw)||raw!=(double)whole||whole<minimum||whole>maximum)throw new IOException(message);
        return whole;
    }
    private static void cancelled(long deadline)throws IOException { if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("搜索已取消");if(System.nanoTime()>=deadline)throw new SocketTimeoutException(); }
    private static String text(JSONObject params,String key,int maximum,boolean required)throws IOException {
        Object value=params.opt(key);if(value==null||value==JSONObject.NULL){if(required)throw new IOException("搜索关键词为空");return "";}
        if(!(value instanceof String))throw new IOException("搜索参数无效");String raw=(String)value;for(int i=0;i<raw.length();i++)if(Character.isISOControl(raw.charAt(i)))throw new IOException("搜索参数无效");String text=required?raw.trim():raw;
        if(text.length()>maximum||(required&&text.isEmpty()))throw new IOException("搜索参数过长或为空");
        for(int i=0;i<text.length();i++)if(Character.isISOControl(text.charAt(i)))throw new IOException("搜索参数无效");return text;
    }
    static boolean validId(String value){return value!=null&&value.matches("[1-9][0-9]{9,23}");}
    private static String stringId(JSONObject json,String key)throws IOException{Object value=json.opt(key);if(!(value instanceof String)||!validId((String)value))throw new IOException("搜索初始化未成功，请重试");return (String)value;}
    private static JSONObject response(JSONObject envelope,String action)throws Exception {
        int status=envelope.optInt("status",0);if(status!=200)throw new IOException("搜索服务响应 HTTP "+status+"，请重试");
        String body=envelope.optString("body","");if(body.isEmpty()||body.length()>2*1024*1024)throw new IOException("搜索服务响应为空或过长，请重试");return new JSONObject(body);
    }
    static String searchQuery(String keyword,int offset,String searchId,String passback,String device,String install) {
        Map<String,String> p=new LinkedHashMap<>();
        p.put("bookshelf_search_plan","4");p.put("offset",Integer.toString(offset));p.put("user_is_login","0");p.put("bookstore_tab","2");p.put("passback",passback.isEmpty()?Integer.toString(offset):passback);p.put("query",keyword);p.put("count","20");p.put("search_source","1");p.put("clicked_content","search_history");p.put("use_lynx","false");p.put("use_correct","true");p.put("tab_type","11");p.put("tab_name","store");p.put("pad_column_cover","0");p.put("is_first_enter_search","false");p.put("iid",install);p.put("device_id",device);p.put("ac","wifi");p.put("channel","xiaomi_1967_64");p.put("aid","1967");p.put("app_name","novelapp");p.put("version_code","65132");p.put("version_name","6.5.1.32");p.put("device_platform","android");p.put("os","android");p.put("ssmix","a");p.put("device_type","FRD-AL10");p.put("device_brand","honor");p.put("language","zh");p.put("os_api","28");p.put("os_version","9");p.put("manifest_version_code","65132");p.put("resolution","1080*1920");p.put("dpi","480");p.put("update_version_code","65132");p.put("pv_player","65132");p.put("gender","2");p.put("need_personal_recommend","1");p.put("player_so_load","1");p.put("is_android_pad_screen","0");p.put("host_abi","arm64-v8a");p.put("dragon_device_type","phone");p.put("rom_version","FRD-AL10+8.0.0.556(C00)");p.put("compliance_status","0");if(!searchId.isEmpty())p.put("search_id",searchId);return SearchProtocol.query(p);
    }
    static JSONObject register(Transport transport,long deadline)throws Exception {
        String openudid=SearchProtocol.hex(SearchProtocol.random(8)),cdid=UUID.randomUUID().toString(),client=UUID.randomUUID().toString();
        Map<String,String> p=new TreeMap<>();
        p.put("tt_data","a");p.put("os_api","29");p.put("device_type","MI 12");p.put("ssmix","a");p.put("manifest_version_code","65132");p.put("dpi","560");p.put("version_name","6.5.1.32");p.put("ts",Long.toString(System.currentTimeMillis()/1000));p.put("cpu_support64","true");p.put("app_type","normal");p.put("appTheme","light");p.put("ac","wifi");p.put("host_abi","arm64-v8a");p.put("update_version_code","65132");p.put("channel","xiaomi_1967_64");p.put("_rticket",Long.toString(System.currentTimeMillis()));p.put("device_platform","android");p.put("version_code","65132");p.put("cdid",cdid);p.put("os","android");p.put("is_android_pad","0");p.put("openudid",openudid);p.put("package","com.dragon.read");p.put("resolution","1440*2392");p.put("os_version","10");p.put("language","zh");p.put("device_brand","Xiaomi");p.put("need_personal_recommend","1");p.put("aid","1967");p.put("minor_status","0");p.put("app_name","novel");p.put("mcc_mnc","46007");
        JSONObject header=new JSONObject();header.put("display_name","番茄小说").put("update_version_code","65132").put("manifest_version_code","65132").put("aid",1967).put("channel","xiaomi_1967_64").put("package","com.dragon.read").put("app_version","6.5.1.32").put("version_code","65132").put("sdk_version","v04.04.05-ov-android").put("sdk_target_version",29).put("os","Android").put("os_version","10").put("os_api","29").put("device_model","MI 12").put("device_brand","Xiaomi").put("device_manufacturer","Google").put("device_category","phone").put("cpu_abi","arm64-v8a").put("release_build",UUID.randomUUID().toString()).put("density_dpi","560").put("display_density","mdpi").put("resolution","1440x2392").put("language","zh").put("mac","02:00:00:00:00:00").put("timezone",8).put("access","wifi").put("not_request_sender",0).put("carrier","CHINA MOBILE").put("mcc_nc","46007").put("mcc_mnc","46007").put("rom","EMUI-"+SearchProtocol.hex(SearchProtocol.random(7))).put("rom_version",SearchProtocol.hex(SearchProtocol.random(2))).put("sig_hash",SearchProtocol.hex(MessageDigest.getInstance("MD5").digest(SearchProtocol.random(16)))).put("openudid",openudid).put("clientudid",client).put("sim_serial_number",new JSONArray()).put("region","CN").put("tz_name","Asia/Shanghai").put("tz_offset",28800).put("sim_region","cn");
        byte[] json=SearchProtocol.utf8(new JSONObject().put("magic_tag","ss_app_log").put("header",header).put("_gen_time",System.currentTimeMillis()).toString());
        Map<String,String> headers=new LinkedHashMap<>();headers.put("content-type","application/octet-stream;tt-data=a");headers.put("user-agent","com.dragon.read/65132 (Linux; U; Android 10; zh_CN; MI 12; Build/MMB29M; Cronet/TTNetVersion:9ac8d95c 2024-11-25 QuicVersion:3f326df4 2024-11-14)");
        return response(transport.request(REGISTER+SearchProtocol.query(p),"POST",headers,SearchProtocol.registrationBody(json),deadline),"初始化");
    }
}
