#!/usr/bin/env bash
# Bazel's workspace status command (.bazelrc): the commit a stamped build (--stamp) records, such as the release
# APK's VCS info. Nothing unstamped reads it.
echo "STABLE_GIT_COMMIT $(git rev-parse HEAD)"
