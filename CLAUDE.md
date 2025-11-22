# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Money Manager is a personal finance management application built with Kotlin Multiplatform. The project is structured to support multiple targets (JVM, Native, Mobile, Web), with the initial focus on JVM implementation.

## Technology Stack

- **Language**: Kotlin 2.2.20
- **Build System**: Gradle 9.2.0
- **Java Version**: 21
- **Database**: SQLite via SQLDelight 2.2.1
- **Dependency Injection**: Metro 0.7.5 (compile-time DI)
- **Multiplatform**: Kotlin Multiplatform (currently targeting JVM, with plans for Android, iOS, and Web)
- **Coroutines**: kotlinx-coroutines-core 1.9.0
- **DateTime**: kotlinx-datetime 0.7.1
- **Dependency Management**: Gradle Version Catalog (libs.versions.toml)

## Build Commands

**IMPORTANT (Windows)**: On Windows, run Gradle commands directly using `./gradlew.bat` without wrapping them in `cmd /c` or `powershell`. For example: `./gradlew.bat build` (not `cmd /c "gradlew.bat build"`).

### Building the Project
```bash
./gradlew build
```

### Running the JVM Application
```bash
./gradlew :jvm-app:run
```

### Cleaning Build Artifacts
```bash
./gradlew clean
```

### Running Tests
```bash
./gradlew test
```

### Building Specific Modules
```bash
./gradlew :shared:build      # Build shared multiplatform module
./gradlew :jvm-app:build     # Build JVM application
```

### Pre-Push Checklist

**IMPORTANT**: Always build locally before pushing changes to ensure code quality and catch issues early:

```bash
./gradlew build
```

This practice:
- Catches compilation errors before CI
- Runs all tests locally
- Verifies changes work on your environment
- Saves CI minutes and time
- Prevents broken builds on main branch

Only push to remote after a successful local build.

## Project Structure

### Module Organization

