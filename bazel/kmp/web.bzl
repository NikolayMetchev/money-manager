"""Kotlin/Wasm (`wasmJs`) and Kotlin/JS (`js`) libraries (#84), the browser counterpart of native.bzl.

A module compiles to one klib per browser target in a single K2 invocation over its fragments, like KGP:
`common ← nonJvm ← web ← wasmJs|js`, where `nonJvm` is the source set the browser shares with native and is only
named when the module has it. The compilers are the ones konan's jar carries (`run_kotlinc`), so the compiler
plugins are the native ones, and a program is linked from klibs into JavaScript (and WebAssembly) the way KGP's
`browser { binaries.executable() }` does.
"""

load(":native.bzl", "fragment_args", "non_jvm_chain", "unique_name")
load("//bazel/kmp/native:toolchain.bzl", "TOOLCHAIN_TYPE", "run_kotlinc")
load("//bazel/kmp/plugins:plugins.bzl", "NativePluginInfo", "native_plugin_args")

# The browser targets, by KGP's target name: Kotlin/Wasm for browsers (not WASI), and Kotlin/JS's IR compiler.
WEB_TARGETS = ["wasmJs", "js"]

WebKlibInfo = provider(
    doc = "A Kotlin/Wasm or Kotlin/JS library for one browser target.",
    fields = {
        "klib": "The .klib file, or None for a group with no library of its own.",
        "npm": "depset of the npm packages (`npm_package`'s structs) these klibs need at runtime.",
        "target": "The KGP target name (`wasmJs`, `js`).",
        "transitive_klibs": "depset of this klib and every klib it needs, for -libraries.",
    },
)

NpmPackageInfo = provider(
    doc = "An npm package and the packages it depends on.",
    fields = {"packages": "depset of struct(name, package_json, files): this package and its dependencies."},
)

def _npm_package_impl(ctx):
    root = "/".join([p for p in (ctx.label.workspace_root, ctx.label.package) if p])
    package_json = [f for f in ctx.files.srcs if f.path == (root + "/" if root else "") + "package.json"]
    if len(package_json) != 1:
        fail("%s: no package.json at the package's root" % ctx.label)
    package = struct(name = ctx.attr.package_name, package_json = package_json[0], files = tuple(ctx.files.srcs))
    return [NpmPackageInfo(packages = depset([package], transitive = [d[NpmPackageInfo].packages for d in ctx.attr.deps]))]

npm_package = rule(
    implementation = _npm_package_impl,
    doc = "An npm package from its registry tarball, which a browser klib names in its `package.json`.",
    attrs = {
        "deps": attr.label_list(providers = [NpmPackageInfo]),
        "package_name": attr.string(mandatory = True, doc = "Its name on npm, as code imports it."),
        "srcs": attr.label_list(allow_files = True, mandatory = True),
    },
    provides = [NpmPackageInfo],
)

WebProgramInfo = provider(
    doc = "A linked browser program.",
    fields = {
        "dir": "The directory holding `<base_name>.mjs` (and its `.wasm`).",
        "npm": "depset of the npm packages it imports.",
        "target": "The KGP target name.",
    },
)

def _npm(deps):
    return depset(transitive = [d[WebKlibInfo].npm for d in deps])

def node_modules(ctx, root, packages):
    """Lays `packages` out as `<root>/node_modules/<name>/…`, symlinks to their files, as npm installs them, so Node
    resolves a program's bare imports (`@js-joda/core`) from `root`."""
    by_name = {}
    out = []
    for package in packages.to_list():
        if package.name in by_name and by_name[package.name] != package.package_json:
            fail("two versions of the npm package %s: %s and %s" % (package.name, by_name[package.name].short_path, package.package_json.short_path))
        by_name[package.name] = package.package_json
        prefix = package.package_json.short_path[:-len("package.json")]
        for f in package.files:
            link = ctx.actions.declare_file("%s/node_modules/%s/%s" % (root, package.name, f.short_path[len(prefix):]))
            ctx.actions.symlink(output = link, target_file = f)
            out.append(link)
    return out

# Each target's compiler, and the flags that pick its flavour.
_COMPILER = {
    "js": struct(tool = "kotlinc-js", flags = []),
    "wasmJs": struct(tool = "kotlinc-wasm", flags = ["-Xwasm-target=wasm-js"]),
}

def _stdlib(ctx):
    return ctx.attr._stdlib_wasm_js if ctx.attr.target == "wasmJs" else ctx.attr._stdlib_js

