package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.json.*;

/** Read-only official relationship projection. No reservations, title guesses or playback actions. */
final class OfficialSeasons {
    static JSONObject query(String sid) throws Exception {
        if (!id(sid)) throw new IOException("系列剧ID无效");
        return new JSONObject().put("scene", "10").put("from", "single_col_same_ip_series")
            .put("offset", "0").put("limit", "0").put("total_chapter_num", "0").put("current_chapter_num", "0")
            .put("post_id", "0").put("is_horizontal_screen", "false").put("bookstore_tab", "0")
            .put("bookstore_tab_type", "0").put("book_id", sid).put("filter_ids", sid);
    }
    static boolean id(String s) { return s != null && s.matches("[1-9][0-9]{0,29}"); }
    private static String text(Object o, int max) { if (!(o instanceof String)) return ""; String s=((String)o).trim(); return s.length()<=max && !s.matches("(?s).*[\\x00-\\x1f\\x7f].*") ? s : ""; }
    private static String identifier(JSONObject row, String... keys) { for(String key:keys){Object raw=row.opt(key);if(raw instanceof String || raw instanceof Long || raw instanceof Integer){String s=String.valueOf(raw);if(id(s))return s;}}return ""; }
    private static int positive(JSONObject row,String...keys){for(String key:keys){Object raw=row.opt(key);try{long n=Long.parseLong(String.valueOf(raw));if(n>0&&n<=10000)return(int)n;}catch(Exception ignored){}}return 0;}
    private static boolean yes(Object v){return Boolean.TRUE.equals(v)||"1".equals(String.valueOf(v))||"true".equals(v);}
    private static List<String> labels(JSONArray rows){List<String> out=new ArrayList<>();if(rows!=null)for(int i=0;i<Math.min(rows.length(),30);i++){JSONObject row=rows.optJSONObject(i);String s=row==null?"":text(row.opt("content"),200);if(!s.isEmpty())out.add(s);}return out;}
    private static String first(JSONObject row,int max,String...keys){for(String key:keys){String s=text(row.opt(key),max);if(!s.isEmpty())return s;}return "";}
    static JSONObject parse(JSONObject payload,String requested) throws Exception {
        if(!id(requested)||!"0".equals(payload.optString("code"))||(payload.has("error_code")&&!"0".equals(payload.optString("error_code")))||(payload.has("status_code")&&!"0".equals(payload.optString("status_code")))||!(payload.opt("data") instanceof JSONArray))throw new IOException("官方系列剧未返回有效列表");
        JSONArray blocks=payload.getJSONArray("data"),out=new JSONArray();Set<String> seen=new HashSet<>();boolean containsCurrent=false;int count=0;
        if(blocks.length()>500)throw new IOException("官方系列剧列表超过上限");
        for(int b=0;b<blocks.length();b++){
            JSONObject block=blocks.optJSONObject(b);if(block==null)throw new IOException("官方系列剧区块无效");
            Object rawRows=block.opt("video_data");if(rawRows==null||rawRows==JSONObject.NULL)continue;
            if(!(rawRows instanceof JSONArray))throw new IOException("官方系列剧条目无效");JSONArray rows=(JSONArray)rawRows;
            for(int i=0;i<rows.length();i++){
                if(++count>1000)throw new IOException("官方系列剧列表超过上限");JSONObject raw=rows.optJSONObject(i);if(raw==null)throw new IOException("官方系列剧数据无效");
                JSONObject row=raw.optJSONObject("video_data");if(row==null)row=raw;
                String sid=identifier(row,"series_id_str","series_id","seriesId","book_id","id");String title=first(row,200,"series_title","series_name","title");
                if(sid.isEmpty()||title.isEmpty()||!seen.add(sid))continue;if(sid.equals(requested))containsCurrent=true;
                int episodes=positive(row,"episode_cnt","episode_total_cnt"),number=positive(row,"season_index");String label="";
                for(String value:labels(row.optJSONArray("secondary_info_list")))if(value.matches("第[1-9][0-9]{0,2}季")){label=value;number=Integer.parseInt(value.substring(1,value.length()-1));break;}
                if(label.isEmpty())label=number>0?"第"+number+"季":sid.equals(requested)?"当前剧集":"系列剧";
                String vid=identifier(row,"vid","video_id");boolean upcoming=row.optJSONObject("subscribe_item")!=null||vid.isEmpty()||episodes<=0;
                boolean playable=!upcoming&&!yes(row.opt("disable_play"));
                String cover=first(row,2048,"series_cover","cover","cover_url");try{URI u=URI.create(cover);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null)cover="";}catch(Exception invalid){cover="";}
                List<String> subtitles=labels(row.optJSONArray("sub_title_list"));
                out.put(new JSONObject().put("seriesId",sid).put("title",title).put("coverUrl",cover).put("description",first(row,500,"series_intro","video_desc","description"))
                    .put("episodeCount",episodes).put("episodeLabel",upcoming?"待上线":episodes+" 集").put("seasonNumber",number>0?number:JSONObject.NULL).put("seasonLabel",label)
                    .put("official",true).put("playable",playable).put("upcoming",upcoming).put("vid",upcoming?"":vid).put("subtitle",String.join(" · ",subtitles)).put("tags",new JSONArray()));
            }
        }
        if(out.length()>0&&!containsCurrent)throw new IOException("官方季关系尚未确认当前剧集，请重试或按标题查找");
        JSONArray limited=new JSONArray();boolean currentKept=false;JSONObject current=null;
        for(int i=0;i<out.length();i++){JSONObject row=out.getJSONObject(i);if(row.getString("seriesId").equals(requested))current=row;if(i<40){limited.put(row);if(row==current)currentKept=true;}}
        if(current!=null&&!currentKept)limited.put(39,current);
        return new JSONObject().put("items",limited).put("matching","official").put("hasMore",false).put("complete",out.length()<=40).put("truncated",out.length()>40).put("total",out.length()).put("page",1);
    }
}
