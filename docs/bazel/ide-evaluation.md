# IntelliJ evaluation: the JetBrains Bazel plugin on the KMP spike (#1005)

Status: **evidence gathered, verdict pending** (the verdict needs someone in the IDE; fill in the checklist
below and post it on #1005).

## Setup

- IntelliJ IDEA 2026.2 with the JetBrains Bazel plugin (`org.jetbrains.bazel` 2026.2.2.1, the one installed
  here).
- Open the repository root as a Bazel project; `.bazelproject` limits sync to the spike modules
  (`utils/bigdecimal`, `utils/files`, `app/model/*`, `app/db/{schema,read}`).
- Close the Gradle project first: both builds on one checkout fight over `.idea`.

## What the build hands the IDE

Read from the plugin's sync aspect (`intellij.libraries.bazel.aspect.sdk`), which knows three rule families:
`@rules_java`, `@rules_kotlin` (through `KtJvmInfo`) and `@rules_python`.

| Target | What the plugin sees |
|---|---|
| `:<name>_jvm` (`kt_jvm_library`) | A Kotlin JVM module: every `commonMain`, `jvmAndroidMain` and `jvmMain` file in **one** module, its jar classpath (Maven jars included), its compiler plugins. |
| `:test_jvm_lib` (`kt_jvm_library`, testonly) | A test module with `associates`, so `internal` resolves. |
| `:<name>_<target>` (`kt_native_klib`), `:test_<target>_bin` | Nothing: our own rules, which the aspect doesn't know. `nativeMain`/`linuxMain`/`appleMain`/`mingwMain` files belong to no module. |
| `:sqldelight` | Its srcjar is a generated source of `:<name>_sqldelight_jvm`, so SQLDelight's generated API resolves (on the JVM). |

## What that predicts

| Question (from #1005) | Expected | Why |
|---|---|---|
| Import and sync time | Fine | Plain rules_kotlin targets; measure it. |
| Navigation/highlighting in `commonMain`, `jvmMain` | Works, as JVM code | Both are in the `_jvm` module. |
| … in `nativeMain`, `linuxMain` | **Doesn't work** | No module owns those files: no highlighting, red references, no navigation. |
| expect ↔ actual navigation | **Likely broken**, maybe with false errors | The IDE sees `expect` and `actual` in one JVM module. Unless it picks up the fragment flags (`-Xmulti-platform -Xfragments…`, which our rules add to the compile action, not to a provider the aspect reads), it may flag every `expect` as having no actual, or as redeclared. Check this first. |
| What `commonMain` is analysed against | **JVM only** | One JVM module. JVM-only APIs in `commonMain` (`java.util.regex`, `DOT_MATCHES_ALL`) won't show as errors, the same blind spot the Gradle project has had without a native target. The build still catches them (native compiles, #1010). |
| Run/debug a JVM test from the gutter | Likely works | `java_test` over a `kt_jvm_library`. Our runner is `KmpJUnitRunner`, not Bazel's, so check that gutter runs filter to the one test (`--test_filter`). |
| Run a native test from the gutter | **Doesn't work** | No module, so no gutter. `bazel test //…:test_linuxX64` from the terminal works. |
| Generated SQLDelight sources visible | Likely works on the JVM | srcjar of a `kt_jvm_library`. |
| Compose preview | Phase 2 | — |

## Checklist (fill in)

| # | Check | Result | Notes |
|---|---|---|---|
| 1 | Sync completes; time from cold | | |
| 2 | `utils/bigdecimal` `BigDecimal.kt` (commonMain): highlighting, Go to declaration into `jvmAndroidMain` actual | | |
| 3 | Same file: any false "expect without actual" / "redeclaration" errors | | |
| 4 | `utils/bigdecimal/src/nativeMain`: is the file part of a module? | | |
| 5 | `app/model/core` (commonMain + jvmMain + native source sets): navigation between them | | |
| 6 | Put `Regex("x", RegexOption.DOT_MATCHES_ALL)` in a commonMain file: does the IDE flag it? | | |
| 7 | Run `BigDecimalTest` from the gutter; debug it with a breakpoint | | |
| 8 | Run a single test method from the gutter (does the filter reach `KmpJUnitRunner`?) | | |
| 9 | `app/db/read`: `MoneyManagerDatabase` and the generated queries resolve, Go to declaration lands in the srcjar | | |
| 10 | Mappie/Metro/serialization code highlights without false errors (plugins applied in the IDE) | | |

## Android Studio (ASwB)

Android Studio 2026.2.1's bundled Bazel plugin (ASwB, query sync) imports the project, but it can't build
dependencies, so no file gets analysis:

- **Its aspect needs Bazel 8 or older.** ASwB writes its aspect into `.aswb/` on every sync. The templates use
  `JavaInfo`, `java_common`, `CcInfo`, `cc_common` and `android_common` as globals, without a `load`. Bazel 9
  removed them, so every dependency build fails with `name 'JavaInfo' is not defined`.
  `--incompatible_autoload_externally` doesn't bring them back for the aspect, and editing `.aswb/` doesn't help
  either, because the next sync rewrites it. This needs an ASwB that supports Bazel 9.
- **Compose tooling target (fixed).** After each sync of a Compose project, ASwB builds
  `@maven_kmp//:androidx_compose_ui_ui_tooling`, the name rules_jvm_external uses, for previews. The hub now has
  ui-tooling under that alias (`maven_kmp.install(aliases = …)`), so this build no longer fails on a missing
  target. It still fails on the aspect, as above.
- Our Android rules (`bazel/kmp/android`) aren't `rules_android`, so ASwB wouldn't see Android modules even with
  a working aspect.

Until then, use IntelliJ with the JetBrains Bazel plugin (above).

## Workarounds, if native and expect/actual fail as predicted

1. **IDE-only JVM targets per native source set.** Gazelle could emit a `kt_jvm_library` per native family
   (common + native + linux sources) tagged `manual`, purely so the IDE has a module for those files. Highlighting
   would be against the JVM stdlib, not the native one: references to `platform.posix` or `kotlinx.cinterop` would
   still be red. Cheap, partial.
2. **Teach the aspect about `KlibInfo`.** The aspect SDK reads providers by name; a small aspect extension (or
   upstreaming KMP support) could describe a klib target as a Kotlin/Native module with its fragments. This is
   the real fix and the most work; it depends on JetBrains' plugin APIs staying stable.
3. **Keep Gradle for the IDE only.** This goes against the no-parallel-builds rule (#995) and doubles maintenance.
   It's listed only so the go/no-go (#1006) can weigh it.

The verdict and the choice between these belong in #1006.
