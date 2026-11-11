#!/bin/bash
# Compatibility entry point: retired self-hosted account tests are replaced by
# official login/session/coordinator tests. No real SMS/cloud operation is run.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
if [ "$#" -ne 0 ]; then echo 'USAGE run-account-sync-tests.sh (official account tests)' >&2; exit 2; fi
bash "$ROOT/android/tests/run-official-account-flow-tests.sh"
bash "$ROOT/android/tests/run-official-account-coordinator-tests.sh"
echo 'OFFICIAL_ACCOUNT_GATE_OK legacySelfHosted=removed realSms=not_run networkRequests=0'
