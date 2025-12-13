# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Money Manager is a personal finance management application built with Kotlin Multiplatform. The project is structured to support multiple targets (JVM, Native, Mobile, Web), with the initial focus on JVM implementation.

## Technology Stack

- **Language**: Kotlin 2.2.21
- **Build System**: Gradle 9.2.0
- **Java Toolchain**: 25 (Target: 24)
- **Database**: SQLite via SQLDelight 2.2.1
- **Dependency Injection**: Metro 0.7.7 (compile-time DI)
- **UI Framework**: Compose Multiplatform 1.9.3 with Material 3
- **Object Mapping**: Mappie 2.2.21-1.6.1 (database-to-domain conversions)
- **Code Quality**: Detekt 2.0.0-alpha.1, ktlint 12.1.2
- **Multiplatform**: Kotlin Multiplatform (currently targeting JVM and Android)
- **Coroutines**: kotlinx-coroutines-core 1.10.2
- **DateTime**: kotlinx-datetime 0.7.1
- **Logging**: KMLogging 2.0.3 (multiplatform), Log4j 2.25.2 (JVM runtime)
- **Dependency Management**: Gradle Version Catalog (libs.versions.toml)
- **Build Tools**: Gradle dependency-analysis 3.5.0, sort-dependencies 0.15

## Build Commands

**IMPORTANT (Windows)**: On Windows, run Gradle commands directly using `./gradlew.bat` without wrapping them in `cmd /c` or `powershell`. For example: `./gradlew.bat build` (not `cmd /c "gradlew.bat build"`).

**IMPORTANT (Gradle Daemon)**: Do NOT use the `--no-daemon` flag when running Gradle commands. The Gradle daemon significantly improves build performance. Let Gradle manage the daemon lifecycle automatically.

**IMPORTANT (Console Output)**: Always use `--console=plain` when running Gradle commands. This provides cleaner output that is easier to read and parse. For example: `./gradlew build --console=plain`.

### Building the Project
```bash
./gradlew build
```

### Running the JVM Application
```bash
./gradlew :app:main:jvm:run
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
./gradlew :app:model:core:build      # Build core domain module
./gradlew :app:db:core:build         # Build database module
./gradlew :app:di:core:build         # Build DI module
./gradlew :app:ui:core:build         # Build Compose UI module
./gradlew :app:main:jvm:build        # Build JVM application
./gradlew :app:main:android:build    # Build Android application
```

### Running the Android Application
```bash
# Build and install debug APK on connected device/emulator
./gradlew :app:main:android:installDebug

# Build debug APK (output: app/main/android/build/outputs/apk/debug/)
./gradlew :app:main:android:assembleDebug

# Build release APK
./gradlew :app:main:android:assembleRelease

# Build Android App Bundle (for Play Store)
./gradlew :app:main:android:bundleRelease
```

**Android Requirements**:
- Android SDK with API 28+ (minSdk), API 36 (compileSdk)
- Connected Android device or emulator
- Target SDK: API 35

### Code Quality and Formatting
```bash
# Format code with ktlint and sort dependencies
./gradlew lintFormat

# Check dependency health
./gradlew buildHealth

# Run detekt static analysis
./gradlew detekt
```

### Creating Native Binaries

The JVM application can be packaged as native installers for Windows, macOS, and Linux using Compose Desktop packaging:

```bash
# Package for the current platform
./gradlew :app:main:jvm:packageDistributionForCurrentOS

# Create release binaries for the current platform
./gradlew :app:main:jvm:packageReleaseDistributionForCurrentOS

# Platform-specific installers:
./gradlew :app:main:jvm:packageMsi     # Windows MSI installer
./gradlew :app:main:jvm:packageDmg     # macOS DMG installer
./gradlew :app:main:jvm:packageDeb     # Linux DEB package

# Create an uber JAR (runnable on any platform with JRE)
./gradlew :app:main:jvm:packageUberJarForCurrentOS
```

**Output location**: Built packages will be in `app/main/jvm/build/compose/binaries/main/`

**Note**: The installers include a bundled Java Runtime, so users don't need Java installed. Cross-platform building may require the target platform (e.g., creating macOS .dmg typically requires a Mac).

### Pre-Push Checklist

**IMPORTANT**: Always build locally before pushing changes to ensure code quality and catch issues early:

```bash
./gradlew build
```

This single command now runs:
- Compiles all code
- Runs all tests (JVM unit tests)
- Generates code coverage reports (`koverXmlReport`)
- Checks dependency health (`buildHealth`)

This practice:
- Catches compilation errors before CI
- Runs all tests locally
- Verifies changes work on your environment
- Ensures dependency hygiene
- Saves CI minutes and time
- Prevents broken builds on main branch

Only push to remote after a successful local build.

### Git Hooks Setup

**IMPORTANT**: Install Git hooks to automatically validate configuration files before committing.