def _libraries(ctx, deps):
    """Every klib `deps` need, the stdlib included."""
    return depset(transitive = [d[WebKlibInfo].transitive_klibs for d in deps + [_stdlib(ctx)]])

def _compile_klib(ctx, out, chain, deps, friends, module_name, mnemonic):
    compiler = _COMPILER[ctx.attr.target]
    transitive = _libraries(ctx, deps + friends)
    args = ctx.actions.args()
    args.add_all(compiler.flags)

    # The klib is `<ir-output-dir>/<ir-output-name>.klib`.
    args.add_all(["-ir-output-dir", out.dirname, "-ir-output-name", out.basename[:-len(".klib")]])
    args.add("-Xir-module-name=" + module_name)
    args.add_all(["-Werror", "-Xexpect-actual-classes"])
    if ctx.attr.target == "js":
        # The link's ES modules, so the frontend checks `@JsModule` declarations as ES imports, not UMD ones.
        args.add_all(["-target", "es2015"])
    plugin_jars = native_plugin_args(args, ctx.attr.plugins)
    srcs = fragment_args(args, chain)
    args.add_joined("-libraries", transitive, join_with = ctx.configuration.host_path_separator)
    friend_klibs = [f[WebKlibInfo].klib for f in friends if f[WebKlibInfo].klib]
    if friend_klibs:
        args.add_joined(friend_klibs, join_with = ctx.configuration.host_path_separator, format_joined = "-Xfriend-modules=%s")
    run_kotlinc(
        ctx,
        tool = compiler.tool,
        arguments = [args],
        inputs = depset(srcs + plugin_jars, transitive = [transitive]),
        outputs = [out],
        mnemonic = mnemonic,
        progress_message = "Compiling Kotlin %{label} for " + ctx.attr.target,
    )
    return WebKlibInfo(
        klib = out,
        npm = _npm(deps + friends + [_stdlib(ctx)]),
        target = ctx.attr.target,
        transitive_klibs = depset([out], transitive = [transitive]),
    )

def web_chain(ctx, suffix):
    """The module's fragments for `ctx.attr.target`, named by source set (`commonMain`, `wasmJsTest`, …)."""
    chain = non_jvm_chain(ctx) + [
        ("web", ctx.files.web_srcs),
        (ctx.attr.target, ctx.files.target_srcs),
    ]
    return [(fragment + suffix, files) for fragment, files in chain]

def _klib_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name + ".klib")
    info = _compile_klib(
        ctx,
        out = out,
        chain = web_chain(ctx, "Main"),
        deps = ctx.attr.deps,
        friends = [],
        module_name = unique_name(ctx.label, ""),
        mnemonic = "KotlinWebCompile",
    )
    return [DefaultInfo(files = depset([out])), info]

_STDLIB_ATTRS = {
    "_stdlib_js": attr.label(default = "@maven_kmp//:org_jetbrains_kotlin_kotlin_stdlib_js", providers = [WebKlibInfo]),
    "_stdlib_wasm_js": attr.label(default = "@maven_kmp//:org_jetbrains_kotlin_kotlin_stdlib_wasmJs", providers = [WebKlibInfo]),
}

WEB_SRC_ATTRS = _STDLIB_ATTRS | {
    "common_generated": attr.label_list(allow_files = True, doc = "Directories of generated commonMain sources."),
    "common_srcs": attr.label_list(allow_files = [".kt"]),
    "non_jvm_srcs": attr.label_list(allow_files = [".kt"], doc = "nonJvmMain: shared with the native targets."),
    "plugins": attr.label_list(providers = [NativePluginInfo], doc = "Compiler plugins, in the order they run."),
    "target": attr.string(mandatory = True, values = WEB_TARGETS),
    "target_srcs": attr.label_list(allow_files = [".kt"], doc = "The target's own source set (wasmJsMain, jsMain)."),
    "web_srcs": attr.label_list(allow_files = [".kt"], doc = "webMain: shared by wasmJs and js."),
}

kt_web_klib = rule(
    implementation = _klib_impl,
    doc = "A module's klib for one browser target.",
    attrs = WEB_SRC_ATTRS | {
        "deps": attr.label_list(providers = [WebKlibInfo]),
    },
    provides = [WebKlibInfo],
    toolchains = [TOOLCHAIN_TYPE],
)

