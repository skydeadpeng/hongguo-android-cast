#!/bin/zsh
set -e
cd /Users/xiaomingpeng/Desktop/红果
git add android/app/src/main/java/io/github/jackotom/hongguo/android/PlayerOptions.java android/app/src/main/java/io/github/jackotom/hongguo/android/PlayerActivity.java android/app/src/main/java/io/github/jackotom/hongguo/android/TvNativeActivity.java android/app/build.gradle
git commit -m "feat(tvnative): decoder mode setting 0.1.23" -m "Settings > playback gains 解码方式: auto (hardware first, software decoder on device decode errors), hardware-only, or software-first (online playback engages libVLC directly). Stored in PlayerOptions as decoder=auto|hw|sw. Quality/speed controls disable while the software layer is active to avoid dual playback." && echo "COMMIT_OK"
git add android/update-tvnative-0.1.23.json android/update-tvnative.json ANDROID.md README.md
git commit -m "release(tvnative): enable online upgrade to 0.1.23" && echo "RELEASE_OK"
git log --oneline -3
