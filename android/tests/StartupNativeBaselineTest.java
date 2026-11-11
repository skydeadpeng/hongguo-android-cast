package io.github.jackotom.hongguo.android;
import java.lang.reflect.*;
final class StartupNativeBaselineTest {
    public static void main(String[] args)throws Exception{
        MainActivity activity=new MainActivity();activity.onCreate(null);
        Field field=MainActivity.class.getDeclaredField("web");field.setAccessible(true);WebView web=(WebView)field.get(activity);
        web.client.onPageFinished(web,web.url);
        Field loaded=MainActivity.class.getDeclaredField("loaded");loaded.setAccessible(true);
        if(loaded.getBoolean(activity))throw new AssertionError("baseline onPageFinished incorrectly acknowledges startup without React commit; native loading/failure UI and AndroidStartup handshake absent");
        if(!web.interfaces.containsKey("AndroidStartup"))throw new AssertionError("baseline has no startup acknowledgement, timeout or native retry UI");
    }
}
