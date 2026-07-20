plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

// Bundled offline crypto catalog (ticker -> display name), plus a network refresh service.
// The catalog and refresh run identically on JVM and Android from a single jvmAndroidMain source set
// (the resource loader uses classpath IO available on both; the refresh uses the shared Ktor client).
// The large `coins.tsv` dataset lives once under src/commonResources and is wired onto each platform's
// runtime resources below. Depends on :app:model:core for CryptoCatalog/CryptoRegistry.Entry — never
// the reverse (model:core stays pure-domain and dependency-free of this module).
//
// buildHealth (KMP quirk): ABI deps used by jvmAndroidMain must be declared `api` on each real platform
// source set (jvmMain + androidMain); runtime-only deps live in jvmAndroidMain.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.model.core)
            }
        }

        // JVM and Android share the entire catalog loader + refresh service.
        val jvmAndroidMain =
            create("jvmAndroidMain") {
                dependsOn(commonMain.get())
                dependencies {
                    implementation(libs.kotlinx.serialization.json)
                    implementation(libs.ktor.client.cio)
                    implementation(libs.ktor.client.core)
                    }
                    }

                    jvmMain {
                    dependsOn(jvmAndroidMain)
                    resources.srcDir("src/commonResources")
                    dependencies {
                    api(projects.app.model.core)
                    api(libs.ktor.client.core)

                    implementation(libs.kotlinx.serialization.core)
                    implementation(libs.ktor.http)
                }
        }

        androidMain {
            dependsOn(jvmAndroidMain)
            resources.srcDir("src/commonResources")
            dependencies {
                api(projects.app.model.core)
                api(libs.ktor.client.core)

                implementation(libs.kotlinx.serialization.core)
                implementation(libs.ktor.http)
            }
        }

        commonTest {
            dependencies {
                implementation(projects.app.model.core)
                implementation(kotlin("test"))
            }
        }
    }
}
