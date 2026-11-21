#!/bin/sh
# CI's acceptance of the Android SDK Licenses, which a person gives with repo-scripts' accept-android-license: the copies
# of the texts bazel/kmp/android/extensions.bzl looks for (the SDK's, and the Arm system image's). Run from the repository's root.
set -eu
mkdir -p "$HOME/.android"
cp bazel/kmp/android/sdk_license.txt "$HOME/.android/money-manager-sdk-license.txt"
cp bazel/kmp/android/sdk_arm_license.txt "$HOME/.android/money-manager-sdk-arm-license.txt"
