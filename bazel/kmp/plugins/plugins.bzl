"""Compiler plugins by name, as `kmp_library(plugins = […])` and `kmp_test(plugins = […])` take them (#1001).

A name stands for the plugin on every platform (`//bazel/kmp/plugins:<name>_jvm` for rules_kotlin,
`…:<name>_native` for konanc) and for the runtime it needs: Metro's runtime as an
`api` dependency, mappie-api, and Mokkery's runtime for tests. Compose and serialization add none: modules
declare their runtimes themselves.
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

PLUGINS = {
    "compose": struct(deps = [], exports = []),
    "mappie": struct(deps = ["@maven_kmp//:mappie_api"], exports = []),
    "metro": struct(deps = [], exports = ["@maven_kmp//:metro_runtime"]),
    "mokkery": struct(deps = ["@maven_kmp//:mokkery_runtime"], exports = []),
    "serialization": struct(deps = [], exports = []),
}

# Metro's Gradle plugin runs it before Compose and serialization. The JVM toolchain always passes these
# (//bazel/kmp:kotlinc_options); native compiles pass them when Metro is applied, as KGP does.
METRO_ID = "dev.zacsweers.metro.compiler"
PLUGIN_ORDER = [
    METRO_ID + ">androidx.compose.compiler.plugins.kotlin",
    METRO_ID + ">org.jetbrains.kotlinx.serialization",
]

ORDER = ["metro", "compose", "serialization", "mappie", "mokkery"]

def plugin_labels(names, platform):
    """The plugin targets for `names`, in ORDER. `platform` is `jvm` or `native`."""
    for name in names:
        if name not in PLUGINS:
            fail("unknown compiler plugin %r; known: %s" % (name, ", ".join(ORDER)))
    return [Label("//bazel/kmp/plugins:%s_%s" % (name, platform)) for name in ORDER if name in names]

def plugin_deps(names):
    deps = [d for name in names for d in PLUGINS[name].deps]
    exports = [d for name in names for d in PLUGINS[name].exports]
    return struct(deps = deps, exports = exports)

NativePluginInfo = provider(
    doc = "A compiler plugin as konanc loads it: `-Xplugin=<jar>` per classpath jar, `-P plugin:<id>:<k>=<v>`.",
    fields = {
        "classpath": "depset of the plugin's jars.",
        "id": "The plugin id.",
        "options": "dict of option → value.",
    },
)

def _native_plugin_impl(ctx):
    return [NativePluginInfo(
        id = ctx.attr.id,
        options = ctx.attr.options,
        classpath = depset(transitive = [d[JavaInfo].transitive_runtime_jars for d in ctx.attr.deps]),
    )]

kt_native_plugin = rule(
    implementation = _native_plugin_impl,
    attrs = {
        "deps": attr.label_list(providers = [JavaInfo], cfg = "exec"),
        "id": attr.string(mandatory = True),
        "options": attr.string_dict(),
    },
    provides = [NativePluginInfo],
)

def native_plugin_args(args, plugins):
    """Adds konanc's plugin flags for `plugins` (targets with NativePluginInfo); returns their jars."""
    jars = []
    if METRO_ID in [p[NativePluginInfo].id for p in plugins]:
        args.add_all(PLUGIN_ORDER, format_each = "-Xcompiler-plugin-order=%s")
    for p in plugins:
        info = p[NativePluginInfo]
        for jar in info.classpath.to_list():
            args.add("-Xplugin=" + jar.path)
            jars.append(jar)
        for key, value in sorted(info.options.items()):
            args.add_all(["-P", "plugin:%s:%s=%s" % (info.id, key, value)])
    return jars
