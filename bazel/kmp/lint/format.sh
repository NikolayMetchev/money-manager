#!/usr/bin/env bash
# `bazel run //:format`: ktlint -F over the sources the `_ktlint` tests check, what `./gradlew lintFormat`
# runs ktlint for. Build scripts, Bazel's own tools and generated or golden files are left alone.
set -euo pipefail
ktlint="$(pwd)/$1"
cd "$BUILD_WORKSPACE_DIRECTORY"
exec "$ktlint" -F "**/src/*/kotlin/**/*.kt" "!**/build/**" "!bazel-*/**" "!gradle/**" "!bazel/kmp/sqldelight/**" \
    "!bazel/kmp/constants/**" "!bazel/gazelle/**" "!bazel/kmp/resolver/**"
