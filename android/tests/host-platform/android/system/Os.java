package android.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

/** Host-only lstat: inspect the link itself, never follow its target. */
public final class Os {
    private Os() {}
    public static StructStat lstat(String path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(
            Paths.get(path), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        int type = attrs.isSymbolicLink() ? OsConstants.S_IFLNK
            : attrs.isDirectory() ? OsConstants.S_IFDIR
            : attrs.isRegularFile() ? OsConstants.S_IFREG : 0;
        return new StructStat(type);
    }
}
