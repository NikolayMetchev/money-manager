# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

Money Manager is a Kotlin Multiplatform personal finance app targeting JVM and Android.

## Technology Stack

- **Language**: Kotlin | **Build**: Gradle | **JVM**: 25
- **Database**: SQLite via SQLDelight | **DI**: Metro 
- **UI**: Compose Multiplatform with Material 3
- **Object Mapping**: Mappie | **Code Quality**: Detekt, ktlint

## Build Commands

**Important**: Always use `--console=plain`. Don't use `--no-daemon`. On Windows, use `./gradlew.bat` directly.

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
| `utils/archive/` | Compress + password-encrypt the DB archive (`ArchiveCodec`); shared by remote backends |
| `app/model/core/` | The flat `domain.model` package: entities, ids, `Money`, audit entries. Depends on nothing but `utils/bigdecimal` |
| `app/model/{apistrategy,accountmapping,csv,qif,csvstrategy,importdirectory,passthrough,timeline}/` | One module per `domain.model` sub-package. All depend on `model/core`; `qif`→`csv`, `csvstrategy`→`qif`+`accountmapping` |
| `app/model/repository/read/`, `app/model/repository/write/` | `*ReadRepository` / `*WriteRepository` interfaces. `write` depends on `read` (each write interface extends its read) |
| `app/db/schema/`, `app/db/read/`, `app/db/repository/`, `app/db/write/`, `app/db/core/` | SQLDelight schema; generated read SQL + Mappie mappers + JSON codecs; read repository impls; write SQL + impls; `DatabaseManager` and services |
| `app/importengineapi/` | `ImportEngine` interface + `ImportBatch`/`ImportResult` model + `ImportEngine.*` write helpers (DB-free) |
| `app/importer/` | `ImportEngineImpl` — the **sole** DB writer (consumes write repositories) |
| `app/csvimporter/`, `app/qifimporter/`, `app/apiimporter/` | Parse/download sources and build an `ImportBatch` (DB-free, enforced) |
| `app/strategies/` | Built-in strategy/pass-through definitions in Kotlin — rendered to the `webpage/strategy-library` catalog site by `tools/strategy-catalog` on Pages deploys (DB-free, nothing checked in or seeded) |
| `app/remotestorage/core/` | Generic `RemoteStorageProvider` interface + factory (DB-free, backend-agnostic) |
| `app/remotestorage/googledrive/` | Google Drive backend — Drive REST v3 over Ktor (JVM + Android) |
| `app/remotestorage/sync/` | Hydrate/push orchestration (`RemoteDatabaseSyncService`/`RemoteDatabaseController`) |
| `app/di/scope/`, `app/di/params/` | The `AppScope`/`DatabaseScope` markers, and `AppComponentParams`. Leaf modules, so contributing a DI module costs nothing else |
| `app/di/core/` | The `AppComponent` graph only. Metro merges contributors off its compile classpath (see **Dependency Injection**) |
| `app/db/di/`, `app/remotestorage/di/`, `app/strategycatalog/di/`, `utils/localsettings/di/` | Each feature's Metro modules, next to the code they provide |
| `app/importfilesource/di/` | Platform factories for import file sources (not Metro — the entry points call these directly) |
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

