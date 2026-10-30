package io.github.jackotom.hongguo.android;

import java.util.*;
import org.json.JSONObject;

/** Live website passport calls; the wire format is the official browser flow and needs no signature. */
final class OfficialWebGateway implements OfficialWebAccount.Gateway {
    private final OfficialWebPassport passport;
    OfficialWebGateway(){this(new OfficialWebPassport());}
    OfficialWebGateway(OfficialWebPassport passport){if(passport==null)throw new IllegalArgumentException("红果官网通道无效");this.passport=passport;}

    @Override public JSONObject start(OfficialWebPassport.CookieJar jar) throws Exception {
        Map<String,String> extra=new LinkedHashMap<>();extra.put("next",OfficialWebPassport.BASE+"/");
        return passport.api("/passport/web/get_qrcode/",extra,jar,System.nanoTime()+OfficialWebPassport.TIMEOUT_NANOS);
    }
    @Override public JSONObject poll(OfficialWebPassport.CookieJar jar,String token) throws Exception {
        Map<String,String> extra=new LinkedHashMap<>();extra.put("next",OfficialWebPassport.BASE+"/");extra.put("token",token);
        return passport.api("/passport/web/check_qrconnect/",extra,jar,System.nanoTime()+OfficialWebPassport.TIMEOUT_NANOS);
    }
    @Override public void confirm(OfficialWebPassport.CookieJar jar,String redirect) throws Exception {
        passport.followRedirect(redirect,jar,System.nanoTime()+OfficialWebPassport.TIMEOUT_NANOS);
    }
    @Override public JSONObject profile(OfficialWebPassport.CookieJar jar) throws Exception {
        return passport.api("/passport/account/info/v2/",new LinkedHashMap<>(),jar,System.nanoTime()+OfficialWebPassport.TIMEOUT_NANOS);
    }
}
