plugins {
    id("moneymanager.compose-multiplatform-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        jvmMain {
            dependencies {
                api(libs.androidx.compose.runtime.desktop)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        androidMain {
            dependencies {
                api(libs.androidx.compose.runtime)

                implementation(libs.androidx.activity)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.compose.ui)
            }
        }

        val androidDeviceTest by getting {
            dependencies {
                implementation(kotlin("test"))
                runtimeOnly(libs.androidx.test.runner)
            }
        }
    }
}