- **build-logic/**: Gradle convention plugins for shared build configuration
  - `src/main/kotlin/`:
    - `moneymanager.kotlin-multiplatform-convention.gradle.kts`: Base KMP setup with JVM toolchain and test dependencies
    - `moneymanager.coroutines-convention.gradle.kts`: Adds coroutines support on top of base convention

- **shared/**: Kotlin Multiplatform module containing core business logic
  - `src/commonMain/`: Platform-independent code
    - `kotlin/com/moneymanager/domain/`: Domain models and repository interfaces
    - `kotlin/com/moneymanager/data/`: Repository implementations and driver factories
    - `kotlin/com/moneymanager/di/`: Metro DI components and modules
    - `sqldelight/com/moneymanager/database/`: SQLDelight schema definitions
  - `src/jvmMain/`: JVM-specific implementations (DatabaseDriverFactory)

- **jvm-app/**: JVM-specific application module
  - `src/main/kotlin/com/moneymanager/`: JVM application entry point

### Domain Model

The application follows a clean architecture pattern with three main entities:

1. **Account**: Represents financial accounts (checking, savings, credit card, cash, investment)
   - Fields: id, name, type, currency, balances, color, icon, active status

2. **Category**: Represents transaction categories (income/expense)
   - Supports hierarchical categories (parent/child relationships)
   - Fields: id, name, type, color, icon, parentId, active status

3. **Transaction**: Represents financial transactions
   - Supports income, expense, and transfer types
   - Fields: id, accountId, categoryId, type, amount, currency, description, date, etc.

### Database Schema

SQLDelight is used for type-safe database access. Schema files are located in:
- `shared/src/commonMain/sqldelight/com/moneymanager/database/`
  - `Account.sq`: Account table and queries
  - `Category.sq`: Category table and queries
  - `Transaction.sq`: TransactionRecord table and queries

**Important**: Boolean values in the database are stored as INTEGER (0/1) since SQLite doesn't have a native boolean type. Do not use `AS Boolean` in schema definitions as it causes import issues.

### Repository Pattern

Repository interfaces are defined in `shared/src/commonMain/kotlin/com/moneymanager/domain/repository/`:
- `AccountRepository`
- `CategoryRepository`
- `TransactionRepository`

Repository implementations use Metro DI annotations:
- `@Inject`: Constructor injection for dependencies
- `@SingleIn(AppScope::class)`: Singleton scoping
- `@ContributesBinding(AppScope::class)`: Automatic binding of implementation to interface

All repositories use Kotlin Flow for reactive data streams and depend on SQLDelight's `MoneyManagerDatabase`.

### Dependency Injection with Metro

The project uses Metro for compile-time dependency injection:

**DI Components** (`shared/src/commonMain/kotlin/com/moneymanager/di/`):
- `AppScope.kt`: Scope marker for application-wide singletons
- `AppComponent.kt`: Main DI component (interface) annotated with `@DependencyGraph(AppScope::class)`
- `DatabaseModule.kt`: Provides database instance with `@ContributesTo(AppScope::class)` and `@Provides`

**Usage**:
```kotlin
val component = AppComponent.create(DatabaseDriverFactory())
val accountRepository = component.accountRepository
```

Metro generates the implementation classes at compile time. Look for generated files in `build/generated/` to see the wiring code.

## Development Notes

### Adding New Platforms

The project is set up for multiplatform but currently only targets JVM. To add other platforms:

1. **Android**: Add `id("com.android.library")` plugin and `androidTarget()` in shared/build.gradle.kts
2. **iOS**: Add iOS targets (`iosX64()`, `iosArm64()`, `iosSimulatorArm64()`)
3. **Web**: Add `@OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }`

For each platform, implement platform-specific `DatabaseDriverFactory` in the corresponding source set.

### Database Initialization

The database is initialized via Metro DI. The `DatabaseModule` provides the singleton instance:

```kotlin
@ContributesTo(AppScope::class)
interface DatabaseModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(driverFactory: DatabaseDriverFactory): MoneyManagerDatabase {
        return MoneyManagerDatabase(driverFactory.createDriver())
    }
}
```

The `DatabaseDriverFactory` is platform-specific (expect/actual pattern):
- **JVM**: Uses SQLite JDBC driver with in-memory database by default
- To use persistent storage: pass a database path to `createDriver(databasePath: String)`

### Gradle Configuration

The project uses modern Gradle practices for maintainability:

**Version Catalog** (`gradle/libs.versions.toml`):
- Centralized dependency version management
- Defines all library versions, dependencies, and plugin references
- Accessed via `libs` in build files (e.g., `libs.metro.runtime`)
- **IMPORTANT**: ALL dependencies MUST be defined in `libs.versions.toml` and referenced via `libs.*`. NEVER hardcode dependencies with version numbers directly in build files (e.g., `implementation("group:artifact:version")`). Always add them to the version catalog first.

**Convention Plugins** (`build-logic/`):
- `moneymanager.kotlin-multiplatform-convention`: Base KMP setup (JVM toolchain 21, test dependencies)
- `moneymanager.coroutines-convention`: Depends on base convention and adds coroutines
- Benefits: DRY principle, consistent configuration across modules

**Module Build Files**:
- `shared/build.gradle.kts`: Applies coroutines convention, SQLDelight, and Metro plugins
- `jvm-app/build.gradle.kts`: JVM application with main class configuration
- `settings.gradle.kts`: Plugin management and module declarations
- Root `build.gradle.kts`: Minimal, defines shared repositories

**Configuration Cache**:
- Enabled in `gradle.properties` for faster builds (11s → 2s improvement)
- Use `org.gradle.configuration-cache=true`

### Common Issues and Best Practices

1. **Java Version**: The project requires Java 21. Ensure `JAVA_HOME` points to JDK 21+

2. **Kotlin Version for Metro**: Metro 0.7.5 is compiled with Kotlin 2.2.20. Keep project Kotlin version aligned to avoid issues with code generation.

3. **Metro Code Generation**:
   - Metro generates code at compile time via compiler plugin
   - Generated files appear in `build/generated/ksp/`
   - If code generation fails, ensure `metro-runtime` dependency is included
   - Components must be `interface` (not `abstract class` or `object`)
   - Modules with `@ContributesTo` must be `interface` (not `object`)

4. **SQLDelight 2.2.1 Breaking Changes**:
   - `execute()`, `update()`, and `delete()` methods now return `Long` (affected rows count) instead of `Unit`
   - Repository methods that need to return `Unit` must explicitly add `: Unit` return type and `Unit` statement
   - Never use `AS Boolean` type in .sq files - use INTEGER (0/1) and handle conversion in code

5. **Convention Plugin Dependencies**:
   - The `coroutines-convention` plugin applies the `kotlin-multiplatform-convention` plugin
   - Only apply `coroutines-convention` if you need both KMP and coroutines
   - Module-specific dependencies (like `kotlinx-datetime`) should be in module build files, not conventions

6. **Expect/Actual Classes**: Warnings about Beta features can be suppressed with `-Xexpect-actual-classes` flag if needed

7. **Configuration Cache**: With configuration cache enabled, first builds after changes to build files will invalidate cache (expected behavior)

8. **Dependency Management**:
   - **ALWAYS** add new dependencies to `gradle/libs.versions.toml` first
   - **NEVER** hardcode dependencies with versions directly in build files
   - Use `libs.*` references in all build files for consistency and centralized version management
   - Example: Use `implementation(libs.kotlinx.coroutines.core)` NOT `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")`
