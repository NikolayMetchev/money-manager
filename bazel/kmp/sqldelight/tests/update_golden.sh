#!/usr/bin/env bash
set -euo pipefail
generated="$(pwd)/$1"
golden="$BUILD_WORKSPACE_DIRECTORY/bazel/kmp/sqldelight/tests/golden"
rm -rf "$golden"
cp -RL "$generated" "$golden"
chmod -R u+w "$golden"
