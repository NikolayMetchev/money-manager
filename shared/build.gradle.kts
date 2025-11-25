plugins {
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
            implementation(libs.kmlogging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        }
    }
}
