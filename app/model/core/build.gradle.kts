plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.utils.bigdecimal)
                api(libs.kotlinx.serialization.core)

                implementation(projects.utils.files)
                implementation(libs.kotlinx.io.core)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.utils.bigdecimal)
                api(libs.kotlinx.serialization.core)

                implementation(projects.utils.files)
                implementation(libs.kotlinx.io.core)
            }
        }

        getByName("androidMain") {
            dependencies {
                api(libs.kotlinx.serialization.core)

                implementation(libs.kotlinx.io.core)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
