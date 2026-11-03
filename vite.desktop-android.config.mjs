import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

/** 桌面版安卓 UI 构建：与 vite.android.config.mjs 同源，产物进 dist-react-android/。 */
export default defineConfig({
  root: fileURLToPath(new URL('./src/android/', import.meta.url)),
  base: './',
  plugins: [react()],
  build: {
    outDir: fileURLToPath(new URL('./dist-react-android/', import.meta.url)),
    emptyOutDir: true,
    target: 'chrome120',
    sourcemap: false,
  },
});
