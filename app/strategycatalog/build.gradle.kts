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

        // buildHealth (KMP quirk): ABI/impl deps used by commonMain must also be declared on each
        // real platform source set. CIO itself is a pure runtime dependency — the engine-less
        // HttpClient() in createStrategyCatalogController discovers it from the runtime classpath.
        jvmMain {
            dependencies {
                api(libs.kotlinx.serialization.core)
                api(projects.app.model.core)

                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)

                runtimeOnly(libs.ktor.client.cio)
            }
        }

        androidMain {
            dependencies {
                api(libs.kotlinx.serialization.core)
                api(projects.app.model.core)

                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)

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
