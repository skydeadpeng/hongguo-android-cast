package io.github.jackotom.hongguo.android;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Android's own release identity. Never consumes the desktop or reference project's feed. */
final class UpdatePolicy {
    static final String REPOSITORY = "skydeadpeng/hongguo-android-cast";
    static final long CHECK_INTERVAL = 24L * 60 * 60 * 1000;
    static final long MAX_APK_BYTES = 150L * 1024 * 1024;
    static final String[] PROXY_HOSTS={"gh-proxy.com","v4.gh-proxy.org","gh-proxy.org","ghfast.top"};
    private static final Set<String> ASSET_HOSTS = new HashSet<>(Arrays.asList("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"));
    static String manifestUrl(String flavor) {
        if (!flavor.equals("phone") && !flavor.equals("tv") && !flavor.equals("tvnative")) throw new IllegalArgumentException("更新渠道无效");
        return "https://raw.githubusercontent.com/" + REPOSITORY + "/main/android/update-" + flavor + ".json";
    }
    static boolean automaticCheckDue(long now, long attempted) { return attempted <= 0 || attempted > now || now - attempted >= CHECK_INTERVAL; }
    static final long CHECK_RETRY_INTERVAL = 15L * 60 * 1000;
    /** Successful checks cool down for one day; an unsuccessful attempt retries after 15 minutes. */
    static boolean automaticCheckDue(long now, long attempted, long succeeded) {
        // After a clock rollback, a new failed attempt must still cool down even if the old success is in the future.
        if (succeeded > now && attempted > 0 && attempted <= now) return now - attempted >= CHECK_RETRY_INTERVAL;
        long stamp = Math.max(attempted, succeeded);
        long interval = succeeded > 0 && succeeded >= attempted ? CHECK_INTERVAL : CHECK_RETRY_INTERVAL;
        return stamp <= 0 || stamp > now || now - stamp >= interval;
    }

