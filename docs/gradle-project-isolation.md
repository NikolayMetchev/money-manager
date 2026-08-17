# Gradle Project Isolation Status

Date tested: 2026-06-06 (previous attempt: 2026-05-24)

## Result

Project isolation (`org.gradle.isolated-projects=true`) is **enabled** in `gradle.properties`. Gradle
9.7 promoted the feature to *incubating* and stabilised the property name — the old
`org.gradle.unsafe.isolated-projects` spelling is a deprecated alias.

`./gradlew build` (tests, kover, buildHealth, detekt, ktlint, Android lint) passes with the flag
on, and the configuration cache entry is stored without isolation violations.

## What was verified

With isolation enabled, all of the following configure and store a configuration-cache entry
cleanly; `build` was also executed to completion:

- `./gradlew build` — BUILD SUCCESSFUL (1881 tasks)
- `./gradlew lintFormat --dry-run`
- `./gradlew :app:main:jvm:run --dry-run`
- `./gradlew :app:main:android:assembleDebug --dry-run`
- `./gradlew :app:ui:core:pixel6api34AndroidDeviceTest --dry-run`

## What unblocked it since the 2026-05-24 attempt

1. **Compose Multiplatform plugin** — the previous blocker. `org.jetbrains.compose` accessed
   `Project.layout`/`Project.tasks` on other projects. Fixed upstream; the version currently in
   use (1.11.0) produces no isolation violations.
2. **Mokkery** (`dev.mokkery` 3.3.0) — re-enabled. Its only violation was
   `MokkeryGradlePlugin.checkKotlinSetup` reading the `dev.mokkery.versionWarnings` property via
   `Project.findProperty`, which dynamically walks up to the parent project when the property is
   undefined. Workaround: define `dev.mokkery.versionWarnings=true` in `gradle.properties` so the
   lookup resolves on the project itself (see comment there). The previous workaround of skipping
   the Mokkery plugin under isolation broke `:app:ui:core` tests (un-transformed `mock()` calls
   throw `MokkeryIntrinsicException`) and has been removed.

## Remaining incompatibilities

1. **gradle-doctor** (`com.osacky.doctor` 0.12.1) — still incompatible. It accesses
   `Project.tasks` and `Project.plugins` across subprojects from the root project (50 violations),
   plus `Project.properties` in `RemoteCacheEstimation`, which fails plugin application outright.
   It is therefore **off unless explicitly opted in** in the root `build.gradle.kts`:
   `-PenableGradleDoctor=true` (with isolation off). The guard deliberately does not test
   `org.gradle.isolated-projects` — isolation can be enabled by the IDE (Android Studio turns it on
   for sync) or a `-D`/CLI flag, which a Gradle-property read cannot observe, and the property name
   itself has already changed once. Re-test when upgrading the plugin.

## Notes / follow-ups

- Detekt, ktlint, SQLDelight, Metro, Mappie, Kover, dependency-analysis (DAGP), Android, and KMP
  plugins all work under isolation at current versions — no plugins needed disabling besides
  gradle-doctor.
- The configuration-cache report still lists *inputs* such as Gradle property reads by KGP/AGP/
  Compose internals; these are recorded inputs, not violations.
- When upgrading any Gradle plugin, watch for new isolation violations: they fail the build at
  configuration-cache store time with `Project ':x' cannot access ... on another project`.
