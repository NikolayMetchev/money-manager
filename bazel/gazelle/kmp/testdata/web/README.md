Browser targets: `kmp_targets` takes `wasmJs` and `js`, or `web` for both, always written in that order. nonJvmMain's
imports go to `non_jvm_deps`, webMain's to `web_deps` and each target's to its own attribute. A package no one library
has for both targets (Skiko's wasm and js runtimes) resolves per target, and the DOM (`kotlinx.browser`, `org.w3c`) is
the stdlib's. Tests get `web_targets` only when a source set the browser compiles has tests (`lib/server`'s are JVM
only).
