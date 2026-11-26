"""`r8_jars`: the desktop app's release jars, shrunk by R8 (#1019).

The program's whole runtime classpath goes through R8 (class-file output, compatibility mode) into one shrunk jar,
with the JDK's classes as the library and Compose's default rules plus the module's own. Obfuscation is off, as in
Compose's default; `--define=r8_optimize=false` turns the optimizer off too. The result is a `JavaInfo`, so a
`java_binary` runs the shrunk app.
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

def _quote(path):
    return "'%s'" % path

def _impl(ctx):
    jars = depset(transitive = [d[JavaInfo].transitive_runtime_jars for d in ctx.attr.deps]).to_list()
    lines = ["-dontobfuscate"]
    if not ctx.attr.optimize:
        lines.append("-dontoptimize")
    lines.append("-keep public class %s {\n    public static void main(java.lang.String[]);\n}" % ctx.attr.main_class)
    for jar in jars:
        if jar.owner.workspace_name == "rules_kotlin+" and jar.basename == "empty.jar":
            # rules_kotlin's placeholder in the toolchain's runtime libraries: no entries.
            continue
        jar_filter = ""
        for prefix, entries in ctx.attr.jar_filters.items():
            if jar.basename.startswith(prefix):
                jar_filter = "(%s)" % entries
        lines.append("-injars %s%s" % (_quote(jar.path), jar_filter))
    lines.append("-include '{compose_rules}'")
    for rules in ctx.files.rules:
        lines.append("-include %s" % _quote(rules.path))
    config = ctx.actions.declare_file(ctx.label.name + ".pro")
    ctx.actions.write(config, "\n".join(lines) + "\n")
    out = ctx.actions.declare_file("%s/%s" % (ctx.label.name, ctx.attr.out))
    ctx.actions.run(
        executable = ctx.executable._runner,
        arguments = [config.path, out.path],
        inputs = depset([config] + ctx.files.rules, transitive = [depset(jars)]),
        outputs = [out],
        mnemonic = "R8",
        progress_message = "Shrinking %{label} with R8" + ("" if ctx.attr.optimize else " (no optimization)"),
    )
    return [
        DefaultInfo(files = depset([out])),
        JavaInfo(output_jar = out, compile_jar = out),
    ]

r8_jars = rule(
    implementation = _impl,
    doc = "A JVM program's runtime classpath, shrunk by R8 into one jar.",
    attrs = {
        "deps": attr.label_list(providers = [JavaInfo], doc = "The program; its runtime closure is shrunk."),
        "jar_filters": attr.string_dict(
            doc = "Jar basename prefix → file filter for that `-injars` (what Gradle's artifact transforms drop).",
        ),
        "main_class": attr.string(mandatory = True, doc = "Kept, as Compose keeps the application's main class."),
        "optimize": attr.bool(default = True),
        "out": attr.string(mandatory = True, doc = "The shrunk jar's name."),
        "rules": attr.label_list(allow_files = [".pro"], doc = "The module's keep rules."),
        "_runner": attr.label(default = "//bazel/kmp/r8:runner", executable = True, cfg = "exec"),
    },
)
