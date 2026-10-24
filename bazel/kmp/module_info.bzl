"""What each module declares, for reports over the whole build (the Pages site's module graph).

`kmp_library` and `kmp_test` each emit `:<name>_module`, a `kmp_module_info` holding their dependencies as written,
keyed by the attribute that declares them (`deps`, `exports`, `jvm_deps`, `device_deps`, …). They're strings, not
labels: a report needs no compile, and a restricted module (`kmp_visibility`) would otherwise refuse them.
`//:modules`, which Gazelle keeps listing every one, gathers them.
"""

KmpModuleInfo = provider(
    doc = "One module's (or its tests') declared dependencies.",
    fields = {
        "module": "the module's package, `//app/db/core`",
        "target": "the declaring target: the module (`//app/db/core`) or its tests (`//app/db/core:test`)",
        "attributes": "attribute name → the dependency labels it declares, as written",
    },
)

def _module_info_impl(ctx):
    return [KmpModuleInfo(module = ctx.attr.module, target = ctx.attr.target, attributes = ctx.attr.attributes)]

kmp_module_info = rule(
    implementation = _module_info_impl,
    doc = "A module's declared dependencies (KmpModuleInfo).",
    attrs = {
        "attributes": attr.string_list_dict(),
        "module": attr.string(mandatory = True),
        "target": attr.string(mandatory = True),
    },
)

KmpModulesInfo = provider(
    doc = "Every module's KmpModuleInfo.",
    fields = {"modules": "a list of KmpModuleInfo"},
)

def _modules_impl(ctx):
    return [KmpModulesInfo(modules = [m[KmpModuleInfo] for m in ctx.attr.modules])]

kmp_modules = rule(
    implementation = _modules_impl,
    doc = "Every module's `:<name>_module` (Gazelle keeps the list): what whole-build reports read.",
    attrs = {
        "modules": attr.label_list(providers = [KmpModuleInfo]),
    },
)

def module_info(name, target, attributes):
    """`:<name>_module` for the target `target` (in this package), from `{attribute: deps}`, empty ones dropped."""
    package = "//" + native.package_name()
    kmp_module_info(
        name = name + "_module",
        module = package,
        target = package if target == None else "%s:%s" % (package, target),
        attributes = {attribute: deps for attribute, deps in attributes.items() if deps},
        visibility = ["//visibility:public"],
    )
