package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.*;
import org.json.*;

/** Java app-source resolution for the native UI: the same playInfo contract the WebView resolver returns. */
final class AppMediaSource {
    static final class AppSourceException extends IOException {
        final String code;
        AppSourceException(String message, String code) { super(message); this.code = code; }
    }
    interface ModelProvider { JSONObject fetch(String vid) throws Exception; }
    private static final String[] MEDIA_DOMAINS = { "qznovelvod.com", "fqnovelvod.com", "douyinvod.com" };
    private final ModelProvider provider;
    AppMediaSource(ModelProvider provider) { this.provider = provider; }
    /** Returns the shared plan JSON (url/contentKey/userAgent/referer/source/vid/quality[/width/height/variants]). */
    JSONObject resolve(String vid, String requestedQuality, Integer playbackCeiling) throws Exception {
        if (vid == null || !vid.matches("[0-9]{1,30}")) throw new AppSourceException("分集请求信息无效", "INVALID_INPUT");
        String quality = MediaQuality.normalize(requestedQuality == null || requestedQuality.isEmpty() ? "auto" : requestedQuality);
        boolean bounded = playbackCeiling != null;
        if (bounded && (playbackCeiling < 0 || playbackCeiling > 16384)) throw new AppSourceException("播放清晰度边界无效", "INVALID_INPUT");
        JSONObject model;
        try {
            JSONObject reply = provider.fetch(vid);
            model = reply == null ? null : reply.optJSONObject("video_model");
        }
        catch (AppSourceException error) { throw error; }
        catch (SocketTimeoutException error) { throw new AppSourceException("App片源请求超时，请重试", "ETIMEDOUT"); }
        catch (InterruptedIOException error) { throw new AppSourceException("App片源请求已取消", "ERR_CANCELED"); }
        catch (Exception error) { throw new AppSourceException("App片源登记或取源未完成，请重试", "APP_INVALID_RESPONSE"); }
        if (model == null) throw new AppSourceException("App接口未返回本集片源模型", "APP_EMPTY_RESPONSE");
        List<Object[]> ranked = rank(videoRows(model), quality, bounded, playbackCeiling);
        if (ranked.isEmpty()) throw new AppSourceException(bounded ? "本集暂无尺寸可确认且满足播放清晰度边界的片源"
            : quality.equals("auto") ? "本集暂未提供兼容的 H.264/HEVC 片源" : "本集暂无尺寸可确认且满足 " + quality + " 的片源，未降级", "QUALITY_UNAVAILABLE");
        List<JSONObject> variants = decode(ranked, vid);
        if (variants.isEmpty()) throw new AppSourceException("本集片源地址或密钥无法解析", "INVALID_RESPONSE");
        JSONObject primary = variants.remove(0);
        JSONObject result = new JSONObject();
        for (Iterator<String> i = primary.keys(); i.hasNext(); ) { String key = i.next(); result.put(key, primary.opt(key)); }
        result.put("vid", vid).put("requestedQuality", quality);
        if (bounded) result.put("playbackCeiling", (int) playbackCeiling);
        if (!variants.isEmpty()) {
            JSONArray list = new JSONArray();
            for (JSONObject variant : variants.subList(0, Math.min(7, variants.size()))) list.put(variant);
            result.put("variants", list);
        }
        return result;
    }
    private static List<JSONObject> videoRows(JSONObject model) throws JSONException {
        List<JSONObject> rows = new ArrayList<>();
        Object videos = model.opt("video_list");
        if (videos instanceof JSONArray) {
            JSONArray array = (JSONArray) videos;
            for (int i = 0; i < array.length(); i++) if (array.optJSONObject(i) != null) rows.add(array.getJSONObject(i));
        } else if (videos instanceof JSONObject) {
            JSONObject object = (JSONObject) videos;
            for (Iterator<String> i = object.keys(); i.hasNext(); ) {
                Object value = object.opt(i.next());
                if (value instanceof JSONObject) rows.add((JSONObject) value);
            }
        }
        return rows;
    }
    /** [row, codecRank, pixels, width, height, qualityLabel]; bounded keeps confirmed sizes within the ceiling. */
    private static List<Object[]> rank(List<JSONObject> rows, String quality, boolean bounded, Integer ceiling) throws JSONException {
        List<Object[]> ranked = new ArrayList<>();
        for (JSONObject row : rows) {
            boolean hasUrl = !text(row.opt("main_url")).isEmpty() || !text(row.opt("backup_url")).isEmpty() || row.optJSONArray("backup_url_list") != null;
            if (!hasUrl) continue;
            JSONObject meta = row.optJSONObject("video_meta");
            String codec = text(row.opt("codec_type"));
            if (codec.isEmpty() && meta != null) codec = text(meta.opt("codec_type"));
            codec = codec.toLowerCase(Locale.ROOT);
            int rank = Arrays.asList("h264", "avc", "avc1").contains(codec) ? 0 : Arrays.asList("hevc", "h265", "hvc1", "hev1", "bytevc1").contains(codec) ? 1 : 2;
            if (rank >= 2) continue;
            int[] size = size(row, meta);
            String label = label(row, meta);
            int pixels = size[0] > 0 && size[1] > 0 ? Math.min(size[0], size[1]) : digits(label);
            if (bounded && (size[0] <= 0 || size[1] <= 0 || ceiling != null && pixels > ceiling)) continue;
            if (!bounded && !quality.equals("auto") && !MediaQuality.meets(size[0], size[1], quality)) continue;
            ranked.add(new Object[]{ row, rank, pixels, size[0], size[1], label });
        }
        Comparator<Object[]> order = bounded
            ? Comparator.<Object[], Integer>comparing(a -> (Integer) a[1]).thenComparing(a -> (Integer) a[2])
            : quality.equals("auto")
                ? Comparator.<Object[], Integer>comparing(a -> (Integer) a[1]).thenComparing(a -> -((Integer) a[2]))
                : Comparator.<Object[], Integer>comparing(a -> Math.min((Integer) a[3], (Integer) a[4])).thenComparing(a -> (Integer) a[1]);
        ranked.sort(order);
        return ranked;
    }
    /** Decode every candidate URL of every ranked row; dedupe on url:contentKey like the shared Mac module. */
    private static List<JSONObject> decode(List<Object[]> ranked, String vid) throws JSONException {
        List<JSONObject> variants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object[] selected : ranked) {
            JSONObject row = (JSONObject) selected[0];
            String codec = text(row.opt("codec_type")).toLowerCase(Locale.ROOT);
            String spade = text(row.opt("spade_a"));
            JSONObject encryption = row.optJSONObject("encrypt_info");
            if (spade.isEmpty() && encryption != null) spade = text(encryption.opt("spade_a"));
            String contentKey = null;
            if (!spade.isEmpty()) {
                try { contentKey = hex(SpadeDecoder.registeredContentKey(spade)); }
                catch (IOException error) { continue; }
            }
            List<String> candidates = new ArrayList<>();
            addCandidate(candidates, row.opt("main_url"));
            addCandidate(candidates, row.opt("backup_url"));
            JSONArray backups = row.optJSONArray("backup_url_list");
            if (backups != null) for (int i = 0; i < Math.min(backups.length(), 16); i++) addCandidate(candidates, backups.opt(i));
            for (String candidate : candidates) {
                try {
                    String media = SpadeDecoder.registeredMediaUrl(candidate);
                    if (!allowedMedia(media)) continue;
                    if (!seen.add(media + ":" + (contentKey == null ? "" : contentKey))) continue;
                    JSONObject variant = new JSONObject().put("url", media)
                        .put("contentKey", contentKey == null ? JSONObject.NULL : contentKey)
                        .put("codec", codec.equals("bytevc1") ? "hevc" : codec).put("source", "app").put("vid", vid)
                        .put("userAgent", DanmakuSigner.USER_AGENT).put("referer", "")
                        .put("quality", (String) selected[5]);
                    if (((Integer) selected[3]) > 0 && ((Integer) selected[4]) > 0) variant.put("width", selected[3]).put("height", selected[4]);
                    variants.add(variant);
                } catch (IOException ignored) { }
            }
        }
        return variants;
    }
    private static boolean allowedMedia(String media) {
        try {
            URI uri = URI.create(media);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            for (String domain : MEDIA_DOMAINS) if (host.equals(domain) || host.endsWith("." + domain)) return true;
            return false;
        } catch (Exception invalid) { return false; }
    }
    private static void addCandidate(List<String> out, Object value) { if (value instanceof String && !((String) value).isEmpty()) out.add((String) value); }
    private static String hex(byte[] key) {
        StringBuilder out = new StringBuilder();
        for (byte b : key) out.append(String.format("%02x", b));
        return out.toString();
    }
    private static int[] size(JSONObject row, JSONObject meta) {
        int[][] pairs = {
            { optInt(row, "vwidth"), optInt(row, "vheight") }, { optInt(row, "width"), optInt(row, "height") },
            { optInt(meta, "vwidth"), optInt(meta, "vheight") }, { optInt(meta, "width"), optInt(meta, "height") } };
        for (int[] pair : pairs) if (pair[0] > 0 && pair[1] > 0) return pair;
        return new int[]{ 0, 0 };
    }
    private static int optInt(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        if (!(value instanceof Number)) return 0;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number == Math.rint(number) && number > 0 && number <= 1e5 ? (int) number : 0;
    }
    private static String label(JSONObject row, JSONObject meta) {
        for (String key : new String[]{ "quality_desc", "definition" }) { String value = text(row.opt(key)); if (!value.isEmpty()) return value; }
        for (String key : new String[]{ "height", "vheight", "definition" }) { String value = text(meta == null ? null : meta.opt(key)); if (!value.isEmpty()) return value; }
        return "";
    }
    private static String text(Object value) { return value instanceof String ? ((String) value).trim() : ""; }
    private static int digits(String label) {
        int start = -1, end = -1;
        for (int i = 0; i < label.length(); i++) {
            boolean digit = label.charAt(i) >= '0' && label.charAt(i) <= '9';
            if (digit) { if (start < 0) start = i; end = i; }
            else if (start >= 0) break;
        }
        if (start < 0) return 0;
        try { return Integer.parseInt(label.substring(start, end + 1)); } catch (NumberFormatException ignored) { return 0; }
    }
}
