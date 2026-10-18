"""Kotlin/Native compile and test-link rules (#999), the native counterpart of the JVM compile in defs.bzl.

A module compiles to one klib per konan target in a single K2 invocation over its fragments, like KGP:
`common ← native ← linux|apple|mingw ← <target>` (the default hierarchy template), leaving out empty ones.
Tests compile to their own klib (with the main klib as a friend, so they see `internal`), and are linked
into a program with `-generate-test-runner`.
"""

load("//bazel/kmp/native:toolchain.bzl", "KONAN_TARGETS", "TOOLCHAIN_TYPE", "run_konan")
load("//bazel/kmp/plugins:plugins.bzl", "NativePluginInfo", "native_plugin_args")

KlibInfo = provider(
    doc = "A Kotlin/Native library for one konan target.",
    fields = {
        "klib": "The .klib file, or None for a module with no Kotlin of its own (only cinterops).",
        "target": "konan's target name (`linux_x64`).",
        "transitive_klibs": "depset of this klib and every klib it needs, for -library.",
    },
)

# The intermediate source set each target refines in the default hierarchy template.
FAMILIES = {
    "linuxArm64": "linux",
    "linuxX64": "linux",
    "macosArm64": "apple",
    "mingwX64": "mingw",
}

def _fragment_args(args, chain):
    """Adds -Xmulti-platform fragment flags and the sources for the `[(fragment, files), …]` chain, root first.

    A file may be a directory of generated sources (SQLDelight's), expanded when the action runs.
    """
    # Every source set of the default hierarchy, with sources or not, as KGP names them (see defs.bzl).
    present = chain
    args.add("-Xmulti-platform")
    args.add("-Xfragments=" + ",".join([f for f, _ in present]))
    for i in range(1, len(present)):
        args.add("-Xfragment-refines=%s:%s" % (present[i][0], present[i - 1][0]))
    for fragment, files in present:
        args.add_all(files, format_each = "-Xfragment-sources=" + fragment + ":%s", expand_directories = True)

    # -Xfragment-sources only says which fragment a file is in; the file is compiled because it's listed.
    srcs = [f for _, files in present for f in files]
    args.add_all(srcs, expand_directories = True)
    return srcs

def _compile_klib(ctx, kgp_target, out, chain, fragment_suffix, deps, friends, module_name, short_name, plugins):
    target = KONAN_TARGETS[kgp_target]
    transitive = depset(transitive = [d[KlibInfo].transitive_klibs for d in deps + friends])
    args = ctx.actions.args()
    args.add_all(["-target", target, "-produce", "library", "-o", out])
    # KGP compiles every klib as debug, whatever the binary that later links it.
    args.add_all(["-g", "-enable-assertions", "-Werror", "-Xexpect-actual-classes"])
    args.add_all(["-module-name", module_name, "-Xshort-module-name=" + short_name])
    plugin_jars = native_plugin_args(args, plugins)

    # konanc names fragments by source set (commonMain, linuxX64Test, …), as KGP does.
    srcs = _fragment_args(args, [(f + fragment_suffix, files) for f, files in chain])
    args.add_all(transitive, before_each = "-library")
    friend_klibs = [f[KlibInfo].klib for f in friends if f[KlibInfo].klib]
    if friend_klibs:
        args.add_joined("-friend-modules", friend_klibs, join_with = ctx.configuration.host_path_separator)
    run_konan(
        ctx,
        tool = "konanc",
        target = target,
        arguments = [args],
        inputs = depset(srcs + plugin_jars, transitive = [transitive]),
        outputs = [out],
        mnemonic = "KotlinNativeCompile",
        progress_message = "Compiling Kotlin/Native %{label} for " + kgp_target,
    )
    return KlibInfo(
        klib = out,
        target = target,
        transitive_klibs = depset([out], transitive = [transitive]),
    )

def _chain(ctx, kgp_target):
    return [
        ("common", ctx.files.common_srcs + ctx.files.common_generated),
        ("native", ctx.files.native_srcs),
        (FAMILIES[kgp_target], ctx.files.family_srcs),
        (kgp_target, ctx.files.target_srcs),
    ]

def _unique_name(label, suffix):
    """KGP's klib unique name: `utils.bigdecimal:utils-bigdecimal` for //utils/bigdecimal (#999 keeps the
    native-convention's naming after the full project path, so same-named modules never clash)."""
    package = label.package
    return "%s:%s%s" % (package.replace("/", "."), package.replace("/", "-"), suffix)

