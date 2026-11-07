"""`android_sdk` module extension: the Android SDK parts the build reads (#1021, #1310), and aapt2 (#1023).

Nothing needs installing: the build downloads what it reads from Google's SDK repository, at the versions `pins.bzl`
names. `@android_platform` is the `compile_sdk` platform: its `android.jar`, neverlink, as `:android_jar`, and the
platform laid out as an SDK for lint as `:lint_sdk`. `@android_platform_tools//:adb` is what device tests run through.
Emulators stay outside the build.

Both are under the Android SDK License, which the person accepts, not the build: Bazel has no way to ask during a
build, so until `bazel run //tools/repo-scripts:repo-scripts-tool -- accept-android-license` has recorded it, these
repositories hold an action that fails saying so, and only what needs them breaks. The record is a copy of the license
text (`sdk_license.txt`) in the Android user home, so a license `repin` brings in that differs is asked about again.

`@android_sdk` downloads nothing, as every BUILD file loads it: `:versions.bzl` holds the levels `android_sdk.levels`
sets (min, target, compile) and AGP's version, and `:debug.keystore` the debug signing key. `@aapt2//:aapt2` is the
host's aapt2, the build AGP itself downloads (`pins.bzl`).
"""

load("//bazel/kmp/android:pins.bzl", "AAPT2_BUILD", "AAPT2_SHA256", "ANDROID_PLATFORM", "PLATFORM_TOOLS")

_SDK_REPOSITORY = "https://dl.google.com/android/repository/"

_LICENSE = Label("//bazel/kmp/android:sdk_license.txt")

# repo-scripts' accept-android-license and bazel/ci/accept_android_license.sh write this.
_LICENSE_RECORD = "money-manager-sdk-license.txt"

_NOT_ACCEPTED = ("Android builds need the Android SDK License accepted. Read and accept it with: " +
                 "bazel run //tools/repo-scripts:repo-scripts-tool -- accept-android-license")

def _android_user_home(rctx):
    return rctx.getenv("ANDROID_USER_HOME") or (rctx.getenv("HOME", rctx.getenv("USERPROFILE", "")) + "/.android")

def _license_accepted(rctx):
    record = rctx.path(_android_user_home(rctx) + "/" + _LICENSE_RECORD)
    rctx.watch(record)
    return record.exists and rctx.read(record) == rctx.read(_LICENSE)

_FAILING_CMD = repr("echo '%s' >&2; exit 1" % _NOT_ACCEPTED)

_PLATFORM = """\
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
        "sdk/platforms/*/*.properties",
    ]),
    visibility = ["//visibility:public"],
)
"""

_PLATFORM_NOT_ACCEPTED = """\
load("@rules_java//java:java_import.bzl", "java_import")

genrule(
    name = "not_accepted",
    outs = ["android.jar"],
    cmd = {cmd},
    visibility = ["//visibility:public"],
)

java_import(
    name = "android_jar",
    jars = [":not_accepted"],
    neverlink = True,
    visibility = ["//visibility:public"],
)

filegroup(
    name = "lint_sdk",
    srcs = [":not_accepted"],
    visibility = ["//visibility:public"],
)
"""

def _platform_impl(rctx):
    # Checked here, not in the extension: repin itself runs through Bazel, on a MODULE.bazel that has just moved.
    if ANDROID_PLATFORM["compile_sdk"] != rctx.attr.compile_sdk:
        fail("pins.bzl has the Android platform for API %d, MODULE.bazel's compile_sdk is %d: run bazel/renovate/repin.sh" % (
            ANDROID_PLATFORM["compile_sdk"],
            rctx.attr.compile_sdk,
        ))
    if ANDROID_PLATFORM["file"] != "platform-%s.zip" % rctx.attr.version:
        fail("pins.bzl has %s, MODULE.bazel's android_sdk.platform is %s: run bazel/renovate/repin.sh" % (
            ANDROID_PLATFORM["file"],
            rctx.attr.version,
        ))
    if not _license_accepted(rctx):
        rctx.file("BUILD.bazel", _PLATFORM_NOT_ACCEPTED.format(cmd = _FAILING_CMD))
        return
    rctx.download_and_extract(
        url = _SDK_REPOSITORY + ANDROID_PLATFORM["file"],
        sha256 = ANDROID_PLATFORM["sha256"],
        output = "sdk/platforms",
    )
    rctx.symlink("sdk/platforms/%s/android.jar" % ANDROID_PLATFORM["dir"], "android.jar")
    rctx.file("BUILD.bazel", _PLATFORM)

_platform = repository_rule(
    implementation = _platform_impl,
    attrs = {
        "compile_sdk": attr.int(mandatory = True),
        "version": attr.string(mandatory = True),
    },
)

_ADB_FILES = """\
filegroup(
    name = "adb_files",
    srcs = {srcs},
    visibility = ["//visibility:public"],
)
"""

_ADB_UNIX = 'exports_files(["adb"])\n\n' + _ADB_FILES.format(srcs = '["adb"]')

