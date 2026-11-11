package io.github.jackotom.hongguo.android;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Consumes actual JS adapter output; production plan parsing/quality/URL checks run unchanged. */
public final class SourceCandidatesContractTest {
    private static int checks, passed, failed;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value,String label) { checks++;if(!value)throw new AssertionError(label); }
    private static void group(String label,Checked test) { try {test.run();passed++;System.out.println("PASS NativeSourceCandidates "+label);}catch(Throwable error){failed++;System.out.println("FAIL NativeSourceCandidates "+label+": "+error.getMessage());} }
    private static JSONObject copy(JSONObject input) throws Exception { return new JSONObject(input.toString()); }
    private static void compare(PlaybackSources.Plan actual,JSONObject expected,String label) throws Exception {
        check(actual.url.equals(expected.getString("url")),label+" URL priority");
        check(Objects.equals(actual.contentKey,expected.isNull("contentKey")?null:expected.getString("contentKey")),label+" own content key");
        check(actual.userAgent.equals(expected.getString("userAgent"))&&actual.referer.equals(expected.getString("referer")),label+" own request headers");
        check(actual.vid.equals(expected.getString("vid"))&&actual.seriesId.equals(expected.optString("seriesId","1234567890123456789")),label+" identity");
        check(actual.requestedQuality.equals(expected.getString("requestedQuality"))&&actual.quality.equals(expected.getString("quality")),label+" quality binding");
        check(actual.width==expected.optInt("width")&&actual.height==expected.optInt("height"),label+" measured dimensions");
        check(actual.source.equals(expected.getString("source")),label+" source type");
    }
    private static void reject(JSONObject input,String expectedMessage) throws Exception {
        boolean rejected=false;
        try {PlaybackSources.register(input,"2345678901234567890",false);}catch(Exception error){rejected=true;if(expectedMessage!=null)check(error.getMessage().contains(expectedMessage),"correct native rejection reason");}
        check(rejected,"invalid plan must be rejected");
    }
    public static void main(String[] args) throws Exception {
        JSONArray cases=new JSONArray(Files.readString(Path.of(args[0])));
        for(int i=0;i<cases.length();i++) {
            JSONObject row=cases.getJSONObject(i);
            group(row.getString("name"),()->{
                JSONObject input=row.getJSONObject("plan"),expected=row.getJSONObject("expected");
                PlaybackSources.Plan actual=PlaybackSources.register(input,"2345678901234567890",false);
                compare(actual,expected,"primary");
                JSONArray variants=expected.getJSONArray("variants");
                check(actual.variants.size()==variants.length()&&actual.variants.size()<=7,"bounded independent backups");
                Set<String> seen=new HashSet<>();seen.add(actual.url);
                for(int j=0;j<variants.length();j++){PlaybackSources.Plan candidate=actual.variants.get(j);compare(candidate,variants.getJSONObject(j),"backup "+j);check(seen.add(candidate.url),"no primary or backup URL repeats");}
            });
        }
        JSONObject good=copy(cases.getJSONObject(0).getJSONObject("expected"));
        group("oversize untrusted bridge list remains rejected",()->{JSONObject invalid=copy(good);JSONArray variants=new JSONArray();for(int i=0;i<9;i++){JSONObject candidate=copy(good);candidate.remove("variants");candidate.put("url","https://cdn.qznovelvod.com/untrusted-"+i+".mp4");variants.put(candidate);}invalid.put("variants",variants);reject(invalid,"备用片源数量无效");});
        group("wrong primary vid remains rejected",()->{JSONObject invalid=copy(good).put("vid","999");reject(invalid,"片源与分集不一致");});
        group("foreign CDN and credential-bearing URL remain rejected",()->{for(String url:new String[]{"https://foreign.example.net/video.mp4","https://user:password@cdn.qznovelvod.com/video.mp4"})reject(copy(good).put("url",url),null);});
        group("malformed primary key and header remain rejected",()->{reject(copy(good).put("contentKey","bad-key"),"媒体准备参数无效");reject(copy(good).put("userAgent","agent\r\nInjected: value"),"片源请求头无效");});
        group("wrong backup series, vid, source and requested quality remain rejected",()->{for(String[] change:new String[][]{{"seriesId","999"},{"vid","999"},{"source","web"},{"requestedQuality","720p"}}){JSONObject candidate=copy(good);candidate.remove("variants");candidate.put("url","https://cdn.qznovelvod.com/malformed-backup.mp4").put(change[0],change[1]);reject(copy(good).put("variants",new JSONArray().put(candidate)),null);}});
        group("explicit insufficient dimensions never register",()->{JSONObject invalid=copy(good).put("requestedQuality","1080p").put("width",360).put("height",640);reject(invalid,"1080p");});
        System.out.println("NATIVE_SOURCE_CANDIDATES groupsPassed="+passed+" groupsFailed="+failed+" checks="+checks+" networkRequests=0 deviceTests=not_run");
        if(failed>0)System.exit(1);
    }
}
