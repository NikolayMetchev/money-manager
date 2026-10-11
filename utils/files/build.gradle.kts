plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    // Shared jvmAndroidMain source set: kotlinx-io has no last-modified time, and java.io.File does.
    id("moneymanager.jvm-android-shared-convention")
    id("moneymanager.native-convention")
}

// Plain-file helpers over kotlinx-io's SystemFileSystem, so JVM, Android and native share one
// implementation: home directory and `~` expansion, the app data directory, whole-file reads/writes,
// atomic replace, and last-modified time.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.io.core)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
