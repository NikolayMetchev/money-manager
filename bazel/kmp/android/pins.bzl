"""aapt2, as AGP downloads it: `com.android.tools.build:aapt2:<AGP>-<build>:<os>` from Google's Maven.

AGP names the build in `com/android/build/gradle/internal/res/aapt2_version.properties` (its plugin jar).
bazel/renovate/repin_aapt2.py (run by repin.sh) rewrites both pins after an AGP bump.
"""

AAPT2_BUILD = "16666295"

AAPT2_SHA256 = {
    "linux": "fa1014cb1431622d292ef55ba870e75882ea19987bec541698b912f446a2d08f",
    "osx": "a43df4c45549e8a877a8d61b130ee255669eec8a36064f2fc8cd58206457f2a3",
    "windows": "0cca28660dd8f8ac75c28023c14e929392e6d094ddac1564945d6ceca8fc6df8",
}
