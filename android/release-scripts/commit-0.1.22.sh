#!/bin/zsh
set -e
cd /Users/xiaomingpeng/Desktop/红果
git add android/app/build.gradle android/gradle.properties android/app/src/main/AndroidManifest.xml android/app/src/main/java android/app/src/tvNative
git commit -m "feat(tvnative): Android 6.0 support with built-in software decoder 0.1.22" -m "minSdk 23 + core library desugaring; LegacyText/LegacyFiles shims for APIs desugaring cannot backport (String.join, java.nio.file); app icon moved to drawable-nodpi to stop SystemUI OOM loops on high-density screens; libVLC 3.6.2 software decoder in the tvNative flavor only (VlcSoftLayer via SoftDecoder reflection bridge), auto-engaged when hardware decoding fails (-38/unsupported), per-ABI APK splits with abis manifest support in AppUpdater." && echo "COMMIT1_OK"
git add android/update-tvnative-0.1.22.json android/update-tvnative.json ANDROID.md README.md
git commit -m "release(tvnative): enable online upgrade to 0.1.22" -m "Update manifests carry minSdk 23 and per-ABI apk entries (arm64-v8a / armeabi-v7a); ANDROID.md and README record the Android 6.0 support boundary and the built-in software decoder." && echo "COMMIT2_OK"
git log --oneline -3
