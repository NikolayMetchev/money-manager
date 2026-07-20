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

                implementation(projects.utils.localsettings)
                implementation(libs.kotlinx.coroutines.core)

                runtimeOnly(libs.kotlinx.coroutines.swing)
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

        getByName("androidDeviceTest") {
            dependencies {
                implementation(kotlin("test"))

                runtimeOnly(libs.androidx.test.runner)
            }
        }
    }
}