# adb.exe loads its DLLs from beside it, so a test's runfiles carry them too.
_ADB_WINDOWS = ('alias(name = "adb", actual = "adb.exe", visibility = ["//visibility:public"])\n\n' +
                _ADB_FILES.format(srcs = 'glob(["adb.exe", "*.dll"])'))

_ADB_NOT_ACCEPTED = """\
genrule(
    name = "not_accepted",
    outs = ["adb"],
    cmd = {cmd},
    visibility = ["//visibility:public"],
)

filegroup(
    name = "adb_files",
    srcs = [":not_accepted"],
    visibility = ["//visibility:public"],
)
"""

def _host(rctx):
    name = rctx.os.name.lower()
    return "osx" if name.startswith("mac") else "windows" if name.startswith("windows") else "linux"

def _platform_tools_impl(rctx):
    host = _host(rctx)
    archive, sha256 = PLATFORM_TOOLS[host]
    if not archive.startswith("platform-tools_r%s-" % rctx.attr.version):
        fail("pins.bzl has %s, MODULE.bazel's android_sdk.platform_tools is %s: run bazel/renovate/repin.sh" % (
            archive,
            rctx.attr.version,
        ))
    if not _license_accepted(rctx):
        rctx.file("BUILD.bazel", _ADB_NOT_ACCEPTED.format(cmd = _FAILING_CMD))
        return
    rctx.download_and_extract(url = _SDK_REPOSITORY + archive, sha256 = sha256, strip_prefix = "platform-tools")
    rctx.file("BUILD.bazel", _ADB_WINDOWS if host == "windows" else _ADB_UNIX)

_platform_tools = repository_rule(
    implementation = _platform_tools_impl,
    attrs = {"version": attr.string(mandatory = True)},
)

_VERSIONS = """\
MIN_SDK = {min}
TARGET_SDK = {target}
COMPILE_SDK = {compile}
AGP_VERSION = "{agp}"
"""

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
    keystore = rctx.path(_android_user_home(rctx) + "/debug.keystore")
    rctx.watch(keystore)
    if keystore.exists:
        rctx.symlink(keystore, "debug.keystore")
        return _KEYSTORE_FOUND
    return _KEYSTORE_MISSING

def _sdk_impl(rctx):
    rctx.file("versions.bzl", _VERSIONS.format(
        min = rctx.attr.min_sdk,
        target = rctx.attr.target_sdk,
        compile = rctx.attr.compile_sdk,
        agp = rctx.attr.agp,
    ))
    rctx.file("BUILD.bazel", _debug_keystore(rctx))

_sdk = repository_rule(
    implementation = _sdk_impl,
    attrs = {
        "agp": attr.string(mandatory = True),
        "compile_sdk": attr.string(mandatory = True),
        "min_sdk": attr.string(mandatory = True),
        "target_sdk": attr.string(mandatory = True),
    },
    local = True,
)

def _aapt2_impl(rctx):
    host = _host(rctx)
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
    agps = [tag.version for mod in module_ctx.modules if mod.is_root for tag in mod.tags.agp]
    if len(agps) != 1:
        fail("android_sdk: the root module sets the AGP version with exactly one android_sdk.agp(version = …)")
    levels = [tag for mod in module_ctx.modules if mod.is_root for tag in mod.tags.levels]
    if len(levels) != 1:
        fail("android_sdk: the root module sets the API levels with exactly one android_sdk.levels(…)")
    platforms = [tag.version for mod in module_ctx.modules if mod.is_root for tag in mod.tags.platform]
    if len(platforms) != 1:
        fail("android_sdk: the root module sets the platform release with exactly one android_sdk.platform(version = …)")
    tools = [tag.version for mod in module_ctx.modules if mod.is_root for tag in mod.tags.platform_tools]
    if len(tools) != 1:
        fail("android_sdk: the root module sets platform-tools with exactly one android_sdk.platform_tools(version = …)")
    _sdk(
        name = "android_sdk",
        agp = agps[0],
        compile_sdk = str(levels[0].compile_sdk),
        min_sdk = str(levels[0].min_sdk),
        target_sdk = str(levels[0].target_sdk),
    )
    _platform(name = "android_platform", compile_sdk = levels[0].compile_sdk, version = platforms[0])
    _platform_tools(name = "android_platform_tools", version = tools[0])
    _aapt2(name = "aapt2", agp = agps[0])
    return module_ctx.extension_metadata(reproducible = False)

android_sdk = module_extension(
    implementation = _android_sdk_impl,
    tag_classes = {
        # The AGP release aapt2 and AGP_VERSION come from; @android_tools resolves the same version.
        "agp": tag_class(attrs = {"version": attr.string(mandatory = True)}),
        # The SDK repository's releases: `<api>_r<revision>` (platform-37.0_r02.zip), and platform-tools' version.
        "platform": tag_class(attrs = {"version": attr.string(mandatory = True)}),
        "platform_tools": tag_class(attrs = {"version": attr.string(mandatory = True)}),
        # The API levels: modules compile against compile_sdk's android.jar, and apps declare min and target.
        "levels": tag_class(attrs = {
            "compile_sdk": attr.int(mandatory = True),
            "min_sdk": attr.int(mandatory = True),
            "target_sdk": attr.int(mandatory = True),
        }),
    },
)
