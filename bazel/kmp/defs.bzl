"""Kotlin Multiplatform rules (kmp_library, kmp_test). Bazel has no KMP support, so we write our own (#995).

KGP compiles each platform as one K2 invocation that sees every fragment (source set) in its hierarchy:
`-Xmulti-platform -Xfragments=… -Xfragment-refines=… -Xfragment-sources=…`. That keeps expect and actual in
separate fragments and checks common code as common code. These macros do the same.

A module emits one target per platform: `:<name>_jvm`, and `:<name>_<target>` (`_linuxX64`, …) for each of
its `native_targets`. Dependencies are named once, by their module label (`//utils/files:files`), and each
platform's compile gets that label's platform target (`//utils/files:files_jvm`, `…:files_linuxX64`).
Maven libraries in `//bazel/kmp/deps` follow the same naming.

The JVM compile is rules_kotlin's `kt_jvm_library`, which gives us its persistent worker, `associates`
(internal access for tests), compiler plugins and strict deps, and which the JetBrains Bazel plugin
understands. rules_kotlin has no fragment support, so `//bazel/third_party/rules_kotlin:fragments.patch`
adds it (`fragment_srcs`, `fragment_refines`). The Kotlin toolchain is `//bazel/kmp:kotlin_toolchain`.
The native compile is `native.bzl`, on the toolchain in `//bazel/kmp/native`.
"""

load("@rules_java//java:java_test.bzl", "java_test")
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")
load(":native.bzl", "BUILDS_ON", "FAMILIES", "RUNS_ON", "kt_native_klib", "kt_native_test_binary")

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

def platform_label(dep, platform):
    """`//pkg:name` → `//pkg:name_<platform>` (also for `//pkg` and `:name`)."""
    package, sep, target = dep.rpartition(":")
    if not sep:
        package = dep
        target = dep.rpartition("/")[2]
    return "%s:%s_%s" % (package, target, platform)

def _on(platform, labels):
    return [platform_label(dep, platform) for dep in labels]

def _family_srcs(target, linux_srcs, apple_srcs, mingw_srcs):
    return {"apple": apple_srcs, "linux": linux_srcs, "mingw": mingw_srcs}[FAMILIES[target]]

def kmp_library(
        name,
        common_srcs = [],
        jvm_android_srcs = [],
        jvm_srcs = [],
        native_srcs = [],
        linux_srcs = [],
        apple_srcs = [],
        mingw_srcs = [],
        native_targets = [],
        deps = [],
        jvm_deps = [],
        native_deps = [],
        exports = [],
        jvm_exports = [],
        plugins = [],
        visibility = None):
    """A Kotlin Multiplatform module. Emits `:<name>_jvm` and `:<name>_<target>` per native target.

    Args:
        name: the module name.
        common_srcs: `src/commonMain/kotlin` sources.
        jvm_android_srcs: `src/jvmAndroidMain/kotlin` sources.
        jvm_srcs: `src/jvmMain/kotlin` sources.
        native_srcs: `src/nativeMain/kotlin` sources.
        linux_srcs: `src/linuxMain/kotlin` sources.
        apple_srcs: `src/appleMain/kotlin` sources.
        mingw_srcs: `src/mingwMain/kotlin` sources.
        native_targets: the Kotlin/Native targets, by KGP's names (`linuxX64`, …).
        deps: `commonMain` `implementation` dependencies, by module label. A compilation has one
            classpath, as in KGP.
        jvm_deps: `jvmAndroidMain`/`jvmMain` dependencies.
        native_deps: `nativeMain` dependencies.
        exports: `commonMain` `api` dependencies: on the classpath of everything that depends on this module.
        jvm_exports: `jvmAndroidMain`/`jvmMain` `api` dependencies.
        plugins: compiler plugins (`kt_compiler_plugin`).
        visibility: the targets' visibility.
    """
    fragments = _jvm_fragments("Main", common_srcs, jvm_android_srcs, jvm_srcs)
    jvm_exported = _on("jvm", exports + jvm_exports)
    kt_jvm_library(
        name = name + "_jvm",
        srcs = fragments.srcs,
        fragment_srcs = fragments.fragment_srcs,
        fragment_refines = fragments.fragment_refines,
        # Gradle's api is on the module's own classpath too; Bazel's exports are only on its dependents'.
        deps = _on("jvm", deps + jvm_deps) + jvm_exported,
        exports = jvm_exported,
        plugins = plugins,
        visibility = visibility,
    )
    for target in native_targets:
        # A klib's dependencies are always transitive on native, so api and implementation are the same.
        kt_native_klib(
            name = name + "_" + target,
            module = name,
            target = target,
            common_srcs = common_srcs,
            native_srcs = native_srcs,
            family_srcs = _family_srcs(target, linux_srcs, apple_srcs, mingw_srcs),
            deps = _on(target, deps + native_deps + exports),
            target_compatible_with = BUILDS_ON.get(target, []),
            visibility = visibility,
        )

