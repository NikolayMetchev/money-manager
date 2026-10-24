"""Kotlin Multiplatform rules (kmp_library, kmp_test). Bazel has no KMP support, so we write our own (#995).

KGP compiles each platform as one K2 invocation that sees every fragment (source set) in its hierarchy:
`-Xmulti-platform -Xfragments=… -Xfragment-refines=… -Xfragment-sources=…`. That keeps expect and actual in
separate fragments and checks common code as common code. These macros do the same.

A module emits one target per platform: `:<name>_jvm`, `:<name>_android` for an Android module, and
`:<name>_<target>` (`_linuxX64`, …) for each of its `native_targets`. A module with native targets also gets `:<name>_metadata`, commonMain compiled on its
own against its dependencies' common metadata, as Gradle's `compileCommonMainKotlinMetadata` (metadata.bzl). Dependencies are named once, by their module label (`//utils/files:files`), and each
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
load("@bazel_skylib//rules:copy_file.bzl", "copy_file")
load("@rules_pkg//pkg:mappings.bzl", "pkg_attributes", "pkg_files")
load("@rules_pkg//pkg:tar.bzl", "pkg_tar")
load(":metadata.bzl", "kt_metadata_library")
load(":module_info.bzl", "kmp_module_info", "module_configurations")
load("@bazel_skylib//lib:structs.bzl", "structs")
load("//bazel/kmp/dokka:dokka.bzl", "dokka_module")
load("//bazel/kmp/checks:checks.bzl", "kmp_forbidden_deps", "kmp_reference_check", "kmp_unused_deps")
load("//bazel/kmp/lint:lint.bzl", "kmp_lint")
load(":resources.bzl", "kmp_resource_jar")
load(":web.bzl", "kt_web_klib", "kt_web_test_program")
load(":native.bzl", "BUILDS_ON", "CROSS_PLATFORMS", "FAMILIES", "RUNS_ON", "konan_static_library", "kt_native_cinterop", "kt_native_cross_test_binary", "kt_native_executable", "kt_native_klib", "kt_native_test_binary")
load("//bazel/kmp/plugins:plugins.bzl", "plugin_deps", "plugin_labels")
load("//bazel/kmp/android:apk.bzl", "android_apk")
load("//bazel/kmp/android:device_test.bzl", "INSTRUMENTATION_RUNNER", "android_device_test")
load("@android_sdk//:versions.bzl", "COMPILE_SDK", "MIN_SDK")

def _fragments(chain):
    """Fragment flags for a `[(fragment, srcs, always), …]` chain, root first.

    KGP names every source set in a compilation's hierarchy, sources or not; K2 takes a lone common fragment
    for the platform's own and rejects `@OptionalExpectation` there. So `commonMain` and the platform's leaf
    are always named; an intermediate source set a module doesn't have (jvmAndroidMain) is left out.
    """
    present = [(fragment, srcs) for fragment, srcs, always in chain if srcs or always]
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

def _jvm_fragments(prefix, common_srcs, jvm_android_srcs, leaf_srcs, leaf = "jvm"):
    return _fragments([
        ("common" + prefix, common_srcs, True),
        ("jvmAndroid" + prefix, jvm_android_srcs, False),
        (leaf + prefix, leaf_srcs, True),
    ])

def platform_label(dep, platform):
    """`//pkg:name` → `//pkg:name_<platform>` (also for `//pkg` and `:name`)."""
    package, sep, target = dep.rpartition(":")
    if not sep:
        package = dep
        target = dep.rpartition("/")[2]
    return "%s:%s_%s" % (package, target, platform)

def _on(platform, labels):
    """Each label's platform target, once: a directive, a plugin and an import may all name one."""
    out = []
    for dep in labels:
        target = platform_label(dep, platform)
        if target not in out:
            out.append(target)
    return out

def _resource_jars(name, kind, resources):
    """`resources` as a jar, the JVM compilation's source sets' `resources` dirs merged, the leaf winning."""
    if not resources:
        return []
    kmp_resource_jar(
        name = name + "_resources",
        srcs = resources,
        # `src/main` is a kotlin("jvm") module's.
        roots = ["src/%s%s/resources" % (fragment, kind) for fragment in ["common", "jvmAndroid", "jvm"]] +
                ["src/%s/resources" % kind.lower()],
        testonly = kind == "Test",
        visibility = ["//visibility:private"],
    )
    return [":" + name + "_resources"]

def _unused_deps(name, target, declared, keep_deps, platform = "jvm"):
    """`:<name>_unused_deps`: what the BUILD file declares, less what directives keep, against the JVM compile
    (an Android app's Android one)."""
    names = []
    for dep in declared:
        if dep not in keep_deps and dep not in names:
            names.append(dep)
    kmp_unused_deps(
        name = name + "_unused_deps",
        target = ":" + target,
        deps = _on(platform, names),
        names = names,
        module = "//" + native.package_name(),
        testonly = True,
    )

def _family(target, linux, apple, mingw):
    """What a target's family source set (linuxMain, appleMain, mingwMain) has: its sources or its deps."""
    return {"apple": apple, "linux": linux, "mingw": mingw}[FAMILIES[target]]

