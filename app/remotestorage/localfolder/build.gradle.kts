plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

// A RemoteStorageProvider backed by a local (or OS-synced, e.g. a Dropbox/Drive desktop folder)
// directory. Useful in its own right and as an OAuth-free backend for testing the sync pipeline.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.remotestorage.core)
            }
        }

        // JVM and Android share a java.io.File implementation.
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        jvmMain {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(projects.app.remotestorage.core)
            }
        }

        androidMain {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(projects.app.remotestorage.core)
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
