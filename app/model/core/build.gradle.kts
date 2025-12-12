plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
            implementation(libs.kmlogging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(projects.utils.bigdecimal)
        }
        }
    }
}
