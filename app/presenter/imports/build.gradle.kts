plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    alias(libs.plugins.compose.compiler)
}

// Starting import runs (directories, APIs, both) and following their tasks (#945). The TUI's Imports tab renders
// it today; the desktop screens, which start their runs per folder and per connection, are to follow.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.session)
                api(projects.app.tasks)
                api(libs.androidx.compose.runtime)
                api(libs.kotlinx.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(projects.app.apiimport)
                implementation(projects.app.db.core)
                implementation(projects.app.db.di)
                implementation(projects.app.db.write)
                implementation(projects.app.importengineapi)
                implementation(projects.app.importfilesource.core)
                implementation(projects.app.importfilesource.localfolder)
                implementation(projects.app.model.core)
                implementation(projects.app.model.importdirectory)
                implementation(projects.app.remotestorage.sync)
                implementation(projects.test.app.db)
                implementation(projects.test.utils.credentialvault)
                implementation(projects.utils.credentialvault)
                implementation(projects.utils.files)
                implementation(projects.utils.localsettings)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.io.core)
                implementation(libs.ktor.client.core)
                implementation(libs.molecule.runtime)
                implementation(libs.turbine)
            }
        }

        jvmMain {
            dependencies {
                api(projects.app.session)
                api(projects.app.tasks)
                api(libs.androidx.compose.runtime.desktop)
            }
        }
    }
}

// The tests open real databases through the DB test helper, which on Android needs a device (as app/session).
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    enabled = false
}
