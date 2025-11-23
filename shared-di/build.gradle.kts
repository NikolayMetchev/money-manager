plugins {
    id("moneymanager.android-convention")
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.sharedDatabase)
                implementation(libs.metro.runtime)
            }
        }
    }
}