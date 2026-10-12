plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.jvm-android-shared-convention")
    id("moneymanager.native-convention")
}

// The one place that picks the Ktor engine for every HTTP client in the app. JVM and Android use CIO; each
// native target uses an engine backed by the OS (or libcurl) TLS stack and trust store, because CIO's TLS
// on Kotlin/Native is unproven.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.ktor.client.core)
            }
        }

        getByName("jvmAndroidMain") {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }

        linuxMain {
            dependencies {
                implementation(libs.ktor.client.curl)
            }
        }

        appleMain {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        mingwMain {
            dependencies {
                implementation(libs.ktor.client.winhttp)
            }
        }
    }
}
