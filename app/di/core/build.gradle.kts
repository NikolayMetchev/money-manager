plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.model.core)
            }
        }

        val jvmMain by getting {
            dependencies {
                api(libs.metro.runtime)
                api(projects.app.db.core)
                api(projects.app.model.core)
            }
        }

        val androidMain by getting {
            dependencies {
                api(libs.metro.runtime)
            }
        }
    }
}
