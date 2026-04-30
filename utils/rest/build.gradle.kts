plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.ktor.client.core)
                api(projects.app.model.core)
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

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
