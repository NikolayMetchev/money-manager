"""`kmp_launcher_test`: a native or browser test, run by the Kotlin/Native test launcher (`:launcher`)."""

def _impl(ctx):
    launcher = ctx.executable._launcher

    # The launcher is the test's own executable, named as Windows wants one (`.exe`); no wrapper script, so the
    # rule is the same on every OS. `args` (the test binary, or node and the test program) reach it as they are.
    exe = ctx.actions.declare_file(ctx.label.name + ("." + launcher.extension if launcher.extension == "exe" else ""))
    ctx.actions.symlink(output = exe, target_file = launcher, is_executable = True)
    runfiles = ctx.runfiles(files = ctx.files.data).merge(ctx.attr._launcher[DefaultInfo].default_runfiles)
    for target in ctx.attr.data:
        runfiles = runfiles.merge(target[DefaultInfo].default_runfiles)
    return [DefaultInfo(executable = exe, runfiles = runfiles)]

kmp_launcher_test = rule(
    implementation = _impl,
    doc = "Runs `args` through the test launcher, which turns their TeamCity output into `test.xml`.",
    attrs = {
        "data": attr.label_list(allow_files = True, doc = "The test binary or program, and what the tests read."),
        "_launcher": attr.label(
            default = Label("//bazel/kmp/testing/launcher"),
            executable = True,
            cfg = "target",
        ),
    },
    test = True,
)
