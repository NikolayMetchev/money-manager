plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.importengineapi)
                api(projects.app.importer)
                api(projects.app.model.core)
                api(projects.utils.localsettings)
            }
        }

        val jvmMain by getting {
            dependencies {
                api(libs.metro.runtime)
                api(projects.app.db.core)
                api(projects.app.importengineapi)
                api(projects.app.model.core)
                api(projects.app.remotestorage.core)
                api(projects.app.remotestorage.sync)
                api(projects.utils.localsettings)

                implementation(projects.app.importer)
                implementation(projects.app.remotestorage.googledrive)
                implementation(projects.app.remotestorage.localfolder)
            }
        }

        val androidMain by getting {
            dependencies {
                api(libs.metro.runtime)
                api(projects.app.remotestorage.core)
                api(projects.app.remotestorage.sync)

                implementation(projects.app.remotestorage.googledrive)
                implementation(projects.app.remotestorage.localfolder)
            }
        }
    }
}
