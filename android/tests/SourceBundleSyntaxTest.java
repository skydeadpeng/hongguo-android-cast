import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/** Evaluates packaged scripts with only in-memory host replies, never with a network host. */
public final class SourceBundleSyntaxTest {
    private static final String FIXTURE = """
        var module = {exports: {}};
        var categoryFixture = "离线分类";
        var fixtureCache = {};
        host.hexDecode = function (value) {
          return new Uint8Array(value.match(/../g).map(function (pair) { return parseInt(pair, 16); }));
        };
        host.utf8Encode = function (value) {
          var encoded = unescape(encodeURIComponent(value)), result = [];
          for (var i = 0; i < encoded.length; i++) result.push(encoded.charCodeAt(i));
          return new Uint8Array(result);
        };
        host.utf8Decode = function (bytes) {
          return decodeURIComponent(escape(String.fromCharCode.apply(null, Array.from(bytes))));
        };
        host.hexEncode = function (bytes) {
          return Array.from(bytes).map(function (byte) { return ("0" + byte.toString(16)).slice(-2); }).join("");
        };
        host.base64Decode = function () { return new Uint8Array(16); };
        host.dramaId = function (id) { return host.source + ":" + id; };
        host.chapterId = function (id, part) { return host.source + ":" + id + ":" + part; };
        host.randomBytes = function (count) { return new Uint8Array(count); };
        host.cacheGet = function (key) {
          return Object.prototype.hasOwnProperty.call(fixtureCache, key) ? fixtureCache[key] : null;
        };
        host.cacheSet = function (key, body) { fixtureCache[key] = body; };
        host.exclusive = function (callback) { return callback(); };
        host.preserveIntegers = function (value) { return value; };
        host.parseUrl = function (value) {
          var match = /^([a-z]+):[/][/](?:([^/@]+)@)?([^:/?#]+)(?::([0-9]+))?([^?#]*)(?:[?]([^#]*))?(?:[#](.*))?$/i.exec(value);
          if (!match) throw new Error("offline fixture URL shape");
          return {scheme: match[1], userInfo: !!match[2], host: match[3] + (match[4] ? ":" + match[4] : ""),
            hostname: match[3], port: match[4] || "", path: match[5] || "/", query: match[6] || "", fragment: match[7] || "", href: value};
        };
        host.resolveUrl = function (value, reference) {
          if (reference.indexOf("https://") === 0) return reference;
          var base = host.parseUrl(value);
          return base.scheme + "://" + base.host + (reference.charAt(0) === "/" ? reference : "/" + reference);
        };
        host.fetchText = function (request) {
          return {status: 200, body: "", url: request.url};
        };
        host.parseHtml = function () {
          return { tag: "html", children: [{
            tag: "a", attrs: { href: "/fixture", "class": "nav-desktop-items" }, children: [{text: categoryFixture}]
          }] };
        };
        host.fetch = function (request) {
          throw new Error("offline fixture has no host response");
        };
        """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        check(args.length >= 2, "expected the assets directory and every module ID");
        var assets = Path.of(args[0]);
        Set<String> seen = new HashSet<>();
        int categories = 0;
        for (int i = 1; i < args.length; i++) {
            String id = args[i];
            check(id.matches("[a-z][a-z0-9-]{1,30}") && seen.add(id), "unrecognized module ID");
            var file = assets.resolve(id).resolve("runtime.js");
            String code = Files.readString(file, StandardCharsets.UTF_8);
            long deadline = System.nanoTime() + 8_000_000_000L;
            ContextFactory engine = new ContextFactory() {
                @Override protected Context makeContext() {
                    Context cx = super.makeContext();
                    cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
                    cx.setInterpretedMode(true);
                    cx.setClassShutter(name -> false);
                    cx.setInstructionObserverThreshold(10000);
                    return cx;
                }
                @Override protected void observeInstructionCount(Context context, int count) {
                    if (System.nanoTime() > deadline) throw new AssertionError("offline module timeout");
                }
            };
            int count = engine.call(cx -> {
                Scriptable global = cx.initSafeStandardObjects();
                Scriptable host = cx.newObject(global);
                ScriptableObject.putProperty(host, "source", id);
                ScriptableObject.putProperty(global, "host", host);
                cx.evaluateString(global, FIXTURE, "offline-fixture", 1, null);
                cx.evaluateString(global, "'use strict';\n" + code, id + "/runtime.js", 1, null);
                Object result = cx.evaluateString(global, """
                    (function () {
                      var api = module.exports;
                      if (["categories", "catalogPage", "detail", "resolveMedia"].some(function (key) {
                        return typeof api[key] !== "function";
                      })) return -1;
                      var response = api.categories(false);
                      var categories = Array.isArray(response) ? response : response.items;
                      if (!Array.isArray(categories) || !categories.length || !categories.every(function (row) {
                        return typeof row.id === "string" && typeof row.name === "string" && row.name.length > 0;
                      })) return -2;
                      return categories.length;
                    }())
                    """, "offline-contract", 1, null);
                return (int) Context.toNumber(result);
            });
            check(count > 0, "module " + id + " did not return offline categories (" + count + ")");
            categories++;
        }
        System.out.println("ANDROID_SOURCE_RHINO_PASS modules=" + seen.size() + " categories=" + categories + " networkRequests=0");
    }
}
