# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Money Manager is a personal finance management application built with Kotlin Multiplatform. The project is structured to support multiple targets (JVM, Native, Mobile, Web), with the initial focus on JVM implementation.

## Technology Stack

- **Language**: Kotlin 2.2.21
- **Build System**: Gradle 9.2.0
- **Java Version**: 21
- **Database**: SQLite via SQLDelight 2.0.2
- **Multiplatform**: Kotlin Multiplatform (currently targeting JVM, with plans for Android, iOS, and Web)
- **Coroutines**: kotlinx-coroutines-core 1.9.0
- **DateTime**: kotlinx-datetime 0.6.1

## Build Commands

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

## Project Structure

### Module Organization

- **shared/**: Kotlin Multiplatform module containing core business logic
  - `src/commonMain/`: Platform-independent code
    - `kotlin/com/moneymanager/domain/`: Domain models and repository interfaces
    - `kotlin/com/moneymanager/data/`: Database initialization and driver factories
    - `sqldelight/com/moneymanager/database/`: SQLDelight schema definitions
  - `src/jvmMain/`: JVM-specific implementations

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

These use Kotlin Flow for reactive data streams.

## Development Notes

### Adding New Platforms

The project is set up for multiplatform but currently only targets JVM. To add other platforms:

1. **Android**: Add `id("com.android.library")` plugin and `androidTarget()` in shared/build.gradle.kts
2. **iOS**: Add iOS targets (`iosX64()`, `iosArm64()`, `iosSimulatorArm64()`)
3. **Web**: Add `@OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }`

For each platform, implement platform-specific `DatabaseDriverFactory` in the corresponding source set.

### Database Initialization

The database is initialized using the singleton `Database` object:
```kotlin
Database.initialize(DatabaseDriverFactory())
val db = Database.getInstance()
```

The JVM implementation uses an in-memory SQLite database by default. For persistent storage, use `createDriver(databasePath: String)` instead.

### Gradle Configuration

- Root `build.gradle.kts`: Defines plugin versions and shared repositories
- `shared/build.gradle.kts`: Multiplatform module with SQLDelight configuration
- `jvm-app/build.gradle.kts`: JVM application with main class configuration
- `settings.gradle.kts`: Includes plugin management and module declarations

### Common Issues

1. **Java Version**: The project requires Java 21. Ensure `JAVA_HOME` points to JDK 21+
2. **Gradle Daemon**: The project is configured to stop daemon after builds (single-use for JVM settings)
3. **SQLDelight Boolean**: Never use `AS Boolean` type in .sq files - use INTEGER and handle conversion in code if needed
4. **Expect/Actual Classes**: Warnings about Beta features can be suppressed with `-Xexpect-actual-classes` flag if needed