def _klib_impl(ctx):
    chain = _chain(ctx, ctx.attr.target)
    if not [f for _, files in chain for f in files]:
        # No Kotlin of its own (only a cinterop): KGP's compile is NO-SOURCE and the module is its dependencies.
        return [DefaultInfo(), KlibInfo(
            klib = None,
            target = KONAN_TARGETS[ctx.attr.target],
            transitive_klibs = depset(transitive = [d[KlibInfo].transitive_klibs for d in ctx.attr.deps]),
        )]
    out = ctx.actions.declare_file(ctx.label.name + ".klib")
    info = _compile_klib(
        ctx,
        kgp_target = ctx.attr.target,
        out = out,
        chain = chain,
        fragment_suffix = "Main",
        deps = ctx.attr.deps,
        friends = [],
        module_name = _unique_name(ctx.label, ""),
        short_name = ctx.attr.module,
        plugins = ctx.attr.plugins,
    )
    return [DefaultInfo(files = depset([out])), info]

_SRC_ATTRS = {
    "common_generated": attr.label_list(allow_files = True, doc = "Directories of generated commonMain sources."),
    "common_srcs": attr.label_list(allow_files = [".kt"]),
    "plugins": attr.label_list(providers = [NativePluginInfo], doc = "Compiler plugins, in the order they run."),
    "family_srcs": attr.label_list(allow_files = [".kt"], doc = "linuxMain, appleMain or mingwMain."),
    "native_srcs": attr.label_list(allow_files = [".kt"]),
    "target": attr.string(mandatory = True, values = KONAN_TARGETS.keys()),
    "target_srcs": attr.label_list(allow_files = [".kt"], doc = "The target's own source set (linuxX64Main)."),
}

kt_native_klib = rule(
    implementation = _klib_impl,
    doc = "A module's klib for one Kotlin/Native target.",
    attrs = _SRC_ATTRS | {
        "deps": attr.label_list(providers = [KlibInfo]),
        "module": attr.string(mandatory = True, doc = "The short module name (Gradle's project name)."),
    },
    provides = [KlibInfo],
    toolchains = [TOOLCHAIN_TYPE],
)

def _test_impl(ctx):
    test_klib = ctx.actions.declare_file(ctx.label.name + "_test.klib")
    associate = ctx.attr.associate[KlibInfo]
    info = _compile_klib(
        ctx,
        kgp_target = ctx.attr.target,
        out = test_klib,
        chain = _chain(ctx, ctx.attr.target),
        fragment_suffix = "Test",
        deps = ctx.attr.deps,
        friends = [ctx.attr.associate],
        module_name = _unique_name(ctx.attr.associate.label, "_test"),
        short_name = ctx.attr.module + "_test",
        plugins = ctx.attr.plugins,
    )

    target = KONAN_TARGETS[ctx.attr.target]
    exe = ctx.actions.declare_file(ctx.label.name + (".exe" if target == "mingw_x64" else ".kexe"))
    args = ctx.actions.args()
    args.add_all(["-target", target, "-produce", "program", "-o", exe])
    args.add_all(["-g", "-enable-assertions", "-Werror", "-generate-test-runner"])
    if associate.klib:
        args.add("-friend-modules", associate.klib)
    args.add("-Xinclude=" + test_klib.path)

    # Every library the tests need except the test klib itself, which -Xinclude names.
    libraries = depset(transitive = [d[KlibInfo].transitive_klibs for d in ctx.attr.deps] + [associate.transitive_klibs])
    args.add_all(libraries, before_each = "-library")

    # A library missing from the link would otherwise only fail when the test reaches it (partial linkage
    # turns unlinked symbols into runtime errors); our rules, not KGP, decide what's linked, so fail here.
    args.add("-Xpartial-linkage-loglevel=ERROR")
    run_konan(
        ctx,
        tool = "konanc",
        target = target,
        arguments = [args],
        inputs = info.transitive_klibs,
        outputs = [exe],
        mnemonic = "KotlinNativeLink",
        progress_message = "Linking Kotlin/Native tests %{label} for " + ctx.attr.target,
    )
    return [DefaultInfo(files = depset([exe]), executable = exe)]

kt_native_test_binary = rule(
    implementation = _test_impl,
    doc = "A module's tests for one Kotlin/Native target, linked with konan's test runner.",
    attrs = _SRC_ATTRS | {
        "associate": attr.label(mandatory = True, providers = [KlibInfo]),
        "deps": attr.label_list(providers = [KlibInfo]),
        "module": attr.string(mandatory = True),
    },
    executable = True,
    toolchains = [TOOLCHAIN_TYPE],
)

# The platform a cross-linked test binary is built for, per target that every host can link (BUILDS_ON).
CROSS_PLATFORMS = {
    "linuxArm64": Label("//bazel/platforms:linux_arm64"),
    "mingwX64": Label("//bazel/platforms:windows_x64"),
}

