plugins {
    id("moneymanager.android-convention")
    id("moneymanager.metro-convention")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.sqldelight.runtime)
                implementation(projects.shared)
                implementation(projects.sharedDatabase)
            }
        }
    }
}
