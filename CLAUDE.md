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
| `./gradlew build` | Build all, run tests, coverage, dependency health |
| `./gradlew :app:main:jvm:run` | Run JVM application |
| `./gradlew :app:main:android:installDebug` | Install Android debug APK |
| `./gradlew :app:ui:core:pixel6api34AndroidDeviceTest` | Run Android UI tests on managed device |
| `./gradlew lintFormat` | Format code (ktlint + sort dependencies) |
| `./gradlew buildHealth` | Check dependency health |
| `./gradlew detekt` | Static analysis |

**Pre-push**: Always run `./gradlew build` locally before pushing.

## Project Structure

### Modules

| Module | Purpose |
|--------|---------|
| `gradle/build-logic/` | Convention plugins (kotlin, android, compose, metro, mappie) |
| `utils/bigdecimal/` | Arbitrary-precision decimal arithmetic (JVM/Android) |
| `utils/currency/` | Locale-aware currency formatting |
| `app/model/core/` | Domain models and repository interfaces |
| `app/db/core/` | SQLDelight database, repository implementations, mappers |
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

### Testing

- Tests in `commonTest` run on both JVM and Android
- Use `runComposeUiTest` for UI tests
- Android tests require manifest with `ComponentActivity` declaration
- Share test sources via `kotlin.srcDir("src/commonTest/kotlin")`
- **Android Device Tests**: Use `:app:ui:core:pixel6api34AndroidDeviceTest` for UI tests on managed device emulator
- **Test Stability**: Always call `waitForIdle()` after `waitUntilDoesNotExist()` to ensure recompositions complete before test ends

### Platform Support

- **JVM** ✅ | **Android** ✅ | **iOS** ⚠️ planned | **Web** ⚠️ planned | **Native** ❌

### Common Issues

1. **Java**: Requires JDK 25 toolchain
2. **Metro**: Keep Kotlin version aligned (2.2.21). Components/modules must be `interface`
3. **SQLDelight**: `execute()`/`update()`/`delete()` return `Long`, not `Unit`
4. **Configuration Cache**: Enabled for faster builds; invalidates on build file changes
