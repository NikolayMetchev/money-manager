plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(projects.app.db.read)
                api(projects.app.db.write)
                api(projects.app.importengineapi)
                api(projects.app.model.core)
                api(projects.utils.currency)

                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(projects.app.importer)
                implementation(projects.utils.bigdecimal)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(projects.app.csvimporter)
                implementation(projects.app.importengineapi)
                implementation(projects.app.model.core)
                implementation(projects.app.qifimporter)
                implementation(projects.test.app.db)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(libs.kotlinx.serialization.core)
                // sqldelight.runtime + read/write are used directly by jvmMain (JvmDatabaseManager uses
                // the read Schema); declared here per dependency-analysis (the SQLDelight plugin that
                // used to supply the runtime now lives in :app:db:read/:write).
                api(libs.sqldelight.runtime)
                api(projects.app.db.write)
                api(projects.utils.currency)

                implementation(libs.sqldelight.sqlite.driver)
                implementation(projects.app.db.seed)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(libs.sqldelight.runtime)
                implementation(projects.app.db.core)
                implementation(projects.app.db.read)
                implementation(projects.app.di.core)
                implementation(projects.app.importfilesource.core)
            }
        }

        getByName("androidMain") {
            dependencies {
                api(libs.kotlinx.serialization.core)
                api(libs.sqldelight.runtime)

                implementation(libs.androidx.sqlite)
                implementation(libs.sqldelight.android.driver)
                implementation(projects.app.db.seed)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(libs.sqldelight.runtime)
                implementation(projects.app.di.core)
                implementation(projects.app.importfilesource.core)
            }
        }

        getByName("androidDeviceTest") {
            // Note: Cannot use dependsOn(commonTest) due to source set tree restrictions
            // Tests are shared via srcDir() below, but we exclude the expect declarations
            // file since androidDeviceTest provides its own implementation
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.sqldelight.runtime)
                implementation(projects.app.di.core)
                implementation(projects.test.app.db)

                runtimeOnly(libs.androidx.test.runner)
            }
            // Include test packages from commonTest (not the expect declarations file)
            kotlin.srcDir("src/commonTest/kotlin/com/moneymanager/database/repository")
            kotlin.srcDir("src/commonTest/kotlin/com/moneymanager/database/audit")
        }
    }
}

// Repository tests shared via commonTest use androidx.test.InstrumentationRegistry
// on Android, which requires a device (or Robolectric). They already run via
// jvmTest and androidDeviceTest, so skip the host-test run here.
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    enabled = false
}

val monzoApiFixtureToolClass = "com.moneymanager.database.tools.MonzoApiSessionFixtureToolKt"
val monzoApiFixtureArchiveToolClass = "com.moneymanager.database.tools.MonzoApiSessionFixtureArchiveToolKt"
val monzoBalanceFixtureToolClass = "com.moneymanager.database.tools.MonzoBalanceFixtureToolKt"
val moneyManagerDbPath = File(System.getProperty("user.home"), ".moneymanager/money_manager.db")
val monzoFixtureDir = layout.projectDirectory.dir("src/commonTest/resources/monzo/sample-apis").asFile
val monzoBalancesFixtureFile = monzoFixtureDir.resolve("balances.json")
val monzoEncryptedFixtureFile =
    layout.projectDirectory
        .dir("src/commonTest/resources/monzo")
        .asFile
        .resolve("sample-apis.zip")

tasks.register<JavaExec>("updateEncryptedMonzoApiSessionFixtures") {
    group = "verification"
    description = "Encrypts the Monzo API fixture directory into a zip archive."
    dependsOn("compileTestKotlinJvm")
    mainClass.set(monzoApiFixtureArchiveToolClass)
    classpath = sourceSets["jvmTest"].runtimeClasspath
    args(
        "encrypt",
        monzoFixtureDir.absolutePath,
        monzoEncryptedFixtureFile.absolutePath,
    )
}

tasks.register<JavaExec>("restoreMonzoApiSessionFixtures") {
    group = "verification"
    description = "Restores the Monzo API fixture directory from the encrypted archive when empty."
    dependsOn("compileTestKotlinJvm")
    mainClass.set(monzoApiFixtureArchiveToolClass)
    classpath = sourceSets["jvmTest"].runtimeClasspath
    args(
        "decrypt",
        monzoEncryptedFixtureFile.absolutePath,
        monzoFixtureDir.absolutePath,
    )
}

tasks.register<JavaExec>("exportMonzoApiSessionFixtures") {
    group = "verification"
    description = "Exports the API session transcript tables to JSON fixtures."
    dependsOn("compileTestKotlinJvm")
    mainClass.set(monzoApiFixtureToolClass)
    classpath = sourceSets["jvmTest"].runtimeClasspath
    args(
        "export",
        moneyManagerDbPath.absolutePath,
        monzoFixtureDir.absolutePath,
    )
}

tasks.register<JavaExec>("importMonzoApiSessionFixtures") {
    group = "verification"
    description = "Imports API session transcript JSON fixtures into a SQLite database."
    dependsOn("compileTestKotlinJvm")
    mainClass.set(monzoApiFixtureToolClass)
    classpath = sourceSets["jvmTest"].runtimeClasspath
    args(
        "import",
        moneyManagerDbPath.absolutePath,
        monzoFixtureDir.absolutePath,
    )
}

tasks.register<JavaExec>("generateMonzoBalanceFixtures") {
    group = "verification"
    description = "Generates Monzo balances.json fixture from the default Money Manager database."
    dependsOn("compileTestKotlinJvm")
    mainClass.set(monzoBalanceFixtureToolClass)
    classpath = sourceSets["jvmTest"].runtimeClasspath
    args(
        "generate",
        moneyManagerDbPath.absolutePath,
        monzoBalancesFixtureFile.absolutePath,
    )
}
