plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.model.core)
            }
        }
        val jvmMain by getting
        val androidMain by getting {
            dependencies {
                api(libs.androidx.test.core)
            }
        }
    }
}
