"""Compose Desktop's native distributions in Bazel (#1020): `jlink_runtime`, `jpackage_app_image`, `jpackage_installer`.

`jlink_runtime` is `createRuntimeImage` (the target JDK's modules, Compose's flags), `jpackage_app_image` is
`createReleaseDistributable` and `jpackage_installer` is `package<Format>` over that image. The arguments are the
ones Compose passes (captured from Gradle's `*.args.txt`); `JPackageRunner.java` lays out jpackage's input as
Compose's `prepareWorkingDir` does and fills in the platform's details (skiko's natives, macOS's `Info.plist`,
ad-hoc signing). jpackage only packages for the host, so the image and installer are the build machine's own.
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

JPackageInfo = provider(
    doc = "What an app image's installer repeats to jpackage: the image's name, version, launchers and content.",
    fields = {
        "head": "jpackage arguments before the type's own (`--add-launcher`, `--app-content`): the image's only.",
        "inputs": "depset of the files those arguments name.",
        "options": "JPackageRunner options (`--version-file`, `--name`, `--cli`, `--bundle-id`).",
        "tail": "jpackage arguments after the type (`--name`, `--description`, `--app-version`, `--vendor`).",
    },
)

def _run(ctx, mode, out, lines, inputs, mnemonic, message, host = False):
    params = ctx.actions.declare_file("%s.%s.params" % (ctx.label.name, mode))
    ctx.actions.write(params, "\n".join([mode, out.path] + lines) + "\n")
    ctx.actions.run(
        executable = ctx.executable._runner,
        arguments = [params.path],
        inputs = depset([params], transitive = [inputs]),
        outputs = [out],
        mnemonic = mnemonic,
        progress_message = message,
        execution_requirements = {"no-remote-exec": "1", "no-sandbox": "1"} if host else {},
    )

def _jlink_impl(ctx):
    out = ctx.actions.declare_directory(ctx.label.name)
    jmods = [f for f in ctx.files._jmods if f.basename.endswith(".jmod")]
    lines = ["--module-path", jmods[0].dirname]
    for module in ctx.attr.modules:
        lines += ["--add-modules", module]
    lines += ["--strip-debug", "--no-header-files", "--no-man-pages", "--strip-native-commands"]
    _run(ctx, "jlink", out, lines, depset(jmods), "JLink", "Linking the Java runtime %{label}")
    return [DefaultInfo(files = depset([out]))]

jlink_runtime = rule(
    implementation = _jlink_impl,
    doc = "Compose's `createRuntimeImage`: a jlinked runtime of the target JDK's modules.",
    attrs = {
        "modules": attr.string_list(
            default = ["java.base", "java.desktop", "java.logging", "jdk.crypto.ec"],
            doc = "Compose's default modules, plus the application's `modules(…)`.",
        ),
        "_jmods": attr.label(default = "//bazel/kmp/deps:jdk_jmods"),
        "_runner": attr.label(default = "//bazel/kmp/jpackage:runner", executable = True, cfg = "exec"),
    },
)

def _common(ctx):
    """Options and leading/trailing jpackage arguments the image and its installer share, and their inputs."""
    options = ["--version-file", ctx.file.version_file.path, "--name", ctx.attr.package_name]
    if ctx.attr.bundle_id:
        options += ["--bundle-id", ctx.attr.bundle_id]
    inputs = [ctx.file.version_file]
    head = []
    for properties, name in ctx.attr.launchers.items():
        file = properties.files.to_list()[0]
        head += ["--add-launcher", "%s=%s" % (name, file.path)]
        inputs.append(file)
    if ctx.file.app_content:
        options += ["--cli", ctx.file.app_content.path]
        head += ["--app-content", "{cli}"]
        inputs.append(ctx.file.app_content)
    tail = [
        "--name",
        ctx.attr.package_name,
        "--description",
        ctx.attr.description,
        "--app-version",
        "{version}",
        "--vendor",
        ctx.attr.vendor,
    ]
    return JPackageInfo(options = options, head = head, tail = tail, inputs = depset(inputs))

def _image_impl(ctx):
    out = ctx.actions.declare_directory(ctx.label.name)
    info = _common(ctx)
    options = list(info.options) + ["--runtime", ctx.file.runtime.path]
    jars = ctx.attr.jars[DefaultInfo].files.to_list()
    if ctx.attr.main_jar not in [jar.basename for jar in jars]:
        fail("%s: main_jar %s isn't one of %s's jars" % (ctx.label, ctx.attr.main_jar, ctx.attr.jars.label))
    for jar in jars:
        options += ["--jar", jar.path]
    options += ["--main-class", ctx.attr.main_class]
    runtime = ctx.file.runtime
    lines = options + ["--"] + info.head + [
        "--input",
        "{libs}",
        "--runtime-image",
        "{runtime}",
        "--resource-dir",
        "{resources}",
        # A Java system property, so `/` serves on Windows too (Compose writes `$APPDIR\\resources` there).
        "--java-options",
        "-Dcompose.application.resources.dir=$APPDIR/resources",
        "--main-jar",
        ctx.attr.main_jar,
        "--main-class",
        ctx.attr.main_class,
        "--icon",
        "{icon}",
        "--java-options",
        "-Dcompose.application.configure.swing.globals=true",
    ]
    for option in ctx.attr.java_options:
        lines += ["--java-options", option]
    lines += ["--java-options", "-Dskiko.library.path=$APPDIR", "--type", "app-image"] + info.tail + ctx.attr.args
    _run(
        ctx,
        "image",
        out,
        lines,
        depset(jars + [runtime], transitive = [info.inputs]),
        "JPackageImage",
        "Packaging the app image %{label}",
    )
    return [DefaultInfo(files = depset([out])), info]

jpackage_app_image = rule(
    implementation = _image_impl,
    doc = "Compose's `createReleaseDistributable`: jpackage's app image of a runtime and the app's jars.",
    attrs = {
        "app_content": attr.label(
            allow_single_file = True,
            doc = "The native `mm`: copied into the app as `cli/mm` (`--app-content`), keeping its exec bit.",
        ),
        "args": attr.string_list(doc = "More jpackage arguments (the platform's own options)."),
        "bundle_id": attr.string(doc = "macOS: `CFBundleIdentifier` and `--mac-package-identifier`."),
        "description": attr.string(mandatory = True),
        "jars": attr.label(mandatory = True, providers = [JavaInfo], doc = "The app's jars (`proguard_jars`)."),
        "java_options": attr.string_list(doc = "The launcher's JVM options (Compose's `jvmArgs`)."),
        "launchers": attr.label_keyed_string_dict(
            allow_files = [".properties"],
            doc = "`--add-launcher`: launcher properties file → launcher name.",
        ),
        "main_class": attr.string(mandatory = True),
        "main_jar": attr.string(mandatory = True, doc = "The jar (by name) holding the main class."),
        "package_name": attr.string(mandatory = True, doc = "`--name`, as Compose's `packageName`."),
        "runtime": attr.label(mandatory = True, allow_single_file = True, doc = "A `jlink_runtime`."),
        "vendor": attr.string(mandatory = True),
        "version_file": attr.label(allow_single_file = True, mandatory = True, doc = "`--app-version`'s text."),
        "_runner": attr.label(default = "//bazel/kmp/jpackage:runner", executable = True, cfg = "exec"),
    },
)

def _installer_impl(ctx):
    out = ctx.actions.declare_directory(ctx.label.name)
    info = ctx.attr.app_image[JPackageInfo]
    image = ctx.file.app_image
    options = info.options + ["--app-image", image.path]
    wix = ctx.files.wix
    if wix:
        candle = [f for f in wix if f.basename == "candle.exe"]
        options += ["--wix", candle[0].dirname]
    # The image already holds the launchers and content, and jpackage rejects `--add-launcher` with `--app-image`.
    lines = options + ["--", "--resource-dir", "{resources}", "--type", ctx.attr.type]
    lines += info.tail + ctx.attr.args
    _run(
        ctx,
        "installer",
        out,
        lines,
        depset([image] + wix, transitive = [info.inputs]),
        "JPackageInstaller",
        "Packaging the %s installer %%{label}" % ctx.attr.type,
        # dpkg-deb and fakeroot, hdiutil, WiX: the installer tools are the host's own, and they run outside the
        # sandbox too. In the darwin sandbox, jpackage gets EPERM in the copied .app and hdiutil can't attach.
        host = True,
    )
    return [DefaultInfo(files = depset([out]))]

jpackage_installer = rule(
    implementation = _installer_impl,
    doc = """Compose's `package<Format>`: jpackage's installer over a `jpackage_app_image` (its name and version
    repeated, as Compose repeats them), in a directory.""",
    attrs = {
        "app_image": attr.label(
            mandatory = True,
            allow_single_file = True,
            providers = [JPackageInfo],
            doc = "A `jpackage_app_image`.",
        ),
        "args": attr.string_list(doc = "More jpackage arguments (the installer type's own options)."),
        "type": attr.string(mandatory = True, values = ["deb", "rpm", "dmg", "pkg", "msi", "exe"]),
        "wix": attr.label(allow_files = True, doc = "Windows: the WiX toolset jpackage runs (Compose's `wix311`)."),
        "_runner": attr.label(default = "//bazel/kmp/jpackage:runner", executable = True, cfg = "exec"),
    },
)
