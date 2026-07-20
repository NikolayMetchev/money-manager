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

                implementation(libs.kmlogging)
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

                implementation(projects.app.model.qif)
                implementation(projects.utils.bigdecimal)
                implementation(projects.utils.parsers.csv)
                implementation(projects.utils.parsers.qif)
                implementation(projects.utils.parsers.xlsx)
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        getByName("androidMain") {
            dependencies {
                api(projects.app.importfilesource.core)

                implementation(projects.utils.bigdecimal)
                implementation(projects.utils.parsers.csv)
                implementation(projects.utils.parsers.qif)
                implementation(projects.utils.parsers.xlsx)
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
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
                implementation(projects.utils.bigdecimal)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(projects.utils.bigdecimal)
            }
        }
    }
}
