package io.github.jackotom.hongguo.android;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Validate legacy season metadata; active queues are restricted to the selected season. */
final class PlaybackPlaylist {
    static final int MAX_EPISODES = 1000, MAX_SERIES = 40, MAX_BYTES = 160 * 1024;
    private PlaybackPlaylist() { }
    private static JSONException invalid(String reason) { return new JSONException("跨季播放队列无效：" + reason); }
    private static String text(JSONObject value, String key, int max, boolean required) throws JSONException {
        Object raw = value.opt(key);
        if (raw == null || raw == JSONObject.NULL) { if (required) throw invalid(key); return ""; }
        if (!(raw instanceof String)) throw invalid(key);
        String result = ((String) raw).trim();
        if (result.length() > max || required && result.isEmpty()) throw invalid(key);
        return result;
    }
    private static int integer(JSONObject value, String key, int min, int max) throws JSONException {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) throw invalid(key);
        double number = ((Number) raw).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max) throw invalid(key);
        return (int) number;
    }
    private static JSONObject series(JSONObject input) throws JSONException {
        if (input == null) throw invalid("缺少季信息");
        String sid = text(input,"seriesId",30,true), title = text(input,"title",200,true);
        if (!sid.matches("[0-9]{6,30}")) throw invalid("seriesId");
        JSONObject result = new JSONObject().put("seriesId",sid).put("title",title)
            .put("coverUrl",text(input,"coverUrl",4096,false)).put("episodeLabel",text(input,"episodeLabel",100,false))
            .put("episodeCount",Math.max(0,Math.min(10000,input.optInt("episodeCount",0))));
        JSONArray tags = input.optJSONArray("tags"), clean = new JSONArray();
        if (tags != null) for (int i=0;i<Math.min(8,tags.length());i++) { Object tag=tags.opt(i); if (tag instanceof String && ((String)tag).length()<=80) clean.put(tag); }
        return result.put("tags",clean);
    }
    private static JSONObject episode(JSONObject input) throws JSONException {
        if (input == null) throw invalid("缺少分集");
        String vid=text(input,"vid",30,true);if (!vid.matches("[0-9]{1,30}")) throw invalid("vid");
        int index=integer(input,"index",1,10000);
        String availability=input.optString("availability","");
        if (!availability.equals("available") && !availability.equals("unknown")) availability=input.optBoolean("webAvailable")?"available":"unknown";
        return new JSONObject().put("vid",vid).put("index",index).put("availability",availability).put("webAvailable",availability.equals("available"));
    }
    static JSONObject owner(JSONObject fallbackSeries, JSONObject entry) {
        JSONObject owned = entry == null ? null : entry.optJSONObject("_series");
        return owned == null ? fallbackSeries : owned;
    }
    /** Validate a bounded series table + ordered flat episode manifest from the JS bridge. */
    static JSONArray parse(JSONObject descriptor, JSONObject selectedSeries, JSONObject selectedEpisode) throws JSONException {
        if (descriptor == null || descriptor.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES || integer(descriptor,"version",1,1)!=1) throw invalid("清单容量或版本");
        JSONArray seasons=descriptor.optJSONArray("series"), input=descriptor.optJSONArray("episodes");
        if (seasons==null || seasons.length()<1 || seasons.length()>MAX_SERIES || input==null || input.length()<1 || input.length()>MAX_EPISODES) throw invalid("季数或分集数量");
        JSONObject selectedOwner=series(selectedSeries), selected=episode(selectedEpisode);
        Map<String,JSONObject> owners=new LinkedHashMap<>();Map<String,Integer> ranks=new HashMap<>();
        for (int i=0;i<seasons.length();i++) {
            JSONObject clean=series(seasons.getJSONObject(i));String sid=clean.getString("seriesId");
            if (owners.containsKey(sid)) throw invalid("重复季编号");
            if (sid.equals(selectedOwner.getString("seriesId"))) {
                if (!clean.getString("title").equals(selectedOwner.getString("title"))) throw invalid("当前季标题不匹配");
                clean=selectedOwner;
            }
            owners.put(sid,clean);ranks.put(sid,i);
        }
        JSONArray result=new JSONArray();Set<String> videos=new HashSet<>(),used=new HashSet<>();int previousRank=-1,previousIndex=0;boolean found=false;
        for (int i=0;i<input.length();i++) {
            JSONObject raw=input.getJSONObject(i), clean=episode(raw);String sid=text(raw,"seriesId",30,true);Integer rank=ranks.get(sid);
            if (rank==null || rank<previousRank || rank>previousRank+1) throw invalid("季顺序不匹配");
            int index=clean.getInt("index");
            if (rank==previousRank && index<=previousIndex || !videos.add(sid+":"+clean.getString("vid"))) throw invalid("重复或倒序分集");
            previousRank=rank;previousIndex=index;used.add(sid);
            if (sid.equals(selectedOwner.getString("seriesId")) && clean.getString("vid").equals(selected.getString("vid")) && index==selected.getInt("index")) found=true;
            result.put(clean.put("_series",owners.get(sid)));
        }
        if (!found || used.size()!=owners.size()) throw invalid("当前分集缺失或空季");
        return result;
    }
    /** Revalidate legacy cast input, then discard other seasons before prefetch or playback. */
    static JSONArray normalize(JSONObject fallbackSeries, JSONArray entries, JSONObject selected) throws JSONException {
        if (entries==null || entries.length()<1 || entries.length()>MAX_EPISODES) throw invalid("分集数量");
        boolean annotated=false;for(int i=0;i<entries.length();i++)if(entries.getJSONObject(i).has("_series"))annotated=true;
        if (!annotated) {
            // Exactly the legacy selected-wins, single-season deduplication/sorting behavior.
            JSONObject picked=episode(selected);List<JSONObject> rows=new ArrayList<>();Set<String> vids=new HashSet<>();Set<Integer> indexes=new HashSet<>();
            rows.add(picked);vids.add(picked.getString("vid"));indexes.add(picked.getInt("index"));
            for(int i=0;i<entries.length();i++){JSONObject clean=episode(entries.getJSONObject(i));if(vids.contains(clean.getString("vid"))||indexes.contains(clean.getInt("index")))continue;rows.add(clean);vids.add(clean.getString("vid"));indexes.add(clean.getInt("index"));}
            if(rows.size()>MAX_EPISODES)throw invalid("分集数量");rows.sort(Comparator.comparingInt(row->row.optInt("index")));JSONArray result=new JSONArray();for(JSONObject row:rows)result.put(row);return result;
        }
        JSONArray seasons=new JSONArray(), flat=new JSONArray();Set<String> ids=new HashSet<>();
        for(int i=0;i<entries.length();i++) {
            JSONObject raw=entries.getJSONObject(i), owned=raw.optJSONObject("_series");if(owned==null)throw invalid("季身份缺失");
            JSONObject clean=series(owned);String sid=clean.getString("seriesId");if(ids.add(sid))seasons.put(clean);
            else {for(int n=0;n<seasons.length();n++){JSONObject earlier=seasons.getJSONObject(n);if(sid.equals(earlier.getString("seriesId"))&&!clean.getString("title").equals(earlier.getString("title")))throw invalid("季标题冲突");}}
            flat.put(episode(raw).put("seriesId",sid));
        }
        JSONArray parsed=parse(new JSONObject().put("version",1).put("series",seasons).put("episodes",flat),owner(fallbackSeries,selected),selected);
        List<JSONObject> rows=new ArrayList<>();for(int i=0;i<parsed.length();i++)rows.add(parsed.getJSONObject(i));
        JSONArray result=new JSONArray();for(JSONObject row:selectedSeason(fallbackSeries,rows,selected))result.put(row);return result;
    }
    /** Keep real IDs/indexes and order, but never let auto-next, prefetch or merge cross a season. */
    static List<JSONObject> selectedSeason(JSONObject fallback,List<JSONObject> entries,JSONObject selected) {
        String sid=owner(fallback,selected).optString("seriesId");
        if(find(entries,sid,selected.optString("vid"),selected.optInt("index"),fallback)<0)throw new IllegalArgumentException("当前分集不在队列中");
        List<JSONObject> result=new ArrayList<>();
        for(JSONObject row:entries)if(sid.equals(owner(fallback,row).optString("seriesId")))result.add(row);
        return result;
    }
    static int find(List<JSONObject> entries,String sid,String vid,int index,JSONObject fallback) {
        for(int i=0;i<entries.size();i++) {JSONObject e=entries.get(i);if(owner(fallback,e).optString("seriesId").equals(sid)&&e.optString("vid").equals(vid)&&e.optInt("index")==index)return i;}
        return -1;
    }
    static String identity(JSONObject fallback,List<JSONObject> entries) {
        String first=entries.isEmpty()?fallback.optString("seriesId"):owner(fallback,entries.get(0)).optString("seriesId");boolean multi=false;
        StringBuilder content=new StringBuilder();
        for(JSONObject entry:entries){String sid=owner(fallback,entry).optString("seriesId");multi|=!first.equals(sid);content.append(sid).append('/').append(entry.optString("vid")).append('/').append(entry.optInt("index")).append('\n');}
        if(!multi)return first;
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(content.toString().getBytes(StandardCharsets.UTF_8));StringBuilder hash=new StringBuilder("playlist:");for(byte b:digest)hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hash.toString();}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static String identity(JSONObject fallback,JSONArray entries) throws JSONException {
        List<JSONObject> rows=new ArrayList<>();for(int i=0;i<entries.length();i++)rows.add(entries.getJSONObject(i));return identity(fallback,rows);
    }
}
