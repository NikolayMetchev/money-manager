#!/usr/bin/env bash
# Fails when SQLDelight generates different code for the fixture than golden/ holds: its compiler API is
# internal, so a version bump has to be checked here, not discovered in app/db (#1003). After checking the
# new output is right, refresh golden/ with: bazel run //bazel/kmp/sqldelight/tests:update_golden
set -euo pipefail
if ! diff -r "$1" "$2"; then
  echo "SQLDelight's output changed; see bazel/kmp/sqldelight/tests/golden_test.sh" >&2
  exit 1
fi
