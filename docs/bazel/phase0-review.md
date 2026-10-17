# Phase 0 go/no-go review (#1006)

Status: **evidence gathered, decision pending.** The decision (go / no-go / go with changes) is the
maintainer's, to be recorded on #995.

## What Phase 0 built

| Issue | Result |
|---|---|
| #996, #997 | Workspace; `kmp_library`/`kmp_test` JVM compile with K2 fragment flags (rules_kotlin, patched) |
| #998 | Hermetic Kotlin/Native toolchain: distribution and every konan dependency sha256-pinned, nothing fetched at build time, nothing written to `~/.konan` |
| #999 | Native klib compile and native tests (`kt_native_klib`, `kt_native_test_binary`, `KnTestLauncher`) |
| #1000 | KMP Maven resolution from Gradle Module Metadata (Go resolver, lockfile, `@maven_kmp`); rules_jvm_external retired |
| #1001 | Compose, serialization, Metro, Mappie and Mokkery on JVM and native, with KGP's own options |
| #1002 | Metro contributions cross module boundaries (jars and klibs) |
| #1003 | SQLDelight codegen, byte-identical to Gradle's |
| #1004 | Gazelle extension: the spike's BUILD files are generated, `--mode=diff` clean |
| #1005 | IDE evaluation prepared; verdict pending (see `ide-evaluation.md`) |

## Test parity

`bazel test` against the same tasks in Gradle (`jvmTest`, `linuxX64Test`), compared case by case after stripping
KGP's `[jvm]`/`[linuxX64]` suffixes. No expectation was edited.

| Module | JVM (Gradle / Bazel) | linuxX64 (Gradle / Bazel) |
|---|---|---|
| utils/bigdecimal | 31 / 31 | 31 / 31 |
| utils/files | 5 / 5 | 5 / 5 |
| app/model/core | 34 / 34 | 34 / 34 |
| app/model/rules | 8 / 8 | 8 / 8 |
| app/db/read | 60 / 60 | 59 / 59 |

Same test names and outcomes in every row. The other spike modules have no tests. SQLDelight's generated code
for `app/db/schema` and `app/db/read` is byte-identical to Gradle's (#1003).

## Build times

Same work on both: the 12 spike modules' JVM and linuxX64 tests, plus their linuxArm64 and mingwX64 compiles.
Linux x64, 8 cores, 31 GB. Gradle with the configuration cache on and the build cache off, daemon warm; Bazel
with no disk or remote cache, server warm, external repositories already fetched. "One file" adds a private
top-level property to `app/model/core`'s `Money.kt` (an ABI-neutral change).

| Build | Gradle | Bazel |
|---|---|---|
| Clean | 139.0 s | 214.8 s |
| No-op | 7.1 s | 0.3 s |
| One file in `app/model/core` | 78.0 s | 134.7 s |

Bazel is slower today on everything but the no-op. That is expected at this stage and is not the case for
the move: with no remote cache, Bazel's advantage (reusing outputs across machines and CI runs, #1017) doesn't
show. The likely costs, not yet profiled:

- **No compile avoidance.** ABI jars are off (Metro and Compose metadata must reach downstream compiles), so
  an ABI-neutral edit still recompiles every dependent, on the JVM and on each native target. Gradle's
  incremental compilation skips most of that work, which is why its one-file build is so much cheaper.
- **konanc runs as a fresh JVM per action.** It has no persistent worker, while Gradle keeps the Kotlin/Native
  compiler warm in its daemon. A worker for konanc is the obvious first optimization.
- **Every native test binary is linked twice over.** Each test target relinks its whole dependency closure.

Both sides ran on the same machine, one after the other, with nothing else running.

## What turned out hard (and what didn't)

- **Kotlin/Native from scratch**: less work than feared. konan runs fine as a plain JVM action once its data
  dir is inside the repository. Two traps took the time: konan matches `-Xinclude` libraries by absolute path
  (fixed by a 20-line launcher), and partial linkage hides a missing library until runtime (now a link error).
  JetBrains' published `.sha256` files are all the same wrong value, so pins hash the archives themselves.
- **Maven variants**: a resolver was needed (rules_jvm_external can't pick klibs). It's about 900 lines of Go
  with no dependencies, and parity on the spike was exact, forces included.
- **Fragments**: K2 needs every source in a fragment, and treats a lone `commonMain` as the platform module.
  KGP always names the full hierarchy; we now do too. rules_kotlin can't fragment a srcjar, so SQLDelight output
  compiles as its own JVM target.
- **Compiler plugins**: the same embeddable jars work on both platforms; options are copied from KGP's logged
  arguments. Mappie keeps state between compiles, which needed a per-action temp dir.
- **Gazelle**: an MVP in a few hundred lines; one package per module made resolution exact. Exports (Gradle
  `api`) still need directives until #1007 infers them.
- **IDE**: the open risk. The plugin only knows rules_kotlin's JVM targets, so native source sets and
  expect/actual are predicted to be poor (see `ide-evaluation.md`).

## Revised estimate for Phases 1–4

The risky pieces (native toolchain, variant resolution, fragments, plugins) are done; what remains is mostly
the same pattern repeated, plus rules nobody has written for KMP yet:

| Phase | Issues | Estimate | Main risk |
|---|---|---|---|
| 1. CLI closure on JVM + native | #1007–#1017 | 2 weeks | bundled SQLite (cinterop rule), the `mm` executables, ktlint/detekt |
| 2. Desktop app | #1018–#1020 | 2 weeks | Compose resources, ProGuard and jpackage rules (none exist upstream for KMP) |
| 3. Android | #1021–#1025 | 3 weeks | rules_android + KMP: AGP's manifest merge, R8, managed-device tests |
| 4. Cutover | #1026–#1031 | 1 week | coverage parity, Pages tooling, CI timing with the remote cache |

Android is the least known and most likely to slip; the rest builds on what Phase 0 proved.

## Options

1. **Go.** Continue with Phase 1 as planned.
2. **Go with changes.** Continue, but settle the IDE story before Phase 2: either IDE-only JVM targets for native
   source sets (cheap, partial) or an aspect extension for `KlibInfo` (real fix, more work), and accept that
   `commonMain` is analysed as JVM in the IDE, as today.
3. **No-go.** Stay on Gradle, and close the stack after salvaging what's useful without Bazel. Nothing obvious
   is: the resolver and the native toolchain only make sense under Bazel.

Recommendation: **option 2**, if the IDE checklist confirms JVM code works and only native source sets and
expect/actual are poor. If basic JVM navigation is broken too, no-go.
