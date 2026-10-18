"""Gradle's custom module checks as Bazel rules (#1014). Both fail `bazel build`, as their tasks fail `check`.

- `kmp_forbidden_deps` is `verifyNoDbDependency`: the pure importers (and their tests) must never depend on
  the database, DI or UI modules, even transitively. It fails at analysis, naming the module and the path.
- `kmp_reference_check` is the source half of `verifyNoWriteRepositoryUsage`: inside a module that may depend
  on the write repositories (`app/importer`), only the listed files may name one. Elsewhere the write module's
  `visibility` already stops it.

`kmp_library(forbidden_deps = …, forbidden_reference = …)` and `kmp_test(forbidden_deps = …)` create them, from
the `kmp_forbid_deps` / `kmp_forbid_reference` Gazelle directives.
"""

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
