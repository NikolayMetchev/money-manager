plugins {
    id("moneymanager.native-convention")
    id("moneymanager.android-convention")
    id("moneymanager.metro-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.di.params)
                api(projects.app.di.scope)
                api(projects.app.remotestorage.core)
                api(projects.app.remotestorage.sync)
                api(projects.utils.credentialvault)
                api(projects.utils.localsettings)

                implementation(projects.app.remotestorage.googledrive)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.di.params)
                api(projects.app.di.scope)
                api(projects.app.remotestorage.core)
                api(projects.app.remotestorage.sync)
                api(projects.utils.credentialvault)
                api(projects.utils.localsettings)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(projects.app.remotestorage.googledrive)
            }
        }
    }
}
