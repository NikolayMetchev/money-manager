# AGENTS.md

This file provides guidance to Codex when working with this repository.

## Project Overview

Money Manager is a Kotlin Multiplatform personal finance app targeting JVM and Android.

## Technology Stack

- **Language**: Kotlin | **Build**: Gradle | **JVM**: 25. JVM version sync: keep this aligned with `gradle/libs.versions.toml`, `.github/actions/gradle-setup/action.yml`, and `.github/workflows/build.yml`.
- **Database**: SQLite via SQLDelight | **DI**: Metro 
- **UI**: Compose Multiplatform with Material 3
- **Object Mapping**: Mappie | **Code Quality**: Detekt, ktlint

## Build Commands

**Important**: Always use `--console=plain`. Don't use `--no-daemon`. On Windows, use `./gradlew.bat` directly. A full `./gradlew build` always takes longer than 2 minutes, so never run it with a short timeout; use at least 20 minutes.

| Command | Description |
|---------|-------------|
| `./gradlew build` | Build all and run tests (coverage/dependency health run as separate CI jobs) |
| `./gradlew :app:main:jvm:run` | Run JVM application |
| `./gradlew :app:main:android:installDebug` | Install Android debug APK |
| `./gradlew :app:ui:core:pixel6api36AndroidDeviceTest` | Run Android UI tests on managed device |
| `./gradlew lintFormat` | Format code (ktlint + sort dependencies) |
| `./gradlew buildHealth` | Check dependency health |
| `./gradlew detekt` | Static analysis |

Build-speed flags (off by default): the Android release variant (R8/minified) only exists with
`-PbuildRelease=true` (CI main/release workflows pass it), and `build` only compiles Android
device-test sources with `-PcompileDeviceTests=true` (the CI emulator job compiles them anyway).
`-PtestMaxParallelForks=N` overrides the parallel test-fork default (e.g. `=1` to serialize), and
`-PandroidCoverage=true` enables JaCoCo coverage for instrumented tests (main-branch CI passes it).

**Pre-push**: Always run `./gradlew build buildHealth` locally before pushing.

## Project Structure

### Modules

| Module | Purpose |
|--------|---------|
| `gradle/build-logic/` | Convention plugins (kotlin, android, compose, metro, mappie) |
| `utils/bigdecimal/` | Arbitrary-precision decimal arithmetic (JVM/Android) |
| `utils/currency/` | Locale-aware currency formatting |
| `app/model/core/` | Domain models and `*ReadRepository`/`*WriteRepository` interfaces |
| `app/db/core/` | SQLDelight database, repository implementations, mappers |
| `app/importengineapi/` | `ImportEngine` interface + `ImportBatch`/`ImportResult` model + `ImportEngine.*` write helpers (DB-free) |
| `app/importer/` | `ImportEngineImpl` — the **sole** DB writer (consumes write repositories) |
| `app/csvimporter/`, `app/qifimporter/`, `app/apiimporter/` | Parse/download sources and build an `ImportBatch` (DB-free, enforced) |
| `app/strategies/` | Built-in strategy/pass-through definitions in Kotlin — rendered to the `webpage/strategy-library` catalog site by `tools/strategy-catalog` on Pages deploys (DB-free, nothing checked in or seeded) |
| `app/di/core/` | Metro DI configuration |
| `app/ui/core/` | Compose UI (JVM/Android only) |
| `app/main/jvm/` | JVM Desktop entry point |
| `app/main/android/` | Android entry point |

### Domain Entities

- **Account**: Financial accounts (checking, savings, credit card, cash, investment)
- **Category**: Transaction categories with parent/child hierarchy
- **Transaction**: Income, expense, and transfer records

## Money and Currency Handling

**BigDecimal-Only Policy**:
- **NEVER** use `Double` or `Float` for monetary calculations or parsing
- **ALWAYS** use `BigDecimal` for decimal parsing and arithmetic
- Use `BigDecimal(String)` constructor for perfect precision

**Storage**: Amounts stored as `INTEGER` in database (value × scale_factor). Most currencies use scale factor 100 (2 decimals).

**Key Classes**:
- `Money`: Value class storing amount as `Long` with associated `Currency`
- `Money.fromDisplayValue(BigDecimal, Currency)`: Create from user input
- `Money.toDisplayValue()`: Convert to BigDecimal for display

## Database

**SQLDelight** schema files: `app/db/core/src/commonMain/sqldelight/com/moneymanager/database/`

**Important**: Store booleans as INTEGER (0/1). Don't use `AS Boolean` in .sq files.

**Mappie** generates type-safe mappers from database entities to domain models. Annotate mapper interfaces with `@Mapper`.

## Database Writes (the ImportEngine is the sole writer)

