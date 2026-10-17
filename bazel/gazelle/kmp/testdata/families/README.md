Source sets per platform: commonMain's imports go to `deps`, jvmMain's to `jvm_deps`, nativeMain's to
`native_deps` and each native family's to its own attribute; a dependency a parent source set already has
isn't repeated. `kmp_export` moves a dependency to `exports`, `# keep` keeps one no import explains, a test
fixture module resolves from its package, and plugins are inferred from the sources. `kmp_generated_srcs` adds a generated source (`kotlin_constants`) to commonMain. `kmp_cinterops` names a cinterop the module compiles
with, and a module with a cinterop but no Kotlin of its own (`lib/cbind`, like utils/sqlite3) still gets its `kmp_library`.
