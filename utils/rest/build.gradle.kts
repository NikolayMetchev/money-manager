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

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
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
                api(projects.app.model.core)

                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)
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
