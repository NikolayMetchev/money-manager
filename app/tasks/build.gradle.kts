plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    id("moneymanager.pure-importer-convention")
}

// Progress reporting and background tasks, free of Compose and the database so the desktop app,
// Android and the native CLI share one implementation.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)

                implementation(libs.kermit)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        getByName("jvmMain") {
            dependencies {
                implementation(libs.kermit.core)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(libs.kermit.core)
            }
        }
    }
}
