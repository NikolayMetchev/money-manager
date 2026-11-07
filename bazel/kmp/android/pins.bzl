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
    "linux": ["platform-tools_r37.0.1-linux.zip", "d230f13842f60f782a8645f9c813f8f845bf36089ea7289f28c48f17979313f1"],
    "osx": ["platform-tools_r37.0.1-darwin.zip", "ee39ad5967e95c2a07f04dbcbde96b1a0c916ba376096db5d2f498b7727a5d1d"],
    "windows": ["platform-tools_r37.0.1-win.zip", "45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99"],
}