def _forbidden_deps(name, forbidden, jvm, natives, compatible):
    """`jvm` holds the JVM-bytecode compiles (`jvm`, `android`), `natives` the native ones, by platform."""
    module = "//" + native.package_name()
    for platform, target in jvm.items() + natives.items():
        kmp_forbidden_deps(
            name = "%s_forbidden_deps_%s" % (name, platform),
            module = module,
            forbidden = forbidden,
            targets = [":" + target],
            testonly = True,
            target_compatible_with = compatible.get(platform, []),
        )

def _module_name(native_targets, jvm):
    """Gradle's Kotlin module name, which internal members' JVM names and Compose's lambda names carry: KGP's
    `<group>_<archivesName>` (the convention's group is the dotted path; the native convention names the archive
    after the dashed path, the rest keep the directory's name), but a `com.android.application` module's own name."""
    package = native.package_name()
    base = package.split("/")[-1]
    if not jvm:
        return base
    return "%s_%s" % (package.replace("/", "."), package.replace("/", "-") if native_targets else base)

def kmp_library(
        name,
        common_srcs = [],
        jvm_android_srcs = [],
        jvm_srcs = [],
        android_srcs = [],
        native_srcs = [],
        linux_srcs = [],
        apple_srcs = [],
        mingw_srcs = [],
        non_jvm_srcs = [],
        web_srcs = [],
        wasm_js_srcs = [],
        js_srcs = [],
        android = False,
        jvm = True,
        native_targets = [],
        web_targets = [],
        deps = [],
        jvm_android_deps = [],
        jvm_deps = [],
        android_deps = [],
        native_deps = [],
        linux_deps = [],
        apple_deps = [],
        mingw_deps = [],
        non_jvm_deps = [],
        web_deps = [],
        wasm_js_deps = [],
        js_deps = [],
        metadata_deps = [],
        exports = [],
        jvm_android_exports = [],
        jvm_exports = [],
        android_exports = [],
        plugins = [],
        sqldelight = None,
        generated_srcs = [],
        cinterops = [],
        forbidden_deps = [],
        forbidden_reference = None,
        forbidden_reference_reason = "",
        forbidden_reference_allowed = [],
        keep_deps = [],
        resources = [],
        tags = [],
        visibility = None):
    """A Kotlin Multiplatform module. Emits `:<name>_jvm`, `:<name>_android` and `:<name>_<target>` per native and
    browser target.

    The Android compile is AGP's Kotlin compile of an Android library:
    `commonMain ← jvmAndroidMain ← androidMain` against the SDK's `android.jar` (`//bazel/kmp/android`), on the
    same Kotlin toolchain (and `jvm_target`) as the JVM. Its resources,
    manifest and dexing are the app's (#1023).

    Args:
        name: the module name.
        common_srcs: `src/commonMain/kotlin` sources.
        jvm_android_srcs: `src/jvmAndroidMain/kotlin` sources.
        jvm_srcs: `src/jvmMain/kotlin` sources.
        android_srcs: `src/androidMain/kotlin` sources.
        native_srcs: `src/nativeMain/kotlin` sources.
        linux_srcs: `src/linuxMain/kotlin` sources.
        apple_srcs: `src/appleMain/kotlin` sources.
        mingw_srcs: `src/mingwMain/kotlin` sources.
        non_jvm_srcs: `src/nonJvmMain/kotlin` sources: what the native and browser targets share.
        web_srcs: `src/webMain/kotlin` sources: what wasmJs and js share.
        wasm_js_srcs: `src/wasmJsMain/kotlin` sources.
        js_srcs: `src/jsMain/kotlin` sources.
        android: whether the module has an Android target (`:<name>_android`).
        jvm: whether it has a JVM one: an Android app (`com.android.application`) has only `:<name>_android`.
            The JVM and Android compiles take Gradle's Kotlin module name (`_module_name`), which a
            `com.android.application` module names differently.
        native_targets: the Kotlin/Native targets, by KGP's names (`linuxX64`, …).
        web_targets: the browser targets (`wasmJs`, `js`; web.bzl). A module with any compiles its commonMain
            metadata against `metadataWeb`, and gets `:<name>_metadataWeb` for the web modules that depend on it.
        deps: `commonMain` `implementation` dependencies, by module label. A compilation has one
            classpath, as in KGP.
        jvm_android_deps: `jvmAndroidMain` dependencies (the JVM's and Android's).
        jvm_deps: `jvmMain` dependencies.
        android_deps: `androidMain` dependencies.
        native_deps: `nativeMain` dependencies.
        linux_deps: `linuxMain` dependencies (only on Linux targets).
        apple_deps: `appleMain` dependencies.
        mingw_deps: `mingwMain` dependencies.
        non_jvm_deps: `nonJvmMain` dependencies (the native and browser targets').
        web_deps: `webMain` dependencies.
        wasm_js_deps: `wasmJsMain` dependencies.
        js_deps: `jsMain` dependencies.
        metadata_deps: what only `:<name>_metadata` adds: a package each target takes from a different library
            (Material 3 is androidx's on Android, `-desktop` on the JVM), so `deps` has none, gets the one the
            common metadata resolves.
        exports: `commonMain` `api` dependencies: on the classpath of everything that depends on this module.
        jvm_android_exports: `jvmAndroidMain` `api` dependencies.
        jvm_exports: `jvmMain` `api` dependencies.
        android_exports: `androidMain` `api` dependencies.
        plugins: compiler plugins by name (`serialization`, `compose`, `metro`, `mappie`; see
            //bazel/kmp/plugins:plugins.bzl), applied on every platform with the runtime they bring.
        sqldelight: a `sqldelight_library` whose generated Kotlin joins `commonMain`. The SQLDelight runtime
            becomes an `api` dependency, as the SQLDelight Gradle plugin makes it.
        generated_srcs: generated `.kt` files (`kotlin_constants`) that join `commonMain`, as Gradle's
            `kotlin.srcDir(task)` does.
        cinterops: `kmp_cinterop` targets of the main compilation; their klibs travel with the module's.
        forbidden_deps: modules this one must never reach, even transitively (`kmp_forbidden_deps`, checked per
            platform as `:<name>_forbidden_deps_<platform>`).
        forbidden_reference: a regular expression the hand-written sources must not match outside comments
            (`:<name>_references`).
        forbidden_reference_reason: why, for the error message.
        forbidden_reference_allowed: module-relative files exempt from `forbidden_reference`.
        keep_deps: dependencies no source uses (`kmp_keep_dep`), which `:<name>_unused_deps` leaves alone.
        resources: the JVM classpath resources, from `src/{common,jvmAndroid,jvm}Main/resources`: one jar, a
            file in a later source set replacing the same path in an earlier one, as Gradle merges them.
        tags: tags of the JVM and Android compiles, such as `kt_abi_plugin_incompatible` (dependents compile
            against the full jar rather than its ABI jar).
        visibility: the targets' visibility. A restricted module also gets `:<name>_for_tests`, testonly and
            public, which every module's tests use instead.
    """
    hand_written = (common_srcs + jvm_android_srcs + jvm_srcs + android_srcs + native_srcs + linux_srcs + apple_srcs +
                    mingw_srcs + non_jvm_srcs + web_srcs + wasm_js_srcs + js_srcs)
    kmp_lint(name, hand_written)
    kmp_module_info(
        name = name + "_module",
        module = "//" + native.package_name(),
        configurations = module_configurations("Main", [
            ("common", deps, exports),
            ("jvmAndroid", jvm_android_deps, jvm_android_exports),
            ("jvm", jvm_deps, jvm_exports),
            ("android", android_deps, android_exports),
            ("nonJvm", non_jvm_deps, []),
            ("native", native_deps, []),
            ("linux", linux_deps, []),
            ("apple", apple_deps, []),
            ("mingw", mingw_deps, []),
            ("web", web_deps, []),
            ("wasmJs", wasm_js_deps, []),
            ("js", js_deps, []),
        ]),
        visibility = ["//visibility:public"],
    )
    if forbidden_reference:
        kmp_reference_check(
            name = name + "_references",
            srcs = hand_written,
            regex = forbidden_reference,
            reason = forbidden_reference_reason,
            allowed = forbidden_reference_allowed,
        )
    if forbidden_deps:
        bytecode = {p: name + "_" + p for p in (["jvm"] if jvm else []) + (["android"] if android else [])}
        _forbidden_deps(name, forbidden_deps, bytecode, {t: name + "_" + t for t in native_targets + web_targets}, BUILDS_ON)
    # Exports are kmp_export directives, a person's say-so (some only generated code uses: Metro's graph,
    # SQLDelight's queries), so only what imports brought is checked.
    declared = deps + jvm_android_deps + (jvm_deps if jvm else android_deps)
    dokka_srcs = struct(
        common_srcs = common_srcs,
        jvm_android_srcs = jvm_android_srcs,
        jvm_srcs = jvm_srcs,
        android_srcs = android_srcs,
        native_srcs = native_srcs,
        linux_srcs = linux_srcs,
        apple_srcs = apple_srcs,
        mingw_srcs = mingw_srcs,
    )
    common_srcs = common_srcs + generated_srcs
    native_deps = native_deps + cinterops
    fragments = _jvm_fragments("Main", common_srcs, jvm_android_srcs, jvm_srcs)
    module_name = _module_name(native_targets, jvm)
    brought = plugin_deps(plugins)
    generated_native = []
    if sqldelight:
        exports = exports + [d for d in ["@maven_kmp//:sqldelight_runtime"] if d not in exports]
        generated_native = [sqldelight + "_kt"]

        # rules_kotlin can't put a srcjar's sources in a fragment, and K2 rejects sources in none, so on the JVM
        # the generated code (which uses nothing of the module's own) compiles on its own and is exported.
        # konanc gets it as commonMain, beside the module's sources, as KGP compiles it.
        for platform in (["jvm"] if jvm else []) + (["android"] if android else []):
            kt_jvm_library(
                name = "%s_sqldelight_%s" % (name, platform),
                srcs = [sqldelight],
                deps = _on(platform, exports),
                exports = _on(platform, exports),
                tags = tags,
                visibility = ["//visibility:private"],
            )
        jvm_android_exports = jvm_android_exports + [":" + name + "_sqldelight"]
    exports = exports + brought.exports
    deps = [d for d in deps + brought.deps if d not in exports]
    if jvm:
        kt_jvm_library(
            name = name + "_jvm",
            srcs = fragments.srcs,
            fragment_srcs = fragments.fragment_srcs,
            fragment_refines = fragments.fragment_refines,
            # Gradle's api is on the module's own classpath too; Bazel's exports are only on its dependents'. A
            # module with no sources of its own (only generated ones) just exports.
            deps = _on("jvm", deps + jvm_android_deps + jvm_deps + exports + jvm_android_exports + jvm_exports) if fragments.srcs else [],
            exports = _on("jvm", exports + jvm_android_exports + jvm_exports),
            module_name = module_name,
            plugins = plugin_labels(plugins, "jvm"),
            resource_jars = _resource_jars(name, "Main", resources),
            # Dispatchers.Main on every JVM runtime classpath.
            runtime_deps = [Label("@maven_kmp//:kotlinx_coroutines_swing_jvm")],
            tags = tags,
            visibility = visibility,
        )
        if fragments.srcs:
            _unused_deps(name, name + "_jvm", declared, keep_deps)
    if android:
        android_fragments = _jvm_fragments("Main", common_srcs, jvm_android_srcs, android_srcs, "android")
        kt_jvm_library(
            name = name + "_android",
            srcs = android_fragments.srcs,
            fragment_srcs = android_fragments.fragment_srcs,
            fragment_refines = android_fragments.fragment_refines,
            deps = _on("android", deps + jvm_android_deps + android_deps + exports + jvm_android_exports + android_exports) +
                   [Label("//bazel/kmp/android:android_jar")] if android_fragments.srcs else [],
            exports = _on("android", exports + jvm_android_exports + android_exports),
            module_name = module_name,
            plugins = plugin_labels(plugins, "jvm"),
            tags = tags,
            visibility = visibility,
        )
        if not jvm and android_fragments.srcs:
            _unused_deps(name, name + "_android", declared, keep_deps, "android")
    if native_targets or web_targets:
        package = native.package_name()
        kt_metadata_library(
            name = name + "_metadata",
            module = "%s:%s" % (package.replace("/", "."), package.replace("/", "-")),
            srcs = common_srcs,
            generated = generated_native,
            deps = _on("metadataWeb" if web_targets else "metadata", deps + metadata_deps + exports),
            plugins = plugin_labels(plugins, "native"),
            web = bool(web_targets),
            visibility = visibility,
        )
    if web_targets:
        # Its own metadata already sees only what the browser does; web modules that depend on it name this.
        native.alias(
            name = name + "_metadataWeb",
            actual = ":" + name + "_metadata",
            visibility = visibility,
        )
    for target in native_targets:
        # A klib's dependencies are always transitive on native, so api and implementation are the same.
        kt_native_klib(
            name = name + "_" + target,
            module = name,
            target = target,
            common_srcs = common_srcs,
            common_generated = generated_native,
            non_jvm_srcs = non_jvm_srcs,
            native_srcs = native_srcs,
            family_srcs = _family(target, linux_srcs, apple_srcs, mingw_srcs),
            deps = _on(target, deps + non_jvm_deps + native_deps + _family(target, linux_deps, apple_deps, mingw_deps) + exports),
            plugins = plugin_labels(plugins, "native"),
            exec_compatible_with = ["//bazel/platforms:compiles_klibs"],
            target_compatible_with = BUILDS_ON.get(target, []),
            visibility = visibility,
        )
    for target in web_targets:
        # As on native, a klib's dependencies are always transitive.
        kt_web_klib(
            name = name + "_" + target,
            target = target,
            common_srcs = common_srcs,
            common_generated = generated_native,
            non_jvm_srcs = non_jvm_srcs,
            web_srcs = web_srcs,
            target_srcs = wasm_js_srcs if target == "wasmJs" else js_srcs,
            deps = _on(target, deps + non_jvm_deps + web_deps + (wasm_js_deps if target == "wasmJs" else js_deps) + exports),
            plugins = plugin_labels(plugins, "native"),
            visibility = visibility,
        )
    if jvm:
        own_sqldelight = [":" + name + "_sqldelight"]
        jvm_classpath = [d for d in deps + jvm_android_deps + exports + jvm_android_exports if d not in own_sqldelight]
        native_dokka = "linuxX64" in native_targets and "mingwX64" in native_targets
        dokka_module(
            name = name + "_dokka",
            module_name = native.package_name().split("/")[-1],
            generated = generated_srcs + ([sqldelight + "_kt"] if sqldelight else []),
            jvm_android = bool(jvm_android_srcs or jvm_android_deps or jvm_android_exports),
            android = android,
            native = native_dokka,
            jvm_deps = _on("jvm", jvm_classpath + jvm_deps + jvm_exports),
            android_deps = _on("android", jvm_classpath + android_deps + android_exports) if android else [],
            metadata_deps = _on("metadata", deps + exports) + [Label("@maven_kmp//:org_jetbrains_kotlin_kotlin_stdlib_metadata")] if native_dokka else [],
            native_deps = _on("linuxX64", deps + native_deps + linux_deps + exports) if native_dokka else [],
            mingw_deps = _on("mingwX64", deps + native_deps + mingw_deps + exports) if native_dokka else [],
            tags = ["manual"],
            visibility = ["//visibility:public"],
            **structs.to_dict(dokka_srcs)
        )
    if visibility and "//visibility:public" not in visibility:
        # `visibility` keeps main code out (verifyNoWriteRepositoryUsage), but Gradle's check only reads main
        # sources: any module's tests may use the module. `:<name>_for_tests` is testonly, so only tests can.
        metadata = (["metadata"] if native_targets or web_targets else []) + (["metadataWeb"] if web_targets else [])
        for platform in (["jvm"] if jvm else []) + (["android"] if android else []) + metadata + native_targets + web_targets:
            native.alias(
                name = "%s_for_tests_%s" % (name, platform),
                actual = ":%s_%s" % (name, platform),
                testonly = True,
                visibility = ["//visibility:public"],
            )

