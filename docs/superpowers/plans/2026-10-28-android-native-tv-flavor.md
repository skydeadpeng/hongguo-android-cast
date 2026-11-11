# Android 原生 TV 独立版本(tvNative)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增独立安装的原生 TV 版本(flavor `tvNative`,包名 `.tvnative`,独立版本线 0.1.0),完全不依赖系统 WebView:取源/数据层全部 Java 化,界面用纯 android.widget + D-pad 焦点导航;现有 phone / tv(WebView 版)源码与行为不变。

**Architecture:** 播放、缓存、降档、投屏、下载全部复用现有原生组件。新增三块:①`AppMediaSource`——用 `RegisteredPlaybackModel` 拿 video_model,`SpadeDecoder` 做 spade_a 密钥推导与片源 URL 解码,产出与 WebView JS 完全相同的 playInfo JSON 契约;②`PlaybackSources` 增加原生查找钩子(WebView 不存在时走 Java 取源,phone/tv 不受影响);③`SiteCatalog`/`AppSearchPage` 复用 `EpisodeResolver` 的 `_ROUTER_DATA` 解析与 `NetworkGuard.get`,提供分类/详情/搜索数据。UI 为三个 Activity(TvNativeActivity 首页、TvDetailActivity 详情选集、TvSearchActivity 搜索),通过抽取的 `PlayLauncher` 以 `resolveInPlayer` 契约启动 PlayerActivity。

**Tech Stack:** Java 17、android.widget(零第三方运行库,遵守项目零依赖原则)、org.json、现有 Gradle flavor 体系(`device` 维度)。测试沿用项目惯例:plain-main 断言 + javac 子集编译 + stub 双打(run-*.sh)。

**关键事实(勘探结论):**
- `PlaybackSources.preparePlan/parse` 消费的 JSON 契约见 `PlaybackSources.java:118-167`;`resolveAppAttempt` 目前通过 `host.script("window.__androidAppResolve...")` 委托 WebView(`PlaybackSources.java:290-315`)。
- `RegisteredPlaybackModel.fetch(vid)` 已能以匿名登记设备拿到投影后的 `video_model`(`RegisteredPlaybackModel.java:51-103`,字段见 `project()`,含 `main_url/backup_url/backup_url_list/spade_a/encrypt_info.spade_a/video_meta/width/height`),错误码 `INVALID_INPUT/APP_INVALID_RESPONSE/APP_EMPTY_RESPONSE/BUSINESS_REJECTED/HTTP_ERROR`。
- JS 侧注册路径的解码规则在 `src/native/app-source.js:147-228`:`deriveRegisteredContentKey`(spade_a→32 hex→16 字节 key)、`registeredMediaUrl`(纯 base64→https)、`checkedUrl` 域名白名单 `qznovelvod.com/fqnovelstatic?` 否——媒体域为 `qznovelvod.com/fqnovelvod.com/douyinvod.com`(`app-source.js:360`);注册路径 UA 为 `VIDEO_UA.replace('71332','73532')`,恰好等于 `DanmakuSigner.USER_AGENT`。
- 编码/清晰度排序规则在 `app-source.js:326-346`:codecRank(h264/avc/avc1=0,hevc/h265/hvc1/hev1/bytevc1=1,其余拒绝),pixels=min(w,h)(无尺寸时从 quality 标签取数字),bounded 排序 codecRank 升、pixels 升;auto 排序 codecRank 升、pixels 降;固定档 min 尺寸升、codecRank 升。变体去重键 `url:contentKey`,最多 7 个备用。
- 目录/详情/搜索页解析在 `src/android/source.mjs`(`parseCatalog/parseSearch/parseDetail`);详情双路由 `/player/{sid}` 与 `/detail?series_id={sid}`(`detail-loader.mjs:13`);分页搜索响应投影在 `src/android/search.mjs:57-88`(tab_type=11,lynx_data 嵌套 cell,封面域 byteimg.com/fqnovelpic.com/fqnovelstatic.com,字符串 ID 才可信)。
- `EpisodeResolver.extractRouterData/publicHttpsUrl` 目前 private(`EpisodeResolver.java:111,155`),改包可见即可复用。
- 播放入口契约:`NativeBridge.play` 的 `resolveInPlayer` 分支(`NativeBridge.java:247-269,338-361`),Intent extras:`series/episode/episodes/url/quality/speed/autoplay/cleanupPlaybackCache/keepPreviousEpisodes=1/playInfo`。
- `LibraryStore.series/episode` 校验形状(`LibraryStore.java:42-86`);`library()` 返回 `{favorites,history}`;`seriesHistory(sid)` 返回单剧进度;搜索历史 `{keywords}`。
- `PlayerOptions.update(context,{quality|speed|danmaku})`;`MediaQuality.normalize` 允许 `auto/720p/1080p/480p/360p`。
- 测试向量(由 JS 实现生成并核对):
  - spade_a `lbAT91q-EfJfvxDvaZYS6myWK95hlC3ZYpcyxki8NMFLvVyqRL1cEUs7KioFLGs`(tag app_v1)→ key `0123456789abcdef0123456789abcdef`
  - spade_a `r7Apy1KyK8p6hDTJeYIyyHiOBfJ5iAPxUb4e8FC8HPdXsUCmWLFAEUs7KioFLGs`(tag app_v1)→ key `fedcba9876543210fedcba9876543210`
  - spade_a `...EUs7KioFLGg`(完整串 `lbAT91q-EfJfvxDvaZYS6myWK95hlC3ZYpcyxki8NMFLvVyqRL1cEUs7KioFLGg`,tag app_v2)→ 拒绝 `spade_a format unsupported`
  - 媒体地址 base64 `aHR0cHM6Ly92LmRvdXlpbnZvZC5jb20vYWJjL3gubXA0P3NpZz0x` → `https://v.douyinvod.com/abc/x.mp4?sig=1`

**执行注记:** 本仓库当前分支有大量未提交改动;除非用户明确要求,执行时只改/增下列文件,不做 git commit(计划中的 commit 步骤是检查点建议)。

---

## File Structure

**新增(全部在 `android/app/src/main/java/io/github/jackotom/hongguo/android/`,与现有同包):**
- `SpadeDecoder.java` — 注册路径 spade_a 内容密钥推导、base64 媒体地址解码(纯函数,无网络)。
- `AppMediaSource.java` — video_model→playInfo 契约:行筛选/排序、变体去重、错误码映射(注入 ModelProvider,可测)。
- `SiteCatalog.java` — 官网分类/详情/网页搜索解析 + NetworkGuard 抓取编排(纯解析可测)。
- `AppSearchPage.java` — 官方 App 分页搜索响应投影(纯解析可测)。
- `PlayLauncher.java` — 从 NativeBridge.play 抽出的 Intent 构造(两处共用)。
- `TvNativeActivity.java` — TV 首页(分类网格/收藏/下载/设置入口)。
- `TvDetailActivity.java` — 详情、分集、收藏、季切换、续播。
- `TvSearchActivity.java` — 搜索(历史 + App 分页搜索,网页首屏兜底)。

**新增 flavor 资源:** `android/app/src/tvNative/AndroidManifest.xml`

**新增测试(android/tests/):** `SpadeDecoderTest.java`、`AppMediaSourceTest.java`、`SiteCatalogTest.java`、`AppSearchPageTest.java`、`PlaybackSourcesNativeLookupTest.java` 及运行脚本 `run-app-media-source-tests.sh`、`run-site-catalog-tests.sh`、`run-native-lookup-tests.sh`(合一亦可)。

**修改:**
- `android/app/build.gradle` — 新 flavor + NATIVE_UI BuildConfig。
- `android/app/src/main/java/.../PlaybackSources.java` — 原生查找钩子(attachNative/detachNative + resolveAppAttempt 分支)。
- `android/app/src/main/java/.../EpisodeResolver.java` — `extractRouterData/publicHttpsUrl` 改包可见(仅可见性)。
- `android/app/src/main/java/.../NativeBridge.java` — play 的 Intent 构造改用 PlayLauncher(行为不变)。
- `scripts/build-android.sh` — debug 增加 tvNative;新增 `--tvnative-release-compatible`/`--tvnative-release`。
- `ANDROID.md`、`android/README.md` — 记录新 flavor。

---

### Task 1: tvNative flavor 脚手架

**Files:**
- Modify: `android/app/build.gradle:30-34`
- Create: `android/app/src/tvNative/AndroidManifest.xml`
- Create: `android/app/src/main/java/io/github/jackotom/hongguo/android/TvNativeActivity.java`(最小可用,Task 8 重写)
- Modify: `scripts/build-android.sh`

- [x] **Step 1.1: build.gradle 增加 flavor**

