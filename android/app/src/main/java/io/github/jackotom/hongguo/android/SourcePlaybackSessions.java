package io.github.jackotom.hongguo.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Single-use handoff from the source service to the independent native player. No disk state. */
final class SourcePlaybackSessions {
    static final String EXTRA_SESSION = "sourceSession";
    private static final long PENDING_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long ACTIVE_NANOS = TimeUnit.HOURS.toNanos(3);
    private static final int MAX_REQUESTS = 10000;
    private static final Pattern HLS_ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,\"]+)(?:,|\\z)");
    private static final Object LOCK = new Object();
    private static Session current;

    private SourcePlaybackSessions() {}

    static final class Session {
        final String id = UUID.randomUUID().toString();
        final String sourceId, title, userAgent, referer, mimeType;
        final URI url;
        final boolean hls;
        private final Set<String> metadataHosts;
        private final String credentialOrigin, cookie;
        private final long expiresMs;
        final long issued = System.nanoTime();
        private boolean claimed;
        private boolean revoked;
        private boolean stopped;
        private Runnable onRevoke;
        private final Set<Runnable> requests = new HashSet<>();
        private int requestCount;

        Session(String sourceId, String title, JSONObject media, JSONObject manifest) throws Exception {
            if (!sourceId.equals(manifest.opt("id")) || manifest.optInt("api") != 1 || !"js".equals(manifest.opt("runtime")))
                throw new IOException("来源媒体清单无效");
            this.sourceId = sourceId;
            this.title = title;
            this.metadataHosts = hosts(manifest);
            for (String field : new String[]{"cookie", "headers", "authorization", "contentKey", "cencKey", "hlsKey"}) {
                if (media.has(field) && !media.isNull(field)) throw new IOException("该来源媒体授权格式尚未适配");
            }
            url = mediaAddress(string(media, "url"));
            JSONObject credentials = media.optJSONObject("credentials");
            if (media.has("credentials") && !media.isNull("credentials") && credentials == null) throw new IOException("来源媒体凭据无效");
            credentialOrigin = credentials == null ? "" : string(credentials, "origin");
            if (credentials != null && !origin(url).equals(credentialOrigin)) throw new IOException("来源媒体凭据与地址不匹配");
            cookie = credentials == null ? "" : string(credentials, "cookie");
            if (cookie.length() > 24576 || !cookie.matches("[\\x20-\\x7e]*")) throw new IOException("来源媒体凭据无效");
            long expiry = credentials == null ? 0 : credentials.optLong("expiresMs", -1);
            if (credentials != null && !credentials.has("expiresMs")) expiry = 0;
            if (expiry < 0 || expiry > 0 && expiry <= System.currentTimeMillis()) throw new IOException("来源媒体授权已过期");
            expiresMs = expiry;
            String format = string(media, "format").toLowerCase(Locale.ROOT);
            String path = url.getPath().toLowerCase(Locale.ROOT);
            if (format.isEmpty()) format = path.endsWith(".m3u8") || string(media, "playlist").trim().startsWith("#EXTM3U") ? "hls" : path.endsWith(".mp4") ? "mp4" : "";
            if ("hls".equals(format) || "m3u8".equals(format)) {
                hls = true;
                mimeType = "application/x-mpegURL";
            } else if ("mp4".equals(format) && !path.endsWith(".m3u8")) {
                hls = false;
                mimeType = "video/mp4";
            } else {
                throw new IOException("此来源只支持 HLS 与 MP4");
            }
            String ref = string(media, "referer");
            if (ref.isEmpty() && credentials != null) ref = string(credentials, "referer");
            if (ref.isEmpty()) ref = manifest.optString("baseUrl", "");
            referer = checkedReferer(ref);
            String agent = string(media, "userAgent");
            if (agent.isEmpty() && credentials != null) agent = string(credentials, "userAgent");
            if (agent.length() > 1000 || !agent.matches("[\\x20-\\x7e]*")) throw new IOException("来源媒体请求头无效");
            userAgent = agent.isEmpty() ? NetworkGuard.UA : agent;
        }

        private String checkedReferer(String value) throws IOException {
            if (value.isEmpty()) return "";
            URI ref = mediaAddress(value);
            String host = ref.getHost().toLowerCase(Locale.ROOT);
            if ((!hostMatches(host, metadataHosts) && !origin(ref).equals(origin(url))) || ref.getRawQuery() != null)
                throw new IOException("来源媒体 Referer 与来源不匹配");
            return ref.getPath().isEmpty() ? origin(ref) + "/" : ref.toASCIIString();
        }
        String cookieFor(URI target) { return credentialOrigin.equals(origin(target)) ? cookie : ""; }

        private boolean fresh() { return expiresMs == 0 || System.currentTimeMillis() < expiresMs; }
        private synchronized boolean pending() { return !claimed && !revoked && fresh() && System.nanoTime() - issued < PENDING_NANOS; }
        private synchronized boolean claim(Runnable onRevoke) {
            if (!pending()) return false;
            claimed = true;
            this.onRevoke = onRevoke;
            return true;
        }
        synchronized boolean active() { return claimed && !revoked && !stopped && fresh() && System.nanoTime() - issued < ACTIVE_NANOS; }
        synchronized boolean begin(Runnable cancel) {
            if (!active() || requestCount >= MAX_REQUESTS || requests.size() >= 16) return false;
            ++requestCount;
            requests.add(cancel);
            return true;
        }
        synchronized void end(Runnable cancel) { requests.remove(cancel); }

        void cancelRequests() {
            Runnable[] cancellations;
            synchronized (this) {
                if (stopped) return;
                stopped = true;
                cancellations = requests.toArray(new Runnable[0]);
                requests.clear();
            }
            for (Runnable cancel : cancellations) cancel.run();
        }

        private void revoke() {
            Runnable callback;
            synchronized (this) {
                if (revoked) return;
                revoked = true;
                callback = onRevoke;
                onRevoke = null;
            }
            cancelRequests();
            if (callback != null) callback.run();
        }
    }

    static String open(String sourceId, String title, JSONObject media, JSONObject manifest) throws Exception {
        if (sourceId == null || !sourceId.matches("[a-z0-9][a-z0-9_-]{0,31}") || "hongguo".equals(sourceId) || manifest == null ||
            title == null || title.trim().isEmpty() || title.length() > 512 || media == null) throw new IOException("来源媒体资料无效");
        Session session = new Session(sourceId, title, media, manifest);
        Session previous;
        synchronized (LOCK) {
            previous = current;
            current = session;
        }
        if (previous != null) previous.revoke();
        return session.id;
    }

    static boolean isPending(String id) {
        synchronized (LOCK) { return current != null && current.id.equals(id) && current.pending(); }
    }

    static Session take(String id, Runnable onRevoke) {
        synchronized (LOCK) {
            if (current == null || !current.id.equals(id) || !current.claim(onRevoke)) return null;
            return current;
        }
    }

    static void close(String id) { revokeMatching(id, false); }
    static void closeSource(String sourceId) { revokeMatching(sourceId, true); }

    private static void revokeMatching(String value, boolean bySource) {
        Session revoked;
        synchronized (LOCK) {
            if (current == null || !(bySource ? current.sourceId : current.id).equals(value)) return;
            revoked = current;
            current = null;
        }
        revoked.revoke();
    }

    static URI mediaAddress(String value) throws IOException {
        URI url;
        try { url = NetworkGuard.validate(value, false); }
        catch (URISyntaxException e) { throw new IOException("来源媒体地址无效"); }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("api.zxcbug.com") || host.endsWith(".api.zxcbug.com")) throw new IOException("来源媒体域名不可用");
        return url;
    }

    static URI redirect(URI base, String location) throws IOException {
        if (location == null || location.isEmpty() || !base.isAbsolute()) throw new IOException("来源媒体跳转无效");
        // URI.resolve("?x") resolves against the directory on some JDKs.
        if (location.startsWith("?")) return mediaAddress(base.toString().split("\\?", 2)[0] + location);
        try { return mediaAddress(base.resolve(location).toString()); }
        catch (IllegalArgumentException e) { throw new IOException("来源媒体跳转无效"); }
    }

    static void checkPlaylist(byte[] bytes) throws IOException {
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (body.startsWith("\ufeff")) body = body.substring(1);
        String[] lines = body.split("\\r?\\n");
        if (lines.length > 20000 || lines.length == 0 || !"#EXTM3U".equals(lines[0].trim()))
            throw new IOException("来源 HLS 清单无效");
        boolean master = false, end = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) master = true;
            if ("#EXT-X-ENDLIST".equals(line)) end = true;
            if (line.startsWith("#EXT-X-DEFINE:") || line.startsWith("#EXT-X-CONTENT-STEERING:"))
                throw new IOException("来源 HLS 地址声明尚未适配");
            if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
                checkKeyAttributes(line.substring(line.indexOf(':') + 1));
            }
        }
        if (!master && !end) throw new IOException("此来源仅支持已完成的点播清单");
    }

    private static void checkKeyAttributes(String attributes) throws IOException {
        Matcher matcher = HLS_ATTRIBUTE.matcher(attributes);
        Set<String> declared = new HashSet<>();
        String method = "", format = "\"identity\"";
        int index = 0;
        while (index < attributes.length()) {
            matcher.region(index, attributes.length());
            if (!matcher.lookingAt() || !declared.add(matcher.group(1))) throw new IOException("来源 HLS 授权格式无效");
            if ("METHOD".equals(matcher.group(1))) method = matcher.group(2);
            if ("KEYFORMAT".equals(matcher.group(1))) format = matcher.group(2);
            index = matcher.end();
        }
        if (!("NONE".equals(method) || "AES-128".equals(method)) || !"\"identity\"".equals(format))
            throw new IOException("来源 HLS 授权格式尚未适配");
    }

    private static Set<String> hosts(JSONObject manifest) throws IOException {
        JSONArray values = manifest.optJSONArray("hosts");
        if (values == null || values.length() == 0 || values.length() > 128) throw new IOException("来源主机清单无效");
        Set<String> result = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            String host = values.optString(i, "").toLowerCase(Locale.ROOT);
            String bare = host.startsWith("*.") ? host.substring(2) : host;
            mediaAddress("https://" + bare);
            result.add(host);
        }
        return result;
    }

    private static boolean hostMatches(String host, Set<String> allowed) {
        return allowed.contains(host) || host.contains(".") && allowed.stream().anyMatch(item -> item.startsWith("*.") && host.endsWith(item.substring(1)));
    }

    private static String origin(URI url) { return "https://" + url.getHost().toLowerCase(Locale.ROOT); }

    private static String string(JSONObject data, String key) throws IOException {
        if (!data.has(key) || data.isNull(key)) return "";
        Object value = data.opt(key);
        if (!(value instanceof String)) throw new IOException("来源媒体资料格式无效");
        return (String) value;
    }
}
