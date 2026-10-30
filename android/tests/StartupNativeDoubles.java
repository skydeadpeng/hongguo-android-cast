package io.github.jackotom.hongguo.android;

import java.util.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class BuildConfig { static boolean IS_TV=true, DEBUG=true; static final String VERSION_NAME="fixture"; }
class Bundle { final Map<String,Boolean> values=new HashMap<>();boolean getBoolean(String key,boolean fallback){return values.getOrDefault(key,fallback);}void putBoolean(String key,boolean value){values.put(key,value);} }
class Configuration {}
class Looper { static Looper getMainLooper(){return new Looper();} }
class Handler { final List<Runnable> delayed=new ArrayList<>(),posted=new ArrayList<>();boolean enqueuePosts;Handler(Looper looper){}boolean post(Runnable action){if(enqueuePosts)posted.add(action);else action.run();return true;}boolean postDelayed(Runnable action,long delay){delayed.add(action);return true;}void removeCallbacks(Runnable action){delayed.remove(action);posted.remove(action);}void expire(){for(Runnable action:new ArrayList<>(delayed))action.run();}void flush(){List<Runnable> queue=new ArrayList<>(posted);posted.clear();for(Runnable action:queue)action.run();} }
class Activity {
    boolean finishing,destroyed;int delegatedKeyEvents; View content; final Window window=new Window(); final AssetManager assets=new AssetManager();
    public void onCreate(Bundle state){}protected void onResume(){}protected void onPause(){}protected void onDestroy(){destroyed=true;}protected void onSaveInstanceState(Bundle state){}public void onConfigurationChanged(Configuration config){}public void onBackPressed(){}public boolean dispatchKeyEvent(KeyEvent event){delegatedKeyEvents++;return false;}
    Window getWindow(){return window;}void setContentView(View view){content=view;}void finish(){finishing=true;}boolean isFinishing(){return finishing;}void runOnUiThread(Runnable action){action.run();}AssetManager getAssets(){return assets;}Resources getResources(){return new Resources();}
}
class Resources { Metrics getDisplayMetrics(){return new Metrics();} } class Metrics { float density=1f; }
class Window {int softInput;void setSoftInputMode(int value){softInput=value;}void setDecorFitsSystemWindows(boolean value){} }
class WindowManager {static class LayoutParams {static final int SOFT_INPUT_ADJUST_RESIZE=16;} }
class Insets {int left,top,right,bottom;} class WindowInsets {static final WindowInsets CONSUMED=new WindowInsets();Insets getInsets(int type){return new Insets();}static class Type{static int systemBars(){return 1;}static int displayCutout(){return 2;}static int ime(){return 4;}} }
class View {
    static final int LAYER_TYPE_SOFTWARE=1,VISIBLE=0,INVISIBLE=4;boolean focusable,focusableInTouchMode,focused,clickable;int layer,visibility,paddingLeft,paddingTop,paddingRight,paddingBottom;Object parent;OnClickListener click;
    View(Activity context){}void setBackgroundColor(int color){}void setFocusable(boolean value){focusable=value;}void setFocusableInTouchMode(boolean value){focusableInTouchMode=value;}boolean requestFocus(){focused=true;return true;}void setPadding(int left,int top,int right,int bottom){paddingLeft=left;paddingTop=top;paddingRight=right;paddingBottom=bottom;}void setLayerType(int type,Object paint){layer=type;}void setVisibility(int value){visibility=value;}void setClickable(boolean value){clickable=value;}void setOnClickListener(OnClickListener listener){click=listener;}void performClick(){if(click!=null)click.onClick(this);}void setOnApplyWindowInsetsListener(InsetsListener listener){}void requestApplyInsets(){}
    interface OnClickListener {void onClick(View view);}interface InsetsListener {WindowInsets onApply(View view,WindowInsets insets);}
}
class FrameLayout extends View {final List<View> children=new ArrayList<>();FrameLayout(Activity context){super(context);}void addView(View view,LayoutParams params){children.add(view);view.parent=this;}void addView(View view,int index,LayoutParams params){children.add(index,view);view.parent=this;}void removeView(View view){children.remove(view);view.parent=null;}static class LayoutParams{LayoutParams(int width,int height){}} }
class LinearLayout extends View {static final int VERTICAL=1;final List<View> children=new ArrayList<>();LinearLayout(Activity context){super(context);}void setOrientation(int value){}void setGravity(int value){}void addView(View view,LayoutParams params){children.add(view);view.parent=this;}static class LayoutParams{LayoutParams(int width,int height){}} }
class TextView extends View {String text="";TextView(Activity context){super(context);}void setText(String value){text=value;}void setTextColor(int color){}void setTextSize(int size){}void setGravity(int value){} }
class Button extends TextView {Button(Activity context){super(context);}void setAllCaps(boolean value){} }
class Gravity {static final int CENTER=17;}
class KeyEvent {static final int KEYCODE_MENU=82,KEYCODE_BACK=4,ACTION_DOWN=0,ACTION_UP=1;final int action,code,repeat;KeyEvent(int action,int code){this(action,code,0);}KeyEvent(int action,int code,int repeat){this.action=action;this.code=code;this.repeat=repeat;}int getAction(){return action;}int getKeyCode(){return code;}int getRepeatCount(){return repeat;}}
class Build {static class VERSION {static int SDK_INT=35;} }
class PackageInfo {String packageName="com.android.webview",versionName="69.0.3497.100";}
class WebSettings {static final int MIXED_CONTENT_NEVER_ALLOW=1;boolean javascript,dom,file,content,fileFromFile,universal,multiple,popup,gesture;int mixed;void setJavaScriptEnabled(boolean value){javascript=value;}void setDomStorageEnabled(boolean value){dom=value;}void setAllowFileAccess(boolean value){file=value;}void setAllowContentAccess(boolean value){content=value;}void setAllowFileAccessFromFileURLs(boolean value){fileFromFile=value;}void setAllowUniversalAccessFromFileURLs(boolean value){universal=value;}void setMixedContentMode(int value){mixed=value;}void setJavaScriptCanOpenWindowsAutomatically(boolean value){popup=value;}void setSupportMultipleWindows(boolean value){multiple=value;}void setMediaPlaybackRequiresUserGesture(boolean value){gesture=value;} }
class WebView extends View {
    static boolean failNext,failDebugNext,packageFailure,debugging;static PackageInfo info=new PackageInfo();static int constructed;
    String url;boolean destroyed,stopped,stopThrows,destroyThrows;int resumes,pauses;final Map<String,Object> interfaces=new HashMap<>();final WebSettings settings=new WebSettings();WebViewClient client;WebChromeClient chrome;List<String> scripts=new ArrayList<>();
    WebView(Activity context){super(context);if(failNext){failNext=false;throw new RuntimeException("provider missing https://private.invalid/?token=secret");}constructed++;}
    static void setWebContentsDebuggingEnabled(boolean value){if(failDebugNext){failDebugNext=false;throw new RuntimeException("provider initialization");}debugging=value;}static PackageInfo getCurrentWebViewPackage(){if(packageFailure)throw new RuntimeException("provider");return info;}
    WebSettings getSettings(){return settings;}void setWebViewClient(WebViewClient value){client=value;}void setWebChromeClient(WebChromeClient value){chrome=value;}void addJavascriptInterface(Object object,String name){interfaces.put(name,object);}void removeJavascriptInterface(String name){interfaces.remove(name);}void loadUrl(String value){url=value;}String getUrl(){return url;}void stopLoading(){stopped=true;if(stopThrows)throw new RuntimeException("dead renderer");}void destroy(){destroyed=true;if(destroyThrows)throw new RuntimeException("already destroyed");}void onResume(){resumes++;}void onPause(){pauses++;}void evaluateJavascript(String source,ValueCallback<String> callback){scripts.add(source);if(callback!=null)callback.onReceiveValue("false");}
}
interface ValueCallback<T>{void onReceiveValue(T value);}
@interface JavascriptInterface {}
class WebChromeClient {boolean onConsoleMessage(ConsoleMessage message){return false;} }
class ConsoleMessage {enum MessageLevel{ERROR,LOG}MessageLevel messageLevel(){return MessageLevel.ERROR;}String message(){return "error";}int lineNumber(){return 1;} }
class Log {static int e(String tag,String text){return 0;} }
class WebViewClient {public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return false;}public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return null;}public void onPageFinished(WebView view,String url){}public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){}public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){}public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){}public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){return false;} }
class WebResourceRequest {final Uri uri;final boolean main;final String method;WebResourceRequest(String url,boolean main,String method){uri=Uri.parse(url);this.main=main;this.method=method;}Uri getUrl(){return uri;}boolean isForMainFrame(){return main;}String getMethod(){return method;} }
class WebResourceError {}class RenderProcessGoneDetail {}
class SslError {final String url;SslError(String value){url=value;}String getUrl(){return url;} }class SslErrorHandler {boolean cancelled;void cancel(){cancelled=true;} }
class WebResourceResponse {final int status;final Map<String,String> headers;WebResourceResponse(String type,String charset,int status,String message,Map<String,String> headers,InputStream body){this.status=status;this.headers=headers;}int getStatusCode(){return status;} }
class CookieManager {static final CookieManager instance=new CookieManager();boolean accept,third;static CookieManager getInstance(){return instance;}void setAcceptCookie(boolean value){accept=value;}void setAcceptThirdPartyCookies(WebView view,boolean value){third=value;} }
class Uri {final URI uri;Uri(String value){uri=URI.create(value);}static Uri parse(String value){return new Uri(value);}String getScheme(){return uri.getScheme();}String getHost(){return uri.getHost();}int getPort(){return uri.getPort();}String getUserInfo(){return uri.getUserInfo();}String getPath(){return uri.getPath();}String getQueryParameter(String key){String query=uri.getRawQuery();if(query==null)return null;for(String value:query.split("&")){String[] pair=value.split("=",2);if(URLDecoder.decode(pair[0],StandardCharsets.UTF_8).equals(key))return pair.length>1?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"";}return null;} }
class AssetManager {boolean absent;InputStream open(String path)throws IOException{if(absent)throw new IOException("missing");return new ByteArrayInputStream("local asset".getBytes(StandardCharsets.UTF_8));} }
class NativeBridge {static int created,closed;boolean isClosed;NativeBridge(MainActivity activity){created++;}void close(){if(!isClosed){isClosed=true;closed++;}} }
class CryptoBridge {}
class PlaybackSources {static int attached,detached;static void attach(MainActivity activity){attached++;}static void detach(MainActivity activity){detached++;} }
class AlertDialog {static int shown;static class Builder{Builder(Activity activity){}Builder setTitle(String value){return this;}Builder setMessage(String value){return this;}Builder setNegativeButton(String value,Listener listener){return this;}Builder setPositiveButton(String value,Listener listener){return this;}void show(){shown++;}}interface Listener{void onClick(Object dialog,int which);} }

/** Account runtime is independently covered; startup gate keeps all actual MainActivity bodies. */
class AccountSync {
    static final AccountSync instance=new AccountSync();Runnable listener;int foregroundCalls,flushCalls;
    static AccountSync get(){return instance;}
    String ownerStamp(){return "guest";}
    void setListener(Runnable value){listener=value;}
    void clearListener(Runnable expected){if(listener==expected)listener=null;}
    void foreground(){foregroundCalls++;}
    void flush(){flushCalls++;}
}

/** Collaborator boundaries only: startup executes the unmodified production MainActivity. */
class AppUpdater {
    AppUpdater(MainActivity host){}
    final class Bridge {}
    void pageReady(){} void pageUnavailable(){} void resume(){} void pause(){} void destroy(){}
}
class OfficialSeriesBridge {
    OfficialSeriesBridge(MainActivity host){}
    void resume(){} void close(){}
}
