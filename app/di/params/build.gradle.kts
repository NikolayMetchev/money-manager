plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
}

kotlin {
    sourceSets {
        getByName("androidMain") {
            dependencies {
                // api (not implementation): GoogleAccessTokenSource is a field type of the Android
                // AppComponentParams, so it is part of this module's ABI.
                api(projects.app.remotestorage.googledrive)
            }
        }
        // BrowserLauncher is a field type of the JVM and native AppComponentParams.
        getByName("jvmMain") {
            dependencies {
                api(projects.app.remotestorage.googledrive)
            }
        }
        nativeMain {
            dependencies {
                api(projects.app.remotestorage.googledrive)
            }
        }
    }
}
