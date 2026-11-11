package io.github.jackotom.hongguo.android;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Shares one merged output between its prepared owner, published URI and live HTTP requests. */
final class DlnaMergedLease {
    private final File file;
    private final List<DlnaSession.Prepared> parts;
    private int references = 1;

    private DlnaMergedLease(File file, List<DlnaSession.Prepared> parts) {
        this.file = file;
        this.parts = new ArrayList<>(parts);
    }

    /** Takes ownership of each part's existing owner lease; no per-request child leases are needed. */
    static DlnaSession.Prepared prepare(File file, String title, List<DlnaSession.Prepared> parts) {
        if (file == null || parts == null || parts.size() < 2) throw new IllegalArgumentException("合并媒体租约无效");
        DlnaMergedLease lease = new DlnaMergedLease(file, parts);
        DlnaSession.Prepared first = parts.get(0);
        return new DlnaSession.Prepared(file, title, first.quality, first.episodeIndex, parts.size(), lease::retain, lease::release);
    }

    private synchronized void retain() {
        if (references <= 0 || references == Integer.MAX_VALUE) throw new IllegalStateException("合并媒体已释放");
        references++;
    }

    private void release() {
        synchronized (this) {
            if (references <= 0 || --references > 0) return;
        }
        // Source/session close may precede HTTP teardown. Delete only after every URI/request lease ends.
        try { delete(file); }
        finally { for (DlnaSession.Prepared part : parts) part.releaseOwner(); }
    }

    static void delete(File file) {
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }
}
