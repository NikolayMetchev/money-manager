"""`android_lint` (#1025): AGP's `lintDebug` for an app module, with the lint AGP runs.

AGP hands lint a model of the variant (`--lint-model`), which this writes the same way
(`generateDebugLintReportModel`): `module.xml` (the module, its boot classpath and lint options), `debug.xml` (namespace,
SDK levels, merged manifest, sources, the classes and R.jar) and the variant's libraries, each AAR extracted so lint reads
its manifest, resources, public resources, external annotations and lint checks (`lint.jar`, which AGP runs on every
module that uses the AAR). The dependency graph is flat, as AGP writes it. The options are moneyManagerLintPolicy's
(`gradle/build-logic`) plus the module's own `disable`: warnings are errors and fail the check, and only the module's
own main sources are analyzed (no test sources, no dependencies).

Paths are written against `@ROOT@` (the runfiles' workspace directory), `@TMP@` (the test's temporary directory) and
`@SEP@` (the host's path separator), which `LintRunner` fills in.
"""

load("@rules_java//java:java_test.bzl", "java_test")
load("//bazel/kmp/android:apk.bzl", "AndroidAppInfo")

_SEP = "@SEP@"

def _p(f):
    return "@ROOT@/" + f.short_path

def _attr(value):
    return value.replace("&", "&amp;").replace('"', "&quot;").replace("<", "&lt;")

def _library_name(f):
    return f.short_path.lstrip("./").replace("/", ":")

def _model_impl(ctx):
    info = ctx.attr.apk[AndroidAppInfo]
    own = info.java.runtime_output_jars
    aar_jars = {a.jar: a for a in info.aars}
    package = ctx.label.package
    module_dir = "@ROOT@/" + package
    runfiles = list(own) + ctx.files.srcs + [info.manifest, info.merged_manifest, info.r_jar, ctx.file._android_jar]

    module = """<lint-module
    format="1"
    dir="{dir}"
    name=":{name}"
    type="APP"
    maven="{maven}:unspecified"
    agpVersion="{agp}"
    buildFolder="@TMP@/build"
    bootClassPath="{boot}"
    javaSourceLevel="{java}"
    compileTarget="android-{compile_sdk}.0"
    highlightGradualR8Api="false">
  <lintOptions
      {disable}abortOnError="true"
      absolutePaths="true"
      warningsAsErrors="true"
      ignoreTestSources="true"
      checkReleaseBuilds="true"
      explainIssues="true">
    <severities>
{severities}    </severities>
  </lintOptions>
  <variant name="{variant}"/>
</lint-module>
""".format(
        dir = module_dir,
        name = package.replace("/", ":"),
        maven = package.rsplit("/", 1)[0].replace("/", ".") + ":" + package.rsplit("/", 1)[-1],
        agp = ctx.attr.agp_version,
        boot = _p(ctx.file._android_jar),
        java = ctx.attr.java_source_level,
        compile_sdk = info.compile_sdk,
        disable = ('disable="%s"\n      ' % ",".join(ctx.attr.disable)) if ctx.attr.disable else "",
        severities = "".join([
            '      <severity\n        id="%s"\n        severity="IGNORE" />\n' % issue
            for issue in ctx.attr.disable
        ]),
        variant = ctx.attr.variant,
    )

    variant = """<variant
    name="{variant}"
    package="{namespace}"
    minSdkVersion="{min_sdk}"
    targetSdkVersion="{target_sdk}.0"
    debuggable="{debuggable}"
    mergedManifest="{merged}"
    partialResultsDir="@TMP@/partial">
  <buildFeatures/>
  <sourceProviders>
    <sourceProvider
        manifests="{manifest}"
        javaDirectories="{java_dirs}"
        resDirectories="{res_dirs}"
        assetsDirectories="{assets_dirs}"/>
  </sourceProviders>
  <testSourceProviders>
  </testSourceProviders>
  <testFixturesSourceProviders>
  </testFixturesSourceProviders>
  <artifact
      classOutputs="{classes}"
      type="MAIN"
      applicationId="{application_id}">
  </artifact>
</variant>
""".format(
        variant = ctx.attr.variant,
        namespace = info.namespace,
        min_sdk = info.min_sdk,
        target_sdk = info.target_sdk,
        debuggable = "true" if info.debuggable else "false",
        merged = _p(info.merged_manifest),
        manifest = _p(info.manifest),
        java_dirs = _SEP.join([module_dir + "/src/main/" + d for d in ["java", "kotlin"]]),
        res_dirs = module_dir + "/src/main/res",
        assets_dirs = module_dir + "/src/main/assets",
        classes = _SEP.join([_p(j) for j in own] + [_p(info.r_jar)]),
        application_id = info.application_id,
    )

    names = []
    libraries = []
    for jar in info.java.transitive_runtime_jars.to_list():
        if jar in own:
            continue
        name = _library_name(jar)
        a = aar_jars.get(jar)
        if a:
            name += "@aar"
            folder = "@ROOT@/" + a.manifest.short_path.rsplit("/", 1)[0]
            parts = [a.manifest, a.jar] + a.raw_res
            # AGP lists every part, whether the AAR has it or not; lint reads the ones that exist.
            parts += [f for f in [a.lint_jar, a.public_txt, a.r_txt, a.annotations] if f]
            runfiles += parts
            libraries.append(
                '  <library\n      name="{name}"\n      jars="{jar}"\n      resolved="bazel:{artifact}:unspecified"\n      folder="{folder}"\n      manifest="AndroidManifest.xml"\n      resFolder="res"\n      assetsFolder="assets"\n      lintJar="lint.jar"\n      publicResources="public.txt"\n      symbolFile="R.txt"\n      externalAnnotations="annotations.zip"\n      proguardRules="proguard.txt"/>\n'.format(
                    name = _attr(name),
                    jar = _attr(_p(a.jar)),
                    artifact = _attr(name[:-4].replace(":", ".")),
                    folder = _attr(folder),
                ),
            )
        else:
            name += "@jar"
            runfiles.append(jar)
            libraries.append(
                '  <library\n      name="{name}"\n      jars="{jar}"\n      resolved="bazel:{artifact}:unspecified"/>\n'.format(
                    name = _attr(name),
                    jar = _attr(_p(jar)),
                    artifact = _attr(name[:-4].replace(":", ".")),
                ),
            )
        names.append(name)

    graph = "".join(['    <dependency name="%s" simpleName="bazel:%s" />\n' % (_attr(n), _attr(n[:-4].replace(":", "."))) for n in names])
    roots = _attr(",".join(names))
    dependencies = '<dependencies>\n  <compile\n      roots="{roots}">\n{graph}  </compile>\n  <package\n      roots="{roots}">\n{graph}  </package>\n</dependencies>\n'.format(
        roots = roots,
        graph = graph,
    )

    files = []
    for basename, content in [
        # The SDK lint looks compileTarget up in (`--sdk-home`): the platform `@android_sdk//:lint_sdk` lays out.
        ("sdk-home", "@ROOT@/" + ctx.files._sdk[0].short_path.split("/sdk/platforms/")[0] + "/sdk"),
        ("module.xml", module),
        (ctx.attr.variant + ".xml", variant),
        (ctx.attr.variant + "-artifact-dependencies.xml", dependencies),
        (ctx.attr.variant + "-artifact-libraries.xml", "<libraries>\n" + "".join(libraries) + "</libraries>\n"),
    ]:
        f = ctx.actions.declare_file(ctx.label.name + "/" + basename)
        ctx.actions.write(f, content)
        files.append(f)
    return [DefaultInfo(
        files = depset(files),
        runfiles = ctx.runfiles(files + runfiles, transitive_files = depset(ctx.files._sdk)),
    )]

