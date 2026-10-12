plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
}

// Client for the central strategy catalog published on GitHub Pages: fetches the index.json manifest
// and per-strategy artifacts over HTTP, and classifies each catalog entry against the local
// StrategyLibrary (installed / not installed / update available). DB-free on purpose — all database
// interaction flows through the injected StrategyLibrary (the sole-writer engine underneath).
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.utils.localsettings)
                api(libs.kotlinx.coroutines.core)
                api(libs.ktor.client.core)

                implementation(projects.utils.files)
                implementation(projects.utils.httpclient)
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        // buildHealth (KMP quirk): ABI/impl deps used by commonMain must also be declared on each
        // real platform source set.
        jvmMain {
            dependencies {
                api(projects.app.model.csvstrategy)
                api(projects.utils.localsettings)
                api(libs.kotlinx.serialization.core)

                implementation(projects.app.model.core)
                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)
            }
        }

        androidMain {
            dependencies {
                api(projects.app.model.csvstrategy)
                api(projects.utils.localsettings)
                api(libs.kotlinx.serialization.core)

                implementation(projects.app.model.core)
                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.ktor.http)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(libs.ktor.http)
            }
        }
    }
}
