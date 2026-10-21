"""ktlint and detekt as tests (#1015): what Gradle's `check` runs through the ktlint and detekt plugins.

Each module's hand-written sources get `:<name>_ktlint` and `:<name>_detekt`, the tool's own `main` run as a
java_test, so a finding fails `bazel test` with the tool's report and nothing needs a shell. Generated sources
are left out, as the Gradle plugins' filters do. ktlint reads the tree's `.editorconfig` files (a module's
own and those above it), so it applies `ktlint_official` with their overrides, as ktlint-gradle does. detekt
reads the root `detekt.yml` over its defaults (`buildUponDefaultConfig`) without type resolution, as Gradle's
per-source-set detekt tasks do.
"""

load("@rules_java//java:java_test.bzl", "java_test")

_KTLINT = Label("@ktlint_cli//:com_pinterest_ktlint_ktlint_cli")
_DETEKT = Label("@detekt_cli//:dev_detekt_detekt_cli")
_DETEKT_CONFIG = Label("//:detekt.yml")
_EDITORCONFIGS = Label("//:editorconfigs")

def _argfile_impl(ctx):
    paths = [f.short_path for f in ctx.files.srcs]
    if ctx.attr.tool == "ktlint":
        lines = paths
    else:
        # One --input per path: detekt splits a single one on File.pathSeparatorChar, which is `;` on Windows.
        lines = [arg for path in paths for arg in ["--input", path]]
        lines += ["--config", ctx.file.config.short_path, "--build-upon-default-config"]
    out = ctx.actions.declare_file(ctx.label.name + ".args")
    ctx.actions.write(out, "\n".join(lines) + "\n")
    return [DefaultInfo(files = depset([out]))]

# The tools' command lines go in an argument file (`@file`, which clikt and JCommander both read), as a module's
# sources can pass Windows' command-line limit.
_argfile = rule(
    implementation = _argfile_impl,
    attrs = {
        "config": attr.label(allow_single_file = True),
        "srcs": attr.label_list(allow_files = [".kt"]),
        "tool": attr.string(values = ["ktlint", "detekt"]),
    },
)

def _lint_test(name, tool, srcs, main_class, runtime_dep, config = None, data = []):
    _argfile(name = name + "_args", srcs = srcs, tool = tool, config = config, testonly = True)
    java_test(
        name = name,
        size = "small",
        main_class = main_class,
        use_testrunner = False,
        args = ["@$(rootpath :%s_args)" % name],
        data = [":%s_args" % name] + srcs + ([config] if config else []) + data,
        runtime_deps = [runtime_dep],
    )

def kmp_lint(name, srcs):
    """`:<name>_ktlint` and `:<name>_detekt` over srcs, the module's hand-written `.kt` files.

    Args:
        name: the kmp_library or kmp_test the sources belong to.
        srcs: its hand-written sources, every source set's.
    """
    if not srcs:
        return
    # ktlint reads every .editorconfig above a file (app/terminal's lets Mosaic composables be PascalCase).
    editorconfigs = native.glob([".editorconfig"], allow_empty = True) + [_EDITORCONFIGS]
    _lint_test(name + "_ktlint", "ktlint", srcs, "com.pinterest.ktlint.Main", _KTLINT, data = editorconfigs)
    _lint_test(name + "_detekt", "detekt", srcs, "dev.detekt.cli.Main", _DETEKT, config = _DETEKT_CONFIG)
