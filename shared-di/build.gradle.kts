plugins {
    id("moneymanager.coroutines-convention")
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.shared)
                implementation(libs.metro.runtime)
            }
        }
    }
}