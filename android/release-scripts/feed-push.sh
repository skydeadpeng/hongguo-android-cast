#!/bin/bash
# Push the release branch (bump included) so the tag targets the right tree.
set -e
cd /tmp/hgandroid
git push publish codex/android-0.12.8
git log --oneline -1 origin/codex/android-0.12.8 2>/dev/null || git log --oneline -1
