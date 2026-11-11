import android.app.UiAutomation;
import android.os.HandlerThread;
import android.os.Looper;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.xmlpull.v1.XmlSerializer;
import java.io.StringWriter;

/** Shell-only QA reader: actual accessibility tree, without waiting for video to go idle. */
public final class NativeSnapshot {
    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
    private static void write(XmlSerializer xml, AccessibilityNodeInfo node) throws Exception {
        if (node == null) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        xml.startTag(null, "node");
        xml.attribute(null, "text", text(node.getText()));
        xml.attribute(null, "class", text(node.getClassName()));
        xml.attribute(null, "resource-id", text(node.getViewIdResourceName()));
        xml.attribute(null, "content-desc", text(node.getContentDescription()));
        xml.attribute(null, "focused", String.valueOf(node.isFocused()));
        xml.attribute(null, "enabled", String.valueOf(node.isEnabled()));
        xml.attribute(null, "clickable", String.valueOf(node.isClickable()));
        xml.attribute(null, "bounds", "[" + bounds.left + "," + bounds.top + "][" + bounds.right + "," + bounds.bottom + "]");
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) { write(xml, child); child.recycle(); } }
        xml.endTag(null, "node");
    }
    public static void main(String[] args) throws Exception {
        HandlerThread thread = new HandlerThread("HongguoQaAccessibility"); thread.start();
        UiAutomation automation = null;
        try {
            Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
            java.lang.reflect.Constructor<?> constructor = UiAutomation.class.getDeclaredConstructor(Looper.class, connectionType); constructor.setAccessible(true);
            automation = (UiAutomation) constructor.newInstance(thread.getLooper(), connection);
            java.lang.reflect.Method connect = UiAutomation.class.getDeclaredMethod("connect"); connect.setAccessible(true); connect.invoke(automation);
            AccessibilityNodeInfo root = null;
            for (int attempt = 0; attempt < 40 && root == null; attempt++) { android.os.SystemClock.sleep(50); root = automation.getRootInActiveWindow(); }
            if (root == null) throw new IllegalStateException("No actual active accessibility window");
            StringWriter output = new StringWriter(); XmlSerializer xml = android.util.Xml.newSerializer(); xml.setOutput(output);
            xml.startDocument("UTF-8", true); xml.startTag(null, "hierarchy"); write(xml, root); xml.endTag(null, "hierarchy"); xml.endDocument(); root.recycle();
            System.out.println(output);
        } finally {
            if (automation != null) { try { java.lang.reflect.Method disconnect = UiAutomation.class.getDeclaredMethod("disconnect"); disconnect.setAccessible(true); disconnect.invoke(automation); } catch (Exception ignored) {} }
            thread.quitSafely();
        }
    }
}
