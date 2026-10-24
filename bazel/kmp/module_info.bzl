"""What each module declares, per source set, for reports over the whole build (the Pages site's module graph).

`kmp_library` and `kmp_test` each emit `:<name>_module`, a `kmp_module_info` holding their dependencies as written,
keyed by the Gradle configuration that would have declared them (`commonMainImplementation`, `jvmMainApi`,
`commonTestImplementation`, …). They're strings, not labels: a report needs no compile, and a restricted module
(`kmp_visibility`) would otherwise refuse them. `//:modules`, which Gazelle keeps listing every one, gathers them.
"""

KmpModuleInfo = provider(
    doc = "One module's (or its tests') declared dependencies.",
    fields = {
        "module": "the module's package, `//app/db/core`",
        "configurations": "Gradle configuration name → the dependency labels it declares, as written",
    },
)

def _module_info_impl(ctx):
    return [KmpModuleInfo(module = ctx.attr.module, configurations = ctx.attr.configurations)]

kmp_module_info = rule(
    implementation = _module_info_impl,
    doc = "A module's declared dependencies (KmpModuleInfo).",
    attrs = {
        "configurations": attr.string_list_dict(),
        "module": attr.string(mandatory = True),
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

def module_configurations(kind, sets):
    """`{"<sourceSet><Kind>Implementation": deps, "<sourceSet><Kind>Api": exports}` for the non-empty ones.

    Args:
        kind: `Main` or `Test`.
        sets: `[(source set, deps, exports), …]`, e.g. `("common", deps, exports)`.
    """
    out = {}
    for source_set, deps, exports in sets:
        if deps:
            out["%s%sImplementation" % (source_set, kind)] = deps
        if exports:
            out["%s%sApi" % (source_set, kind)] = exports
    return out
