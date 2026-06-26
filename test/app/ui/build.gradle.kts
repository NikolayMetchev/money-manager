plugins {
    id("moneymanager.compose-multiplatform-convention")
    alias(libs.plugins.compose.compiler)
}

// Shared Compose-UI test fixtures (the `runMoneyManagerComposeUiTest` harness) consumed by the
// commonTest source sets of the split-out `:app:ui:*` feature modules. The fixtures live in `main`
// source sets so other modules' tests can depend on them; the Compose UI-test framework is therefore
// exposed via `api`.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(libs.androidx.compose.ui.test)

                runtimeOnly(libs.kotlinx.coroutines.android)
                runtimeOnly(libs.kotlinx.coroutines.test)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(libs.compose.ui.test.desktop)

                runtimeOnly(libs.kotlinx.coroutines.test)
            }
        }
        getByName("jvmTest") {
            dependencies {
                runtimeOnly(libs.kotlinx.coroutines.test)
            }
        }
        getByName("androidHostTest") {
            dependencies {
                runtimeOnly(libs.kotlinx.coroutines.android)
                runtimeOnly(libs.kotlinx.coroutines.test)
            }
        }
        getByName("androidDeviceTest") {
            dependencies {
                runtimeOnly(libs.kotlinx.coroutines.android)
                runtimeOnly(libs.kotlinx.coroutines.test)
            }
        }
    }
}
