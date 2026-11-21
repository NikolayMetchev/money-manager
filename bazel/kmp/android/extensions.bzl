"""`android_sdk` module extension: the Android SDK parts the build reads (#1021, #1310), and aapt2 (#1023).

Nothing needs installing: the build downloads what it reads from Google's SDK repository, at the versions `pins.bzl`
names. `@android_platform` is the `compile_sdk` platform: its `android.jar`, neverlink, as `:android_jar`, and the
platform laid out as an SDK for lint as `:lint_sdk`. `@android_platform_tools//:adb` is what device tests run through.
`@android_emulator` is the emulator and system image the device tests boot when no device is connected.

Both are under the Android SDK License, which the person accepts, not the build: Bazel has no way to ask during a
build, so until `bazel run //tools/repo-scripts:repo-scripts-tool -- accept-android-license` has recorded it, these
repositories hold an action that fails saying so, and only what needs them breaks. The record is a copy of the license
text (`sdk_license.txt`) in the Android user home, so a license `repin` brings in that differs is asked about again.

`@android_sdk` downloads nothing, as every BUILD file loads it: `:versions.bzl` holds the levels `android_sdk.levels`
sets (min, target, compile) and AGP's version, and `:debug.keystore` the debug signing key. `@aapt2//:aapt2` is the
host's aapt2, the build AGP itself downloads (`pins.bzl`).
"""

load("//bazel/kmp/android:pins.bzl", "AAPT2_BUILD", "AAPT2_SHA256", "ANDROID_EMULATOR", "ANDROID_PLATFORM", "ANDROID_SYSTEM_IMAGE", "PLATFORM_TOOLS")

_SDK_REPOSITORY = "https://dl.google.com/android/repository/"

_LICENSE = Label("//bazel/kmp/android:sdk_license.txt")

# The Arm system image (Apple Silicon, Windows on Arm) is under a license that adds a clause on running Arm code.
_ARM_LICENSE = Label("//bazel/kmp/android:sdk_arm_license.txt")

# repo-scripts' accept-android-license and bazel/ci/accept_android_license.sh write these.
_LICENSE_RECORD = "money-manager-sdk-license.txt"
_ARM_LICENSE_RECORD = "money-manager-sdk-arm-license.txt"

_NOT_ACCEPTED = ("Android builds need the Android SDK License accepted. Read and accept it with: " +
                 "bazel run //tools/repo-scripts:repo-scripts-tool -- accept-android-license")

def _android_user_home(rctx):
    return rctx.getenv("ANDROID_USER_HOME") or (rctx.getenv("HOME", rctx.getenv("USERPROFILE", "")) + "/.android")

def _license_accepted(rctx, license = _LICENSE, record_name = _LICENSE_RECORD):
    record = rctx.path(_android_user_home(rctx) + "/" + record_name)
    rctx.watch(record)
    return record.exists and rctx.read(record) == rctx.read(license)

_FAILING_CMD = repr("echo '%s' >&2; exit 1" % _NOT_ACCEPTED)

_ARM_NOT_ACCEPTED = ("The Arm Android system image needs its license accepted. Read and accept it with: " +
                     "bazel run //tools/repo-scripts:repo-scripts-tool -- accept-android-license arm")


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

_EMULATOR = """\
exports_files(["emulator.properties"])
"""

def _cpu(rctx):
    return "aarch64" if rctx.os.arch in ("aarch64", "arm64") else "x64"

def _emulator_impl(rctx):
    host = _host(rctx)
    cpu = _cpu(rctx)
    key = "%s_%s" % (host, cpu)

    # A host that can't boot the emulator still builds device tests, and runs them on a connected device: the runner
    # reports `blocked` only when it has no device and needs the emulator.
    if key not in ANDROID_EMULATOR:
        rctx.file("emulator.properties", "blocked=Google publishes no Android emulator for %s (only %s): connect a device or set ANDROID_SERIAL\n" % (
            key,
            ", ".join(ANDROID_EMULATOR.keys()),
        ))
        rctx.file("BUILD.bazel", _EMULATOR)
        return
    if ANDROID_SYSTEM_IMAGE["api"] != str(rctx.attr.target_sdk):
        fail("pins.bzl has the Android system image for API %s, MODULE.bazel's target_sdk is %s: run bazel/renovate/repin.sh" % (
            ANDROID_SYSTEM_IMAGE["api"],
            rctx.attr.target_sdk,
        ))

    # An Arm host runs Arm images natively, every other host the x86_64 ones.
    abi = "arm64-v8a" if cpu == "aarch64" else "x86_64"
    arm = abi == "arm64-v8a"
    for needed, license, record in [(True, _LICENSE, _LICENSE_RECORD), (arm, _ARM_LICENSE, _ARM_LICENSE_RECORD)]:
        if needed and not _license_accepted(rctx, license, record):
            rctx.file("emulator.properties", "blocked=%s\n" % (_ARM_NOT_ACCEPTED if license == _ARM_LICENSE else _NOT_ACCEPTED))
            rctx.file("BUILD.bazel", _EMULATOR)
            return

    emulator, emulator_sha256 = ANDROID_EMULATOR[key]
    rctx.download_and_extract(url = _SDK_REPOSITORY + emulator, sha256 = emulator_sha256, output = "sdk")

    # The emulator takes a directory for an SDK root only with platform-tools in it (it never runs that adb).
    tools, tools_sha256 = PLATFORM_TOOLS[host]
    rctx.download_and_extract(url = _SDK_REPOSITORY + tools, sha256 = tools_sha256, output = "sdk")
    image, image_sha256 = ANDROID_SYSTEM_IMAGE[abi]
    image_dir = "system-images/android-%s/%s" % (ANDROID_SYSTEM_IMAGE["api"], ANDROID_SYSTEM_IMAGE["tag"])
    rctx.download_and_extract(url = _SDK_REPOSITORY + image, sha256 = image_sha256, output = "sdk/" + image_dir)

    # What the runner reads (DeviceTestRunner): everything is relative to this file.
    rctx.file("emulator.properties", "\n".join([
        "sdk=sdk",
        "abi=" + abi,
        "api=" + ANDROID_SYSTEM_IMAGE["api"],
        "image=%s/%s" % (image_dir, abi),
        "",
    ]))
    rctx.file("BUILD.bazel", _EMULATOR)

_emulator = repository_rule(
    implementation = _emulator_impl,
    attrs = {"target_sdk": attr.int(mandatory = True)},
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
    _emulator(name = "android_emulator", target_sdk = levels[0].target_sdk)
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
