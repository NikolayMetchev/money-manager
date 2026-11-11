"""What makes a platform optional: Android, Kotlin/Native and the browser build only when their flag is on, and the JVM only unless its is off.

Every target only one of those platforms builds has `target_compatible_with = <PLATFORM>_ONLY`, which is incompatible
unless `//bazel/kmp/platforms:<platform>` is set (`jvm` is set by default). A wildcard (`bazel build //...`, `bazel test //...`) skips an
incompatible target, so by default it builds and tests the JVM alone, and naming one fails with "incompatible" until
the flag is on. `.bazelrc` sets the flags: `--config=android`, `--config=native`, `--config=web` (they combine) and
`--config=all-platforms`; `--config=no-jvm` leaves the JVM out, so `--config=no-jvm --config=android` builds Android alone. CI sets them from a pull request's labels (`bazel/ci/build_and_test.sh`).

Incompatibility spreads to everything that depends on an incompatible target, so only a platform's own roots need it:
the `kmp_*` macros add it to what they emit (Gazelle-written BUILD files need nothing) and a hand-written Android,
Kotlin/Native or browser target adds it itself.
"""

def _only(platform):
    return select({
        Label("//bazel/kmp/platforms:%s_enabled" % platform): [],
        "//conditions:default": ["@platforms//:incompatible"],
    })

JVM_ONLY = _only("jvm")
ANDROID_ONLY = _only("android")
NATIVE_ONLY = _only("native")
WEB_ONLY = _only("web")
