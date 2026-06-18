plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

// Google Drive remote-storage backend. The Drive REST API + OAuth are spoken over the shared KMP Ktor
// client and a raw ServerSocket loopback receiver, so the provider runs identically on JVM and Android
// from a single jvmAndroidMain source set. The only platform-specific piece is opening the system
// browser for consent (BrowserLauncher), implemented in jvmMain/androidMain.
//
// buildHealth (KMP quirk): ABI project/library deps used by jvmAndroidMain must be declared as `api` on
// each real platform source set (jvmMain + androidMain); runtime-only deps live in jvmAndroidMain.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }

        // JVM and Android share the entire provider, OAuth, loopback receiver and account store.
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.cio)
            }
        }

        jvmMain {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(libs.kotlinx.serialization.core)
                api(libs.ktor.client.core)
                api(projects.app.remotestorage.core)
                api(projects.utils.localsettings)

                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)
            }
        }

        androidMain {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(libs.kotlinx.serialization.core)
                api(libs.ktor.client.core)
                api(projects.app.remotestorage.core)
                api(projects.utils.localsettings)

                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
