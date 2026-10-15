plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    alias(libs.plugins.compose.compiler)
}

// The accounts list's state (#942): filters over the AccountQuery snapshot. The desktop/Android Accounts
// screen and the TUI's Accounts tab both render this, so they keep the same accounts for the same filter.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.model.core)
                api(projects.app.query)
                api(libs.androidx.compose.runtime)
                api(libs.kotlinx.coroutines.core)

                implementation(projects.app.presenter.core)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.molecule.runtime)
                implementation(libs.turbine)
            }
        }

        jvmMain {
            dependencies {
                api(projects.app.model.core)
                api(projects.app.query)
                api(libs.androidx.compose.runtime.desktop)
            }
        }
    }
}