def link_web(ctx, out, base_name, include, libraries, production, mnemonic):
    """Links the klib `include` and its `libraries` into `out`, a directory: `<base_name>.mjs`, plus
    `<base_name>.wasm` and the glue modules it imports on wasmJs.

    A production link (KGP's `productionExecutable`) drops unreachable code; a development one (its tests') keeps
    it and links faster.
    """
    compiler = _COMPILER[ctx.attr.target]
    args = ctx.actions.args()
    args.add_all(compiler.flags)
    args.add("-Xir-produce-js")
    if ctx.attr.target == "js":
        # ES modules (`.mjs`), as the wasm glue is.
        args.add_all(["-target", "es2015"])
    args.add_all(["-ir-output-dir", out.path, "-ir-output-name", base_name])
    args.add("-Xir-module-name=" + base_name)
    args.add_all(["-Werror", "-Xpartial-linkage-loglevel=ERROR"])
    if production:
        args.add("-Xir-dce")
    args.add("-Xinclude=" + include.path)
    # Every klib before the klibs it depends on. The linker takes a class from the first library that declares it, and
    # on js kotlinx-browser redeclares kotlin-dom-api-compat's DOM classes with the overloads libraries are compiled
    # against (`EventTarget.addEventListener(String, (Event) -> Unit)`, which Compose and ktor call).
    ordered = depset(order = "topological", transitive = [libraries])
    args.add_joined("-libraries", [k for k in ordered.to_list() if k != include], join_with = ctx.configuration.host_path_separator)
    run_kotlinc(
        ctx,
        tool = compiler.tool,
        arguments = [args],
        inputs = libraries,
        outputs = [out],
        mnemonic = mnemonic,
        progress_message = "Linking %{label} for " + ctx.attr.target,
    )

def _executable_impl(ctx):
    library = ctx.attr.library[WebKlibInfo]
    out = ctx.actions.declare_directory(ctx.label.name)
    link_web(
        ctx,
        out = out,
        base_name = ctx.attr.base_name,
        include = library.klib,
        libraries = depset(transitive = [library.transitive_klibs, _stdlib(ctx)[WebKlibInfo].transitive_klibs]),
        production = True,
        mnemonic = "KotlinWebLink",
    )
    return [DefaultInfo(files = depset([out])), WebProgramInfo(dir = out, npm = library.npm, target = ctx.attr.target)]

kt_web_executable = rule(
    implementation = _executable_impl,
    doc = "A browser program for one target, `<base_name>.mjs` in a directory: KGP's production executable.",
    attrs = _STDLIB_ATTRS | {
        "base_name": attr.string(mandatory = True, doc = "The entry module's name, without `.mjs`."),
        "library": attr.label(mandatory = True, providers = [WebKlibInfo], doc = "The klib whose `main` is the program."),
        "target": attr.string(mandatory = True, values = WEB_TARGETS),
    },
    provides = [WebProgramInfo],
    toolchains = [TOOLCHAIN_TYPE],
)

def _dist_impl(ctx):
    lines = []
    inputs = []
    packages = {}
    for program in ctx.attr.programs:
        info = program[WebProgramInfo]
        lines.append("program\t%s\t%s" % (info.target, info.dir.path))
        inputs.append(info.dir)
        for jar in ctx.files.runtime_jars:
            lines.append("jar\t%s\t%s" % (info.target, jar.path))
        for package in info.npm.to_list():
            if package.name in packages and packages[package.name] != package.package_json:
                fail("two versions of the npm package %s" % package.name)
            if package.name not in packages:
                packages[package.name] = package.package_json
                prefix = package.package_json.path[:-len("package.json")]
                lines.append("package\t" + package.name)
                for f in package.files:
                    lines.append("file\tnode_modules/%s/%s\t%s" % (package.name, f.path[len(prefix):], f.path))
                    inputs.append(f)
    inputs.extend(ctx.files.runtime_jars)
    for f in ctx.files.srcs:
        lines.append("file\t%s\t%s" % (f.basename, f.path))
        inputs.append(f)
    manifest = ctx.actions.declare_file(ctx.label.name + ".manifest")
    ctx.actions.write(manifest, "\n".join(lines) + "\n")

    out = ctx.actions.declare_directory(ctx.label.name)
    ctx.actions.run(
        executable = ctx.toolchains["@rules_nodejs//nodejs:toolchain_type"].nodeinfo.node,
        arguments = [ctx.file._script.path, out.path, manifest.path],
        inputs = inputs + [manifest, ctx.file._script],
        outputs = [out],
        mnemonic = "KotlinWebDist",
        progress_message = "Laying out the site %{label}",
    )
    return [DefaultInfo(files = depset([out]), runfiles = ctx.runfiles(files = [out]))]