```groovy
    productFlavors {
        phone { dimension 'device'; buildConfigField 'boolean', 'IS_TV', 'false'; buildConfigField 'boolean', 'NATIVE_UI', 'false' }
        tv { dimension 'device'; applicationIdSuffix '.tv'; buildConfigField 'boolean', 'IS_TV', 'true'; buildConfigField 'boolean', 'NATIVE_UI', 'false' }
        tvNative {
            dimension 'device'
            applicationIdSuffix '.tvnative'
            versionCode 1
            versionName '0.1.0'
            buildConfigField 'boolean', 'IS_TV', 'true'
            buildConfigField 'boolean', 'NATIVE_UI', 'true'
        }
    }
```

- [x] **Step 1.2: tvNative Manifest(Leanback 启动器 + 独立标签)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-feature android:name="android.software.leanback" android:required="true" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
    <application android:label="红果原生版" tools:replace="android:label">
        <activity android:name=".TvNativeActivity" android:exported="true"
            android:screenOrientation="landscape"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [x] **Step 1.3: 最小 TvNativeActivity(编译占位,Task 8 重写)**

```java
package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

public class TvNativeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff131516);
        TextView text = new TextView(this);
        text.setText("红果原生版 准备中");
        text.setTextColor(0xfff2f2f2);
        text.setTextSize(22);
        root.addView(text, new FrameLayout.LayoutParams(-2, -2, 17));
        setContentView(root);
    }
}
```

- [x] **Step 1.4: scripts/build-android.sh** — `--debug` 模式的 gradle 任务追加 `:app:assembleTvNativeDebug`;case 增加 `--tvnative-release-compatible|--tvnative-release` 分支(镜像 tv 的两个模式,分别映射 compatible/external 签名),帮助文本补两行。
- [x] **Step 1.5: 构建验证**

Run: `cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" gradle :app:assembleTvNativeDebug`
Expected: `BUILD SUCCESSFUL`,产物在 `app/build/outputs/apk/tvNative/debug/`。
(用户网络需镜像:沿用仓库现有 gradle 配置,不改代理。)

- [x] **Step 1.6: Commit** `feat(android): add tvNative flavor scaffold`

---

### Task 2: SpadeDecoder(TDD)

**Files:**
- Create: `android/app/src/main/java/io/github/jackotom/hongguo/android/SpadeDecoder.java`
- Test: `android/tests/SpadeDecoderTest.java`、`android/tests/run-app-media-source-tests.sh`(Task 2/3 共用)

- [x] **Step 2.1: 先写失败测试**(完整文件,断言用上文向量;错误信息须含中文)

```java
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
        catch (Exception expected) {
            check(expected.getMessage().contains(messagePart), "message " + messagePart + " got " + expected.getMessage());
            check(expected.getMessage().matches(".*[\\u4e00-\\u9fff].*") || messagePart.matches(".*[a-z_ ].*"), "message present");
        }
    }
    public static void main(String[] unused) throws Exception {
        key("lbAT91q-EfJfvxDvaZYS6myWK95hlC3ZYpcyxki8NMFLvVyqRL1cEUs7KioFLGs", "0123456789abcdef0123456789abcdef");
        key("r7Apy1KyK8p6hDTJeYIyyHiOBfJ5iAPxUb4e8FC8HPdXsUCmWLFAEUs7KioFLGs", "fedcba9876543210fedcba9876543210");
        key("lbAT91q-EfJfvxDvaZYS6myWK95hlC3ZYpcyxki8NMFLvVyqRL1cEUs7KioFLGs".replace("KioFLGs", "tPzgFLGs"), "0123456789abcdef0123456789abcdef"); // web_v1 tag accepted
        rejected("lbAT91q-EfJfvxDvaZYS6myWK95hlC3ZYpcyxki8NMFLvVyqRL1cEUs7KioFLGg", "spade_a format unsupported"); // app_v2 tag
        rejected("", "spade_a encoding invalid");
        rejected("!!!!", "spade_a encoding invalid");
        rejected("aGVsbG8=", "spade_a structure invalid");
        // registeredMediaUrl
        same(SpadeDecoder.registeredMediaUrl("aHR0cHM6Ly92LmRvdXlpbnZvZC5jb20vYWJjL3gubXA0P3NpZz0x"), "https://v.douyinvod.com/abc/x.mp4?sig=1", "b64 url");
        same(SpadeDecoder.registeredMediaUrl("https://v.qznovelvod.com/x.mp4"), "https://v.qznovelvod.com/x.mp4", "plain https passthrough");
        try { SpadeDecoder.registeredMediaUrl("aGVsbG8="); throw new AssertionError("accepted non-https"); } catch (Exception expected) { check(expected.getMessage().contains("片源地址"), "non-https rejected"); }
        try { SpadeDecoder.registeredMediaUrl("****"); throw new AssertionError("accepted bad b64"); } catch (Exception expected) { check(expected.getMessage().contains("片源编码"), "bad b64 rejected"); }
        System.out.println("SPADE_DECODER_OK checks=" + checks + " networkRequests=0");
    }
}
```

- [x] **Step 2.2: 运行确认失败**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" bash android/tests/run-app-media-source-tests.sh`(先建脚本见 Step 2.4)
Expected: 编译错误 `SpadeDecoder` 不存在。

- [x] **Step 2.3: 实现 SpadeDecoder**(与 JS 逐行对应;注意 `subarray(1,33)` 是索引 1..32;popcount 用 `Integer.bitCount`;base64 需容忍缺省 padding)

```java
package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Registered-model media decoding: spade_a content keys and base64 media URLs. Mirrors src/native/app-source.js. */
final class SpadeDecoder {
    static final class DecodeException extends IOException {
        DecodeException(String message) { super(message); }
    }
    private SpadeDecoder() { }
    private static byte[] base64(String text) throws DecodeException {
        String normalized = text.replace('-', '+').replace('_', '/');
        StringBuilder padded = new StringBuilder(normalized);
        while (padded.length() % 4 != 0) padded.append('=');
        try { return Base64.getDecoder().decode(padded.toString()); }
        catch (IllegalArgumentException invalid) { throw new DecodeException("App 单集片源编码无效"); }
    }
    static byte[] registeredContentKey(String value) throws IOException {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > 2048 || !text.matches("[A-Za-z0-9+/_=-]{1,2048}")) throw new DecodeException("spade_a encoding invalid");
        byte[] raw = base64(text);
        if (raw.length < 3 || raw.length > 1024) throw new DecodeException("spade_a length invalid");
        int tagLength = ((raw[0] ^ raw[1] ^ raw[2]) & 255) - 48;
        int length = raw.length - tagLength - 1;
        if (tagLength < 1 || length < 33 || length >= raw.length) throw new DecodeException("spade_a structure invalid");
        int seed = raw[raw.length - tagLength - 2] ^ raw[raw.length - tagLength - 1];
        StringBuilder tag = new StringBuilder();
        for (int i = raw.length - tagLength; i < raw.length; i++) tag.append((char) ((raw[i] ^ seed) & 255));
        String tagText = tag.toString();
        if (tagText.equals("app_v2") || tagText.equals("web_v2")) throw new DecodeException("spade_a format unsupported");
        // decoded[i] = ((previous[i&1] ^ raw[i+1]) - 21 - bitcount(i)) & 255; key = decoded[1..32].
        byte[] decoded = new byte[length];
        int[] previous = { 250, 85 };
        for (int i = 0; i < length; i++) {
            int current = raw[i + 1] & 255;
            decoded[i] = (byte) (((previous[i & 1] ^ current) - 21 - Integer.bitCount(i)) & 255);
            previous[i & 1] = current;
        }
        byte[] keyBytes = Arrays.copyOfRange(decoded, 1, 33);
        for (byte b : keyBytes) if ((b & 255) > 127) throw new DecodeException("App 视频密钥格式无效");
        String hex = new String(keyBytes, StandardCharsets.US_ASCII);
        if (!hex.matches("(?i)[0-9a-f]{32}")) throw new DecodeException("App 视频密钥格式无效");
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return key;
    }
    static String registeredMediaUrl(String value) throws IOException {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("https://")) return text;
        if (text.isEmpty() || text.length() > 32768 || !text.matches("[A-Za-z0-9+/]+={0,2}") || text.length() % 4 == 1)
            throw new DecodeException("App 单集片源编码无效");
        String decoded = new String(base64(text), StandardCharsets.UTF_8);
        if (!decoded.startsWith("https://")) throw new DecodeException("App 单集片源地址无效");
        return decoded;
    }
}
```

- [x] **Step 2.4: 运行脚本 run-app-media-source-tests.sh**(模式照 `run-source-deadline-tests.sh`;编译闭包仅 `SpadeDecoder/MediaQuality/AppMediaSource` + 测试)

