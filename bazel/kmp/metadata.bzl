"""The commonMain metadata compile (#1010): Gradle's `compileCommonMainKotlinMetadata`.

Each platform compile checks commonMain beside that platform's own fragments, against that platform's
libraries, so an API only some platforms have (`java.util.regex`, `RegexOption.DOT_MATCHES_ALL`) passes every
one of them. Gradle catches it by compiling commonMain on its own against each dependency's common metadata;
so does `<module>_metadata`, which every module with native targets gets.

A Maven library's common metadata is a jar of one unpacked klib per source set; `@maven_kmp` extracts the
ones our targets all see (the resolver's `source_sets`), and a module's own compiles to an unpacked klib. The
compiler is konan's (`run_kotlin_metadata`), so the plugins are the native ones.
"""

load("//bazel/kmp/native:toolchain.bzl", "TOOLCHAIN_TYPE", "run_kotlin_metadata")
load("//bazel/kmp/plugins:plugins.bzl", "NativePluginInfo", "native_plugin_args")

KtMetadataInfo = provider(
    doc = "Common metadata klibs: a module's or library's, and everything it needs.",
    fields = {
        "classpath": "depset of klib directory paths, for -classpath.",
        "files": "depset of the files under them, the compile's inputs.",
    },
)

_MANIFEST = "/default/manifest"

def _merge(deps, paths = [], files = []):
    return KtMetadataInfo(
        classpath = depset(paths, transitive = [d[KtMetadataInfo].classpath for d in deps]),
        files = depset(files, transitive = [d[KtMetadataInfo].files for d in deps]),
    )

def _import_impl(ctx):
    paths = []
    for source_set in ctx.attr.source_sets:
        manifests = [f.path for f in source_set.files.to_list() if f.path.endswith(_MANIFEST)]
        if len(manifests) != 1:
            fail("%s: want one klib, found manifests %s" % (source_set.label, manifests))
        paths.append(manifests[0][:-len(_MANIFEST)])
    return [_merge(ctx.attr.deps, paths, ctx.files.source_sets)]

kt_metadata_import = rule(
    implementation = _import_impl,
    doc = "A Maven library's common metadata: the klib directories of the source sets our targets see.",
    attrs = {
        "deps": attr.label_list(providers = [KtMetadataInfo]),
        "source_sets": attr.label_list(allow_files = True, doc = "One filegroup per source set's klib directory."),
    },
    provides = [KtMetadataInfo],
)

def _group_impl(ctx):
    return [_merge(ctx.attr.deps)]

kt_metadata_group = rule(
    implementation = _group_impl,
    doc = "Several libraries' metadata under one label (a module that only redirects to another).",
    attrs = {"deps": attr.label_list(providers = [KtMetadataInfo])},
    provides = [KtMetadataInfo],
)

def _library_impl(ctx):
    deps = ctx.attr.deps + [ctx.attr._stdlib]
    srcs = ctx.files.srcs + ctx.files.generated
    if not srcs:
        # Nothing of its own to check (a cinterop-only module); dependents see its dependencies.
        return [DefaultInfo(), _merge(deps)]
    out = ctx.actions.declare_directory(ctx.label.name)
    transitive = _merge(deps)
    args = ctx.actions.args()
    args.add("-d", out.path)
    args.add_all(["-module-name", ctx.attr.module])

    # Common code compiles for every platform; KGP names them all, and -Werror holds as in the platform compiles.
    args.add_all(["-Xmulti-platform", "-Xtarget-platform=JVM,Native", "-Werror", "-Xexpect-actual-classes"])
    plugin_jars = native_plugin_args(args, ctx.attr.plugins)
    args.add_joined("-classpath", transitive.classpath, join_with = ctx.configuration.host_path_separator)
    args.add_all(srcs, expand_directories = True)
    run_kotlin_metadata(
        ctx,
        arguments = [args],
        inputs = depset(srcs + plugin_jars, transitive = [transitive.files]),
        outputs = [out],
        mnemonic = "KotlinMetadataCompile",
        progress_message = "Compiling commonMain metadata %{label}",
    )
    return [
        DefaultInfo(files = depset([out])),
        _merge(deps, [out.path], [out]),
    ]

kt_metadata_library = rule(
    implementation = _library_impl,
    doc = "A module's commonMain compiled alone, against its dependencies' common metadata.",
    attrs = {
        "deps": attr.label_list(providers = [KtMetadataInfo]),
        "generated": attr.label_list(allow_files = True, doc = "Directories of generated commonMain sources."),
        "module": attr.string(mandatory = True, doc = "The klib's unique name."),
        "plugins": attr.label_list(providers = [NativePluginInfo], doc = "Compiler plugins, in the order they run."),
        "srcs": attr.label_list(allow_files = [".kt"]),
        "_stdlib": attr.label(default = "@maven_kmp//:org_jetbrains_kotlin_kotlin_stdlib_metadata", providers = [KtMetadataInfo]),
    },
    provides = [KtMetadataInfo],
    toolchains = [TOOLCHAIN_TYPE],
)
