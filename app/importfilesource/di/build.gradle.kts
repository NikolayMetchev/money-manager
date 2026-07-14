plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

// Platform factories for the import-file sources. Not a Metro module: the entry points construct these
// directly, because the browsers need platform handles the graph does not carry.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.di.params)
                api(projects.app.importfilesource.core)
                api(projects.utils.localsettings)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.di.params)
                api(projects.app.importfilesource.core)
                api(projects.utils.localsettings)

                implementation(projects.app.importfilesource.localfolder)
                implementation(projects.app.model.importdirectory)
                implementation(projects.app.remotestorage.googledrive)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(projects.app.importfilesource.localfolder)
                implementation(projects.app.model.importdirectory)
                implementation(projects.app.remotestorage.googledrive)
            }
        }
    }
}
