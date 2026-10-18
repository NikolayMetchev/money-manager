"""`android_sdk` module extension: the Android SDK platform modules compile against (#1021), and aapt2 (#1023).

AGP compiles an Android library against the SDK's `platforms/android-<compileSdk>/android.jar`. This finds the SDK
as AGP does (`ANDROID_HOME`, then `ANDROID_SDK_ROOT`, then `local.properties`' `sdk.dir`) and exposes that jar,
neverlink, as `@android_sdk//:android_jar`. `compileSdk` is the catalog's `android-compileSdk`. Nothing is
downloaded: installing the SDK means accepting its license, which stays the person's (or CI's `sdkmanager`) call.
Without the SDK the repository still loads, and building anything that needs the jar says what's missing.

`@android_sdk//:versions.bzl` holds the catalog's `android-minSdk`, `android-targetSdk`, `android-compileSdk` and
AGP's version, `@android_sdk//:adb` is platform-tools' adb (device tests run through it), and
`@aapt2//:aapt2` is the host's aapt2, the build AGP itself downloads (`pins.bzl`).
"""

load("//bazel/kmp/android:pins.bzl", "AAPT2_BUILD", "AAPT2_SHA256")
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

# The platform as Android lint reads it (#1025): an SDK holding only this platform, its API database included.
filegroup(
    name = "lint_sdk",
    srcs = glob([
        "sdk/platforms/*/android.jar",
        "sdk/platforms/*/build.prop",
        "sdk/platforms/*/data/**",
        "sdk/platforms/*/optional/**",
        "sdk/platforms/*/package.xml",
        "sdk/platforms/*/*.properties",
    ]),
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

filegroup(
    name = "lint_sdk",
    srcs = [":missing"],
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

_VERSIONS = """\
MIN_SDK = {min}
TARGET_SDK = {target}
COMPILE_SDK = {compile}
AGP_VERSION = "{agp}"
"""

# The adb device tests install and run through (`android_device_test`); without platform-tools only running them fails.
_ADB_FOUND = """
exports_files(["adb"])
"""

_ADB_MISSING = """
genrule(
    name = "adb_missing",
    outs = ["adb"],
    cmd = {cmd},
    visibility = ["//visibility:public"],
)
"""

def _adb(rctx, sdk):
    exe = "adb.exe" if rctx.os.name.lower().startswith("windows") else "adb"
    if sdk:
        adb = rctx.path("%s/platform-tools/%s" % (sdk, exe))
        rctx.watch(adb)
        if adb.exists:
            rctx.symlink(adb, "adb")
            return _ADB_FOUND
    message = "No adb: install the SDK's platform-tools with `sdkmanager platform-tools`."
    return _ADB_MISSING.format(cmd = repr("echo '%s' >&2; exit 1" % message))

# AGP signs debug builds with ~/.android/debug.keystore, creating it when it's missing, so a Bazel APK and a
# Gradle one install over each other. Without one the build makes its own, as AGP would.
_KEYSTORE_FOUND = """
exports_files(["debug.keystore"])
"""

_KEYSTORE_MISSING = """
genrule(
    name = "debug_keystore",
    outs = ["debug.keystore"],
    cmd = "$(execpath @money_manager//bazel/kmp/android:android_app) debug-keystore --out $@",
    tools = ["@money_manager//bazel/kmp/android:android_app"],
    visibility = ["//visibility:public"],
)
"""

def _debug_keystore(rctx):
    home = rctx.getenv("ANDROID_USER_HOME") or (rctx.getenv("HOME", rctx.getenv("USERPROFILE", "")) + "/.android")
    keystore = rctx.path(home + "/debug.keystore")
    rctx.watch(keystore)
    if keystore.exists:
        rctx.symlink(keystore, "debug.keystore")
        return _KEYSTORE_FOUND
    return _KEYSTORE_MISSING

def _platform_impl(rctx):
    tools = _debug_keystore(rctx)
    rctx.file("versions.bzl", _VERSIONS.format(
        min = rctx.attr.min_sdk,
        target = rctx.attr.target_sdk,
        compile = rctx.attr.compile_sdk,
        agp = rctx.attr.agp,
    ))
    sdk = _sdk_dir(rctx)
    tools += _adb(rctx, sdk)
    level = rctx.attr.compile_sdk
    # Newer SDKs name the platform after its minor version too (`android-37.0`).
    candidates = ["android-%s" % level, "android-%s.0" % level]
    if sdk:
        for candidate in candidates:
            jar = rctx.path("%s/platforms/%s/android.jar" % (sdk, candidate))
            rctx.watch(jar)
            if jar.exists:
                rctx.symlink(jar, "android.jar")
                rctx.symlink(jar.dirname, "sdk/platforms/" + candidate)
                rctx.file("BUILD.bazel", _FOUND + tools)
                return
    message = ("No Android SDK platform %s: set ANDROID_HOME (or sdk.dir in local.properties) and install it " +
               "with `sdkmanager \"platforms;%s\"`. Looked in %s.") % (level, candidates[-1], sdk or "no SDK")
    rctx.file("BUILD.bazel", _MISSING.format(cmd = repr("echo '%s' >&2; exit 1" % message)) + tools)

_platform = repository_rule(
    implementation = _platform_impl,
    attrs = {
        "agp": attr.string(mandatory = True),
        "compile_sdk": attr.string(mandatory = True),
        "local_properties": attr.string(mandatory = True),
        "min_sdk": attr.string(mandatory = True),
        "target_sdk": attr.string(mandatory = True),
    },
    local = True,
)

def _aapt2_impl(rctx):
    name = rctx.os.name.lower()
    host = "osx" if name.startswith("mac") else "windows" if name.startswith("windows") else "linux"
    version = "%s-%s" % (rctx.attr.agp, AAPT2_BUILD)
    rctx.download_and_extract(
        url = "https://dl.google.com/android/maven2/com/android/tools/build/aapt2/%s/aapt2-%s-%s.jar" % (
            version,
            version,
            host,
        ),
        sha256 = AAPT2_SHA256[host],
        type = "zip",
    )
    if host == "windows":
        rctx.file("BUILD.bazel", 'alias(name = "aapt2", actual = "aapt2.exe", visibility = ["//visibility:public"])\n')
    else:
        # A jar keeps no file modes.
        rctx.execute(["chmod", "+x", "aapt2"])
        rctx.file("BUILD.bazel", 'exports_files(["aapt2"])\n')

_aapt2 = repository_rule(
    implementation = _aapt2_impl,
    attrs = {"agp": attr.string(mandatory = True)},
)

def _android_sdk_impl(module_ctx):
    versions = catalog(module_ctx.read(Label("//gradle:libs.versions.toml")))[0]
    root = module_ctx.path(Label("//:MODULE.bazel")).dirname
    _platform(
        name = "android_sdk",
        agp = versions["android-gradle-plugin"],
        compile_sdk = versions["android-compileSdk"],
        min_sdk = versions["android-minSdk"],
        target_sdk = versions["android-targetSdk"],
        local_properties = str(root) + "/local.properties",
    )
    _aapt2(name = "aapt2", agp = versions["android-gradle-plugin"])
    return module_ctx.extension_metadata(reproducible = False)

android_sdk = module_extension(implementation = _android_sdk_impl)
