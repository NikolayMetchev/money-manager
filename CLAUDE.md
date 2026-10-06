# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

Money Manager is a Kotlin Multiplatform personal finance app targeting JVM and Android.

## Design Principle: Generalize, Don't Add

**Prefer generalizing or reusing an existing feature over adding a new one.** The goal is a small set
of very composable, generic features that are easy to understand and configure — not a pile of
source-specific knobs.

**Why:** import features used to be added one source at a time (Wise, Monzo, Crypto.com, Binance,
Kraken, Coinbase, Bybit, Koinly, Curve), so the same few ideas ended up existing 3–6 times under
different names, with CSV and API each keeping their own copy. The import-strategy consolidation stack
(#897, #898, #899, #894) had to collapse them back into a handful of shared vocabularies (see
**Import Strategies**). It also turned up dead config (`fixedDirection` had an editor dropdown but did
nothing) and features that only built-ins could configure. Don't recreate that debt.

**How to apply:**
- Before adding a config field, rule type, pipeline or screen, look for the existing concept that
  already covers it — `Condition`, `ValueExpr`, `Direction`, `AccountRule`, `LegGroupRule`, `FeeRule`,
  `ApiPaging`/`ApiDateWindowing`, `ApiAccountsSource`, the `ImportBatch` intents, the leg matcher —
  and extend or parameterize that.
- A new data source should be **configuration, not code**. If a source can't be expressed, find the
  missing *generic* capability and add it in a way every source can use, for CSV and API alike. Never
  put source-specific code outside a built-in strategy's config.
- If an existing feature is too narrow, replace it with the more general one and **retire the old one
  completely**: no parallel code paths and no legacy fields kept "just in case". Persisted strategy
  configs are upgraded through `StrategyConfigMigrations`, so retiring a field is safe.
- Keep vocabularies small and orthogonal so they compose: one way to ask "does this match?", one way
  to compute a value, one ordered rule list, one shared editor for each concept.
- Everything a built-in strategy can do must be configurable in the UI. If no editor can set it, it
  isn't a feature yet.
- When you're unsure whether two things should stay separate, say so and ask rather than adding a third
  variant. When unifying would change existing outcomes, leave them separate and document why, as #894
  did for the trade-side reconcilers.

## Technology Stack

- **Language**: Kotlin | **Build**: Gradle | **JVM**: 25
- **Database**: SQLite via SQLDelight | **DI**: Metro 
- **UI**: Compose Multiplatform with Material 3
- **Object Mapping**: Mappie | **Code Quality**: Detekt, ktlint

## Build Commands

**Important**: Always use `--console=plain`. Don't use `--no-daemon`. On Windows, use `./gradlew.bat`
directly. A full `./gradlew build` always takes longer than 2 minutes, so never run it with a short
timeout; use at least 20 minutes.

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
`-PreleaseSmokeTest=true` builds the release variant debug-signed and enables `app/main/android-smoketest`:
`./gradlew :app:main:android-smoketest:pixel6api36ReleaseAndroidTest -PreleaseSmokeTest=true --console=plain` installs the
minified APK and drives first run. CI runs it only via the manual "Android Release Smoke Test" workflow (dispatch it on main or a PR branch).

**Pre-push**: Always run `./gradlew build buildHealth` locally before pushing.

## Project Structure

### Modules