def kmp_test(
        name,
        associate,
        common_srcs = [],
        jvm_android_srcs = [],
        jvm_srcs = [],
        android_srcs = [],
        native_srcs = [],
        linux_srcs = [],
        apple_srcs = [],
        mingw_srcs = [],
        non_jvm_srcs = [],
        web_srcs = [],
        wasm_js_srcs = [],
        js_srcs = [],
        native_targets = [],
        web_targets = [],
        android = False,
        deps = [],
        jvm_android_deps = [],
        jvm_deps = [],
        android_deps = [],
        native_deps = [],
        linux_deps = [],
        apple_deps = [],
        mingw_deps = [],
        non_jvm_deps = [],
        web_deps = [],
        wasm_js_deps = [],
        js_deps = [],
        plugins = [],
        data = [],
        size = "medium",
        forbidden_deps = [],
        keep_deps = [],
        resources = [],
        jvm_flags = [],
        flaky = False,
        device_srcs = [],
        device_deps = [],
        device_manifest = None,
        device_resources = []):
    """A module's tests. Emits `:<name>_jvm`, `:<name>_android`, `:<name>_device`, `:<name>_<target>` per native and
    browser target, and `:<name>_<target>_cross` (manual) per target in `CROSS_PLATFORMS`, its test binary built for
    that platform.

    JVM tests are JUnit 4, run by `//bazel/kmp/testing:KmpJUnitRunner`. Android host tests (AGP's
    `testAndroidHostTest`) are `commonTest ← jvmAndroidTest ← androidHostTest` compiled against the module's
    Android compile, run the same way on a JVM with AGP's mockable android.jar
    (`//bazel/kmp/android:mockable_android_jar`), which returns default values as the Android convention asks. Native tests are linked with konan's
    test runner and run by `//bazel/kmp/testing:native_launcher`; a target's tests only run where its
    executables do (`RUNS_ON`), so on Linux x64 only `_linuxX64` runs. Browser tests (`_wasmJs`, `_js`) are linked
    into an ES module and run on every host, in the pinned Node by `//bazel/kmp/testing/web:runner.mjs`, which
    reports to the same launcher.

    Args:
        name: the test name.
        associate: the `kmp_library` under test, by its module label; the tests see its `internal` declarations.
        common_srcs: `src/commonTest/kotlin` sources.
        jvm_android_srcs: `src/jvmAndroidTest/kotlin` sources.
        jvm_srcs: `src/jvmTest/kotlin` sources.
        android_srcs: `src/androidHostTest/kotlin` sources.
        native_srcs: `src/nativeTest/kotlin` sources.
        linux_srcs: `src/linuxTest/kotlin` sources.
        apple_srcs: `src/appleTest/kotlin` sources.
        mingw_srcs: `src/mingwTest/kotlin` sources.
        non_jvm_srcs: `src/nonJvmTest/kotlin` sources: the native and browser targets'.
        web_srcs: `src/webTest/kotlin` sources.
        wasm_js_srcs: `src/wasmJsTest/kotlin` sources.
        js_srcs: `src/jsTest/kotlin` sources.
        native_targets: the Kotlin/Native targets to test on, by KGP's names.
        web_targets: the browser targets to test on (`wasmJs`, `js`).
        android: run the tests as Android host tests too (the associate must be an Android `kmp_library`).
        deps: test dependencies (Gradle `implementation` on `commonTest`), by module label.
        jvm_android_deps: `jvmAndroidTest` dependencies.
        jvm_deps: `jvmTest` dependencies.
        android_deps: `androidHostTest` dependencies.
        native_deps: `nativeTest` dependencies.
        linux_deps: `linuxTest` dependencies.
        apple_deps: `appleTest` dependencies.
        mingw_deps: `mingwTest` dependencies.
        non_jvm_deps: `nonJvmTest` dependencies.
        web_deps: `webTest` dependencies.
        wasm_js_deps: `wasmJsTest` dependencies.
        js_deps: `jsTest` dependencies.
        plugins: compiler plugins by name, as for kmp_library, plus `mokkery`.
        data: files the tests read at run time, from the workspace root (the runfiles' working directory).
        size: the Bazel test size. Gradle has no test timeout, so the default is medium (300 s); give a slower
            suite a bigger one.
        forbidden_deps: modules the tests must never reach either (`:<name>_forbidden_deps_<platform>`).
        keep_deps: dependencies no test source uses (`kmp_keep_dep`), which `:<name>_unused_deps` leaves alone.
        resources: the JVM tests' classpath resources, from `src/{common,jvmAndroid,jvm}Test/resources`, merged
            as for kmp_library. The Android host tests get those not under `src/jvmTest`.
        jvm_flags: more JVM flags for the JVM test (Gradle's `systemProperty`s: headless Compose Desktop tests).
        flaky: retry a failing JVM test up to twice more, as the Gradle test-retry plugin does for the Compose
            modules (`maxRetries = 2`).
        device_srcs: `androidDeviceTest`'s sources: `src/androidDeviceTest/kotlin` and whatever `srcDir`s the
            module's Gradle build adds to it (the Compose UI modules share `src/commonTest/kotlin`).
        device_deps: `androidDeviceTest` dependencies, by module label.
        device_manifest: `src/androidDeviceTest/AndroidManifest.xml`, merged into the test APK's.
        device_resources: the test APK's Java resources (`src/androidDeviceTest/resources` and shared dirs).
    """
    srcs = common_srcs + jvm_android_srcs + jvm_srcs + android_srcs + native_srcs + linux_srcs + apple_srcs + mingw_srcs
    kmp_lint(name, srcs + [s for s in device_srcs if s not in srcs])
    kmp_module_info(
        name = name + "_module",
        # The associate is always the package's own kmp_library.
        module = "//" + native.package_name(),
        configurations = module_configurations("Test", [
            ("common", deps, []),
            ("jvmAndroid", jvm_android_deps, []),
            ("jvm", jvm_deps, []),
            ("androidHost", android_deps, []),
            ("androidDevice", device_deps, []),
            ("nonJvm", non_jvm_deps, []),
            ("native", native_deps, []),
            ("linux", linux_deps, []),
            ("apple", apple_deps, []),
            ("mingw", mingw_deps, []),
            ("web", web_deps, []),
            ("wasmJs", wasm_js_deps, []),
            ("js", js_deps, []),
        ]),
        visibility = ["//visibility:public"],
    )
    fragments = _jvm_fragments("Test", common_srcs, jvm_android_srcs, jvm_srcs)
    brought = plugin_deps(plugins)
    declared = deps + jvm_android_deps + jvm_deps
    android_deps = jvm_android_deps + android_deps
    jvm_deps = jvm_android_deps + jvm_deps
    deps = deps + brought.deps + brought.exports
    # Tests with only native sources have no JVM test, as KGP's jvmTest finds no sources (utils/sqlite3).
    if fragments.srcs:
        lib = name + "_jvm_lib"
        kt_jvm_library(
            name = lib,
            testonly = True,
            srcs = fragments.srcs,
            fragment_srcs = fragments.fragment_srcs,
            fragment_refines = fragments.fragment_refines,
            associates = [platform_label(associate, "jvm")],
            deps = _on("jvm", deps + jvm_deps),
            plugins = plugin_labels(plugins, "jvm"),
            resource_jars = _resource_jars(name, "Test", resources),
        )
        _unused_deps(name, lib, declared, keep_deps)
        _jvm_test(name + "_jvm", lib, size, flaky, jvm_flags, data, [])
    android_fragments = _jvm_fragments("Test", common_srcs, jvm_android_srcs, android_srcs, "androidHost")
    if android and android_fragments.srcs:
        lib = name + "_android_lib"
        kt_jvm_library(
            name = lib,
            testonly = True,
            srcs = android_fragments.srcs,
            fragment_srcs = android_fragments.fragment_srcs,
            fragment_refines = android_fragments.fragment_refines,
            associates = [platform_label(associate, "android")],
            # As AGP: compiled against android.jar, run on the mockable one.
            deps = _on("android", deps + android_deps) + [Label("//bazel/kmp/android:android_jar")],
            plugins = plugin_labels(plugins, "jvm"),
            resource_jars = _resource_jars(
                name + "_android",
                "Test",
                [r for r in resources if "/src/jvmTest/" not in "/" + r],
            ),
        )
        _jvm_test(name + "_android", lib, size, flaky, [], data, [Label("//bazel/kmp/android:mockable_android_jar")])
    if device_srcs:
        _device_test(name, associate, device_srcs, deps_of_plugins = brought, plugins = plugins, deps = device_deps,
                     manifest = device_manifest, resources = device_resources)
    if forbidden_deps:
        _forbidden_deps(
            name,
            forbidden_deps,
            {"jvm": name + "_jvm_lib"} if fragments.srcs else {},
            {t: "%s_%s_bin" % (name, t) for t in native_targets + web_targets},
            RUNS_ON,
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
            non_jvm_srcs = non_jvm_srcs,
            native_srcs = native_srcs,
            family_srcs = _family(target, linux_srcs, apple_srcs, mingw_srcs),
            deps = _on(target, deps + non_jvm_deps + native_deps + _family(target, linux_deps, apple_deps, mingw_deps)),
            plugins = plugin_labels(plugins, "native"),
            target_compatible_with = RUNS_ON[target],
        )
        java_test(
            name = "%s_%s" % (name, target),
            size = size,
            main_class = "com.moneymanager.bazel.kmp.testing.KnTestLauncher",
            use_testrunner = False,
            args = ["$(rootpath :%s)" % binary],
            data = [":" + binary] + data,
            target_compatible_with = RUNS_ON[target],
            runtime_deps = [Label("//bazel/kmp/testing:native_launcher_lib")],
        )
        if target in CROSS_PLATFORMS:
            kt_native_cross_test_binary(
                name = "%s_%s_cross" % (name, target),
                testonly = True,
                binary = ":" + binary,
                platform = CROSS_PLATFORMS[target],
                tags = ["manual"],
            )

    for target in web_targets:
        program = "%s_%s_bin" % (name, target)
        kt_web_test_program(
            name = program,
            testonly = True,
            target = target,
            associate = platform_label(associate, target),
            common_srcs = common_srcs,
            non_jvm_srcs = non_jvm_srcs,
            web_srcs = web_srcs,
            target_srcs = wasm_js_srcs if target == "wasmJs" else js_srcs,
            deps = _on(target, deps + non_jvm_deps + web_deps + (wasm_js_deps if target == "wasmJs" else js_deps)),
            plugins = plugin_labels(plugins, "native"),
        )
        java_test(
            name = "%s_%s" % (name, target),
            size = size,
            main_class = "com.moneymanager.bazel.kmp.testing.KnTestLauncher",
            use_testrunner = False,
            args = [
                "--node",
                "$(rootpath %s)" % Label("//bazel/kmp/testing/web:node"),
                "$(rootpath %s)" % Label("//bazel/kmp/testing/web:runner.mjs"),
                "$(rootpath :%s)/test.mjs" % program,
            ],
            data = [
                ":" + program,
                Label("//bazel/kmp/testing/web:node"),
                Label("//bazel/kmp/testing/web:runner.mjs"),
            ] + data,
            runtime_deps = [Label("//bazel/kmp/testing:native_launcher_lib")],
        )