def _cross_transition_impl(_settings, attr):
    return {"//command_line_option:platforms": [str(attr.platform)]}

_cross_transition = transition(
    implementation = _cross_transition_impl,
    inputs = [],
    outputs = ["//command_line_option:platforms"],
)

def _cross_impl(ctx):
    return [DefaultInfo(files = depset(ctx.files.binary))]

# Kotlin/Native has no Linux arm64 host, and a Windows runner is slow to build on, so CI links these on Linux x64
# in its one `bazel build` and runs them on a runner of their own; the transition saves that build a second
# invocation with `--platforms`.
kt_native_cross_test_binary = rule(
    implementation = _cross_impl,
    doc = "A test binary built for `platform` from whatever platform the build targets.",
    attrs = {
        "binary": attr.label(mandatory = True, cfg = _cross_transition),
        "platform": attr.label(mandatory = True),
    },
)

def _import_impl(ctx):
    deps = [d[KlibInfo].transitive_klibs for d in ctx.attr.deps]
    return [
        DefaultInfo(files = depset(ctx.files.klibs)),
        KlibInfo(
            klib = ctx.files.klibs[0],
            target = KONAN_TARGETS[ctx.attr.target],
            transitive_klibs = depset(ctx.files.klibs, transitive = deps),
        ),
    ]

kt_native_import = rule(
    implementation = _import_impl,
    doc = "A prebuilt library: a Maven library's native variant, one klib plus any cinterop klibs it ships.",
    attrs = {
        "deps": attr.label_list(providers = [KlibInfo]),
        "klibs": attr.label_list(mandatory = True, allow_files = [".klib"]),
        "target": attr.string(mandatory = True, values = KONAN_TARGETS.keys()),
    },
    provides = [KlibInfo],
)

def _group_impl(ctx):
    return [KlibInfo(
        klib = None,
        target = KONAN_TARGETS[ctx.attr.target],
        transitive_klibs = depset(transitive = [d[KlibInfo].transitive_klibs for d in ctx.attr.deps]),
    )]

kt_native_group = rule(
    implementation = _group_impl,
    doc = "Several klibs under one label, or none: what a JVM-only library (kotlin-test's JUnit binding) is on native.",
    attrs = {
        "deps": attr.label_list(providers = [KlibInfo]),
        "target": attr.string(mandatory = True, values = KONAN_TARGETS.keys()),
    },
    provides = [KlibInfo],
)

# Where a target's klibs can be compiled: Apple targets need the macOS distribution (#1009 may lift this).
BUILDS_ON = {
    "macosArm64": ["@platforms//os:macos"],
}

# Where a target's test executable can run.
RUNS_ON = {
    "linuxArm64": ["@platforms//os:linux", "@platforms//cpu:aarch64"],
    "linuxX64": ["@platforms//os:linux", "@platforms//cpu:x86_64"],
    "macosArm64": ["@platforms//os:macos", "@platforms//cpu:aarch64"],
    "mingwX64": ["@platforms//os:windows", "@platforms//cpu:x86_64"],
}

def _static_library_impl(ctx):
    target = KONAN_TARGETS[ctx.attr.target]
    out = ctx.actions.declare_file("%s/lib%s.a" % (ctx.label.name, ctx.attr.library_name or ctx.label.name))
    objects = []
    for src in ctx.files.srcs:
        obj = ctx.actions.declare_file("%s/_objs/%s.o" % (ctx.label.name, src.basename))
        args = ctx.actions.args()
        args.add_all(["clang", target, "-c", "-O2"])
        args.add_all(ctx.attr.defines, format_each = "-D%s")
        args.add_all(depset([h.dirname for h in ctx.files.hdrs]), format_each = "-I%s")
        args.add_all([src, "-o", obj])
        run_konan(
            ctx,
            tool = "clang",
            target = target,
            arguments = [args],
            inputs = [src] + ctx.files.hdrs,
            outputs = [obj],
            mnemonic = "KonanClang",
            progress_message = "Compiling %s for %s" % (src.short_path, ctx.attr.target),
            konan_options = False,
        )
        objects.append(obj)
    args = ctx.actions.args()
    args.add_all(["llvm-ar", "rcs", out])
    args.add_all(objects)
    run_konan(
        ctx,
        tool = "llvm",
        target = target,
        arguments = [args],
        inputs = objects,
        outputs = [out],
        mnemonic = "KonanArchive",
        progress_message = "Archiving %{output}",
        konan_options = False,
    )
    return [DefaultInfo(files = depset([out]))]