| Module | Purpose |
|--------|---------|
| `gradle/build-logic/` | Convention plugins (kotlin, android, compose, metro, mappie, `jvm-android-shared`, `pure-importer`) plus the `verifyNoDbDependency`/`verifyNoWriteRepositoryUsage` tasks, with TestKit tests that the root `check` runs |
| `utils/bigdecimal/` | Arbitrary-precision decimal arithmetic (JVM/Android) |
| `utils/currency/` | Locale-aware currency formatting |
| `utils/humanreadable/` | English file-size / duration / "time ago" formatting (replaces Human-Readable, whose localisation layer pulled ICU4J into the desktop build) |
| `utils/archive/` | Compress + password-encrypt the DB archive (`ArchiveCodec`); shared by remote backends |
| `utils/credentialvault/` | The encrypted, password-protected credential file (API tokens, Google refresh tokens) that outlives the DB — see **Credentials** |
| `utils/parsers/{csv,qif,xlsx}/` | File-format parsers |
| `utils/rest/` | Shared Ktor `ApiClient`, request signing and API traffic recording |
| `utils/compose/{filePicker,scrollbar}/` | Platform file/folder pickers and scrollbar widgets |
| `app/model/core/` | The flat `domain.model` package: entities, ids, `Money`, audit entries. Depends on nothing but `utils/bigdecimal` |
| `app/model/rules/` | The strategy vocabulary that CSV and API share: `Condition`, `ValueExpr`, `Direction`, `AssetCodeRules`, `FeeRule`, `RuleEvaluator` — see **Import Strategies** |
| `app/model/{apistrategy,accountmapping,csv,qif,csvstrategy,importdirectory,passthrough,reconciliation,timeline}/` | One module per `domain.model` sub-package. All depend on `model/core`; `qif`→`csv`, `csvstrategy`→`qif`+`accountmapping` |
| `app/model/repository/read/`, `app/model/repository/write/` | `*ReadRepository` / `*WriteRepository` interfaces. `write` depends on `read` (each write interface extends its read) |
| `app/db/schema/`, `app/db/read/`, `app/db/repository/`, `app/db/write/`, `app/db/core/` | SQLDelight schema; generated read SQL + Mappie mappers + JSON codecs (and the strategy config migrations); read repository impls; write SQL + impls; `DatabaseManager` and services |
| `app/db/seed/`, `app/db/schemaspy/` | Static seed SQL; SchemaSpy docs generator for the Pages site |
| `app/cryptodata/` | Bundled crypto asset catalog plus its refresher/installer (built by `tools/crypto-dataset`) |
| `app/importengineapi/` | `ImportEngine` interface + `ImportBatch`/`ImportResult` model + `ImportEngine.*` write helpers (DB-free) |
| `app/importer/` | `ImportEngineImpl` — the **sole** DB writer (consumes write repositories) |
| `app/csvimporter/`, `app/qifimporter/`, `app/apiimporter/` | Parse/download sources and build an `ImportBatch` (DB-free, enforced) |
| `app/reconciliation/` | Source-agnostic reconciliation: leg matcher + shadow→real auto-link planner (DB-free, enforced) |
| `app/strategies/` | Built-in strategy/pass-through definitions in Kotlin — rendered to the `webpage/strategy-library` catalog site by `tools/strategy-catalog` on Pages deploys (DB-free, nothing checked in or seeded) |
| `app/strategycatalog/` (+ `di/`) | Browse/install the published strategy catalog (`StrategyCatalogController`) |
| `app/importfilesource/{core,localfolder}/` | `ImportFileSource` abstraction for import directories; local-folder / Android SAF implementations |
| `app/remotestorage/core/` | Generic `RemoteStorageProvider` interface + factory (DB-free, backend-agnostic) |
| `app/remotestorage/googledrive/` | Google Drive backend — Drive REST v3 over Ktor (JVM + Android) |
| `app/remotestorage/sync/` | Hydrate/push orchestration (`RemoteDatabaseSyncService`/`RemoteDatabaseController`) |
| `app/di/scope/`, `app/di/params/` | The `AppScope`/`DatabaseScope` markers, and `AppComponentParams`. Leaf modules, so contributing a DI module costs nothing else |
| `app/di/core/` | The `AppComponent` graph only. Metro merges contributors off its compile classpath (see **Dependency Injection**) |
| `app/db/di/`, `app/remotestorage/di/`, `app/strategycatalog/di/`, `utils/localsettings/di/`, `utils/credentialvault/di/` | Each feature's Metro modules, next to the code they provide |
| `app/importfilesource/di/` | Platform factories for import file sources (not Metro — the entry points call these directly) |
| `app/ui/core/` | Compose UI shell: app, navigation, setup wizard (JVM/Android only) |
| `app/ui/{foundation,components}/` | Platform UI helpers; shared widgets and editors (pickers, `ConditionsEditor`, `DirectionEditor`, …) |
| `app/ui/{accounts,audit,categories,currencies,people,settings,transactions}/`, `app/ui/imports/{api,csv,qif,importDirectory,reconciliation,timeline}/` | One module per feature screen |
| `tools/{strategy-catalog,crypto-dataset}/` | Build-time tools: catalog site renderer (also hosts `LegacyStrategyUpgradeTest`); crypto dataset builder |
| `test/{app/db,app/ui,utils/credentialvault}/` | Test fixtures only (see **Testing**) |
| `app/main/jvm/` | JVM Desktop entry point |
| `app/main/android/` | Android entry point |
| `app/main/android-smoketest/` | Self-instrumenting UiAutomator smoke test of the R8-minified release APK (only included in the build with `-PreleaseSmokeTest=true`) |

### Domain Entities

- **Account**: Financial accounts (checking, savings, credit card, cash, investment)
- **Category**: Transaction categories with parent/child hierarchy
- **Transaction**: Income, expense, and transfer records

## Money and Currency Handling

