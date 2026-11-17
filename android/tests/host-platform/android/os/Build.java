package android.os;

/** Host-only API35 fixture for the modern guest TLS contract; never packaged. */
public final class Build {
    private Build() {}
    public static final class VERSION {
        private VERSION() {}
        public static final int SDK_INT = Integer.parseInt("35");
    }
}
