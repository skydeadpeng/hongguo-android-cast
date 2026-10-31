package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * Reads the two remote announcement files through the same mirror lines as the update feed.
 * Raw text only: schema validation, suppression and preferences live in the page module,
 * so a malformed remote file can never become a dialog without passing the strict checks.
 */
final class AnnouncementClient {
    static final int MAX_BYTES = 32 * 1024;
    private static final int TIMEOUT_MS = 6000;

    /** Mirror lines for one announcement channel; proxies first, GitHub direct last. */
    static List<String> lines(String channel) {
        String canonical = "https://raw.githubusercontent.com/" + UpdatePolicy.REPOSITORY + "/main/announcements/" + channel + ".json";
        List<String> lines = new ArrayList<>();
        for (String host : UpdatePolicy.PROXY_HOSTS) lines.add("https://" + host + "/" + canonical);
        lines.add(canonical);
        return lines;
    }

    /** Returns each channel's raw text, or null when every mirror line failed for it. */
    static JSONObject fetch() {
        JSONObject result = new JSONObject();
        for (String channel : new String[]{"normal", "emergency"}) {
            String text = null;
            for (String line : lines(channel)) {
                String candidate = read(line);
                if (candidate != null) { text = candidate; break; }
            }
            try { result.put(channel, text == null ? JSONObject.NULL : text); } catch (Exception ignored) { }
        }
        return result;
    }

    /** One GET: fixed HTTPS hosts only, no redirects, hard byte cap, bounded time. */
    private static String read(String line) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(line);
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || url.getDefaultPort() != 443 && url.getPort() != -1) return null;
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Hongguo-Android-Announcements");
            if (connection.getResponseCode() != 200) return null;
            if (connection.getContentLength() > MAX_BYTES) return null;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                for (int read; (read = input.read(chunk)) >= 0; ) {
                    if (buffer.size() + read > MAX_BYTES) return null;
                    buffer.write(chunk, 0, read);
                }
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception failure) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
    private AnnouncementClient() { }
}
