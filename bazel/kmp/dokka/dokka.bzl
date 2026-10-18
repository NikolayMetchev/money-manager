"""The KDoc site (#1027): Dokka run as the Gradle plugin (2.x) runs it, one module at a time, then aggregated.

`kmp_library` gives every module `:<name>_dokka`, a `dokka_module`: Dokka over the module's source sets with
`delayTemplateSubstitution`, as `dokkaGenerateModuleHtml` does. `dokka_site` is `dokkaGeneratePublicationHtml`:
the all-modules page over those outputs, each at its package's path (`app/model/core`).

A source set is a Gradle one, with the ID Gradle gives it (`:app:model:core/commonMain`: the pages' filters and
`sourceset_dependencies.js` name it), every source set of the module's hierarchy whether it has sources or not,
and the classpath of its compilation: commonMain the dependencies' common metadata (on a JVM-only module, the
JVM classpath, as KGP analyses it as JVM), the JVM and Android leaves their compile jars. Bazel has no metadata
klibs for the shared native source sets, so they read a leaf's klibs, as do the Apple ones: Bazel builds macOS
klibs only on a Mac. Generated sources are analysed but not documented (Gradle's `suppressGeneratedFiles`).
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//bazel/kmp:metadata.bzl", "KtMetadataInfo")
load("//bazel/kmp:native.bzl", "KlibInfo")
load("//bazel/kmp/native:toolchain.bzl", "KONAN_TARGETS", "TOOLCHAIN_TYPE")

DokkaModuleInfo = provider(
    doc = "One module's Dokka output, awaiting the all-modules page.",
    fields = {
        "dir": "The output directory.",
        "name": "Gradle's project name (`core`).",
        "path": "Where the site puts it: the module's package (`app/model/core`).",
    },
)

_ROOT = "@ROOT@/"

_SRC_ATTRS = ["android_srcs", "apple_srcs", "common_srcs", "jvm_android_srcs", "jvm_srcs", "linux_srcs", "mingw_srcs", "native_srcs"]

_JDK_AND_KOTLIN_LINKS = [
    {"url": "https://docs.oracle.com/en/java/javase/25/docs/api/", "packageListUrl": "https://docs.oracle.com/en/java/javase/25/docs/api/element-list"},
    {"url": "https://kotlinlang.org/api/core/", "packageListUrl": "https://kotlinlang.org/api/core/package-list"},
]

_ANDROID_LINKS = [
    {"url": "https://developer.android.com/reference/kotlin/", "packageListUrl": "https://developer.android.com/reference/kotlin/package-list"},
    {"url": "https://developer.android.com/reference/kotlin/", "packageListUrl": "https://developer.android.com/reference/kotlin/androidx/package-list"},
]

# The Gradle plugin's defaults, as its dokka-configuration.json carries them.
_PLUGINS_CONFIGURATION = [
    {
        "fqPluginName": "org.jetbrains.dokka.base.DokkaBase",
        "serializationFormat": "JSON",
        "values": json.encode({
            "customAssets": [],
            "customStyleSheets": [],
            "separateInheritedMembers": False,
            "mergeImplicitExpectActualDeclarations": False,
        }),
    },
    {
        "fqPluginName": "org.jetbrains.dokka.kotlinPlaygroundSamples.KotlinPlaygroundSamplesPlugin",
        "serializationFormat": "JSON",
        "values": "{}",
    },
    {
        "fqPluginName": "org.jetbrains.dokka.versioning.VersioningPlugin",
        "serializationFormat": "JSON",
        "values": json.encode({
            "olderVersions": [],
            "olderVersionsDirName": "older",
            "renderVersionsNavigationOnAllPages": True,
        }),
    },
]

def _paths(files):
    return [_ROOT + f.path for f in files]

def _configuration(module_name, output, plugins, source_sets = [], modules = [], delay = False):
    return json.encode({
        "moduleName": module_name,
        "moduleVersion": "@VERSION@",
        "outputDir": _ROOT + output.path,
        "cacheRoot": None,
        "offlineMode": False,
        "sourceSets": source_sets,
        "pluginsClasspath": _paths(plugins),
        "pluginsConfiguration": _PLUGINS_CONFIGURATION,
        "modules": modules,
        "failOnWarning": False,
        "delayTemplateSubstitution": delay,
        "suppressObviousFunctions": True,
        "includes": [],
        "suppressInheritedMembers": False,
        "finalizeCoroutines": False,
    })

def _source_set(scope, name, display, platform, refines, classpath, roots, suppressed, android = False):
    return {
        "displayName": display,
        "sourceSetID": {"scopeId": scope, "sourceSetName": name},
        "classpath": classpath,
        "sourceRoots": roots,
        "dependentSourceSets": [{"scopeId": scope, "sourceSetName": r} for r in refines],
        "samples": [],
        "includes": [],
        "includeNonPublic": False,
        "reportUndocumented": False,
        "skipEmptyPackages": True,
        "skipDeprecated": False,
        "jdkVersion": 25,
        "sourceLinks": [],
        "perPackageOptions": [],
        "externalDocumentationLinks": (_ANDROID_LINKS if android else []) + _JDK_AND_KOTLIN_LINKS,
        "languageVersion": None,
        "apiVersion": None,
        "noStdlibLink": False,
        "noJdkLink": False,
        "suppressedFiles": suppressed,
        "analysisPlatform": platform,
        "documentedVisibilities": ["PUBLIC"],
    }

def _jars(targets, extra = []):
    return depset(extra, transitive = [t[JavaInfo].transitive_compile_time_jars for t in targets])

def _native_classpath(ctx, kgp_target, deps):
    """A konan target's klibs: the stdlib, the target's platform libraries and the dependencies'."""
    kn = ctx.toolchains[TOOLCHAIN_TYPE].kotlin_native
    target = KONAN_TARGETS[kgp_target]
    marker = "/klib/platform/%s/" % target
    platform_files = kn.targets[target].to_list()
    platform = {}
    for f in platform_files:
        if marker in f.path:
            prefix, _, rest = f.path.partition(marker)
            platform[prefix + marker + rest.partition("/")[0]] = True
    klibs = depset(transitive = [d[KlibInfo].transitive_klibs for d in deps]).to_list()
    paths = [_ROOT + kn.dist_dir + "/klib/common/stdlib"] + [_ROOT + p for p in sorted(platform)] + _paths(klibs)
    return paths, depset(klibs + platform_files, transitive = [kn.dist_files])

