plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    id("moneymanager.android-convention")
}

// Test support only: an in-memory remote store standing in for Google Drive, for tests of the cloud sync.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.remotestorage.core)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.remotestorage.core)
            }
        }
    }
}
