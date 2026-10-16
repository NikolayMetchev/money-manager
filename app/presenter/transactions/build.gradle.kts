plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.mokkery)
}

// One account's transactions, paged both ways around an anchor (#944), with the selected row's detail. The TUI's
// Transactions tab renders it today; the desktop/Android transactions screen is to follow.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.model.core)
                api(projects.app.query)
                api(libs.androidx.compose.runtime)
                api(libs.kotlinx.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.mokkery.runtime)
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

        jvmTest {
            dependencies {
                implementation(projects.app.model.repository.read)
                implementation(libs.mokkery.core)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(projects.app.model.repository.read)
                implementation(libs.mokkery.core)
            }
        }
    }
}
