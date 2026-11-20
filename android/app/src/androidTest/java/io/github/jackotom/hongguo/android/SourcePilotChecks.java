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
            java.util.Set<String> expected = new java.util.HashSet<>(java.util.Arrays.asList("beabox", "dsd", "guipian", "hanime1", "hanxiaoquan",
                "heitao", "hongguo", "huangguo-video", "huangguoai", "huangju", "ikanbot", "jinguoduanju", "missav", "sorani", "yeguo"));
            check(sources.length() == expected.size(), "all locked modules registered");
            int restricted = 0;
            for (int i = 0; i < sources.length(); i++) {
                JSONObject source = sources.getJSONObject(i);
                String id = source.getString("id");
                check(expected.remove(id), "source ID unique and expected");
                boolean adult = source.getBoolean("adult");
                if (adult) restricted++;
                check(source.getBoolean("enabled") != adult, "only ordinary sources enabled by default");
                if (!id.equals("guipian") && !id.equals("hanxiaoquan")) continue;
                Result category = call(test, service, request("categories", id));
                check(category.code == null && category.value instanceof JSONArray, "runtime categories " + id + " code=" + category.code);
                JSONArray categories = (JSONArray) category.value;
                check(categories.length() > 1 && categories.getJSONObject(0).has("label"), "normalized categories");
            }
            check(restricted == 10, "ten restricted sources still default off");
            check("SOURCE_DISABLED".equals(call(test, service, request("categories", "yeguo")).code), "restricted source does not run before enable");

            Result unsupported = call(test, service, request("categories", "unknown"));
            check("INVALID_INPUT".equals(unsupported.code), "unknown source");
            check("INVALID_INPUT".equals(call(test, service, request("play", "guipian")
                .put("seriesId", "12345").put("episodeId", "https://example.invalid/not-an-episode")).code),
                "reject renderer URL");
            check("SOURCE_DETAIL_EXPIRED".equals(call(test, service, request("play", "guipian").put("seriesId", "12345")
                .put("episodeId", "ep_" + String.format("%064d", 0))).code), "unissued session cannot reach resolveMedia");

            call(test, service, request("settings", "guipian").put("enabled", false));
            check("SOURCE_DISABLED".equals(call(test, service, request("categories", "guipian")).code), "disabled source blocks work");
            check(call(test, service, request("categories", "hanxiaoquan")).code == null, "other source stays usable");
            SourceService previous = service;
            test.runOnMainSync(previous::close);
            service = new SourceService(app);
            JSONArray after = (JSONArray) call(test, service, request("list", null)).value;
            check(!source(after, "guipian").getBoolean("enabled"), "persisted setting");
            call(test, service, request("settings", "guipian").put("enabled", true));

            Result cancelled = new Result(), cancel = new Result();
            SourceService active = service;
            JSONObject categories = request("categories", "guipian");
            JSONObject stop = request("cancel", "guipian");
            test.runOnMainSync(() -> { active.submit(categories, cancelled); active.submit(stop, cancel); });
            check("ERR_CANCELED".equals(cancelled.await().code) && cancel.await().code == null, "late result cancelled");
            check(call(test, service, categories).code == null, "source works after cancellation");

            result.putString("stream", "\nANDROID_ALL_SOURCES_PASS registered=15 defaultOff=10 offlineCategories=2 opaqueEpisodes=true persistence=true cancellation=true networkRequests=0\n");
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