def kmp_test(
        name,
        associate,
        common_srcs = [],
        jvm_android_srcs = [],
        jvm_srcs = [],
        native_srcs = [],
        linux_srcs = [],
        apple_srcs = [],
        mingw_srcs = [],
        native_targets = [],
        deps = [],
        jvm_deps = [],
        native_deps = [],
        plugins = [],
        size = "small"):
    """A module's tests. Emits `:<name>_jvm` and `:<name>_<target>` per native target.

    JVM tests are JUnit 4, run by `//bazel/kmp/testing:KmpJUnitRunner`. Native tests are linked with konan's
    test runner and run by `//bazel/kmp/testing:native_launcher`; a target's tests only run where its
    executables do (`RUNS_ON`), so on Linux x64 only `_linuxX64` runs.

    Args:
        name: the test name.
        associate: the `kmp_library` under test, by its module label; the tests see its `internal` declarations.
        common_srcs: `src/commonTest/kotlin` sources.
        jvm_android_srcs: `src/jvmAndroidTest/kotlin` sources.
        jvm_srcs: `src/jvmTest/kotlin` sources.
        native_srcs: `src/nativeTest/kotlin` sources.
        linux_srcs: `src/linuxTest/kotlin` sources.
        apple_srcs: `src/appleTest/kotlin` sources.
        mingw_srcs: `src/mingwTest/kotlin` sources.
        native_targets: the Kotlin/Native targets to test on, by KGP's names.
        deps: test dependencies (Gradle `implementation` on `commonTest`), by module label.
        jvm_deps: `jvmAndroidTest`/`jvmTest` dependencies.
        native_deps: `nativeTest` dependencies.
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
        associates = [platform_label(associate, "jvm")],
        deps = _on("jvm", deps + jvm_deps),
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
    module = associate.rpartition(":")[2] if ":" in associate else associate.rpartition("/")[2]
    for target in native_targets:
        binary = "%s_%s_bin" % (name, target)
        kt_native_test_binary(
            name = binary,
            testonly = True,
            module = module,
            target = target,
            associate = platform_label(associate, target),
            common_srcs = common_srcs,
            native_srcs = native_srcs,
            family_srcs = _family_srcs(target, linux_srcs, apple_srcs, mingw_srcs),
            deps = _on(target, deps + native_deps),
            target_compatible_with = RUNS_ON[target],
        )
        java_test(
            name = "%s_%s" % (name, target),
            size = size,
            main_class = "com.moneymanager.bazel.kmp.testing.KnTestLauncher",
            use_testrunner = False,
            args = ["$(rootpath :%s)" % binary],
            data = [":" + binary],
            target_compatible_with = RUNS_ON[target],
            runtime_deps = [Label("//bazel/kmp/testing:native_launcher_lib")],
        )
