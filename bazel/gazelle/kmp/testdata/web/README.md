Browser targets: `kmp_targets` takes `wasmJs` and `js`, or `web` for both, always written in that order. nonJvmMain's
imports go to `non_jvm_deps`, webMain's to `web_deps` and each target's to its own attribute. A package no one library
has for both targets (Skiko's wasm and js runtimes) resolves per target, and the commonMain metadata compile gets the
library the common metadata resolves in `metadata_deps` (`lib/ui`'s Material 3 is another library on the JVM), and the DOM (`kotlinx.browser`, `org.w3c`) is
kotlinx-browser, a library like any other. Tests get `web_targets` only when a source set the browser compiles has tests (`lib/server`'s are JVM
only), and a source set whose dir has gone loses its glob (`lib/server`'s `nativeMain`, moved away). `kmp_web_tests false` keeps a module's tests off the browser targets (`lib/files`'s need a file system).
`kmp_main_platform web` compiles a non-KMP module's `src/main` for the browser targets alone (`main`, an app).