**BETA — no DB versioning yet**: The app is in BETA and database versioning/migrations are not
implemented (tracked in [issue #426](https://github.com/NikolayMetchev/money-manager/issues/426)).
Until that lands, **do not make backward-compatible DB changes or write migration code**. The
database is recreated from scratch on schema changes (seeding runs only on fresh-database creation),
so existing local databases are expected to be deleted/recreated rather than migrated.

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
the engine is constructed in `app/db/di` via `DatabaseComponent.createImportEngine(editGate)`. In Compose,
reach it through `LocalImportEngine.current`.

The interfaces live in two modules — `:app:model:repository:read` and `:app:model:repository:write` — so a
module that only reads never even has the write interfaces on its compile classpath.

**Enforced in Gradle:** `moneymanager.kotlin-multiplatform-convention` registers a
`verifyNoWriteRepositoryUsage` check (wired into `check`) that fails if a module's main sources
reference any `*WriteRepository`. Exempt: `:app:model:repository:write` (interfaces), `:app:db:core` +
`:app:db:write` (impls), `:app:di:core` + `:app:db:di` (wiring), `:app:importer` (the engine),
`:test:app:db` (fixtures).

**The one exception:** `DeviceWriteRepository` is injected directly in `DeviceIdModule` (di/core).
`DeviceId` is a synchronous singleton that every write-repo impl — and therefore the engine — depends
on, so routing it through the suspend engine would be a DI cycle.

**How to apply:** to add a write (new field, entity, or config), extend `ImportBatch`/`ImportResult`
and `ImportEngineImpl`, add a helper if useful, and call it — never inject a `*WriteRepository` outside
the engine.

## Remote Storage (Cloud-backed databases)

A database can optionally be backed by remote storage (currently **Google Drive**). SQLite can't run
against a remote file, so a "cloud-backed database" is a **local working copy hydrated from the cloud on
open and pushed back on close** (plus an explicit "Sync now"). Before upload the DB is **shrunk**
(materialized views truncated + VACUUM via the snapshot path), **compressed and encrypted**; opening
re-hydrates it (decrypt → inflate → write local `.db` → rebuild materialized views).

**Layering — keep these separate (do not collapse them):**
- `utils/archive` — `ArchiveCodec.pack/unpack`: Deflate → PBKDF2-SHA256 → AES-GCM. Pure `commonMain`,
  backend-agnostic. Wrong password / tampering surfaces as `ArchiveDecryptionException`.
- `app/remotestorage/core` — generic `RemoteStorageProvider` (a *dumb* file store:
  `upload`/`download`/`list`/`delete`) + `RemoteStorageProviderFactory`. **DB-free and crypto-free** on
  purpose, so backends stay reusable and testable. Don't add DB/encryption methods here.
- `app/remotestorage/googledrive` — Drive REST v3 over the shared **Ktor** client, in one
  `jvmAndroidMain` source set (runs on JVM **and** Android; the Google Java SDK is JVM-only and not used).
  OAuth is the installed-app loopback flow; the only platform-specific piece is `BrowserLauncher`.
- `app/remotestorage/sync` — composes `DatabaseManager` + `ArchiveCodec` + a provider:
  `RemoteDatabaseSyncService` (the pipeline) and `RemoteDatabaseController` (session-scoped facade that
  reconstructs the provider via the factory and holds the in-memory password). `RemoteDatabaseController`
  is the single DI entry point exposed by `AppComponent`.

**Bring-your-own credentials**: the app ships **no** Google secrets. Each user supplies their own OAuth
client (Desktop type) via the in-app wizard; least-privilege `drive.file` scope. The refresh token is
persisted in `LocalSettings` keyed by a short hash of the OAuth client id (raw ids exceed the JVM prefs
80-char key limit). Connection scope is **per database** — each binding stores its own OAuth client, so
different databases can use different Google accounts.

## Dependency Injection

**Metro** provides compile-time DI. The graphs live in `app/di/core` (`AppComponent`,
`@DependencyGraph(AppScope::class)`) and `app/db/di` (`DatabaseComponent`,
`@DependencyGraph(DatabaseScope::class)`). Components must be `interface`, not `abstract class` or `object`.

**DI modules live with the code they provide, not in a central hub.** `RemoteStorageModule` is in
`app/remotestorage/di`, `LocalSettingsModule` in `utils/localsettings/di`, the repository bindings in
`app/db/di`, and so on. Metro merges every `@ContributesTo(AppScope::class)` module it finds **on the
graph module's compile classpath** — `AppComponent` names none of them.

Two consequences worth knowing:
- `app/di/core` depends on each feature DI module purely so Metro can *see* it. Nothing there imports
  them. Drop one and the graph loses its bindings.
- Those deps must be `api`, not `implementation`: Metro makes each contributed module a **supertype** of
  the generated `AppComponent`, so anything touching `AppComponent` needs them on its compile classpath.

To add a binding: put a `@ContributesTo(AppScope::class)` interface in *your* module (apply
`moneymanager.metro-convention`, depend on `app/di/scope`), then add that module to `app/di/core`.

## Packages

**One package, one module** — no package may be declared by the main sources of two modules. A root
`verifyUniquePackages` task (wired into `check`) enforces this. Split packages let `internal` leak across
a module boundary the compiler can no longer police, and hide which module owns a type. When splitting a
module, give the new one its own package (typically the old package plus a segment). Test source sets are
exempt: they compile separately, so sharing a package there costs nothing.

## UI

**Compose Multiplatform** with Material 3. JVM and Android only.

**Schema Error Handling**: Always use `collectAsStateWithSchemaErrorHandling()` instead of `collectAsState()` for repository Flows to catch and display database schema errors gracefully.

## Setup Wizard

A full-screen stepper (`app/ui/core/.../screens/setup/`) that guides a user through setting up a database:
default currency → strategy catalog → strategy cloud sync → import folders → API credentials. Each step body
is the same composable the feature uses elsewhere (`StrategyCatalogScreen`, `StrategyCloudCard`,
`ImportDirectoriesScreen`, `ApiConnectScreen`), so the wizard adds ordering and explanation, never a parallel
set of writes.

- **Trigger**: `settings.setup_wizard_completed` is a **per-database** flag, so a freshly created database
  runs the wizard, and re-running it from Settings ("Run setup wizard") is always available. "Skip setup"
  exits from any step and records completion.
- **The database-location step** happens before a database exists, so it lives in `FirstRunDatabaseSetupScreen`
  (rendered by `AppStartupHost`) and only appears in the wizard's indicator as an already-completed step.
- **Step list is dynamic** (`setupWizardSteps`): a step whose feature isn't wired in is dropped, and the API
  step only appears once the database has at least one API strategy — installing one mid-wizard adds it.
- Tests that render `MoneyManagerApp` must mark the flag (`MoneyManagerTestApp` does this by default), or the
  wizard takes over the screen.

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
- **Android Device Tests**: Use `:app:ui:core:pixel6api36AndroidDeviceTest` for UI tests on managed device emulator
- **Test Stability**: Always call `waitForIdle()` after `waitUntilDoesNotExist()` to ensure recompositions complete before test ends

### Platform Support

- **JVM** ✅ | **Android** ✅ | **iOS** ⚠️ planned | **Web** ⚠️ planned | **Native** ❌

### Common Issues

1. **Java**: Requires JDK 25 toolchain
2. **Metro**: Keep Kotlin version aligned (2.2.21). Components/modules must be `interface`
3. **SQLDelight**: `execute()`/`update()`/`delete()` return `Long`, not `Unit`
4. **Configuration Cache**: Enabled for faster builds; invalidates on build file changes
