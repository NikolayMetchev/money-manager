# Money Manager

A personal finance money manager built with **Kotlin Multiplatform** and **Compose Multiplatform**, targeting Desktop (JVM) and Android.

> ⚠️ **BETA**: This app is under active development. There is no database versioning/migration yet
> ([#426](https://github.com/NikolayMetchev/money-manager/issues/426)), so the database is recreated
> from scratch on schema changes. Don't store data you can't afford to lose.

## Goals

* Explore what can be achieved with Kotlin Multiplatform and Compose Multiplatform
* Support all types of accounts a person can have — banking, pensions, investments, crypto
* Automate data entry as much as possible — this is the feature I want to be the distinguishing factor
* Once there is a version that can do anything useful, contributions will be very welcome

## Already Working

* **Accounts & Transfers** — create, edit, view and delete transactions across multiple accounts and currencies
* **Multi-currency** — locale-aware formatting; amounts stored as integers with per-currency scale factors
* **Categories** — hierarchical (parent/child) categorisation
* **People & ownership** — track counterparties and who owns which accounts
* **Importing** — CSV, QIF, and live JSON-API imports through a unified import engine
  * Built-in strategies for **Monzo**, **Starling** and **Wise**
  * Reusable, user-definable CSV column-mapping and API strategies
* **Reconciliation** — cross-source deduplication detects the same transaction seen from different
  providers (e.g. a manual CSV plus an API feed) and links them via typed transfer relationships
* **Excluded transactions** — mark transactions to omit them from balance calculations while keeping them visible
* **Running & account balances** — maintained via incrementally-refreshed materialized views
* **Audit history** — full audit trail with timestamps and source attribution (device, file, API session)
* **Cloud-backed databases** — optionally store your database on **Google Drive**: a local working copy is
  hydrated from Drive on open and pushed back on close (and on demand via **Sync now**). Before upload the
  database is shrunk (materialized views truncated + VACUUM), **compressed and password-encrypted**, so
  Drive only ever holds an opaque blob. **Bring-your-own credentials** — you supply your own OAuth client
  (no app-owned secrets) via an in-app wizard; least-privilege `drive.file` scope. Works on Desktop and Android.
* **Desktop app** — JVM/Compose Desktop with native distributions (Windows, macOS, Linux)
* **Android app**
* **Database documentation** — auto-generated [SchemaSpy ER diagrams & docs](https://nikolaymetchev.github.io/money-manager/database/)
* **CI/CD** — automated build, test, coverage, static analysis, tagging and releases

## Planned Features

* Charting, slicing & dicing — data exploration
* Budgeting and forecasting
* AI classification of transactions
* Export to GnuCash
* Tags on all objects; categorisation hierarchy for accounts, people and assets
* Safe DB upgrades / migrations across versions
* Multi-user support
* iOS and Web (JS/WASM) versions

## Platform Support

| Platform | Status |
|----------|--------|
| JVM (Desktop) | ✅ Supported — Windows / macOS / Linux distributions |
| Android | ✅ Supported |
| iOS | ⚠️ Planned |
| Web (JS/WASM) | ⚠️ Planned |
| Native | ❌ Not planned |

## Technology Stack

* **Language**: Kotlin (Multiplatform) · **Build**: Gradle · **JVM toolchain**: 25
* **Database**: SQLite via [SQLDelight](https://github.com/sqldelight/sqldelight)
* **DI**: [Metro](https://github.com/ZacSweers/metro) (compile-time)
* **UI**: Compose Multiplatform with Material 3
* **Object mapping**: [Mappie](https://github.com/Mr-Mappie/mappie)
* **HTTP**: Ktor Client · **Serialization**: kotlinx.serialization
* **Code quality**: Detekt, ktlint, dependency-analysis (buildHealth)
* **Money**: `BigDecimal`-only policy — never `Double`/`Float` for monetary values

## Project Structure

| Module | Purpose |
|--------|---------|
| `gradle/build-logic/` | Convention plugins (Kotlin, Android, Compose, Metro, Mappie) |
| `utils/bigdecimal/` | Arbitrary-precision decimal arithmetic |
| `utils/currency/` | Locale-aware currency formatting |
| `utils/parsers/csv/`, `utils/parsers/qif/` | CSV and QIF file parsers |
| `utils/rest/` | REST/HTTP utilities |
| `utils/compose/*` | Reusable Compose components (file picker, scrollbar) |
| `utils/archive/` | Compress + password-encrypt the database archive (shared by remote backends) |
| `app/model/core/` | Domain models and `*ReadRepository`/`*WriteRepository` interfaces |
| `app/db/core/` | SQLDelight database, repository implementations, mappers |
| `app/importengineapi/` | DB-free import/write API — `ImportBatch`/`ImportResult`, `ImportEngine.*` helpers, dedup policies, source/key types |
| `app/csvimporter/`, `app/qifimporter/`, `app/apiimporter/` | Per-format importers that build an `ImportBatch` (DB-free) |
| `app/importer/` | Central import engine — applies an `ImportBatch`, deduplicates; the sole DB writer |
| `app/remotestorage/core/` | Generic remote-storage provider interface (DB-free, backend-agnostic) |
| `app/remotestorage/googledrive/` | Google Drive backend — Drive REST v3 over Ktor (JVM + Android) |
| `app/remotestorage/sync/` | Hydrate/push orchestration + remote-binding persistence |
| `app/di/core/` | Metro DI configuration |
| `app/ui/core/` | Compose UI (JVM/Android) |
| `app/main/jvm/` | JVM Desktop entry point |
| `app/main/android/` | Android entry point |
| `app/db/schemaspy/` | Generates the published SchemaSpy database docs |

## Building & Running

> Always build with `--console=plain`. Requires a JDK 25 toolchain.

| Command | Description |
|---------|-------------|
| `./gradlew build` | Build everything, run tests, coverage and dependency health |
| `./gradlew :app:main:jvm:run` | Run the JVM desktop application |
| `./gradlew :app:main:android:installDebug` | Install the Android debug APK |
| `./gradlew lintFormat` | Format code (ktlint + sort dependencies) |
| `./gradlew detekt` | Static analysis |
| `./gradlew buildHealth` | Check dependency health |

## Development Notes

* **Single write seam** — every database mutation goes through the `ImportEngine`: callers build an
  `ImportBatch` and call `importEngine.import(batch)`; they never use a `*WriteRepository` directly.
  Repositories are split into read/write pairs, only `app/importer` is injected with the write side, and
  the UI gets a read-only view plus the engine. A Gradle `verifyNoWriteRepositoryUsage` check enforces
  it. (The lone exception is device-id bootstrap.) See [`CLAUDE.md`](CLAUDE.md) for details.
* **Database design**, **CI/CD** and the **data model** are designed and reviewed closely.
* The **UI** and **unit tests** are partly AI-generated and will be reviewed/refactored over time.
* See [`CLAUDE.md`](CLAUDE.md) for detailed contributor/AI-assistant guidance.

## License

Licensed under the [Apache License 2.0](LICENSE).
