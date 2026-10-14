plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(libs.ktor.client.core)

                implementation(projects.utils.httpclient)
                // HMAC/ECDSA signing for exchange APIs (Crypto.com/Binance/Kraken/Coinbase), same lib as utils/archive.
                implementation(libs.cryptography.core)
                implementation(libs.cryptography.random)
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
                implementation(libs.cryptography.bigint)
                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)

                // Provider is resolved at runtime via CryptographyProvider.Default (no compile usage).
                runtimeOnly(libs.cryptography.provider.jdk)
            }
        }

        nativeMain {
            dependencies {
                implementation(libs.cryptography.provider.openssl3.prebuilt)
            }
        }

        jvmMain {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.apistrategy)

                implementation(projects.app.model.core)
                implementation(libs.cryptography.bigint)
                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)

                runtimeOnly(libs.cryptography.provider.jdk)
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
