plugins {
    id("moneymanager.metro-convention")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.sharedDatabase)
            }
        }
    }
}