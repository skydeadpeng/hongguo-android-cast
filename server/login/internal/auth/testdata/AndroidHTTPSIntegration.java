package io.github.jackotom.hongguo.android;

import java.io.*;
import java.lang.reflect.*;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;
import org.json.*;

/** JVM network/JSON contract proof; never instantiates Android UI or its Keystore. */
public final class AndroidHTTPSIntegration {
    private static Method network;
    private static String origin;
    private static JSONObject call(String path,String method,String token,JSONObject body) throws Exception {
        try { return (JSONObject)network.invoke(null,origin,path,method,token,body,System.currentTimeMillis()+10000); }
        catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw e;}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static JSONObject row(String sid,int index,long position) throws Exception {
        return new JSONObject().put("seriesId",sid).put("title","持械入宋").put("coverUrl","").put("description","真实 Java HTTPS 与 Go 接口测试")
            .put("episodeCount",68).put("episodeLabel","共68集").put("tags",new JSONArray().put("短剧"))
            .put("vid",Integer.toString(100000+index)).put("index",index).put("positionMs",position).put("updatedAt",System.currentTimeMillis());
    }
    private static JSONObject post(String token,String device,JSONArray batch) throws Exception {
        return call("/v1/history/sync","POST",token,new JSONObject().put("deviceId",device).put("mutations",batch));
    }
    private static String login(String username,String device) throws Exception {
        JSONObject reply=call("/v1/auth/login","POST","",new JSONObject().put("username",username).put("password","a-strong-test-password-123").put("deviceName",device));
        check(reply.getJSONObject("user").getString("username").equals(username),"login user");
        check(reply.getString("token").matches("[0-9a-f]{64}"),"login token format");
        return reply.getString("token");
    }
    private static void pull(HistorySyncState state,String token) throws Exception {
        boolean more;
        do {JSONObject p=call("/v1/history?cursor="+state.cursor()+"&limit=1","GET",token,null);more=p.getBoolean("hasMore");state.pull(p.getJSONArray("records"),p.getLong("cursor"));}while(more);
    }
    public static void main(String[] args) throws Exception {
        origin=AccountEndpoint.normalize(args[0]);
        network=AccountSync.class.getDeclaredMethod("request",String.class,String.class,String.class,String.class,JSONObject.class,long.class);network.setAccessible(true);
        boolean invalidRejected=false;
        try{call("/healthz","GET","",null);}catch(SSLException expected){invalidRejected=true;}
        check(invalidRejected,"production transport accepted untrusted certificate");
        // Only this test process trusts the local Go httptest certificate. The
        // ordinary HTTPS hostname verifier remains installed and unchanged.
        CertificateFactory factory=CertificateFactory.getInstance("X.509");
        java.security.cert.Certificate cert;try(InputStream in=new FileInputStream(args[1])){cert=factory.generateCertificate(in);}
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("local-fixture",cert);
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,tm.getTrustManagers(),null);HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
        String phoneToken=login("alice","Android 手机"),tvToken=login("alice","Android TV");
        HistorySyncState phone=new HistorySyncState("{}"),tv=new HistorySyncState("{}");
        phone.record("phone-player",row("123456",8,50000));
        JSONArray batch=phone.batch(10);String frozen=batch.toString();
        JSONObject first=post(phoneToken,"real-java-phone",batch); // response deliberately lost
        phone=new HistorySyncState(phone.encode());batch=phone.batch(10);
        check(batch.toString().equals(frozen),"lost-response retry changed payload");
        JSONObject replay=post(phoneToken,"real-java-phone",batch);
        check(replay.getJSONArray("results").getJSONObject(0).getJSONObject("record").getLong("revision")==first.getJSONArray("results").getJSONObject(0).getJSONObject("record").getLong("revision"),"lost response allocated another revision");
        phone.acknowledge(replay.getJSONArray("results"),phone.sentWriters(batch));
        pull(tv,tvToken);check(tv.rows().getJSONObject(0).getLong("positionMs")==50000,"phone to TV progress");
        tv.record("tv-player",row("123456",9,10000));batch=tv.batch(10);
        JSONObject next=post(tvToken,"real-java-tv",batch);tv.acknowledge(next.getJSONArray("results"),tv.sentWriters(batch));
        phone.record("phone-player",row("123456",8,60000));batch=phone.batch(10);
        JSONObject conflict=post(phoneToken,"real-java-phone",batch);
        check(conflict.getJSONArray("results").getJSONObject(0).getString("status").equals("conflict"),"offline stale update was not rejected");
        phone.acknowledge(conflict.getJSONArray("results"),phone.sentWriters(batch));
        check(phone.rows().getJSONObject(0).getInt("index")==9,"conflict did not import TV episode");
        check(!phone.record("phone-player",row("123456",8,61000)),"old player resumed overwriting after conflict");
        check(phone.record("explicit-new-player",row("123456",1,0)),"explicit rewatch rejected");batch=phone.batch(10);
        JSONObject rewatch=post(phoneToken,"real-java-phone",batch);phone.acknowledge(rewatch.getJSONArray("results"),phone.sentWriters(batch));
        pull(tv,tvToken);check(tv.rows().getJSONObject(0).getInt("index")==1&&tv.rows().getJSONObject(0).getLong("positionMs")==0,"rewatch failed");
        // Exercise a real multi-record POST and one-record GET pagination.
        phone.record("new-series-player",row("123457",2,1234));phone.record("another-series-player",row("123458",3,4567));batch=phone.batch(10);
        JSONObject many=post(phoneToken,"real-java-phone",batch);phone.acknowledge(many.getJSONArray("results"),phone.sentWriters(batch));pull(tv,tvToken);check(tv.rows().length()==3,"pagination lost rows");
        String bob=login("bob","another account");JSONObject isolated=call("/v1/history?cursor=0","GET",bob,null);check(isolated.getJSONArray("records").length()==0,"cross-account history leaked");
        boolean redirectRejected=false;try{call("/probe-redirect","GET",phoneToken,null);}catch(IOException expected){redirectRejected=true;}check(redirectRejected,"credentialed redirect followed");
        call("/v1/auth/logout","POST",phoneToken,new JSONObject());boolean revoked=false;try{call("/v1/history","GET",phoneToken,null);}catch(IOException expected){revoked=true;}check(revoked,"logged-out session still works");
        check(call("/v1/auth/me","GET",tvToken,null).getJSONObject("user").getString("username").equals("alice"),"logging out phone revoked TV unexpectedly");
        System.out.println("ANDROID_GO_HTTPS_INTEGRATION_PASS clients=2 login=true progress=true lostAck=true CAS=true rewatch=true pagination=true accountIsolation=true revokedSession=true untrustedTLSRejected=true redirectsRejected=true productionRequest=true deviceUI=not_tested");
    }
}
