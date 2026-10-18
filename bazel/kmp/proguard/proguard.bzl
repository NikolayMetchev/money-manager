"""`proguard_jars`: Compose Desktop's `proguardReleaseJars` (#1019).

Every jar on the program's runtime classpath goes through ProGuard into its own shrunk jar, as Compose does
(`-injars`/`-outjars` per jar), with the target JDK's modules as library jars and Compose's default rules plus the
module's own. Obfuscation is off, as in Compose's default; `--define=proguard_optimize=false` turns the optimizer
off too (Gradle's `-PproguardOptimize=false`), which is most of ProGuard's time. The result is a `JavaInfo`, so a
`java_binary` runs the shrunk app.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//bazel/kmp:distribution.bzl", "jar_name")

def _quote(path):
    return "'%s'" % path

def _impl(ctx):
    jars = depset(transitive = [d[JavaInfo].transitive_runtime_jars for d in ctx.attr.deps]).to_list()
    lines = ["-dontobfuscate"]
    if not ctx.attr.optimize:
        lines.append("-dontoptimize")
    lines.append("-keep public class %s {\n    public static void main(java.lang.String[]);\n}" % ctx.attr.main_class)
    outputs = []
    seen = {}
    for jar in jars:
        if jar.owner.workspace_name == "rules_kotlin+" and jar.basename == "empty.jar":
            # rules_kotlin's placeholder in the toolchain's runtime libraries: no entries.
            continue
        name = jar_name(jar)
        if name in seen:
            fail("%s: %s and %s are both %s" % (ctx.label, seen[name].short_path, jar.short_path, name))
        seen[name] = jar
        out = ctx.actions.declare_file("%s/%s" % (ctx.label.name, name))
        outputs.append(out)
        jar_filter = ""
        for prefix, entries in ctx.attr.jar_filters.items():
            if jar.basename.startswith(prefix):
                jar_filter = "(%s)" % entries
        lines.append("-injars %s%s" % (_quote(jar.path), jar_filter))
        lines.append("-outjars %s" % _quote(out.path))
    jmods = [f for f in ctx.files._jmods if f.basename.endswith(".jmod")]
    for jmod in jmods:
        lines.append("-libraryjars %s(!**.jar;!module-info.class)" % _quote(jmod.path))
    lines.append("-include '{compose_rules}'")
    for rules in ctx.files.rules:
        lines.append("-include %s" % _quote(rules.path))
    config = ctx.actions.declare_file(ctx.label.name + ".pro")
    ctx.actions.write(config, "\n".join(lines) + "\n")
    ctx.actions.run(
        executable = ctx.executable._runner,
        arguments = [config.path],
        inputs = depset([config] + jmods + ctx.files.rules, transitive = [depset(jars)]),
        outputs = outputs,
        mnemonic = "ProGuard",
        progress_message = "Shrinking %{label} with ProGuard" + ("" if ctx.attr.optimize else " (no optimization)"),
    )
    return [
        DefaultInfo(files = depset(outputs)),
        java_common.merge([JavaInfo(output_jar = out, compile_jar = out) for out in outputs]),
    ]

proguard_jars = rule(
    implementation = _impl,
    doc = "A JVM program's runtime classpath, each jar shrunk by ProGuard as Compose Desktop's release build does.",
    attrs = {
        "deps": attr.label_list(providers = [JavaInfo], doc = "The program; its runtime closure is shrunk."),
        "jar_filters": attr.string_dict(
            doc = "Jar basename prefix → ProGuard file filter for that `-injars` (what Gradle's artifact transforms drop).",
        ),
        "main_class": attr.string(mandatory = True, doc = "Kept, as Compose keeps the application's main class."),
        "optimize": attr.bool(default = True),
        "rules": attr.label_list(allow_files = [".pro"], doc = "The module's ProGuard configuration files."),
        "_jmods": attr.label(default = "//bazel/kmp/deps:jdk_jmods"),
        "_runner": attr.label(default = "//bazel/kmp/proguard:runner", executable = True, cfg = "exec"),
    },
)
