import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  root: fileURLToPath(new URL('./src/android/', import.meta.url)),
  base: './',
  plugins: [react(), {
    name: 'android-checked-startup',
    // Vite still builds the real module graph, but the classic ES5 bootstrap
    // alone decides whether this WebView is capable of executing that graph.
    transformIndexHtml: {
      order: 'post',
      handler(html) {
        const modules = [];
        html = html.replace(/<script\b(?=[^>]*\btype="module")(?=[^>]*\bsrc="([^"]+)")[^>]*>\s*<\/script>/g, (tag, src) => { if (src.includes('/@vite/client')) return tag; modules.push(src); return ''; });
        if (modules.length !== 1) throw new Error('Android startup requires exactly one application module');
        return html.replace('data-hongguo-boot', `data-hongguo-boot data-app-src="${modules[0]}"`);
      },
    },
  }],
  build: {
    outDir: fileURLToPath(new URL('./android/app/src/main/assets/web/', import.meta.url)),
    emptyOutDir: true,
    target: 'chrome67',
    sourcemap: false,
  },
});
