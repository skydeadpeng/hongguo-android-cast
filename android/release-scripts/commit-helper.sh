#!/bin/bash
# Two commits on codex/android-0.12.8, prepared and emulator-verified 2026-11-07.
set -e
cd /tmp/hgandroid
git add package.json
git commit -m "fix(android): restore core-js so the phone ui build runs from this branch

src/android/startup/bootstrap.js still imports core-js polyfills for old TV
WebViews, but the desktop bundle-slim pass dropped it from package.json while
package-lock kept 3.51.0. npm ci then installed a tree without it and
build-android-mac-source.mjs failed to resolve the imports. Declare it again
(the lockfile already pins 3.51.0); anime4k-webgpu and electron-updater stay
desktop-only."
git add android/app/src/main/java/io/github/jackotom/hongguo/android/MainActivity.java src/android/App.jsx
git commit -m "fix(android): reopen the last browsing session after background process death

Root-back minimize (0.12.27) relies on the process surviving in the
background; heavier builds now get killed by aggressive ROMs and reopening
landed on the discover tab, so minimize looked broken. MainActivity marks a
system recreation (savedInstanceState != null) by appending restore=1 to the
startup URL, and App.jsx persists the active tab plus the open series to
localStorage, reopening them only on that restore signal. Fresh launches and
task removal still start at discover."
git log --oneline -4
