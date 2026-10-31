#!/usr/bin/env bash
# Builds the debug APK (//app/main/android:debug, AGP's assembleDebug), installs it on the connected device or
# emulator and starts it. ASwB can't launch it itself: it only recognises rules_android targets, and the APK comes
# from our own android_apk rule. With several devices attached, set ANDROID_SERIAL. Bazel runs with your own
# configs (user.bazelrc), so RBE, the cache or rbe-mac apply as they do to any build.
set -euo pipefail

cd "$(dirname "$0")/../.."
target=//app/main/android:debug

# The SDK the build uses, found the way AGP and bazel/kmp/android/extensions.bzl find it.
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $sdk && -f local.properties ]]; then
  sdk=$(sed -n 's/^sdk\.dir[[:space:]]*=[[:space:]]*//p' local.properties | tail -1)
fi
adb=${sdk:-$HOME/Android/Sdk}/platform-tools/adb

bazel build "$target"
apk=$(bazel cquery --output=files "$target" 2>/dev/null | grep '\.apk$' | head -1)
echo "Installing $apk"
"$adb" install -r "$apk"
"$adb" shell am start -n com.moneymanager/com.moneymanager.android.MainActivity
