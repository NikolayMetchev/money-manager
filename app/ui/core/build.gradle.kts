plugins {
    id("moneymanager.compose-multiplatform-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.apiimporter)
                api(projects.app.csvimporter)
                api(projects.app.db.core)
                api(projects.app.importengineapi)
                api(projects.app.importfilesource.core)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.passthrough)
                api(projects.app.model.repository.read)
                api(projects.app.model.timeline)
                api(projects.app.qifimporter)
                api(projects.app.remotestorage.core)
                api(projects.app.remotestorage.sync)
                api(projects.app.strategycatalog)
                api(projects.app.ui.accounts)
                api(projects.app.ui.categories)
                api(projects.app.ui.components)
                api(projects.app.ui.currencies)
                api(projects.app.ui.foundation)
                api(projects.app.ui.imports.api)
                api(projects.app.ui.imports.csv)
                api(projects.app.ui.imports.importDirectory)
                api(projects.app.ui.imports.qif)
                api(projects.app.ui.imports.timeline)
                api(projects.app.ui.people)
                api(projects.app.ui.settings)
                api(projects.app.ui.transactions)
                api(projects.utils.localsettings)
                api(projects.utils.rest)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)

                implementation(projects.app.db.read)
                implementation(projects.app.remotestorage.googledrive)
                implementation(projects.utils.compose.filePicker)
                implementation(projects.utils.currency)
                implementation(projects.utils.parsers.qif)
                implementation(libs.androidx.navigation3.runtime)
            }
        }
        val jvmAndroidMain =
            create("jvmAndroidMain") {
                dependsOn(getByName("commonMain"))
            }
        getByName("commonTest") {
            dependencies {
                implementation(projects.test.app.db)
                implementation(projects.test.app.ui)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
        getByName("androidMain") {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(projects.app.db.write)
                api(libs.androidx.compose.foundation)
                api(libs.androidx.compose.foundation.layout)
                api(libs.androidx.compose.runtime)
                api(libs.androidx.compose.ui)
                api(libs.androidx.compose.ui.unit)

                implementation(projects.app.model.apistrategy)
                implementation(projects.app.model.repository.write)
                implementation(libs.androidx.compose.animation)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.runtime.annotation)
                implementation(libs.androidx.compose.ui.graphics)
                implementation(libs.androidx.compose.ui.text)
                implementation(libs.androidx.navigation3.ui)
                implementation(libs.androidx.savedstate)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.http)

                runtimeOnly(libs.kotlinx.coroutines.android)
            }
        }
        getByName("jvmMain") {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(projects.app.db.core)
                api(projects.app.db.write)
                api(projects.app.importengineapi)
                api(projects.app.importfilesource.core)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.repository.read)
                api(projects.app.model.timeline)
                api(projects.app.remotestorage.sync)
                api(projects.app.strategycatalog)
                api(projects.app.ui.foundation)
                api(projects.utils.localsettings)
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.layout.desktop)

                implementation(projects.app.model.apistrategy)
                implementation(projects.app.model.passthrough)
                implementation(projects.app.model.repository.write)
                implementation(projects.app.remotestorage.core)
                implementation(libs.androidx.compose.runtime.annotation)
                implementation(libs.androidx.navigation3.runtime.desktop)
                implementation(libs.androidx.savedstate.desktop)
                implementation(libs.compose.animation.desktop)
                implementation(libs.compose.foundation.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
                implementation(libs.jetbrains.navigation3.ui.desktop)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.http)

                runtimeOnly(libs.kotlinx.coroutines.swing)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(projects.app.apiimporter)
                implementation(projects.app.csvimporter)
                implementation(projects.app.db.di)
                implementation(projects.app.db.write)
                implementation(projects.app.importer)
                implementation(projects.app.model.apistrategy)
                implementation(projects.app.model.csv)
                implementation(projects.app.model.repository.write)
                implementation(projects.app.qifimporter)
                implementation(projects.utils.bigdecimal)
                implementation(projects.utils.rest)
                implementation(kotlin("test"))
                // Skiko native libraries for desktop UI tests
                implementation(compose.desktop.currentOs)
                implementation(libs.androidx.compose.runtime.desktop)
                implementation(libs.compose.ui.test.desktop)
            }
        }
        getByName("androidDeviceTest") {
            // Note: Cannot use dependsOn(commonTest) due to source set tree restrictions
            // Tests are shared via kotlin.srcDir() below
            dependencies {
                implementation(projects.app.db.di)
                implementation(projects.app.db.write)
                implementation(projects.app.importer)
                implementation(projects.app.model.apistrategy)
                implementation(projects.app.model.qif)
                implementation(projects.app.model.repository.write)
                implementation(projects.test.app.db)
                implementation(projects.test.app.ui)
                implementation(projects.utils.bigdecimal)
                implementation(kotlin("test"))
                implementation(libs.androidx.compose.runtime)
                implementation(libs.androidx.compose.ui.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
            kotlin.srcDir("src/commonTest/kotlin")
            // Include commonTest resources for test database files
            resources.srcDir("src/commonTest/resources")
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(projects.app.db.di)
                implementation(projects.app.db.write)
                implementation(projects.app.importer)
                implementation(projects.app.model.apistrategy)
                implementation(projects.app.model.repository.write)
                implementation(projects.test.app.db)
                implementation(projects.utils.bigdecimal)
                implementation(libs.androidx.compose.runtime)
                implementation(libs.androidx.compose.ui.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// Configure tests to run in headless mode for Compose Desktop
tasks.withType<Test> {
    systemProperty("java.awt.headless", "true")
    // Additional properties for Skiko on Windows
    systemProperty("skiko.test.harness", "true")
}

// Compose UI tests shared via commonTest require Robolectric (or a device) to run
// on the JVM. They already execute as jvmTest (Compose Desktop) and
// androidDeviceTest (managed emulator), so skip the host-test run here.
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    enabled = false
}
