plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(kotlin("test-annotations-common"))
                api(kotlin("test-common"))
                api(projects.app.db.core)
                api(projects.app.di.core)
                api(projects.app.model.core)

                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                api(kotlin("test-junit"))

                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val androidMain by getting {
            dependencies {
                api(kotlin("test-junit"))
                api(libs.androidx.test.core)

                implementation(libs.sqldelight.android.driver)
            }
        }
    }
}
