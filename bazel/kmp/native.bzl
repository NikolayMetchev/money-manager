"""Kotlin/Native compile and test-link rules (#999), the native counterpart of the JVM compile in defs.bzl.

A module compiles to one klib per konan target in a single K2 invocation over its fragments, like KGP:
`common ← native ← linux|apple|mingw ← <target>` (the default hierarchy template), leaving out empty ones.
Tests compile to their own klib (with the main klib as a friend, so they see `internal`), and are linked
into a program with `-generate-test-runner`.
"""

load("//bazel/kmp/native:toolchain.bzl", "KONAN_TARGETS", "TOOLCHAIN_TYPE", "run_konan")

KlibInfo = provider(
    doc = "A Kotlin/Native library for one konan target.",
    fields = {
        "klib": "The .klib file.",
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

def _fragment_args(args, prefix, chain):
    """Adds -Xmulti-platform fragment flags for a `[(fragment, files), …]` chain, root first."""
    present = [(fragment, files) for fragment, files in chain if files]
    args.add("-Xmulti-platform")
    args.add("-Xfragments=" + ",".join([prefix + f for f, _ in present]))
    for i in range(1, len(present)):
        args.add("-Xfragment-refines=%s%s:%s%s" % (prefix, present[i][0], prefix, present[i - 1][0]))
    for fragment, files in present:
        for f in files:
            args.add("-Xfragment-sources=%s%s:%s" % (prefix, fragment, f.path))
    return [f for _, files in present for f in files]

def _compile_klib(ctx, kgp_target, out, chain, fragment_suffix, deps, friends, module_name, short_name):
    target = KONAN_TARGETS[kgp_target]
    transitive = depset(transitive = [d[KlibInfo].transitive_klibs for d in deps + friends])
    args = ctx.actions.args()
    args.add_all(["-target", target, "-produce", "library", "-o", out])
    # KGP compiles every klib as debug, whatever the binary that later links it.
    args.add_all(["-g", "-enable-assertions", "-Werror", "-Xexpect-actual-classes"])
    args.add_all(["-module-name", module_name, "-Xshort-module-name=" + short_name])

    # konanc names fragments by source set (commonMain, linuxX64Test, …), as KGP does.
    srcs = _fragment_args(args, "", [(f + fragment_suffix, files) for f, files in chain])

    # -Xfragment-sources only says which fragment a file is in; the file is compiled because it's listed.
    args.add_all(srcs)
    args.add_all(transitive, before_each = "-library")
    if friends:
        args.add_joined("-friend-modules", [f[KlibInfo].klib for f in friends], join_with = ctx.configuration.host_path_separator)
    run_konan(
        ctx,
        tool = "konanc",
        target = target,
        arguments = [args],
        inputs = depset(srcs, transitive = [transitive]),
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
        ("common", ctx.files.common_srcs),
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
    out = ctx.actions.declare_file(ctx.label.name + ".klib")
    info = _compile_klib(
        ctx,
        kgp_target = ctx.attr.target,
        out = out,
        chain = _chain(ctx, ctx.attr.target),
        fragment_suffix = "Main",
        deps = ctx.attr.deps,
        friends = [],
        module_name = _unique_name(ctx.label, ""),
        short_name = ctx.attr.module,
    )
    return [DefaultInfo(files = depset([out])), info]

_SRC_ATTRS = {
    "common_srcs": attr.label_list(allow_files = [".kt"]),
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
    )

    target = KONAN_TARGETS[ctx.attr.target]
    exe = ctx.actions.declare_file(ctx.label.name + (".exe" if target == "mingw_x64" else ".kexe"))
    args = ctx.actions.args()
    args.add_all(["-target", target, "-produce", "program", "-o", exe])
    args.add_all(["-g", "-enable-assertions", "-Werror", "-generate-test-runner"])
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

def _import_impl(ctx):
    deps = [d[KlibInfo].transitive_klibs for d in ctx.attr.deps]
    return [
        DefaultInfo(files = depset([ctx.file.klib])),
        KlibInfo(
            klib = ctx.file.klib,
            target = KONAN_TARGETS[ctx.attr.target],
            transitive_klibs = depset([ctx.file.klib], transitive = deps),
        ),
    ]

kt_native_import = rule(
    implementation = _import_impl,
    doc = "A prebuilt klib (a Maven library's native variant).",
    attrs = {
        "deps": attr.label_list(providers = [KlibInfo]),
        "klib": attr.label(mandatory = True, allow_single_file = [".klib"]),
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
