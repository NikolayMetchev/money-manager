plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

// The encrypted local credential vault (API tokens, Google sign-ins). Encryption is utils/archive's
// ArchiveCodec; file IO is utils/files (kotlinx-io), shared by every platform.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.utils.localsettings)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.core)

                implementation(projects.utils.archive)
                implementation(projects.utils.files)
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        jvmMain {
            dependencies {
                api(projects.utils.localsettings)
            }
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
