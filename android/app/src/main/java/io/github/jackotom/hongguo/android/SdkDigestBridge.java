package io.github.jackotom.hongguo.android;

/** Entry to libhongguo_sdk.so. Loaded lazily and only on arm64: a device without the library simply
 *  has no SDK route and playback keeps using the signed-request ladder. */
final class SdkDigestBridge {
    private static volatile int state; // 0 unknown, 1 available, -1 unavailable

    private SdkDigestBridge() { }

    /** True once this process is known to run arm64 code with the adapter library present. */
    static synchronized boolean supported() {
        if (state == 0) {
            try {
                boolean arm64 = false;
                for (String abi : android.os.Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(abi)) arm64 = true;
                if (arm64) { System.loadLibrary("hongguo_sdk"); state = 1; } else state = -1;
            } catch (Throwable unavailable) { state = -1; }
        }
        return state == 1;
    }

    /** Maps the verified image; `carrier` is 1 for a memfd (Android 11+) and 0 for ashmem. */
    static native int nativeLoad(byte[] image, int carrier);
    static native int nativeDigest(byte[] payload, int selector, int seconds, byte[] out);
    static native int nativeReady();
    static native String nativeDetail();

    /** The raw digest call, as a seam so the adapter below stays testable without the library. */
    interface Native {
        int digest(byte[] payload, int selector, int seconds, byte[] out);
        String detail();
    }

    static final Native NATIVE = new Native() {
        @Override public int digest(byte[] payload, int selector, int seconds, byte[] out) { return nativeDigest(payload, selector, seconds, out); }
        @Override public String detail() { return nativeDetail(); }
    };

    /** The image faulted or overran its call. It is not offered another chance in this process. */
    static final class Broken extends java.io.IOException { Broken(String message) { super(message); } }

    /** The signing core over a 20-byte digest call: the signature binds the first 16 bytes, exactly as
     *  the reference's own adapter slices them. */
    static SdkSigner.Core core(Native nativeDigest) {
        return (payload, selector, seconds) -> {
            byte[] out = new byte[20];
            int status = nativeDigest.digest(payload, selector, seconds, out);
            // Statuses of the native adapter: 5 not usable, 8 the image faulted, 9 the call overran its budget.
            boolean broken = status == 5 || status == 8 || status == 9;
            String detail = "SDK 摘要失败 " + status + ": " + nativeDigest.detail();
            if (broken) throw new Broken(detail);
            if (status != 0) throw new java.io.IOException(detail);
            return java.util.Arrays.copyOf(out, 16);
        };
    }
}
