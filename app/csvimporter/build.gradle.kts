plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.pure-importer-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.accountmapping)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.passthrough)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)
                api(libs.kotlinx.coroutines.core)

                implementation(libs.cryptography.core)
                implementation(libs.kermit)
                implementation(libs.kotlinx.datetime)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.importfilesource.core)
                api(projects.app.model.accountmapping)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.passthrough)
                api(projects.app.model.repository.read)
                api(libs.kotlinx.coroutines.core)

                implementation(projects.app.model.qif)
                implementation(projects.app.model.rules)
                implementation(projects.utils.bigdecimal)
                implementation(projects.utils.parsers.csv)
                implementation(projects.utils.parsers.qif)
                implementation(projects.utils.parsers.xlsx)
                implementation(libs.kermit.core)

                // Provider is resolved at runtime via CryptographyProvider.Default (no compile usage).
                runtimeOnly(libs.cryptography.provider.jdk)
            }
        }

        getByName("androidMain") {
            dependencies {
                api(projects.app.importfilesource.core)
                api(libs.kotlinx.coroutines.core)

                implementation(projects.app.model.rules)
                implementation(projects.utils.bigdecimal)
                implementation(projects.utils.parsers.csv)
                implementation(projects.utils.parsers.qif)
                implementation(projects.utils.parsers.xlsx)
                implementation(libs.kermit.core)

                runtimeOnly(libs.cryptography.provider.jdk)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(projects.app.strategies)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(projects.app.model.rules)
                implementation(projects.utils.bigdecimal)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(projects.app.model.rules)
                implementation(projects.utils.bigdecimal)
            }
        }
    }
}
