plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.pure-importer-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.csvimporter)
                api(projects.app.importengineapi)
                api(projects.app.model.core)

                implementation(libs.kmlogging)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.csvimporter)
                api(projects.app.importengineapi)
                api(projects.app.model.core)

                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(projects.app.strategies)
            }
        }
    }
}
