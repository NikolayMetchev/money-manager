plugins {
    id("moneymanager.compose-multiplatform-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {}
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        jvmMain {
            dependencies {
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.desktop)
                api(libs.compose.ui.desktop)
            }
        }

        androidMain {
            dependencies {
                api(libs.androidx.compose.foundation)
                api(libs.androidx.compose.runtime)
                api(libs.androidx.compose.ui)

                runtimeOnly(libs.kotlinx.coroutines.android)
            }
        }

        val androidDeviceTest by getting {
            dependencies {
                runtimeOnly(libs.kotlinx.coroutines.android)
            }
        }
    }
}
