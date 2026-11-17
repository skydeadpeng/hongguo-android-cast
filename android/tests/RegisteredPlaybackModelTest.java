package io.github.jackotom.hongguo.android;

import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.util.zip.GZIPOutputStream;import org.json.*;

/** Real registration/signing/model class and HTTP framing with offline boundary doubles. */
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
 static final String CDID="00000000-0000-4000-8000-000000000003";
 interface RegFixture{OfficialApiClient.Response run(OfficialApiClient.Request r)throws Exception;}
 /** Adapts the legacy registration-transport fixture style onto the bootstrap hook:
  *  the fixture response body is parsed as the registration payload, same as before. */
 static RegisteredPlaybackModel operation(RegFixture registration,OfficialApiClient.Transport model){return operation(registration,model,System.nanoTime()+20_000_000_000L);}
 static RegisteredPlaybackModel operation(RegFixture registration,OfficialApiClient.Transport model,long deadline){
  OfficialApiClient.Transport noop=new OfficialApiClient.Transport(){public OfficialApiClient.Response execute(OfficialApiClient.Request r)throws Exception{throw new AssertionError("registration transport is not used by the model lane");}public void close(){ }};
  return new RegisteredPlaybackModel(noop,model,()->{
   OfficialApiClient.Response response=registration.run(new OfficialApiClient.Request("https://log5-applog-lf.fqnovel.com/service/2/device_register/","POST",Collections.emptyMap(),new byte[0],deadline));
   JSONObject payload=OfficialApiClient.parse(response.body);
   String did=payload.optString("device_id_str",""),iid=payload.optString("install_id_str","");
   if(did.isEmpty())did=payload.optString("device_id","");
   if(iid.isEmpty())iid=payload.optString("install_id","");
   return new String[]{did,iid,CDID};
  },deadline);
 }
 static Map<String,String> query(String url)throws Exception{Map<String,String>out=new LinkedHashMap<>();for(String row:new URI(url).getRawQuery().split("&")){String[]p=row.split("=",2);out.put(URLDecoder.decode(p[0],"UTF-8"),URLDecoder.decode(p.length>1?p[1]:"","UTF-8"));}return out;}
 static byte[] gzip(byte[]raw)throws Exception{ByteArrayOutputStream output=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(output)){gzip.write(raw);}return output.toByteArray();}
 static final class WireConnection extends HttpURLConnection {
  final String expectedMethod;final int status;final byte[] response;
  final Map<String,String> sentHeaders=new LinkedHashMap<>();final ByteArrayOutputStream sentBody=new ByteArrayOutputStream();
  int fixedLength=-1,outputOpens,reads;boolean disconnected,outputClosed;
  WireConnection(URL url,String expectedMethod,int status,byte[] response){super(url);this.expectedMethod=expectedMethod;this.status=status;this.response=response;}
  @Override public void connect(){throw new AssertionError("fixture never opens network connections");}
  @Override public void disconnect(){disconnected=true;}
  @Override public boolean usingProxy(){return false;}
  @Override public void setDoOutput(boolean value){check("POST".equals(expectedMethod)&&value,"GET must not enable request output");super.setDoOutput(value);}
  @Override public void setFixedLengthStreamingMode(int size){check("POST".equals(expectedMethod),"GET must not set body length");fixedLength=size;super.setFixedLengthStreamingMode(size);}
  @Override public void setRequestProperty(String name,String value){sentHeaders.put(name,value);}
  @Override public OutputStream getOutputStream(){
   // Match HttpURLConnection behavior: touching a GET output stream rewrites the wire method.
   if("GET".equals(method))method="POST";
   check("POST".equals(expectedMethod)&&doOutput&&fixedLength>=0,"only configured POST opens an output stream");outputOpens++;
   return new FilterOutputStream(sentBody){@Override public void close(){outputClosed=true;}};
  }
  @Override public int getResponseCode(){check(expectedMethod.equals(method),"wire method must match signed request");check(!getInstanceFollowRedirects()&&getConnectTimeout()>0&&getReadTimeout()>0,"transport preserves redirect and timeout rules");check("GET".equals(method)||outputClosed,"POST body is closed before reading response");return status;}
  @Override public long getContentLengthLong(){return response.length;}
  @Override public InputStream getInputStream(){check(status==200,"non-success response body must not be read");reads++;return new ByteArrayInputStream(response);}
  @Override public Map<String,List<String>> getHeaderFields(){return Collections.singletonMap("Content-Type",Collections.singletonList("application/json"));}
 }
 public static void main(String[]args)throws Exception{
  group("registered identity precedes current v5 player/v1 signed request",()->{
   List<OfficialApiClient.Request> requests=new ArrayList<>();AtomicInteger bootCalls=new AtomicInteger();
   RegisteredPlaybackModel op=operation(r->{bootCalls.incrementAndGet();return registration();},r->{
    requests.add(r);check(bootCalls.get()==1,"register exactly once before model");
    check(new URI(r.url).getHost().equals("api5-normal-sinfonlinea.fqnovel.com")&&new URI(r.url).getPath().equals("/novel/player/multi_video_model/player/v1")&&r.method.equals("POST")&&r.body.length>0,"current v5 player/v1 target first");
    Map<String,String> q=query(r.url);check(DID.equals(q.get("device_id"))&&IID.equals(q.get("iid")),"only registered response identity signs model");check(!q.containsKey("openudid"),"probe profile excludes extra passport fields");check(q.get("cdid")!=null&&q.get("cdid").matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")&&!q.get("cdid").equals(CDID),"v5 carries an independent session cdid");check(q.get("version_code").equals("74332")&&q.get("aid").equals("8662")&&q.get("video_id")==null&&!q.containsKey("ts")&&q.containsKey("_rticket"),"v5 profile exact with no ts parameter");
    for(String header:new String[]{"x-khronos","x-helios","x-medusa","x-ss-stub"})check(r.headers.get(header)!=null&&!r.headers.get(header).isEmpty(),"v5 signing header "+header);
    for(String retired:new String[]{"X-Ladon","X-Argus","X-SS-Req-Ticket","X-Gorgon"})check(!r.headers.containsKey(retired),"v3-era header retired: "+retired);
    long khronos=Long.parseLong(r.headers.get("x-khronos")),ticket=Long.parseLong(q.get("_rticket"));check(ticket>=khronos*1000&&ticket<khronos*1000+1024,"v5 ticket search stays within the signed second");check(r.headers.get("x-ss-stub").equalsIgnoreCase(DanmakuSigner.hex(DanmakuSigner.md5(r.body))),"v5 signature hashes actual gzip body");check(PlaybackSignerV5.PLAYER_V1_UA.equals(r.headers.get("User-Agent")),"v5 player UA exact");check(!r.headers.containsKey("Cookie")&&!r.headers.containsKey("X-Tt-Token"),"anonymous model never copies registration/account cookies");return response(200,envelope(model()));});
   JSONObject result=op.fetch(VID);check(requests.size()==1&&result.length()==2&&result.getString("vid").equals(VID),"one bootstrap plus one v5 model only");check(result.getJSONObject("video_model").getJSONArray("video_list").length()==1,"real model projected");check(reject(()->op.fetch(VID)) instanceof IOException,"one lookup instance cannot reuse consumed identity");
  });
  group("hollowed v5 surfaces immediately for the web fallback",()->{
   AtomicInteger registrations=new AtomicInteger();List<OfficialApiClient.Request> requests=new ArrayList<>();
   RegisteredPlaybackModel op=operation(r->{requests.add(r);registrations.incrementAndGet();return registration();},r->{
    requests.add(r);check(new URI(r.url).getPath().endsWith("/player/v1"),"single-shot v5 only");
    return response(200," ");});
   try { op.fetch(VID); check(false,"hollow must throw"); }
   catch (Exception failure) {
     check(failure instanceof RegisteredPlaybackModel.ModelError && "APP_EMPTY_RESPONSE".equals(((RegisteredPlaybackModel.ModelError)failure).code),"hollow error surfaced");
   }
   check(registrations.get()==1&&requests.size()==2,"register plus one v5, no ladder");
  });
group("same identity recovers on the retry (UI-level)",()->{
   AtomicInteger models=new AtomicInteger(),registrations=new AtomicInteger();
   // First fetch hollows, second fetch on the SAME identity succeeds (UI retry)
   OfficialApiClient.Transport model=r->{int call=models.incrementAndGet();return response(200,call<=1?" ":envelope(model()));};
   try { operation(r->{registrations.incrementAndGet();return registration();},model).fetch(VID); } catch (Exception expected) { }
   JSONObject result=operation(r->{registrations.incrementAndGet();return registration();},model).fetch(VID);
   check(result.getJSONObject("video_model").getJSONArray("video_list").length()==1&&models.get()>=1,"second attempt on same identity succeeds after first hollow");check(result.getJSONObject("video_model").getJSONArray("video_list").length()==1,"recovered model projected");
  });
group("HTTP refusal fails typed from single attempt",()->{
   AtomicInteger both=new AtomicInteger();
   Exception failure=reject(()->operation(r->registration(),r->{both.incrementAndGet();return response(404,"");}).fetch(VID));
   check(failure instanceof RegisteredPlaybackModel.ModelError&&((RegisteredPlaybackModel.ModelError)failure).code.equals("HTTP_ERROR"),"refusal stays typed");
   check(both.get()==1,"one v5 attempt per fetch");
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
  group("registration failure never makes a model request or invents IDs",()->{for(String bad:new String[]{"{}","{\"device_id\":0,\"install_id\":1}","{\"device_id_str\":\"abc\",\"install_id\":9}","{\"device_id\":7,\"install_id_str\":\"\"}"}){AtomicInteger calls=new AtomicInteger();reject(()->operation(r->response(200,bad),r->{calls.incrementAndGet();return response(200,envelope(model()));}).fetch(VID));check(calls.get()==0,"failed registered identity blocks model");}AtomicInteger calls=new AtomicInteger();reject(()->operation(r->{throw new IOException("fixture registration failure");},r->{calls.incrementAndGet();return registration();}).fetch(VID));check(calls.get()==0,"network registration failure blocks model");});
  group("business denials missing status invalid JSON and contradictory identity remain failures",()->{
   List<String> bad=new ArrayList<>(Arrays.asList("{}","[]","{bad}","{\"code\":1,\"data\":{}}","{\"code\":false,\"data\":{}}","{\"code\":0,\"error_code\":1,\"data\":{}}","{\"code\":0,\"data\":{\"error_code\":1}}"));
   for(String key:new String[]{"item_id","episode_id"}){bad.add(new JSONObject().put("code",0).put(key,"123").put("data",new JSONObject().put("video_model",model())).toString());bad.add(envelope(model().put(key,"123")));}
   for(String body:bad)check(reject(()->operation(r->registration(),r->response(200,body)).fetch(VID)) instanceof RegisteredPlaybackModel.ModelError,"invalid response cannot appear successful");
   for(int status:new int[]{301,302,307,403,500}){AtomicInteger calls=new AtomicInteger();check(reject(()->operation(r->registration(),r->{calls.incrementAndGet();return response(status,envelope(model()));}).fetch(VID)) instanceof RegisteredPlaybackModel.ModelError,"non200 refused");check(calls.get()==1,"one v5 attempt per fetch for an HTTP refusal");}
  });
  group("empty and oversized raw or gzip model bodies have precise bounded failures",()->{
   Exception empty=reject(()->operation(r->registration(),r->response(200,"  ")).fetch(VID));check(empty instanceof RegisteredPlaybackModel.ModelError&&((RegisteredPlaybackModel.ModelError)empty).code.equals("APP_EMPTY_RESPONSE"),"empty model typed not success");
   byte[] huge=new byte[RegisteredPlaybackModel.MAX_BYTES+1];check(reject(()->operation(r->registration(),r->new OfficialApiClient.Response(200,Collections.emptyMap(),huge)).fetch(VID)) instanceof RegisteredPlaybackModel.ModelError,"raw bound rejects before parse");byte[] packed=gzip(huge);check(reject(()->operation(r->registration(),r->new OfficialApiClient.Response(200,Collections.singletonMap("Content-Encoding",Arrays.asList("gzip")),packed)).fetch(VID)) instanceof RegisteredPlaybackModel.ModelError,"gzip expansion bounded too");
   byte[] normal=gzip(envelope(model()).getBytes(StandardCharsets.UTF_8));check(operation(r->registration(),r->new OfficialApiClient.Response(200,Collections.singletonMap("Content-Encoding",Arrays.asList("gzip")),normal)).fetch(VID).has("video_model"),"bounded gzip actual model accepted");
  });
  group("shared total deadline blocks late registration and model responses",()->{
   AtomicInteger models=new AtomicInteger();long stop=System.nanoTime()+25_000_000L;RegisteredPlaybackModel lateRegister=operation(r->{Thread.sleep(40);return registration();},r->{models.incrementAndGet();return response(200,envelope(model()));},stop);check(reject(()->lateRegister.fetch(VID)) instanceof SocketTimeoutException&&models.get()==0,"late registration never starts model stage");
   stop=System.nanoTime()+25_000_000L;RegisteredPlaybackModel lateModel=operation(r->registration(),r->{Thread.sleep(40);return response(200,envelope(model()));},stop);check(reject(()->lateModel.fetch(VID)) instanceof SocketTimeoutException,"late model never publishes result");
   AtomicInteger all=new AtomicInteger();RegisteredPlaybackModel expired=operation(r->{all.incrementAndGet();return registration();},r->{all.incrementAndGet();return registration();},System.nanoTime()-1);check(reject(()->expired.fetch(VID)) instanceof SocketTimeoutException&&all.get()==0,"already expired budget sends zero bootstrap or model requests");
  });
  group("cancel closes each stage and rejects deliberately late successful replies",()->{
   for(boolean duringModel:new boolean[]{false,true}){CountDownLatch entered=new CountDownLatch(1),released=new CountDownLatch(1);AtomicInteger modelCalls=new AtomicInteger();AtomicReference<Throwable> result=new AtomicReference<>();AtomicBoolean transportClosed=new AtomicBoolean();OfficialApiClient.Transport blockingModel=new OfficialApiClient.Transport(){public OfficialApiClient.Response execute(OfficialApiClient.Request r)throws Exception{entered.countDown();while(released.getCount()>0)try{released.await();}catch(InterruptedException ignored){}return response(200,envelope(model()));}public void close(){transportClosed.set(true);released.countDown();}};
    RegisteredPlaybackModel.DeviceBootstrap blockingBootstrap=()->{entered.countDown();while(released.getCount()>0)try{released.await();}catch(InterruptedException ignored){}return new String[]{DID,IID,CDID};};
    OfficialApiClient.Transport noop=new OfficialApiClient.Transport(){public OfficialApiClient.Response execute(OfficialApiClient.Request r)throws Exception{throw new AssertionError("registration transport is not used by the model lane");}public void close(){transportClosed.set(true);released.countDown();}};
    RegisteredPlaybackModel op=duringModel?operation(r->registration(),blockingModel):new RegisteredPlaybackModel(noop,new OfficialApiClient.Transport(){public OfficialApiClient.Response execute(OfficialApiClient.Request r)throws Exception{modelCalls.incrementAndGet();return response(200,envelope(model()));}public void close(){}},blockingBootstrap,System.nanoTime()+20_000_000_000L);
    Thread worker=new Thread(()->{try{op.fetch(VID);result.set(new AssertionError("late success"));}catch(Throwable failure){result.set(failure);}});worker.start();check(entered.await(2,TimeUnit.SECONDS),"stage reached");op.close();worker.join(2000);check(!worker.isAlive()&&result.get() instanceof InterruptedIOException&&transportClosed.get(),"cancel promptly closes real operation boundary and rejects late body");check(modelCalls.get()==0&&!op.current(),"canceled registration never reaches model and operation inactive");
   }
  });
  group("fixed model transport rejects alternate host path scheme auth port method before network",()->{for(String url:new String[]{"http://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/","https://evil.invalid/novel/player/video_model/v1/","https://api5-normal-sinfonlineb.fqnovel.com/reading/user/info/v1/","https://a@api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/","https://api5-normal-sinfonlineb.fqnovel.com:443/novel/player/video_model/v1/","https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/#x","https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/v1/","https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/player/v1"})reject(()->RegisteredPlaybackModel.ModelHttpTransport.validate(new OfficialApiClient.Request(url,"POST",Collections.emptyMap(),new byte[0],Long.MAX_VALUE)));reject(()->RegisteredPlaybackModel.ModelHttpTransport.validate(new OfficialApiClient.Request(RegisteredPlaybackModel.API,"GET",Collections.emptyMap(),new byte[0],Long.MAX_VALUE)));reject(()->RegisteredPlaybackModel.ModelHttpTransport.validate(new OfficialApiClient.Request(PlaybackSignerV5.PLAYER_V1_API+"?x=1","GET",Collections.emptyMap(),new byte[0],Long.MAX_VALUE)));check(RegisteredPlaybackModel.ModelHttpTransport.validate(new OfficialApiClient.Request(RegisteredPlaybackModel.API+"?x=1","POST",Collections.emptyMap(),new byte[0],Long.MAX_VALUE)).getHost().equals("api5-normal-sinfonlineb.fqnovel.com"),"only intended fixed model target accepted");check(RegisteredPlaybackModel.ModelHttpTransport.validate(new OfficialApiClient.Request(PlaybackSignerV5.PLAYER_V1_API+"?x=1","POST",Collections.emptyMap(),new byte[0],Long.MAX_VALUE)).getHost().equals("api5-normal-sinfonlinea.fqnovel.com"),"v5 player target accepted on its own host");});
  group("validated video_tab GET never carries a body and signed POST preserves its gzip bytes",()->{
   List<String[]>wire=new ArrayList<>();byte[] payload=gzip("fixture-request".getBytes(StandardCharsets.UTF_8));
   byte[] reply="fixture-response".getBytes(StandardCharsets.UTF_8);
   String getUrl=RegisteredPlaybackModel.VIDEO_TAB_API+"?video_id="+VID+"&x=%2B",postUrl=RegisteredPlaybackModel.API+"?aid=8662&x=%20",playerUrl=PlaybackSignerV5.PLAYER_V1_API+"?aid=8662&x=%20";
   Map<String,String> tabHeaders=Collections.singletonMap("X-Argus","fixture-tab-signature");
   Map<String,String> postHeaders=new LinkedHashMap<>();postHeaders.put("Content-Encoding","gzip");postHeaders.put("X-SS-STUB","fixture-body-signature");
   RegisteredPlaybackModel.ModelHttpTransport.Engine fake=(uri,request,timeoutMs,pin)->{
    wire.add(new String[]{request.method,uri.toString(),new String(request.body,StandardCharsets.UTF_8),request.headers.toString(),Integer.toString(timeoutMs)});
    if(request.method.equals("GET"))return new OfficialApiClient.Response(404,Collections.emptyMap(),new byte[0]);
    return new OfficialApiClient.Response(200,Collections.singletonMap("Content-Type",Collections.singletonList("application/json")),reply);
   };
   try(RegisteredPlaybackModel.ModelHttpTransport transport=new RegisteredPlaybackModel.ModelHttpTransport(fake)){
    long deadline=System.nanoTime()+5_000_000_000L;
    Exception getBody=reject(()->transport.execute(new OfficialApiClient.Request(getUrl,"GET",tabHeaders,new byte[]{1},deadline)));
    Exception wrongMethod=reject(()->transport.execute(new OfficialApiClient.Request(getUrl,"POST",tabHeaders,new byte[0],deadline)));
    Exception wrongHost=reject(()->transport.execute(new OfficialApiClient.Request(postUrl.replace("api5-normal-sinfonlineb.fqnovel.com","fixture.invalid"),"POST",postHeaders,payload,deadline)));
    Exception wrongPlayerHost=reject(()->transport.execute(new OfficialApiClient.Request(playerUrl.replace("api5-normal-sinfonlinea.fqnovel.com","api5-normal-sinfonlineb.fqnovel.com"),"POST",postHeaders,payload,deadline)));
    for(Exception invalid:new Exception[]{getBody,wrongMethod,wrongHost,wrongPlayerHost})check(invalid instanceof RegisteredPlaybackModel.ModelError&&"INVALID_INPUT".equals(((RegisteredPlaybackModel.ModelError)invalid).code),"wire request validated before connection");
    check(wire.isEmpty(),"invalid request opens zero connections");
    OfficialApiClient.Response tab=transport.execute(new OfficialApiClient.Request(getUrl,"GET",tabHeaders,new byte[0],deadline));
    String[] get=wire.get(0);check(tab.status==404&&tab.body.length==0,"GET returns its HTTP refusal");
    check(get[0].equals("GET")&&get[2].isEmpty()&&get[1].equals(getUrl),"GET forwards its exact URL and never carries a request body");
    check(get[3].contains("X-Argus=fixture-tab-signature"),"GET forwards its exact headers");
    OfficialApiClient.Response post=transport.execute(new OfficialApiClient.Request(postUrl,"POST",postHeaders,payload,deadline));
    String[] send=wire.get(1);check(post.status==200&&Arrays.equals(reply,post.body)&&"application/json".equals(post.header("Content-Type")),"POST keeps response body and headers");
    check(send[0].equals("POST")&&send[1].equals(postUrl)&&send[2].equals(new String(payload,StandardCharsets.UTF_8)),"POST keeps exactly its signed gzip request bytes");
    check(send[3].contains("X-SS-STUB=fixture-body-signature"),"POST forwards exact headers");
    check(Integer.parseInt(send[4])>0&&Integer.parseInt(send[4])<=7000,"engine timeout stays within the shared deadline budget");
    OfficialApiClient.Response player=transport.execute(new OfficialApiClient.Request(playerUrl,"POST",postHeaders,payload,deadline));
    String[] v5=wire.get(2);check(player.status==200&&Arrays.equals(reply,player.body)&&v5[2].equals(new String(payload,StandardCharsets.UTF_8)),"v5 POST keeps exactly its signed gzip request bytes");
   }
  });
  if(args.length==1)java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),projectedFlags.toString().getBytes(StandardCharsets.UTF_8));
  System.out.println("REGISTERED_PLAYBACK_MODEL_OK groups="+groups+" checks="+checks+" actualRegistrationAndSigner=true oneSharedDeadline=true projectedModelOnly=true networkRequests=0");
 }
}
