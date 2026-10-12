plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    id("moneymanager.android-convention")
}

// App-level settings that live outside any database. Desktop JVM and native share one JSON file
// (FileLocalSettings), so the desktop app and the CLI see the same state; Android keeps SharedPreferences.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.io.core)

                implementation(projects.utils.files)
                implementation(libs.atomicfu)
                implementation(libs.kermit)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        androidMain {
            dependencies {
                implementation(libs.kermit.core)
                implementation(libs.kotlinx.serialization.core)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.kermit.core)
                implementation(libs.kotlinx.serialization.core)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
