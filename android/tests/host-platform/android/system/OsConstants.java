package android.system;

/** File type bits needed by the host-only lstat adapter. */
public final class OsConstants {
    private OsConstants() {}
    public static final int S_IFMT = 0170000;
    public static final int S_IFLNK = 0120000;
    public static final int S_IFDIR = 0040000;
    public static final int S_IFREG = 0100000;
}