```bash
#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE_ROOT="$ROOT"
if [[ "${1:-}" = '--source-root' ]];then SOURCE_ROOT="$2";shift;2>/dev/null || shift;fi
[[ $# = 0 ]] || { echo 'USAGE run-app-media-source-tests.sh [--source-root PROJECT]' >&2;exit 2; }
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
if [ ! -f "$JSON_JAR" ]; then JSON_JAR="$(find "$HOME/.gradle/caches" "$HOME/.m2/repository/org/json/json" -name 'json-*.jar' ! -name '*sources*' ! -name '*javadoc*' -print 2>/dev/null | head -1)"; fi
[ -f "$JSON_JAR" ] || { echo 'TEST_DEPENDENCY_MISSING set JSON_JAR'; exit 2; }
TMP="$(mktemp -d -t hongguo-app-media.XXXXXX)"; trap 'rm -rf "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
  "$SRC/SpadeDecoder.java" "$SRC/MediaQuality.java" "$SRC/AppMediaSource.java" \
  "$ROOT/android/tests/SpadeDecoderTest.java" "$ROOT/android/tests/AppMediaSourceTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.SpadeDecoderTest
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.AppMediaSourceTest
```

- [x] **Step 2.5: 跑通** Expected: `SPADE_DECODER_OK ... networkRequests=0`
- [x] **Step 2.6: Commit** `feat(android): registered spade content-key and media-url decoder`

---

### Task 3: AppMediaSource(TDD)

**Files:**
- Create: `android/app/src/main/java/io/github/jackotom/hongguo/android/AppMediaSource.java`
- Test: `android/tests/AppMediaSourceTest.java`

- [x] **Step 3.1: 失败测试**(fixture 是 `RegisteredPlaybackModel.project` 的输出形状;playInfo 断言与 `PlaybackSources.parse` 契约一致:url/contentKey/userAgent/referer=""/source="app"/vid/requestedQuality/width/height/quality/variants≤7)

```java
package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.util.Base64;
import org.json.*;

/** Selection, variants and error codes over fixture video_models; no network. */
public final class AppMediaSourceTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static String url(String path) { return "https://v.douyinvod.com/" + path + ".mp4?sig=1"; }
    static String encoded(String path) { return Base64.getEncoder().encodeToString(url(path).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    static final String KEY_A = "lbAT91q-EfJfvxDvaZYS6myWK95hlC3ZYpcyxki8NMFLvVyqRL1cEUs7KioFLGs";
    static final String KEY_B = "r7Apy1KyK8p6hDTJeYIyyHiOBfJ5iAPxUb4e8FC8HPdXsUCmWLFAEUs7KioFLGs";
    static JSONObject row(String mainUrl, String codec, String quality, int w, int h, String spade) throws Exception {
        JSONObject row = new JSONObject().put("main_url", mainUrl).put("codec_type", codec).put("quality_desc", quality)
            .put("vwidth", w).put("vheight", h);
        if (spade != null) row.put("spade_a", spade);
        return row;
    }
    static JSONObject model(JSONObject... rows) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject row : rows) list.put(row);
        return new JSONObject().put("vid", "880006").put("video_model", new JSONObject().put("video_list", list));
    }
    static AppMediaSource.Source resolve(JSONObject model, String quality, Integer ceiling) throws Exception {
        AppMediaSource source = new AppMediaSource(vid -> model);
        return source.resolve("880006", quality, ceiling);
    }
    static void rejected(JSONObject model, String quality, Integer ceiling, String code) throws Exception {
        try { resolve(model, quality, ceiling); throw new AssertionError("accepted " + code); }
        catch (AppMediaSource.AppSourceException expected) { check(code.equals(expected.code), "code " + code + " got " + expected.code); }
    }
    public static void main(String[] unused) throws Exception {
        // auto: codecRank asc then pixels desc → 1080p h264 first; hevc second; unsupported codec dropped.
        AppMediaSource.Source auto = resolve(model(
            row(encoded("hevc720"), "bytevc1", "720p", 1280, 720, KEY_B),
            row(encoded("h264-480"), "h264", "480p", 854, 480, null),
            row(encoded("h264-1080"), "h264", "1080p", 1920, 1080, KEY_A),
            row(encoded("av1"), "av01", "1080p", 1920, 1080, null)), "auto", null);
        check(auto.json.getString("url").equals(url("h264-1080")), "auto primary is h264 1080p");
        check(auto.json.getJSONArray("variants").length() == 2, "auto variants exclude rejected codec");
        check(auto.json.getJSONArray("variants").getJSONObject(0).getString("url").equals(url("hevc720")), "variant order");
        check(auto.json.getString("contentKey").equals("0123456789abcdef0123456789abcdef"), "primary key");
        check(auto.json.getJSONArray("variants").getJSONObject(1).isNull("contentKey"), "no spade → null key");
        check(auto.json.getString("referer").isEmpty() && auto.json.getString("userAgent").equals(DanmakuSignerHolder.UA), "registered headers");
        // bounded: ceiling 719 keeps only ≤719 short edge, sorted ascending
        AppMediaSource.Source bounded = resolve(model(
            row(encoded("h264-1080"), "h264", "1080p", 1920, 1080, KEY_A),
            row(encoded("h264-480"), "h264", "480p", 854, 480, null),
            row(encoded("hevc-720"), "hevc", "720p", 1280, 720, KEY_B)), "auto", 719);
        check(bounded.json.getString("url").equals(url("h264-480")), "bounded keeps lowest within ceiling");
        check(bounded.json.getInt("playbackCeiling") == 719, "ceiling echoed");
        // fixed 720p: only confirmed ≥720 rows, min size ascending, QUALITY_UNAVAILABLE otherwise
        AppMediaSource.Source fixed = resolve(model(
            row(encoded("h264-1080"), "h264", "1080p", 1920, 1080, KEY_A),
            row(encoded("h264-720"), "h264", "720p", 1280, 720, KEY_B)), "720p", null);
        check(fixed.json.getString("url").equals(url("h264-720")), "fixed 720p picks exact size");
        rejected(model(row(encoded("h264-480"), "h264", "480p", 854, 480, null)), "720p", null, "QUALITY_UNAVAILABLE");
        // dedupe by url+key; backup_url_list; domain whitelist rejects foreign host (skipped, last error INVALID_RESPONSE when nothing left)
        JSONObject multi = row(encoded("dup"), "h264", "1080p", 1920, 1080, KEY_A);
        multi.put("backup_url", encoded("dup")).put("backup_url_list", new JSONArray().put(encoded("backup1")).put(encoded("backup1")));
        AppMediaSource.Source variants = resolve(model(multi), "auto", null);
        check(variants.json.getJSONArray("variants").length() == 1 && variants.json.getJSONArray("variants").getJSONObject(0).getString("url").equals(url("backup1")), "dedupe url:contentKey");
        JSONObject foreign = new JSONObject().put("main_url", Base64.getEncoder().encodeToString("https://evil.example.com/x.mp4".getBytes())).put("codec_type", "h264").put("quality_desc", "1080p").put("vwidth", 1920).put("vheight", 1080);
        rejected(model(foreign), "auto", null, "INVALID_RESPONSE");
        // provider error pass-through
        try { new AppMediaSource(vid -> { throw new IOException("x"); }).resolve("880006", "auto", null); throw new AssertionError("no error"); }
        catch (AppMediaSource.AppSourceException expected) { check("APP_INVALID_RESPONSE".equals(expected.code) || "APP_EMPTY_RESPONSE".equals(expected.code), "provider failure mapped"); }
        System.out.println("APP_MEDIA_SOURCE_OK checks=" + checks + " networkRequests=0");
    }
}
// Test-only UA holder: AppMediaSource uses DanmakuSigner.USER_AGENT; the test compile set does not include DanmakuSigner.
final class DanmakuSignerHolder { static final String UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"; }
```

注:上面测试以 holder 断言 UA 字面值;`AppMediaSource` 实际引用 `DanmakuSigner.USER_AGENT`(生产相等)。若编译闭包含 DanmakuSigner 则直接比较。

- [x] **Step 3.2: 跑脚本确认失败**(javac 报 AppMediaSource 缺失)
- [x] **Step 3.3: 实现 AppMediaSource**

