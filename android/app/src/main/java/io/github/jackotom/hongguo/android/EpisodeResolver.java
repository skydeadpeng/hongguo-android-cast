package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** On-demand resolver for an exact official public-web episode; no app-interface fallback. */
final class EpisodeResolver {
    private static final String ORIGIN = "https://hongguoduanju.com";
    private static final int MAX_HTML = 8 * 1024 * 1024;
    private static final Pattern ROUTER = Pattern.compile("(?:window\\.)?_ROUTER_DATA\\s*=\\s*(\\{)");
    private static final Pattern SCRIPT = Pattern.compile("<script\\b([^>]*)>([\\s\\S]*?)</script\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LD_TYPE = Pattern.compile("\\btype\\s*=\\s*[\"']application/ld\\+json[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESERVED = Pattern.compile("(?:^|\\.)(?:localhost|local|internal|lan|home|test|invalid|example|onion)$");
    static final class ResolverException extends IOException {
        final String code;
        ResolverException(String code, String message) { super(message); this.code = code; }
    }
    private EpisodeResolver() { }
    private static ResolverException error(String code, String message) { return new ResolverException(code, message); }
    private static void requireId(String value, String label) throws ResolverException {
        if (value == null || !value.matches("[0-9]{1,30}")) throw error("INVALID_INPUT", label + "必须是 1 至 30 位数字字符串");
    }
    /** Returns a lexically checked URL; the player's transport must run NetworkGuard.media before use. */
    static String resolve(String seriesId, String vid) throws Exception {
        requireId(seriesId, "剧集编号");
        requireId(vid, "分集编号");
        Exception failure = null;
        // Match the desktop's two official webpage routes. '_' is a route placeholder,
        // never the actual series identity accepted by the response parser.
        for (String path : new String[]{"/player/" + seriesId + "/" + vid, "/player/_/" + vid}) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("已切换分集");
            try {
                NetworkGuard.Reply response = NetworkGuard.get(ORIGIN + path);
                if (response.status == 404) throw error("EPISODE_UNAVAILABLE", "该分集未向官网提供公开播放页面，请在官方 App 查看");
                if (response.status < 200 || response.status >= 300) throw error("SOURCE_HTTP", "播放页面响应 HTTP " + response.status);
                return parse(response.body, seriesId, vid);
            } catch (Exception error) { failure = error; }
        }
        throw failure;
    }
    /** Pure parser: no DNS, HTTP, media requests, JavaScript execution or preview-count gating. */
    static String parse(String html, String seriesId, String vid) throws Exception {
        requireId(seriesId, "剧集编号");
        requireId(vid, "分集编号");
        JSONObject loaders = extractRouterData(html).optJSONObject("loaderData");
        Object raw = loaders == null ? null : loaders.opt("player_(series_id)/(vid)/page");
        if (raw == null || raw == JSONObject.NULL) raw = loaders == null ? null : loaders.opt("player_(series_id)/page");
        JSONObject data = raw instanceof JSONObject ? (JSONObject) raw : null;
        JSONObject detail = data == null ? null : data.optJSONObject("seriesDetail");
        JSONObject seo = data == null ? null : data.optJSONObject("publicVideoSeo");
        if (data == null || Boolean.FALSE.equals(data.opt("isSuccess")) || !exact(data, "series_id", seriesId) ||
                !exact(data, "vid", vid) || !exact(detail, "series_id", seriesId) ||
                !exact(seo, "seriesId", seriesId) || !exact(seo, "itemId", vid)) {
            throw error("IDENTITY_MISMATCH", "播放页面与请求剧集或分集不一致");
        }
        if (!Boolean.TRUE.equals(seo.opt("available"))) throw error("EPISODE_UNAVAILABLE", "该分集未向官网公开播放，请在官方 App 查看");
        // Protected pages expose SEO previews even when metadata claims the full duration.
        if (Boolean.TRUE.equals(data.opt("protectedPlayback"))) throw error("PREVIEW_ONLY", "官网仅提供本集预览，正在尝试完整片源");
        JSONArray vids = detail.optJSONArray("vid_list");
        boolean canonicalFirst = vids != null && vid.equals(vids.opt(0));
        boolean invalidUrl = false;
        Matcher scripts = SCRIPT.matcher(html);
        while (scripts.find()) {
            if (!LD_TYPE.matcher(scripts.group(1)).find()) continue;
            Object json;
            try { json = strictJson(scripts.group(2)); } catch (Exception ignored) { continue; }
            List<JSONObject> videos = new ArrayList<>();
            videoObjects(json, videos, 0);
            for (JSONObject video : videos) {
                Object content = video.opt("contentUrl");
                String url = content instanceof String ? publicHttpsUrl(((String) content).replaceAll("(?i)&amp;", "&")) : "";
                URI media = url.isEmpty() ? null : new URI(url);
                // Later canonical pages omit VID; only their checked, same-origin media path binds it.
                boolean officialMedia = media != null && "hongguoduanju.com".equals(media.getHost()) &&
                        media.getRawPath().startsWith("/video/public/");
                boolean boundMedia = officialMedia && media.getRawPath().startsWith("/video/public/" + vid + "/");
                // Contradictory media identity must not borrow the legacy first-episode CDN exception.
                if (officialMedia && !boundMedia) continue;
                boolean allowCanonical = canonicalFirst || boundMedia;
                if (!matchesPlayer(video.opt("url"), seriesId, vid, allowCanonical)) continue;
                Object id = video.opt("@id");
                if (truthy(id) && !matchesPlayer(id, seriesId, vid, allowCanonical)) continue;
                if (!url.isEmpty()) return url;
                invalidUrl = true;
            }
        }
        throw error(invalidUrl ? "INVALID_MEDIA_URL" : "NO_PLAYABLE_URL", invalidUrl ?
                "来源播放地址未通过 HTTPS 公网地址校验" : "该分集暂无官网公开播放地址");
    }
    private static boolean exact(JSONObject object, String key, String expected) {
        return object != null && expected.equals(object.opt(key));
    }
    private static boolean truthy(Object value) {
        if (value == null || value == JSONObject.NULL || Boolean.FALSE.equals(value)) return false;
        if (value instanceof String) return !((String) value).isEmpty();
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        return true;
    }
    static JSONObject extractRouterData(String html) throws Exception {
        if (html == null || html.trim().isEmpty() || html.length() > MAX_HTML) throw error("SOURCE_INVALID", "来源页面为空或超过大小限制");
        Matcher marker = ROUTER.matcher(html);
        if (!marker.find()) throw error("SOURCE_INVALID", "来源页面未返回目录数据");
        int start = marker.end() - 1, depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                try { return (JSONObject) strictJson(html.substring(start, i + 1)); }
                catch (Exception ignored) { throw error("SOURCE_INVALID", "来源目录数据不是有效 JSON"); }
            }
        }
        throw error("SOURCE_INVALID", "来源目录数据不完整");
    }
    private static void videoObjects(Object value, List<JSONObject> out, int depth) {
        if (depth > 256) return;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) videoObjects(array.opt(i), out, depth + 1);
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if ("VideoObject".equals(object.opt("@type"))) out.add(object);
            videoObjects(object.opt("@graph"), out, depth + 1);
        }
    }
    private static boolean matchesPlayer(Object value, String seriesId, String vid, boolean allowCanonical) {
        if (!(value instanceof String)) return false;
        try {
            URI uri = new URI((String) value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"hongguoduanju.com".equalsIgnoreCase(uri.getHost()) ||
                    uri.getRawUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) return false;
            String path = normalizedPath(uri.getRawPath());
            return ("/player/" + seriesId + "/" + vid).equals(path) || allowCanonical &&
                    (uri.getRawQuery() == null || uri.getRawQuery().isEmpty()) && ("/player/" + seriesId).equals(path);
        } catch (Exception ignored) { return false; }
    }
    /** Keep every signed query byte; decode keys only to remove Range (case-insensitively). */
    static String publicHttpsUrl(String value) {
        if (value == null || value.length() > 16_384 || hasUrlWhitespaceOrBackslash(value)) return "";
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null ||
                    uri.getPort() != -1 && uri.getPort() != 443) return "";
            host = host.toLowerCase(Locale.ROOT);
            String checkedHost = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
            if (!checkedHost.contains(".") || checkedHost.matches("[0-9.]+") || checkedHost.contains(":") || RESERVED.matcher(checkedHost).find()) return "";
            String[] labels = checkedHost.split("\\.", -1);
            for (String label : labels) if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) return "";
            // WHATWG URL interprets a numerical final label as an IPv4 literal (including hex/octal).
            if (labels[labels.length - 1].matches("(?:[0-9]+|0[xX][0-9a-fA-F]+)")) return "";
            String query = uri.getRawQuery();
            List<String> kept = new ArrayList<>();
            if (query != null) for (String part : query.split("&", -1)) {
                String key = part.split("=", 2)[0];
                if (!"range".equalsIgnoreCase(decodeComponent(key))) kept.add(part);
            }
            String path = normalizedPath(uri.getRawPath());
            if (path == null || path.isEmpty()) path = "/";
            String joined = String.join("&", kept);
            String result = "https://" + host + path + (!joined.isEmpty() ? "?" + joined : "");
            return new URI(result).toASCIIString();
        } catch (Exception ignored) { return ""; }
    }
    // Scan code points directly: Android's regex engine rejects the JDK Unicode-class flag.
    private static boolean hasUrlWhitespaceOrBackslash(String value) {
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            if (cp == '\\' || cp == 0x85 || cp == 0xFEFF || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return true;
            offset += Character.charCount(cp);
        }
        return false;
    }
    // java.net.URI.normalize collapses double slashes, which can invalidate signed CDN paths.
    private static String normalizedPath(String raw) {
        if (raw == null || raw.isEmpty()) return "/";
        String[] segments = raw.split("/", -1);
        List<String> out = new ArrayList<>();
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i], dot = segment.replaceAll("(?i)%2e", ".");
            if (dot.equals(".")) {
                if (i == segments.length - 1) out.add("");
            } else if (dot.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                if (i == segments.length - 1) out.add("");
            } else out.add(segment);
        }
        return "/" + String.join("/", out);
    }
    private static String decodeComponent(String value) throws Exception {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length();) {
            if (value.charAt(i) != '%') { out.append(value.charAt(i++)); continue; }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (i < value.length() && value.charAt(i) == '%') {
                if (i + 2 >= value.length()) throw new IOException("invalid percent escape");
                int a = Character.digit(value.charAt(i + 1), 16), b = Character.digit(value.charAt(i + 2), 16);
                if (a < 0 || b < 0) throw new IOException("invalid percent escape");
                bytes.write(a * 16 + b); i += 3;
            }
            out.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())));
        }
        return out.toString();
    }
    private static Object strictJson(String value) throws Exception {
        new JsonSyntax(value).validate();
        return new JSONTokener(value).nextValue();
    }
    /** Android's org.json is lenient; validate JSON grammar before letting it build the object. */
    private static final class JsonSyntax {
        final String text;
        int at;
        JsonSyntax(String text) { this.text = text; }
        void validate() throws IOException { value(0); space(); if (at != text.length()) fail(); }
        void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
        void fail() throws IOException { throw new IOException("invalid JSON"); }
        boolean take(char c) { if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        void value(int depth) throws IOException {
            if (depth > 256) fail();
            space(); if (at >= text.length()) fail();
            char c = text.charAt(at);
            if (c == '"') { string(); return; }
            if (take('{')) {
                space(); if (take('}')) return;
                for (;;) { space(); string(); space(); if (!take(':')) fail(); value(depth + 1); space(); if (take('}')) return; if (!take(',')) fail(); }
            }
            if (take('[')) {
                space(); if (take(']')) return;
                for (;;) { value(depth + 1); space(); if (take(']')) return; if (!take(',')) fail(); }
            }
            for (String token : new String[]{"true", "false", "null"}) if (text.startsWith(token, at)) { at += token.length(); return; }
            int start = at;
            take('-');
            if (!take('0')) { if (at >= text.length() || text.charAt(at) < '1' || text.charAt(at) > '9') fail(); digits(); }
            if (take('.')) { int before = at; digits(); if (before == at) fail(); }
            if (take('e') || take('E')) { if (!take('+')) take('-'); int before = at; digits(); if (before == at) fail(); }
            if (at == start) fail();
        }
        void digits() { while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++; }
        void string() throws IOException {
            if (!take('"')) fail();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return;
                if (c < 0x20) fail();
                if (c == '\\') {
                    if (at >= text.length()) fail();
                    char escape = text.charAt(at++);
                    if (escape == 'u') {
                        for (int i = 0; i < 4; i++) if (at >= text.length() || Character.digit(text.charAt(at++), 16) < 0) fail();
                    } else if ("\"\\/bfnrt".indexOf(escape) < 0) fail();
                }
            }
            fail();
        }
    }
}
