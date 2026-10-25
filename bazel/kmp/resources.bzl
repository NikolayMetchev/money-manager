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

def _file_resource_jar_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name + ".jar")
    args = ctx.actions.args()
    args.add("--out", out)
    files = []
    for target, entry in ctx.attr.entries.items():
        target_files = target.files.to_list()
        if entry.endswith("/"):
            for f in target_files:
                args.add("%s%s=%s" % (entry, f.basename, f.path))
        elif len(target_files) == 1:
            args.add("%s=%s" % (entry, target_files[0].path))
        else:
            fail("%s must be one file, got %d (or name a directory entry, ending in /)" % (target.label, len(target_files)))
        files.extend(target_files)
    ctx.actions.run(
        executable = ctx.executable._tool,
        arguments = [args],
        inputs = files,
        outputs = [out],
        mnemonic = "KmpResources",
        progress_message = "Packing %{label}",
    )
    return [DefaultInfo(files = depset([out]))]

file_resource_jar = rule(
    implementation = _file_resource_jar_impl,
    doc = "Packs files from anywhere (another repository's, say) into a resource jar, each at its named entry, or " +
          "every file of a target under an entry ending in `/`. Wrap it in a `java_import` for a JVM library to take " +
          "as a runtime dep.",
    attrs = {
        "entries": attr.label_keyed_string_dict(allow_files = True, doc = "File → its path in the jar, or files → their directory."),
        "_tool": attr.label(default = "//bazel/kmp/resources", executable = True, cfg = "exec"),
    },
)
