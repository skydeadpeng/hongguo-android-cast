package io.github.jackotom.hongguo.android;

import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.util.zip.GZIPOutputStream;import org.json.*;

/** Real registration/signing/model class with synthetic transport. Never sends SMS or opens sockets. */
public final class RegisteredPlaybackModelTest {
 static final JSONArray projectedFlags=new JSONArray();
 static int checks,groups; static final String VID="7694655440692448281",DID="7390000000000000001",IID="7390000000000000002";
 interface Action {void run()throws Exception;}
 static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
 static Exception reject(Action action)throws Exception{try{action.run();}catch(Exception expected){checks++;return expected;}throw new AssertionError("invalid operation accepted");}
 static void group(String title,Action action)throws Exception{try{action.run();groups++;System.out.println("PASS "+title);}finally{Thread.interrupted();}}
 static OfficialApiClient.Response response(int status,String value)throws Exception{return new OfficialApiClient.Response(status,Collections.emptyMap(),value.getBytes(StandardCharsets.UTF_8));}
 static OfficialApiClient.Response registration()throws Exception{return response(200,"{\"device_id\":"+DID+",\"install_id\":"+IID+"}");}
 static JSONObject model()throws Exception{return new JSONObject().put("video_duration",120).put("video_list",new JSONArray().put(new JSONObject().put("main_url","https://cdn.qznovelvod.com/fixture.mp4").put("video_meta",new JSONObject().put("codec_type","bytevc1").put("vwidth",720).put("vheight",1280)).put("encrypt_info",new JSONObject().put("spade_a","fixture-media-metadata"))));}
 static String envelope(JSONObject model)throws Exception{return new JSONObject().put("code",0).put("data",new JSONObject().put(VID,new JSONObject().put("video_model",model))).toString();}
 static Map<String,String> query(String url)throws Exception{Map<String,String>out=new LinkedHashMap<>();for(String row:new URI(url).getRawQuery().split("&")){String[]p=row.split("=",2);out.put(URLDecoder.decode(p[0],"UTF-8"),URLDecoder.decode(p.length>1?p[1]:"","UTF-8"));}return out;}
 static RegisteredPlaybackModel operation(OfficialApiClient.Transport registration,OfficialApiClient.Transport model){return new RegisteredPlaybackModel(registration,model,System.nanoTime()+20_000_000_000L);}
 static byte[] gzip(byte[]raw)throws Exception{ByteArrayOutputStream output=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(output)){gzip.write(raw);}return output.toByteArray();}
 public static void main(String[]args)throws Exception{
  group("registered identity precedes current per-episode signed video_tab request",()->{
   List<OfficialApiClient.Request> requests=new ArrayList<>();AtomicInteger bootstrap=new AtomicInteger();long[] deadline={0};
   RegisteredPlaybackModel op=operation(r->{requests.add(r);bootstrap.incrementAndGet();deadline[0]=r.deadlineNanos;check(new URI(r.url).getHost().equals("log.snssdk.com"),"only anonymous official register first");check(!r.headers.containsKey("Cookie")&&!r.headers.containsKey("X-Tt-Token"),"registration has no account credentials");check(OfficialApiClient.parse(PassportDevice.decrypt(r.body)).has("header"),"real encrypted registration payload");return registration();},r->{
    requests.add(r);check(bootstrap.get()==1,"register exactly once before model");check(new URI(r.url).getHost().equals("api5-normal-sinfonlineb.fqnovel.com")&&new URI(r.url).getPath().equals("/reading/bookapi/video_tab/multi_video_model/v1/")&&r.method.equals("GET")&&r.body.length==0,"current per-episode tab target first");check(r.deadlineNanos==deadline[0],"registration and model share absolute deadline");
    Map<String,String> q=query(r.url);check(DID.equals(q.get("device_id"))&&IID.equals(q.get("iid")),"only registered response identity signs model");check(!q.containsKey("cdid")&&!q.containsKey("openudid"),"probe profile excludes extra passport fields");check(q.get("version_code").equals("73932")&&q.get("aid").equals("8662")&&VID.equals(q.get("video_id"))&&q.containsKey("ts")&&q.containsKey("_rticket"),"tab profile and per-episode query exact");
    for(String header:new String[]{"X-Khronos","X-Ladon","X-Argus","X-SS-Req-Ticket"})check(r.headers.get(header)!=null&&!r.headers.get(header).isEmpty(),"full signing header "+header);
    for(String retired:new String[]{"X-Helios","X-Medusa","X-Gorgon"})check(!r.headers.containsKey(retired),"placeholder header retired: "+retired);
    check(r.headers.get("X-SS-Req-Ticket").equals(Long.toString(Long.parseLong(q.get("ts"))*1000)),"signed ticket derives from signed ts");check(!r.headers.containsKey("Cookie")&&!r.headers.containsKey("X-Tt-Token"),"anonymous model never copies registration/account cookies");return response(200,envelope(model()));});
   JSONObject result=op.fetch(VID);check(requests.size()==2&&result.length()==2&&result.getString("vid").equals(VID),"one registration plus one tab model only");check(result.getJSONObject("video_model").getJSONArray("video_list").length()==1,"real model projected");check(reject(()->op.fetch(VID)) instanceof IOException,"one lookup instance cannot reuse consumed identity");
  });
  group("hollowed tab recovers once through the same identity's legacy signed POST",()->{
   AtomicInteger registrations=new AtomicInteger();List<OfficialApiClient.Request> requests=new ArrayList<>();
   RegisteredPlaybackModel op=operation(r->{requests.add(r);registrations.incrementAndGet();return registration();},r->{
    requests.add(r);
    if(r.method.equals("GET"))return response(200," ");
    check(new URI(r.url).getPath().equals("/novel/player/multi_video_model/v1/"),"legacy POST recovers hollowed tab");
    try(java.io.ByteArrayInputStream bytes=new java.io.ByteArrayInputStream(r.body);java.util.zip.GZIPInputStream in=new java.util.zip.GZIPInputStream(bytes)){check(new String(in.readAllBytes(),StandardCharsets.UTF_8).equals(new JSONObject().put("biz_param",new JSONObject().put("detail_page_version",0).put("device_level",3).put("disable_digg_stat",false).put("need_all_video_definition",true).put("need_mp4_align",false).put("use_os_player",false).put("use_server_dns",false).put("video_platform",1024)).put("mixed_video_id_map",new JSONObject().put("1004",new org.json.JSONArray().put(VID))).toString()),"successful probe body byte exact after gzip");}
    Map<String,String> q=query(r.url);check(DID.equals(q.get("device_id"))&&IID.equals(q.get("iid")),"recovery POST signs with the same registered identity");check(q.get("version_code").equals("74332")&&q.get("video_id")==null,"legacy POST profile exact");
    for(String header:new String[]{"X-Khronos","X-Ladon","X-Argus","X-SS-Req-Ticket","X-SS-STUB"})check(r.headers.get(header)!=null&&!r.headers.get(header).isEmpty(),"full signing header "+header);
    for(String retired:new String[]{"X-Helios","X-Medusa","X-Gorgon"})check(!r.headers.containsKey(retired),"placeholder header retired: "+retired);
    check(r.headers.get("X-SS-STUB").equalsIgnoreCase(DanmakuSigner.hex(DanmakuSigner.md5(r.body))),"signature hashes actual model body");check("close".equalsIgnoreCase(r.headers.get("Connection")),"recovery POST closes the connection");check(!r.headers.containsKey("Cookie")&&!r.headers.containsKey("X-Tt-Token"),"anonymous model never copies registration/account cookies");return response(200,envelope(model()));});
   JSONObject result=op.fetch(VID);check(registrations.get()==1&&requests.size()==3,"register, tab, POST within one identity");check(result.getJSONObject("video_model").getJSONArray("video_list").length()==1,"recovered model projected");
  });
  group("refused identity is replaced in memory until a model arrives",()->{
   AtomicInteger models=new AtomicInteger(),registrations=new AtomicInteger();
   JSONObject result=operation(r->{registrations.incrementAndGet();return registration();},r->{int call=models.incrementAndGet();return response(200,call<=2?" ":envelope(model()));}).fetch(VID);
   check(models.get()==3&&registrations.get()==2,"two forms on the first identity, then one tab on the fresh one");check(result.getJSONObject("video_model").getJSONArray("video_list").length()==1,"rotated identity model projected");
  });
  group("HTTP refusals recover once across the two forms and then fail typed",()->{
   check(operation(r->registration(),r->r.method.equals("GET")?response(404,""):response(200,envelope(model()))).fetch(VID).has("video_model"),"tab HTTP refusal recovered by legacy POST");
   AtomicInteger both=new AtomicInteger();
   Exception failure=reject(()->operation(r->registration(),r->{both.incrementAndGet();return response(404,"");}).fetch(VID));
   check(failure instanceof RegisteredPlaybackModel.ModelError&&((RegisteredPlaybackModel.ModelError)failure).code.equals("HTTP_ERROR"),"dual refusal stays typed");
   check(both.get()==6,"each form tried once per identity, at most three identities");
  });
  group("only media projection reaches RPC and source objects are deeply isolated",()->{
   JSONObject source=model();source.put("device_id",DID).put("token","private-token").put("cookies",new JSONObject().put("sessionid","private-session"));JSONObject row=source.getJSONArray("video_list").getJSONObject(0);row.put("install_id",IID);row.getJSONObject("video_meta").put("session","private-meta");row.getJSONObject("encrypt_info").put("token","private-encryption-token");
   RegisteredPlaybackModel op=operation(r->registration(),r->response(200,envelope(source)));JSONObject result=op.fetch(VID);String text=result.toString();check(!text.contains(DID)&&!text.contains(IID)&&!text.contains("private-")&&!text.contains("device_id")&&!text.contains("session")&&!text.contains("cookies")&&!text.contains("token"),"identity credentials and unrelated model fields discarded");
   check(result.getJSONObject("video_model").getJSONArray("video_list").getJSONObject(0).getJSONObject("encrypt_info").getString("spade_a").equals("fixture-media-metadata"),"media preparation information retained");source.getJSONArray("video_list").getJSONObject(0).put("main_url","mutated");check(!result.toString().contains("mutated"),"result has no mutable source alias");
  });
  group("explicit clear and encrypted flags survive real fetch projection without coercion",()->{
   for(Object flag:new Object[]{false,0,"0","false",true,1,"1","true",JSONObject.NULL,"", "False",2}){
    JSONObject m=model();JSONObject encryption=m.getJSONArray("video_list").getJSONObject(0).getJSONObject("encrypt_info");encryption.remove("spade_a");encryption.put("encrypt",flag).put("token","private-flag-token").put("device_id",DID);
    JSONObject result=operation(r->registration(),r->response(200,envelope(m))).fetch(VID);
    projectedFlags.put(new JSONObject().put("clear",Boolean.FALSE.equals(flag)||Integer.valueOf(0).equals(flag)||"0".equals(flag)||"false".equals(flag)).put("reply",result));
    JSONObject clean=result.getJSONObject("video_model").getJSONArray("video_list").getJSONObject(0).getJSONObject("encrypt_info");
    check(clean.has("encrypt"),"explicit encrypt flag must survive projection: "+flag);
    Object actual=clean.get("encrypt");check(flag instanceof Number ? actual instanceof Number&&((Number)actual).doubleValue()==((Number)flag).doubleValue() : actual.getClass()==flag.getClass()&&actual.equals(flag),"encrypt flag retains JSON scalar type and value: "+flag);
    check(clean.length()==1&&!result.toString().contains("private-")&&!result.toString().contains(DID),"retaining encrypt never exposes unrelated sensitive fields");
   }
   for(boolean includeInfo:new boolean[]{false,true}){
    JSONObject m=model();JSONObject row=m.getJSONArray("video_list").getJSONObject(0);if(includeInfo)row.put("encrypt_info",new JSONObject());else row.remove("encrypt_info");
    JSONObject result=operation(r->registration(),r->response(200,envelope(m))).fetch(VID);
    projectedFlags.put(new JSONObject().put("clear",false).put("reply",result));
    JSONObject clean=result.getJSONObject("video_model").getJSONArray("video_list").getJSONObject(0);
    check(includeInfo? !clean.getJSONObject("encrypt_info").has("encrypt"):!clean.has("encrypt_info"),"missing encryption flag is never invented as clear");
   }
   for(Object bad:new Object[]{new JSONObject().put("encrypt",false),new JSONArray().put(false),"0".repeat(16385)}){
    JSONObject m=model();m.getJSONArray("video_list").getJSONObject(0).getJSONObject("encrypt_info").put("encrypt",bad);
    Exception failure=reject(()->operation(r->registration(),r->response(200,envelope(m))).fetch(VID));
    check(failure instanceof RegisteredPlaybackModel.ModelError&&((RegisteredPlaybackModel.ModelError)failure).code.equals("APP_INVALID_RESPONSE"),"non-scalar or oversized flag rejected, never coerced to clear");
   }
  });
  group("trial markers fail closed at envelope data and model before projection",()->{
   for(String location:new String[]{"envelope","data","model"})for(Object flag:new Object[]{true,1,"false",JSONObject.NULL,false}){
    JSONObject body=new JSONObject(envelope(model()));JSONObject target=location.equals("envelope")?body:location.equals("data")?body.getJSONObject("data"):body.getJSONObject("data").getJSONObject(VID).getJSONObject("video_model");
    // android-json keeps JSONObject.NULL on put (unlike upstream): inject explicit null textually.
    if(flag==JSONObject.NULL)body=new JSONObject(body.toString().replaceFirst("\\}$",",\"is_trial_video\":null}"));
    else target.put("is_trial_video",flag);
    final JSONObject trialBody=body;
    AtomicInteger registrations=new AtomicInteger(),requests=new AtomicInteger();RegisteredPlaybackModel op=operation(r->{registrations.incrementAndGet();return registration();},r->{requests.incrementAndGet();return response(200,trialBody.toString());});
    if(Boolean.FALSE.equals(flag)){check(op.fetch(VID).has("video_model"),"explicit non-trial marker succeeds at "+location);}
    else {Exception failure=reject(()->op.fetch(VID));check(failure instanceof RegisteredPlaybackModel.ModelError&&((RegisteredPlaybackModel.ModelError)failure).code.equals(Boolean.TRUE.equals(flag)?"BUSINESS_REJECTED":"APP_INVALID_RESPONSE"),"trial or malformed marker never publishes model at "+location+": "+flag);}
    check(registrations.get()==1&&requests.get()==1,"trial handling never retries registration or model");
   }
  });
  group("string JSON and object-keyed quality models normalize without fallback network",()->{for(boolean text:new boolean[]{false,true}){JSONObject m=model();if(!text)m.put("video_list",new JSONObject().put("video_1",m.getJSONArray("video_list").get(0)));Object value=text?m.toString():m;String body=new JSONObject().put("code","0").put("data",new JSONObject().put(VID,new JSONObject().put("video_model",value))).toString();JSONObject result=operation(r->registration(),r->response(200,body)).fetch(VID);check(result.getJSONObject("video_model").getJSONArray("video_list").length()==1,"either upstream quality shape supported");}});
  group("VOD video_id is a distinct namespace and never mistaken for item ID",()->{JSONObject m=model().put("video_id","v0300f0000fictionalvodidentity").put("vid","v0vodmeta");JSONObject value=operation(r->registration(),r->response(200,envelope(m))).fetch(VID);check(value.getString("vid").equals(VID)&&!value.toString().contains("fictionalvodidentity"),"actual request vid binds RPC independently of VOD metadata");});
  group("parameters only accept one string vid before any registration",()->{for(JSONObject bad:new JSONObject[]{null,new JSONObject(),new JSONObject().put("vid",12),new JSONObject().put("vid","../"),new JSONObject().put("vid",VID).put("url","https://else.invalid/"),new JSONObject().put("vid","1".repeat(31))}){check(reject(()->RegisteredPlaybackModel.requestedVid(bad)) instanceof RegisteredPlaybackModel.ModelError,"invalid RPC shape rejected");}check(RegisteredPlaybackModel.requestedVid(new JSONObject().put("vid",VID)).equals(VID),"numeric string only accepted");});
  group("registration failure never makes a model request or invents IDs",()->{for(String bad:new String[]{"{}","{\"device_id\":0,\"install_id\":1}","{\"device_id\":7,\"device_id_str\":\"8\",\"install_id\":9}"}){AtomicInteger calls=new AtomicInteger();reject(()->operation(r->response(200,bad),r->{calls.incrementAndGet();return response(200,envelope(model()));}).fetch(VID));check(calls.get()==0,"failed registered identity blocks model");}AtomicInteger calls=new AtomicInteger();reject(()->operation(r->{throw new IOException("fixture registration failure");},r->{calls.incrementAndGet();return registration();}).fetch(VID));check(calls.get()==0,"network registration failure blocks model");});
  group("business denials missing status invalid JSON and contradictory identity remain failures",()->{
   List<String> bad=new ArrayList<>(Arrays.asList("{}","[]","{bad}","{\"code\":1,\"data\":{}}","{\"code\":false,\"data\":{}}","{\"code\":0,\"error_code\":1,\"data\":{}}","{\"code\":0,\"data\":{\"error_code\":1}}"));
   for(String key:new String[]{"item_id","episode_id"}){bad.add(new JSONObject().put("code",0).put(key,"123").put("data",new JSONObject().put("video_model",model())).toString());bad.add(envelope(model().put(key,"123")));}
   for(String body:bad)check(reject(()->operation(r->registration(),r->response(200,body)).fetch(VID)) instanceof RegisteredPlaybackModel.ModelError,"invalid response cannot appear successful");
   for(int status:new int[]{301,302,307,403,500}){AtomicInteger calls=new AtomicInteger();check(reject(()->operation(r->registration(),r->{calls.incrementAndGet();return response(status,envelope(model()));}).fetch(VID)) instanceof RegisteredPlaybackModel.ModelError,"non200 refused");check(calls.get()==6,"each form tried once per identity for an HTTP refusal, never more");}
  });
  group("empty and oversized raw or gzip model bodies have precise bounded failures",()->{
   Exception empty=reject(()->operation(r->registration(),r->response(200,"  ")).fetch(VID));check(empty instanceof RegisteredPlaybackModel.ModelError&&((RegisteredPlaybackModel.ModelError)empty).code.equals("APP_EMPTY_RESPONSE"),"empty model typed not success");
   byte[] huge=new byte[RegisteredPlaybackModel.MAX_BYTES+1];check(reject(()->operation(r->registration(),r->new OfficialApiClient.Response(200,Collections.emptyMap(),huge)).fetch(VID)) instanceof RegisteredPlaybackModel.ModelError,"raw bound rejects before parse");byte[] packed=gzip(huge);check(reject(()->operation(r->registration(),r->new OfficialApiClient.Response(200,Collections.singletonMap("Content-Encoding",Arrays.asList("gzip")),packed)).fetch(VID)) instanceof RegisteredPlaybackModel.ModelError,"gzip expansion bounded too");
   byte[] normal=gzip(envelope(model()).getBytes(StandardCharsets.UTF_8));check(operation(r->registration(),r->new OfficialApiClient.Response(200,Collections.singletonMap("Content-Encoding",Arrays.asList("gzip")),normal)).fetch(VID).has("video_model"),"bounded gzip actual model accepted");
  });
  group("shared total deadline blocks late registration and model responses",()->{
   AtomicInteger models=new AtomicInteger();long stop=System.nanoTime()+25_000_000L;RegisteredPlaybackModel lateRegister=new RegisteredPlaybackModel(r->{Thread.sleep(40);return registration();},r->{models.incrementAndGet();return response(200,envelope(model()));},stop);check(reject(()->lateRegister.fetch(VID)) instanceof SocketTimeoutException&&models.get()==0,"late registration never starts model stage");
   stop=System.nanoTime()+25_000_000L;RegisteredPlaybackModel lateModel=new RegisteredPlaybackModel(r->registration(),r->{Thread.sleep(40);return response(200,envelope(model()));},stop);check(reject(()->lateModel.fetch(VID)) instanceof SocketTimeoutException,"late model never publishes result");
   AtomicInteger all=new AtomicInteger();RegisteredPlaybackModel expired=new RegisteredPlaybackModel(r->{all.incrementAndGet();return registration();},r->{all.incrementAndGet();return registration();},System.nanoTime()-1);check(reject(()->expired.fetch(VID)) instanceof SocketTimeoutException&&all.get()==0,"already expired budget sends zero network requests");
  });
  group("cancel closes each stage and rejects deliberately late successful replies",()->{
   for(boolean duringModel:new boolean[]{false,true}){CountDownLatch entered=new CountDownLatch(1),released=new CountDownLatch(1);AtomicInteger modelCalls=new AtomicInteger();AtomicReference<Throwable> result=new AtomicReference<>();AtomicBoolean transportClosed=new AtomicBoolean();OfficialApiClient.Transport blocking=new OfficialApiClient.Transport(){public OfficialApiClient.Response execute(OfficialApiClient.Request r)throws Exception{entered.countDown();while(released.getCount()>0)try{released.await();}catch(InterruptedException ignored){}return duringModel?response(200,envelope(model())):registration();}public void close(){transportClosed.set(true);released.countDown();}};
    RegisteredPlaybackModel op=operation(duringModel?r->registration():blocking,duringModel?blocking:r->{modelCalls.incrementAndGet();return response(200,envelope(model()));});Thread worker=new Thread(()->{try{op.fetch(VID);result.set(new AssertionError("late success"));}catch(Throwable failure){result.set(failure);}});worker.start();check(entered.await(2,TimeUnit.SECONDS),"stage reached");op.close();worker.join(2000);check(!worker.isAlive()&&result.get() instanceof InterruptedIOException&&transportClosed.get(),"cancel promptly closes real operation boundary and rejects late body");check(modelCalls.get()==0&&!op.current(),"canceled registration never reaches model and operation inactive");
   }
  });
  group("fixed model transport rejects alternate host path scheme auth port method before network",()->{for(String url:new String[]{"http://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/","https://evil.invalid/novel/player/video_model/v1/","https://api5-normal-sinfonlineb.fqnovel.com/reading/user/info/v1/","https://a@api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/","https://api5-normal-sinfonlineb.fqnovel.com:443/novel/player/video_model/v1/","https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/#x"})reject(()->RegisteredPlaybackModel.ModelHttpTransport.validate(new OfficialApiClient.Request(url,"POST",Collections.emptyMap(),new byte[0],Long.MAX_VALUE)));reject(()->RegisteredPlaybackModel.ModelHttpTransport.validate(new OfficialApiClient.Request(RegisteredPlaybackModel.API,"GET",Collections.emptyMap(),new byte[0],Long.MAX_VALUE)));check(RegisteredPlaybackModel.ModelHttpTransport.validate(new OfficialApiClient.Request(RegisteredPlaybackModel.API+"?x=1","POST",Collections.emptyMap(),new byte[0],Long.MAX_VALUE)).getHost().equals("api5-normal-sinfonlineb.fqnovel.com"),"only intended fixed model target accepted");});
  if(args.length==1)java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),projectedFlags.toString().getBytes(StandardCharsets.UTF_8));
  System.out.println("REGISTERED_PLAYBACK_MODEL_OK groups="+groups+" checks="+checks+" actualRegistrationAndSigner=true oneSharedDeadline=true projectedModelOnly=true networkRequests=0");
 }
}
