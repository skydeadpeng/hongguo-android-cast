package io.github.jackotom.hongguo.android;

import java.util.concurrent.TimeUnit;
import org.json.*;

/** Offline regression against the production login flow; no SMS or account requests. */
public final class OfficialLoginCompletionTest {
    private static int checks, groups;
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    private static void ticketFallback()throws Exception {
        OfficialAccountFlowTest.Env e=new OfficialAccountFlowTest.Env();
        e.factory.login.add(OfficialAccountFlowTest.mfa()); e.flow.loginSMS("13800000000","123456");
        e.factory.verify.add(OfficialAccountFlowTest.ok(new JSONObject().put("ticket","").put("verify_ticket","PRIVATE_FALLBACK_TICKET")));
        JSONObject result=e.flow.verifyMfa();
        check(result.getBoolean("authenticated"),"empty primary ticket must use nonempty verify_ticket");
        check(e.factory.logins==2&&e.factory.tickets.get(1).equals("PRIVATE_FALLBACK_TICKET"),"fallback ticket reaches native re-login once");
        check(!result.toString().contains("PRIVATE_FALLBACK_TICKET")&&OfficialAccountFlowTest.otp(e.flow).isEmpty(),"ticket is not projected and OTP clears");groups++;
    }
    private static void ticketPrimary()throws Exception {
        OfficialAccountFlowTest.Env e=new OfficialAccountFlowTest.Env();
        e.factory.login.add(OfficialAccountFlowTest.mfa());e.flow.loginSMS("13800000000","123456");
        e.factory.verify.add(OfficialAccountFlowTest.ok(new JSONObject().put("ticket","PRIVATE_PRIMARY").put("verify_ticket","PRIVATE_OTHER")));
        check(e.flow.verifyMfa().getBoolean("authenticated"),"primary ticket still authenticates");
        check(e.factory.tickets.get(1).equals("PRIVATE_PRIMARY"),"nonempty primary ticket keeps precedence");groups++;
    }
    private static void missingTicket()throws Exception {
        OfficialAccountFlowTest.Env e=new OfficialAccountFlowTest.Env();
        e.factory.login.add(OfficialAccountFlowTest.mfa());e.flow.loginSMS("13800000000","123456");
        e.factory.verify.add(OfficialAccountFlowTest.ok(new JSONObject().put("ticket","").put("verify_ticket","")));
        JSONObject result=e.flow.verifyMfa();
        check(!result.getBoolean("authenticated")&&e.factory.logins==1,"missing ticket never replays login");
        check(result.getString("phase").equals("mfa"),"live challenge remains retryable after malformed success");groups++;
    }
    private static void monotonicExpiry()throws Exception {
        for(String entry:new String[]{"status","suspend","verify"}){
            OfficialAccountFlowTest.Env e=new OfficialAccountFlowTest.Env();
            e.factory.login.add(OfficialAccountFlowTest.mfa());e.flow.loginSMS("13800000000","123456");
            e.clock.now-=1_200_000;e.clock.nanos+=TimeUnit.MINUTES.toNanos(10);
            JSONObject result;
            if(entry.equals("suspend"))result=e.flow.suspendLogin();
            else if(entry.equals("verify")){try{e.flow.verifyMfa();throw new AssertionError("expired challenge accepted");}catch(java.io.IOException expected){}result=e.flow.status();}
            else result=e.flow.status();
            check(result.getString("phase").equals("phone"),"wall-clock rollback must not extend 10-minute MFA via "+entry);
            check(!result.has("smsContent")&&OfficialAccountFlowTest.otp(e.flow).isEmpty(),"expired private challenge clears via "+entry);
            check(e.factory.verifications==0&&e.factory.logins==1,"expired challenge sends no follow-up requests via "+entry);
        }groups++;
    }
    private static void remainingBudget()throws Exception {
        OfficialAccountFlowTest.Env e=new OfficialAccountFlowTest.Env();
        e.factory.login.add(OfficialAccountFlowTest.mfa());e.flow.loginSMS("13800000000","123456");
        e.clock.now-=1_200_000;e.clock.nanos+=TimeUnit.SECONDS.toNanos(590);long start=e.clock.nanos;
        JSONObject result=e.flow.verifyMfa();
        check(result.getString("phase").equals("phone"),"remaining monotonic challenge deadline must expire during poll");
        check(e.clock.nanos-start==TimeUnit.SECONDS.toNanos(10),"10 seconds remaining caps poll and sleep instead of another 90-second round");
        check(e.factory.verifications==4&&e.factory.logins==1,"only 4 bounded status polls and no login after challenge deadline");groups++;
    }
    private static void lateReply()throws Exception {
        OfficialAccountFlowTest.Env e=new OfficialAccountFlowTest.Env();
        e.factory.login.add(OfficialAccountFlowTest.mfa());e.flow.loginSMS("13800000000","123456");
        e.clock.now-=1_200_000;e.clock.nanos+=TimeUnit.SECONDS.toNanos(590);
        e.factory.onVerify=()->e.clock.nanos+=TimeUnit.SECONDS.toNanos(11);
        e.factory.verify.add(OfficialAccountFlowTest.ok(new JSONObject().put("ticket","PRIVATE_LATE")));
        JSONObject result=e.flow.verifyMfa();
        check(!result.getBoolean("authenticated")&&e.factory.logins==1,"late success after challenge monotonic expiry cannot re-login");
        check(result.getString("phase").equals("phone")&&OfficialAccountFlowTest.otp(e.flow).isEmpty(),"late reply ends expired private challenge");groups++;
    }
    public static void main(String[]args)throws Exception {
        String selected=args.length==0?"all":args[0];
        if(selected.equals("all")||selected.equals("ticket"))ticketFallback();
        if(selected.equals("all")||selected.equals("expiry"))monotonicExpiry();
        if(selected.equals("all")||selected.equals("budget"))remainingBudget();
        if(selected.equals("all")){ticketPrimary();missingTicket();lateReply();}
        if(groups==0)throw new IllegalArgumentException("unknown selection");
        System.out.println("OFFICIAL_LOGIN_COMPLETION_OK groups="+groups+" checks="+checks+" productionFlow=true fallbackTicket=true monotonicChallenge=true networkRequests=0 realSms=0");
    }
}
