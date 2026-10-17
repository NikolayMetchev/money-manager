"""`sqldelight_library`: SQLDelight code generation as a Bazel action (#1003).

SQLDelight only ships a Gradle plugin. This runs its compiler through //bazel/kmp/sqldelight:generator on a
module's `.sq` files, with each upstream database's `.sq` files as dependencies (Gradle's
`dependency(project)`), and hands the generated Kotlin to `kmp_library(sqldelight = …)` as `commonMain`
sources: a srcjar for the JVM compile, a directory for konanc. `verifyMigrations` stays off (no database
versioning yet, #426).
"""

SqlDelightInfo = provider(
    doc = "A SQLDelight database, as its dependents see it.",
    fields = {
        "database": "`package:ClassName` of the generated database.",
        "folders": "depset of the source folders (`src/commonMain/sqldelight`) of this and every upstream database.",
        "srcs": "depset of this and every upstream database's `.sq` files.",
    },
)

def _impl(ctx):
    srcjar = ctx.actions.declare_file(ctx.label.name + ".srcjar")
    out_dir = ctx.actions.declare_directory(ctx.label.name + "_kt")
    folder = ctx.label.package + "/" + ctx.attr.folder
    upstream = [d[SqlDelightInfo] for d in ctx.attr.deps]

    args = ctx.actions.args()
    args.add_all(["--package", ctx.attr.package, "--class-name", ctx.attr.class_name])
    args.add_all(["--module-name", ctx.attr.module_name, "--dialect", ctx.attr.dialect])
    args.add("--source-folder", folder)
    args.add_all(depset(transitive = [u.folders for u in upstream]), before_each = "--dependency-folder")
    args.add_all([u.database for u in upstream], before_each = "--dependency")
    args.add_all(["--out-dir", out_dir.path, "--srcjar", srcjar])
    ctx.actions.run(
        executable = ctx.executable._generator,
        arguments = [args],
        inputs = depset(ctx.files.srcs, transitive = [u.srcs for u in upstream]),
        outputs = [srcjar, out_dir],
        mnemonic = "SqlDelight",
        progress_message = "Generating SQLDelight %{label}",
    )
    return [
        DefaultInfo(files = depset([srcjar])),
        OutputGroupInfo(kt = depset([out_dir])),
        SqlDelightInfo(
            database = "%s:%s" % (ctx.attr.package, ctx.attr.class_name),
            folders = depset([folder], transitive = [u.folders for u in upstream]),
            srcs = depset(ctx.files.srcs, transitive = [u.srcs for u in upstream]),
        ),
    ]

_sqldelight_library = rule(
    implementation = _impl,
    attrs = {
        "class_name": attr.string(default = "MoneyManagerDatabase"),
        "deps": attr.label_list(providers = [SqlDelightInfo], doc = "Upstream databases (Gradle's `dependency(project)`)."),
        "dialect": attr.string(
            # sqldelight-dialect-sqlite in the catalog (sqlite-3-25-dialect).
            default = "app.cash.sqldelight.dialects.sqlite_3_25.SqliteDialect",
        ),
        "folder": attr.string(default = "src/commonMain/sqldelight", doc = "The source folder, relative to the package."),
        "module_name": attr.string(mandatory = True, doc = "Gradle's project name, which SQLDelight is given."),
        "package": attr.string(mandatory = True),
        "srcs": attr.label_list(allow_files = [".sq"]),
        "_generator": attr.label(
            default = "//bazel/kmp/sqldelight:generator",
            executable = True,
            cfg = "exec",
        ),
    },
    provides = [SqlDelightInfo],
)

def sqldelight_library(name, visibility = None, **kwargs):
    """A SQLDelight database. Emits `:<name>` (srcjar + SqlDelightInfo) and `:<name>_kt` (the sources' directory).

    Args:
        name: the target name; `kmp_library(sqldelight = ":<name>")` takes it.
        visibility: the targets' visibility.
        **kwargs: `srcs`, `package`, `module_name`, `deps`, `class_name`, `folder`, `dialect`.
    """
    _sqldelight_library(name = name, visibility = visibility, **kwargs)
    native.filegroup(
        name = name + "_kt",
        srcs = [":" + name],
        output_group = "kt",
        visibility = visibility,
    )