def _device_test(name, associate, srcs, deps_of_plugins, plugins, deps, manifest, resources):
    """`:<name>_device`: a library's device tests, as AGP builds and runs them (its `androidDeviceTest`)."""
    lib = name + "_device_lib"
    resource_jars = []
    if resources:
        kmp_resource_jar(
            name = name + "_device_resources",
            srcs = resources,
            roots = ["src/commonTest/resources", "src/androidDeviceTest/resources"],
            testonly = True,
            visibility = ["//visibility:private"],
        )
        resource_jars = [":" + name + "_device_resources"]
    kt_jvm_library(
        name = lib,
        testonly = True,
        srcs = srcs,
        associates = [platform_label(associate, "android")],
        deps = _on("android", deps + deps_of_plugins.deps + deps_of_plugins.exports) +
               [Label("//bazel/kmp/android:android_jar")],
        plugins = plugin_labels(plugins, "jvm"),
        resource_jars = resource_jars,
    )

    # The Android convention's namespace (`com.moneymanager.<group>`), and AGP's test application id after it.
    application_id = "com.moneymanager.%s.test" % native.package_name().replace("/", ".").replace("-", ".")
    android_apk(
        name = name + "_device_apk",
        testonly = True,
        application_id = application_id,
        compile_sdk = COMPILE_SDK,
        lib = ":" + lib,
        manifest = manifest,
        min_sdk = MIN_SDK,
        namespace = application_id,
        # The library convention sets no targetSdk, and AGP's test APK then takes compileSdk.
        target_sdk = COMPILE_SDK,
        test_runner = INSTRUMENTATION_RUNNER,
    )
    android_device_test(
        name = name + "_device",
        size = "large",
        apk = ":%s_device_apk" % name,
        package = application_id,
    )