**BigDecimal-Only Policy**:
- **NEVER** use `Double` or `Float` for monetary calculations or parsing
- **ALWAYS** use `BigDecimal` for decimal parsing and arithmetic
- Use `BigDecimal(String)` constructor for perfect precision

**Storage**: Amounts stored as `INTEGER` in database (value × scale_factor). **Every** asset — crypto
*and* currency — is created at scale factor 1e18, not at the currency's ISO 4217 decimal-place count;
`CurrencyScaleFactors` (in `app/model/core`) documents why. Assuming 100 is how you get spurious
"Rounding necessary" failures, so `Currency.scaleFactor` has no default — always pass it explicitly.

**Key Classes**:
- `Money`: Value class storing amount as `Long` with associated `Currency`
- `Money.fromDisplayValue(BigDecimal, Currency)`: Create from user input
- `Money.toDisplayValue()`: Convert to BigDecimal for display

## Database

**SQLDelight** schema files: `app/db/schema/src/commonMain/sqldelight/com/moneymanager/database/sql/`

**BETA — no DB versioning yet**: The app is in BETA and database versioning/migrations are not
implemented (tracked in [issue #426](https://github.com/NikolayMetchev/money-manager/issues/426)).
Until that lands, **do not make backward-compatible DB changes or write migration code**. The
database is recreated from scratch on schema changes (seeding runs only on fresh-database creation),
so existing local databases are expected to be deleted/recreated rather than migrated.
(Strategy *config JSON* is the exception: it is versioned and migrated — see **Import Strategies**.)

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
client (Desktop type) via the in-app wizard; least-privilege `drive.file` scope. The refresh token (and granted
scopes) live in the credential vault keyed by OAuth client id (see **Credentials**); access tokens are
cached in memory only. Connection scope is **per database** — each binding stores its own OAuth client, so
different databases can use different Google accounts.

## Credentials (the vault file)

Secrets never live in the database, so wiping or recreating a database doesn't cost the user their tokens.
`utils/credentialvault` keeps them in one **encrypted, password-protected file** — `ArchiveCodec` again,
no new crypto — defaulting to `money-manager.credentials` next to the database (a user-chosen location
is remembered per database in `LocalSettings`). It holds API tokens / api secrets / SCA signing keys and
Google refresh tokens.

- **The password is never stored** — not in the DB, `LocalSettings` or logs; only in memory while unlocked.
- **Lazy unlock**: code that needs a secret calls `CredentialVault.requireUnlocked(reason)`, which raises an
  `UnlockRequest`; `CredentialVaultPromptHost` (mounted once by `AppStartupHost`) asks for the password, or
  where to create the file and with what password. Cancelling throws `CredentialVaultLockedException`.
  In Compose, reach the vault through `LocalCredentialVault.current`.
- **API credentials are keyed by strategy name**, not id: catalog installs give a strategy a fresh random id
  in every database. `api_credential` is just a secret-free connection row (id, strategy) that sessions
  hang off; `ImportEngine.ensureApiCredentials` creates it idempotently, and the API screens create rows for
  any installed strategy with vault secrets, which is how a recreated database reconnects.
- The vault is `AppScope` and re-bound (`bindToDatabase`) on every database switch, which locks it if the
  file changes. Tests use `unlockedCredentialVault()` from `test/utils/credentialvault`.
- **Opt-in cloud backup**: `CredentialSyncController` (`app/remotestorage/sync`) uploads the vault file's
  already-encrypted bytes, as is, to Drive `Money Manager/Credentials/<vault file name>`. It runs on its
  own connection, like the strategy library. Sync is two-way against a per-vault-path baseline: the side
  that changed wins. When both sides changed, or a new device finds an existing backup, the two are merged
  with `CredentialBundle.mergedWith`: the newer API credential wins and the local Google account wins. A
  backup under another password needs that password once, and the local vault then adopts it
  (`CredentialVault.applyRemote`). `CredentialBackupSyncHost` syncs on unlock and after every change, and it
  never prompts.

## Import Strategies (CSV and API)

CSV and API strategies are built from the same small vocabularies. Extend these rather than adding
parallel ones (see **Design Principle**):

| Concept | Type | Replaced |
|---|---|---|
| "Does this row/item match?" | `Condition` (`app/model/rules`), evaluated by one `RuleEvaluator` over CSV rows and JSON items | row conditions, predicates, content-match rules, exclude/decline fields, item filters |
| "Compute a value" | `ValueExpr`: ordered fallback columns/paths, extraction, templates | per-field fallback/extraction/template settings |
| Asset tickers | `AssetCodeRules`: aliases + suffix handling | separate CSV and API alias/suffix settings |
| Direction | `Direction` (amount sign / field / always outgoing) | `flipAccountsOnPositive`, `negateValues`, `signSource`/`signField`/`creditValues`, … |
| Account resolution | `AccountRulesMapping`, an ordered, **first-match-wins** `AccountRule` list | lookup/template/regex/attribute/conditional account mappings |
| Multi-row grouping | `LegGroupRule` with `Trade` or `ThroughAccount` assembly | `ConversionConfig`, `TradeGroupConfig` |
| Fees | `FeeRule` (CSV, API transfers and API trades) | three separate sets of fee fields |
| API fetching | One pipeline: `ApiAccountsSource` (`Downloaded`/`Single`) × `ApiDateWindowing` × `ApiPaging` (single/offset/before-cursor/forward-id/token), walked by one `PageWalker` | separate bank vs exchange paths and three hand-written pagination loops |
| API ledger trades | `ApiLedgerTrades` | `reconcileTradeAmounts*`, `unpairedTradeLeg*` |
| Transfer reconciliation | `ImportDeduper.reconcile()` over an ordered `ReconcileRule` table. Each rule only says how to find the existing record; one ranking decides which record stays counted (precise > approximate, named > placeholder, itemised > gross, ties keep the existing one) | nine cross-source classifiers |

Shared editors (`ConditionsEditor`, `DirectionEditor`, the account-rules, fee and leg-group editors) serve
both the CSV and API strategy screens.

**Still separate, on purpose:** `TradeReconciler`, `ConversionTradeReconciler` and `ConversionGroupReconciler`
use genuinely different assignment rules, and `app/reconciliation`'s `LegMatcher` is a read-only report.
Merging any of them would change built-in outcomes.

**Strategy config versioning (this is not DB versioning):** CSV and API configs carry a `configVersion`.
`StrategyConfigMigrations` (`app/db/read/.../json/`) upgrades older JSON on decode, one raw-JSON step per
version, so a retired field can still be read after its Kotlin property is gone. Each step must accept
**both** persisted shapes: the DB form (ids) and the export/catalog form (names). Configs in old databases,
on Drive and in the published catalog keep working. To reshape a config, add a step and bump the version.
`LegacyStrategyUpgradeTest` (`tools/strategy-catalog`) decodes the `legacy-v0` snapshots of every built-in
CSV and API strategy (DB and export form) and checks each upgrades to the current built-in. Pass-through
rules (`.passthrough.json`) aren't versioned, so the test skips them. When you rewrite a built-in, make it
equal the migration output.

**Changing the engine:** the contract is that every built-in strategy imports exactly as before. Don't edit
an expected test result to make a refactor pass; report the divergence instead. Built-in configs don't
re-sync into databases that already installed them. Export lists must serialize in a canonical order, or
Drive strategy sync sees false conflicts, but never sort first-match-wins rule lists.

## Reconciliation Sources (Koinly, …)

A CSV strategy with a `ReconciliationConfig(sourceName, linkableAccountPrefix)` is a **reconciliation
source** (built-in: Koinly). Its data never touches real accounts:

- Every account it resolves/creates is a **shadow account** tagged with the `reconciliation-source`
  account attribute (-9, value = source name). The mapper only sees that source's shadow accounts
  (`accountsVisibleTo`), and `ImportBatch.shadowSource` makes the engine match names only among them,
  tag what it creates, and reject a batch referencing any other account. Because real imports only load
  transfers on their own accounts for dedupe, shadow data is isolated in both directions — no exclusion
  attribute needed, trades included.
- Shadow **wallets** (names starting with `linkableAccountPrefix`) are linked to real accounts in
  `reconciliation_account_link` (many real → one wallet; one wallet per source per real account),
  written via `ReconciliationLinkMutation`. The Reconciliation tab auto-links exact name matches and
  lists the rest as "needs attention" (link to an existing account or create one).
- `reconcile()` (`app/reconciliation`) matches legs per link group on (asset, signed amount): exact
  second, then nearest within 24h, then summed per (group, asset, second) so a fee booked separately on
  one side still matches the other side's gross leg.
- Rows the source marks deleted are imported **excluded** (attribute mapping → `excluded`), like
  declined card payments. Trades can be excluded too (`trade_attribute`, honoured by
  `BalanceLegsSelect`, toggled by hand in `TradeExclusionDialog`), and a transfer's fee/pass-through
  legs inherit its exclusion. Imports declare the attribute types their source **owns**
  (`ownedAttributeTypeIds`: a CSV strategy's mapped columns); a re-import removes an owned type the row no
  longer reports — so a row un-deleted at the source comes back — and never touches other attributes.
- `CsvStrategyConfig.assetAliases` maps a source's tickers onto Money Manager's (Koinly `KNCL` → `KNC`).
- Shadow accounts are hidden by default in the Accounts screen and in `AccountPicker` (tickbox to show).
- Keep it source-agnostic: no Koinly-specific code outside the built-in strategy's config.

**The Reconciliation tab** (Imports → Reconciliation) holds everything an import leaves for a person to
resolve, as sub-tabs: External Account Reconciliation (the sources above), Manual Entries (companion
transaction rules), and Card Last-4. Card Last-4 lists funding references that no account owns: values in
any CSV strategy's `fundingAttributeMatch` column that its attribute type doesn't resolve. Assigning one
adds the value to the account's token set (`addAttributeToken`, so an account can own several cards) and
re-runs only the re-import plan's funding reconciles (`rerunFundingReconciles`).

## Dependency Injection

**Metro** provides compile-time DI. The graphs live in `app/di/core` (`AppComponent`,
`@DependencyGraph(AppScope::class)`) and `app/db/di` (`DatabaseComponent`,
`@DependencyGraph(DatabaseScope::class)`). Graphs must be `interface`, not `abstract class` or `object`.

**Binding containers, not module interfaces.** Every contributed DI module is a
`@BindingContainer object` holding `@Provides` functions. Metro warns on a `@ContributesTo` *interface*
with instance `@Provides` functions ("Consider making this a binding container with `@BindingContainer`
instead"), and the build compiles with `-Werror`, so that warning is a hard failure.

**DI modules live with the code they provide, not in a central hub.** `RemoteStorageModule` is in
`app/remotestorage/di`, `LocalSettingsModule` in `utils/localsettings/di`, the repository bindings in
`app/db/di`, and so on. Metro merges every `@ContributesTo(AppScope::class)` container it finds **on the
graph module's compile classpath** — `AppComponent` names none of them.

The consequence worth knowing: `app/di/core` depends on each feature DI module purely so Metro can
*see* it. Nothing there imports them. Drop one and the graph loses its bindings. Because a binding
container is *merged* into the generated `AppComponent` rather than made a supertype of it, those deps
are plain `implementation` — let `buildHealth` decide, it flags any that should be `api`.

To add a binding: put a `@ContributesTo(AppScope::class) @BindingContainer object` in *your* module
(apply `moneymanager.metro-convention`, depend on `app/di/scope`), then add that module to
`app/di/core`.

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
- **Android Device Tests**: Use `:app:ui:core:pixel6api36AndroidDeviceTest` for UI tests on managed
  device emulator. **Android emulator API level sync**: change the level here and you must change it
  in `gradle/libs.versions.toml` (`android-targetSdk`), the Gradle managed device
  (`moneymanager.android-convention.gradle.kts` and `app/main/android-smoketest/build.gradle.kts`),
  the CI emulator (`.github/workflows/build.yml` and `.github/workflows/android-release-smoke-test.yml`) and the IntelliJ run configuration
  (`.idea/runConfigurations/Android_Tests.xml`) too.
- **Test Stability**: Always call `waitForIdle()` after `waitUntilDoesNotExist()` to ensure recompositions complete before test ends

### Platform Support

- **JVM** ✅ | **Android** ✅ | **iOS** ⚠️ planned | **Web** ⚠️ planned | **Native** ❌

### Common Issues

1. **Java**: Requires JDK 25 toolchain. **JVM version sync**: the version is pinned in four places
   that must move together — `gradle/libs.versions.toml` (`jvm-target`/`jvm-toolchain`),
   `.github/actions/gradle-setup/action.yml`, `.github/workflows/build.yml` and
   `.github/workflows/lint-format.yml`
2. **Metro**: Keep the Kotlin version compatible with Metro (both in `gradle/libs.versions.toml`). Graphs
   must be `interface`; binding containers `object`
3. **SQLDelight**: `execute()`/`update()`/`delete()` return `Long`, not `Unit`
4. **Configuration Cache**: Enabled for faster builds; invalidates on build file changes
5. **Gradle wrapper**: `gradle-wrapper.properties` pins `distributionSha256Sum`. Update it with the
   distribution URL when bumping Gradle. Build-script deprecations fail the build (warnings are errors)
6. **`jvmAndroidMain`**: apply `moneymanager.jvm-android-shared-convention` for shared JVM+Android code. It
   adds `jvmAndroidMain`/`jvmAndroidTest` to Kotlin's default hierarchy template. Never hand-wire
   `dependsOn` between source sets, because that makes KGP fall back from the template
