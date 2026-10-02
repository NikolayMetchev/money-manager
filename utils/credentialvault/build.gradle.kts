plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

// The encrypted local credential vault (API tokens, Google sign-ins). Encryption is utils/archive's
// ArchiveCodec; file IO lives in jvmAndroidMain (java.nio on both platforms).
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.utils.localsettings)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.core)

                implementation(projects.utils.archive)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val jvmAndroidMain =
            create("jvmAndroidMain") {
                dependsOn(commonMain.get())
            }

        jvmMain {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(projects.utils.localsettings)
            }
        }

        androidMain {
            dependsOn(jvmAndroidMain)
        }

        commonTest {
            dependencies {
                implementation(projects.test.utils.credentialvault)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        jvmTest {
            dependencies {
                implementation(projects.utils.credentialvault)
            }
        }
    }
}
