// SPDX-License-Identifier: GPL-3.0-only
package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.net.ssl.*;
import org.json.*;

/** Anonymous, bounded read-only comment client. No credentials, writes or playback transport changes. */
final class DanmakuClient {
    static final int WINDOW_MS = 30_000, MAX_ITEMS = 270, MAX_PAGES = 3;
    private static final String HOST = "api5-normal-sinfonlineb.fqnovel.com";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String device, install;
    static final class Item {
        final String id, text; final long timeMs; final int color;
        Item(String id,String text,long timeMs,int color){this.id=id;this.text=text;this.timeMs=timeMs;this.color=color;}
    }
    static final class Page {
        final List<Item> items; final long next; final boolean more;
        Page(List<Item> items,long next,boolean more){this.items=items;this.next=next;this.more=more;}
    }
    static final class Result {
        final List<Item> items; final String notice; final boolean partial;
        Result(List<Item> items,String notice,boolean partial){this.items=Collections.unmodifiableList(new ArrayList<>(items));this.notice=notice;this.partial=partial;}
    }
    DanmakuClient(Context context) throws Exception {
        SharedPreferences prefs=context.getApplicationContext().getSharedPreferences("official_danmaku_device_v1",Context.MODE_PRIVATE);
        String d=prefs.getString("device",""),i=prefs.getString("install","");
        if(d.isEmpty()&&i.isEmpty()){d=newId();i=newId();if(!prefs.edit().putString("device",d).putString("install",i).commit())throw new IOException("弹幕设备配置保存未成功");}
        if(!validDevice(d)||!validDevice(i))throw new IOException("弹幕设备配置读取未成功");
        device=d;install=i;
    }
    static boolean validDevice(String s){return s!=null&&s.matches("[1-9][0-9]{18}");}
    private static String newId(){return new BigInteger(64,RANDOM).mod(new BigInteger("8000000000000000000")).add(new BigInteger("1000000000000000000")).toString();}
    static boolean validId(String id){return id!=null&&id.matches("[1-9][0-9]{0,29}");}
    static JSONObject body(String vid,String sid,long start,long duration)throws Exception{
        if(!validId(vid)||!validId(sid)||start<0||start>86400000||duration<1||duration>86400000)throw new IllegalArgumentException("弹幕分集或时间无效");
        return new JSONObject().put("comment_source",601).put("server_channel",1000).put("group_id",vid).put("group_type",30).put("comment_type",20).put("sort",1).put("count",90).put("cursor","").put("aid",8662).put("compliance_status",0)
            .put("business_param",new JSONObject().put("book_id",sid).put("start_offset_time",start).put("playlet_item_duration",duration).put("need_danmaku_guide_type",new JSONArray().put(1).put(3).put(4).put(2)));
    }
    Result window(String sid,String vid,long start,long duration)throws Exception {
        long end=Math.min(86400000,start+WINDOW_MS),cursor=start,deadline=System.nanoTime()+18_000_000_000L;
        LinkedHashMap<String,Item> items=new LinkedHashMap<>();String notice="";boolean partial=false;
        for(int page=0;page<MAX_PAGES;page++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("弹幕读取已取消");
            try{
                JSONObject payload=request(body(vid,sid,cursor,duration),vid,deadline);Page parsed=parse(payload,vid);
                for(Item item:parsed.items)if(item.timeMs>=start&&item.timeMs<end){String key=item.id.isEmpty()?item.timeMs+":"+item.text:item.id;items.putIfAbsent(key,item);if(items.size()>=MAX_ITEMS)break;}
                if(!parsed.more||parsed.next>=end)break;
                if(parsed.next<=cursor)throw new IOException("弹幕分页未前进");
                cursor=parsed.next;
                if(page==MAX_PAGES-1||items.size()>=MAX_ITEMS){notice="当前时间段弹幕较多，已限制载入数量";break;}
            }catch(Exception error){if(items.isEmpty())throw error;notice="部分弹幕未载入，可关闭后重新开启重试";partial=true;break;}
        }
        List<Item> sorted=new ArrayList<>(items.values());sorted.sort(Comparator.comparingLong(x->x.timeMs));return new Result(sorted,notice,partial);
    }
    static Page parse(JSONObject payload,String vid)throws Exception{
        if(!"0".equals(payload.optString("code")))throw new IOException("弹幕接口未返回有效数据");JSONObject data=payload.optJSONObject("data");if(data==null||!(data.opt("data_list") instanceof JSONArray))throw new IOException("弹幕列表格式无效");JSONArray rows=data.getJSONArray("data_list");if(rows.length()>1000)throw new IOException("弹幕条数超过上限");
        JSONObject info=data.optJSONObject("common_list_info"),extra=data.optJSONObject("extra");boolean more=false,explicit=false;long next=0;
        if(extra!=null)next=number(extra.opt("next_query_danmaku_list_time"),-1);
        if(info!=null&&info.has("has_more")){Object raw=info.opt("has_more");if(raw instanceof Boolean){more=(Boolean)raw;explicit=true;}else if(raw instanceof Number){more=((Number)raw).longValue()!=0;explicit=true;}else if(raw instanceof String&&Arrays.asList("1","0","true","false").contains(raw)){more=raw.equals("1")||raw.equals("true");explicit=true;}else throw new IOException("弹幕分页状态无效");
            JSONObject cursor=info.optJSONObject("cursor");if(cursor==null&&info.opt("cursor") instanceof String){String c=info.optString("cursor");if(c.startsWith("{"))try{cursor=new JSONObject(c);}catch(JSONException invalid){throw new IOException("弹幕游标无效");}}
            if(cursor!=null)for(String key:new String[]{"end_offset_time","next_offset_time","offset"})if(cursor.has(key)){next=Math.max(next,number(cursor.opt(key),-1));break;}
        }
        if(!explicit)more=next>0; if(explicit&&!more)next=0;
        List<Item> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(int n=0;n<rows.length()&&result.size()<MAX_ITEMS;n++){
            JSONObject row=rows.optJSONObject(n),comment=row==null?null:row.optJSONObject("comment"),common=comment==null?null:comment.optJSONObject("common");if(common==null||!"1".equals(common.optString("status")))continue;
            String group=common.optString("group_id","");if(!group.isEmpty()&&!group.equals(vid))continue;
            JSONObject content=common.optJSONObject("content"),expand=comment.optJSONObject("expand");String text=clean(content==null?null:content.opt("text"));if(text.isEmpty())continue;
            long time=number(expand!=null&&expand.has("offset_time")?expand.opt("offset_time"):comment.opt("offset_time"),-1);if(time<0||time>86400000)continue;
            int color=0xffffffff;boolean found=false;for(JSONObject holder:new JSONObject[]{content,common,expand,comment}){if(holder==null)continue;for(String k:new String[]{"color","font_color","text_color","col"})if(holder.has(k)){color=color(holder.opt(k));found=true;break;}if(found)break;}
            String id=comment.optString("comment_id","");if(id.length()>128)id="";String key=id.isEmpty()?time+":"+text:id;if(seen.add(key))result.add(new Item(id,text,time,color));
        }
        result.sort(Comparator.comparingLong(x->x.timeMs));return new Page(result,next,more);
    }
    static long number(Object value,long fallback){try{if(!(value instanceof Number||value instanceof String)||!String.valueOf(value).matches("[0-9]{1,16}(?:\\.0+)?"))return fallback;double n=Double.parseDouble(String.valueOf(value));if(!Double.isFinite(n)||n!=Math.rint(n)||n<0||n>9007199254740991d)return fallback;return(long)n;}catch(RuntimeException bad){return fallback;}}
    static String clean(Object value){if(!(value instanceof String))return"";String s=(String)value;if(s.codePointCount(0,s.length())>200)return"";StringBuilder out=new StringBuilder();for(int i=0;i<s.length();){int cp=s.codePointAt(i);i+=Character.charCount(cp);out.appendCodePoint(cp<32||cp==127||cp==0x2028||cp==0x2029?' ':cp);}return out.toString().trim();}
    static int color(Object value){try{long n;if(value instanceof Number){n=((Number)value).longValue();if(n<0)return-1;}else if(value instanceof String){String s=((String)value).trim();if(s.matches("#[a-fA-F0-9]{3}"))s="#"+s.charAt(1)+s.charAt(1)+s.charAt(2)+s.charAt(2)+s.charAt(3)+s.charAt(3);if(s.matches("#[a-fA-F0-9]{6}"))n=Long.parseLong(s.substring(1),16);else if(s.matches("0[xX][a-fA-F0-9]{1,8}"))n=Long.parseLong(s.substring(2),16);else if(s.matches("[0-9]{1,10}"))n=Long.parseLong(s);else return-1;}else return-1;return 0xff000000|(int)(n&0xffffff);}catch(RuntimeException invalid){return-1;}}
    private JSONObject request(JSONObject body,String vid,long deadline)throws Exception{
        long ms=System.currentTimeMillis();String query=DanmakuSigner.query(ms,device,install);byte[] nonce=new byte[11];RANDOM.nextBytes(nonce);Map<String,String> headers=DanmakuSigner.headers(query,ms/1000,nonce);headers.put("User-Agent",DanmakuSigner.USER_AGENT);headers.put("Accept","application/json");headers.put("X-XS-From-Web","0");headers.put("Sdk-Version","2");headers.put("Content-Type","application/json; charset=utf-8");headers.put("Comment-Source","601");headers.put("Server-Channel","1000");
        URI uri=new URI("https://"+HOST+"/novel/commentapi/comment/list/"+vid+"/v1/?"+query);byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);InetAddress[] addresses=NetworkGuard.publicAddresses(uri,deadline);Socket raw=null;IOException last=null;
        for(int i=0;i<Math.min(4,addresses.length);i++){remaining(deadline);Socket candidate=new Socket();try(AppTransport.SocketGuard guard=AppTransport.guardSocket(candidate,deadline)){candidate.connect(new InetSocketAddress(addresses[i],443),remaining(deadline));raw=candidate;break;}catch(IOException e){last=e;candidate.close();}}
        if(raw==null)throw new IOException("弹幕服务连接未成功",last);
        try(AppTransport.SocketGuard guard=AppTransport.guardSocket(raw,deadline);SSLSocket socket=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,HOST,443,true)){
            SSLParameters tls=socket.getSSLParameters();tls.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(tls);socket.setSoTimeout(remaining(deadline));socket.startHandshake();
            StringBuilder req=new StringBuilder("POST "+uri.getRawPath()+"?"+query+" HTTP/1.1\r\nHost: "+HOST+"\r\nAccept-Encoding: identity\r\nConnection: close\r\n");for(Map.Entry<String,String>e:headers.entrySet())req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");req.append("Content-Length: ").append(payload.length).append("\r\n\r\n");OutputStream out=socket.getOutputStream();out.write(req.toString().getBytes(StandardCharsets.UTF_8));out.write(payload);out.flush();InputStream input=new BufferedInputStream(socket.getInputStream());String status=line(input,8192);if(!status.matches("HTTP/1\\.[01] [0-9]{3}.*")||Integer.parseInt(status.substring(9,12))!=200)throw new IOException("弹幕服务暂不可用");Map<String,String> response=new HashMap<>();int total=status.length();for(;;){String row=line(input,16384);total+=row.length();if(total>65536)throw new IOException("弹幕响应头过长");if(row.isEmpty())break;int colon=row.indexOf(':');if(colon<1)throw new IOException("弹幕响应头无效");String key=row.substring(0,colon).toLowerCase(Locale.ROOT);if(response.containsKey(key)&&Arrays.asList("content-length","transfer-encoding","content-encoding").contains(key))throw new IOException("弹幕响应头重复");response.put(key,row.substring(colon+1).trim());}
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();NetworkGuard.streamBody(input,bytes,response,2*1024*1024,deadline);remaining(deadline);return new JSONObject(bytes.toString("UTF-8"));
        }finally{raw.close();}
    }
    private static int remaining(long deadline)throws IOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("弹幕读取已取消");long ms=(deadline-System.nanoTime())/1000000;if(ms<=0)throw new SocketTimeoutException("弹幕读取超时");return(int)Math.min(7000,Math.max(1,ms));}
    private static String line(InputStream in,int max)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();while(out.size()<=max){int n=in.read();if(n<0)throw new EOFException("弹幕响应中断");if(n=='\n')return out.toString("US-ASCII").replaceFirst("\\r$","");out.write(n);}throw new IOException("弹幕响应行过长");}
}
