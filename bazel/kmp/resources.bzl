"""A module's Java resources as a jar, the way a KMP compilation merges its source sets' (#1018)."""

def _resource_jar_impl(ctx):
    package = ctx.label.package + "/" if ctx.label.package else ""
    entries = {}

    # Root first, so the leaf's resources replace commonMain's of the same name, as in Gradle's processResources.
    for root in ctx.attr.roots:
        prefix = package + root.rstrip("/") + "/"
        for f in ctx.files.srcs:
            if f.short_path.startswith(prefix):
                entries[f.short_path[len(prefix):]] = f
    out = ctx.actions.declare_file(ctx.label.name + ".jar")
    args = ctx.actions.args()
    args.add("--out", out)
    args.add_all(["%s=%s" % (entry, f.path) for entry, f in entries.items()])
    ctx.actions.run(
        executable = ctx.executable._tool,
        arguments = [args],
        inputs = ctx.files.srcs,
        outputs = [out],
        mnemonic = "KmpResources",
        progress_message = "Packing %{label}",
    )
    return [DefaultInfo(files = depset([out]))]

kmp_resource_jar = rule(
    implementation = _resource_jar_impl,
    doc = "Packs `srcs` into a jar, each under its path below the first of `roots` it's in; later roots win.",
    attrs = {
        "roots": attr.string_list(mandatory = True, doc = "Package-relative resource dirs, root source set first."),
        "srcs": attr.label_list(allow_files = True),
        "_tool": attr.label(default = "//bazel/kmp/resources", executable = True, cfg = "exec"),
    },
)
