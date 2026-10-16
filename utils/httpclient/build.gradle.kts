plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.jvm-android-shared-convention")
    id("moneymanager.native-convention")
}

// The one place that picks the Ktor engine for every HTTP client in the app: Ktor's curated per-platform
// defaults (OkHttp on JVM and Android, Curl on Linux, Darwin on Apple, WinHttp on Windows), found at
// runtime by the engine-less HttpClient().
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.ktor.client.core)
                implementation(libs.ktor.client.engine.defaults)
            }
        }
    }
}
