"""The GitHub Pages site's generated parts (#1027), built as actions so static.yml only copies outputs.

`tool_output_dir` runs a tool that writes a directory (the strategy catalog), `schemaspy_docs` is
app/db/schemaspy's `publishSchemaSpyDocs` and `pages_site` lays the site out as the Pages artifact expects:
`webpage/`'s checked-in pages with each generated directory at its path.
"""

def _tool_output_dir_impl(ctx):
    out = ctx.actions.declare_directory(ctx.label.name)
    ctx.actions.run(
        executable = ctx.executable.tool,
        arguments = [a.replace("{out}", out.path) for a in ctx.attr.tool_args],
        outputs = [out],
        mnemonic = "ToolOutputDir",
        progress_message = "Generating %{label}",
    )
    return [DefaultInfo(files = depset([out]))]

tool_output_dir = rule(
    implementation = _tool_output_dir_impl,
    doc = "A directory a tool writes: `{out}` in `tool_args` is its path.",
    attrs = {
        "tool": attr.label(executable = True, cfg = "exec", mandatory = True),
        "tool_args": attr.string_list(),
    },
)

def _schemaspy_docs_impl(ctx):
    db = ctx.actions.declare_file(ctx.label.name + ".db")
    ctx.actions.run(
        executable = ctx.executable.database_creator,
        arguments = [db.path],
        outputs = [db],
        mnemonic = "SchemaSpyDatabase",
        progress_message = "Creating the schema database for %{label}",
    )
    out = ctx.actions.declare_directory(ctx.label.name)
    ctx.actions.run_shell(
        # SQLite opens the database read-write, beside its journal, so SchemaSpy reads a writable copy. Its pages are
        # titled with the path it's given: Gradle's file name, without Gradle's build directory.
        command = 'tool="$PWD/$1" db="$PWD/$2" out="$PWD/$3" && cd "$(mktemp -d)" && cp "$db" schemaspy-temp.db && ' +
                  'chmod u+w schemaspy-temp.db && exec "$tool" ' +
                  # generateSchemaSpyDocs's arguments.
                  '-t sqlite-xerial -db schemaspy-temp.db -o "$out" -cat % -s main -u "" -sso -norows',
        arguments = [ctx.executable.schemaspy.path, db.path, out.path],
        inputs = [db],
        tools = [ctx.attr.schemaspy[DefaultInfo].files_to_run],
        outputs = [out],
        mnemonic = "SchemaSpy",
        progress_message = "Documenting the schema for %{label}",
        # SchemaSpy draws its diagrams with the host's Graphviz `dot`, which the remote executors don't have.
        execution_requirements = {"no-remote-exec": "1"},
    )
    return [DefaultInfo(files = depset([out]))]

schemaspy_docs = rule(
    implementation = _schemaspy_docs_impl,
    doc = "SchemaSpy's HTML for the schema `database_creator` writes to the path it's given.",
    attrs = {
        "database_creator": attr.label(executable = True, cfg = "exec", mandatory = True),
        "schemaspy": attr.label(executable = True, cfg = "exec", mandatory = True),
    },
)

def _pages_site_impl(ctx):
    out = ctx.actions.declare_directory(ctx.label.name)
    commands = ['set -eu', 'out="$1"']
    inputs = []
    strip = ctx.attr.strip_prefix.rstrip("/") + "/"
    for f in ctx.files.srcs:
        path = f.short_path
        if not path.startswith(strip):
            fail("%s is not under %s" % (path, strip))
        commands.append('mkdir -p "$out/$(dirname "%s")" && cp "%s" "$out/%s"' % (path[len(strip):], f.path, path[len(strip):]))
        inputs.append(f)
    for target, path in ctx.attr.generated.items():
        files = target[DefaultInfo].files.to_list()
        if len(files) != 1:
            fail("%s must output one file or directory" % target.label)
        f = files[0]
        inputs.append(f)
        if f.is_directory:
            commands.append('mkdir -p "$out/%s" && cp -R "%s/." "$out/%s"' % (path, f.path, path))
        else:
            commands.append('mkdir -p "$out/$(dirname "%s")" && cp "%s" "$out/%s"' % (path, f.path, path))
    ctx.actions.run_shell(
        command = "\n".join(commands),
        arguments = [out.path],
        inputs = inputs,
        outputs = [out],
        mnemonic = "PagesSite",
        progress_message = "Assembling %{label}",
    )
    return [DefaultInfo(files = depset([out]))]

pages_site = rule(
    implementation = _pages_site_impl,
    doc = "The site: `srcs` at their paths under `strip_prefix`, each `generated` output at its path in the site " +
          "(a directory's contents, or one file).",
    attrs = {
        "generated": attr.label_keyed_string_dict(allow_files = True),
        "srcs": attr.label_list(allow_files = True),
        "strip_prefix": attr.string(mandatory = True),
    },
)
