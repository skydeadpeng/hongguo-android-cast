package io.github.jackotom.hongguo.android;

import java.lang.reflect.*;
import java.util.*;

final class StartupNativeTest {
    private static int checks;
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static Object call(Object owner,String name,Class<?>[] types,Object...args)throws Exception{Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,args);}
    private static WebView web(MainActivity activity)throws Exception{return (WebView)field(activity,"web");}
    private static StartupPolicy policy(MainActivity activity)throws Exception{return (StartupPolicy)field(activity,"startup");}
    private static Handler timer(MainActivity activity)throws Exception{return (Handler)field(activity,"startupHandler");}
    private static LinearLayout panel(MainActivity activity)throws Exception{return (LinearLayout)field(activity,"startupPanel");}
    private static boolean loaded(MainActivity activity)throws Exception{return (boolean)field(activity,"loaded");}
    private static Object startup(WebView web){return web.interfaces.get("AndroidStartup");}
    private static void ready(Object bridge,String token)throws Exception{call(bridge,"ready",new Class<?>[]{String.class},token);}
    private static void fail(Object bridge,String token,String code)throws Exception{call(bridge,"failed",new Class<?>[]{String.class,String.class},token,code);}
    private static String token(WebView web){return Uri.parse(web.url).getQueryParameter("startup");}
    private static List<Button> buttons(MainActivity activity)throws Exception{List<Button> result=new ArrayList<>();for(View view:panel(activity).children)if(view instanceof Button)result.add((Button)view);return result;}
    private static String text(MainActivity activity)throws Exception{StringBuilder result=new StringBuilder();for(View view:panel(activity).children)if(view instanceof TextView)result.append(((TextView)view).text).append('\n');return result.toString();}
    private static MainActivity start(boolean tv){BuildConfig.IS_TV=tv;MainActivity activity=new MainActivity();activity.onCreate(null);return activity;}
    private static void timeout(MainActivity activity)throws Exception{timer(activity).expire();}
    private static void policyTests(){
        StartupPolicy policy=new StartupPolicy();String first="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",second="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        check(!policy.ready(first),"idle not ready");
        for(String invalid:new String[]{null,"", "short", "aaaaaaaaaaaaaaaa?token=secret"}){boolean rejected=false;try{policy.begin(invalid);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"invalid native token rejected");}
        policy.begin(first);check(policy.loading(),"begin loading");check(!policy.ready(second),"wrong generation cannot ready");
        check(!policy.javascriptFailed(first,"https://secret/?token=secret"),"JS error whitelist");check(!policy.nativeFailed(first,"other"),"native error whitelist");
        check(policy.ready(first)&&policy.isReady(),"one mount acknowledgement");check(!policy.ready(first),"duplicate acknowledgement ignored");
        for(String code:new String[]{"bootstrap_error","script_error","unsupported_webview"})check(!policy.javascriptFailed(first,code),"late JS failure cannot replace successful mount");
        check(!policy.nativeFailed(first,"timeout"),"late timeout cannot replace mount");check(policy.nativeFailed(first,"render_process_gone")&&policy.failed(),"renderer can fail after mount");
        check(!policy.ready(first),"late mount cannot undo failure");
        for(String code:new String[]{"page_error","http_error","ssl_error","render_process_gone","timeout","webview_unavailable"}){policy.begin(first);check(policy.nativeFailed(first,code)&&code.equals(policy.failure()),"known native code "+code);}
        for(String code:new String[]{"bootstrap_error","script_error","unsupported_webview"}){policy.begin(first);check(policy.javascriptFailed(first,code)&&policy.failed(),"known JS code "+code);}
        policy.begin(first);policy.begin(second);check(!policy.nativeFailed(first,"timeout"),"stale timeout ignored");check(policy.ready(second),"new attempt accepts own ack");policy.destroy();check(!policy.ready(second)&&!policy.nativeFailed(second,"render_process_gone"),"destroy invalidates generation");
    }
    private static void loadingAndTrust()throws Exception{
        MainActivity activity=start(true);WebView candidate=web(activity);String token=token(candidate);Object bridge=startup(candidate);
        check(candidate!=null&&bridge!=null,"real startup interface installed");check(policy(activity).loading()&&!loaded(activity),"onCreate loading not ready");
        check(panel(activity)!=null&&text(activity).contains("正在启动红果"),"native loading visible before JS");check(timer(activity).delayed.size()==1,"one bounded startup timer");
        candidate.client.onPageFinished(candidate,candidate.url);check(!loaded(activity)&&panel(activity)!=null,"onPageFinished never acknowledges React");
        activity.onResume();check(candidate.scripts.isEmpty(),"resume does not call unmounted JS");
        ready(bridge,"wrong");fail(bridge,token,"forged https://secret/");check(policy(activity).loading(),"bad token and unknown code ignored");
        String actual=candidate.url;
        for(String invalid:new String[]{"https://example.invalid/index.html?startup="+token,"http://appassets.androidplatform.net/index.html?startup="+token,"https://user@appassets.androidplatform.net/index.html?startup="+token,"https://appassets.androidplatform.net:443/index.html?startup="+token,"https://appassets.androidplatform.net/other.html?startup="+token,"https://appassets.androidplatform.net/index.html?startup=wrong"}){
            candidate.url=invalid;ready(bridge,token);check(!loaded(activity),"nontrusted/wrong document cannot acknowledge "+checks);
        }
        candidate.url=actual;ready(bridge,token);check(loaded(activity)&&policy(activity).isReady(),"real commit ready");check(panel(activity)==null&&timer(activity).delayed.isEmpty(),"ready removes native overlay and timer");check(candidate.focused,"ready restores WebView remote focus");
        fail(bridge,token,"script_error");check(loaded(activity),"post-mount nonstartup errors do not hide app");
        activity.onResume();check(candidate.scripts.size()==1&&candidate.scripts.get(0).contains("__androidResume"),"existing resume callback retained after mount");
        check(candidate.settings.javascript&&candidate.settings.dom,"same JS and local storage");check(!candidate.settings.file&&!candidate.settings.content&&!candidate.settings.fileFromFile&&!candidate.settings.universal,"file capabilities remain disabled");
        check(candidate.settings.mixed==WebSettings.MIXED_CONTENT_NEVER_ALLOW&&!candidate.settings.multiple&&!candidate.settings.popup&&candidate.settings.gesture,"mixed content/popup/media unchanged");
        check(!CookieManager.instance.accept&&!CookieManager.instance.third,"cookies remain disabled");
        WebResourceResponse response=candidate.client.shouldInterceptRequest(candidate,new WebResourceRequest(actual,true,"GET"));check(response.status==200,"query token still loads local asset");
        check(response.headers.get("Content-Security-Policy").contains("connect-src 'none'")&&response.headers.get("Content-Security-Policy").contains("script-src 'self'"),"CSP unchanged");
        check("nosniff".equals(response.headers.get("X-Content-Type-Options")),"nosniff unchanged");
        check(candidate.client.shouldInterceptRequest(candidate,new WebResourceRequest(actual,true,"POST")).status==405,"local mutation method blocked");
        check(candidate.client.shouldInterceptRequest(candidate,new WebResourceRequest(MainActivity.ORIGIN+"/../secret",true,"GET")).status==403,"asset traversal blocked");
        check(candidate.client.shouldInterceptRequest(candidate,new WebResourceRequest("http://example.invalid/a",false,"GET")).status==403,"cleartext resources blocked");
        check(candidate.client.shouldOverrideUrlLoading(candidate,new WebResourceRequest("https://example.invalid/",true,"GET")),"remote main navigation blocked");
        check(!candidate.client.shouldOverrideUrlLoading(candidate,new WebResourceRequest(actual,true,"GET")),"local main navigation retained");
        activity.onDestroy();check(candidate.destroyed&&candidate.interfaces.isEmpty()&&web(activity)==null,"destroy removes bridges and WebView");check(timer(activity).delayed.isEmpty(),"destroy removes timer");ready(bridge,token);check(!policy(activity).isReady(),"destroyed activity ignores late ready");
    }
    private static void failureAndRetry()throws Exception{
        MainActivity activity=start(true);WebView first=web(activity);Object oldInterface=startup(first);String oldToken=token(first);NativeBridge oldBridge=(NativeBridge)field(activity,"bridge");
        first.client.onReceivedError(first,new WebResourceRequest(MainActivity.ORIGIN+"/poster.png",false,"GET"),new WebResourceError());check(policy(activity).loading(),"subresource network error ignored");
        first.client.onReceivedHttpError(first,new WebResourceRequest(first.url,false,"GET"),new WebResourceResponse("text/plain","UTF-8",503,"Error",Collections.emptyMap(),null));check(policy(activity).loading(),"subresource HTTP error ignored");
        first.client.onReceivedError(first,new WebResourceRequest(first.url,true,"GET"),new WebResourceError());
        check(policy(activity).failed()&&panel(activity)!=null&&!loaded(activity),"main-document error displays native fallback");
        List<Button> buttons=buttons(activity);check(buttons.size()==3,"TV retry software exit all present");check(buttons.get(0).focused&&buttons.get(0).parent==panel(activity),"retry has TV focus after attached");for(Button button:buttons)check(button.focusable&&button.focusableInTouchMode,"TV native action focusable");
        check(first.visibility==View.INVISIBLE&&panel(activity).clickable,"native recovery hides failed web focus target and consumes touch");
        String diagnostic=text(activity);check(diagnostic.contains("page_error")&&diagnostic.contains("Android API 35")&&diagnostic.contains("69.0.3497.100"),"finite error and WebView diagnostics");check(!diagnostic.contains(oldToken)&&!diagnostic.contains("https://"),"diagnostic excludes page URL and token");
        int constructed=WebView.constructed;buttons.get(0).performClick();WebView second=web(activity);
        check(second!=first&&WebView.constructed==constructed+1,"retry creates fresh WebView");check(first.destroyed&&first.stopped&&first.interfaces.isEmpty()&&oldBridge.isClosed,"retry releases old WebView and bridge");check(!oldToken.equals(token(second)),"retry token rotated");
        ready(oldInterface,oldToken);fail(oldInterface,oldToken,"bootstrap_error");check(policy(activity).loading(),"stale old callbacks cannot complete or fail new view");
        first.client.onRenderProcessGone(first,new RenderProcessGoneDetail());check(web(activity)==second&&!second.destroyed,"stale renderer exit cannot kill current view");
        constructed=WebView.constructed;timeout(activity);check(policy(activity).failed()&&"timeout".equals(policy(activity).failure()),"timeout displays terminal native fallback");check(WebView.constructed==constructed,"no automatic reload loop");
        ready(startup(second),token(second));check(policy(activity).failed(),"late ready does not dismiss timeout");
        buttons(activity).get(1).performClick();WebView software=web(activity);check(software.layer==View.LAYER_TYPE_SOFTWARE,"TV explicit software action changes WebView layer only");check((boolean)field(activity,"softwareRendering"),"software mode visible state");
        Bundle saved=new Bundle();activity.onSaveInstanceState(saved);check(saved.getBoolean("startupSoftwareRendering",false),"mode saved across recreation without clearing records");
        timeout(activity);check(text(activity).contains("网页渲染：软件"),"software render status displayed");check(buttons(activity).get(1).text.contains("默认"),"TV allows switching back to default");buttons(activity).get(1).performClick();check(web(activity).layer!=View.LAYER_TYPE_SOFTWARE,"default retry restores default renderer");
        timeout(activity);buttons(activity).get(2).performClick();check(activity.finishing,"native exit usable without JS");activity.onDestroy();
    }
    private static void errorsAndRenderer()throws Exception{
        for(String code:new String[]{"bootstrap_error","script_error","unsupported_webview"}){MainActivity activity=start(true);WebView view=web(activity);fail(startup(view),token(view),code);check(policy(activity).failed()&&text(activity).contains(code),"JS bootstrap failure native UI "+code);activity.onDestroy();}
        MainActivity http=start(true);WebView h=web(http);h.client.onReceivedHttpError(h,new WebResourceRequest(h.url,true,"GET"),new WebResourceResponse("text/plain","UTF-8",404,"Not Found",Collections.emptyMap(),null));check("http_error".equals(policy(http).failure()),"main HTTP status displays native UI");http.onDestroy();
        MainActivity ssl=start(true);WebView s=web(ssl);SslErrorHandler sub=new SslErrorHandler();s.client.onReceivedSslError(s,sub,new SslError("https://example.invalid/image"));check(sub.cancelled&&policy(ssl).loading(),"subresource TLS error cancelled not accepted");SslErrorHandler main=new SslErrorHandler();s.client.onReceivedSslError(s,main,new SslError(s.url));check(main.cancelled&&"ssl_error".equals(policy(ssl).failure()),"main TLS error cancelled and reported");ssl.onDestroy();
        MainActivity renderer=start(true);WebView dead=web(renderer);Object bridge=startup(dead);String token=token(dead);ready(bridge,token);NativeBridge nativeBridge=(NativeBridge)field(renderer,"bridge");
        dead.stopThrows=true;dead.destroyThrows=true;
        check(dead.client.onRenderProcessGone(dead,new RenderProcessGoneDetail()),"renderer exit handled even cleanup operations throw");check(dead.destroyed&&dead.interfaces.isEmpty()&&nativeBridge.isClosed&&web(renderer)==null,"dead renderer WebView and native bridge released");check("render_process_gone".equals(policy(renderer).failure())&&panel(renderer)!=null&&!loaded(renderer),"post-ready renderer crash returns native UI");
        buttons(renderer).get(0).performClick();WebView replacement=web(renderer);check(replacement!=null&&replacement!=dead,"renderer retry builds new WebView");ready(bridge,token);check(policy(renderer).loading(),"dead view callback cannot acknowledge replacement");ready(startup(replacement),token(replacement));check(loaded(renderer),"replacement commit recovers app");renderer.onDestroy();
        WebView.failNext=true;MainActivity unavailable=start(true);check(web(unavailable)==null&&policy(unavailable).failed()&&"webview_unavailable".equals(policy(unavailable).failure()),"WebView provider creation failure not silent black");check(buttons(unavailable).size()==3&&!text(unavailable).contains("private.invalid"),"provider error sanitized and retry available");buttons(unavailable).get(0).performClick();check(web(unavailable)!=null&&policy(unavailable).loading(),"provider retry can recreate later");unavailable.onDestroy();
        WebView.failDebugNext=true;MainActivity missingProvider=start(true);check(web(missingProvider)==null&&policy(missingProvider).failed()&&panel(missingProvider)!=null,"static provider initialization failure occurs inside visible native recovery UI");buttons(missingProvider).get(0).performClick();check(web(missingProvider)!=null,"static provider failure remains manually retryable");missingProvider.onDestroy();
    }
    private static void phoneAndLifecycle()throws Exception{
        MainActivity phone=start(false);WebView view=web(phone);check(phone.window.softInput==WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,"phone resize policy retained");phone.onConfigurationChanged(new Configuration());check(web(phone)==view,"phone rotation does not recreate WebView");
        int dialogs=AlertDialog.shown;phone.onBackPressed();check(AlertDialog.shown==dialogs+1&&view.scripts.isEmpty(),"pre-ready back uses native exit confirmation");
        timeout(phone);check(buttons(phone).size()==2,"phone has retry and exit without TV GPU option");call(phone,"startWebView",new Class<?>[]{boolean.class},true);view=web(phone);check(view.layer!=View.LAYER_TYPE_SOFTWARE,"phone never force software rendering");
        ready(startup(view),token(view));int scripts=view.scripts.size();phone.onBackPressed();check(view.scripts.size()==scripts+1&&view.scripts.get(scripts).contains("__androidBack"),"ready back retains SPA back handler");
        phone.onPause();phone.onResume();check(view.pauses==1&&view.resumes==1,"WebView lifecycle retained");phone.onDestroy();
        MainActivity closing=start(true);WebView old=web(closing);Object bridge=startup(old);String token=token(old);closing.finish();ready(bridge,token);check(!loaded(closing),"finishing activity ignores commit");closing.onDestroy();
        WebView.info.packageName="pkg/https://secret?token=private";WebView.info.versionName="69\nPRIVATE😀";MainActivity sanitized=start(true);timeout(sanitized);String diag=text(sanitized);check(!diag.contains("https://")&&!diag.contains("?token="),"diagnostic package values use finite alphabet and tolerate supplementary Unicode");sanitized.onDestroy();WebView.info=new PackageInfo();
        WebView.packageFailure=true;MainActivity unknown=start(true);timeout(unknown);check(text(unknown).contains("版本读取失败"),"diagnostic version read failure does not kill fallback");unknown.onDestroy();WebView.packageFailure=false;
        BuildConfig.DEBUG=false;MainActivity release=start(true);check(!WebView.debugging&&web(release).chrome==null,"release startup also works without debug console interface");release.onDestroy();BuildConfig.DEBUG=true;
    }
    private static void queuedCallbacks()throws Exception{
        MainActivity activity=start(true);WebView first=web(activity);Handler handler=timer(activity);handler.enqueuePosts=true;
        ready(startup(first),token(first));fail(startup(first),token(first),"bootstrap_error");check(handler.posted.size()==2&&!loaded(activity),"JS callbacks queue onto actual UI handler");
        timeout(activity);buttons(activity).get(0).performClick();WebView second=web(activity);handler.flush();check(policy(activity).loading()&&!loaded(activity),"callbacks queued before retry cannot acknowledge or fail new document");
        ready(startup(second),token(second));check(!loaded(activity),"current ack remains queued until UI dispatch");handler.flush();check(loaded(activity),"current queued ack accepted on UI");
        call(activity,"startWebView",new Class<?>[]{boolean.class},false);WebView third=web(activity);ready(startup(third),token(third));activity.onDestroy();handler.flush();check(!policy(activity).isReady()&&web(activity)==null,"ack queued before destroy cannot resurrect view");
    }
    private static void tvMenuDiagnostics()throws Exception{
        MainActivity activity=start(true);WebView original=web(activity);String originalToken=token(original);ready(startup(original),originalToken);
        int created=WebView.constructed,dialogs=AlertDialog.shown,scripts=original.scripts.size();
        check(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU)),"TV MENU opens native diagnostics after JS ready");
        check(policy(activity).isReady()&&loaded(activity)&&policy(activity).failure().isEmpty(),"manual diagnostic preserves ready status without fake error");
        check(web(activity)==original&&WebView.constructed==created&&originalToken.equals(token(web(activity))),"opening manual diagnostics never reloads document");
        check(panel(activity)!=null&&text(activity).contains("启动与网页显示诊断")&&text(activity).contains("69.0.3497.100")&&text(activity).contains("首页已完成挂载"),"ready silent-black case gets finite native WebView diagnostics");
        check(!text(activity).contains("page_error")&&!text(activity).contains(originalToken),"manual diagnostic never fabricates page error or exposes token");
        List<Button> actions=buttons(activity);check(actions.size()==4&&actions.get(0).text.equals("继续使用"),"live mounted view has native continue action");
        check(actions.get(1).focused&&actions.get(1).focusable&&actions.get(1).text.contains("软件渲染"),"software retry is immediately remote-focus reachable");
        LinearLayout current=panel(activity);check(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU,2))&&panel(activity)==current,"held MENU does not rebuild repeated panels");
        check(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MENU))&&panel(activity)==current,"MENU up consumed without duplicate action");
        activity.onBackPressed();check(panel(activity)==null&&original.visibility==View.VISIBLE&&original.focused,"Back closes manual panel and restores live page focus");
        check(policy(activity).isReady()&&loaded(activity)&&web(activity)==original&&WebView.constructed==created,"Back from diagnostics preserves mounted page without reload");
        check(original.scripts.size()==scripts&&AlertDialog.shown==dialogs,"diagnostic Back neither triggers SPA Back nor exit dialog");
        check(!activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK))&&activity.delegatedKeyEvents==1,"ordinary remote Back remains framework handled");
        activity.onBackPressed();check(original.scripts.size()==scripts+1,"ordinary TV Back retains original SPA handler after closing diagnostics");
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));buttons(activity).get(0).performClick();check(panel(activity)==null&&policy(activity).isReady()&&WebView.constructed==created,"continue action dismisses without reload or state change");
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));buttons(activity).get(1).performClick();WebView software=web(activity);
        check(software!=original&&original.destroyed&&software.layer==View.LAYER_TYPE_SOFTWARE&&WebView.constructed==created+1,"only explicit software retry rebuilds WebView");check(policy(activity).loading()&&!(boolean)field(activity,"manualDiagnostics"),"explicit retry begins normal new startup attempt");
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));check(text(activity).contains("正在等待首页挂载")&&buttons(activity).size()==3,"MENU remains available before ready without continue to unusable page");
        activity.onBackPressed();check(policy(activity).loading()&&text(activity).contains("正在启动红果")&&timer(activity).delayed.size()==1,"cancel during loading restores native loading with original bounded timer");
        timeout(activity);String failure=policy(activity).failure();activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));check(policy(activity).failed()&&failure.equals(policy(activity).failure()),"manual diagnostic preserves actual prior failure");activity.onBackPressed();check(text(activity).contains("首页启动未完成")&&failure.equals(policy(activity).failure()),"cancel failure diagnostics restores actual native failure screen");activity.onDestroy();
        MainActivity phone=start(false);WebView phoneView=web(phone);ready(startup(phoneView),token(phoneView));check(!phone.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU))&&phone.delegatedKeyEvents==1&&panel(phone)==null,"phone MENU is not intercepted or given TV diagnostic UI");phone.onDestroy();
    }
    public static void main(String[] args)throws Exception{
        policyTests();loadingAndTrust();failureAndRetry();errorsAndRenderer();phoneAndLifecycle();queuedCallbacks();tvMenuDiagnostics();
        System.out.println("STARTUP_NATIVE_OK checks="+checks+" actualMainActivity=true boundedWaitMs="+StartupPolicy.TIMEOUT_MS+" networkRequests=0 deviceTests=not_run");
    }
}