kt_web_dist = rule(
    implementation = _dist_impl,
    doc = """A browser site in one directory, served as is: each program in a directory named after its target with the
    runtime jars' files beside it, the npm packages under `node_modules` with every bare import rewritten to a relative
    path (browsers resolve none, and a worker can't use an import map), and `srcs` at the root.""",
    attrs = {
        "programs": attr.label_list(mandatory = True, providers = [WebProgramInfo]),
        "runtime_jars": attr.label_list(allow_files = [".jar"], doc = "Jars of browser files every program loads beside itself (Skiko's)."),
        "srcs": attr.label_list(allow_files = True, doc = "Files for the site's root (the page and its loader)."),
        "_script": attr.label(default = "//bazel/kmp/web:dist.mjs", allow_single_file = True),
    },
    toolchains = ["@rules_nodejs//nodejs:toolchain_type"],
)

def _test_impl(ctx):
    associate = ctx.attr.associate[WebKlibInfo]
    info = _compile_klib(
        ctx,
        out = ctx.actions.declare_file(ctx.label.name + "_test.klib"),
        chain = web_chain(ctx, "Test"),
        deps = ctx.attr.deps,
        friends = [ctx.attr.associate],
        module_name = unique_name(ctx.attr.associate.label, "_test"),
        mnemonic = "KotlinWebTestCompile",
    )

    # The test klib's generated `main` registers every test with kotlin-test's adapter (runner.mjs).
    out = ctx.actions.declare_directory(ctx.label.name)
    link_web(
        ctx,
        out = out,
        base_name = "test",
        include = info.klib,
        libraries = info.transitive_klibs,
        production = False,
        mnemonic = "KotlinWebTestLink",
    )

    # runner.mjs resolves the program's npm imports from `<program>_node_modules`.
    modules = node_modules(ctx, ctx.label.name + "_node_modules", info.npm)
    return [DefaultInfo(files = depset([out]), runfiles = ctx.runfiles(files = modules))]

kt_web_test_program = rule(
    implementation = _test_impl,
    doc = "A module's tests for one browser target, linked into `test.mjs` in a directory for runner.mjs.",
    attrs = WEB_SRC_ATTRS | {
        "associate": attr.label(mandatory = True, providers = [WebKlibInfo]),
        "deps": attr.label_list(providers = [WebKlibInfo]),
    },
    toolchains = [TOOLCHAIN_TYPE],
)

def _node_impl(ctx):
    node = ctx.toolchains["@rules_nodejs//nodejs:runtime_toolchain_type"].nodeinfo.node
    return [DefaultInfo(files = depset([node]))]

node_runtime = rule(
    implementation = _node_impl,
    doc = "The `node` binary for the target platform, from rules_nodejs' toolchain, for a test's data.",
    toolchains = ["@rules_nodejs//nodejs:runtime_toolchain_type"],
)

def _import_impl(ctx):
    deps = [d[WebKlibInfo].transitive_klibs for d in ctx.attr.deps]
    return [
        DefaultInfo(files = depset(ctx.files.klibs)),
        WebKlibInfo(
            klib = ctx.files.klibs[0],
            npm = depset(transitive = [_npm(ctx.attr.deps)] + [p[NpmPackageInfo].packages for p in ctx.attr.npm]),
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
        "npm": attr.label_list(providers = [NpmPackageInfo], doc = "The npm packages its `package.json` names."),
        "target": attr.string(mandatory = True, values = WEB_TARGETS),
    },
    provides = [WebKlibInfo],
)

def _group_impl(ctx):
    return [WebKlibInfo(
        klib = None,
        npm = depset(transitive = [_npm(ctx.attr.deps)] + [p[NpmPackageInfo].packages for p in ctx.attr.npm]),
        target = ctx.attr.target,
        transitive_klibs = depset(transitive = [d[WebKlibInfo].transitive_klibs for d in ctx.attr.deps]),
    )]

kt_web_group = rule(
    implementation = _group_impl,
    doc = "Several klibs under one label, or none: a library that only redirects to others, or only npm packages.",
    attrs = {
        "deps": attr.label_list(providers = [WebKlibInfo]),
        "npm": attr.label_list(providers = [NpmPackageInfo]),
        "target": attr.string(mandatory = True, values = WEB_TARGETS),
    },
    provides = [WebKlibInfo],
)