_model = rule(
    implementation = _model_impl,
    attrs = {
        "agp_version": attr.string(mandatory = True),
        "apk": attr.label(mandatory = True, providers = [AndroidAppInfo]),
        "disable": attr.string_list(),
        "java_source_level": attr.string(mandatory = True),
        "srcs": attr.label_list(allow_files = True),
        "variant": attr.string(mandatory = True),
        "_android_jar": attr.label(default = Label("@android_sdk//:android_jar"), allow_single_file = True),
        "_sdk": attr.label(default = Label("@android_sdk//:lint_sdk")),
    },
)

def android_lint(name, apk, srcs, agp_version, java_source_level, variant = "debug", disable = [], **kwargs):
    """Android lint over an app variant's main sources, as a test (Gradle's `lint<Variant>` fails `check`).

    Args:
        name: the test.
        apk: the variant's `android_apk`.
        srcs: the module's main sources (lint reads them from the model's source directories).
        agp_version: the AGP version the model records.
        java_source_level: the module's Java source level (the toolchain's).
        variant: the variant name.
        disable: issue ids the module's `lint { disable += … }` turns off.
        **kwargs: passed to java_test.
    """
    _model(
        name = name + "_model",
        agp_version = agp_version,
        apk = apk,
        disable = disable,
        java_source_level = java_source_level,
        srcs = srcs,
        variant = variant,
        testonly = True,
    )
    java_test(
        name = name,
        main_class = "com.moneymanager.bazel.kmp.android.LintRunner",
        use_testrunner = False,
        args = [
            "$(rootpaths :%s_model)" % name,
            "--",
            "--variant",
            variant,
            "--exitcode",
        ],
        data = [":%s_model" % name],
        runtime_deps = [Label("//bazel/kmp/android:lint_runner")],
        **kwargs
    )
