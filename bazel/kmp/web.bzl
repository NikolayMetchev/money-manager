"""Kotlin/Wasm (`wasmJs`) and Kotlin/JS (`js`) libraries (#84), the browser counterpart of native.bzl.

Both compile to klibs with the same compiler, so one provider and one set of rules serve both targets.
"""

# The browser targets, by KGP's target name: Kotlin/Wasm for browsers (not WASI), and Kotlin/JS's IR compiler.
WEB_TARGETS = ["wasmJs", "js"]

WebKlibInfo = provider(
    doc = "A Kotlin/Wasm or Kotlin/JS library for one browser target.",
    fields = {
        "klib": "The .klib file, or None for a group with no library of its own.",
        "target": "The KGP target name (`wasmJs`, `js`).",
        "transitive_klibs": "depset of this klib and every klib it needs, for -libraries.",
    },
)

def _import_impl(ctx):
    deps = [d[WebKlibInfo].transitive_klibs for d in ctx.attr.deps]
    return [
        DefaultInfo(files = depset(ctx.files.klibs)),
        WebKlibInfo(
            klib = ctx.files.klibs[0],
            target = ctx.attr.target,
            transitive_klibs = depset(ctx.files.klibs, transitive = deps),
        ),
    ]

kt_web_import = rule(
    implementation = _import_impl,
    doc = "A prebuilt library: a Maven library's wasmJs or js variant.",
    attrs = {
        "deps": attr.label_list(providers = [WebKlibInfo]),
        "klibs": attr.label_list(mandatory = True, allow_files = [".klib"]),
        "target": attr.string(mandatory = True, values = WEB_TARGETS),
    },
    provides = [WebKlibInfo],
)

def _group_impl(ctx):
    return [WebKlibInfo(
        klib = None,
        target = ctx.attr.target,
        transitive_klibs = depset(transitive = [d[WebKlibInfo].transitive_klibs for d in ctx.attr.deps]),
    )]

kt_web_group = rule(
    implementation = _group_impl,
    doc = "Several klibs under one label, or none: a library that only redirects to others.",
    attrs = {
        "deps": attr.label_list(providers = [WebKlibInfo]),
        "target": attr.string(mandatory = True, values = WEB_TARGETS),
    },
    provides = [WebKlibInfo],
)