```java
package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.json.*;

/** Java app-source resolution for the native UI: same playInfo contract the WebView resolver returns. */
final class AppMediaSource {
    static final class AppSourceException extends IOException {
        final String code;
        AppSourceException(String message, String code) { super(message); this.code = code; }
    }
    interface ModelProvider { JSONObject fetch(String vid) throws Exception; }
    static final class Source {
        final JSONObject json;
        Source(JSONObject json) { this.json = json; }
    }
    private static final String[] MEDIA_DOMAINS = { "qznovelvod.com", "fqnovelvod.com", "douyinvod.com" };
    private final ModelProvider provider;
    AppMediaSource() { this(vid -> new RegisteredPlaybackModel().fetch(vid)); }
    AppMediaSource(ModelProvider provider) { this.provider = provider; }
    private static String text(Object value) { return value instanceof String ? ((String) value).trim() : ""; }
    Source resolve(String vid, String requestedQuality, Integer playbackCeiling) throws IOException {
        if (vid == null || !vid.matches("[0-9]{1,30}")) throw new AppSourceException("分集请求信息无效", "INVALID_INPUT");
        String quality = MediaQuality.normalize(requestedQuality == null ? "auto" : requestedQuality);
        boolean bounded = playbackCeiling != null;
        if (bounded && (playbackCeiling < 0 || playbackCeiling > 16384)) throw new AppSourceException("播放清晰度边界无效", "INVALID_INPUT");
        JSONObject model;
        try { JSONObject reply = provider.fetch(vid); model = reply == null ? null : reply.optJSONObject("video_model"); }
        catch (RegisteredPlaybackModel.ModelError error) { throw new AppSourceException(error.getMessage(), error.code); }
        catch (java.net.SocketTimeoutException error) { throw new AppSourceException("App片源请求超时，请重试", "ETIMEDOUT"); }
        catch (java.io.InterruptedIOException error) { throw new AppSourceException("App片源请求已取消", "ERR_CANCELED"); }
        catch (Exception error) { throw new AppSourceException("App片源登记或取源未完成，请重试", "APP_INVALID_RESPONSE"); }
        if (model == null) throw new AppSourceException("App接口未返回本集片源模型", "APP_EMPTY_RESPONSE");
        List<JSONObject> rows = new ArrayList<>();
        Object videos = model.opt("video_list");
        if (videos instanceof JSONArray) { JSONArray array = (JSONArray) videos; for (int i = 0; i < array.length(); i++) if (array.optJSONObject(i) != null) rows.add(array.getJSONObject(i)); }
        else if (videos instanceof JSONObject) { JSONObject object = (JSONObject) videos; for (Iterator<String> i = object.keys(); i.hasNext(); ) { Object value = object.opt(i.next()); if (value instanceof JSONObject) rows.add((JSONObject) value); } }
        if (rows.isEmpty()) throw new AppSourceException("App单集模型缺少有效片源列表", "APP_INVALID_RESPONSE");
        List<Object[]> ranked = new ArrayList<>(); // [row, codecRank, pixels, width, height, label]
        for (JSONObject row : rows) {
            if (!text(row.opt("main_url")).isEmpty() || !text(row.opt("backup_url")).isEmpty() || row.optJSONArray("backup_url_list") != null) {
                JSONObject meta = row.optJSONObject("video_meta");
                String codec = text(row.opt("codec_type")).isEmpty() && meta != null ? text(meta.opt("codec_type")) : text(row.opt("codec_type"));
                codec = codec.toLowerCase(Locale.ROOT);
                int rank = Arrays.asList("h264", "avc", "avc1").contains(codec) ? 0 : Arrays.asList("hevc", "h265", "hvc1", "hev1", "bytevc1").contains(codec) ? 1 : 2;
                if (rank >= 2) continue;
                int[] size = size(row, meta);
                String label = firstText(row, meta);
                int pixels = size[0] > 0 && size[1] > 0 ? Math.min(size[0], size[1]) : digits(label);
                if (bounded && (size[0] <= 0 || size[1] <= 0 || pixels > playbackCeiling)) continue;
                if (!bounded && !quality.equals("auto") && !MediaQuality.meets(size[0], size[1], quality)) continue;
                ranked.add(new Object[]{ row, rank, pixels, size[0], size[1], label });
            }
        }
        Comparator<Object[]> order = bounded
            ? Comparator.<Object[], Integer>comparing(a -> (Integer) a[1]).thenComparing(a -> (Integer) a[2])
            : quality.equals("auto")
                ? Comparator.<Object[], Integer>comparing(a -> (Integer) a[1]).thenComparing(a -> -((Integer) a[2]))
                : Comparator.<Object[], Integer>comparing(a -> Math.min((Integer) a[3], (Integer) a[4])).thenComparing(a -> (Integer) a[1]);
        ranked.sort(order);
        if (ranked.isEmpty()) throw new AppSourceException(bounded ? "本集暂无尺寸可确认且满足播放清晰度边界的片源" : quality.equals("auto") ? "本集暂未提供兼容的 H.264/HEVC 片源" : "本集暂无尺寸可确认且满足 " + quality + " 的片源，未降级", "QUALITY_UNAVAILABLE");
        List<JSONObject> variants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        IOException last = null;
        for (Object[] selected : ranked) {
            JSONObject row = (JSONObject) selected[0];
            String codec = text(row.opt("codec_type")).toLowerCase(Locale.ROOT);
            String spade = text(row.opt("spade_a"));
            JSONObject encryption = row.optJSONObject("encrypt_info");
            if (spade.isEmpty() && encryption != null) spade = text(encryption.opt("spade_a"));
            String contentKey = null;
            if (!spade.isEmpty()) {
                try { byte[] key = SpadeDecoder.registeredContentKey(spade);
                    StringBuilder hex = new StringBuilder(); for (byte b : key) hex.append(String.format("%02x", b)); contentKey = hex.toString(); }
                catch (IOException error) { last = error; continue; }
            }
            List<String> candidates = new ArrayList<>();
            addCandidate(candidates, row.opt("main_url")); addCandidate(candidates, row.opt("backup_url"));
            JSONArray backups = row.optJSONArray("backup_url_list");
            if (backups != null) for (int i = 0; i < Math.min(backups.length(), 16); i++) addCandidate(candidates, backups.opt(i));
            for (String candidate : candidates) {
                try {
                    String media = SpadeDecoder.registeredMediaUrl(candidate);
                    URI uri = URI.create(media);
                    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                    boolean allowed = false;
                    for (String domain : MEDIA_DOMAINS) if (host.equals(domain) || host.endsWith("." + domain)) { allowed = true; break; }
                    if (!allowed || !"https".equalsIgnoreCase(uri.getScheme())) { last = new AppSourceException("App片源返回了不受支持的服务地址", "INVALID_RESPONSE"); continue; }
                    String identity = media + ":" + (contentKey == null ? "" : contentKey);
                    if (!seen.add(identity)) continue;
                    JSONObject variant = new JSONObject().put("url", media).put("contentKey", contentKey == null ? JSONObject.NULL : contentKey)
                        .put("codec", codec.equals("bytevc1") ? "hevc" : codec).put("source", "app").put("vid", vid)
                        .put("userAgent", DanmakuSigner.USER_AGENT).put("referer", "")
                        .put("quality", quality.equals("auto") ? selected[5] : quality);
                    if (((Integer) selected[3]) > 0 && ((Integer) selected[4]) > 0) variant.put("width", selected[3]).put("height", selected[4]);
                    variants.add(variant);
                } catch (IOException error) { last = error; }
            }
        }
        if (variants.isEmpty()) throw new AppSourceException(last == null ? "本集片源地址或密钥无法解析" : last.getMessage(), "INVALID_RESPONSE");
        JSONObject primary = variants.remove(0);
        JSONObject result = new JSONObject(primary, JSONObject.getNames(primary));
        result.put("vid", vid).put("requestedQuality", quality);
        if (bounded) result.put("playbackCeiling", (int) playbackCeiling);
        if (!variants.isEmpty()) { JSONArray list = new JSONArray(); for (JSONObject variant : variants.subList(0, Math.min(7, variants.size()))) list.put(variant); result.put("variants", list); }
        return new Source(result);
    }
    private static void addCandidate(List<String> out, Object value) { if (value instanceof String && !((String) value).isEmpty()) out.add((String) value); }
    private static int[] size(JSONObject row, JSONObject meta) {
        int[][] pairs = { { optInt(row, "vwidth"), optInt(row, "vheight") }, { optInt(row, "width"), optInt(row, "height") },
            { meta == null ? 0 : optInt(meta, "vwidth"), meta == null ? 0 : optInt(meta, "vheight") }, { meta == null ? 0 : optInt(meta, "width"), meta == null ? 0 : optInt(meta, "height") } };
        for (int[] pair : pairs) if (pair[0] > 0 && pair[1] > 0) return pair;
        return new int[]{ 0, 0 };
    }
    private static int optInt(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        if (!(value instanceof Number)) return 0;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number == Math.rint(number) && number > 0 && number <= 1e5 ? (int) number : 0;
    }
    private static String firstText(JSONObject row, JSONObject meta) {
        for (String key : new String[]{ "quality_desc", "definition" }) { String value = text(row.opt(key)); if (!value.isEmpty()) return value; }
        for (String key : new String[]{ "height", "vheight", "definition" }) { String value = meta == null ? "" : text(meta.opt(key)); if (!value.isEmpty()) return value; }
        return "";
    }
    private static int digits(String label) { Matcher-free scan: int start = -1, end = -1; for (int i = 0; i < label.length(); i++) { boolean digit = Character.isDigit(label.charAt(i)); if (digit && start < 0) start = i; if (digit) end = i; else if (start >= 0) break; } try { return start >= 0 ? Integer.parseInt(label.substring(start, end + 1)) : 0; } catch (NumberFormatException ignored) { return 0; } }
}
```

