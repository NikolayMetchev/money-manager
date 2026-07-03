plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

// Client for the central strategy catalog published on GitHub Pages: fetches the index.json manifest
// and per-strategy artifacts over HTTP, and classifies each catalog entry against the local
// StrategyLibrary (installed / not installed / update available). DB-free on purpose — all database
// interaction flows through the injected StrategyLibrary (the sole-writer engine underneath).
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.ktor.client.core)
                api(projects.app.model.core)

                implementation(libs.kotlinx.serialization.json)
            }
        }

        // The engine-less HttpClient() in createStrategyCatalogController discovers CIO from the
        // runtime classpath on both JVM and Android — a pure runtime dependency, no code references it.
        jvmMain {
            dependencies {
                runtimeOnly(libs.ktor.client.cio)
            }
        }

        androidMain {
            dependencies {
                runtimeOnly(libs.ktor.client.cio)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}
