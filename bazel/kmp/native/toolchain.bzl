"""The Kotlin/Native toolchain, and `run_konan`, the one way a rule runs a konan tool (#998).

There is one toolchain per exec platform. It carries every target that host can build (`HOST_TARGETS` in
properties.bzl), each with its own input files, and a rule picks its target by name: one build compiles
the same module for linuxX64 and mingwX64 side by side, as KGP does, with no platform transition.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")

KotlinNativeToolchainInfo = provider(
    doc = "A Kotlin/Native distribution with its konan dependencies, and the JVM that runs it.",
    fields = {
        "data_dir": "Exec path of KONAN_DATA_DIR (holds `dependencies/`).",
        "dist_dir": "Exec path of the distribution (konan.home).",
        "dist_files": "depset of the distribution files every invocation reads.",
        "host": "konan's name for the exec host (`linux_x64`, `macos_arm64`, `mingw_x64`).",
        "java_runtime": "JavaRuntimeInfo konan runs on.",
        "launcher": "The KonanLauncher jar, run in front of konan's main class.",
        "platform_caches": "dict: konan target → depset of the cache directories of stdlib and the platform " +
                           "klibs our libraries use, for each target konan caches on this host (see caches below).",
        "targets": "dict: konan target name → depset of that target's platform klibs and dependencies.",
        "version": "The Kotlin/Native version.",
    },
)

# Compiler caches (#1101's follow-up): konan compiles every library a binary links into machine code at link
# time, the stdlib and every Maven library included, unless the library has a static cache (`-p static_cache`).
# KGP builds one per library for a debug link; so do we, one action per library (the aspect in native.bzl, and
# `kotlin_native_platform_cache` for the distribution's own klibs), and a test link reads them through
# `-Xcache-directory`. A library is only cacheable when every library it depends on is, platform klibs included,
# and only for the targets konan caches on its own host (`cacheableTargets.<host>` in konan.properties).
KonanCacheInfo = provider(
    doc = "The static caches of a library and everything it depends on.",
    fields = {
        "cacheable": "Whether the library itself has a cache, so the libraries above it can have one too.",
        "dirs": "depset of cache directories, each holding one library's `<unique name>-cache`.",
    },
)

def _platform_caches(ctx):
    caches = {}
    for cache, target in ctx.attr.platform_caches.items():
        caches.setdefault(target, []).append(cache[KonanCacheInfo].dirs)
    return {target: depset(transitive = dirs) for target, dirs in caches.items()}

def _toolchain_impl(ctx):
    root = ctx.label.workspace_root
    info = KotlinNativeToolchainInfo(
        version = ctx.attr.version,
        host = ctx.attr.host,
        dist_dir = root + "/" + ctx.attr.dist_dir,
        data_dir = root + "/" + ctx.attr.data_dir,
        dist_files = depset(ctx.files.dist),
        java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo],
        launcher = ctx.attr._launcher[JavaInfo].runtime_output_jars[0],
        platform_caches = _platform_caches(ctx),
        targets = {
            target: files[DefaultInfo].files
            for files, target in ctx.attr.targets.items()
        },
    )
    return [platform_common.ToolchainInfo(kotlin_native = info)]

kotlin_native_toolchain = rule(
    implementation = _toolchain_impl,
    attrs = {
        "data_dir": attr.string(mandatory = True),
        "dist": attr.label(mandatory = True),
        "dist_dir": attr.string(mandatory = True),
        "host": attr.string(mandatory = True),
        "platform_caches": attr.label_keyed_string_dict(
            providers = [KonanCacheInfo],
            doc = "Platform klib cache → its konan target: the caches every library cache and test link reads.",
        ),
        "targets": attr.label_keyed_string_dict(mandatory = True),
        "version": attr.string(mandatory = True),
        "_launcher": attr.label(
            default = "//bazel/kmp/native/launcher",
            cfg = "exec",
            providers = [JavaInfo],
        ),
        "_java_runtime": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
            cfg = "exec",
            providers = [java_common.JavaRuntimeInfo],
        ),
    },
)

kotlin_native_stale_toolchain = rule(
    doc = "Stands in for the toolchain while pins.bzl is out of date, and says how to fix it.",
    implementation = lambda ctx: fail(ctx.attr.message),
    attrs = {"message": attr.string()},
)

TOOLCHAIN_TYPE = Label("//bazel/kmp/native:toolchain_type")

# konan targets as the Kotlin Gradle plugin names them (linuxX64, …), which is how BUILD files name them.
KONAN_TARGETS = {
    "linuxArm64": "linux_arm64",
    "linuxX64": "linux_x64",
    "macosArm64": "macos_arm64",
    "mingwX64": "mingw_x64",
}

def kotlin_native_toolchain_info(ctx):
    return ctx.toolchains[TOOLCHAIN_TYPE].kotlin_native

# cinterop's argument parser takes the option's value as the next argument.
_AIRPLANE = {"cinterop": ["-Xoverride-konan-properties", "airplaneMode=true"]}

def run_konan(ctx, tool, target, arguments, inputs, outputs, mnemonic, progress_message, konan_options = True, kn = None):
    """Runs a konan tool (`konanc`, `cinterop`, `klib`, …) for one konan target.

    Mirrors the distribution's `bin/run_konan` launcher without its shell script, so it runs the same on
    every exec OS (`KonanLauncher` fixes up the one path konan needs absolute): the JVM comes from the Java toolchain and konan's data directory is inside the
    toolchain's repository. `airplaneMode=true` makes a missing dependency an error rather than a download.

    Args:
        ctx: the rule context; its rule must list `TOOLCHAIN_TYPE` in `toolchains`.
        tool: the konan tool name.
        target: konan's target name (`linux_x64`).
        arguments: the tool's arguments (a list of `Args` or strings).
        inputs: depset or list of the action's own inputs.
        outputs: the action's outputs.
        mnemonic: the action mnemonic.
        progress_message: the action progress message.
        konan_options: whether the tool takes konan's own options; `clang` and `llvm` pass every argument to
            the LLVM tool, and neither downloads anything.
        kn: the KotlinNativeToolchainInfo to run, for a rule that names its distribution instead of resolving
            the toolchain (`kotlin_native_platform_cache`).
    """
    kn = kn or kotlin_native_toolchain_info(ctx)
    if target not in kn.targets:
        fail("Kotlin/Native target %s doesn't build on host %s" % (target, kn.host))
    _launch(
        ctx,
        kn,
        tool = tool,
        arguments = arguments + (_AIRPLANE.get(tool, ["-Xoverride-konan-properties=airplaneMode=true"]) if konan_options else []),
        inputs = inputs,
        tool_inputs = [kn.dist_files, kn.targets[target]],
        outputs = outputs,
        mnemonic = mnemonic,
        progress_message = progress_message,
    )

def run_kotlinc(ctx, tool, arguments, inputs, outputs, mnemonic, progress_message):
    """Runs one of the other compilers konan's jar carries: `kotlinc-metadata` (the common metadata compiler,
    Gradle's `compileCommonMainKotlinMetadata`), `kotlinc-js` (Kotlin/JS) or `kotlinc-wasm` (Kotlin/Wasm).

    They need none of a target's konan dependencies, so they run on any exec host.

    Args:
        ctx: the rule context; its rule must list `TOOLCHAIN_TYPE` in `toolchains`.
        tool: the compiler (see KonanLauncher's COMPILERS).
        arguments: the compiler's arguments (a list of `Args` or strings).
        inputs: depset or list of the action's own inputs.
        outputs: the action's outputs.
        mnemonic: the action mnemonic.
        progress_message: the action progress message.
    """
    kn = kotlin_native_toolchain_info(ctx)
    _launch(
        ctx,
        kn,
        tool = tool,
        arguments = arguments,
        inputs = inputs,
        tool_inputs = [kn.dist_files],
        outputs = outputs,
        mnemonic = mnemonic,
        progress_message = progress_message,
    )

def _launch(ctx, kn, tool, arguments, inputs, tool_inputs, outputs, mnemonic, progress_message):
    java = kn.java_runtime
    launcher = ctx.actions.args()
    launcher.add_all([
        "-ea",
        "-Xmx3G",
        "-XX:TieredStopAtLevel=1",
        "-Dfile.encoding=UTF-8",
        "--enable-native-access=ALL-UNNAMED",
        "-Dkonan.home=" + kn.dist_dir,
        "-Dkonan.data.dir=" + kn.data_dir,
        "-cp",
        ctx.configuration.host_path_separator.join([
            kn.launcher.path,
            kn.dist_dir + "/konan/lib/kotlin-native-compiler-embeddable.jar",
        ]),
        "com.moneymanager.bazel.kmp.nativelauncher.KonanLauncher",
        tool,
    ])
    for args in arguments:
        if type(args) == "Args":
            # A module's command line can pass Windows' 32767-character limit; KonanLauncher reads these back.
            args.use_param_file("@%s", use_always = True)
            args.set_param_file_format("multiline")
    ctx.actions.run(
        executable = java.java_executable_exec_path,
        arguments = [launcher] + arguments,
        inputs = depset(
            direct = (inputs if type(inputs) == "list" else []) + [kn.launcher],
            transitive = tool_inputs + [java.files] +
                         ([inputs] if type(inputs) == "depset" else []),
        ),
        outputs = outputs,
        env = {
            "KONAN_DATA_DIR": kn.data_dir,
            "LIBCLANG_DISABLE_CRASH_RECOVERY": "1",
        },
        mnemonic = mnemonic,
        progress_message = progress_message,
    )

def static_cache_args(ctx, target, library, libraries, dep_caches, out):
    """konanc's arguments for `library`'s static cache in `out`, built against `dep_caches`' directories.

    `libraries` are the klibs it depends on, but for stdlib and the platform klibs, which konan finds itself.

    konan writes the cache to `<out>/<unique name>-cache`, and a later invocation finds it by that name in any
    `-Xcache-directory`, so no rule needs a library's unique name. `out` comes first: it's where konan writes.
    The cache is debug (`-g`), the kind a test link reads.
    """
    args = ctx.actions.args()
    args.add_all(["-target", target, "-produce", "static_cache", "-g", "-Xadd-cache=" + library])
    args.add("-Xcache-directory=" + out.path)
    args.add_all(dep_caches, format_each = "-Xcache-directory=%s", expand_directories = False)
    args.add_all(libraries, before_each = "-l")
    args.add("-Xpartial-linkage-loglevel=ERROR")
    return args

def _platform_cache_impl(ctx):
    kn = ctx.attr.konan[platform_common.ToolchainInfo].kotlin_native
    deps = depset(transitive = [d[KonanCacheInfo].dirs for d in ctx.attr.deps])
    out = ctx.actions.declare_directory(ctx.label.name)

    # The path konan resolves the library at (konan.home relative), or konan says it isn't among its libraries.
    library = kn.dist_dir + "/" + ctx.attr.path
    run_konan(
        ctx,
        tool = "konanc",
        target = ctx.attr.target,
        arguments = [static_cache_args(ctx, ctx.attr.target, library, [], deps, out)],
        inputs = deps,
        outputs = [out],
        mnemonic = "KotlinNativeCache",
        progress_message = "Caching Kotlin/Native %s for %s" % (ctx.attr.path.rpartition("/")[2], ctx.attr.target),
        kn = kn,
    )
    return [KonanCacheInfo(cacheable = True, dirs = depset([out], transitive = [deps]))]

kotlin_native_platform_cache = rule(
    implementation = _platform_cache_impl,
    doc = "The static cache of one of the distribution's klibs (stdlib or a platform klib), for one target.",
    attrs = {
        "deps": attr.label_list(providers = [KonanCacheInfo], doc = "The caches of the klibs it depends on."),
        "konan": attr.label(mandatory = True, doc = "The distribution's toolchain, without these caches."),
        "path": attr.string(mandatory = True, doc = "The klib, relative to the distribution (`klib/common/stdlib`)."),
        "target": attr.string(mandatory = True, doc = "konan's target name (`macos_arm64`)."),
    },
)
