# Gradle Project Isolation Status

Date tested: 2026-05-24

## Result

Project isolation (`org.gradle.unsafe.isolated-projects=true`) is **not yet fully compatible** with this build.
Several repository-level blockers were fixed, but one plugin-level blocker remains.

## What was tested

1. Enabled:
   - `org.gradle.unsafe.isolated-projects=true`
2. Ran:
   - `./gradlew.bat help --console=plain`
   - `./gradlew.bat tasks --console=plain`

Both commands were used repeatedly while applying fixes. Remaining failure is during configuration cache storage with isolated-project violations.

## Why it fails

### Fixed in this change

1. Root build script cross-project configuration was removed:
   - removed `allprojects { ... }` and `subprojects { ... }`
   - removed dynamic `subprojects.mapNotNull { it.tasks.findByName(...) }` wiring
2. Convention plugin cross-project file access was removed:
   - `gradle/build-logic/src/main/kotlin/moneymanager.kotlin-convention.gradle.kts`
   - `rootProject.file("detekt.yml")` -> `rootDir.resolve("detekt.yml")`
3. Module script cross-project file access was removed:
   - `app/main/android/build.gradle.kts`
   - `app/main/jvm/build.gradle.kts`
   - replaced `rootProject.file("VERSION")` with `rootDir.resolve("VERSION")`
4. Root plugin conflict was mitigated:
   - `com.osacky.doctor` is not applied when isolation is enabled
5. `dev.mokkery` dynamic lookup conflict was mitigated:
   - in `app/ui/core/build.gradle.kts`, plugin application is skipped when isolation is enabled

### Remaining blocker

With the fixes above, the remaining isolation violations come from `org.jetbrains.compose`:

- `Plugin 'org.jetbrains.compose': Project ':app' cannot access 'Project.layout' functionality on another project ':'`
- `Plugin 'org.jetbrains.compose': Project ':app' cannot access 'Project.tasks' functionality on another project ':'`
- `Plugin 'org.jetbrains.compose': Project ':app:main' cannot access 'Project.layout' functionality on another project ':'`
- `Plugin 'org.jetbrains.compose': Project ':app:main' cannot access 'Project.tasks' functionality on another project ':'`
- `Plugin 'org.jetbrains.compose': Project ':app:main:jvm' cannot access 'Project.layout' functionality on another project ':'`
- `Plugin 'org.jetbrains.compose': Project ':app:main:jvm' cannot access 'Project.tasks' functionality on another project ':'`

This appears to be plugin internals, not project script code.

## Required migration work before re-enabling

1. Track Compose plugin support for isolated projects in the currently used version.
2. Upgrade Compose Gradle plugin once a compatible version is available.
3. Re-evaluate whether `dev.mokkery` and `com.osacky.doctor` can be kept enabled under isolation.
4. Re-run:
   - `./gradlew.bat help --console=plain`
   - `./gradlew.bat build --console=plain`
   with isolation enabled.
