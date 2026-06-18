plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.cryptography.core)
                implementation(libs.cryptography.random)
            }
        }

        // Shared JVM+Android source set: both expose java.util.zip for Deflate/Inflate.
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
        }

        jvmMain {
            dependsOn(jvmAndroidMain)
            dependencies {
                // Provider is resolved at runtime via CryptographyProvider.Default (no compile usage).
                runtimeOnly(libs.cryptography.provider.jdk)
            }
        }

        androidMain {
            dependsOn(jvmAndroidMain)
            dependencies {
                runtimeOnly(libs.cryptography.provider.jdk)
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
