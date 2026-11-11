package io.github.jackotom.hongguo.android;
import java.util.*;
public final class UpdatePolicyTest {
 static int checks;
 static void yes(boolean x){checks++;if(!x)throw new AssertionError("check "+checks);}
 static void bad(Runnable r){checks++;try{r.run();throw new AssertionError("accepted "+checks);}catch(IllegalArgumentException expected){}}
 static String url(String flavor){return "https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.11/Hongguo-0.12.11-"+flavor+"-release-compatible.apk";}
 static String nativeUrl(String name){return "https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v"+name+"/HongguoNative-"+name+"-tvNative-release-compatible.apk";}
 static void validate(String pkg,String flavor,int code,String name,int sdk,long size,String hash,String url,int installed,String installedName){UpdatePolicy.validate(1,"android-compatible",flavor,pkg,code,name,sdk,size,hash,url,pkg,installed,installedName,35);}
 public static void main(String[]a){
  String pkg="io.github.jackotom.hongguo.android",hash="a".repeat(64);
  validate(pkg,"phone",34,"0.12.11",26,100,hash,url("phone"),33,"0.12.10"); checks++;
  validate(pkg+".tv","tv",34,"0.12.11",26,100,hash,url("tv"),33,"0.12.10"); checks++;
  validate(pkg,"phone",33,"0.12.10",26,100,hash,url("phone").replace("0.12.11","0.12.10"),33,"0.12.10"); checks++;
  bad(()->validate(pkg,"tv",34,"0.12.11",26,100,hash,url("tv"),33,"0.12.10"));
  bad(()->validate(pkg,"phone",32,"0.12.11",26,100,hash,url("phone"),33,"0.12.10"));
  bad(()->validate(pkg,"phone",33,"0.12.11",26,100,hash,url("phone"),33,"0.12.10"));
  bad(()->validate(pkg,"phone",34,"0.12.11",36,100,hash,url("phone"),33,"0.12.10"));
  for(String u: new String[]{url("tv"),url("phone")+"?q=1",url("phone")+"#x",url("phone").replace("github.com","github.com.evil.test"),url("phone").replace("https:","http:"),url("phone").replace("skydeadpeng","aston314"),url("phone").replace("/v0.12.11/","/latest/"),"https://github.com@evil.test/a.apk",url("phone").replace("github.com/","github.com:443/")})bad(()->validate(pkg,"phone",34,"0.12.11",26,100,hash,u,33,"0.12.10"));
  for(long size:new long[]{0,-1,UpdatePolicy.MAX_APK_BYTES+1})bad(()->validate(pkg,"phone",34,"0.12.11",26,size,hash,url("phone"),33,"0.12.10"));
  for(String h:new String[]{"", "a".repeat(63),"z".repeat(64)})bad(()->validate(pkg,"phone",34,"0.12.11",26,100,h,url("phone"),33,"0.12.10"));
  bad(()->UpdatePolicy.validate(2,"android-compatible","phone",pkg,34,"0.12.11",26,100,hash,url("phone"),pkg,33,"0.12.10",35));
  bad(()->UpdatePolicy.validate(1,"other","phone",pkg,34,"0.12.11",26,100,hash,url("phone"),pkg,33,"0.12.10",35));
  yes(UpdatePolicy.automaticCheckDue(1000,0));yes(UpdatePolicy.automaticCheckDue(1000,2000));yes(!UpdatePolicy.automaticCheckDue(1000,999));yes(UpdatePolicy.automaticCheckDue(UpdatePolicy.CHECK_INTERVAL+1,1));
  yes(UpdatePolicy.matchingArchive(pkg,33,pkg,34,"0.12.11",34,"0.12.11",new String[]{"b","a"},new String[]{"a","b"}));
  yes(!UpdatePolicy.matchingArchive(pkg,33,pkg,34,"0.12.11",34,"0.12.11",new String[]{"a"},new String[]{"b"}));
  yes(!UpdatePolicy.matchingArchive(pkg,33,pkg+".tv",34,"0.12.11",34,"0.12.11",new String[]{"a"},new String[]{"a"}));
  yes(!UpdatePolicy.matchingArchive(pkg,33,pkg,33,"0.12.11",34,"0.12.11",new String[]{"a"},new String[]{"a"}));
  yes(!UpdatePolicy.matchingArchive(pkg,33,pkg,34,"0.12.11",34,"0.12.11",new String[0],new String[0]));
  yes(!UpdatePolicy.matchingArchive(pkg,33,pkg,34,"0.12.12",34,"0.12.11",new String[]{"a"},new String[]{"a"}));
  yes(UpdatePolicy.allowedRedirect("https://release-assets.githubusercontent.com/github-production-release-asset/abc?token=abc"));yes(!UpdatePolicy.allowedRedirect("https://127.0.0.1/x"));yes(!UpdatePolicy.allowedRedirect("http://github.com/x"));yes(!UpdatePolicy.allowedRedirect("https://github.com.evil.test/x"));yes(!UpdatePolicy.allowedRedirect("https://user@github.com/x"));
  // tvNative is an independent version line: own manifest channel, package and HongguoNative asset naming.
  yes(UpdatePolicy.manifestUrl("tvnative").endsWith("/android/update-tvnative.json"));
  validate(pkg+".tvnative","tvnative",2,"0.2.0",26,100,hash,nativeUrl("0.2.0"),1,"0.1.0"); checks++;
  validate(pkg+".tvnative","tvnative",1,"0.1.0",26,100,hash,nativeUrl("0.1.0"),1,"0.1.0"); checks++;
  bad(()->validate(pkg,"tvnative",2,"0.2.0",26,100,hash,nativeUrl("0.2.0"),1,"0.1.0"));
  bad(()->validate(pkg+".tv","tvnative",2,"0.2.0",26,100,hash,nativeUrl("0.2.0"),1,"0.1.0"));
  bad(()->validate(pkg+".tvnative","phone",2,"0.2.0",26,100,hash,url("phone"),1,"0.1.0"));
  bad(()->validate(pkg+".tvnative","tvnative",2,"0.2.0",26,100,hash,url("tv"),1,"0.1.0"));
  bad(()->validate(pkg,"phone",34,"0.12.11",26,100,hash,nativeUrl("0.12.11"),33,"0.12.10"));
  bad(()->validate(pkg+".tvnative","tvnative",1,"0.1.0",26,100,hash,nativeUrl("0.1.0"),1,"0.2.0"));
  yes(UpdatePolicy.downloadSources(nativeUrl("0.2.0")).size()==5);
  yes(UpdatePolicy.downloadSources(nativeUrl("0.2.0").replace("-compatible","")).size()==5);
  bad(()->UpdatePolicy.downloadSources(nativeUrl("0.2.0").replace("HongguoNative-0.2.0-tvNative","Hongguo-0.2.0-tvNative")));
  bad(()->UpdatePolicy.downloadSources(nativeUrl("0.2.0").replace("HongguoNative-0.2.0-tvNative","HongguoNative-0.2.0-phone")));
  yes(UpdatePolicy.manifestSources("tvnative",5).get(0).url.contains("update-tvnative.json"));
  yes(UpdatePolicy.manifestSources("tv",5).get(0).url.contains("update-tv.json"));
  yes(!UpdatePolicy.automaticCheckDue(100000000L,99999000L,200000000L));
  yes(UpdatePolicy.automaticCheckDue(100000000L,100000000L-UpdatePolicy.CHECK_RETRY_INTERVAL,200000000L));
  // Automatic scheduling distinguishes successful daily checks from failed short retries.
  long day=UpdatePolicy.CHECK_INTERVAL,retry=UpdatePolicy.CHECK_RETRY_INTERVAL,base=10000000;
  yes(UpdatePolicy.automaticCheckDue(base,0,0));
  yes(!UpdatePolicy.automaticCheckDue(base+day-1,base,base));
  yes(UpdatePolicy.automaticCheckDue(base+day,base,base));
  yes(!UpdatePolicy.automaticCheckDue(base+retry-1,base,base-1));
  yes(UpdatePolicy.automaticCheckDue(base+retry,base,base-1));
  yes(UpdatePolicy.automaticCheckDue(base-1,base,base));
  yes(!UpdatePolicy.automaticCheckDue(base+retry,base-100,base));
  yes(UpdatePolicy.automaticCheckDue(base+day,base-100,base));
  yes(UpdatePolicy.automaticCheckDue(base,base+1,0));
  yes(UpdatePolicy.automaticCheckDue(base,0,base+1));
  System.out.println("UpdatePolicy: "+checks+" checks passed");
 }
}
