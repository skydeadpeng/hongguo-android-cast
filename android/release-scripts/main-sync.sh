#!/bin/bash
# The device feed URL points at the publish repo's main branch; fast-forward it
# to the release branch head so 0.12.42 becomes visible to update checks.
set -e
cd /tmp/hgandroid
git push publish codex/android-0.12.8:main
