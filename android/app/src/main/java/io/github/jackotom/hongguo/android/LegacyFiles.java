package io.github.jackotom.hongguo.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** File helpers with no java.nio.file dependency so every download/cache path runs on Android 6.0. */
final class LegacyFiles {
    private LegacyFiles() {}

    /** True when the path itself is a symbolic link; lstat never follows it. FUSE mounts
     *  (Android 6.0 /storage views) canonicalize differently, so path comparison would
     *  misjudge them — the kernel lstat verdict is the only faithful one. */
    static boolean isSymbolicLink(File file) {
        try {
            return (android.system.Os.lstat(file.getAbsolutePath()).st_mode
                & android.system.OsConstants.S_IFMT) == android.system.OsConstants.S_IFLNK;
        } catch (Exception unavailable) { return false; }
    }

    /** Delete when present; false means the file was already gone. */
    static boolean deleteIfExists(File file) { return !file.exists() || file.delete(); }

    /** Rename-based move (atomic on Linux) with a streaming fallback; existing targets are replaced. */
    static void move(File from, File to, boolean replaceExisting) throws IOException {
        if (to.exists() && !to.delete()) throw new IOException("目标文件占用，无法替换");
        if (from.renameTo(to)) return;
        copy(from, to, replaceExisting);
        if (!from.delete()) throw new IOException("移动完成后清理源文件失败");
    }

    static void copy(File from, File to, boolean replaceExisting) throws IOException {
        if (to.exists() && !replaceExisting) throw new IOException("目标文件已存在");
        try (InputStream input = new FileInputStream(from); FileOutputStream output = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = input.read(buffer)) != -1; ) output.write(buffer, 0, n);
            output.getFD().sync();
        }
    }

    static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = input.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
