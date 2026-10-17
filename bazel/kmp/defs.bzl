"""Kotlin Multiplatform rules (kmp_library, kmp_test). Bazel has no KMP support, so we write our own (#995).

KGP compiles each platform as one K2 invocation that sees every fragment (source set) in its hierarchy:
`-Xmulti-platform -Xfragments=… -Xfragment-refines=… -Xfragment-sources=…`. That keeps expect and actual in
separate fragments and checks common code as common code. These macros do the same.

The JVM compile is rules_kotlin's `kt_jvm_library`, which gives us its persistent worker, `associates`
(internal access for tests), compiler plugins and strict deps, and which the JetBrains Bazel plugin
understands. rules_kotlin has no fragment support, so `//bazel/third_party/rules_kotlin:fragments.patch`
adds it (`fragment_srcs`, `fragment_refines`). The Kotlin toolchain is `//bazel/kmp:kotlin_toolchain`.
"""

load("@rules_java//java:java_test.bzl", "java_test")
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

def _fragments(chain):
    """Fragment flags for a `[(fragment, srcs), …]` chain, root first, leaving out empty fragments."""
    present = [(fragment, srcs) for fragment, srcs in chain if srcs]
    fragment_srcs = {}
    for fragment, srcs in present:
        for src in srcs:
            fragment_srcs[src] = fragment
    fragment_refines = {
        present[i][0]: present[i - 1][0]
        for i in range(1, len(present))
    }
    return struct(
        srcs = [src for _, srcs in present for src in srcs],
        fragment_srcs = fragment_srcs,
        fragment_refines = fragment_refines,
    )

def _jvm_fragments(prefix, common_srcs, jvm_android_srcs, jvm_srcs):
    return _fragments([
        ("common" + prefix, common_srcs),
        ("jvmAndroid" + prefix, jvm_android_srcs),
        ("jvm" + prefix, jvm_srcs),
    ])

def kmp_library(
        name,
        common_srcs = [],
        jvm_android_srcs = [],
        jvm_srcs = [],
        deps = [],
        exports = [],
        plugins = [],
        visibility = None):
    """A Kotlin Multiplatform module. Emits `:<name>_jvm`.

    Args:
        name: the module name.
        common_srcs: `src/commonMain/kotlin` sources.
        jvm_android_srcs: `src/jvmAndroidMain/kotlin` sources.
        jvm_srcs: `src/jvmMain/kotlin` sources.
        deps: Gradle `implementation` dependencies. A JVM compilation has one classpath, as in KGP.
        exports: Gradle `api` dependencies: on the classpath of everything that depends on this module.
        plugins: compiler plugins (`kt_compiler_plugin`).
        visibility: the targets' visibility.
    """
    fragments = _jvm_fragments("Main", common_srcs, jvm_android_srcs, jvm_srcs)
    kt_jvm_library(
        name = name + "_jvm",
        srcs = fragments.srcs,
        fragment_srcs = fragments.fragment_srcs,
        fragment_refines = fragments.fragment_refines,
        # Gradle's api is on the module's own classpath too; Bazel's exports are only on its dependents'.
        deps = deps + exports,
        exports = exports,
        plugins = plugins,
        visibility = visibility,
    )

def kmp_test(
        name,
        associate,
        common_srcs = [],
        jvm_android_srcs = [],
        jvm_srcs = [],
        deps = [],
        plugins = [],
        size = "small"):
    """A module's tests. Emits `:<name>_jvm` (JUnit 4, run by `//bazel/kmp/testing:KmpJUnitRunner`).

    Args:
        name: the test name.
        associate: the `kmp_library` under test; the tests see its `internal` declarations.
        common_srcs: `src/commonTest/kotlin` sources.
        jvm_android_srcs: `src/jvmAndroidTest/kotlin` sources.
        jvm_srcs: `src/jvmTest/kotlin` sources.
        deps: test dependencies (Gradle `implementation` on the test source sets).
        plugins: compiler plugins (`kt_compiler_plugin`).
        size: the Bazel test size. Unit tests are small; give slower suites a bigger size.
    """
    fragments = _jvm_fragments("Test", common_srcs, jvm_android_srcs, jvm_srcs)
    lib = name + "_jvm_lib"
    kt_jvm_library(
        name = lib,
        testonly = True,
        srcs = fragments.srcs,
        fragment_srcs = fragments.fragment_srcs,
        fragment_refines = fragments.fragment_refines,
        associates = [associate + "_jvm"],
        deps = deps,
        plugins = plugins,
    )
    java_test(
        name = name + "_jvm",
        size = size,
        main_class = "com.moneymanager.bazel.kmp.testing.KmpJUnitRunner",
        use_testrunner = False,
        # The runner finds this jar on its classpath by file name.
        jvm_flags = ["-Dkmp.test.jar=%s.jar" % lib],
        runtime_deps = [
            ":" + lib,
            Label("//bazel/kmp/testing:runner"),
        ],
    )