(执行时把 `digits` 写成正常方法体;上面为一行速记,不允许照抄占位。)

- [x] **Step 3.4: 跑通** Expected: `APP_MEDIA_SOURCE_OK ... networkRequests=0`
- [x] **Step 3.5: Commit** `feat(android): java app-source resolution with spade decoding`

---

### Task 4: PlaybackSources 原生查找钩子(TDD)

**Files:**
- Modify: `android/app/src/main/java/io/github/jackotom/hongguo/android/PlaybackSources.java`
- Test: `android/tests/PlaybackSourcesNativeLookupTest.java`(运行脚本镜像编译 `PlaybackSourcesLookupRetryTest` 的那个 runner——执行时 `grep -l PlaybackSourcesLookupRetryTest android/tests/*.sh` 找到并复制其 stub 布局)

- [x] **Step 4.1: 失败测试要点**(完整文件执行时按现有 stub 双打编写;断言:)
  - `attachNative(lookup)` 后 `PlaybackSources.resolve(SID,VID,"1080p")` 不触达 MainActivity/WebView 脚本回调,返回 `lookup` 提供的契约 Plan(url/quality/宽高)。
  - `lookup` 抛 `AppSourceException("…","QUALITY_UNAVAILABLE")` → `resolve` 抛出且 `canLowerQuality` 为 true;`APP_INVALID_RESPONSE` → `canLowerQuality` false。
  - `detachNative` 后回退 WebView 路径(旧测试仍全绿)。
- [x] **Step 4.2: PlaybackSources 修改**(精确 diff)

在 `PlaybackSources.java` `attach/detach` 附近加:

```java
    /** Native-UI lookup used when no WebView page owns resolution; phone/tv keep the page path. */
    interface NativeLookup { org.json.JSONObject lookup(String sid, String vid, String requestedQuality, int ceiling, long timeoutMs) throws Exception; }
    private static volatile NativeLookup nativeLookup;
    static void attachNative(NativeLookup lookup) { nativeLookup = lookup; }
    static void detachNative(NativeLookup lookup) { if (nativeLookup == lookup) nativeLookup = null; }
```

`resolveAppAttempt` 开头(`MainActivity host;` 取值之前)插入:

```java
        NativeLookup lookup = nativeLookup;
        if (lookup != null) {
            if (pending.size() >= 8) throw new IOException("分集正在准备，请稍后重试");
            JSONObject info;
            try { info = lookup.lookup(sid, vid, requestedQuality, ceiling, timeoutMs); }
            catch (AppMediaSource.AppSourceException error) { throw new AppLookupException(error.getMessage() == null ? "App片源暂未取得" : error.getMessage(), error.code); }
            catch (java.net.SocketTimeoutException error) { throw new AppLookupException("App片源准备超时，请重试", "ETIMEDOUT"); }
            catch (java.io.InterruptedIOException error) { throw (java.io.InterruptedIOException) new java.io.InterruptedIOException("播放恢复已取消").initCause(error); }
            catch (Exception error) { throw new AppLookupException(error.getMessage() == null ? "App片源暂未取得" : error.getMessage(), ""); }
            if (!requestedQuality.equals(info.optString("requestedQuality", requestedQuality))) throw new IOException("片源清晰度结果不匹配");
            if (ceiling > 0 && info.optInt("playbackCeiling", -1) != ceiling) throw new IOException("播放清晰度边界结果不匹配");
            return info;
        }
```

同时:AppMediaSource 的编译需要进 runner 闭包(SpadeDecoder/MediaQuality/DanmakuSigner 一起编译,或 runner 里给 DanmakuSigner 提供 stub——按现有 runner 惯例优先编译真实文件)。
- [x] **Step 4.3: 跑新测试 + 全部 PlaybackSources 相关旧测试**:`run-native-lookup-tests.sh`、`run-source-deadline-tests.sh`、PlaybackSources 系列全部。
- [x] **Step 4.4: Commit** `feat(android): native lookup hook in PlaybackSources`

---

### Task 5: SiteCatalog(TDD)

**Files:**
- Modify: `android/app/src/main/java/io/github/jackotom/hongguo/android/EpisodeResolver.java`(仅可见性:`static JSONObject extractRouterData`、`static String publicHttpsUrl` 去 private)
- Create: `android/app/src/main/java/io/github/jackotom/hongguo/android/SiteCatalog.java`
- Test: `android/tests/SiteCatalogTest.java`、`android/tests/run-site-catalog-tests.sh`

- [x] **Step 5.1: 失败测试**(fixture 页面内联构造,风格同 `EpisodeResolverTest`;断言 items 形状、页码、身份不符拒绝、详情分集列表与 accessible 计数、搜索结果)

```java
package io.github.jackotom.hongguo.android;
import org.json.*;
/** Inline _ROUTER_DATA fixtures; parser only, no network. */
public final class SiteCatalogTest {
    static int checks;
    static void check(boolean v, String m){checks++;if(!v)throw new AssertionError(m);}
    static String page(JSONObject loaderData){ return "<script>window._ROUTER_DATA = " + new JSONObject().put("loaderData", loaderData) + ";</script>"; }
    static JSONObject categoryData(String contentType, String... names) throws Exception {
        JSONArray list = new JSONArray();
        for (int i = 0; i < names.length; i++) list.put(new JSONObject().put("series_id", "10000" + i).put("series_name", names[i])
            .put("series_cover", "https://p.byteimg.com/c" + i + ".webp").put("series_intro", "简介" + i).put("episode_cnt", 12 + i)
            .put("tags", new JSONArray().put("逆袭")).put("episode_right_text", "全12集"));
        return new JSONObject().put("categoryRoute", new JSONObject().put("contentType", contentType))
            .put("isSuccess", true).put("recommendList", list)
            .put("pagination", new JSONObject().put("pageNum", 2).put("totalPages", 5).put("total", 96));
    }
    public static void main(String[] unused) throws Exception {
        JSONObject catalog = SiteCatalog.parseCatalog(page(new JSONObject().put("category_real-drama/page", categoryData("real-drama", "剧一", "剧二", "剧二"))), "real-drama", 2);
        check(catalog.getInt("page") == 2 && catalog.getInt("totalPages") == 5, "pagination");
        JSONArray items = catalog.getJSONArray("items");
        check(items.length() == 2, "dedupe by seriesId");
        check(items.getJSONObject(0).getString("title").equals("剧一") && items.getJSONObject(0).getInt("episodeCount") == 12, "series shape");
        // wrong category content type → rejected
        try { SiteCatalog.parseCatalog(page(new JSONObject().put("category_real-drama/page", categoryData("comic-drama", "x"))), "real-drama", 1); throw new AssertionError("accepted"); } catch (Exception expected) { }
        // detail
        JSONObject detailData = new JSONObject().put("isSuccess", true)
            .put("seriesDetail", new JSONObject().put("series_id", "100000").put("series_name", "详情剧").put("series_intro", "好看")
                .put("episode_cnt", 3).put("vid_list", new JSONArray().put("9001").put("9002").put("9001")).put("accessible_episode_cnt", 2));
        JSONObject detail = SiteCatalog.parseDetail(page(new JSONObject().put("player_(series_id)/page", detailData)), "100000");
        check(detail.getJSONArray("episodes").length() == 2, "dedupe vid_list");
        check(detail.getJSONArray("episodes").getJSONObject(0).getBoolean("webAvailable") && !detail.getJSONArray("episodes").getJSONObject(1).getBoolean("webAvailable"), "accessible gate");
        check(detail.getInt("accessibleEpisodeCount") == 1, "accessible count");
        // identity mismatch rejected
        try { SiteCatalog.parseDetail(page(new JSONObject().put("player_(series_id)/page", detailData)), "100001"); throw new AssertionError("accepted"); } catch (Exception expected) { }
        // web search
        JSONObject searchData = new JSONObject().put("isSuccess", true).put("searchList", new JSONArray().put(new JSONObject()
            .put("video_data", new JSONObject().put("series_id", "777").put("series_title", "搜索剧").put("category_list", new JSONArray().put(new JSONObject().put("name", "甜宠"))))))
            .put("total", 1);
        JSONObject search = SiteCatalog.parseSearch(page(new JSONObject().put("search_(keyword)/page", searchData)));
        check(search.getJSONArray("items").getJSONObject(0).getString("seriesId").equals("777"), "search item shape");
        System.out.println("SITE_CATALOG_OK checks=" + checks + " networkRequests=0");
    }
}
```