_COVERAGE_AGENT = str(Label("//bazel/kmp/testing:coverage_agent_deploy.jar"))
_KOVER_AGENT = str(Label("@kover//:org_jetbrains_kotlinx_kover_jvm_agent"))

def _jvm_test(name, lib, size, flaky, jvm_flags, data, runtime_deps):
    """A JUnit 4 test over `lib`'s classes, run by KmpJUnitRunner (the JVM tests and the Android host tests)."""
    java_test(
        name = name,
        size = size,
        flaky = flaky,
        main_class = "com.moneymanager.bazel.kmp.testing.KmpJUnitRunner",
        use_testrunner = False,
        jvm_flags = [
            # The runner finds this jar on its classpath by file name.
            "-Dkmp.test.jar=%s.jar" % lib,
            # java.util.prefs writes under ~/.java, which the sandbox keeps read-only; tests get their own.
            "-Djava.util.prefs.userRoot=$${TEST_TMPDIR}/prefs",
            # Skiko unpacks its native library under ~/.skiko for the same reason (Compose Desktop UI tests).
            "-Dskiko.data.path=$${TEST_TMPDIR}/skiko",
        ] + select({
            "@platforms//os:windows": [],
            # Gradle's test JVMs use /tmp, and terminal tests lay out paths under it within a fixed width
            # (app/tui's footer). The sandbox's /tmp is hermetic, so this stays per test.
            "//conditions:default": ["-Djava.io.tmpdir=/tmp"],
        }) + select({
            # The IntelliJ coverage engine (--config=coverage): see CoverageAgent and coverage.sh.
            Label("//bazel/kmp/testing:coverage"): [
                "-javaagent:$(rootpath %s)=$(rootpath %s)" % (_COVERAGE_AGENT, _KOVER_AGENT),
            ],
            "//conditions:default": [],
        }) + jvm_flags,
        data = data + select({
            Label("//bazel/kmp/testing:coverage"): [_COVERAGE_AGENT, _KOVER_AGENT],
            "//conditions:default": [],
        }),
        runtime_deps = [
            ":" + lib,
            Label("//bazel/kmp/testing:runner"),
        ] + runtime_deps,
    )

