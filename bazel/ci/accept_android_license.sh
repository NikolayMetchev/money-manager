#!/bin/sh
# CI's acceptance of the Android SDK License, which a person gives with repo-scripts' accept-android-license: the copy of
# the text bazel/kmp/android/extensions.bzl looks for. Run from the repository's root.
set -eu
mkdir -p "$HOME/.android"
cp bazel/kmp/android/sdk_license.txt "$HOME/.android/money-manager-sdk-license.txt"
