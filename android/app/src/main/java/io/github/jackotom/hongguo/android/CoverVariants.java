package io.github.jackotom.hongguo.android;

/**
 * Official cover URLs are byte-CDN templates whose trailing extension selects the
 * delivered format: the original bytes are HEIC (".image"/".heic"), which old TV
 * WebViews cannot decode. Asking the CDN for ".webp" returns the same picture in
 * a format every supported Android can render, no proxy or transcoder needed.
 */
final class CoverVariants {
    private CoverVariants() { }
    /** Returns the webp twin of a HEIC-template cover URL, or null when the URL is not one. */
    static String webp(String url) {
        if (url == null) return null;
        if (url.regionMatches(true, url.length() - 5, ".heic", 0, 5)) return url.substring(0, url.length() - 5) + ".webp";
        if (url.regionMatches(true, url.length() - 6, ".image", 0, 6)) return url.substring(0, url.length() - 6) + ".webp";
        return null;
    }
}
