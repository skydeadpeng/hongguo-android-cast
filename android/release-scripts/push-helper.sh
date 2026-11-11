#!/bin/bash
# Push the emulator-verified session-restore fix to its home branch on GitHub.
set -e
cd /tmp/hgandroid
git push publish codex/android-0.12.8
