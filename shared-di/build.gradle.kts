plugins {
    id("moneymanager.coroutines-convention")
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(libs.metro.runtime)
            }
        }
    }
}