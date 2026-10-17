"""`kotlin_constants` (#1011): Gradle's GenerateKotlinConstantsTask, an `internal object` of string constants
compiled into a module's commonMain (`kmp_library(generated_srcs = …)`). Kotlin/Native has no classpath
resources, so data a module ships is compiled in on every platform."""

def _kotlin_constants_impl(ctx):
    out = ctx.actions.declare_file("%s/%s.kt" % (ctx.label.name, ctx.attr.object))
    args = ctx.actions.args()
    args.add("-package", ctx.attr.package)
    args.add("-object", ctx.attr.object)
    args.add("-generated_by", ctx.attr.generated_by or str(ctx.label))
    args.add("-out", out)
    inputs = []
    for flag, values in [("-constant", ctx.attr.constants), ("-obfuscated", ctx.attr.obfuscated)]:
        for target, constant in values.items():
            files = target.files.to_list()
            if len(files) != 1:
                fail("%s: %s must be one file" % (constant, target.label))
            args.add(flag, "%s=%s" % (constant, files[0].path))
            inputs.append(files[0])
    for constant in ctx.attr.trim:
        args.add("-trim", constant)
    ctx.actions.run(
        executable = ctx.executable._generator,
        arguments = [args],
        inputs = inputs,
        outputs = [out],
        mnemonic = "KotlinConstants",
        progress_message = "Generating %{output}",
    )
    return [DefaultInfo(files = depset([out]))]

kotlin_constants = rule(
    implementation = _kotlin_constants_impl,
    doc = "Generates `internal object <object>` in `<package>`, one `String` constant per file.",
    attrs = {
        "package": attr.string(mandatory = True, doc = "The Kotlin package."),
        "object": attr.string(mandatory = True, doc = "The object's name."),
        "constants": attr.label_keyed_string_dict(
            allow_files = True,
            doc = "File to constant name: the constant is the file's text.",
        ),
        "trim": attr.string_list(doc = "Constants whose surrounding whitespace is trimmed (a file's final newline)."),
        "obfuscated": attr.label_keyed_string_dict(
            allow_files = True,
            doc = "File to constant name: the file holds `xorObfuscate` hex (google_oauth.bzl writes it), which the " +
                  "generated property decodes, so the plaintext is never an action input.",
        ),
        "generated_by": attr.string(
            doc = "What the header names as the generator (default: this target). The Gradle build's task path " +
                  "until the cutover, so both builds generate the same file.",
        ),
        "_generator": attr.label(default = "//bazel/kmp/constants:generator", executable = True, cfg = "exec"),
    },
)
