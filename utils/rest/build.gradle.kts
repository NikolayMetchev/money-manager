plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
            }
        }

        androidMain {
            dependencies {
                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)
            }
        }
    }
}
