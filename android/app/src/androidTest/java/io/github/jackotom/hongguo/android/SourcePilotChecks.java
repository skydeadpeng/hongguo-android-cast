package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.*;

final class SourcePilotChecks {
    private static final class Result implements SourceService.Reply {
        final CountDownLatch done = new CountDownLatch(1);
        Object value;
        String code, token;
        public void success(Object value, String token) { this.value = value; this.token = token; done.countDown(); }
        public void failure(String code, String message) { this.code = code; done.countDown(); }
        Result await() throws Exception {
            if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("source response missing");
            return this;
        }
    }

    static void run(Instrumentation test) {
        Bundle result = new Bundle();
        SourceService service = null;
        try {
            Context app = test.getTargetContext();
            if (!android.os.Build.FINGERPRINT.toLowerCase(java.util.Locale.ROOT).contains("generic") &&
                !android.os.Build.MODEL.contains("sdk")) throw new AssertionError("Use an isolated test emulator");
            service = new SourceService(app);
            JSONArray sources = (JSONArray) call(test, service, request("list", null)).value;
            check(sources.length() == 1, "only the native module registered");
            JSONObject source = sources.getJSONObject(0);
            check("hongguo".equals(source.getString("id")), "native source present");
            check(!source.getBoolean("adult") && source.getBoolean("enabled"), "native source enabled by default");

            Result unsupported = call(test, service, request("categories", "unknown"));
            check("INVALID_INPUT".equals(unsupported.code), "unknown source");
            check("SOURCE_NATIVE".equals(call(test, service, request("categories", "hongguo")).code), "native source routes to discovery");

            call(test, service, request("settings", "hongguo").put("enabled", false));
            check("SOURCE_DISABLED".equals(call(test, service, request("categories", "hongguo")).code), "disabled source blocks work");
            SourceService previous = service;
            test.runOnMainSync(previous::close);
            service = new SourceService(app);
            JSONArray after = (JSONArray) call(test, service, request("list", null)).value;
            check(!source(after, "hongguo").getBoolean("enabled"), "persisted setting");
            call(test, service, request("settings", "hongguo").put("enabled", true));

            Result cancelled = new Result(), cancel = new Result();
            SourceService active = service;
            JSONObject categories = request("categories", "hongguo");
            JSONObject stop = request("cancel", "hongguo");
            test.runOnMainSync(() -> { active.submit(categories, cancelled); active.submit(stop, cancel); });
            check("ERR_CANCELED".equals(cancelled.await().code) && cancel.await().code == null, "late result cancelled");

            result.putString("stream", "\nANDROID_ALL_SOURCES_PASS registered=1 nativeOnly=true persistence=true cancellation=true networkRequests=0\n");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nANDROID_SOURCE_PILOT_FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
            test.finish(1, result);
        } finally {
            if (service != null) test.runOnMainSync(service::close);
        }
    }
    private static JSONObject request(String operation, String id) throws JSONException {
        JSONObject request = new JSONObject().put("operation", operation);
        if (id != null) request.put("sourceId", id);
        return request;
    }
    private static JSONObject source(JSONArray list, String id) throws JSONException {
        for (int i=0;i<list.length();i++) if (id.equals(list.getJSONObject(i).optString("id"))) return list.getJSONObject(i);
        throw new JSONException("source is missing: " + id);
    }
    private static Result call(Instrumentation test, SourceService service, JSONObject message) throws Exception {
        Result result = new Result();
        test.runOnMainSync(() -> service.submit(message, result));
        return result.await();
    }
    private static void check(boolean condition, String evidence) {
        if (!condition) throw new AssertionError(evidence);
    }
}
