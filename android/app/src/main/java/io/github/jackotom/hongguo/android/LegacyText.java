package io.github.jackotom.hongguo.android;

/** String helpers with no java.lang.String dependency so every join runs on Android 6.0. */
final class LegacyText {
    private LegacyText() {}

    /** String.join for Iterable<String>. String.join itself is API 26+, absent on Android 6.0;
     *  core library desugaring cannot backport methods on final platform classes, so the loop is real. */
    static String join(CharSequence separator, Iterable<String> items) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String item : items) {
            if (!first) out.append(separator);
            out.append(item);
            first = false;
        }
        return out.toString();
    }
}
