#!/bin/bash
# Isolated mocked UI acceptance test. Starts and stops only its own Vite/Chrome.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
compatible_node() { "$1" -e 'const [a,b]=process.versions.node.split(".").map(Number);process.exit(a>22||(a===22&&b>=12)?0:1)' >/dev/null 2>&1; }
if ! command -v node >/dev/null 2>&1 || ! compatible_node "$(command -v node)"; then
  for candidate in "$HOME"/.nvm/versions/node/v*/bin/node /opt/homebrew/opt/node@22/bin/node; do
    if [[ -x "$candidate" ]] && compatible_node "$candidate"; then export PATH="$(dirname "$candidate"):$PATH"; break; fi
  done
fi
compatible_node "$(command -v node)" || { echo 'Node >=22.12 is required.' >&2; exit 1; }
if [[ -z "${CHROME_BIN:-}" ]]; then
  for candidate in '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' google-chrome chromium chromium-browser; do
    if command -v "$candidate" >/dev/null 2>&1; then CHROME_BIN="$(command -v "$candidate")"; break; fi
  done
fi
[[ -x "${CHROME_BIN:-}" ]] || { echo 'Set CHROME_BIN to Chrome/Chromium executable.' >&2; exit 1; }
[[ -d node_modules/vite ]] || { echo 'Run npm ci first.' >&2; exit 1; }
VITE_PORT="${HONGGUO_UI_PORT:-5191}"
CDP_PORT="${HONGGUO_CDP_PORT:-9223}"
export HONGGUO_UI_URL="http://127.0.0.1:$VITE_PORT/"
export HONGGUO_CDP_URL="http://127.0.0.1:$CDP_PORT"
export HONGGUO_UI_OUTPUT="${HONGGUO_UI_OUTPUT:-$ROOT/verification/android-playback}"
mkdir -p "$HONGGUO_UI_OUTPUT"
# Fail before starting if either port belongs to another process.
node --input-type=module - "$VITE_PORT" "$CDP_PORT" <<'JS'
import { createServer } from 'node:net';
for (const port of process.argv.slice(2).map(Number)) {
  await new Promise((resolve, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => server.close(resolve));
  });
}
JS
PROFILE="$(mktemp -d "${TMPDIR:-/tmp}/hongguo-ui-qa.XXXXXX")"
VITE_PID=''; CHROME_PID=''
cleanup() {
  [[ -z "$VITE_PID" ]] || kill "$VITE_PID" 2>/dev/null || true
  [[ -z "$CHROME_PID" ]] || kill "$CHROME_PID" 2>/dev/null || true
  [[ -z "$VITE_PID" ]] || wait "$VITE_PID" 2>/dev/null || true
  [[ -z "$CHROME_PID" ]] || wait "$CHROME_PID" 2>/dev/null || true
  rm -rf "$PROFILE"
}
trap cleanup EXIT
node node_modules/vite/bin/vite.js --config vite.android.config.mjs --host 127.0.0.1 --port "$VITE_PORT" --strictPort >"$HONGGUO_UI_OUTPUT/ui-vite.log" 2>&1 & VITE_PID=$!
"$CHROME_BIN" --headless=new --remote-debugging-port="$CDP_PORT" --user-data-dir="$PROFILE" --no-first-run --disable-background-networking about:blank >"$HONGGUO_UI_OUTPUT/ui-chrome.log" 2>&1 & CHROME_PID=$!
node --input-type=module - "$HONGGUO_UI_URL" "$HONGGUO_CDP_URL/json/version" <<'JS'
for (const url of process.argv.slice(2)) {
  let ready = false;
  for (let n = 0; n < 100; n++) {
    try { if ((await fetch(url)).ok) { ready = true; break; } } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  if (!ready) throw new Error(`QA server did not start: ${url}`);
}
JS
# Both processes must be this runner's, rather than an unrelated service on a busy port.
kill -0 "$VITE_PID" && kill -0 "$CHROME_PID"
node scripts/check-android-playback-ui.mjs "$@"