konan_static_library = rule(
    implementation = _static_library_impl,
    doc = """A C static library for one Kotlin/Native target, compiled with the distribution's own clang and
sysroot (`run_konan clang`), so it links into that target's binaries like the klibs beside it.""",
    attrs = {
        "defines": attr.string_list(doc = "`NAME=VALUE` preprocessor definitions."),
        "hdrs": attr.label_list(allow_files = [".h"], doc = "Headers; their directories are on the include path."),
        "library_name": attr.string(doc = "The archive is `lib<library_name>.a` (default: the target name)."),
        "srcs": attr.label_list(allow_files = [".c"], mandatory = True),
        "target": attr.string(mandatory = True, values = KONAN_TARGETS.keys()),
    },
    toolchains = [TOOLCHAIN_TYPE],
)

def _cinterop_impl(ctx):
    target = KONAN_TARGETS[ctx.attr.target]
    out = ctx.actions.declare_file(ctx.label.name + ".klib")
    deps = depset(transitive = [d[KlibInfo].transitive_klibs for d in ctx.attr.deps])
    args = ctx.actions.args()
    args.add_all(["-target", target, "-def", ctx.file.def_file, "-o", out])
    args.add_all(["-Xmodule-name", _unique_name(ctx.label, "-cinterop-" + ctx.attr.interop)])
    for lib in ctx.files.static_libraries:
        # cinterop embeds each -staticLibrary it finds on -libraryPath into the klib, which every binary then links.
        args.add_all(["-libraryPath", lib.dirname, "-staticLibrary", lib.basename])
    args.add_all(deps, before_each = "-library")
    run_konan(
        ctx,
        tool = "cinterop",
        target = target,
        arguments = [args],
        inputs = depset([ctx.file.def_file] + ctx.files.static_libraries, transitive = [deps]),
        outputs = [out],
        mnemonic = "KotlinNativeCinterop",
        progress_message = "Generating cinterop %{label} for " + ctx.attr.target,
    )
    return [
        DefaultInfo(files = depset([out])),
        KlibInfo(klib = out, target = target, transitive_klibs = depset([out], transitive = [deps])),
    ]

kt_native_cinterop = rule(
    implementation = _cinterop_impl,
    doc = "A cinterop klib for one Kotlin/Native target: KGP's `cinterops.create(<interop>)` on a compilation.",
    attrs = {
        "def_file": attr.label(mandatory = True, allow_single_file = [".def"]),
        "deps": attr.label_list(providers = [KlibInfo]),
        "interop": attr.string(mandatory = True, doc = "The interop's name, as KGP's `cinterops.create` names it."),
        "static_libraries": attr.label_list(allow_files = [".a", ".lib"], doc = "Static libraries the klib embeds."),
        "target": attr.string(mandatory = True, values = KONAN_TARGETS.keys()),
    },
    provides = [KlibInfo],
    toolchains = [TOOLCHAIN_TYPE],
)

def _executable_impl(ctx):
    library = ctx.attr.library[KlibInfo]
    target = KONAN_TARGETS[ctx.attr.target]
    exe = ctx.actions.declare_file("%s/%s.%s" % (ctx.label.name, ctx.attr.base_name, "exe" if target == "mingw_x64" else "kexe"))
    args = ctx.actions.args()
    args.add_all(["-target", target, "-produce", "program", "-o", exe, "-entry", ctx.attr.entry_point])

    # KGP's release link: optimised, no debug info. It never uses the compiler caches (clikt and clikt-mordant
    # both define a private Context.selfAndAncestors(), a duplicate symbol in a cached link).
    args.add_all(["-opt", "-Werror", "-Xmulti-platform", "-Xexpect-actual-classes"])
    args.add("-Xinclude=" + library.klib.path)
    args.add_all([k for k in library.transitive_klibs.to_list() if k != library.klib], before_each = "-library")
    args.add("-Xpartial-linkage-loglevel=ERROR")
    run_konan(
        ctx,
        tool = "konanc",
        target = target,
        arguments = [args],
        inputs = library.transitive_klibs,
        outputs = [exe],
        mnemonic = "KotlinNativeLink",
        progress_message = "Linking %{output}",
    )
    return [DefaultInfo(files = depset([exe]), executable = exe)]

kt_native_executable = rule(
    implementation = _executable_impl,
    doc = "A release executable for one Kotlin/Native target: KGP's `binaries.executable(RELEASE)`.",
    attrs = {
        "base_name": attr.string(mandatory = True, doc = "The file name, without `.kexe`/`.exe`."),
        "entry_point": attr.string(mandatory = True, doc = "The fully qualified `main` function."),
        "library": attr.label(mandatory = True, providers = [KlibInfo], doc = "The klib whose code is the program."),
        "target": attr.string(mandatory = True, values = KONAN_TARGETS.keys()),
    },
    executable = True,
    toolchains = [TOOLCHAIN_TYPE],
)
