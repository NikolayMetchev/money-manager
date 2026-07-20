plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.utils.bigdecimal)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.utils.bigdecimal)
                api(libs.kotlinx.serialization.core)
            }
        }

        getByName("androidMain") {
            dependencies {
                api(libs.kotlinx.serialization.core)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