def _module_impl(ctx):
    scope = ":" + ctx.label.package.replace("/", ":")
    generated = ctx.files.generated
    suppressed = _paths(generated)
    jvm_jars = _jars(ctx.attr.jvm_deps, ctx.files._jvm_stdlib)
    srcs = [f for a in _SRC_ATTRS for f in getattr(ctx.files, a)]
    inputs = [jvm_jars, depset(srcs + generated)]
    jvm_classpath = _paths(jvm_jars.to_list())
    native = bool(ctx.attr.native_deps or ctx.attr.native)

    def roots(attr):
        return _paths(getattr(ctx.files, attr))

    common_roots = roots("common_srcs") + suppressed
    source_sets = []
    leaf_refines = ["jvmAndroidMain"] if ctx.attr.jvm_android else ["commonMain"]
    if native:
        metadata = depset(transitive = [d[KtMetadataInfo].files for d in ctx.attr.metadata_deps])
        metadata_paths = depset(transitive = [d[KtMetadataInfo].classpath for d in ctx.attr.metadata_deps])
        inputs.append(metadata)
        source_sets.append(_source_set(scope, "commonMain", "common", "common", [], [_ROOT + p for p in metadata_paths.to_list()], common_roots, suppressed))
    else:
        source_sets.append(_source_set(scope, "commonMain", "common", "jvm", [], jvm_classpath, common_roots, suppressed))
    if ctx.attr.jvm_android:
        source_sets.append(_source_set(scope, "jvmAndroidMain", "jvmAndroid", "jvm", ["commonMain"], jvm_classpath, roots("jvm_android_srcs"), suppressed))
    source_sets.append(_source_set(scope, "jvmMain", "jvm", "jvm", leaf_refines, jvm_classpath, roots("jvm_srcs"), suppressed))
    if ctx.attr.android:
        android_jars = _jars(ctx.attr.android_deps, [ctx.file._android_jar] + ctx.files._jvm_stdlib)
        inputs.append(android_jars)
        source_sets.append(_source_set(scope, "androidMain", "android", "jvm", leaf_refines, _paths(android_jars.to_list()), roots("android_srcs"), suppressed, android = True))
    if native:
        linux, linux_inputs = _native_classpath(ctx, "linuxX64", ctx.attr.native_deps)
        mingw, mingw_inputs = _native_classpath(ctx, "mingwX64", ctx.attr.mingw_deps)
        inputs += [linux_inputs, mingw_inputs]
        for name, display, refines, classpath, attr in [
            ("nativeMain", "native", "commonMain", linux, "native_srcs"),
            ("linuxMain", "linux", "nativeMain", linux, "linux_srcs"),
            ("linuxX64Main", "linuxX64", "linuxMain", linux, None),
            ("linuxArm64Main", "linuxArm64", "linuxMain", linux, None),
            ("appleMain", "apple", "nativeMain", linux, "apple_srcs"),
            ("macosMain", "macos", "appleMain", linux, None),
            ("macosArm64Main", "macosArm64", "macosMain", linux, None),
            ("mingwMain", "mingw", "nativeMain", mingw, "mingw_srcs"),
            ("mingwX64Main", "mingwX64", "mingwMain", mingw, None),
        ]:
            source_sets.append(_source_set(scope, name, display, "native", [refines], classpath, roots(attr) if attr else [], suppressed))

    # In the Gradle plugin's order: by source set name.
    source_sets = sorted(source_sets, key = lambda s: s["sourceSetID"]["sourceSetName"])
    out = ctx.actions.declare_directory(ctx.label.name)
    plugins = _plugin_jars(ctx.attr._module_plugins)
    config = ctx.actions.declare_file(ctx.label.name + ".json")
    ctx.actions.write(config, _configuration(ctx.attr.module_name, out, plugins.to_list(), source_sets, delay = True))
    _run(ctx, config, out, depset([config], transitive = inputs + [plugins]), "Documenting %{label}")
    return [
        DefaultInfo(files = depset([out])),
        DokkaModuleInfo(dir = out, name = ctx.attr.module_name, path = ctx.label.package),
    ]

