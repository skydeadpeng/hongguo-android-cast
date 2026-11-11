#!/bin/bash
# Four feed commits for 0.12.42, mirroring the 0.12.41 release pattern, then push.
set -e
cd /tmp/hgandroid
git add android/update-phone-0.12.42.json
git commit -m "release(android): 0.12.42 phone versioned feed"
git add android/update-phone.json
git commit -m "release(android): 0.12.42 phone update feed"
git add android/update-tv-0.12.42.json
git commit -m "release(android): 0.12.42 tv versioned feed"
git add android/update-tv.json
git commit -m "release(android): 0.12.42 tv update feed"
git push publish codex/android-0.12.8
git log --oneline -5
