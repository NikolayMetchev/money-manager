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

# The emulator the device tests boot (`@android_emulator`, extensions.bzl) and the system image it runs: the stable
# emulator's archive per "<os>_<cpu of the host>", and the `target_sdk` level's headless `aosp_atd` image per ABI. Paths
# are under https://dl.google.com/android/repository/. repo-scripts repin android-sdk (run by repin.sh) moves both to the
# newest stable release; no version is named in MODULE.bazel.
ANDROID_EMULATOR = {
    "linux_x64": ["emulator-linux_x64-16970787.zip", "a4405349d8a9c52136ab7861e5331dc40e85574bd91cb8889a298a2ff4c52e26"],
    "osx_aarch64": ["emulator-darwin_aarch64-16970787.zip", "9824ef77c4319fe123e6446d9a8b88e49c51eea458ef85986f7fc309499ee3ac"],
    "osx_x64": ["emulator-darwin_x64-16970787.zip", "fd9bcc7b35946e86f85d6b989db71d05830e0b97692c89a29da426225cc97395"],
    "windows_x64": ["emulator-windows_x64-16970787.zip", "d069eab462ea1da4a18e21b0887c4f95ea240ffba26a1760db056a6f1b0c0338"],
}

ANDROID_SYSTEM_IMAGE = {
    "api": "36",
    "tag": "aosp_atd",
    "arm64-v8a": ["sys-img/aosp_atd/arm64-v8a-36_r01.zip", "ad73836c93e0669b9d3582f4013c5a9ed9a44364b7c64ba7d8f9794ec3c63647"],
    "x86_64": ["sys-img/aosp_atd/x86_64-36_r01.zip", "6fa5785398b19c0b55efdc154f17f9f0e4fa07330bbf2052e21baeb881e7697d"],
}
