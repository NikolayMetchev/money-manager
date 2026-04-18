plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(projects.utils.bigdecimal)
            }
        }

        val jvmMain by getting {
            dependencies {
                api(libs.kotlinx.serialization.core)
                api(projects.utils.bigdecimal)
            }
        }

        val androidMain by getting {
            dependencies {
                api(libs.kotlinx.serialization.core)
            }
        }
    }
}
