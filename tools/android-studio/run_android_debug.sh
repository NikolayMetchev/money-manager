#!/usr/bin/env bash
# Builds the debug APK (//app/main/android:debug, AGP's assembleDebug), installs it on the connected device or
# emulator and starts it. ASwB can't launch it itself: it only recognises rules_android targets, and the APK comes
# from our own android_apk rule. With several devices attached, set ANDROID_SERIAL.
set -euo pipefail

cd "$(dirname "$0")/../.."
target=//app/main/android:debug
adb=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb

bazel build --config=rbe "$target"
apk=$(bazel cquery --config=rbe --output=files "$target" 2>/dev/null | grep '\.apk$' | head -1)
echo "Installing $apk"
"$adb" install -r "$apk"
"$adb" shell am start -n com.moneymanager/com.moneymanager.android.MainActivity
