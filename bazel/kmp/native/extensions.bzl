"""`kotlin_native` module extension: the hermetic Kotlin/Native toolchain for the exec host (#998).

The Kotlin version has one source: rules_kotlin's compiler release, which the JVM compile already uses
(and which must equal `kotlin` in libs.versions.toml). `pins.bzl` must have been generated for
that version, so bumping rules_kotlin without repinning fails the first native build.
"""

load("@rules_kotlin//src/main/starlark/core/repositories:versions.bzl", "versions")
load(":pins.bzl", "KOTLIN_NATIVE_DEPENDENCY_SHA256", "KOTLIN_NATIVE_DIST_SHA256", "KOTLIN_NATIVE_VERSION")
load(":repository.bzl", "kotlin_native_repin", "kotlin_native_repository")

def _host(os):
    name = os.name.lower()
    arch = os.arch.lower()
    x64 = arch in ["amd64", "x86_64"]
    arm64 = arch in ["aarch64", "arm64"]
    if name.startswith("linux") and x64:
        return "linux_x64"
    if name.startswith("mac") and arm64:
        return "macos_arm64"
    if name.startswith("windows") and x64:
        return "mingw_x64"
    return ""

def _kotlin_native_impl(module_ctx):
    version = versions.KOTLIN_CURRENT_COMPILER_RELEASE.version
    kotlin_native_repin(name = "kotlin_native_repin", version = version)
    host = _host(module_ctx.os)
    kotlin_native_repository(
        name = "kotlin_native",
        version = version,
        pinned_version = KOTLIN_NATIVE_VERSION,
        host = host,
        dist_sha256 = KOTLIN_NATIVE_DIST_SHA256.get(host, ""),
        dependency_sha256 = KOTLIN_NATIVE_DEPENDENCY_SHA256.get(host, {}),
    )
    return module_ctx.extension_metadata(reproducible = True)

kotlin_native = module_extension(
    implementation = _kotlin_native_impl,
    os_dependent = True,
    arch_dependent = True,
)
