plugins {
    id("moneymanager.android-convention")
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.metro.runtime)
                implementation(projects.shared)
                implementation(projects.sharedDatabase)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.test.core)
            }
        }
    }
}
