#!/bin/bash
set -euo pipefail

compatible_node() {
  "$1" -e 'const [major, minor] = process.versions.node.split(".").map(Number); process.exit(major > 22 || (major === 22 && minor >= 12) ? 0 : 1)' >/dev/null 2>&1
}

# Finder and ordinary terminals may select an older Node. Keep the change local
# to this launcher and reuse a compatible installation instead of changing nvm.
export PATH="$PATH:/opt/homebrew/bin:/usr/local/bin"
if ! command -v node >/dev/null 2>&1 || ! compatible_node "$(command -v node)"; then
  for candidate in "$HOME"/.nvm/versions/node/v*/bin/node \
    /opt/homebrew/opt/node@22/bin/node /opt/homebrew/opt/node/bin/node \
    /usr/local/opt/node@22/bin/node /usr/local/opt/node/bin/node; do
    if [[ -x "$candidate" ]] && compatible_node "$candidate"; then
      export PATH="$(dirname "$candidate"):$PATH"
      break
    fi
  done
fi

if ! command -v node >/dev/null 2>&1 || ! compatible_node "$(command -v node)"; then
  printf 'CHECK_FAILED node=%s required=>=22.12\n' "$(node --version 2>/dev/null || echo missing)" >&2
  exit 1
fi

if [[ "${1:-}" == "--check" ]]; then
  node -p '"CHECK_OK node=" + process.version + " platform=" + process.platform + " arch=" + process.arch'
  exit 0
fi

cd "$(dirname "$0")"
if [[ ! -x node_modules/.bin/electron ]]; then
  echo '请先在项目目录运行 npm ci。' >&2
  exit 1
fi
unset ELECTRON_RUN_AS_NODE
printf '使用 Node %s 启动红果开发模式\n' "$(node --version)"
exec npm run dev
