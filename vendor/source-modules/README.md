# Desktop source module bundle

Pinned, unmodified GPL-3.0 source modules from [guoapp-modules](https://github.com/guoapp-modules), read on 2026-11-19. `index.json` records each release tag, classification and upstream SHA-256 of `manifest.json` and `main.js`. All 15 indexed modules are pinned. Entries marked `adult: true` are disabled by default and enabled individually on desktop.

The desktop uses its existing red-fruit provider and player for `hongguo`; the bundled module is retained as pinned reference material, not as a replacement playback path. Other reviewed entries use the local synchronous host compatibility layer. Module files are source code, not site content or media.

To refresh the reviewed sources, run `node scripts/sync-source-modules.cjs` from the repository root. This downloads only GitHub metadata/code, verifies both file hashes and classification before replacement, and never enables a source. Review the resulting diff before release.

Full license: [LICENSE](LICENSE). Upstream: [index](https://github.com/guoapp-modules/index), [runtime documentation](https://github.com/Xanerix/guoapp/blob/51f39b58bce6b4c9d464e382e86e5f79aa2e9d2b/DEVELOPMENT.md).
