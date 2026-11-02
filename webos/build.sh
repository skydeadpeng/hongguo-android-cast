#!/bin/bash
# 零依赖 webOS .ipk 打包：等价 ares-package 的产物结构（ar + control.tar.gz + data.tar.gz）。
# 用法：bash webos/build.sh [输出目录]（默认 webos/dist）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/webos/dist}"
APP="$ROOT/webos"
ID="$(python3 -c "import json;print(json.load(open('$APP/appinfo.json'))['id'])")"
VER="$(python3 -c "import json;print(json.load(open('$APP/appinfo.json'))['version'])")"
NAME="${ID}_${VER}_all.ipk"
STAGE="$(mktemp -d -t hongguo-webos.XXXXXX)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/data/usr/palm/applications/$ID" "$STAGE/control"
cp "$APP/appinfo.json" "$APP/index.html" "$APP/icon.png" "$APP/icon130.png" "$STAGE/data/usr/palm/applications/$ID/"
cp -R "$APP/js" "$APP/css" "$STAGE/data/usr/palm/applications/$ID/"

cat > "$STAGE/control/control" <<EOF
Package: $ID
Version: $VER
Architecture: all
Section: webos
Priority: optional
Maintainer: skydeadpeng <skydeadpeng@users.noreply.github.com>
Description: 红果短剧 webOS TV 客户端（官网网页源）
EOF

(cd "$STAGE/control" && tar czf ../control.tar.gz ./control)
(cd "$STAGE/data" && tar czf ../data.tar.gz ./usr)
printf '2.0\n' > "$STAGE/debian-binary"
mkdir -p "$OUT"
rm -f "$OUT/$NAME"
# 手写 ar 归档：macOS 的 ar 会附加 __.SYMDEF 符号表项，opkg 拒绝；直接产出规范三成员归档。
python3 - "$OUT/$NAME" "$STAGE" <<'PYEOF'
import sys, os
out, stage = sys.argv[1], sys.argv[2]
members = ['debian-binary', 'control.tar.gz', 'data.tar.gz']
with open(out, 'wb') as archive:
    archive.write(b'!<arch>\n')
    for name in members:
        data = open(os.path.join(stage, name), 'rb').read()
        header = f'{name:<16}{0:<12}{0:<6}{0:<6}{100644:<8}{len(data):<10}`\n'.encode()
        archive.write(header)
        archive.write(data)
        if len(data) % 2: archive.write(b'\n')
PYEOF
echo "BUILT $OUT/$NAME"
python3 -c "
import sys
data = open(sys.argv[1], 'rb').read()
assert data.startswith(b'!<arch>\n')
offset = 8
names = []
while offset < len(data):
    names.append(data[offset:offset+16].decode().strip())
    size = int(data[offset+48:offset+58].decode().strip())
    offset += 60 + size + (size % 2)
print('members:', names)
assert names == ['debian-binary', 'control.tar.gz', 'data.tar.gz'], names
print('IPK_OK')
" "$OUT/$NAME"
