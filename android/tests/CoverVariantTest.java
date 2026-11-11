package io.github.jackotom.hongguo.android;

/** Pure URL-template checks for the webp cover twin; no network, no android.graphics. */
public final class CoverVariantTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] unused) {
        // byteimg template: trailing ".image" selects the HEIC original; ".webp" is its twin
        check("https://p3-novel.byteimg.com/novel-pic/abc~tplv-shrink:640:0.webp"
                .equals(CoverVariants.webp("https://p3-novel.byteimg.com/novel-pic/abc~tplv-shrink:640:0.image")),
            "tplv .image becomes .webp");
        check("https://p3.fqnovelpic.com/x/y.webp".equals(CoverVariants.webp("https://p3.fqnovelpic.com/x/y.heic")),
            "plain .heic becomes .webp");
        check("https://p3.byteimg.com/a.webp".equals(CoverVariants.webp("https://p3.byteimg.com/a.HEIC")),
            "uppercase .HEIC still matched case-insensitively");
        // already-decodable formats and non-covers must stay untouched
        check(CoverVariants.webp("https://p3.byteimg.com/a.webp") == null, ".webp needs no twin");
        check(CoverVariants.webp("https://p3.byteimg.com/a.jpg") == null, ".jpg needs no twin");
        check(CoverVariants.webp("https://p3.byteimg.com/a.jpeg") == null, ".jpeg needs no twin");
        check(CoverVariants.webp("https://p3.byteimg.com/a.png") == null, ".png needs no twin");
        check(CoverVariants.webp("data:image/jpeg;base64,AAAA") == null, "data URLs stay");
        check(CoverVariants.webp("https://p3.byteimg.com/a.image?sign=1") == null, "query URLs are not rewritten");
        check(CoverVariants.webp(null) == null, "null stays null");
        check(CoverVariants.webp("") == null, "empty stays null");
        check(checks >= 10, "expected at least ten checks, ran " + checks);
        System.out.println("CoverVariantTest ok: " + checks + " checks");
    }
}
