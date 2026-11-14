"""`kmp_ide_module`: a Kotlin JVM module for the JetBrains Bazel plugin that never runs kotlinc."""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("@rules_kotlin//kotlin/internal:defs.bzl", "JAVA_TOOLCHAIN_TYPE", "KtJvmInfo", "TOOLCHAIN_TYPE")

def _kmp_ide_module_impl(ctx):
    java_toolchain = ctx.toolchains[JAVA_TOOLCHAIN_TYPE].java
    srcs = ctx.files.srcs

    # The plugin builds every module's jars on sync, so the class jar is an empty one rather than a compile that
    # fails on `kotlinx.cinterop` and the other native-only APIs.
    class_jar = ctx.actions.declare_file(ctx.label.name + ".jar")
    java_common.pack_sources(ctx.actions, java_toolchain = java_toolchain, output_source_jar = class_jar)
    source_jar = ctx.actions.declare_file(ctx.label.name + "-sources.jar")
    java_common.pack_sources(ctx.actions, java_toolchain = java_toolchain, output_source_jar = source_jar, sources = srcs)

    java_info = JavaInfo(
        output_jar = class_jar,
        compile_jar = class_jar,
        source_jar = source_jar,
        deps = [d[JavaInfo] for d in ctx.attr.deps],
        exports = [d[JavaInfo] for d in ctx.attr.exports],
    )
    return [
        DefaultInfo(files = depset([class_jar])),
        java_info,
        KtJvmInfo(
            srcs = srcs,
            module_name = ctx.attr.module_name,
            module_jars = [class_jar],
            language_version = ctx.toolchains[TOOLCHAIN_TYPE].api_version,
            exported_compiler_plugins = depset(),
            # rules_kotlin's own shape: the aspect reads `ijar` too, which a JavaOutput lacks.
            outputs = struct(jars = [struct(
                class_jar = class_jar,
                compile_jar = class_jar,
                ijar = class_jar,
                source_jars = [source_jar],
            )]),
            transitive_compile_time_jars = java_info.transitive_compile_time_jars,
            transitive_source_jars = java_info.transitive_source_jars,
            additional_generated_source_jars = [],
            all_output_jars = [class_jar],
        ),
    ]

kmp_ide_module = rule(
    implementation = _kmp_ide_module_impl,
    doc = """What the JetBrains Bazel plugin's sync aspect reads of a `kt_jvm_library` (`KtJvmInfo`, `JavaInfo` and
    the `srcs`, `deps`, `exports` and `plugins` attributes), with an empty class jar: a module for source sets the
    JVM can't compile (nativeMain's `kotlinx.cinterop`, a library with no JVM jar), so a sync never fails on them.""",
    attrs = {
        "srcs": attr.label_list(allow_files = [".kt"]),
        "deps": attr.label_list(providers = [JavaInfo]),
        "exports": attr.label_list(providers = [JavaInfo]),
        # Only read by the aspect, for the module's compiler plugins (Compose, Metro).
        "plugins": attr.label_list(),
        "module_name": attr.string(),
    },
    toolchains = [TOOLCHAIN_TYPE, JAVA_TOOLCHAIN_TYPE],
    provides = [JavaInfo, KtJvmInfo],
)
