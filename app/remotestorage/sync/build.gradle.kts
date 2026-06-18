plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.model.core)
                api(projects.app.remotestorage.core)
                api(projects.utils.localsettings)

                implementation(projects.utils.archive)
            }
        }

        // KMP ABI deps must be re-declared per platform source set for dependency-analysis.
        jvmMain {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.model.core)
                api(projects.app.remotestorage.core)
                api(projects.utils.localsettings)

                implementation(libs.kotlinx.coroutines.core)
            }
        }

        androidMain {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.model.core)
                api(projects.app.remotestorage.core)
                api(projects.utils.localsettings)

                implementation(libs.kotlinx.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.sqldelight.runtime)
                implementation(projects.test.app.db)
                implementation(projects.utils.archive)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(projects.app.remotestorage.sync)
            }
        }
    }
}

// The round-trip tests shared via commonTest use the DB test helper, which on Android needs
// androidx.test.InstrumentationRegistry (a device). They already run via jvmTest and androidDeviceTest,
// so skip the host-test run here (mirrors app/db/core).
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    enabled = false
}
