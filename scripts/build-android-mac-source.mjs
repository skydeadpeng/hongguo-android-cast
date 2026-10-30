import { build } from 'esbuild';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import './build-android-startup.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const adapter = path.join(root, 'src/android/mac-source');
const output = path.join(root, 'src/android/generated/mac-app-source.mjs');
await build({
  entryPoints: [path.join(adapter, 'entry.mjs')], outfile: output,
  bundle: true, format: 'esm', platform: 'browser', target: 'chrome67',
  inject: [path.join(adapter, 'buffer-runtime.mjs')],
  alias: { 'node:crypto': path.join(adapter, 'crypto-shim.mjs'), axios: path.join(adapter, 'axios-common.cjs') },
  define: { 'AbortSignal.timeout': 'androidAppTimeout' },
  legalComments: 'inline', sourcemap: false,
  banner: { js: '// SPDX-License-Identifier: GPL-3.0-only\n// Generated Android adapter for shared src/native/app-source.js, sm3.js and download-quality.js.\n// Protocol attribution: src/native/licenses/NOTICE.txt and LICENSE.drpys.txt.' },
});
console.log('ANDROID_MAC_SOURCE_BUILT src/android/generated/mac-app-source.mjs');
