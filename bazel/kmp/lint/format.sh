#!/usr/bin/env bash
# `bazel run //:format`: Gazelle writes every module's BUILD file (as `bazel run //:gazelle`), then ktlint -F
# formats the sources the `_ktlint` tests check, what `./gradlew lintFormat` runs ktlint for. Build scripts,
# Bazel's own tools and generated or golden files are left alone.
set -euo pipefail
gazelle="$(pwd)/$1"
ktlint="$(pwd)/$2"
cd "$BUILD_WORKSPACE_DIRECTORY"
"$gazelle"
exec "$ktlint" -F "**/src/*/kotlin/**/*.kt" "!**/build/**" "!bazel-*/**" "!gradle/**" "!bazel/kmp/sqldelight/**" \
    "!bazel/kmp/constants/**" "!bazel/gazelle/**" "!bazel/kmp/resolver/**"
