"""An AAR's parts (#1023): `:jar` in every AAR repository `maven_kmp` writes.

Its code is the jar modules compile against and run with, as before. The rest is what an app packages: the
manifest it merges, the resources (as they are, and compiled here once, `aapt2 compile`, as AGP's AAR transform does), `R.txt`
(the symbols the library's R class holds), assets, native libraries, keep rules and baseline profile, carried by
`AarInfo` for `android_apk` (`apk.bzl`) to collect.
"""

AarInfo = provider(
    doc = "An AAR's Android parts.",
    fields = {
        "jar": "The code jar.",
        "manifest": "AndroidManifest.xml.",
        "root": "The directory the AAR is extracted in (assets/ and jni/ are under it).",
        "res": "The compiled resources (aapt2 flat files, zipped), or None.",
        "raw_res": "The resource files as the AAR has them (R8 reads their `tools:keep` rules).",
        "r_txt": "R.txt, or None.",
        "files": "A depset of the assets and native libraries.",
        "proguard": "proguard.txt, or None.",
        "art_profile": "baseline-prof.txt, or None.",
        "lint_jar": "lint.jar, the AAR's lint checks (AGP runs them on the modules that use it), or None.",
        "public_txt": "public.txt, the resources it makes public (lint's PrivateResource), or None.",
        "annotations": "annotations.zip, its external annotations (lint's typedef checks), or None.",
    },
)

def _first(files):
    return files[0] if files else None

def _aar_import_impl(ctx):
    manifest = ctx.file.manifest
    res = None
    if ctx.files.res:
        res = ctx.actions.declare_file(ctx.label.name + "_res.zip")
        ctx.actions.run(
            executable = ctx.executable._aapt2,
            arguments = ["compile", "--dir", manifest.dirname + "/res", "-o", res.path],
            inputs = ctx.files.res,
            outputs = [res],
            mnemonic = "Aapt2Compile",
            progress_message = "Compiling resources of %{label}",
        )
    return [
        DefaultInfo(files = depset([ctx.file.jar])),
        AarInfo(
            jar = ctx.file.jar,
            manifest = manifest,
            root = manifest.dirname,
            res = res,
            raw_res = ctx.files.res,
            r_txt = _first(ctx.files.r_txt),
            files = depset(ctx.files.assets + ctx.files.jni),
            proguard = _first(ctx.files.proguard),
            art_profile = _first(ctx.files.art_profile),
            lint_jar = _first(ctx.files.lint_jar),
            public_txt = _first(ctx.files.public_txt),
            annotations = _first(ctx.files.annotations),
        ),
    ]

aar_import = rule(
    implementation = _aar_import_impl,
    attrs = {
        "annotations": attr.label_list(allow_files = True),
        "art_profile": attr.label_list(allow_files = True),
        "assets": attr.label_list(allow_files = True),
        "jar": attr.label(allow_single_file = [".jar"], mandatory = True),
        "jni": attr.label_list(allow_files = True),
        "lint_jar": attr.label_list(allow_files = True),
        "manifest": attr.label(allow_single_file = True, mandatory = True),
        "proguard": attr.label_list(allow_files = True),
        "public_txt": attr.label_list(allow_files = True),
        "r_txt": attr.label_list(allow_files = True),
        "res": attr.label_list(allow_files = True),
        "_aapt2": attr.label(default = Label("@aapt2"), executable = True, cfg = "exec", allow_single_file = True),
    },
    doc = "An AAR: its code jar (DefaultInfo) and its Android parts (AarInfo).",
)
