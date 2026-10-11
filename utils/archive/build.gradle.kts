plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    // Shared jvmAndroidMain source set: both platforms expose java.util.zip for Deflate/Inflate.
    id("moneymanager.jvm-android-shared-convention")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.cryptography.core)
                implementation(libs.cryptography.random)
            }
        }

        jvmMain {
            dependencies {
                // Provider is resolved at runtime via CryptographyProvider.Default (no compile usage).
                runtimeOnly(libs.cryptography.provider.jdk)
            }
        }

        androidMain {
            dependencies {
                runtimeOnly(libs.cryptography.provider.jdk)
            }
        }

        nativeMain {
            dependencies {
                // openssl3 on every native target (Apple too): one provider covering PBKDF2, AES-GCM,
                // HMAC, ECDSA and EdDSA, statically linked so the CLI needs no system OpenSSL.
                implementation(libs.cryptography.provider.openssl3.prebuilt)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
