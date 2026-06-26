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
                api(libs.compose.ui.test)
                api(libs.kotlinx.coroutines.core)
            }
        }
    }
}