- [x] **Step 5.2: 实现 SiteCatalog**(解析按 `source.mjs:89-152` 逐行对应;抓取编排 `loadCatalog/loadDetail/loadSearchWeb` 用 `NetworkGuard.get`,8MiB 上限与 30s 缓存沿用;`loadDetail` 先 `/player/{sid}` 再 `/detail?series_id={sid}`;`detail`/`catalog` 输出直接是 `LibraryStore.series` 兼容形状)

```java
package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Official public-site catalog/detail/search parsing and bounded fetching. */
final class SiteCatalog {
    static final String ORIGIN = "https://hongguoduanju.com";
    static final String[] CATEGORIES = { "real-drama", "comic-drama", "ai-drama" };
    private SiteCatalog() { }
    static String categoryLabel(String id) { return id.equals("real-drama") ? "真人剧" : id.equals("comic-drama") ? "漫剧" : "AI剧"; }
    private static String text(Object value, int max) {
        if (!(value instanceof String)) return "";
        String s = ((String) value).trim();
        return s.length() <= max && !s.matches("(?s).*[\\x00-\\x1f\\x7f].*") ? s : "";
    }
    private static int count(Object value) {
        if (!(value instanceof Number)) return 0;
        double n = ((Number) value).doubleValue();
        return Double.isFinite(n) && n == Math.rint(n) && n >= 0 && n <= 10000 ? (int) n : 0;
    }
    private static JSONObject series(JSONObject item, boolean search) throws JSONException {
        if (item == null) return null;
        String id = text(item.opt(search ? "series_id" : "series_id"), 30);
        // 目录与搜索的主键都叫 series_id;搜索标题字段为 series_title,目录为 series_name。
        String title = text(item.opt(search ? "series_title" : "series_name"), 200);
        if (!id.matches("[0-9]{1,30}") || title.isEmpty()) return null;
        JSONArray rawTags = item.optJSONArray(search ? "category_list" : "tags");
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (rawTags != null) for (int i = 0; i < rawTags.length() && tags.size() < 8; i++) {
            Object raw = rawTags.opt(i);
            String tag = raw instanceof JSONObject ? text(((JSONObject) raw).opt("name"), 80) : text(raw, 80);
            if (!tag.isEmpty()) tags.add(tag);
        }
        return new JSONObject().put("seriesId", id).put("title", title)
            .put("coverUrl", EpisodeResolver.publicHttpsUrl(text(item.opt("series_cover"), 4096)))
            .put("description", text(item.opt("series_intro"), 8000))
            .put("episodeCount", count(item.opt("episode_cnt")))
            .put("episodeLabel", text(item.opt("episode_right_text"), 80))
            .put("tags", new JSONArray(new ArrayList<>(tags)));
    }
    static JSONObject parseCatalog(String html, String category, int page) throws Exception {
        if (!Arrays.asList(CATEGORIES).contains(category) || page < 1) throw new IOException("目录参数无效");
        JSONObject router = EpisodeResolver.extractRouterData(html);
        JSONObject data = null;
        JSONObject loaders = router.optJSONObject("loaderData");
        if (loaders != null) for (Iterator<String> i = loaders.keys(); i.hasNext(); ) {
            JSONObject candidate = loaders.optJSONObject(i.next());
            if (candidate != null && candidate.optJSONObject("categoryRoute") != null && category.equals(candidate.getJSONObject("categoryRoute").opt("contentType"))) { data = candidate; break; }
        }
        JSONArray recommend = data == null ? null : data.optJSONArray("recommendList");
        if (data == null || Boolean.FALSE.equals(data.opt("isSuccess")) || recommend == null) throw new IOException("来源目录暂不可用");
        JSONObject pagination = data.optJSONObject("pagination");
        JSONArray items = unique(recommend, false);
        return new JSONObject().put("items", items).put("page", pagination == null ? page : count(pagination.opt("pageNum")) == 0 ? page : count(pagination.opt("pageNum")))
            .put("totalPages", pagination == null ? 0 : count(pagination.opt("totalPages"))).put("total", pagination == null ? items.length() : count(pagination.opt("total")));
    }
    static JSONObject parseSearch(String html) throws Exception {
        JSONObject loaders = EpisodeResolver.extractRouterData(html).optJSONObject("loaderData");
        JSONObject data = loaders == null ? null : loaders.optJSONObject("search_(keyword)/page");
        JSONArray list = data == null ? null : data.optJSONArray("searchList");
        if (data == null || Boolean.FALSE.equals(data.opt("isSuccess")) || list == null) throw new IOException("来源搜索暂不可用");
        JSONArray items = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject cell = list.optJSONObject(i);
            JSONObject video = cell == null ? null : cell.optJSONObject("video_data");
            JSONObject row = video == null ? null : series(video, true);
            if (row != null && notSeen(items, row.getString("seriesId"))) items.put(row);
        }
        return new JSONObject().put("items", items);
    }
    static JSONObject parseDetail(String html, String seriesId) throws Exception {
        if (seriesId == null || !seriesId.matches("[0-9]{1,30}")) throw new IOException("剧集编号无效");
        JSONObject loaders = EpisodeResolver.extractRouterData(html).optJSONObject("loaderData");
        JSONObject data = firstNonNull(loaders == null ? null : loaders.optJSONObject("player_(series_id)/page"), loaders == null ? null : loaders.optJSONObject("player_(series_id)/(vid)/page"), loaders == null ? null : loaders.optJSONObject("detail_page"));
        JSONObject detail = data == null ? null : data.optJSONObject("seriesDetail");
        if (data == null || Boolean.FALSE.equals(data.opt("isSuccess")) || detail == null || !seriesId.equals(text(detail.opt("series_id"), 30))
            || detail.has("series_id") && !seriesId.equals(detail.get("series_id"))) throw new IOException("剧集详情与请求编号不一致");
        JSONObject item = series(detail, false);
        JSONArray vids = detail.optJSONArray("vid_list");
        if (item == null || vids == null) throw new IOException("来源页面缺少剧集详情或分集列表");
        int accessible = count(detail.opt("accessible_episode_cnt"));
        JSONArray episodes = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < vids.length(); i++) {
            String vid = text(vids.opt(i), 30);
            if (!vid.matches("[0-9]{1,30}") || !seen.add(vid)) continue;
            episodes.put(new JSONObject().put("vid", vid).put("index", seen.size()).put("webAvailable", seen.size() <= accessible).put("availability", seen.size() <= accessible ? "available" : "unknown"));
        }
        if (episodes.length() == 0) throw new IOException("来源未返回有效分集");
        int available = 0;
        for (int i = 0; i < episodes.length(); i++) if (episodes.getJSONObject(i).getBoolean("webAvailable")) available++;
        return item.put("episodes", episodes).put("accessibleEpisodeCount", available);
    }
    private static JSONArray unique(JSONArray rows, boolean search) throws JSONException {
        JSONArray items = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = series(rows.optJSONObject(i), search);
            if (row != null && notSeen(items, row.getString("seriesId"))) items.put(row);
        }
        return items;
    }
    private static boolean notSeen(JSONArray items, String id) throws JSONException {
        for (int i = 0; i < items.length(); i++) if (id.equals(items.getJSONObject(i).getString("seriesId"))) return false;
        return true;
    }
    private static JSONObject firstNonNull(JSONObject... values) { for (JSONObject value : values) if (value != null) return value; return null; }
    // ---- bounded fetching (NetworkGuard transport) ----
    static JSONObject loadCatalog(String category, int page) throws Exception {
        NetworkGuard.Reply reply = NetworkGuard.get(ORIGIN + "/category/" + category + (page > 1 ? "?page=" + page : ""));
        requireOk(reply);
        return parseCatalog(reply.body, category, page);
    }
    static JSONObject loadDetail(String seriesId) throws Exception {
        Exception failure = null;
        for (String path : new String[]{ "/player/" + seriesId, "/detail?series_id=" + seriesId }) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("分集加载已取消");
            try {
                NetworkGuard.Reply reply = NetworkGuard.get(ORIGIN + path);
                requireOk(reply);
                return parseDetail(reply.body, seriesId);
            } catch (Exception error) { failure = error; }
        }
        throw failure == null ? new IOException("分集加载未成功") : failure;
    }
    static JSONObject loadSearchWeb(String keyword) throws Exception {
        if (keyword == null || keyword.trim().isEmpty() || keyword.trim().length() > 100) throw new IOException("请输入 1 至 100 字的搜索词");
        NetworkGuard.Reply reply = NetworkGuard.get(ORIGIN + "/search/" + java.net.URLEncoder.encode(keyword.trim(), "UTF-8"));
        requireOk(reply);
        return parseSearch(reply.body);
    }
    private static void requireOk(NetworkGuard.Reply reply) throws IOException {
        if (reply.status < 200 || reply.status >= 300) throw new IOException("来源页面响应 HTTP " + reply.status);
    }
}
```

