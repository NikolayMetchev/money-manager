plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    id("moneymanager.android-convention")
}

// Opening a database outside any UI: where to open, the open itself, and the read services plus
// ImportEngine a session runs on. The desktop app, Android and the native CLI all start here.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.db.di)
                api(projects.app.db.write)
                api(projects.app.importdirectory)
                api(projects.app.importengineapi)
                api(projects.app.importfilesource.core)
                api(projects.app.model.core)
                api(projects.app.remotestorage.sync)
                api(projects.app.tasks)
                api(projects.utils.credentialvault)
                api(projects.utils.localsettings)

                implementation(projects.app.model.repository.read)
                implementation(libs.kermit)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.db.di)
                api(projects.app.db.write)
                api(projects.app.importdirectory)
                api(projects.app.importengineapi)
                api(projects.app.importfilesource.core)
                api(projects.app.model.core)
                api(projects.app.remotestorage.sync)
                api(projects.app.tasks)
                api(projects.utils.credentialvault)
                api(projects.utils.localsettings)

                implementation(projects.app.model.repository.read)
                implementation(libs.kermit.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(projects.app.db.write)

                implementation(libs.kermit.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(projects.app.db.write)
                implementation(projects.app.remotestorage.core)
                implementation(projects.test.app.db)
                implementation(projects.test.utils.credentialvault)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// The tests open real databases through the DB test helper, which on Android needs a device. They run
// as jvmTest and the native tests, so skip the host-test run (mirrors app/remotestorage/sync).
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    enabled = false
}