**⚠️ Note**: Git hooks are not tracked by version control and must be installed by each developer after cloning the repository. Run the installation script once after cloning:

```bash
# On Windows (using Git Bash or Command Prompt)
.\scripts\install-hooks.bat

# On Linux/macOS
./scripts/install-hooks.sh
```

**Installed Hooks:**
- **pre-commit**: Validates `codecov.yml` when it changes
  - Reads staged version of the file (works from any directory)
  - Uses curl to call Codecov validation API with 10-second timeout
  - Handles network failures gracefully
  - Prevents committing invalid codecov configuration
  - Works cross-platform (Windows/Linux/macOS)

**Manual Validation** (if hooks not installed):
```bash
curl -X POST https://codecov.io/validate -H 'Content-Type: application/x-yaml' --data-binary @codecov.yml
```

## Project Structure

### Module Organization

The project follows a modular architecture with clear separation of concerns:

- **gradle/build-logic/**: Gradle convention plugins for shared build configuration
  - `src/main/kotlin/`:
    - `moneymanager.kotlin-convention.gradle.kts`: Base Kotlin setup with detekt, ktlint, sort-dependencies
      - Automatically sets `group` based on project path (e.g., `:app:model:core` → `app.model.core`)
    - `moneymanager.kotlin-multiplatform-convention.gradle.kts`: Base KMP setup with JVM toolchain
    - `moneymanager.coroutines-convention.gradle.kts`: Adds coroutines support
    - `moneymanager.android-convention.gradle.kts`: Android library multiplatform setup
      - Automatically sets Android `namespace` based on group (e.g., `app.model.core` → `com.moneymanager.app.model.core`)
    - `moneymanager.android-application-convention.gradle.kts`: Android application setup
    - `moneymanager.compose-multiplatform-convention.gradle.kts`: Compose multiplatform with Material 3
    - `moneymanager.mappie-convention.gradle.kts`: Mappie object mapping
    - `moneymanager.metro-convention.gradle.kts`: Metro DI plugin and runtime

- **utils/bigdecimal/**: BigDecimal utility module (multiplatform: JVM, Android)
  - `src/jvmAndroidMain/kotlin/`: Shared JVM/Android implementation using `java.math.BigDecimal`
  - Provides arbitrary-precision decimal arithmetic for Money display values
  - Not available on native platforms (uses expect/actual pattern for future expansion)

- **utils/currency/**: Currency formatting utility module (multiplatform: JVM, Android)
  - Provides locale-aware currency symbol formatting
  - Used by UI layer for displaying formatted amounts

- **app/model/core/**: Core domain module (multiplatform: JVM, Android)
  - `src/commonMain/kotlin/com/moneymanager/domain/`:
    - `model/`: Domain models (Account, Category, Transaction)
    - `repository/`: Repository interfaces (AccountRepository, CategoryRepository, TransactionRepository)
  - Platform-independent domain logic only, no implementations

- **app/db/core/**: Database module (multiplatform: JVM, Android)
  - `src/commonMain/`:
    - `sqldelight/com/moneymanager/database/`: SQLDelight schema definitions
      - `Account.sq`: Account table and queries
      - `Category.sq`: Category table and queries
      - `Transaction.sq`: TransactionRecord table and queries
    - `kotlin/com/moneymanager/`:
      - `repository/`: Repository implementations using SQLDelight
      - `mapper/`: Mappie mappers for database-to-domain conversions
      - `DatabaseDriverFactory.kt`: Platform-specific database driver (expect/actual)
  - `src/jvmMain/`: JVM DatabaseDriverFactory (JDBC SQLite driver)
  - `src/androidMain/`: Android DatabaseDriverFactory (AndroidSqliteDriver)

- **app/di/core/**: Dependency injection module (multiplatform: JVM, Android)
  - `src/commonMain/kotlin/com/moneymanager/di/`:
    - `AppScope.kt`: Scope marker for application-wide singletons
    - `AppComponent.kt`: Main DI component interface
    - `DatabaseModule.kt`: Provides database instance
    - `RepositoryModule.kt`: Provides repository implementations

- **app/ui/core/**: Compose Multiplatform UI module (JVM, Android)
  - `src/commonMain/kotlin/com/moneymanager/ui/`:
    - `screens/`: Main screens (AccountsScreen, CategoriesScreen, TransactionsScreen)
    - `components/`: Reusable UI components (ErrorScreen, ErrorDialog, MinimalErrorScreen)
    - `error/`: Global schema error handling (GlobalSchemaErrorState, SchemaErrorDetector, collectAsStateWithSchemaErrorHandling)
  - `src/jvmMain/`: JVM-specific UI (Debug logging window, database selection dialog)
  - Material 3 design system

- **app/main/jvm/**: JVM Desktop application
  - `src/main/kotlin/com/moneymanager/`: JVM application entry point
  - Compose Desktop with native installers support

- **app/main/android/**: Android application
  - `src/main/kotlin/com/moneymanager/`: Android MainActivity
  - `src/main/AndroidManifest.xml`: App manifest
  - minSdk 28, targetSdk 35, compileSdk 36

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

### Money and Currency Handling

The project uses a **Money value class** to handle monetary amounts with proper precision and currency association.

**Why Money Type?**
- Prevents mixing amounts from different currencies
- Avoids floating-point precision issues
- Stores amounts as INTEGER in database (no rounding errors)
- Uses BigDecimal for display calculations
- Type-safe amount handling throughout the application

**Key Components**:

1. **Money Value Class** (`app/model/core/src/commonMain/kotlin/com/moneymanager/domain/model/Money.kt`):
   - Stores amount as `Long` (integer representation)
   - Stores associated `Currency` object
   - Provides `toDisplayValue()` to convert to BigDecimal for UI
   - Factory method `fromDisplayValue(displayValue: Double, currency: Currency)` for creating from user input
   - Arithmetic operations that maintain currency type

2. **BigDecimal Utility** (`utils/bigdecimal` module):
   - Multiplatform expect/actual implementation
   - JVM: Uses `java.math.BigDecimal`
   - Android: Uses `java.math.BigDecimal` via jvmAndroidMain sourceSet
   - Provides arbitrary-precision decimal arithmetic for display calculations
   - Not available on native platforms (future consideration)

3. **Currency Scale Factors** (`app/model/core/src/commonMain/kotlin/com/moneymanager/domain/model/CurrencyScaleFactors.kt`):
   - ISO 4217 currency scale factor lookup utility
   - Most currencies use 2 decimal places (scale factor 100)
   - Some currencies use 0 decimal places (e.g., JPY, KRW: scale factor 1)
   - Some currencies use 3 decimal places (e.g., BHD, KWD: scale factor 1000)
   - Formula: `scaleFactor = 10^decimalPlaces`

4. **Currency Formatting** (`app/ui/core/src/commonMain/kotlin/com/moneymanager/ui/util/CurrencyFormatting.kt`):
   - `formatAmount(money: Money): String` - Formats Money values for display
   - `formatAmount(amount: Number, currency: Currency): String` - Low-level formatter
   - Uses `utils/currency` module for locale-aware formatting with currency symbols

**Amount Storage**:
- Database stores amounts as `INTEGER` (SQLite type)
- Value = display_amount × scale_factor
- Example: $12.34 stored as 1234 (with scale factor 100)
- Example: ¥1000 stored as 1000 (with scale factor 1)
- Example: KWD 12.345 stored as 12345 (with scale factor 1000)

**Usage Pattern**:
```kotlin
// Creating Money from user input
val amount = Money.fromDisplayValue(12.34, usdCurrency)  // Stores 1234 internally

// Displaying Money
val formatted = formatAmount(amount)  // Returns "$12.34"

// Converting to BigDecimal for calculations
val displayValue: BigDecimal = amount.toDisplayValue()  // Returns 12.34

// Database storage
// SQLDelight automatically handles conversion between Long and INTEGER
// Currency information comes from joined Currency table
```

**Module Dependencies**:
- `app/model/core`: Contains Money value class and Currency domain models
- `utils/bigdecimal`: Provides BigDecimal for display calculations (jvmAndroidMain)
- `utils/currency`: Provides currency symbol formatting
- `app/ui/core`: Uses Money type for all amount displays, depends on bigdecimal and currency utils

### Database Schema

SQLDelight is used for type-safe database access. Schema files are located in:
- `app/db/core/src/commonMain/sqldelight/com/moneymanager/database/`
  - `Account.sq`: Account table and queries
  - `Category.sq`: Category table and queries
  - `Transaction.sq`: TransactionRecord table and queries

**Important**: Boolean values in the database are stored as INTEGER (0/1) since SQLite doesn't have a native boolean type. Do not use `AS Boolean` in schema definitions as it causes import issues.

### Mapper Layer

The project uses **Mappie** for type-safe object mapping between database types and domain types:

- Mapper interfaces are located in `app/db/core/src/commonMain/kotlin/com/moneymanager/mapper/`
- Mappie generates implementation code at compile time
- Mappers convert SQLDelight-generated types to domain models
- Example: `AccountEntity` (database) → `Account` (domain)

**Usage**:
```kotlin
@Mapper
interface AccountMapper {
    fun toDomain(entity: AccountEntity): Account
}
```

Mappie automatically generates mapping code based on matching property names and types.

### Repository Pattern

**Repository Interfaces** are defined in `app/model/core/src/commonMain/kotlin/com/moneymanager/domain/repository/`:
- `AccountRepository`
- `CategoryRepository`
- `TransactionRepository`

**Repository Implementations** are located in `app/db/core/src/commonMain/kotlin/com/moneymanager/repository/`:
- Plain Kotlin classes with constructor dependencies
- No DI annotations on the classes themselves
- Registered via `@Provides` functions in `RepositoryModule` (in `app/di/core`)
- All repositories use Kotlin Flow for reactive data streams
- Depend on SQLDelight's `MoneyManagerDatabase` and Mappie mappers

**Example**:
```kotlin
// Repository implementation (in app/db/core)
class AccountRepositoryImpl(
    private val database: MoneyManagerDatabase,
    private val mapper: AccountMapper
) : AccountRepository {
    override fun getAllAccounts(): Flow<List<Account>> =
        database.accountQueries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { entities -> entities.map(mapper::toDomain) }
}

// Registration in RepositoryModule (in app/di/core)
@Provides
@SingleIn(AppScope::class)
fun provideAccountRepository(
    database: MoneyManagerDatabase,
    mapper: AccountMapper
): AccountRepository = AccountRepositoryImpl(database, mapper)
```

### Dependency Injection with Metro

The project uses Metro for compile-time dependency injection. All DI configuration is in the `app/di/core` module:

**DI Components** (`app/di/core/src/commonMain/kotlin/com/moneymanager/di/`):
- `AppScope.kt`: Scope marker for application-wide singletons
- `AppComponent.kt`: Main DI component (interface) annotated with `@DependencyGraph(AppScope::class)`
- `DatabaseModule.kt`: Provides database instance via `@Provides` function
- `RepositoryModule.kt`: Provides all repository implementations via `@Provides` functions

**Component Creation**:
```kotlin
// Create with SqlDriver directly
val driver: SqlDriver = DatabaseDriverFactory().createDriver()
val component = AppComponent.create(driver)

// Access repositories
val accountRepository = component.accountRepository
val categoryRepository = component.categoryRepository
val transactionRepository = component.transactionRepository
```

**Module Pattern**:
```kotlin
@ContributesTo(AppScope::class)
interface DatabaseModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(driver: SqlDriver): MoneyManagerDatabase {
        return MoneyManagerDatabase(driver)
    }
}

@ContributesTo(AppScope::class)
interface RepositoryModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideAccountRepository(
        database: MoneyManagerDatabase,
        mapper: AccountMapper
    ): AccountRepository = AccountRepositoryImpl(database, mapper)
}
```

Metro generates the implementation classes at compile time via compiler plugin. Look for generated files in `build/generated/ksp/` to see the wiring code.

### Compose UI Module

The `app/ui/core` module provides a Material 3-based user interface for both JVM and Android platforms:

**Common Screens** (`app/ui/core/src/commonMain/kotlin/com/moneymanager/ui/screens/`):
- `AccountsScreen`: Display and manage financial accounts
- `CategoriesScreen`: Display and manage transaction categories
- `TransactionsScreen`: Display and manage transactions

**Error Handling Components** (`app/ui/core/src/commonMain/kotlin/com/moneymanager/ui/components/`):
- `ErrorScreen`: Full-screen error display
- `ErrorDialog`: Modal error dialog
- `MinimalErrorScreen`: Compact error display

**Platform-Specific UI** (`app/ui/core/src/jvmMain/kotlin/com/moneymanager/ui/`):
- `DebugLoggingWindow`: JVM-only debug logging console
- `DatabaseSelectionDialog`: JVM-only database file picker

**Design System**:
- Material 3 components
- Multiplatform support (JVM and Android only - Compose doesn't support native)
- Consistent UI across platforms

### UI Testing

The `app/ui/core` module includes comprehensive UI tests using Compose Multiplatform's testing framework.

**Test Location**: `app/ui/core/src/commonTest/kotlin/com/moneymanager/ui/`

**Test Framework**: Uses `runComposeUiTest` - the multiplatform approach (not JUnit 4-specific)

**Running Tests**:
```bash
# Run JVM unit tests
./gradlew :app/ui/core:jvmTest

# Run Android instrumented tests (requires device/emulator or managed device)
./gradlew :app/ui/core:connectedAndroidDeviceTest

# Run on managed device (Pixel 6 API 34 - no physical device needed)
./gradlew :app/ui/core:pixel6api34AndroidDeviceTest

# Run all tests (JVM + Android if available)
./gradlew :app/ui/core:test
```

#### Test Structure

Tests are written in `commonTest` and automatically run on both JVM and Android:

```kotlin
@OptIn(ExperimentalTestApi::class)
class ErrorScreenTest {
    @Test
    fun errorScreen_displaysErrorMessage() = runComposeUiTest {
        // Given
        val errorMessage = "Database connection failed"

        // When
        setContent {
            ErrorScreen(errorMessage = errorMessage, fullException = null)
        }

        // Then
        onNodeWithText("Application Error").assertIsDisplayed()
        onNodeWithText(errorMessage).assertIsDisplayed()
    }
}
```

#### Test Dependencies

**commonTest** (`app/ui/core/build.gradle.kts`):
```kotlin
val commonTest by getting {
    dependencies {
        @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
        implementation(compose.uiTest)  // Compose UI testing framework

        implementation(libs.kotlinx.coroutines.test)  // Coroutine testing
        implementation(libs.turbine)  // Flow testing utilities
    }
}
```

**jvmTest** - Additional JVM-specific dependencies:
```kotlin
val jvmTest by getting {
    dependencies {
        // Skiko native libraries for desktop UI tests
        implementation(compose.desktop.currentOs)
    }
}
```

**androidDeviceTest** - Android instrumented test configuration:
```kotlin
val androidDeviceTest by getting {
    // Note: Cannot use dependsOn(commonTest) due to source set tree restrictions
    // Tests are shared via kotlin.srcDir() below
    dependencies {
        @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
        implementation(compose.uiTest)

        implementation(kotlin("test"))  // Required for @Test annotation
        implementation(libs.androidx.test.core)
        implementation(libs.androidx.test.runner)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.turbine)
    }
    kotlin.srcDir("src/commonTest/kotlin")  // Share tests from commonTest
}
```

#### Android Test Manifest

Android instrumented tests require a manifest to launch activities. Located at `app/ui/core/src/androidDeviceTest/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity
            android:name="androidx.activity.ComponentActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

**Why this is needed**:
- `runComposeUiTest` on Android needs an Activity to host Compose UI
- Uses `ComponentActivity` from `androidx.activity:activity-compose`
- Must be declared with MAIN/LAUNCHER intent filter for test framework to launch it

#### Source Set Sharing for Android Tests

**Important**: Kotlin Multiplatform has source set tree restrictions that prevent using `dependsOn(commonTest)` for `androidDeviceTest`. Instead:

1. **Duplicate all test dependencies** in `androidDeviceTest` (see above)
2. **Use `kotlin.srcDir()`** to share test sources:
   ```kotlin
   kotlin.srcDir("src/commonTest/kotlin")
   ```

This pattern is used across all modules with Android device tests (e.g., `app/db/core`).

#### Testing Best Practices

**Test User Interactions**:
```kotlin
@Test
fun accountsScreen_opensCreateDialog_whenFabClicked() = runComposeUiTest {
    setContent { AccountsScreen(accountRepository = repository) }

    onNodeWithText("+").performClick()

    onNodeWithText("Create New Account").assertIsDisplayed()
}
```

**Use Fake Implementations**:
```kotlin
private class FakeAccountRepository(
    private val accounts: List<Account>
) : AccountRepository {
    private val accountsFlow = MutableStateFlow(accounts)

    override fun getAllAccounts(): Flow<List<Account>> = accountsFlow

    override suspend fun createAccount(account: Account): Long {
        val newId = (accounts.maxOfOrNull { it.id } ?: 0L) + 1
        accountsFlow.value = accountsFlow.value + account.copy(id = newId)
        return newId
    }
}
```

**Test Different States**:
```kotlin
@Test
fun accountsScreen_displaysEmptyState_whenNoAccounts() = runComposeUiTest {
    val repository = FakeAccountRepository(emptyList())
    setContent { AccountsScreen(accountRepository = repository) }

    onNodeWithText("No accounts yet. Add your first account!").assertIsDisplayed()
}

@Test
fun accountsScreen_displaysAccounts_whenAccountsExist() = runComposeUiTest {
    val repository = FakeAccountRepository(listOf(testAccount))
    setContent { AccountsScreen(accountRepository = repository) }

    onNodeWithText("Checking Account").assertIsDisplayed()
}
```

#### Common Test Utilities

**Turbine** - For testing Flows:
```kotlin
@Test
fun repository_emitsUpdates_whenDataChanges() = runTest {
    repository.getAllAccounts().test {
        assertEquals(emptyList(), awaitItem())  // Initial state

        repository.createAccount(testAccount)

        val accounts = awaitItem()  // Updated state
        assertEquals(1, accounts.size)
    }
}
```

#### Platform Differences

**JVM Tests**:
- Run with headless mode configured in `build.gradle.kts`:
  ```kotlin
  tasks.withType<Test> {
      systemProperty("java.awt.headless", "true")
      systemProperty("skiko.test.harness", "true")
  }
  ```
- Require `compose.desktop.currentOs` dependency for Skiko native libraries
- Fast execution (no device/emulator needed)

**Android Tests**:
- Run on physical devices, emulators, or managed devices
- Require Android test manifest with ComponentActivity declaration
- Test real Android UI behavior
- Can test Android-specific features (accessibility, screen sizes, etc.)

#### CI/CD Integration

Tests are automatically run in GitHub Actions:

**Unit tests** (JVM):
- Part of `./gradlew build` task
- Coverage reported to Codecov with "unit" flag

**Instrumented tests** (Android):
- Run on Gradle Managed Device (Pixel 6 API 34, AOSP ATD)
- Separate workflow job with AVD caching
- Coverage reported to Codecov with "instrumented" flag

## Development Notes

### Code Quality Tools

The project uses multiple tools to maintain code quality and consistency:

**Detekt** (Static Code Analysis):
- Configuration: `config/detekt/detekt.yml`
- Analyzes Kotlin code for code smells and potential issues
- Run: `./gradlew detekt`
- Reports generated in `build/reports/detekt/`

**ktlint** (Code Formatting):
- Enforces Kotlin coding conventions
- Automatically formats code
- Run: `./gradlew ktlintFormat`
- Check without formatting: `./gradlew ktlintCheck`

**sort-dependencies** (Gradle Dependencies):
- Automatically sorts dependencies in build files
- Run: `./gradlew sortDependencies`
- Ensures consistent dependency ordering

**lintFormat** (Combined Task):
- Runs both `sortDependencies` and `ktlintFormat`
- Run: `./gradlew lintFormat`
- Used by CI for automatic PR formatting

**buildHealth** (Dependency Analysis):
- Analyzes project dependency graph
- Identifies unused dependencies and missing declarations
- Run: `./gradlew buildHealth`
- Reports appear in `build/reports/dependency-analysis/`
- **Important**: Never exclude modules from this task

### Code Comments

Use comments sparingly and strategically. Good code should be self-documenting through clear naming and structure.

**Avoid**:
- Stating obvious details that the code already expresses clearly
- Comments that duplicate what the code does (e.g., `// Create a list` before `val list = mutableListOf()`)
- Comments that require updating when implementation changes
- Exposing implementation details that may change

**Prefer**:
- Self-documenting code with descriptive function and variable names
- Comments that explain "why" rather than "what"
- Comments for non-obvious behavior, constraints, or design decisions
- KDoc for public APIs explaining purpose, parameters, and return values
- Comments referencing external issues, bugs, or workarounds (e.g., "// Workaround for SQLDelight 2.2.1 parser limitations")

**Examples**:

❌ **Bad** - Obvious detail that will need updating:
```kotlin
if (isNewDatabase) {
    // Seed with default data (also creates incremental refresh triggers)
    DatabaseConfig.seedDatabase(database, driver)
}
```

✅ **Good** - No comment needed, code is self-explanatory:
```kotlin
if (isNewDatabase) {
    DatabaseConfig.seedDatabase(database, driver)
}
```

❌ **Bad** - Duplicates what code says:
```kotlin
// Loop through all currencies
allCurrencies.forEach { currency ->
    // Insert the currency
    currencyRepository.upsertCurrencyByCode(currency.code, currency.displayName)
}
```

✅ **Good** - Explains why, not what:
```kotlin
// NOTE: Triggers are created at runtime (not in schema) due to SQLDelight 2.2.1 parser limitations.
createIncrementalRefreshTriggers(driver)
```

### Type Imports

**ALWAYS** import types explicitly. **NEVER** use fully qualified type names in code.

❌ **Bad** - Fully qualified type name:
```kotlin
fun example(columnWidths: Map<CurrencyId, androidx.compose.ui.unit.Dp>) {
    // ...
}
```

✅ **Good** - Import the type:
```kotlin
import androidx.compose.ui.unit.Dp

fun example(columnWidths: Map<CurrencyId, Dp>) {
    // ...
}
```

This improves code readability and is automatically enforced by ktlint which will expand wildcard imports into explicit imports.

### Platform Support Status

The project currently supports:

- **JVM** ✅: Fully implemented with Compose Desktop UI
- **Android** ✅: Fully implemented with Compose for Android UI
  - minSdk 28, targetSdk 35, compileSdk 36
  - Platform-specific `DatabaseDriverFactory` using AndroidSqliteDriver
- **iOS** ⚠️: Not yet implemented (planned)
  - Database layer ready (can add iOS targets to `app/model/core` and `app/db/core`)
  - UI would need native iOS implementation (Compose Multiplatform supports iOS but not yet integrated)
- **Web** ⚠️: Not yet implemented (planned)
  - Would use `@OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }`
  - Database would need browser-compatible driver (e.g., SQL.js)
- **Native** ❌: Not supported
  - Compose Multiplatform doesn't support native targets
  - `app/ui/core` module is limited to JVM and Android only

### Adding New Platforms

To add iOS or Web support:

1. Add platform targets to multiplatform modules (`app/model/core`, `app/db/core`, `app/di/core`)
2. Implement platform-specific `DatabaseDriverFactory` in the corresponding source set
3. For iOS: Use NativeSqliteDriver from SQLDelight
4. For Web: Use a browser-compatible SQLite driver (e.g., SQL.js wrapper)
5. UI implementation would be platform-specific (native iOS UI or Compose for Web)

### Database Initialization

The database is initialized via Metro DI. The `DatabaseModule` provides the singleton instance:

```kotlin
@ContributesTo(AppScope::class)
interface DatabaseModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(driver: SqlDriver): MoneyManagerDatabase {
        return MoneyManagerDatabase(driver)
    }
}
```

The `AppComponent` is created with a `SqlDriver`, which is platform-specific:

**JVM** (`app/db/core/src/jvmMain/kotlin/com/moneymanager/DatabaseDriverFactory.kt`):
```kotlin
actual class DatabaseDriverFactory {
    actual fun createDriver(databasePath: String?): SqlDriver {
        val url = databasePath?.let { "jdbc:sqlite:$it" } ?: "jdbc:sqlite::memory:"
        return JdbcSqliteDriver(url).also {
            MoneyManagerDatabase.Schema.create(it)
        }
    }
}
```
- Uses JDBC SQLite driver
- In-memory database if no path provided: `createDriver()`
- Persistent database with path: `createDriver("/path/to/database.db")`

**Android** (`app/db/core/src/androidMain/kotlin/com/moneymanager/DatabaseDriverFactory.kt`):
```kotlin
actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(databasePath: String?): SqlDriver {
        return AndroidSqliteDriver(
            schema = MoneyManagerDatabase.Schema,
            context = context,
            name = databasePath ?: "moneymanager.db"
        )
    }
}
```
- Uses AndroidSqliteDriver
- Requires Android Context
- Default database name: "moneymanager.db"

**Usage**:
```kotlin
// JVM
val driver = DatabaseDriverFactory().createDriver("/path/to/db.db")
val component = AppComponent.create(driver)

// Android
val driver = DatabaseDriverFactory(applicationContext).createDriver()
val component = AppComponent.create(driver)
```

### Gradle Configuration

The project uses modern Gradle practices for maintainability:

**Version Catalog** (`gradle/libs.versions.toml`):
- Centralized dependency version management
- Defines all library versions, dependencies, and plugin references
- Accessed via `libs` in build files (e.g., `libs.metro.runtime`)
- **IMPORTANT**: ALL dependencies MUST be defined in `libs.versions.toml` and referenced via `libs.*`. NEVER hardcode dependencies with version numbers directly in build files (e.g., `implementation("group:artifact:version")`). Always add them to the version catalog first.

**Convention Plugins** (`gradle/build-logic/`):
- `moneymanager.kotlin-convention`: Base Kotlin setup with detekt, ktlint, sort-dependencies
- `moneymanager.kotlin-multiplatform-convention`: Base KMP setup (JVM toolchain 25, test dependencies)
- `moneymanager.coroutines-convention`: Adds coroutines support on top of base convention
- `moneymanager.android-convention`: Android library multiplatform setup (includes JVM + Android targets, managed device configuration)
- `moneymanager.android-application-convention`: Android application setup with compose convention
- `moneymanager.compose-multiplatform-convention`: Compose multiplatform with Material 3 (applies android-convention for Android support)
- `moneymanager.mappie-convention`: Mappie plugin and API dependencies
- `moneymanager.metro-convention`: Metro plugin and runtime dependencies
- Benefits: DRY principle, consistent configuration across modules, reduced build file duplication

**Module Build Files**:
- `app/model/core/build.gradle.kts`: Applies coroutines convention for domain models
- `app/db/core/build.gradle.kts`: Applies coroutines, mappie conventions, and SQLDelight plugin
- `app/di/core/build.gradle.kts`: Applies coroutines and metro conventions
- `app/ui/core/build.gradle.kts`: Applies android, coroutines, and compose-multiplatform conventions
- `app/main/jvm/build.gradle.kts`: JVM application with Compose Desktop
- `app/main/android/build.gradle.kts`: Android application with Compose
- `settings.gradle.kts`: Plugin management, auto module discovery via `com.pablisco.gradle.auto.include`
- Root `build.gradle.kts`: Minimal, defines shared repositories and lintFormat task

**Configuration Cache**:
- Enabled in `gradle.properties` for faster builds (11s → 2s improvement)
- Use `org.gradle.configuration-cache=true`

### Common Issues and Best Practices

1. **Java Version**: The project requires Java 25 toolchain (target: 24). Ensure `JAVA_HOME` points to JDK 21+ (minimum), preferably JDK 25.

2. **Kotlin Version for Metro**: Metro 0.7.7 is compiled with Kotlin 2.2.21. Keep project Kotlin version aligned to avoid issues with code generation.

3. **Metro Code Generation**:
   - Metro generates code at compile time via compiler plugin
   - Generated files appear in `build/generated/ksp/`
   - If code generation fails, ensure `metro-runtime` dependency is included
   - Components must be `interface` (not `abstract class` or `object`)
   - Modules with `@ContributesTo` must be `interface` (not `object`)
   - Repositories are now registered via `@Provides` functions, not `@Inject` or `@ContributesBinding`

4. **Mappie Object Mapping**:
   - Mappie generates mapping code at compile time
   - Mapper interfaces must be annotated with `@Mapper`
   - Generated implementations appear in `build/generated/ksp/`
   - Mappie automatically maps properties with matching names and types
   - For custom mapping logic, use `@Mapping` annotations

5. **SQLDelight 2.2.1 Breaking Changes**:
   - `execute()`, `update()`, and `delete()` methods now return `Long` (affected rows count) instead of `Unit`
   - Repository methods that need to return `Unit` must explicitly add `: Unit` return type and `Unit` statement
   - Never use `AS Boolean` type in .sq files - use INTEGER (0/1) and handle conversion in code

6. **Convention Plugin Dependencies**:
   - The `coroutines-convention` plugin applies the `kotlin-multiplatform-convention` plugin
   - The `android-convention` plugin applies both `kotlin-convention` and `compose-multiplatform-convention`
   - Module-specific dependencies (like `kotlinx-datetime`) should be in module build files, not conventions
   - Use appropriate convention plugins to avoid duplication

7. **Expect/Actual Classes**: Warnings about Beta features can be suppressed with `-Xexpect-actual-classes` flag if needed

8. **Configuration Cache**: With configuration cache enabled, first builds after changes to build files will invalidate cache (expected behavior)

9. **Dependency Management**:
   - **ALWAYS** add new dependencies to `gradle/libs.versions.toml` first
   - **NEVER** hardcode dependencies with versions directly in build files
   - Use `libs.*` references in all build files for consistency and centralized version management
   - Example: Use `implementation(libs.kotlinx.coroutines.core)` NOT `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")`
   - **ALWAYS** use typesafe project accessors for project dependencies
   - Use `projects.*` references instead of string-based `project()` calls
   - Example: Use `implementation(projects.utils.bigdecimal)` NOT `implementation(project(":utils:bigdecimal"))`
   - Typesafe accessors provide compile-time safety and better IDE support

10. **Build Health (Dependency Analysis)**:
    - **NEVER** skip or exclude modules from the `buildHealth` task
    - The dependency analysis plugin helps maintain a healthy dependency graph
    - If `buildHealth` fails, add the recommended dependencies directly to the module
    - Do NOT use `exclude()` or conditional logic to skip modules from dependency analysis

11. **Code Formatting**:
    - Always run `./gradlew lintFormat` before committing code
    - CI will automatically format PRs, but local formatting is preferred
    - ktlint and sort-dependencies maintain consistent code style

12. **Multiplatform Module Structure**:
    - `app/model/core`: Domain models and repository interfaces only (no implementations)
    - `app/db/core`: Database implementations, mappers, and platform-specific drivers
    - `app/di/core`: DI configuration and component definitions
    - `app/ui/core`: UI components (JVM and Android only - Compose doesn't support native)

13. **Codecov Configuration Validation**:
    - **ALWAYS** validate `codecov.yml` changes before committing
    - Use the Codecov validation API to check for configuration errors
    - Validation command (PowerShell on Windows):
      ```powershell
      Invoke-RestMethod -Uri https://codecov.io/validate -Body (Get-Content -Raw -LiteralPath .\codecov.yml) -Method Post
      ```
    - Common issues:
      - `after_n_builds` must be under `codecov.notify` (not under `coverage.status` or `comment`)
      - Flag definitions go under top-level `flags` section
      - Invalid fields will be reported by the validator
    - The project uses two flags: `unit` (JVM tests) and `instrumented` (Android tests)
    - Configuration waits for both uploads before sending notifications: `codecov.notify.after_n_builds: 2`

14. **Schema Error Handling in Compose UI**:
    - **ALWAYS** use `collectAsStateWithSchemaErrorHandling()` instead of `collectAsState()` for repository Flow collection
    - This ensures schema errors (missing tables, views, columns) are caught and displayed via `DatabaseSchemaErrorDialog`
    - Without this, schema errors will crash the app instead of showing the recovery dialog
    - The pattern:
      ```kotlin
      // WRONG - will crash on schema errors
      val accounts by accountRepository.getAllAccounts().collectAsState(initial = emptyList())

      // CORRECT - catches schema errors and reports to global state
      val accounts by accountRepository.getAllAccounts()
          .collectAsStateWithSchemaErrorHandling(initial = emptyList())
      ```
    - Import: `import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling`
    - How it works:
      - `SchemaErrorDetector` checks if exception message contains "no such table", "no such view", etc.
      - If schema error detected, it's reported to `GlobalSchemaErrorState`
      - `MoneyManagerApp` observes this state and shows `DatabaseSchemaErrorDialog`
      - User can backup and recreate or delete and recreate the database
    - Files involved:
      - `app/ui/core/src/commonMain/.../error/GlobalSchemaErrorState.kt` - Global MutableStateFlow holder
      - `app/ui/core/src/commonMain/.../error/SchemaErrorDetector.kt` - Detection logic
      - `app/ui/core/src/commonMain/.../error/SchemaErrorAwareFlow.kt` - The `collectAsStateWithSchemaErrorHandling` extension
