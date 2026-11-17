package android.system;

/** Minimal real file-type result consumed by LegacyFiles.isSymbolicLink. */
public final class StructStat {
    public final int st_mode;
    public StructStat(int mode) { st_mode = mode; }
}
