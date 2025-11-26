plugins {
    id("moneymanager.android-convention")
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.metro.runtime)
                implementation(projects.sharedDatabase)
            }
        }
    }
}
