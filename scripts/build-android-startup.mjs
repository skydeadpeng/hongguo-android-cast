import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

await build({
  entryPoints: [fileURLToPath(new URL('../src/android/startup/bootstrap.js', import.meta.url))],
  outfile: fileURLToPath(new URL('../src/android/public/generated/startup-bootstrap.js', import.meta.url)),
  bundle: true, format: 'iife', platform: 'browser', target: 'es5',
  legalComments: 'inline', sourcemap: false, minify: true,
});
console.log('ANDROID_STARTUP_BUILT classic=ES5 polyfills=globalThis,flat,flatMap,matchAll');
