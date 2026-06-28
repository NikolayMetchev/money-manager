plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.di.core)
                api(projects.app.model.core)

                implementation(libs.kotlinx.coroutines.test)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(kotlin("test-junit"))
                api(projects.app.db.core)
                api(projects.app.db.write)
                api(projects.app.di.core)
                api(projects.app.model.core)

                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(kotlin("test-junit"))
                api(projects.app.db.write)

                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.monitor)
                implementation(libs.kotlinx.coroutines.core)
                // The Android AppComponentParams carries a GoogleAccessTokenSource (googledrive) whose
                // httpClient is a Ktor client — both referenced by the test double in TestDatabaseHelper.
                implementation(libs.ktor.client.core)
                implementation(projects.app.remotestorage.googledrive)
            }
        }
    }
}
