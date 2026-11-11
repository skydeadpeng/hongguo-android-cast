import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';
import { cpSync } from 'node:fs';

/** 桌面版安卓 UI 构建：与 vite.android.config.mjs 同源，产物进 dist-react-android/。 */
export default defineConfig({
  root: fileURLToPath(new URL('./src/android/', import.meta.url)),
  base: './',
  plugins: [react(), {
    name: 'android-startup-copy',
    closeBundle() {
      // 安卓 UI 的 index.html 引用 /generated/startup-bootstrap.js（ES5 启动面板），一并拷进产物。
      cpSync(
        fileURLToPath(new URL('./src/android/public/', import.meta.url)),
        fileURLToPath(new URL('./dist-react-android/', import.meta.url)),
        { recursive: true },
      );
    },
  }],
  build: {
    outDir: fileURLToPath(new URL('./dist-react-android/', import.meta.url)),
    emptyOutDir: true,
    target: 'chrome120',
    sourcemap: false,
  },
});