注:测试里 search fixture 的 cell 是 `{video_data:{...}}`(与 `uniqueSeries(items, true)` 的 `item?.video_data` 一致);目录 fixture 的 recommendList 项直接是 series 对象。执行时以 `source.mjs:89-109` 为准对齐字段名(`series(item, search)` 中 search 分支读 `video_data` 内层,已在 `parseSearch` 处理)。
- [x] **Step 5.3: runner** `run-site-catalog-tests.sh`(编译闭包:EpisodeResolver、NetworkGuard、SiteCatalog、测试——NetworkGuard 真实编译,不发网)
- [x] **Step 5.4: 跑通 + 旧测试回归** `run-episode-resolver-tests.sh` 必须仍绿(可见性变更零行为变化)
- [x] **Step 5.5: Commit** `feat(android): official site catalog/detail/search parsing`

---

### Task 6: AppSearchPage(TDD)

**Files:**
- Create: `android/app/src/main/java/io/github/jackotom/hongguo/android/AppSearchPage.java`
- Test: `android/tests/AppSearchPageTest.java`(并入 run-site-catalog-tests.sh)

- [x] **Step 6.1: 失败测试要点**:tab_type=11 提取;lynx_data 嵌套 cell 展开;字符串 ID 精度保护(数值 ID 丢弃);canonical 标题绑定(`video_detail.series_id_str === seriesId`);封面域白名单外置空;`has_more` + `next_offset` 游标推进(不前进 → `SEARCH_CURSOR_STALLED`);去重 seen;>40 项拒绝;错误码消息中文。
- [x] **Step 6.2: 实现**(逐行对应 `search.mjs:34-88`;纯静态 `JSONObject parse(JSONObject envelope, int offset, Set<String> seen)` → `{items, hasMore, cursor:{offset,searchId,passback}}`)
- [x] **Step 6.3: 跑通;Commit** `feat(android): app cursor search projection`

---

### Task 7: PlayLauncher 抽取

**Files:**
- Create: `android/app/src/main/java/io/github/jackotom/hongguo/android/PlayLauncher.java`
- Modify: `android/app/src/main/java/io/github/jackotom/hongguo/android/NativeBridge.java:338-361`

- [x] **Step 7.1: PlayLauncher.build** — 把 `NativeBridge.play` 中 `Intent intent = ...` 起的 9 行原样移入:

```java
package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.Intent;
import org.json.*;

/** Single construction point of the player Intent shared by the WebView bridge and the native TV UI. */
final class PlayLauncher {
    private PlayLauncher() { }
    static Intent build(Context context, JSONObject series, JSONObject episode, JSONArray episodes, String url, boolean local, JSONObject params) throws JSONException {
        JSONObject defaults = PlayerOptions.read(context);
        String quality = MediaQuality.normalize(params.optString("quality", defaults.getString("quality")));
        double speed = params.optDouble("speed", defaults.getDouble("speed"));
        PlayerOptions.validateSpeed(speed);
        Intent intent = new Intent(context, PlayerActivity.class).putExtra("series", series.toString()).putExtra("episode", episode.toString())
            .putExtra("episodes", episodes.toString()).putExtra("url", url).putExtra("download", local)
            .putExtra("quality", quality).putExtra("speed", speed)
            .putExtra("autoplay", params.optBoolean("autoplay", true)).putExtra("cleanupPlaybackCache", params.optBoolean("cleanupPlaybackCache", true))
            .putExtra("keepPreviousEpisodes", 1).putExtra("playInfo", params.optJSONObject("playInfo") == null ? "" : params.getJSONObject("playInfo").toString());
        if (!local && params.optJSONObject("playlist") != null) intent.putExtra("playlist", params.getJSONObject("playlist").toString());
        return intent;
    }
}
```

`NativeBridge.play` 改为 `Intent intent = PlayLauncher.build(activity, series, episode, episodes, url, local, params);`(其余不动)。行为等价:原方法里 quality/speed 默认值读取移入 build(原本就在 runOnUiThread 内做,顺序无副作用)。
- [x] **Step 7.2: 回归** — 编译 + `run-playback-autoplay-activity-tests.sh` 等播放系列测试(如需 android.jar 的部分跳过,以 gradle assemblePhoneDebug 编译为准)+ `gradle :app:assemblePhoneDebug`
- [x] **Step 7.3: Commit** `refactor(android): extract PlayLauncher intent construction`

---

### Task 8: TvNativeActivity 首页

**Files:**
- Rewrite: `android/app/src/main/java/io/github/jackotom/hongguo/android/TvNativeActivity.java`

- [x] **Step 8.1: 实现**(要点,完整代码执行时按此写):
  - 顶部横向 `LinearLayout` 头部按钮:`真人剧/漫剧/AI剧 | 搜索 | 收藏 | 下载 | 设置`(Button,焦点高亮用 `StateListDrawable`/背景色变化;选中分类重置页码并加载)。
  - `GridView numColumns=4`,item = 竖向 LinearLayout:标题(两行)、`episodeLabel · N集`、tags 一行;焦点边框高亮。底部 "加载更多" 按钮(totalPages 未达且非收藏/下载 tab)。
  - 数据:`SiteCatalog.loadCatalog(category,page)` 在单线程 executor;收藏 tab 用 `new LibraryStore(this).library().getJSONArray("favorites")`;下载 tab 用 `LibraryStore.downloads()`(标题/状态文本,仅 success 可点 → 复用 Task 10 的离线播放辅助;v1 无删除)。
  - 点击剧集 → `TvDetailActivity`(`seriesId` extra);收藏项同样进详情。
  - 设置 AlertDialog:清晰度三选(`PlayerOptions.update({quality})`)、弹幕开关、清除播放缓存(`PlaybackCache.get(this).clear()`)、版本展示。
  - `PlaybackSources.attachNative((sid, vid, quality, ceiling, timeoutMs) -> new AppMediaSource().resolve(vid, quality, ceiling == 0 ? null : ceiling).json)` 于 `onCreate`;`onDestroy` `detachNative`。executor shutdownNow 取消在途请求。
  - 生命周期:`onResume/onPause` 调 `AccountSync.get().foreground()/flush()`、`AppUpdater resume/pause`(与 MainActivity 一致;`updates.pageReady()` 首次渲染后调用一次)。
  - 网络错误 → AlertDialog(重试/关闭);所有 UI 更新 `runOnUiThread`。
- [x] **Step 8.2: 编译** `gradle :app:assembleTvNativeDebug`
- [x] **Step 8.3: Commit** `feat(android): native tv home screen`

---

### Task 9: TvDetailActivity 详情/选集

**Files:**
- Create: `android/app/src/main/java/io/github/jackotom/hongguo/android/TvDetailActivity.java`

- [x] **Step 9.1: 实现**要点:
  - extra `seriesId`;`SiteCatalog.loadDetail` 后端加载;标题/简介/集数/标签;`继续观看 第N集` 按钮(有 `LibraryStore.seriesHistory(seriesId)` 进度时显示,直接定位该集)。
  - 分集 `GridView`(项:`第N集`,非 webAvailable 灰色小标记);点击播放:
    ```java
    JSONObject series = LibraryStore.series(detail);            // detail 含 LibraryStore.series 需要的字段
    JSONObject episode = LibraryStore.episode(episodes.getJSONObject(i));
    JSONArray queue = LibraryStore.episodes(episodes, episode);
    JSONObject params = new JSONObject().put("resolveInPlayer", true);
    startActivity(PlayLauncher.build(this, series, episode, queue, null, false, params));
    ```
  - 收藏按钮:镜像 `NativeBridge.toggleFavorite` 的 `AccountSync.get().captureSelection()/requireCurrent` 归属保护 + `new LibraryStore(this).toggle(LibraryStore.series(detail))`。
  - 季:后台 `OfficialSeriesService.load(sid)`(带缓存),成功且 items>1 时显示横向季按钮行(当前季高亮),点击 → `TvDetailActivity` 以新 seriesId 重启(finish + startActivity 或 recreate 模式)。
  - 失败/无分集 → 错误对话框 + 重试。
- [x] **Step 9.2: 编译 + Commit** `feat(android): native tv detail and episode selection`

---

### Task 10: TvSearchActivity 搜索

**Files:**
- Create: `android/app/src/main/java/io/github/jackotom/hongguo/android/TvSearchActivity.java`

