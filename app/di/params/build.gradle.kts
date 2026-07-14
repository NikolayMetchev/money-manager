plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
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
    }
}
