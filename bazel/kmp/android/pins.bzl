"""aapt2, as AGP downloads it: `com.android.tools.build:aapt2:<AGP>-<build>:<os>` from Google's Maven.

AGP names the build in `com/android/build/gradle/internal/res/aapt2_version.properties` (its plugin jar).
repo-scripts repin aapt2 (run by repin.sh) rewrites both pins after an AGP bump.
"""

AAPT2_BUILD = "16666295"

AAPT2_SHA256 = {
    "linux": "fa1014cb1431622d292ef55ba870e75882ea19987bec541698b912f446a2d08f",
    "osx": "a43df4c45549e8a877a8d61b130ee255669eec8a36064f2fc8cd58206457f2a3",
    "windows": "0cca28660dd8f8ac75c28023c14e929392e6d094ddac1564945d6ceca8fc6df8",
}

# The SDK repository's archives extensions.bzl downloads, under https://dl.google.com/android/repository/.
# Their versions are MODULE.bazel's android_sdk.platform and android_sdk.platform_tools, which Renovate bumps;
# repo-scripts repin android-sdk (run by repin.sh) rewrites these, and sdk_license.txt, from its index.
ANDROID_PLATFORM = {
    "compile_sdk": 37,
    "dir": "android-37.0",
    "file": "platform-37.0_r02.zip",
    "sha256": "840b23e827f96e64aea4c89a1194aac3dc5f6bad37edb231c5c795d890330e8d",
}

PLATFORM_TOOLS = {
    "linux": ["platform-tools_r37.0.3-linux.zip", "214548146cadb2ca3508143d8a1d4be4c59de5bb837253e3ede89ed74cf450d1"],
    "osx": ["platform-tools_r37.0.3-darwin.zip", "df21339b9371fef9b19264404338dc686f59a88a1dce634b963da344909001d6"],
    "windows": ["platform-tools_r37.0.3-win.zip", "89d108360a3bf2924d3bea837239edaccc22fd6ff5befd3787c4348a9fe4f09d"],
}