- [x] **Step 10.1: 实现**要点:
  - 顶部 `EditText`(TV 遥控可输入)+ 搜索 Button;历史 chips(`LibraryStore.searchHistory()`;搜索成功 `addSearchHistory({keyword})`,长按 chip 删除单条)。
  - 结果 GridView;`加载更多` 维护 `{offset,searchId,passback}` 游标链:`SearchClient.search(this, params)` → `AppSearchPage.parse` 追加。App 搜索失败时兜底 `SiteCatalog.loadSearchWeb`(仅首屏,无翻页)。
  - 结果项含 `episodeCount/episodeLabel/tags`,点击进 `TvDetailActivity`。
  - 单飞行线程 + 取消;错误对话框中文消息。
- [x] **Step 10.2: 编译 + Commit** `feat(android): native tv search`

---

### Task 11: 构建脚本、文档、更新链路

**Files:**
- Modify: `scripts/build-android.sh`(若 Task 1 未完成全部模式)、`ANDROID.md`、`android/README.md`

- [x] **Step 11.1: build-android.sh 全模式**(debug 追加 tvNative;`--tvnative-release-compatible`/`--tvnative-release` 镜像 tv 分支;gradle 任务名 `:app:assembleTvNativeDebug/Release`)
- [x] **Step 11.2: 文档**:ANDROID.md 增"tvNative 原生 TV 版"小节(定位、限制:无账号登录 UI/无网页视图、版本线独立 0.1.x、与 WebView 版共存);android/README.md 构建命令与测试脚本补充。
- [x] **Step 11.3: Commit** `docs(android): tvNative flavor guide`

---

### Task 12: 回归与验收

- [x] **Step 12.1: 新测试全绿**:`run-app-media-source-tests.sh`、`run-site-catalog-tests.sh`、`run-native-lookup-tests.sh`
- [x] **Step 12.2: 旧测试回归**:`run-episode-resolver-tests.sh`、`run-source-deadline-tests.sh`、`run-source-candidates-contract-tests.sh`、`run-initial-playback-tests.sh`、`run-playback-resolution-source-tests.sh`、`run-playback-resolution-cache-tests.sh`、`run-quality-cache-tests.sh`、`run-empty-app-response-tests.sh`、`run-search-protocol-tests.sh`、`run-library-store-queue-tests.sh`、`run-player-options-tests.sh`
- [x] **Step 12.3: 三 flavor 构建**:`gradle :app:assemblePhoneDebug :app:assembleTvDebug :app:assembleTvNativeDebug` 全部 BUILD SUCCESSFUL
- [x] **Step 12.4: 手动验收(模拟器/设备,条件允许时)**:tvNative 安装启动 → 分类加载 → 详情 → 播放第 1 集(auto 与 720p)→ 下一集连播 → 搜索 → 收藏 → 重启后收藏保留;tv(WebView 版)与 phone 包不受影响(可共存安装)。
- [x] **Step 12.5: Commit** `test(android): tvNative regression gates`

---

## Self-Review 结论

- **覆盖**:用户需求 B(取源 Java 化 + TV 原生界面,手机保留 WebView)+ "独立版本"(独立 flavor/包名/版本线/可共存)→ Task 1、3-4、8-10、11 全覆盖;播放/缓存/降档/投屏复用既有实现。
- **风险与对策**:①`RegisteredPlaybackModel` 每次取源都新登记设备(连播变慢)→ v1 接受(与现网 WebView 空响应回退同路径),后续可做持久会话;②`multi_video_model` 主路径未移植 → 注册路径已生产验证,够 v1;③官网改版 → 解析器全部有 fixture 测试,失败信息中文;④tvNative 无账号 UI → 收藏走 guest owner,AccountSync 生命周期已接线。
- **不做(YAGNI)**:手机原生 UI、账号登录界面、跨季自动连播 playlist、封面图加载、下载删除 UI、medusa/argus 全量签名移植。

---

## As-built 执行记录(2026-10-28)

全部任务完成,唯一未执行的是 12.4 手动设备验收(需要模拟器/电视)。与计划的偏差:

- **AppUpdater 未接入 tvNative**:其构造与提示链绑定 MainActivity/WebView(`host.trusted()/host.script()`),v1 无应用内自更新提示,已在 ANDROID.md 边界中注明。
- **PlaybackSourcesLookupRetryTest 修正**:WIP 把单次等待 14s→28s 并新增超时取消脚本(`__androidAppCancelResolve`,单参数),已提交的测试解析器假定 ≥2 参数导致既存失败(与本次改动无关,基线复现确认)。按新行为更新了该测试回调(跳过并计数取消脚本),未改产品代码。
- **run-media-redirect-recovery 仍有 3 组失败**("unexpected stage 480p"):基线(禁用本次钩子)复现同样失败,属 WIP 的 AdaptiveQuality 改动与已提交测试滞后,本次不动。
- 八个编译 PlaybackSources 的旧 runner 补入了 SpadeDecoder/DanmakuSigner/AppMediaSource 三个真实源文件(新依赖),七个可离线运行的全部恢复绿色;run-source-candidates 需外部 --input,仅做了同样的编译集修补。
- 测试期间的小修:AppMediaSourceTest 变体排序断言与 JS 语义对齐(h264 全部先于 hevc);SiteCatalog fixture 修正重复 seriesId 与 accessible 计数;AppSearchPage 单页 40 上限改为放入后检查(与 JS 一致);数值 series_id 允许回落 cell.book_id(与 JS 一致)。
- 新增 runner:run-app-media-source-tests.sh、run-site-catalog-tests.sh;PlaybackSourcesNativeLookupTest 挂入 run-native-media-contract-tests.sh。
- release:--tvnative-release(-compatible) 出包+SHA-256,跳过 check-android-release(其包名/版本断言仅适配 phone/tv),已在 ANDROID.md 注明。

## 追加执行记录:应用内自动更新(2026-10-28 第二轮)

用户要求 tvNative 也支持自动更新。实现:

- `AppUpdater` 解耦:字段改为 `Activity host` + 可空 `MainActivity page`(`AppUpdater(MainActivity)` 旧构造保留,MainActivity/既有桩件零改动);页面通知走 `pageScript`,信任判定走 `pageAlive()`(无页面时=Activity 存活)。新增 `manualCheck/manualDownload/manualInstall/manualCancel/snapshotJson` 供原生 UI;`maybePrompt` 扩展:无页面且状态 ready 时弹"更新已就绪"安装确认(独立 readyPromptedCode 防重复)。
- `UpdatePolicy` 新增 tvnative 渠道:`manifestUrl/manifestSources(String flavor)`;validate 按安装包后缀派生期望 flavor(`.tvnative`/`.tv`/phone)与包名;tvNative 资产前缀 `HongguoNative-<版本>-tvNative-release`,`downloadSources` 正则强制 Native↔tvNative 配对。测试 39→54 checks。
- `scripts/generate-android-update.mjs` 支持 `--device tvnative`(包名/资产名校验同规则);新建 `android/update-tvnative.json` 初始清单(0.1.0/code1,与已装同版本,占位 sha/size,发布前须重新生成)。
- `TvNativeActivity` 接入:设置对话框增加"检查更新"与状态摘要;生命周期 pageReady/resume/pause/destroy 对齐。
- 桩件适配:update-download-harness 的 manifestSources 组改为三口味字符串,BuildConfig 桩补 NATIVE_UI;AppUpdater 抽取式桩件要求保留 `host` 字段名与 fetchManifest 内联 flavor 表达式(无 flavorName() 辅助)。
- 回归:update-policy(54)/update-resume(11 组)/update-restore(18 组)/update-download-proxy(26 组)/update-checkpoint/update-operation-gate 全绿;三 flavor 零错误构建。

## 模拟器实测记录(2026-10-28,Small_Phone AVD,Android 16/arm64,无头)

- 安装 `app-tvNative-debug.apk` → 冷启动 TvNativeActivity 成功(TotalTime 675ms);首次冷加载曾因模拟器网络未就绪弹"提示/重试",重试即恢复,重试路径工作正常。
- 首页目录真实数据加载(王妈在顾家当保姆后全家只会搞笑了/归路/枝头春意闹等 9 部,分类/标签/加载更多正常);点击进详情:简介、86 集分集、第1/第2季官方季行、accessible 提示全部正确。
- 遥控器确认键播放 → PlayerActivity 启动并真实解码渲染(截屏经视觉确认有画面与字幕,进度已记录——续播按钮自动变为第 2 集),零崩溃。
- 实测发现并修复两个真缺陷:①网格子项误设 focusable/clickable 吞掉 onItemClick,改为 GridView 选择模式(selector 高亮,子项不抢焦点);②触摸进详情后无预设焦点,补"焦点默认落在播放键"。PlayerActivity"播放信息无效"分支补了错误日志(原为静默 finish)。
- 验证后的 APK 已含上述修复;模拟器(emulator-5582,5554 端口对应的 5582)保留运行中。