def _plugin_jars(plugins):
    # Dokka loads every plugin its classpath has, so the plugins (with what they need) come only through
    # pluginsClasspath, and a module's run never has the all-modules page.
    return depset(transitive = [p[JavaInfo].transitive_runtime_jars for p in plugins])

def _run(ctx, config, out, inputs, message):
    ctx.actions.run(
        executable = ctx.executable._runner,
        arguments = [config.path, ctx.file._version.path],
        inputs = depset([ctx.file._version], transitive = [inputs]),
        outputs = [out],
        mnemonic = "Dokka",
        progress_message = message,
        # Dokka downloads the external documentation's package lists (the JDK's, Kotlin's, Android's).
        execution_requirements = {"requires-network": "1"},
    )

# The Kotlin analysis loads with the plugins, as on the Gradle plugin's one classpath: it reads the enum members'
# documentation templates from dokka-base.
_MODULE_PLUGINS = ["@dokka//:dokka_analysis_kotlin_symbols_jvm", "@dokka//:dokka_templating_plugin_jvm", "@dokka//:dokka_base_jvm"]

_COMMON_ATTRS = {
    "_runner": attr.label(default = "//bazel/kmp/dokka:runner", executable = True, cfg = "exec"),
    "_version": attr.label(default = "//:VERSION", allow_single_file = True),
}

dokka_module = rule(
    implementation = _module_impl,
    doc = "One module's KDoc, as the Gradle plugin's dokkaGenerateModuleHtml writes it.",
    attrs = _COMMON_ATTRS | {
        "android": attr.bool(),
        "android_deps": attr.label_list(providers = [JavaInfo]),
        "android_srcs": attr.label_list(allow_files = [".kt"]),
        "apple_srcs": attr.label_list(allow_files = [".kt"]),
        "common_srcs": attr.label_list(allow_files = [".kt"]),
        "generated": attr.label_list(allow_files = True, doc = "Generated commonMain sources: analysed, not documented."),
        "jvm_android": attr.bool(doc = "Whether the module has jvmAndroidMain."),
        "jvm_android_srcs": attr.label_list(allow_files = [".kt"]),
        "jvm_deps": attr.label_list(providers = [JavaInfo]),
        "jvm_srcs": attr.label_list(allow_files = [".kt"]),
        "linux_srcs": attr.label_list(allow_files = [".kt"]),
        "metadata_deps": attr.label_list(providers = [KtMetadataInfo]),
        "mingw_deps": attr.label_list(providers = [KlibInfo]),
        "mingw_srcs": attr.label_list(allow_files = [".kt"]),
        "module_name": attr.string(mandatory = True),
        "native": attr.bool(doc = "Whether the module has native targets (its source sets are the native hierarchy's too)."),
        "native_deps": attr.label_list(providers = [KlibInfo], doc = "linuxX64's."),
        "native_srcs": attr.label_list(allow_files = [".kt"]),
        "_android_jar": attr.label(default = "@android_sdk//:android.jar", allow_single_file = True),
        "_jvm_stdlib": attr.label(default = "@rules_kotlin//kotlin/compiler:kotlin-stdlib", allow_files = True),
        "_module_plugins": attr.label_list(default = _MODULE_PLUGINS, providers = [JavaInfo]),
    },
    toolchains = [config_common.toolchain_type(TOOLCHAIN_TYPE, mandatory = False)],
)

def _site_impl(ctx):
    out = ctx.actions.declare_directory(ctx.label.name)
    modules = sorted([m[DokkaModuleInfo] for m in ctx.attr.modules], key = lambda m: m.path)
    plugins = _plugin_jars(ctx.attr._site_plugins)
    config = ctx.actions.declare_file(ctx.label.name + ".json")
    ctx.actions.write(config, _configuration(ctx.attr.module_name, out, plugins.to_list(), modules = [
        {
            "name": m.name,
            "relativePathToOutputDirectory": m.path,
            "includes": [],
            "sourceOutputDirectory": _ROOT + m.dir.path,
        }
        for m in modules
    ]))
    _run(ctx, config, out, depset([config] + [m.dir for m in modules], transitive = [plugins]), "Assembling the KDoc site %{label}")
    return [DefaultInfo(files = depset([out]))]

dokka_site = rule(
    implementation = _site_impl,
    doc = "The multi-module KDoc site, as the Gradle plugin's dokkaGeneratePublicationHtml writes it.",
    attrs = _COMMON_ATTRS | {
        "module_name": attr.string(mandatory = True),
        "modules": attr.label_list(providers = [DokkaModuleInfo]),
        "_site_plugins": attr.label_list(default = _MODULE_PLUGINS + ["@dokka//:dokka_all_modules_page_plugin_jvm"], providers = [JavaInfo]),
    },
)
