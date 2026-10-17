"""A Kotlin/Native program compiled straight through `run_konan`: the toolchain's own check (#998)."""

load("//bazel/kmp/native:toolchain.bzl", "KONAN_TARGETS", "TOOLCHAIN_TYPE", "run_konan")

def _impl(ctx):
    target = KONAN_TARGETS[ctx.attr.target]
    # konanc appends the target's executable suffix to -o unless it's already there.
    exe = ctx.actions.declare_file(ctx.label.name + (".exe" if target == "mingw_x64" else ".kexe"))
    args = ctx.actions.args()
    args.add_all(["-target", target, "-p", "program", "-Werror", "-o", exe])
    args.add_all(ctx.files.srcs)
    run_konan(
        ctx,
        tool = "konanc",
        target = target,
        arguments = [args],
        inputs = ctx.files.srcs,
        outputs = [exe],
        mnemonic = "KotlinNativeLink",
        progress_message = "Linking Kotlin/Native %{label}",
    )
    return [DefaultInfo(files = depset([exe]), executable = exe)]

konan_smoke_program = rule(
    implementation = _impl,
    attrs = {
        "srcs": attr.label_list(allow_files = [".kt"]),
        "target": attr.string(default = "linuxX64", values = KONAN_TARGETS.keys()),
    },
    executable = True,
    toolchains = [TOOLCHAIN_TYPE],
)
