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
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.passthrough)
                api(projects.app.model.repository.write)

                implementation(libs.kotlinx.coroutines.core)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(projects.app.model.accountmapping)
                implementation(projects.app.model.apistrategy)
                implementation(projects.app.model.csvstrategy)
                implementation(projects.app.model.importdirectory)
                implementation(projects.utils.bigdecimal)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.repository.write)

                implementation(projects.app.model.accountmapping)
                implementation(projects.app.model.apistrategy)
                implementation(projects.app.model.csvstrategy)
                implementation(projects.app.model.importdirectory)
                implementation(projects.app.model.passthrough)
                implementation(projects.utils.bigdecimal)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
