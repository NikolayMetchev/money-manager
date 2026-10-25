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
* **Command line** — `mm`, a native CLI for Linux, macOS and Windows: query accounts, balances and transactions, and
  run every import from a script (see [Command line](#command-line-mm))
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
| Web (JS/WASM) | ⚠️ In progress — [open it in your browser](https://nikolaymetchev.github.io/money-manager/app/), or serve it locally (`bazel run //app/main/web:serve`); databases live in the browser (OPFS), optionally on Google Drive. No import folders; API downloads need the desktop app or `mm`, as most providers refuse requests from a web page |
| Native | ⚠️ CLI only — `mm` for Linux x64/ARM64, macOS ARM64, Windows x64 |

## Command line (mm)

`mm` opens the same database files as the desktop app and shares its settings, so the two can be used side by side.
It prints a table in a terminal and JSON when piped. Prompts, progress and errors go to stderr, so stdout holds only
the result.

**Install.** Download the binary for your platform from the
[latest release](https://github.com/NikolayMetchev/money-manager/releases) and put it on your `PATH`:

| Platform | Release asset |
|----------|---------------|
| Linux x64 / ARM64 | `mm-linux-x64.tar.gz` / `mm-linux-arm64.tar.gz` (holds an executable `mm`) |
| macOS (Apple silicon) | `mm-macos-arm64.tar.gz` |
| Windows x64 | `mm-windows-x64.exe` |

The desktop installers also include the CLI: `mm-jvm`, which runs on the bundled Java runtime, and the native `mm`.

```sh
mm status                                            # open database, cloud sync and vault state
mm accounts --owner "Alice Smith"                    # accounts with owners and balances
mm balances --asset GBP --non-zero                   # one row per account and asset
mm transactions --account Current --from 2024-03-01  # newest first, with running balances
mm sql "SELECT COUNT(*) FROM transfer"               # one read-only SQL query, for debugging
mm pdf-lines statement.pdf                           # a PDF's text lines, as PDF line rules read them
mm --push import all                                 # download + import every folder and API, then upload
mm tui                                               # full-screen terminal UI: status & cloud sync, accounts, transactions, imports (? for keys, q to quit)
```

**Credential vault.** Commands that need API tokens or a Google sign-in unlock the vault with the password from
`--vault-password-stdin`, then `MM_VAULT_PASSWORD`, then a no-echo prompt in a terminal. The password is never stored.

**Exit codes**: `0` ok, `1` error, `2` usage, `3` partial failure or needs attention, `130` interrupted.

The full guide is on the [command-line page](https://nikolaymetchev.github.io/money-manager/cli.html), and the
JSON contract that scripts can rely on is in [`docs/cli-json.md`](docs/cli-json.md).

## Technology Stack

* **Language**: Kotlin (Multiplatform) · **Build**: [Bazel](https://bazel.build) · **JVM toolchain**: 25
* **Database**: SQLite via [SQLDelight](https://github.com/sqldelight/sqldelight)
* **DI**: [Metro](https://github.com/ZacSweers/metro) (compile-time)
* **UI**: Compose Multiplatform with Material 3
* **Object mapping**: [Mappie](https://github.com/Mr-Mappie/mappie)
* **HTTP**: Ktor Client · **Serialization**: kotlinx.serialization
* **Code quality**: Detekt, ktlint, strict and unused-dependency checks
* **Money**: `BigDecimal`-only policy — never `Double`/`Float` for monetary values

## Project Structure

The main modules (see [`CLAUDE.md`](CLAUDE.md) for the full table and the
[module dependency tree](https://nikolaymetchev.github.io/money-manager/modules.html)):

| Module | Purpose |
|--------|---------|
| `bazel/` | The build: Kotlin Multiplatform rules, the Gazelle extension that writes the BUILD files, the Maven resolver and the module checks ([how it works](bazel/README.md)) |
| `utils/*` | Platform-neutral helpers: `bigdecimal`, `currency`, `archive`, `files`, `credentialvault`, `localsettings`, `rest`, `httpclient`, `sqlite3`, parsers (CSV, QIF, XLSX), Compose widgets |
| `app/model/core/`, `app/model/*` | Domain model; the strategy vocabulary (`app/model/rules`) shared by CSV and API imports |
| `app/model/repository/read/`, `app/model/repository/write/` | `*ReadRepository` / `*WriteRepository` interfaces |
| `app/db/*` | SQLDelight schema, read/write SQL, repository implementations, `DatabaseManager` |
| `app/importengineapi/` | DB-free import/write API: `ImportBatch`/`ImportResult` and `ImportEngine.*` helpers |
| `app/csvimporter/`, `app/qifimporter/`, `app/apiimporter/` | Per-format importers that build an `ImportBatch` (DB-free) |
| `app/importer/` | Central import engine: applies an `ImportBatch` and deduplicates. The sole DB writer |
| `app/importdirectory/`, `app/apiimport/` | Import-folder and API-connection orchestration shared by the desktop app and the CLI |
| `app/session/` | Headless database bootstrap shared by every front end |
| `app/query/` | Read queries and JSON DTOs shared by the desktop app and the CLI |
| `app/tasks/`, `app/terminal/` | Background tasks and progress; terminal progress rendering (Mosaic) for the CLI |
| `app/remotestorage/*` | Remote-storage provider interface, Google Drive backend, hydrate/push sync |
| `app/strategies/`, `app/strategycatalog/` | Built-in strategy definitions; browse/install the published catalog |
| `app/di/core/` | Metro DI graph |
| `app/ui/*` | Compose UI (JVM, Android and the browser) |
| `app/main/jvm/`, `app/main/android/`, `app/main/web/`, `app/main/cli/` | Desktop, Android, browser and `mm` CLI entry points |
| `app/db/schemaspy/` | Generates the published SchemaSpy database docs |

## Building & Running

> Install [bazelisk](https://github.com/bazelbuild/bazelisk) (as `bazel`); it runs the Bazel version in
> `.bazelversion`, and Bazel downloads the JDK, Kotlin and Kotlin/Native itself. Android targets also need an
> Android SDK (`ANDROID_HOME`) with the platform the catalog's `android-compileSdk` names.

| Command | Description |
|---------|-------------|
| `bazel build //...` | Build everything |
| `bazel test //...` | Run every test this machine can run (plus ktlint, detekt and Android lint) |
| `bazel run //app/main/jvm:app` | Run the desktop application |
| `bazel run //app/main/web:serve -- 8080` | Serve the browser app on http://127.0.0.1:8080 (wasmJs, or js where the browser lacks WasmGC) |
| `bazel build //app/main/android:debug` | Build the Android debug APK (`bazel-bin/app/main/android/debug.apk`) |
| `bazel build //app/main/cli:mm-jvm` | Build the JVM `mm` into `bazel-bin/app/main/cli/mm-jvm/bin/mm-jvm` |
| `bazel build //app/main/cli:mm` | Link the native `mm` for this machine (`bazel-bin/app/main/cli/mm_<target>/mm.kexe`) |
| `bazel build //app/main/jvm:installer` | Build this OS's desktop installer (deb, dmg or msi) |
| `bazel run //:format` | Regenerate the BUILD files (Gazelle) and format the code (ktlint) |

## Development Notes

* **Single write seam** — every database mutation goes through the `ImportEngine`: callers build an
  `ImportBatch` and call `importEngine.import(batch)`; they never use a `*WriteRepository` directly.
  Repositories are split into read/write pairs, only `app/importer` is injected with the write side, and
  the UI gets a read-only view plus the engine. The build enforces
  it: only those modules can see the write interfaces. (The lone exception is device-id bootstrap.) See [`CLAUDE.md`](CLAUDE.md) for details.
* **Database design**, **CI/CD** and the **data model** are designed and reviewed closely.
* The **UI** and **unit tests** are partly AI-generated and will be reviewed/refactored over time.
* See [`CLAUDE.md`](CLAUDE.md) for detailed contributor/AI-assistant guidance.

## License

Licensed under the [Apache License 2.0](LICENSE).
