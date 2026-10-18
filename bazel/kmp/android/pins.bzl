"""aapt2, as AGP downloads it: `com.android.tools.build:aapt2:<AGP>-<build>:<os>` from Google's Maven.

AGP names the build in `com/android/build/gradle/internal/res/aapt2_version.properties` (its plugin jar). After an
AGP bump, set `AAPT2_BUILD` from that file and the checksums from the new jars (the download fails on a stale checksum).
"""

AAPT2_BUILD = "15978811"

AAPT2_SHA256 = {
    "linux": "f5bebd466ecf14d341fd465f2756a16d86052f29eb4532003d5ff7bcffd08de5",
    "osx": "eb93ce9d0fa121333f12395b3ab30822c5cdb9155737f20d342fe71809757059",
    "windows": "5fe3c8ee5c6b3f47efd1fced1d6418084037f54f13290816c5db717e6b5c92aa",
}
