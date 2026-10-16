plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.jvm-android-shared-convention")
    id("moneymanager.native-convention")
}

// The one place that picks the Ktor engine for every HTTP client in the app: Ktor's curated per-platform
// defaults (OkHttp on JVM and Android, Curl on Linux, Darwin on Apple, WinHttp on Windows), found at
// runtime by the engine-less HttpClient(). Nothing compiles against them, so JVM and Android take them
// runtimeOnly; Kotlin/Native links only the klibs on a binary's compile classpath, so native needs
// implementation or the engine is left out of the executable.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.ktor.client.core)
            }
        }

        getByName("jvmAndroidMain") {
            dependencies {
                runtimeOnly(libs.ktor.client.engine.defaults)
            }
        }

        nativeMain {
            dependencies {
                implementation(libs.ktor.client.engine.defaults)
            }
        }
    }
}
