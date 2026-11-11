#!/bin/bash
# Commit the 0.12.42 version bump on codex/android-0.12.8.
set -e
cd /tmp/hgandroid
git add android/app/build.gradle
git commit -m "release(android): bump 0.12.42/versionCode 65"
git log --oneline -1
