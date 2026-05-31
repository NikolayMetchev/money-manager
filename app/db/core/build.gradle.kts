plugins {
    id("moneymanager.android-convention")
    id("moneymanager.mappie-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.mappie.api)
                api(projects.app.model.core)
                api(projects.utils.currency)

                implementation(libs.kmlogging)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.sqldelight.coroutines.extensions)
                implementation(projects.utils.bigdecimal)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(projects.app.model.core)
                implementation(projects.test.app.db)
            }
        }

        val jvmMain by getting {
            dependencies {
                api(libs.kotlinx.serialization.core)
                api(projects.utils.currency)

                implementation(libs.diamondedge.logging)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(projects.app.db.core)
                implementation(projects.app.di.core)
            }
        }

        val androidMain by getting {
            dependencies {
                api(libs.kotlinx.serialization.core)

                implementation(libs.androidx.sqlite)
                implementation(libs.diamondedge.logging)
                implementation(libs.sqldelight.android.driver)
            }
        }

        val androidHostTest by getting {
            dependencies {
                implementation(projects.app.di.core)
            }
        }

        val androidDeviceTest by getting {
            // Note: Cannot use dependsOn(commonTest) due to source set tree restrictions
            // Tests are shared via srcDir() below, but we exclude the expect declarations
            // file since androidDeviceTest provides its own implementation
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
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

sqldelight {
    databases {
        create("MoneyManagerDatabase") {
            packageName.set("com.moneymanager.database.sql")
            verifyMigrations.set(false)
        }
    }
}

tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    enabled = false
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
