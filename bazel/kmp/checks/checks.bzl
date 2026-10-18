"""Gradle's custom module checks as Bazel rules (#1014). Both fail `bazel build`, as their tasks fail `check`.

- `kmp_forbidden_deps` is `verifyNoDbDependency`: the pure importers (and their tests) must never depend on
  the database, DI or UI modules, even transitively. It fails at analysis, naming the module and the path.
- `kmp_reference_check` is the source half of `verifyNoWriteRepositoryUsage`: inside a module that may depend
  on the write repositories (`app/importer`), only the listed files may name one. Elsewhere the write module's
  `visibility` already stops it.
- `kmp_unused_deps` is DAGP's unused-dependency advice (#1016): every `kmp_library`/`kmp_test` gets
  `:<name>_unused_deps`, which fails when the JVM compile's jdeps used none of a declared dependency's own jars.
  `keep_deps` (the `kmp_keep_dep` directives) are exempt, and so are exports, which generated code uses where
  jdeps can't see it. Its other half, a dependency used but not declared, is rules_kotlin's strict deps.

`kmp_library(forbidden_deps = …, forbidden_reference = …)` and `kmp_test(forbidden_deps = …)` create them, from
the `kmp_forbid_deps` / `kmp_forbid_reference` Gazelle directives.
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

_Reached = provider(
    doc = "This repository's targets a target reaches, with the path to each (for the error message).",
    fields = {"paths": "depset of `//a -> //b -> //c` strings, one per reached target."},
)

def _children(attr_value):
    if type(attr_value) == "Target":
        return [attr_value]
    if type(attr_value) == "list":
        return [v for v in attr_value if type(v) == "Target"]
    return []

def _reached_impl(target, ctx):
    if target.label.workspace_name:
        return [_Reached(paths = depset())]
    label = str(target.label)
    children = []
    for name in dir(ctx.rule.attr):
        for child in _children(getattr(ctx.rule.attr, name)):
            if _Reached in child:
                children.append(child[_Reached].paths)
    paths = depset(transitive = children)
    # Prefix this target onto each path below it, and add itself.
    return [_Reached(paths = depset([label] + [label + " -> " + p for p in paths.to_list()]))]

_reached = aspect(
    implementation = _reached_impl,
    attr_aspects = ["*"],
    doc = "Every target of this repository a target reaches (external repositories are never ours).",
)

def _forbidden_deps_impl(ctx):
    forbidden = ["//" + Label(f).package for f in ctx.attr.forbidden]
    shortest = {}  # forbidden target → the shortest path to it
    for target in ctx.attr.targets:
        for path in target[_Reached].paths.to_list():
            path = path.replace("@@//", "//")
            last = path.rpartition(" -> ")[2]
            if "//" + last.removeprefix("//").partition(":")[0] not in forbidden:
                continue
            if last not in shortest or len(path) < len(shortest[last]):
                shortest[last] = path
    offenders = shortest.values()
    if offenders:
        fail("\n%s must not depend on %s (CLAUDE.md, \"Database Writes\": the pure importers only build an " %
             (ctx.attr.module, ", ".join(ctx.attr.forbidden)) +
             "ImportBatch), but it does:\n  " + "\n  ".join(sorted(offenders)) + "\n")
    out = ctx.actions.declare_file(ctx.label.name + ".ok")
    ctx.actions.write(out, "")
    return [DefaultInfo(files = depset([out]))]

kmp_forbidden_deps = rule(
    implementation = _forbidden_deps_impl,
    doc = "Fails analysis when `targets` reach a package of `forbidden`, directly or not.",
    attrs = {
        "forbidden": attr.string_list(mandatory = True, doc = "Module labels (`//app/db/core`): their whole package."),
        "module": attr.string(mandatory = True, doc = "The module, for the message."),
        "targets": attr.label_list(aspects = [_reached], doc = "The module's per-platform targets."),
    },
)

def _reference_check_impl(ctx):
    package = ctx.label.package + "/"
    allowed = {a: True for a in ctx.attr.allowed}
    files = [f for f in ctx.files.srcs if f.short_path.removeprefix(package) not in allowed]
    out = ctx.actions.declare_file(ctx.label.name + ".ok")
    args = ctx.actions.args()
    args.add("--regex", ctx.attr.regex)
    args.add("--reason", ctx.attr.reason)
    args.add("--module", "//" + ctx.label.package)
    args.add("--strip", package)
    args.add("--out", out)
    args.add_all(files)
    ctx.actions.run(
        executable = ctx.executable._tool,
        arguments = [args],
        inputs = files,
        outputs = [out],
        mnemonic = "KmpReferenceCheck",
        progress_message = "Checking %{label} for forbidden references",
    )
    return [DefaultInfo(files = depset([out]))]

kmp_reference_check = rule(
    implementation = _reference_check_impl,
    doc = "Fails the build when a source outside `allowed` matches `regex` on a line that isn't a comment.",
    attrs = {
        "allowed": attr.string_list(doc = "Module-relative paths exempt from the check."),
        "reason": attr.string(mandatory = True, doc = "Why, for the message."),
        "regex": attr.string(mandatory = True, doc = "A Go (RE2) regular expression."),
        "srcs": attr.label_list(allow_files = [".kt"]),
        "_tool": attr.label(default = "//bazel/kmp/checks/reference", executable = True, cfg = "exec"),
    },
)

_OwnJarsInfo = provider(doc = "The jars a dependency is.", fields = ["jars"])

def _own_jars_aspect_impl(target, ctx):
    info = target[JavaInfo]
    own = [o.compile_jar for o in info.java_outputs if o.compile_jar] + [o.class_jar for o in info.java_outputs if o.class_jar]

    # A library with no sources of its own (kotlin_test: junit and kotlin-test together) is the jars it exports.
    if not own or (hasattr(ctx.rule.attr, "srcs") and not ctx.rule.attr.srcs):
        own = info.compile_jars.to_list()
    return [_OwnJarsInfo(jars = own)]

# The jars a dependency is, not what it re-exports (rules_kotlin's own unused-deps check counts those).
_own_jars_aspect = aspect(implementation = _own_jars_aspect_impl, required_providers = [JavaInfo])

def _unused_deps_impl(ctx):
    lines = []
    for name, dep in zip(ctx.attr.names, ctx.attr.deps):
        own = dep[_OwnJarsInfo].jars
        lines.append("\t".join([name] + [j.path for j in own]))
    deps_file = ctx.actions.declare_file(ctx.label.name + ".deps")
    ctx.actions.write(deps_file, "\n".join(lines) + "\n")
    jdeps = ctx.attr.target[JavaInfo].outputs.jdeps
    out = ctx.actions.declare_file(ctx.label.name + ".ok")
    args = ctx.actions.args()
    args.add("--jdeps", jdeps)
    args.add("--deps", deps_file)
    args.add("--module", ctx.attr.module)
    args.add("--out", out)
    ctx.actions.run(
        executable = ctx.executable._tool,
        arguments = [args],
        inputs = [jdeps, deps_file],
        outputs = [out],
        mnemonic = "KmpUnusedDeps",
        progress_message = "Checking %{label} for unused dependencies",
    )
    return [DefaultInfo(files = depset([out]))]

kmp_unused_deps = rule(
    implementation = _unused_deps_impl,
    doc = """Fails the build when `target`'s compile used none of a dependency's own jars (DAGP's unused advice).

    The JVM compile stands for every platform: common code compiles there too, and a dependency only a native
    source set uses is in that source set's deps, which this never sees.""",
    attrs = {
        "deps": attr.label_list(
            providers = [JavaInfo],
            aspects = [_own_jars_aspect],
            doc = "The declared dependencies' JVM targets.",
        ),
        "module": attr.string(mandatory = True, doc = "The module, for the message."),
        "names": attr.string_list(mandatory = True, doc = "`deps` as the BUILD file names them, for the message."),
        "target": attr.label(mandatory = True, providers = [JavaInfo], doc = "The module's JVM compile."),
        "_tool": attr.label(default = "//bazel/kmp/checks/unuseddeps", executable = True, cfg = "exec"),
    },
)
