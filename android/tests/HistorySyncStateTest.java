package io.github.jackotom.hongguo.android;

import org.json.*;
import java.util.*;

public final class HistorySyncStateTest {
    static int checks,groups;
    interface Checked {void run()throws Exception;}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void group(String name,Checked run)throws Exception {run.run();groups++;System.out.println("PASS "+name);}
    static void rejects(Checked run)throws Exception {try{run.run();}catch(Exception expected){checks++;return;}throw new AssertionError("expected rejection");}
    static JSONObject row(String sid,int index,long position)throws Exception {return new JSONObject().put("seriesId",sid).put("title","持械入宋").put("coverUrl","").put("description","").put("episodeCount",68).put("episodeLabel","共68集").put("tags",new JSONArray()).put("vid",String.valueOf(10000+index)).put("index",index).put("positionMs",position).put("updatedAt",position+1000);}
    static JSONObject remote(String sid,int index,long position,long revision)throws Exception{return row(sid,index,position).put("revision",revision);}
    static JSONArray result(JSONArray sent,String status,JSONObject row)throws Exception{return new JSONArray().put(new JSONObject().put("mutationId",sent.getJSONObject(0).getString("mutationId")).put("status",status).put("record",row));}
    static void ack(HistorySyncState s,JSONArray batch,String status,JSONObject row)throws Exception{s.acknowledge(result(batch,status,row),s.sentWriters(batch));}
    public static void main(String[]args)throws Exception {
        group("strict fixed HTTPS origins",()->{
            check(AccountEndpoint.normalize("HTTPS://Example.COM:443/").equals("https://example.com"),"normalizes root/default port");
            check(AccountEndpoint.normalize("https://192.168.1.5:9443").equals("https://192.168.1.5:9443"),"custom server IP and port allowed with standard TLS");
            for(String url:new String[]{"http://example.com","https://user:pass@example.com","https://example.com/path","https://example.com?x=1","https://example.com#fragment","https://example.com:","https://example.com:0","https://example.com:65536"," https://example.com","https://example.com\\evil","//example.com"})rejects(()->AccountEndpoint.normalize(url));
        });
        group("local writes persist and preserve intentional seek/rewatch",()->{
            HistorySyncState s=new HistorySyncState("{}");s.record("player",row("123456",9,50000));s.record("player",row("123456",1,2000));
            HistorySyncState reopen=new HistorySyncState(s.encode());check(reopen.pendingCount()==1,"coalesces per actual season");check(reopen.rows().getJSONObject(0).getInt("index")==1&&reopen.rows().getJSONObject(0).getLong("positionMs")==2000,"never max-index/max-position merge");
            JSONArray batch=reopen.batch(10);check(!batch.getJSONObject(0).getJSONObject("record").has("updatedAt"),"server fields excluded");check(batch.getJSONObject(0).getLong("sequence")==2,"durable monotonic sequence");
        });
        group("immutable retry survives new ticks lost acknowledgement and restart",()->{
            HistorySyncState s=new HistorySyncState("{}");s.record("before-restart",row("123456",9,10000));JSONArray first=s.batch(10);String wire=first.toString();
            s.record("before-restart",row("123456",9,20000));check(s.batch(10).toString().equals(wire),"new tick cannot replace attempted request");
            s=new HistorySyncState(s.encode());s.record("after-restart",row("123456",9,30000));check(s.batch(10).toString().equals(wire),"restart retries byte-identical id/sequence/base/body");
            ack(s,first,"applied",remote("123456",9,10000,1));JSONArray second=s.batch(10);
            check(second.getJSONObject(0).getLong("baseRevision")==1,"own prior committed mutation causally rebases never-sent replacement");
            check(second.getJSONObject(0).getJSONObject("record").getLong("positionMs")==30000,"late ack cannot roll back newer local progress");
            check(second.getJSONObject(0).getLong("sequence")==3,"replay preserves monotonic sequence");
            ack(s,second,"applied",remote("123456",9,30000,2));check(s.pendingCount()==0,"eventually acknowledged after lost response");
            check(s.record("after-restart",row("123456",9,31000)),"current writer remains active after own lost reply");
        });
        group("other-device CAS conflict wins and suppresses stale playback writer",()->{
            HistorySyncState s=new HistorySyncState("{}");s.record("old-player",row("123456",8,50000));JSONArray sent=s.batch(10);
            s.record("old-player",row("123456",8,51000));ack(s,sent,"conflict",remote("123456",12,1234,7));
            check(s.pendingCount()==0&&s.rows().getJSONObject(0).getInt("index")==12,"remote wins conflicting old and replacement event");
            check(!s.record("old-player",row("123456",8,52000))&&s.conflicted(),"continuous stale ticks never rebase blindly");
            check(s.record("new-explicit-play",row("123456",1,0)),"new playback intent can rewatch");check(s.batch(10).getJSONObject(0).getLong("baseRevision")==7,"fresh writer bases on known remote revision");
        });
        group("pull does not rebase offline edits or overwrite their visible local row",()->{
            HistorySyncState s=new HistorySyncState("{}");s.record("offline",row("123456",2,1000));s.pull(new JSONArray().put(remote("123456",4,7000,8)),8);
            check(s.rows().getJSONObject(0).getInt("index")==2,"pending stays local until resolved");check(s.batch(10).getJSONObject(0).getLong("baseRevision")==0,"pull cannot change stale mutation base");
            check(!s.record("offline",row("123456",2,2000)),"already active writer blocked by higher remote revision");
        });
        group("global revisions paginate independently of write acknowledgements",()->{
            HistorySyncState s=new HistorySyncState("{}");s.pull(new JSONArray().put(remote("123456",1,1000,5)),5);s.record("writer",row("654321",1,2000));JSONArray b=s.batch(10);ack(s,b,"applied",remote("654321",1,2000,30));
            check(s.cursor()==5,"write ack must not skip other-device unread rows");s.pull(new JSONArray().put(remote("777777",1,3000,21)).put(remote("654321",1,2000,30)),30);check(s.rows().length()==3&&s.cursor()==30,"next page retains intervening remote records");rejects(()->s.pull(new JSONArray(),29));
        });
        group("server/account states remain isolated and five-cache fields never enter protocol",()->{
            HistorySyncState a=new HistorySyncState("{}"),b=new HistorySyncState("{}");JSONObject local=row("123456",1,1234).put("preparedFile","secret.mp4").put("sourceUrl","https://cdn/token").put("password","not-uploaded");a.record("a",local);
            check(b.rows().length()==0,"separate account state empty");JSONObject sent=a.batch(10).getJSONObject(0).getJSONObject("record");check(!sent.has("preparedFile")&&!sent.has("sourceUrl")&&!sent.has("password"),"allowlist only history metadata/progress");
        });
        group("season identity and display bound retained",()->{
            HistorySyncState s=new HistorySyncState("{}");for(int i=0;i<105;i++)s.record("writer",row(String.valueOf(100000+i),1,i));check(s.rows().length()==100&&s.pendingCount()==105,"display bound does not drop unsynced rows");
            JSONArray batch=s.batch(10);check(batch.length()==10,"wire batch bound10");long prior=0;for(int i=0;i<batch.length();i++){long seq=batch.getJSONObject(i).getLong("sequence");check(seq>prior,"ascending sequences");prior=seq;}
        });
        group("obsolete receipts never reapply old progress",()->{
            HistorySyncState s=new HistorySyncState("{}");s.record("old",row("123456",1,200));JSONArray b=s.batch(10);ack(s,b,"obsolete",remote("123456",20,10000,99));check(s.pendingCount()==0&&s.rows().getJSONObject(0).getInt("index")==20&&!s.record("old",row("123456",1,300)),"pruned receipt is conflict, not retry-as-new");
        });
        group("malformed partial and mismatched acknowledgements do not mutate state",()->{
            HistorySyncState s=new HistorySyncState("{}");s.record("writer",row("123456",1,500));JSONArray b=s.batch(10);String before=s.encode();rejects(()->s.acknowledge(new JSONArray(),s.sentWriters(b)));rejects(()->ack(s,b,"applied",remote("654321",1,500,1)));rejects(()->ack(s,b,"mystery",remote("123456",1,500,1)));check(s.encode().equals(before),"validation precedes all mutations");
        });
        group("checkpoint preserves runtime writer conflict state on persistence failure",()->{
            HistorySyncState s=new HistorySyncState("{}");s.record("writer",row("123456",1,500));JSONArray b=s.batch(10);ack(s,b,"conflict",remote("123456",3,900,2));HistorySyncState copy=s.copyState();check(!copy.record("writer",row("123456",1,600))&&copy.conflicted(),"checkpoint includes muted writers, not only JSON");
        });
        group("explicit guest import skip predicate sees local and server records",()->{
            HistorySyncState s=new HistorySyncState("{}");s.pull(new JSONArray().put(remote("123456",5,5000,2)),2);s.record("writer",row("654321",2,100));check(s.contains("123456")&&s.contains("654321")&&!s.contains("999999"),"import only fills absent series");
        });
        group("capacity counts unique series and fails explicitly at 1000",()->{
            HistorySyncState s=new HistorySyncState("{}");JSONArray page=new JSONArray();for(int i=0;i<999;i++)page.put(remote(String.valueOf(100000+i),1,100,i+1));s.pull(page,999);s.record("writer",row("100000",2,200));check(s.record("writer",row("999999",1,0)),"same-series pending must not double count");rejects(()->s.record("writer",row("888888",1,0)));check(s.pendingCount()==2,"capacity exception does not silently evict old outbox");
        });
        group("remote numeric protocol validation precedes any merge",()->{
            HistorySyncState s=new HistorySyncState("{}");for(String field:new String[]{"index","positionMs","updatedAt","episodeCount","revision"}){JSONObject bad=remote("123456",1,100,1).put(field,"1");rejects(()->s.pull(new JSONArray().put(bad),1));}
            rejects(()->s.pull(new JSONArray().put(remote("123456",1,604800001L,1)),1));check(s.rows().length()==0&&s.cursor()==0,"bad remote values never poison state/cursor");
        });
        System.out.println("ACCOUNT_SYNC_STATE_OK groups="+groups+" checks="+checks+" network=fixture deviceKeystore=not_run");
    }
}
