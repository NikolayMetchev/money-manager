plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.metro.runtime)
                implementation(projects.app.db.core)
                implementation(projects.app.model.core)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.test.core)
            }
        }
    }
}
