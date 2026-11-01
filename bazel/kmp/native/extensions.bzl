"""`kotlin_native` module extension: the hermetic Kotlin/Native toolchain for each exec host (#998).

The Kotlin version is the compiler the JVM compile uses: rules_kotlin's release, or the one the root module's
`compiler` tag names when it overrides rules_kotlin's compiler (`rules_kotlin_extensions.kotlinc_version`, for a
Kotlin release rules_kotlin has none for yet). It must equal `KOTLIN` in MODULE.bazel, the maven_kmp libraries'
Kotlin. `pins.bzl` must have been generated for that version, so bumping it without repinning fails the first native
build.
"""

load("@rules_kotlin//src/main/starlark/core/repositories:versions.bzl", "versions")
load(":pins.bzl", "KOTLIN_NATIVE_DEPENDENCY_SHA256", "KOTLIN_NATIVE_DIST_SHA256", "KOTLIN_NATIVE_VERSION")
load(":repository.bzl", "DIST_HOSTS", "kotlin_native_repin", "kotlin_native_repository")

def _kotlin_native_impl(module_ctx):
    version = versions.KOTLIN_CURRENT_COMPILER_RELEASE.version
    for mod in module_ctx.modules:
        for tag in mod.tags.compiler:
            if not mod.is_root:
                fail("kotlin_native.compiler is available to the root module only")
            version = tag.version
    kotlin_native_repin(name = "kotlin_native_repin", version = version)

    # One repository per exec host, not just this one: a Mac sends its klib compiles to Linux executors
    # (#1089). Each is fetched only once a toolchain on its host is selected.
    for host in DIST_HOSTS:
        kotlin_native_repository(
            name = "kotlin_native_" + host,
            version = version,
            pinned_version = KOTLIN_NATIVE_VERSION,
            host = host,
            dist_sha256 = KOTLIN_NATIVE_DIST_SHA256.get(host, ""),
            dependency_sha256 = KOTLIN_NATIVE_DEPENDENCY_SHA256.get(host, {}),
            klib_only_dist_sha256 = KOTLIN_NATIVE_DIST_SHA256,
        )
    return module_ctx.extension_metadata(reproducible = True)

kotlin_native = module_extension(
    implementation = _kotlin_native_impl,
    tag_classes = {
        "compiler": tag_class(
            doc = "The Kotlin release to use instead of rules_kotlin's, when its compiler is overridden.",
            attrs = {"version": attr.string(mandatory = True)},
        ),
    },
)
