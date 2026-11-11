# 番茄静态分析用于红果播放修复

目标是修复现有红果播放，不新增番茄入口，不换用番茄身份取流。

- [调查总报告](SYSTEMATIC_REPORT.md)：全包索引、视频链路、已证实结论与未覆盖范围。
- [模型契约](model-contract.md)：主 App 与 Series SDK 的不同请求/响应和枚举。
- [批量模型内容类型](mixed-video-id-map-contract.md)：真实构造证据与未改动的类型传递问题。
- [明文、试看与权限](plaintext-and-entitlement-contract.md)：本次修复直接依据。
- [实现评估](implementation-review.md)及[验证记录](verification.md)。
- [官网复核](public-web-review.md)、[APK来源](apk-source.json)、[文件/DEX校验索引](inventory.json)。

原始官方APK、完整反汇编及运行期媒体不放入仓库。索引是全包结构覆盖；深入分析集中于视频调用链，不声称已经审完全部类、原生库或运行期动态配置。

## 本地复现

需要 Python 3 和官方 Android SDK build-tools 35.0.0 的 aapt、apksigner、dexdump。不安装或执行被分析的APK。下载后先核对 SHA-256，须匹配报告中的样本，否则结果/地址不适用。

```sh
APK=/path/to/fanqie-official.apk
OUT=/path/to/analysis-output
SDK="$ANDROID_HOME/build-tools/35.0.0"
mkdir -p "$OUT"
sha256sum "$APK"
"$SDK/apksigner" verify --verbose --print-certs "$APK" > "$OUT/apk-signature.txt"
"$SDK/aapt" dump badging "$APK" > "$OUT/apk-badging.txt"
"$SDK/aapt" dump xmltree "$APK" AndroidManifest.xml > "$OUT/manifest-xmltree.txt"
python3 scripts/fanqie-static/fanqie_static_inventory.py "$APK" "$OUT"
for n in 9 14 16 17; do
  unzip -p "$APK" "classes$n.dex" > "$OUT/classes$n.dex"
  "$SDK/dexdump" -a -d "$OUT/classes$n.dex" > "$OUT/classes$n-dump.txt"
done
python3 scripts/fanqie-static/extract_model_contract.py "$OUT"
python3 scripts/fanqie-static/extract_enum_contract.py "$OUT"
python3 scripts/fanqie-static/extract_tt_evidence.py "$OUT"
python3 scripts/fanqie-static/write_contract_report.py "$OUT"
python3 scripts/fanqie-static/scan_fanqie_calls.py "$APK" "$OUT" "$SDK/dexdump" \
  classes4.dex classes5.dex classes6.dex classes10.dex classes11.dex classes16.dex
```

调用边扫描针对选定名称与DEX，有假阳性/漏报，需人工核对；枚举脚本仅解析已观察到的直线寄存器常量与构造函数，不是通用DEX解释器。JSON字段名与权限枚举仅作契约证据，不包含账号、设备身份、访问令牌、播放密钥或签名媒体地址。
