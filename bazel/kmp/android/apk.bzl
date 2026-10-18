"""`android_apk` (#1023): AGP's `assembleDebug` / `assembleRelease` for an app module, with AGP's own tools.

The app's Android compile (a `kmp_library` with `jvm = False`) and everything on its runtime classpath become one
APK, in AGP's steps:

- `processDebugManifest`: the app's manifest merged with every AAR's (`AndroidApp manifest`, AGP's
  `mergeManifests`), `applicationId`, versions and SDK levels injected, `android:debuggable` in a debug build. A
  test APK (`test_runner`, a library's `androidDeviceTest`) is `processAndroidDeviceTestManifest` instead
  (`AndroidApp test-manifest`): AGP's generated `<instrumentation>`, targeting the test APK itself.
- `processDebugResources`: `aapt2 link` over the AARs' compiled resources (`aar.bzl`), later classpath entries
  overlaid first so the earlier one wins, and the R.jar AGP writes: one non-transitive R class per AAR, with the
  app's final ids (`AndroidApp rjar`).
- dexing: D8 over the runtime jars in a debug build. A release build is `minifyReleaseWithR8` instead, AGP's own
  `runR8` (`AndroidApp r8`): AGP's `proguard-android-optimize.txt`, the app's rules and each library's (an AAR's
  `proguard.txt`, a jar's embedded rules), in classpath order. R8 also takes the merged Java resources (renaming the
  service files as it renames their classes), the AARs' baseline profiles with their wildcards expanded (which it
  rewrites), and the proto-format link, whose unused resources it drops (optimized resource shrinking). aapt2
  then converts those to binary and shortens their paths, and profgen compiles the profile to
  `assets/dexopt/baseline.prof{,m}`. A stamped build (`--stamp`) records the commit as AGP's VCS info.
- `packageDebug`: `AndroidApp package` (AGP's `ApkFlinger`), signed with the debug key unless `signed = False`
  (the published release APK is unsigned, as Gradle's is).
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//bazel/kmp/android:aar.bzl", "AarInfo")

_AarsInfo = provider(doc = "The AARs a target reaches, in classpath order.", fields = ["aars"])

AndroidAppInfo = provider(
    doc = "What `android_lint` (lint.bzl) reads of an APK: AGP's lint model of the variant.",
    fields = {
        "java": "The app's Android compile (JavaInfo).",
        "aars": "The AARs it reaches (AarInfo), in classpath order.",
        "manifest": "The app's own manifest.",
        "merged_manifest": "The merged manifest.",
        "r_jar": "The R.jar the app compiles against.",
        "namespace": "The namespace.",
        "application_id": "The application id.",
        "min_sdk": "minSdk.",
        "target_sdk": "targetSdk.",
        "compile_sdk": "compileSdk.",
        "debuggable": "Whether the variant is debuggable.",
    },
)

_StampInfo = provider(doc = "Whether the build is stamped (`--stamp`).", fields = ["stamped"])

def _stamp_setting_impl(ctx):
    return [_StampInfo(stamped = ctx.attr.stamped)]

stamp_setting = rule(
    implementation = _stamp_setting_impl,
    attrs = {"stamped": attr.bool()},
    doc = "`--stamp`, as a rule reads it: `stamped` is a `select` on a `stamp` config_setting.",
)

_WALKED = ["deps", "exports", "runtime_deps", "jars", "associates"]

def _aars_aspect_impl(target, ctx):
    direct = [target[AarInfo]] if AarInfo in target else []
    transitive = []
    for attr in _WALKED:
        for dep in getattr(ctx.rule.attr, attr, []):
            if _AarsInfo in dep:
                transitive.append(dep[_AarsInfo].aars)
    return [_AarsInfo(aars = depset(direct, transitive = transitive, order = "preorder"))]

_aars_aspect = aspect(implementation = _aars_aspect_impl, attr_aspects = _WALKED)



def _run_tool(ctx, command, args, inputs, outputs, mnemonic):
    ctx.actions.run(
        executable = ctx.executable._tool,
        arguments = [command, args],
        inputs = inputs,
        outputs = outputs,
        mnemonic = mnemonic,
        progress_message = "%s %%{label}" % mnemonic,
    )

def _release(ctx, aars, program, linked, dex, files):
    """AGP's release tasks after the link: R8 over the code, Java resources, ART profile and resources, then the
    shrunk resources converted and optimized, the profile compiled and, stamped, the VCS info.

    Returns the resources to package, the Java resources (one jar, R8's output) and the extra outputs; adds the
    APK's other files to `files`.
    """
    name = ctx.label.name
    runtime_jars = program[1:]
    defaults = ctx.actions.declare_file(name + "/proguard-android-optimize.txt")
    args = ctx.actions.args()
    args.add("--name", "proguard-android-optimize.txt")
    args.add("--out", defaults)
    _run_tool(ctx, "proguard-defaults", args, [], [defaults], "ProguardDefaults")

    # aapt2's keep rules are left out: optimized resource shrinking doesn't need them, and AGP drops them.
    library_rules = ctx.actions.declare_file(name + "/library_rules.pro")
    aar_rules = [a for a in aars if a.proguard]
    args = ctx.actions.args()
    args.add_all(runtime_jars, before_each = "--jar")
    args.add_all(["%s=%s" % (a.jar.path, a.proguard.path) for a in aar_rules], before_each = "--aar-rules")
    args.add("--out", library_rules)
    _run_tool(
        ctx,
        "embedded-rules",
        args,
        runtime_jars + [a.proguard for a in aar_rules],
        [library_rules],
        "EmbeddedRules",
    )
    specs = [defaults] + ctx.files.proguard_specs + [library_rules]

    java_res_in = ctx.actions.declare_file(name + "/java_res.jar")
    args = ctx.actions.args()
    args.add_all(runtime_jars, before_each = "--jar")
    args.add("--out", java_res_in)
    _run_tool(ctx, "java-res", args, runtime_jars, [java_res_in], "MergeJavaRes")

    profiles = [a.art_profile for a in aars if a.art_profile]
    art_profile = None
    if profiles:
        art_profile = ctx.actions.declare_file(name + "/baseline-prof.txt")
        args = ctx.actions.args()
        args.add_all(profiles, before_each = "--profile")
        args.add_all(program, before_each = "--jar")
        args.add("--out", art_profile)
        _run_tool(ctx, "art-profile", args, profiles + program, [art_profile], "ExpandArtProfile")

    mapping = ctx.actions.declare_file(name + "/mapping.txt")
    configuration = ctx.actions.declare_file(name + "/configuration.txt")
    java_res = ctx.actions.declare_file(name + "/java_res_r8.jar")
    shrunk = ctx.actions.declare_file(name + "/shrunk_resources_proto.ap_")
    raw_res = depset(transitive = [depset(a.raw_res) for a in aars])
    args = ctx.actions.args()
    args.add("--min-sdk", ctx.attr.min_sdk)
    args.add("--lib", ctx.file._android_jar)
    args.add_all(specs, before_each = "--pg-conf")
    args.add_all(program, before_each = "--program")
    args.add("--java-res", java_res_in)
    args.add("--java-res-out", java_res)
    args.add("--resources", linked)
    args.add("--shrunk-resources", shrunk)
    args.add_all([a.root + "/res" for a in aars if a.raw_res], before_each = "--raw-res")
    args.add("--mapping", mapping)
    args.add("--configuration", configuration)
    args.add("--dex", dex)
    outputs = [dex, mapping, configuration, java_res, shrunk]
    inputs = [ctx.file._android_jar, java_res_in, linked] + specs + program
    art_profile_out = None
    if art_profile:
        art_profile_out = ctx.actions.declare_file(name + "/baseline-prof-r8.txt")
        args.add("--art-profile", art_profile)
        args.add("--art-profile-out", art_profile_out)
        inputs.append(art_profile)
        outputs.append(art_profile_out)
    _run_tool(ctx, "r8", args, depset(inputs, transitive = [raw_res]), outputs, "R8")

    binary = ctx.actions.declare_file(name + "/shrunk_resources_binary.ap_")
    ctx.actions.run(
        executable = ctx.executable._aapt2,
        arguments = ["convert", "--output-format", "binary", "-o", binary.path, shrunk.path],
        inputs = [shrunk],
        outputs = [binary],
        mnemonic = "Aapt2Convert",
        progress_message = "Converting the shrunk resources of %{label}",
    )
    optimized = ctx.actions.declare_file(name + "/optimized_resources.ap_")
    shortening_map = ctx.actions.declare_file(name + "/resource-path-shortening-map.txt")
    ctx.actions.run(
        executable = ctx.executable._aapt2,
        arguments = [
            "optimize",
            binary.path,
            "--shorten-resource-paths",
            "--resource-path-shortening-map=" + shortening_map.path,
            "-o",
            optimized.path,
        ],
        inputs = [binary],
        outputs = [optimized, shortening_map],
        mnemonic = "Aapt2Optimize",
        progress_message = "Optimizing the resources of %{label}",
    )
    extra = [mapping, configuration, shortening_map]

    if art_profile_out:
        prof = ctx.actions.declare_file(name + "/baseline.prof")
        profm = ctx.actions.declare_file(name + "/baseline.profm")
        args = ctx.actions.args()
        args.add("--profile", art_profile_out)
        args.add("--dex", dex)
        args.add("--out", prof)
        args.add("--out-metadata", profm)
        _run_tool(ctx, "compile-art-profile", args, [art_profile_out, dex], [prof, profm], "CompileArtProfile")
        files["assets/dexopt/baseline.prof"] = prof
        files["assets/dexopt/baseline.profm"] = profm

    if ctx.attr._stamp[_StampInfo].stamped:
        vcs = ctx.actions.declare_file(name + "/version-control-info.textproto")
        args = ctx.actions.args()
        args.add("--status", ctx.info_file)
        args.add("--out", vcs)
        _run_tool(ctx, "vcs-info", args, [ctx.info_file], [vcs], "VcsInfo")
        files["META-INF/version-control-info.textproto"] = vcs
    return optimized, [java_res], extra

def _android_apk_impl(ctx):
    name = ctx.label.name
    aars = ctx.attr.lib[_AarsInfo].aars.to_list()
    runtime_jars = ctx.attr.lib[JavaInfo].transitive_runtime_jars
    android_jar = ctx.file._android_jar

    manifest = ctx.actions.declare_file(name + "/AndroidManifest.xml")
    args = ctx.actions.args()
    args.add_all([a.manifest for a in aars], before_each = "--dep")
    args.add("--application-id", ctx.attr.application_id)
    args.add("--min-sdk", ctx.attr.min_sdk)
    args.add("--target-sdk", ctx.attr.target_sdk)
    args.add("--out", manifest)
    inputs = [a.manifest for a in aars] + ctx.files.manifest
    if ctx.attr.test_runner:
        if ctx.file.manifest:
            args.add("--test-manifest", ctx.file.manifest)
        args.add("--runner", ctx.attr.test_runner)
        if ctx.attr.debuggable:
            args.add("--debuggable")
        _run_tool(ctx, "test-manifest", args, inputs, [manifest], "MergeTestManifests")
    else:
        args.add("--main", ctx.file.manifest)
        args.add("--namespace", ctx.attr.namespace)
        args.add("--version-code", ctx.attr.version_code)
        args.add("--version-name", ctx.attr.version_name)
        args.add("--compile-sdk", ctx.attr.compile_sdk)
        if not ctx.attr.release:
            args.add("--debuggable")
        _run_tool(ctx, "manifest", args, inputs, [manifest], "MergeManifests")

    # A release build links to aapt2's proto format, which R8's resource shrinker reads.
    linked = ctx.actions.declare_file(name + ("/resources_proto.ap_" if ctx.attr.release else "/resources.ap_"))
    r_txt = ctx.actions.declare_file(name + "/R.txt")
    compiled = [a.res for a in aars if a.res]
    args = ctx.actions.args()
    args.add("link")
    args.add("-I", android_jar)
    args.add("--manifest", manifest)
    args.add("--auto-add-overlay")
    args.add_all(reversed(compiled), before_each = "-R")
    args.add("--output-text-symbols", r_txt)
    if ctx.attr.release:
        args.add("--proto-format")
    elif ctx.attr.debuggable:
        args.add("--debug-mode")
    args.add("-o", linked)
    ctx.actions.run(
        executable = ctx.executable._aapt2,
        arguments = [args],
        inputs = [android_jar, manifest] + compiled,
        outputs = [linked, r_txt],
        mnemonic = "Aapt2Link",
        progress_message = "Linking resources of %{label}",
    )

    empty = ctx.actions.declare_file(name + "/empty_R.txt")
    ctx.actions.write(empty, "")
    r_jar = ctx.actions.declare_file(name + "/R.jar")
    args = ctx.actions.args()
    args.add("--r-txt", r_txt)
    args.add("--namespace", ctx.attr.namespace)
    args.add_all(["%s:%s" % (a.manifest.path, (a.r_txt or empty).path) for a in aars], before_each = "--lib")
    args.add("--out", r_jar)
    _run_tool(
        ctx,
        "rjar",
        args,
        [r_txt, empty] + [a.manifest for a in aars] + [a.r_txt for a in aars if a.r_txt],
        [r_jar],
        "RJar",
    )

    program = [r_jar] + runtime_jars.to_list()
    dex = ctx.actions.declare_file(name + "/dex.zip")
    files = {}
    if ctx.attr.release:
        resources, java_res, extra = _release(ctx, aars, program, linked, dex, files)
    else:
        # D8 builds debug by default. `--debug` would reach java_binary's launcher, which waits for a debugger.
        args = ctx.actions.args()
        args.add("--min-api", ctx.attr.min_sdk)
        if not ctx.attr.debuggable:
            args.add("--release")
        args.add("--lib", android_jar)
        args.add("--output", dex)
        args.add_all(program)
        ctx.actions.run(
            executable = ctx.executable._d8,
            arguments = [args],
            inputs = depset([android_jar], transitive = [depset(program)]),
            outputs = [dex],
            mnemonic = "D8",
            progress_message = "Dexing %{label}",
        )
        resources = linked
        java_res = program
        extra = []

    apk = ctx.outputs.apk
    args = ctx.actions.args()
    args.add("--resources", resources)
    args.add("--dex", dex)
    args.add_all(java_res, before_each = "--java-res")
    args.add_all([a.root for a in aars], before_each = "--aar-root")
    args.add_all(["%s=%s" % (path, f.path) for path, f in files.items()], before_each = "--file")
    args.add("--min-sdk", ctx.attr.min_sdk)
    if not ctx.attr.test_runner:
        # AGP writes app-metadata.properties into an app, never a test APK.
        args.add("--agp-version", ctx.attr.agp_version)
    inputs = [resources, dex] + java_res + files.values()
    if ctx.attr.signed:
        args.add("--keystore", ctx.file._debug_keystore)
        args.add("--store-password", "android")
        args.add("--key-alias", "androiddebugkey")
        args.add("--key-password", "android")
        inputs.append(ctx.file._debug_keystore)
    args.add("--out", apk)
    _run_tool(
        ctx,
        "package",
        args,
        depset(inputs, transitive = [a.files for a in aars]),
        [apk],
        "PackageApk",
    )
    return [
        DefaultInfo(files = depset([apk]), runfiles = ctx.runfiles([apk])),
        OutputGroupInfo(extra = extra),
        AndroidAppInfo(
            java = ctx.attr.lib[JavaInfo],
            aars = aars,
            manifest = ctx.file.manifest,
            merged_manifest = manifest,
            r_jar = r_jar,
            namespace = ctx.attr.namespace,
            application_id = ctx.attr.application_id,
            min_sdk = ctx.attr.min_sdk,
            target_sdk = ctx.attr.target_sdk,
            compile_sdk = ctx.attr.compile_sdk,
            debuggable = not ctx.attr.release,
        ),
    ]

android_apk = rule(
    implementation = _android_apk_impl,
    attrs = {
        "agp_version": attr.string(doc = "Recorded in an app's app-metadata.properties, as AGP does."),
        "application_id": attr.string(mandatory = True),
        "compile_sdk": attr.int(mandatory = True),
        "debuggable": attr.bool(
            default = True,
            doc = "A test APK only: false for a `com.android.test` module's release variant, dexed as release.",
        ),
        "lib": attr.label(
            mandatory = True,
            providers = [JavaInfo],
            aspects = [_aars_aspect],
            doc = "The app's Android compile (`:<name>_android`).",
        ),
        "manifest": attr.label(
            allow_single_file = True,
            doc = "src/main/AndroidManifest.xml, or a test APK's source-set manifest (optional there).",
        ),
        "min_sdk": attr.int(mandatory = True),
        "namespace": attr.string(mandatory = True),
        "proguard_specs": attr.label_list(allow_files = True, doc = "The app's keep rules (release only)."),
        "release": attr.bool(doc = "R8 (minified) instead of D8, and not debuggable."),
        "signed": attr.bool(default = True, doc = "Sign with the debug key (v2, as AGP signs minSdk 24+)."),
        "target_sdk": attr.int(mandatory = True),
        "test_runner": attr.string(
            doc = "A self-instrumenting test APK's instrumentation runner, as AGP builds a library's device tests.",
        ),
        "version_code": attr.int(),
        "version_name": attr.string(),
        "_aapt2": attr.label(default = Label("@aapt2"), executable = True, cfg = "exec", allow_single_file = True),
        "_android_jar": attr.label(default = Label("@android_sdk//:android.jar"), allow_single_file = True),
        "_d8": attr.label(default = Label("//bazel/kmp/android:d8"), executable = True, cfg = "exec"),
        "_debug_keystore": attr.label(default = Label("@android_sdk//:debug.keystore"), allow_single_file = True),
        "_stamp": attr.label(default = Label("//bazel/kmp/android:stamp"), providers = [_StampInfo]),
        "_tool": attr.label(default = Label("//bazel/kmp/android:android_app"), executable = True, cfg = "exec"),
    },
    outputs = {"apk": "%{name}.apk"},
)
