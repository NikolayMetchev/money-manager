#!/usr/bin/env bash
# Builds the debug APK (//app/main/android:debug, AGP's assembleDebug), installs it on the connected device or
# emulator and starts it. ASwB can't launch it itself: it only recognises rules_android targets, and the APK comes
# from our own android_apk rule. With several devices attached, set ANDROID_SERIAL. Bazel runs with your own
# configs (user.bazelrc), so RBE, the cache or rbe-mac apply as they do to any build.
set -euo pipefail

cd "$(dirname "$0")/../.."
target=//app/main/android:debug

adb_target=@android_platform_tools//:adb

bazel build "$target" "$adb_target"
adb=$(bazel info execution_root)/$(bazel cquery --output=files "$adb_target" 2>/dev/null)
apk=$(bazel cquery --output=files "$target" 2>/dev/null | grep '\.apk$' | head -1)
echo "Installing $apk"
"$adb" install -r "$apk"
"$adb" shell am start -n com.moneymanager/com.moneymanager.android.MainActivity