def kmp_static_library(name, srcs, native_targets, hdrs = [], defines = [], library_name = None, visibility = None):
    """A C static library per Kotlin/Native target (`:<name>_<target>`), built with that target's konan clang.

    Args:
        name: the target name.
        srcs: the C sources.
        native_targets: the Kotlin/Native targets, by KGP's names.
        hdrs: headers, whose directories go on the include path.
        defines: `NAME=VALUE` preprocessor definitions.
        library_name: the archives are `lib<library_name>.a` (default: the name).
        visibility: the targets' visibility.
    """
    for target in native_targets:
        konan_static_library(
            name = name + "_" + target,
            srcs = srcs,
            hdrs = hdrs,
            defines = defines,
            library_name = library_name or name,
            target = target,
            target_compatible_with = BUILDS_ON.get(target, []),
            visibility = visibility,
        )

def kmp_cinterop(name, def_file, native_targets, static_libraries = [], deps = [], visibility = None):
    """A cinterop per Kotlin/Native target (`:<name>_<target>`), for `kmp_library(cinterops = …)`.

    Args:
        name: the interop's name (KGP's `cinterops.create(name)`).
        def_file: the `.def` file (KGP's default is `src/nativeInterop/cinterop/<name>.def`).
        native_targets: the Kotlin/Native targets, by KGP's names.
        static_libraries: `kmp_static_library` targets the klib embeds (cinterop's `-staticLibrary`).
        deps: klibs the interop's declarations use, by module label.
        visibility: the targets' visibility.
    """
    for target in native_targets:
        kt_native_cinterop(
            name = name + "_" + target,
            def_file = def_file,
            interop = name,
            static_libraries = _on(target, static_libraries),
            deps = _on(target, deps),
            target = target,
            target_compatible_with = BUILDS_ON.get(target, []),
            visibility = visibility,
        )

