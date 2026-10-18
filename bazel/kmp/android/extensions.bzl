"""`android_sdk` module extension: the Android SDK platform modules compile against (#1021).

AGP compiles an Android library against the SDK's `platforms/android-<compileSdk>/android.jar`. This finds the SDK
as AGP does (`ANDROID_HOME`, then `ANDROID_SDK_ROOT`, then `local.properties`' `sdk.dir`) and exposes that jar,
neverlink, as `@android_sdk//:android_jar`. `compileSdk` is the catalog's `android-compileSdk`. Nothing is
downloaded: installing the SDK means accepting its license, which stays the person's (or CI's `sdkmanager`) call.
Without the SDK the repository still loads, and building anything that needs the jar says what's missing.
"""

load("//bazel/kmp/deps:catalog.bzl", "catalog")

_FOUND = """\
load("@rules_java//java:java_import.bzl", "java_import")

exports_files(["android.jar"])

java_import(
    name = "android_jar",
    jars = ["android.jar"],
    neverlink = True,
    visibility = ["//visibility:public"],
)
"""

# Without the platform the jar is an action that fails saying so, so only what compiles for Android breaks.
_MISSING = """\
load("@rules_java//java:java_import.bzl", "java_import")

genrule(
    name = "missing",
    outs = ["android.jar"],
    cmd = {cmd},
    visibility = ["//visibility:public"],
)

java_import(
    name = "android_jar",
    jars = [":missing"],
    neverlink = True,
    visibility = ["//visibility:public"],
)
"""

def _properties(text):
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, _, value = line.partition("=")
            out[key.strip()] = value.strip()
    return out

def _sdk_dir(rctx):
    for var in ["ANDROID_HOME", "ANDROID_SDK_ROOT"]:
        value = rctx.getenv(var)
        if value:
            return value
    local = rctx.path(rctx.attr.local_properties)
    rctx.watch(local)
    if local.exists:
        return _properties(rctx.read(local)).get("sdk.dir")
    return None

def _platform_impl(rctx):
    sdk = _sdk_dir(rctx)
    level = rctx.attr.compile_sdk
    # Newer SDKs name the platform after its minor version too (`android-37.0`).
    candidates = ["android-%s" % level, "android-%s.0" % level]
    if sdk:
        for candidate in candidates:
            jar = rctx.path("%s/platforms/%s/android.jar" % (sdk, candidate))
            rctx.watch(jar)
            if jar.exists:
                rctx.symlink(jar, "android.jar")
                rctx.file("BUILD.bazel", _FOUND)
                return
    message = ("No Android SDK platform %s: set ANDROID_HOME (or sdk.dir in local.properties) and install it " +
               "with `sdkmanager \"platforms;%s\"`. Looked in %s.") % (level, candidates[-1], sdk or "no SDK")
    rctx.file("BUILD.bazel", _MISSING.format(cmd = repr("echo '%s' >&2; exit 1" % message)))

_platform = repository_rule(
    implementation = _platform_impl,
    attrs = {
        "compile_sdk": attr.string(mandatory = True),
        "local_properties": attr.string(mandatory = True),
    },
    local = True,
)

def _android_sdk_impl(module_ctx):
    versions = catalog(module_ctx.read(Label("//gradle:libs.versions.toml")))[0]
    root = module_ctx.path(Label("//:MODULE.bazel")).dirname
    _platform(
        name = "android_sdk",
        compile_sdk = versions["android-compileSdk"],
        local_properties = str(root) + "/local.properties",
    )
    return module_ctx.extension_metadata(reproducible = False)

android_sdk = module_extension(implementation = _android_sdk_impl)
