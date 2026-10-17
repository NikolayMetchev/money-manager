plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    alias(libs.plugins.compose.compiler)
}

// The open database's standing (#946): its path, cloud sync state and credential vault, with the actions on them
// (sync, download check, vault lock and unlock). The TUI's Status tab renders it; the desktop's cloud card is to follow.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.model.core)
                api(projects.app.remotestorage.sync)
                api(projects.app.session)
                api(projects.utils.credentialvault)
                api(libs.androidx.compose.runtime)
                api(libs.kotlinx.coroutines.core)

                implementation(projects.app.db.write)
            }
        }

        commonTest {
            dependencies {
                implementation(projects.app.db.core)
                implementation(projects.app.db.write)
                implementation(projects.app.importengineapi)
                implementation(projects.app.model.core)
                implementation(projects.app.remotestorage.core)
                implementation(projects.app.remotestorage.sync)
                implementation(projects.test.app.db)
                implementation(projects.test.app.remotestorage)
                implementation(projects.test.utils.credentialvault)
                implementation(projects.utils.localsettings)
                implementation(kotlin("test"))
                implementation(libs.molecule.runtime)
                implementation(libs.turbine)
            }
        }

        jvmMain {
            dependencies {
                api(projects.app.model.core)
                api(projects.app.remotestorage.sync)
                api(projects.app.session)
                api(projects.utils.credentialvault)
                api(libs.androidx.compose.runtime.desktop)

                implementation(projects.app.db.write)
            }
        }
    }
}

// The tests open real databases through the DB test helper, which on Android needs a device (as app/session).
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    enabled = false
}
