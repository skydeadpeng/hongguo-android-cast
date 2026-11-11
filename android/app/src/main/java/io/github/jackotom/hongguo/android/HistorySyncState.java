package io.github.jackotom.hongguo.android;

import org.json.*;
import java.util.*;

/** Account-local durable outbox. Server revisions, not clocks/episode numbers, resolve races. */
final class HistorySyncState {
    private final JSONObject canonical, pending;
    private JSONArray inFlight;
    private JSONObject inFlightWriters;
    private final Map<String,Long> writers = new HashMap<>();
    private final Set<String> muted = new HashSet<>();
    private long cursor, nextSequence;
    private boolean conflict;
    HistorySyncState(String encoded) throws JSONException {
        JSONObject root = new JSONObject(encoded == null ? "{}" : encoded);
        canonical = root.optJSONObject("canonical") == null ? new JSONObject() : root.getJSONObject("canonical");
        pending = root.optJSONObject("pending") == null ? new JSONObject() : root.getJSONObject("pending");
        inFlight=root.optJSONArray("inFlight")==null?new JSONArray():root.getJSONArray("inFlight");
        inFlightWriters=root.optJSONObject("inFlightWriters")==null?new JSONObject():root.getJSONObject("inFlightWriters");
        cursor = Math.max(0, root.optLong("cursor"));
        nextSequence=Math.max(0,root.optLong("nextSequence"));
    }
    String encode() throws JSONException { return new JSONObject().put("canonical",canonical).put("pending",pending).put("cursor",cursor).put("nextSequence",nextSequence).put("inFlight",inFlight).put("inFlightWriters",inFlightWriters).toString(); }
    HistorySyncState copyState() throws JSONException {
        HistorySyncState copy=new HistorySyncState(encode());copy.writers.putAll(writers);copy.muted.addAll(muted);copy.conflict=conflict;return copy;
    }
    boolean contains(String sid) { return canonical.has(sid)||pending.has(sid); }
    long cursor() { return cursor; }
    int pendingCount() { return pending.length(); }
    boolean conflicted() { return conflict; }
    private static JSONObject copy(JSONObject value) throws JSONException { return new JSONObject(value.toString()); }
    private long revision(String sid) { JSONObject row=canonical.optJSONObject(sid);return row==null?0:row.optLong("revision"); }
    private static String writerKey(String writer,String sid) { return writer+":"+sid; }
    boolean record(String writer,JSONObject row) throws JSONException {
        String sid=row.getString("seriesId"), key=writerKey(writer,sid);
        if(muted.contains(key)){conflict=true;return false;}
        long rev=revision(sid);
        Long base=writers.get(key);
        if(base==null) { base=rev;writers.put(key,base); }
        if(base!=rev) { muted.add(key);conflict=true;return false; }
        if(!canonical.has(sid)&&!pending.has(sid)){
            int count=canonical.length();Iterator<String> keys=pending.keys();while(keys.hasNext())if(!canonical.has(keys.next()))count++;
            if(count>=1000)throw new JSONException("账户观看记录已达 1000 部上限，新增记录未保存");
        }
        if(nextSequence==Long.MAX_VALUE)throw new JSONException("同步序号已达上限");
        JSONObject mutation=new JSONObject().put("mutationId",UUID.randomUUID().toString()).put("baseRevision",base).put("sequence",++nextSequence)
            .put("record",clean(row)).put("writerId",writer).put("localUpdatedAt",row.optLong("updatedAt",System.currentTimeMillis()));
        pending.put(sid,mutation);conflict=false;return true;
    }
    static JSONObject clean(JSONObject row) throws JSONException {
        JSONObject out=new JSONObject();
        for(String field:new String[]{"seriesId","title","coverUrl","description","episodeCount","episodeLabel","tags","vid","index","positionMs"}) if(row.has(field))out.put(field,row.get(field));
        return out;
    }
    JSONArray batch(int limit) throws JSONException {
        if(inFlight.length()>0)return new JSONArray(inFlight.toString());
        JSONArray batch=new JSONArray();Iterator<String> keys=pending.keys();List<JSONObject> ordered=new ArrayList<>();
        while(keys.hasNext())ordered.add(pending.getJSONObject(keys.next()));
        ordered.sort((a,b)->Long.compare(a.optLong("sequence"),b.optLong("sequence")));
        for(JSONObject m:ordered) {
            if(batch.length()>=limit)break;
            batch.put(new JSONObject().put("mutationId",m.getString("mutationId")).put("sequence",m.getLong("sequence")).put("baseRevision",m.getLong("baseRevision")).put("record",copy(m.getJSONObject("record"))));
        }
        inFlight=batch;inFlightWriters=new JSONObject();
        for(int i=0;i<batch.length();i++){JSONObject b=batch.getJSONObject(i);JSONObject m=pending.getJSONObject(b.getJSONObject("record").getString("seriesId"));inFlightWriters.put(b.getString("mutationId"),m.getString("writerId"));}
        return new JSONArray(batch.toString());
    }
    /** Sent snapshot retains writer identity separately; retries preserve the exact mutation bytes. */
    JSONObject sentWriters(JSONArray batch) throws JSONException {
        return copy(inFlightWriters);
    }
    void acknowledge(JSONArray results,JSONObject sentWriters) throws JSONException {
        if(results.length()!=sentWriters.length())throw new JSONException("同步应答数量异常");
        Set<String> seen=new HashSet<>();
        for(int i=0;i<results.length();i++) {
            JSONObject result=results.getJSONObject(i);String id=result.getString("mutationId"),status=result.getString("status");
            if(!sentWriters.has(id)||!seen.add(id)||!(status.equals("applied")||status.equals("conflict")||status.equals("obsolete")))throw new JSONException("同步应答无效");
            validateRemote(result.getJSONObject("record"));
            boolean matched=false;for(int j=0;j<inFlight.length();j++){JSONObject sent=inFlight.getJSONObject(j);if(id.equals(sent.getString("mutationId"))&&sent.getJSONObject("record").getString("seriesId").equals(result.getJSONObject("record").getString("seriesId")))matched=true;}
            if(!matched)throw new JSONException("同步应答剧集不匹配");
        }
        for(int i=0;i<results.length();i++) {
            JSONObject result=results.getJSONObject(i), row=result.getJSONObject("record");String sid=row.getString("seriesId"), id=result.getString("mutationId"), writer=sentWriters.optString(id);
            if(writer.isEmpty())throw new JSONException("未知同步应答");
            boolean applied="applied".equals(result.getString("status"));if(!applied&&!"conflict".equals(result.getString("status"))&&!"obsolete".equals(result.getString("status")))throw new JSONException("同步应答状态无效");
            JSONObject p=pending.optJSONObject(sid);long remoteRev=row.getLong("revision");
            if(remoteRev>=revision(sid))canonical.put(sid,copy(row));
            if(applied) {
                writers.put(writerKey(writer,sid),remoteRev);
                if(p!=null&&id.equals(p.optString("mutationId")))pending.remove(sid);
                else if(p!=null) {
                    JSONObject sent=null;for(int j=0;j<inFlight.length();j++)if(id.equals(inFlight.getJSONObject(j).getString("mutationId")))sent=inFlight.getJSONObject(j);
                    if(sent!=null&&p.optLong("sequence")>sent.optLong("sequence")&&p.optLong("baseRevision")==sent.optLong("baseRevision")){
                        p.put("baseRevision",remoteRev);writers.put(writerKey(p.getString("writerId"),sid),remoteRev);
                    }
                }
            } else {
                muted.add(writerKey(writer,sid));conflict=true;
                if(p!=null&&writer.equals(p.optString("writerId")))pending.remove(sid);
            }
        }
        inFlight=new JSONArray();inFlightWriters=new JSONObject();
    }
    void pull(JSONArray rows,long nextCursor) throws JSONException {
        if(nextCursor<cursor)throw new JSONException("同步游标倒退");
        Set<String> newIds=new HashSet<>();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);validateRemote(row);if(!canonical.has(row.getString("seriesId")))newIds.add(row.getString("seriesId"));}
        if(canonical.length()+newIds.size()>1000)throw new JSONException("服务器观看记录数量超限");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);String sid=row.getString("seriesId");long rev=row.getLong("revision");
            if(rev>revision(sid))canonical.put(sid,copy(row));
        }
        cursor=nextCursor;
    }
    private static void validateRemote(JSONObject row) throws JSONException {
        for(String field:new String[]{"index","revision","positionMs","updatedAt","episodeCount"}){
            Object value=row.opt(field);if(!(value instanceof Integer)&&!(value instanceof Long))throw new JSONException("观看记录数值类型异常");
        }
        if(row.getLong("positionMs")>604800000L||row.getLong("episodeCount")<0||row.getLong("episodeCount")>10000)throw new JSONException("观看记录数值超限");
        if(!row.getString("seriesId").matches("[0-9]{6,30}")||!row.getString("vid").matches("[0-9]{1,30}")||row.getInt("index")<1||row.getInt("index")>10000||row.getLong("revision")<=0||row.getLong("positionMs")<0||row.getLong("updatedAt")<0)throw new JSONException("观看记录格式异常");
        for(String field:new String[]{"title","coverUrl","description","episodeLabel"})if(!(row.opt(field) instanceof String))throw new JSONException("观看记录字段异常");
        if(row.getString("title").isEmpty()||row.getString("title").length()>200||row.getString("coverUrl").length()>4096||row.getString("description").length()>8000||row.getString("episodeLabel").length()>100||!(row.opt("tags") instanceof JSONArray)||row.getJSONArray("tags").length()>8)throw new JSONException("观看记录字段过长");
        JSONArray tags=row.getJSONArray("tags");for(int i=0;i<tags.length();i++)if(!(tags.get(i) instanceof String)||tags.getString(i).length()>80)throw new JSONException("观看记录标签异常");
    }
    JSONArray rows() throws JSONException {
        Map<String,JSONObject> visible=new HashMap<>();Iterator<String> keys=canonical.keys();while(keys.hasNext()){String sid=keys.next();visible.put(sid,copy(canonical.getJSONObject(sid)));}
        keys=pending.keys();while(keys.hasNext()){String sid=keys.next();JSONObject p=pending.getJSONObject(sid);visible.put(sid,copy(p.getJSONObject("record")).put("updatedAt",p.optLong("localUpdatedAt")));}
        List<JSONObject> rows=new ArrayList<>(visible.values());rows.sort((a,b)->Long.compare(b.optLong("updatedAt"),a.optLong("updatedAt")));
        JSONArray result=new JSONArray();for(int i=0;i<Math.min(100,rows.size());i++)result.put(rows.get(i));return result;
    }
}
