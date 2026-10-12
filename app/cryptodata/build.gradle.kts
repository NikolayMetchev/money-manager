plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.jvm-android-shared-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

// Bundled offline crypto catalog (ticker -> display name), plus a network refresh service.
// The catalog is common code: the checked-in `data/coins.tsv` is compiled in as a generated constant
// (generateBundledCryptoDataset below), so no platform loads it as a resource. The refresh service uses
// the shared Ktor client from jvmAndroidMain. Depends on :app:model:core for CryptoCatalog/CryptoRegistry.Entry — never
// the reverse (model:core stays pure-domain and dependency-free of this module).
//
// buildHealth (KMP quirk): ABI deps used by jvmAndroidMain must be declared `api` on each real platform
// source set (jvmMain + androidMain); runtime-only deps live in jvmAndroidMain.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.model.core)

                implementation(projects.utils.files)
                implementation(libs.kotlinx.io.core)
            }
        }

        // JVM and Android share the entire catalog loader + refresh service.
        getByName("jvmAndroidMain") {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.core)
            }
        }

        jvmMain {
            dependencies {
                api(projects.app.model.core)
                api(libs.ktor.client.core)

                implementation(libs.kotlinx.serialization.core)
                implementation(libs.ktor.http)
            }
        }

        androidMain {
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

val generateBundledCryptoDataset =
    tasks.register<GenerateKotlinConstantsTask>("generateBundledCryptoDataset") {
        packageName.set("com.moneymanager.cryptodata")
        objectName.set("GeneratedCryptoDataset")
        constants.put("COINS_TSV", providers.fileContents(layout.projectDirectory.file("data/coins.tsv")).asText)
    }

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generateBundledCryptoDataset)
}
