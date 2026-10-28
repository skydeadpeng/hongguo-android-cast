package io.github.jackotom.hongguo.android;

/** Vector fixtures generated from src/native/app-source.js deriveRegisteredContentKey/registeredMediaUrl. */
public final class SpadeDecoderTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static void same(String actual, String expected, String message) { check(expected.equals(actual), message + " actual=" + actual); }
    static void key(String vector, String expectedHex) throws Exception {
        byte[] key = SpadeDecoder.registeredContentKey(vector);
        StringBuilder hex = new StringBuilder();
        for (byte b : key) hex.append(String.format("%02x", b));
        same(hex.toString(), expectedHex, "key vector");
    }
    static void rejected(String vector, String messagePart) {
        try { SpadeDecoder.registeredContentKey(vector); throw new AssertionError("accepted " + messagePart); }
        catch (Exception expected) { check(expected.getMessage().contains(messagePart), "message '" + messagePart + "' got '" + expected.getMessage() + "'"); }
    }
    public static void main(String[] unused) throws Exception {
        key("lbAT91q-EfJfvxDvaZYS6myWK95hlC3ZYpcyxki8NMFLvVyqRL1cEUs7KioFLGs", "0123456789abcdef0123456789abcdef");
        key("r7Apy1KyK8p6hDTJeYIyyHiOBfJ5iAPxUb4e8FC8HPdXsUCmWLFAEUs7KioFLGs", "fedcba9876543210fedcba9876543210");
        key("lbAT91q-EfJfvxDvaZYS6myWK95hlC3ZYpcyxki8NMFLvVyqRL1cEUs7tPzgFLGs", "0123456789abcdef0123456789abcdef"); // web_v1 tag accepted
        rejected("lbAT91q-EfJfvxDvaZYS6myWK95hlC3ZYpcyxki8NMFLvVyqRL1cEUs7KioFLGg", "spade_a format unsupported"); // app_v2 tag
        rejected("", "spade_a encoding invalid");
        rejected("!!!!", "spade_a encoding invalid");
        rejected("aGVsbG8=", "spade_a structure invalid");
        same(SpadeDecoder.registeredMediaUrl("aHR0cHM6Ly92LmRvdXlpbnZvZC5jb20vYWJjL3gubXA0P3NpZz0x"), "https://v.douyinvod.com/abc/x.mp4?sig=1", "b64 url");
        same(SpadeDecoder.registeredMediaUrl("https://v.qznovelvod.com/x.mp4"), "https://v.qznovelvod.com/x.mp4", "plain https passthrough");
        try { SpadeDecoder.registeredMediaUrl("aGVsbG8="); throw new AssertionError("accepted non-https"); } catch (Exception expected) { check(expected.getMessage().contains("片源地址"), "non-https rejected"); }
        try { SpadeDecoder.registeredMediaUrl("****"); throw new AssertionError("accepted bad b64"); } catch (Exception expected) { check(expected.getMessage().contains("片源编码"), "bad b64 rejected"); }
        System.out.println("SPADE_DECODER_OK checks=" + checks + " networkRequests=0");
    }
}