    static void validate(int schema, String channel, String flavor, String pkg, int code, String name,
                         int minSdk, long size, String sha256, String download, String installedPackage,
                         int installedCode, String installedName, int deviceSdk) {
        // The independent native TV line owns its package suffix and HongguoNative asset prefix.
        String expected = installedPackage.endsWith(".tvnative") ? "tvnative" : installedPackage.endsWith(".tv") ? "tv" : "phone";
        String expectedPackage = "io.github.jackotom.hongguo.android" + (expected.equals("tvnative") ? ".tvnative" : expected.equals("tv") ? ".tv" : "");
        if (schema != 1 || !"android-compatible".equals(channel) || !expected.equals(flavor)
                || !installedPackage.equals(pkg) || !expectedPackage.equals(pkg)
                || code <= 0 || name == null || !name.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || minSdk < 26 || minSdk > deviceSdk || size <= 0 || size > MAX_APK_BYTES
                || sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("更新信息与当前设备不匹配");
        if (code < installedCode || code == installedCode && !installedName.equals(name)) throw new IllegalArgumentException("更新清单尚未刷新或版本不一致，请稍后重试");
        URI uri;
        try { uri = URI.create(download); } catch (RuntimeException invalid) { throw new IllegalArgumentException("更新包地址无效"); }
        String prefix = expected.equals("tvnative")
                ? "/" + REPOSITORY + "/releases/download/v" + name + "/HongguoNative-" + name + "-tvNative-release"
                : "/" + REPOSITORY + "/releases/download/v" + name + "/Hongguo-" + name + "-" + flavor + "-release";
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getPort() != -1
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !((prefix + "-compatible.apk").equals(uri.getRawPath()) || (prefix + ".apk").equals(uri.getRawPath()))) throw new IllegalArgumentException("更新包不是本应用的固定版本发行文件");
    }
    static boolean matchingArchive(String installedPackage, int installedCode, String archivePackage,
                                   int archiveCode, String archiveName, int expectedCode, String expectedName,
                                   String[] installedSigners, String[] archiveSigners) {
        if (!installedPackage.equals(archivePackage) || archiveCode <= installedCode || archiveCode != expectedCode
                || !expectedName.equals(archiveName) || installedSigners == null || archiveSigners == null
                || installedSigners.length == 0 || installedSigners.length != archiveSigners.length) return false;
        String[] left = installedSigners.clone(), right = archiveSigners.clone(); Arrays.sort(left); Arrays.sort(right);
        return Arrays.equals(left, right);
    }
    static boolean allowedRedirect(String value) {
        try { URI uri = URI.create(value); return "https".equals(uri.getScheme()) && ASSET_HOSTS.contains(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawUserInfo() == null && uri.getRawFragment() == null; }
        catch (RuntimeException invalid) { return false; }
    }
    /** Transport candidates never replace the canonical manifest / persisted APK identity. */
    static abstract class Source {
        final String url,label;
        Source(String url,String label){this.url=url;this.label=label;}
        abstract boolean allows(String value);
    }
    static final class DownloadSource extends Source {
        final String proxyHost, canonical;
        DownloadSource(String url, String label, String proxyHost, String canonical) {
            super(url,label); this.proxyHost=proxyHost; this.canonical=canonical;
        }
        boolean allows(String value) {
            if (proxyHost == null) return allowedRedirect(value);
            try {
                URI uri=URI.create(value), origin=URI.create(canonical);
                if (!"https".equals(uri.getScheme()) || (uri.getPort()!=-1 && uri.getPort()!=443)
                        || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null) return false;
                // A mirror may not redirect to another mirror or change its embedded target.
                if (proxyHost.equals(uri.getHost())) return uri.getRawQuery()==null
                    && ("/"+canonical).equals(uri.getRawPath());
                if ("github.com".equals(uri.getHost())) return uri.getRawQuery()==null
                    && origin.getRawPath().equals(uri.getRawPath());
                return allowedRedirect(value);
            } catch (RuntimeException invalid) { return false; }
        }
    }
    static List<DownloadSource> downloadSources(String canonical) {
        URI uri;
        try { uri=URI.create(canonical); } catch (RuntimeException invalid) { throw new IllegalArgumentException("更新包地址无效"); }
        // The Native prefix pairs only with the tvNative flavor; phone/tv keep the legacy name.
        String pattern="/"+REPOSITORY+"/releases/download/v([0-9]+\\.[0-9]+\\.[0-9]+)/Hongguo(Native)?-\\1-(phone|tv|tvNative)-release(?:-compatible)?\\.apk";
        java.util.regex.Matcher matcher;
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getPort()!=-1
            || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
            || uri.getRawPath()==null || !(matcher=java.util.regex.Pattern.compile(pattern).matcher(uri.getRawPath())).matches()
            || ("Native".equals(matcher.group(2)) != "tvNative".equals(matcher.group(3))))
            throw new IllegalArgumentException("更新包不是本应用的固定版本发行文件");
        List<DownloadSource> sources=new ArrayList<>();
        for (String host:PROXY_HOSTS) sources.add(new DownloadSource("https://"+host+"/"+canonical,host,host,canonical));
        sources.add(new DownloadSource(canonical,"GitHub 官方直连",null,canonical));
        return Collections.unmodifiableList(sources);
    }

    /** Manifest routing is separate from APK/CDN routing and keeps the same one-use cache nonce. */
    static final class ManifestSource extends Source {
        final String proxyHost,canonical,query;
        ManifestSource(String url,String label,String proxyHost,String canonical,String query){super(url,label);this.proxyHost=proxyHost;this.canonical=canonical;this.query=query;}
        boolean allows(String value){
            try {
                URI uri=URI.create(value),origin=URI.create(canonical);
                if(!"https".equals(uri.getScheme())||(uri.getPort()!=-1&&uri.getPort()!=443)||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||!query.equals(uri.getRawQuery()))return false;
                if(proxyHost!=null&&proxyHost.equals(uri.getHost()))return ("/"+canonical).equals(uri.getRawPath());
                return origin.getHost().equals(uri.getHost())&&origin.getRawPath().equals(uri.getRawPath());
            }catch(RuntimeException invalid){return false;}
        }
    }
    static List<ManifestSource> manifestSources(String flavor,long nonce){
        if(nonce<=0)throw new IllegalArgumentException("更新检查标记无效");
        String canonical=manifestUrl(flavor),query="check="+nonce;
        List<ManifestSource> sources=new ArrayList<>();
        for(String host:PROXY_HOSTS)sources.add(new ManifestSource("https://"+host+"/"+canonical+"?"+query,host,host,canonical,query));
        sources.add(new ManifestSource(canonical+"?"+query,"GitHub 清单直连",null,canonical,query));
        return Collections.unmodifiableList(sources);
    }

}
