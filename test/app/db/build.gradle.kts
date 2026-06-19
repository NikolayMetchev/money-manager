plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.di.core)
                api(projects.app.model.core)

                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                api(kotlin("test-junit"))
                api(projects.app.db.core)
                api(projects.app.di.core)
                api(projects.app.model.core)

                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val androidMain by getting {
            dependencies {
                api(kotlin("test-junit"))

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
