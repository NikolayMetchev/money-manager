plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    id("moneymanager.pure-importer-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.csvimporter)
                api(projects.app.importengineapi)
                api(projects.app.model.accountmapping)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)

                implementation(projects.app.model.rules)
                implementation(libs.kermit)
                implementation(libs.kermit.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.csvimporter)
                api(projects.app.importengineapi)
                api(projects.app.model.accountmapping)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)

                implementation(libs.kermit.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(libs.kermit.core)
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
    }
}