**Every database mutation goes through the `ImportEngine`.** The UI, importers, services, and DI
bootstrap never call a `*WriteRepository` directly. A caller builds an `ImportBatch` declaratively
(transfers + account/person/category/currency intents, CSV/API strategy + mapping + CSV/QIF staging +
API-session mutations, attribute-type names to resolve, settings) and calls `importEngine.import(batch)`,
reading generated ids back from `ImportResult`. Convenience `ImportEngine.*` extensions
(`ImportEngineActions.kt`, `ImportEngineConfigActions.kt` in `app/importengineapi`) wrap the common
one-item cases — `createCurrency`, `deleteCsvStrategy`, `createCsvImport`, `getOrCreateAttributeType`,
`setDefaultCurrency`, `insertApiRequest`, …

**Why:** one write seam means one `EditGate`, so writes can be blocked centrally when a cloud-backed
database is locked, and provenance/audit recording lives in a single place.

**Read vs write interfaces:** repositories come in `*ReadRepository` + `*WriteRepository` pairs (write
extends read). **Only `ImportEngineImpl` (`app/importer`) is injected with write repositories.** The UI
gets a fully read-only `AppServices` (every field a `*ReadRepository`) plus the prebuilt `ImportEngine`;
the engine is constructed in di/core via `DatabaseComponent.createImportEngine(editGate)`. In Compose,
reach it through `LocalImportEngine.current`.

**Enforced in Gradle:** `moneymanager.kotlin-multiplatform-convention` registers a
`verifyNoWriteRepositoryUsage` check (wired into `check`) that fails if a module's main sources
reference any `*WriteRepository`. Exempt: `:app:model:core` (interfaces), `:app:db:core` (impls),
`:app:di:core` (wiring), `:app:importer` (the engine), `:test:app:db` (fixtures).

**The one exception:** `DeviceWriteRepository` is injected directly in `DeviceIdModule` (di/core).
`DeviceId` is a synchronous singleton that every write-repo impl — and therefore the engine — depends
on, so routing it through the suspend engine would be a DI cycle.

**How to apply:** to add a write (new field, entity, or config), extend `ImportBatch`/`ImportResult`
and `ImportEngineImpl`, add a helper if useful, and call it — never inject a `*WriteRepository` outside
the engine.

## Dependency Injection

**Metro** provides compile-time DI. Configuration in `app/di/core`:
- `AppComponent`: Main DI graph interface with `@DependencyGraph(AppScope::class)`
- Modules use `@ContributesTo(AppScope::class)` and `@Provides` functions
- Components must be `interface`, not `abstract class` or `object`

## UI

**Compose Multiplatform** with Material 3. JVM and Android only.

**Schema Error Handling**: Always use `collectAsStateWithSchemaErrorHandling()` instead of `collectAsState()` for repository Flows to catch and display database schema errors gracefully.

## Development Guidelines

### Dependencies

- **ALWAYS** add dependencies to `gradle/libs.versions.toml` first
- Use `libs.*` references, never hardcode versions
- Use `projects.*` for project dependencies (typesafe accessors)

### Code Style

- Run `./gradlew lintFormat` before committing
- Import types explicitly, never use fully qualified names in code
- Comments should explain "why", not "what" - prefer self-documenting code
- Never skip `buildHealth` checks
- **Never use `@Suppress("DEPRECATION")`** to silence deprecated API warnings. Always migrate to the replacement API instead (e.g. use `LocalClipboard` instead of `LocalClipboardManager`).

### Testing

- **`test/` is for test support only**: modules under `test/` hold test fixtures and helpers consumed
  exclusively by test source sets. Production code (anything compiled into the shipped app, or main
  sources that production modules depend on) must never live under `test/` — give it its own module
  under `app/`, `utils/`, or `tools/` instead (e.g. the built-in strategy definitions live in
  `app/strategies`, not `test/app/strategies`).
- Tests in `commonTest` run on both JVM and Android
- Use `runComposeUiTest` for UI tests
- Android tests require manifest with `ComponentActivity` declaration
- Share test sources via `kotlin.srcDir("src/commonTest/kotlin")`
- **Android Device Tests**: Use `:app:ui:core:pixel6api36AndroidDeviceTest` for UI tests on managed device emulator. Android emulator API level sync: update this with the Gradle managed device, CI emulator, and IntelliJ Android Tests run configuration.
- **Test Stability**: Always call `waitForIdle()` after `waitUntilDoesNotExist()` to ensure recompositions complete before test ends

### Platform Support

- **JVM** ✅ | **Android** ✅ | **iOS** ⚠️ planned | **Web** ⚠️ planned | **Native** ❌

### Common Issues

1. **Java**: Requires JDK 25 toolchain. JVM version sync: update this with `gradle/libs.versions.toml`, `.github/actions/gradle-setup/action.yml`, and `.github/workflows/build.yml`.
2. **Metro**: Keep Kotlin version aligned (2.2.21). Components/modules must be `interface`
3. **SQLDelight**: `execute()`/`update()`/`delete()` return `Long`, not `Unit`
4. **Configuration Cache**: Enabled for faster builds; invalidates on build file changes