def kmp_native_executable(name, library, entry_point, base_name, native_targets, release_assets = {}, visibility = None):
    """A release executable per Kotlin/Native target, KGP's `binaries.executable(RELEASE)`.

    `:<name>_<target>` links one target and `:<name>` the build platform's own, so `bazel build //...` links
    only the host's, as Gradle's `build` does. A target with a `release_assets` name also gets
    `:<asset>`: the program as a release asset, `<asset>.tar.gz` holding `<base_name>` with its executable bit
    (release assets don't keep it), or a plain `<asset>.exe` on Windows.

    Args:
        name: the target name.
        library: the `kmp_library` whose klib is the program, by module label.
        entry_point: the fully qualified `main` function.
        base_name: the file name, `<base_name>.kexe` (`.exe` on Windows).
        native_targets: the Kotlin/Native targets, by KGP's names.
        release_assets: target → release asset name (`mm-linux-x64`).
        visibility: the targets' visibility.
    """
    for target in native_targets:
        kt_native_executable(
            name = name + "_" + target,
            library = platform_label(library, target),
            entry_point = entry_point,
            base_name = base_name,
            target = target,
            tags = ["manual"],
            target_compatible_with = BUILDS_ON.get(target, []),
            visibility = visibility,
        )
        asset = release_assets.get(target)
        if not asset:
            continue
        if target == "mingwX64":
            copy_file(
                name = asset,
                src = name + "_" + target,
                out = asset + ".exe",
                tags = ["manual"],
                target_compatible_with = BUILDS_ON.get(target, []),
                visibility = visibility,
            )
        else:
            pkg_files(
                name = asset + "_files",
                srcs = [name + "_" + target],
                attributes = pkg_attributes(mode = "0755"),
                renames = {name + "_" + target: base_name},
                tags = ["manual"],
                target_compatible_with = BUILDS_ON.get(target, []),
            )
            pkg_tar(
                name = asset,
                srcs = [asset + "_files"],
                extension = "tar.gz",
                tags = ["manual"],
                target_compatible_with = BUILDS_ON.get(target, []),
                visibility = visibility,
            )
    native.alias(
        name = name,
        actual = select(
            {"//bazel/kmp/native:is_" + target: name + "_" + target for target in native_targets},
            no_match_error = "%s: no Kotlin/Native target for this platform" % name,
        ),
        visibility = visibility,
    )
